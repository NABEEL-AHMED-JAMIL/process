package process.security;

import process.util.BusinessTime;
import org.barco.platform.correlation.CorrelationId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.repository.JobQueueRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One token per run: minted at dispatch, good for that run and attempt, gone when the run ends.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
public class RunCallbackTokensTest {

    private static final long JOB = 2410L;
    private static final long RUN = 88123L;
    private static final String LEGACY = "the-old-shared-secret";

    @Mock private JobQueueRepository jobQueueRepository;

    private RunCallbackTokens tokens;
    private JobQueue run;

    @BeforeEach
    void setUp() {
        this.tokens = new RunCallbackTokens(this.jobQueueRepository, 24, LEGACY);
        this.run = new JobQueue();
        this.run.setJobQueueId(RUN);
        this.run.setJobId(JOB);
        this.run.setAttempt(1);
        lenient().when(this.jobQueueRepository.findById(RUN)).thenReturn(Optional.of(this.run));
        lenient().when(this.jobQueueRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void issuingStoresOnlyAHashAndTheTokenVerifiesForItsOwnRun() {
        String token = this.tokens.issue(this.run);

        assertThat(token).startsWith("cbt_1." + RUN + ".");
        assertThat(this.run.getCallbackTokenHash()).isNotEqualTo(token).hasSize(64);
        assertThat(this.run.getCallbackTokenAttempt()).isEqualTo(1);
        assertThat(this.run.getCallbackTokenExpiresAt()).isAfter(BusinessTime.now().plusHours(23));
        verify(this.jobQueueRepository).save(this.run);

        assertThat(this.tokens.verify(JOB, RUN, token)).isEmpty();
        assertThat(this.tokens.verify(JOB, RUN, "  " + token + " ")).isEmpty();
    }

    @Test
    void aTokenIsBoundToItsRunAndItsJob() {
        String token = this.tokens.issue(this.run);
        JobQueue other = new JobQueue(); other.setJobQueueId(RUN + 1); other.setJobId(JOB); other.setAttempt(1);
        String othersToken = this.tokens.issue(other);
        when(this.jobQueueRepository.findById(999999L)).thenReturn(Optional.empty());

        // Another run's token does not open this one, and the right token with the wrong job fails.
        assertThat(this.tokens.verify(JOB, RUN, othersToken)).contains(RunCallbackTokens.Refusal.MISMATCH);
        assertThat(this.tokens.verify(JOB + 1, RUN, token)).contains(RunCallbackTokens.Refusal.WRONG_JOB);
        assertThat(this.tokens.verify(JOB, 999999L, token)).contains(RunCallbackTokens.Refusal.NO_SUCH_RUN);
        assertThat(this.tokens.verify(JOB, RUN, null)).contains(RunCallbackTokens.Refusal.MISMATCH);
        assertThat(this.tokens.verify(JOB, RUN, "")).contains(RunCallbackTokens.Refusal.MISMATCH);
    }

    /** A retry re-uses the row and mints afresh: the earlier attempt's token stops working. */
    @Test
    void aRetryReplacesTheToken() {
        String first = this.tokens.issue(this.run);
        this.run.setAttempt(2);
        String second = this.tokens.issue(this.run);

        assertThat(second).startsWith("cbt_2.").isNotEqualTo(first);
        assertThat(this.run.getCallbackTokenAttempt()).isEqualTo(2);
        assertThat(this.tokens.verify(JOB, RUN, first)).contains(RunCallbackTokens.Refusal.MISMATCH);
        assertThat(this.tokens.verify(JOB, RUN, second)).isEmpty();
    }

    /**
     * The end of a run is not always the worker's doing: a cancel from the screen or a run the
     * dispatcher could not send marks it Failed without passing through retire(). The token of
     * such a run must be as dead as one that was retired -- ghost log lines on a finished run
     * are what the old shared secret allowed.
     */
    @Test
    void aTokenOfARunThatEndedWithoutRetireIsRefused() {
        String token = this.tokens.issue(this.run);
        for (JobStatus over : new JobStatus[] {JobStatus.Failed, JobStatus.Completed, JobStatus.Skip, JobStatus.Interrupt, JobStatus.Missed}) {
            this.run.setJobStatus(over);
            assertThat(this.tokens.verify(JOB, RUN, token)).as(over.name()).contains(RunCallbackTokens.Refusal.RUN_OVER);
        }
        for (JobStatus live : new JobStatus[] {JobStatus.Queue, JobStatus.Start, JobStatus.Running}) {
            this.run.setJobStatus(live);
            assertThat(this.tokens.verify(JOB, RUN, token)).as(live.name()).isEmpty();
        }
    }

    /** The legacy secret does not open a finished run either. */
    @Test
    void theLegacySecretDoesNotOpenAFinishedRun() {
        this.run.setJobStatus(JobStatus.Failed);
        assertThat(this.tokens.verify(JOB, RUN, LEGACY)).contains(RunCallbackTokens.Refusal.RUN_OVER);
    }

    @Test
    void anExpiredTokenIsRefusedEvenWhenItMatches() {
        String token = this.tokens.issue(this.run);
        this.run.setCallbackTokenExpiresAt(BusinessTime.now().minusMinutes(1));
        assertThat(this.tokens.verify(JOB, RUN, token)).contains(RunCallbackTokens.Refusal.EXPIRED);
    }

    @Test
    void retiringSpendsTheTokenForCallbacksAndKeepsItForReportsForADay() {
        String token = this.tokens.issue(this.run);
        this.run.setJobStatus(JobStatus.Completed);
        this.tokens.retire(RUN);

        // The hash stays: a report can still prove it is this run's...
        assertThat(this.run.getCallbackTokenHash()).isNotNull();
        assertThat(this.run.getCallbackTokenExpiresAt()).isAfter(BusinessTime.now().plusHours(23));
        assertThat(this.tokens.verifyForReport(JOB, RUN, token)).isEmpty();
        // ...while a callback on the finished run is refused whatever it carries.
        assertThat(this.tokens.verify(JOB, RUN, token)).contains(RunCallbackTokens.Refusal.RUN_OVER);
    }

    /** A run dispatched before tokens existed carries no hash; the shared secret still proves it. */
    @Test
    void aRunWithoutATokenAcceptsTheLegacySecretWhileItIsConfigured() {
        assertThat(this.tokens.verify(JOB, RUN, LEGACY)).isEmpty();
        assertThat(this.tokens.verify(JOB, RUN, "not-it")).contains(RunCallbackTokens.Refusal.NOT_ISSUED);

        RunCallbackTokens noLegacy = new RunCallbackTokens(this.jobQueueRepository, 24, "  ");
        assertThat(noLegacy.verify(JOB, RUN, LEGACY)).contains(RunCallbackTokens.Refusal.NOT_ISSUED);
    }

    /** The legacy secret never opens a run that HAS a token: the token is the only proof then. */
    @Test
    void theLegacySecretDoesNotOpenATokenedRun() {
        this.tokens.issue(this.run);
        assertThat(this.tokens.verify(JOB, RUN, LEGACY)).contains(RunCallbackTokens.Refusal.MISMATCH);
    }

    @Test
    void retiringARunThatNeverHadATokenWritesNothing() {
        this.tokens.retire(RUN);
        verify(this.jobQueueRepository, never()).save(any());
        this.tokens.retire(null);
    }

    /**
     * A usage report may arrive after the run is over -- the worker reports as it closes, and a
     * batch spooled through a meter outage comes with the next run. The token's own expiry
     * still bounds it; a wrong token is still a wrong token.
     */
    @Test
    void aReportIsAcceptedForARunThatIsOverUntilTheTokenExpires() {
        String token = this.tokens.issue(this.run);
        this.run.setJobStatus(JobStatus.Completed);

        assertThat(this.tokens.verify(JOB, RUN, token)).contains(RunCallbackTokens.Refusal.RUN_OVER);
        assertThat(this.tokens.verifyForReport(JOB, RUN, token)).isEmpty();
        assertThat(this.tokens.verifyForReport(JOB, RUN, "not-it")).contains(RunCallbackTokens.Refusal.MISMATCH);

        this.run.setCallbackTokenExpiresAt(BusinessTime.now().minusMinutes(1));
        assertThat(this.tokens.verifyForReport(JOB, RUN, token)).contains(RunCallbackTokens.Refusal.EXPIRED);
    }

    // ---- MIG-18 and MIG-63: a refusal that names the run's state implies the run's own token ------------------

    /**
     * RUN_OVER and EXPIRED are only ever said to a caller holding the run's own token; anyone else is
     * told MISMATCH (or NOT_ISSUED) whatever state the run is in. That is what lets the controller answer
     * a finished run's redelivered callback from its receipt, and lets the stall sweep act on a
     * refused-but-genuine report, without either being reachable by a caller who merely knows an id.
     */
    @Test
    void aRunIsOnlyReportedOverOrExpiredToItsOwnToken() {
        String token = this.tokens.issue(this.run);
        String someoneElses = new RunCallbackTokens(this.jobQueueRepository, 24, LEGACY).issue(new JobQueue());
        this.run.setJobStatus(JobStatus.Completed);

        assertThat(this.tokens.verify(JOB, RUN, someoneElses)).contains(RunCallbackTokens.Refusal.MISMATCH);
        assertThat(this.tokens.verify(JOB, RUN, null)).contains(RunCallbackTokens.Refusal.MISMATCH);
        assertThat(this.tokens.verify(JOB, RUN, token)).contains(RunCallbackTokens.Refusal.RUN_OVER);

        this.run.setJobStatus(JobStatus.Running);
        this.run.setCallbackTokenExpiresAt(BusinessTime.now().minusMinutes(1));
        assertThat(this.tokens.verify(JOB, RUN, someoneElses)).contains(RunCallbackTokens.Refusal.MISMATCH);
        assertThat(this.tokens.verify(JOB, RUN, token)).contains(RunCallbackTokens.Refusal.EXPIRED);
    }

    @Test
    void aFinishedRunWithoutATokenIsOverOnlyToTheLegacySecret() {
        this.run.setJobStatus(JobStatus.Failed);
        assertThat(this.tokens.verify(JOB, RUN, "not-it")).contains(RunCallbackTokens.Refusal.NOT_ISSUED);
        assertThat(this.tokens.verify(JOB, RUN, LEGACY)).contains(RunCallbackTokens.Refusal.RUN_OVER);
    }

    // ---- MIG-95: the correlation id rides the token's write -------------------------------------------------

    /** Stamped in the very save that stores the hash: one write, so the two can never disagree. */
    @Test
    void theCorrelationIdIsWrittenInTheSameSaveAsTheHash() {
        this.tokens.issue(this.run);

        ArgumentCaptor<JobQueue> saved = ArgumentCaptor.forClass(JobQueue.class);
        verify(this.jobQueueRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getCallbackTokenHash()).isNotNull();
        assertThat(CorrelationId.isAcceptable(saved.getValue().getCorrelationId())).isTrue();
    }

    @Test
    void theDispatchsBoundIdIsTheOneStamped() {
        CorrelationId.set("corr-bound-at-dispatch");
        try {
            this.tokens.issue(this.run);
        } finally {
            CorrelationId.clear();
        }
        assertThat(this.run.getCorrelationId()).isEqualTo("corr-bound-at-dispatch");
    }

    /** A retry re-mints the token and keeps the id: it is the same piece of work. */
    @Test
    void aRetryKeepsTheIdOfItsFirstDispatch() {
        this.tokens.issue(this.run);
        String first = this.run.getCorrelationId();
        this.run.setAttempt(2);

        this.tokens.issue(this.run);

        assertThat(this.run.getCorrelationId()).isEqualTo(first);
    }

    @Test
    void theRunsIdIsReadBackForItsCallbacks() {
        this.run.setCorrelationId("corr-dispatch-88123");

        assertThat(this.tokens.correlationOf(RUN).map(found -> found.correlationId)).contains("corr-dispatch-88123");
        assertThat(this.tokens.correlationOf(null)).isEmpty();
    }
}

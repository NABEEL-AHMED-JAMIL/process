package process.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.pojo.JobQueue;
import process.model.repository.JobQueueRepository;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
        assertThat(this.run.getCallbackTokenExpiresAt()).isAfter(LocalDateTime.now().plusHours(23));
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

    @Test
    void anExpiredTokenIsRefusedEvenWhenItMatches() {
        String token = this.tokens.issue(this.run);
        this.run.setCallbackTokenExpiresAt(LocalDateTime.now().minusMinutes(1));
        assertThat(this.tokens.verify(JOB, RUN, token)).contains(RunCallbackTokens.Refusal.EXPIRED);
    }

    @Test
    void retiringSpendsTheTokenSoAReplayFindsNothing() {
        String token = this.tokens.issue(this.run);
        this.tokens.retire(RUN);

        assertThat(this.run.getCallbackTokenHash()).isNull();
        assertThat(this.run.getCallbackTokenExpiresAt()).isNull();
        // With no hash, only the legacy secret would be accepted -- and the run's token is not it.
        assertThat(this.tokens.verify(JOB, RUN, token)).contains(RunCallbackTokens.Refusal.NOT_ISSUED);
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
}

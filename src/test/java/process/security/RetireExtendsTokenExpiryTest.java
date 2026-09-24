package process.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import process.api.NotifyResetApi;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.repository.JobQueueRepository;
import process.model.service.NotifyService;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C5 (MIG-138): retire() EXTENDS the callback token's expiry rather than clearing its hash.
 *
 * "Revoke the credential when the run ends" is what a security reviewer will ask for, and it silently
 * deletes billable usage. Verbatim from RunCallbackTokens.retire: "Clearing the hash here used to make
 * every late report NOT_ISSUED, which is a lost line on the bill, not a defence." Two gates, not one:
 * the run's STATUS (the OVER set) stops callbacks; the token's EXPIRY stops usage reports. Collapsing
 * them into one loses billable usage. REPORT_GRACE_HOURS = 24 deliberately outlasts the dispatch budget.
 *
 * Identity & Tenancy will own this token; these are the nine assertions of 16-testing-strategy 2.4 C5.
 *
 * The clock: RunCallbackTokens reads LocalDateTime.now() with no seam. "+24h" is asserted by
 * bracketing the call; "+23h59m" and "+24h01m" are reached by moving the stored expiry back by that
 * much, which is the same comparison verify makes when the clock moves forward instead.
 */
@ExtendWith(MockitoExtension.class)
class RetireExtendsTokenExpiryTest {

    private static final long JOB = 2410L;
    private static final long RUN = 88123L;

    @Mock private JobQueueRepository jobQueueRepository;

    private RunCallbackTokens tokens;
    private JobQueue run;
    private ListAppender<ILoggingEvent> log;
    private Logger logger;

    @BeforeEach
    void setUp() {
        this.tokens = new RunCallbackTokens(this.jobQueueRepository, 24, "");
        this.run = new JobQueue();
        this.run.setJobQueueId(RUN);
        this.run.setJobId(JOB);
        this.run.setAttempt(1);
        this.run.setJobStatus(JobStatus.Queue);
        lenient().when(this.jobQueueRepository.findById(RUN)).thenReturn(Optional.of(this.run));
        lenient().when(this.jobQueueRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        this.logger = (Logger) LoggerFactory.getLogger(RunCallbackTokens.class);
        this.logger.setLevel(Level.DEBUG);
        this.log = new ListAppender<>();
        this.log.start();
        this.logger.addAppender(this.log);
    }

    @AfterEach
    void detach() {
        this.logger.detachAppender(this.log);
        this.logger.setLevel(null);
    }

    private static void assertWithin(LocalDateTime actual, LocalDateTime from, LocalDateTime to) {
        assertThat(actual.isBefore(from) || actual.isAfter(to))
            .as("%s should lie between %s and %s", actual, from, to).isFalse();
    }

    /** The run ended three hours after dispatch, as the worker's Completed arrives. */
    private String dispatchedThreeHoursAgoAndNowOver(JobStatus outcome) {
        String token = this.tokens.issue(this.run);
        this.run.setCallbackTokenExpiresAt(this.run.getCallbackTokenExpiresAt().minusHours(3));
        this.run.setJobStatus(outcome);
        return token;
    }

    // ---- 1 and 2. the hash stays; the expiry moves out ------------------------------------------------

    @Test
    void retiringKeepsTheHashExactlyAsItWas() {
        this.dispatchedThreeHoursAgoAndNowOver(JobStatus.Completed);
        String hashBefore = this.run.getCallbackTokenHash();

        this.tokens.retire(RUN);

        assertThat(this.run.getCallbackTokenHash()).isNotNull().isEqualTo(hashBefore);
        verify(this.jobQueueRepository, times(2)).save(this.run);
    }

    @Test
    void retiringSetsTheExpiryToTwentyFourHoursFromNowWhichOutlastsTheDispatchBudget() {
        this.dispatchedThreeHoursAgoAndNowOver(JobStatus.Completed);
        LocalDateTime budgetExpiry = this.run.getCallbackTokenExpiresAt();

        LocalDateTime before = LocalDateTime.now();
        this.tokens.retire(RUN);
        LocalDateTime after = LocalDateTime.now();

        assertThat(RunCallbackTokens.REPORT_GRACE_HOURS).isEqualTo(24);
        assertWithin(this.run.getCallbackTokenExpiresAt(), before.plusHours(24), after.plusHours(24));
        assertThat(this.run.getCallbackTokenExpiresAt()).isAfter(budgetExpiry);
    }

    // ---- 3 and 4. the no-ops ---------------------------------------------------------------------------

    @Test
    void retiringNothingIsANoOp() {
        this.tokens.retire(null);

        verifyNoInteractions(this.jobQueueRepository);
        assertThat(this.log.list).isEmpty();
    }

    /** A run dispatched before tokens existed (pre-V42): left exactly as it was, and nothing said. */
    @Test
    void aRunThatNeverHadATokenReturnsEarlyWithoutSavingOrLogging() {
        this.run.setCallbackTokenHash(null);

        this.tokens.retire(RUN);

        verify(this.jobQueueRepository, never()).save(any());
        assertThat(this.run.getCallbackTokenExpiresAt()).isNull();
        assertThat(this.log.list).isEmpty();
    }

    /** The control for the two above: a real retire is saved, and says so once, at DEBUG. */
    @Test
    void aRealRetireIsSavedAndLoggedOnceAtDebug() {
        this.dispatchedThreeHoursAgoAndNowOver(JobStatus.Completed);

        this.tokens.retire(RUN);

        assertThat(this.log.list).hasSize(1);
        assertThat(this.log.list.get(0).getLevel()).isEqualTo(Level.DEBUG);
    }

    // ---- 5. callbacks stop on status ------------------------------------------------------------------

    @Test
    void theRunsThatCallbacksCanNoLongerTouchAreExactlyTheFiveOverStates() throws Exception {
        Field over = RunCallbackTokens.class.getDeclaredField("OVER");
        over.setAccessible(true);
        assertThat(over.get(null)).isEqualTo(EnumSet.of(JobStatus.Failed, JobStatus.Completed, JobStatus.Skip,
            JobStatus.Interrupt, JobStatus.Missed));

        for (JobStatus outcome : EnumSet.of(JobStatus.Failed, JobStatus.Completed, JobStatus.Skip,
                JobStatus.Interrupt, JobStatus.Missed)) {
            String token = this.dispatchedThreeHoursAgoAndNowOver(outcome);
            this.tokens.retire(RUN);
            assertThat(this.tokens.verify(JOB, RUN, token)).as("callback on a %s run", outcome)
                .contains(RunCallbackTokens.Refusal.RUN_OVER);
        }
    }

    // ---- 6 and 7. reports stop on expiry --------------------------------------------------------------

    @Test
    void aUsageReportAfterRetireIsAcceptedForADay() {
        String token = this.dispatchedThreeHoursAgoAndNowOver(JobStatus.Completed);
        this.tokens.retire(RUN);

        assertThat(this.tokens.verifyForReport(JOB, RUN, token)).as("straight after").isEmpty();
        this.run.setCallbackTokenExpiresAt(this.run.getCallbackTokenExpiresAt().minusHours(23).minusMinutes(59));
        assertThat(this.tokens.verifyForReport(JOB, RUN, token)).as("at +23h59m").isEmpty();
    }

    @Test
    void aUsageReportTwentyFourHoursAndAMinuteAfterRetireIsRefusedOnExpiry() {
        String token = this.dispatchedThreeHoursAgoAndNowOver(JobStatus.Completed);
        this.tokens.retire(RUN);

        this.run.setCallbackTokenExpiresAt(this.run.getCallbackTokenExpiresAt().minusHours(24).minusMinutes(1));

        assertThat(this.tokens.verifyForReport(JOB, RUN, token)).contains(RunCallbackTokens.Refusal.EXPIRED);
    }

    // ---- 8. constant time, by construction ---------------------------------------------------------------

    /**
     * Asserted at the call site, not by timing. Every comparison of a presented secret goes through
     * constantTimeEquals, which is MessageDigest.isEqual over the bytes; a test -- or a refactor --
     * that swaps in String.equals has changed the behaviour.
     */
    @Test
    void everySecretIsComparedInConstantTime() throws Exception {
        String source = new String(Files.readAllBytes(Paths.get(
            "src/main/java/process/security/RunCallbackTokens.java")), StandardCharsets.UTF_8);

        Matcher helper = Pattern.compile("private static boolean constantTimeEquals\\(String a, String b\\) \\{\\s*"
            + "return MessageDigest\\.isEqual\\(a\\.getBytes\\(StandardCharsets\\.UTF_8\\), "
            + "b\\.getBytes\\(StandardCharsets\\.UTF_8\\)\\);\\s*}").matcher(source);
        assertThat(helper.find()).as("constantTimeEquals is MessageDigest.isEqual over UTF-8 bytes").isTrue();
        assertThat(source).contains("constantTimeEquals(run.getCallbackTokenHash(), sha256(token))");
        assertThat(source).contains("constantTimeEquals(this.legacyToken, token)");
        assertThat(source).doesNotContain(".equals(token)").doesNotContain("Hash().equals(")
            .doesNotContain(".equals(sha256(").doesNotContain("legacyToken.equals(");
        assertThat(RunCallbackTokens.sha256("cbt_1.1.x")).matches("[0-9a-f]{64}");
    }

    // ---- 9. the token's shape, and its header ----------------------------------------------------------

    @Test
    void theTokenIsPrefixAttemptRunIdAndThirtyTwoRandomBytes() {
        this.run.setAttempt(0);
        String first = this.tokens.issue(this.run);
        this.run.setAttempt(3);
        String third = this.tokens.issue(this.run);

        // Base64url of 32 bytes, unpadded, is 43 characters. An attempt below 1 is written as 1.
        assertThat(first).matches("cbt_1\\.88123\\.[A-Za-z0-9_-]{43}");
        assertThat(third).matches("cbt_3\\.88123\\.[A-Za-z0-9_-]{43}");
        assertThat(this.run.getCallbackTokenHash()).isEqualTo(RunCallbackTokens.sha256(third));
    }

    /** required = false: a missing header reaches the token check and is a clean 401, not Spring's 400. */
    @Test
    void theHeaderIsXWorkerTokenAndAMissingOneIsA401() throws Exception {
        Field header = NotifyResetApi.class.getDeclaredField("WORKER_TOKEN_HEADER");
        header.setAccessible(true);
        assertThat(header.get(null)).isEqualTo("X-Worker-Token");
        int declared = 0;
        for (Method method : NotifyResetApi.class.getDeclaredMethods()) {
            for (Annotation[] parameter : method.getParameterAnnotations()) {
                for (Annotation annotation : parameter) {
                    // Idempotency-Key (MIG-18) and X-Correlation-Id (MIG-95) sit beside it; the token is
                    // the one header that authenticates, and each callback reads it exactly once.
                    if (annotation instanceof RequestHeader && "X-Worker-Token".equals(((RequestHeader) annotation).value())) {
                        declared++;
                        assertThat(((RequestHeader) annotation).required()).as(method.getName()).isFalse();
                    }
                }
            }
        }
        assertThat(declared).as("changeState, addLogs and addLogsBatch").isEqualTo(3);

        this.tokens.issue(this.run);
        this.run.setJobStatus(JobStatus.Running);
        NotifyService notifyService = mock(NotifyService.class);
        NotifyResetApi api = new NotifyResetApi(notifyService, this.tokens);
        SourceJobQueueDto body = new SourceJobQueueDto();
        body.setJobStatusMessage("working");

        ResponseEntity<?> response = api.changeState(JOB, RUN, JobStatus.Running, null, null, null, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(notifyService);
    }

    // ---- a retire that does not end the run ----------------------------------------------------------------

    /**
     * NotifyResetApi retires on any Failed that is not answered ERROR -- including one the service
     * answered by scheduling a retry, which puts the run back in Queue. Queue is not OVER, so the
     * attempt that just failed keeps a working callback token, now good for 24 more hours, until the
     * retry's dispatch mints a new one. The C7 table blocks every status change from Queue that the
     * API accepts, but addLogs and addLogsBatch still land on the re-queued run. Pinned, not endorsed.
     */
    @Test
    @Tag("pinned-unreviewed")
    void aTokenRetiredOnARunRequeuedForRetryStillOpensCallbacksUntilTheRetryIsDispatched() {
        String token = this.tokens.issue(this.run);
        this.run.setJobStatus(JobStatus.Running);
        NotifyService notifyService = mock(NotifyService.class);
        when(notifyService.changeState(any(SourceJobQueueDto.class), any())).thenAnswer(invocation -> {
            this.run.setJobStatus(JobStatus.Queue);
            // The service's own answer to a retried Failed: a message and the DTO, and no status.
            Object echoed = invocation.getArgument(0);
            return new ResponseDto("Job 2410 run failed and has been queued for another attempt.", echoed);
        });
        SourceJobQueueDto body = new SourceJobQueueDto();
        body.setJobStatusMessage("source refused the connection");
        LocalDateTime before = LocalDateTime.now();

        ResponseEntity<?> response = new NotifyResetApi(notifyService, this.tokens)
            .changeState(JOB, RUN, JobStatus.Failed, token, null, null, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(this.run.getJobStatus()).isEqualTo(JobStatus.Queue);
        assertThat(this.run.getCallbackTokenExpiresAt()).isAfterOrEqualTo(before.plusHours(24));
        assertThat(this.tokens.verify(JOB, RUN, token)).as("the failed attempt's token, on the re-queued run").isEmpty();
    }
}

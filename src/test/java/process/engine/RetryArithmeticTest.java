package process.engine;

import process.util.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.TestNotifications;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * C7c (MIG-141): the retry arithmetic, pinned as a table, and that a retry RE-USES the run's row.
 *
 * BulkAction.scheduleRetry: multiplier = 1L << Math.min(attempt - 1, 20);
 * backoffSeconds = Math.min(base * multiplier, 3600). With base 60 and five attempts the waits are
 * 60, 120, 240 and 480 seconds, and the fifth failure is final. Both bounds are asserted where they
 * bite: the 3600-second ceiling at its edge, and the shift clamp at the attempts where an unclamped
 * shift would wrap (1L << 63 is negative, 1L << 64 is 1) -- at attempt 21 the ceiling alone already
 * hides it.
 *
 * The row, verbatim from the method: "The row is re-used rather than replaced, so the retry continues
 * to occupy the single in-flight slot its job is allowed -- two attempts of one job running at once
 * would have two workers writing the same output folder." And the write clears job_send and end_time,
 * without which the pick-up query never takes the row again.
 *
 * The clock: scheduleRetry reads BusinessTime.now() itself, so each wait is asserted exactly by
 * bracketing -- next_attempt_at minus the expected wait must lie between the instants read either
 * side of the call.
 */
@ExtendWith(MockitoExtension.class)
class RetryArithmeticTest {

    private static final long JOB_ID = 2420L;
    private static final long QUEUE_ID = 77L;

    @Mock private TransactionServiceImpl transactionService;

    private BulkAction bulkAction;

    @BeforeEach
    void setUp() {
        this.bulkAction = new BulkAction(this.transactionService, TestNotifications.recording(
            mock(TestNotifications.FeedSink.class), null, null));
    }

    private JobQueue storedRun(int attempt) {
        JobQueue row = new JobQueue();
        row.setJobQueueId(QUEUE_ID);
        row.setJobId(JOB_ID);
        row.setAttempt(attempt);
        row.setJobStatus(JobStatus.Running);
        row.setJobSend(true);
        row.setEndTime(BusinessTime.now());
        row.setOutputFolder("etl-demo/out/2420");
        return row;
    }

    private void given(Integer maxAttempts, Integer backoffSeconds, JobQueue stored) {
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID);
        job.setMaxAttempts(maxAttempts);
        job.setRetryBackoffSeconds(backoffSeconds);
        lenient().when(this.transactionService.findByJobId(JOB_ID)).thenReturn(Optional.of(job));
        lenient().when(this.transactionService.findJobQueueByJobQueueId(QUEUE_ID)).thenReturn(Optional.of(stored));
    }

    /** The wait scheduleRetry chose, to the second: -1 when it declined. */
    private long waitChosen(int attempt, Integer maxAttempts, Integer base) {
        JobQueue stored = this.storedRun(attempt);
        this.given(maxAttempts, base, stored);
        LocalDateTime before = BusinessTime.now();
        boolean retried = this.bulkAction.scheduleRetry(QUEUE_ID, JOB_ID, "connection reset");
        LocalDateTime after = BusinessTime.now();
        if (!retried) {
            return -1;
        }
        LocalDateTime dueAt = stored.getNextAttemptAt();
        for (long seconds = Duration.between(after, dueAt).getSeconds();
             seconds <= Duration.between(before, dueAt).getSeconds() + 1; seconds++) {
            LocalDateTime readAt = dueAt.minusSeconds(seconds);
            if (!readAt.isBefore(before) && !readAt.isAfter(after)) {
                return seconds;
            }
        }
        throw new AssertionError("next_attempt_at " + dueAt + " is not a whole number of seconds after the call");
    }

    // ---- the table ---------------------------------------------------------------------------------

    @ParameterizedTest(name = "base 60, attempt {0} of 5 -> {1}")
    @CsvSource({
        "1, 60",
        "2, 120",
        "3, 240",
        "4, 480",
        "5, -1",   // the fifth failure is the last: no sixth attempt
    })
    void theDefaultSequenceIsSixtyDoublingThenExhausted(int attempt, long expected) {
        assertThat(this.waitChosen(attempt, 5, 60)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "base {0}, attempt {1} -> {2}")
    @CsvSource({
        "1800, 1, 1800",
        "1800, 2, 3600",   // exactly at the ceiling
        "1801, 2, 3600",   // two seconds over, cut back
        "3600, 1, 3600",   // the largest base the column allows, at once
        "60,   6, 1920",
        "60,   7, 3600",   // 3840, cut back
    })
    void theWaitIsCappedAtAnHour(int base, int attempt, long expected) {
        assertThat(this.waitChosen(attempt, 100, base)).isEqualTo(expected);
    }

    /**
     * The clamp at 20. max_attempts is capped at ten by a CHECK constraint in another table; the
     * arithmetic does not rely on it. Unclamped, attempt 64 would shift into the sign bit and 65
     * would wrap to a multiplier of one -- a sixty-second wait after an hour's.
     */
    @ParameterizedTest(name = "attempt {0} -> 3600")
    @CsvSource({"21", "22", "64", "65", "1000"})
    void theShiftIsClampedSoTheWaitCanNeverWrap(int attempt) {
        assertThat(this.waitChosen(attempt, Integer.MAX_VALUE, 60)).isEqualTo(3600);
    }

    @Test
    void anUnconfiguredJobGetsOneAttemptAndASixtySecondBase() {
        assertThat(this.waitChosen(1, null, null)).as("max_attempts unset means one attempt").isEqualTo(-1);
        assertThat(this.waitChosen(1, 2, null)).as("retry_backoff_seconds unset means 60").isEqualTo(60);
    }

    // ---- the row -------------------------------------------------------------------------------------

    /** The same row, by identity: the id is unchanged, and no second row is created alongside it. */
    @Test
    void theRetryReusesTheRunsRowRatherThanReplacingIt() {
        JobQueue stored = this.storedRun(1);
        this.given(3, 60, stored);
        JobQueue callersCopy = this.storedRun(1);

        assertThat(this.bulkAction.scheduleRetry(callersCopy, "connection reset")).isTrue();

        ArgumentCaptor<JobQueue> written = ArgumentCaptor.forClass(JobQueue.class);
        verify(this.transactionService, times(1)).saveOrUpdateJobQueue(written.capture());
        verify(this.transactionService, never()).saveJobQueue(any());
        assertThat(written.getValue()).isSameAs(stored);
        assertThat(written.getValue().getJobQueueId()).isEqualTo(QUEUE_ID);
        assertThat(written.getValue().getOutputFolder()).isEqualTo("etl-demo/out/2420");
        assertThat(written.getValue().getAttempt()).isEqualTo(2);
        assertThat(written.getValue().getJobStatus()).isEqualTo(JobStatus.Queue);
        // What makes the row eligible again: the pick-up query takes Queue rows with job_send false.
        assertThat(written.getValue().isJobSend()).isFalse();
        // The run has not ended; a stale end time makes the eventual duration read negative.
        assertThat(written.getValue().getEndTime()).isNull();

        // And the caller's copy -- the one a failure mail would be built from -- follows it.
        assertThat(callersCopy.getJobQueueId()).isEqualTo(QUEUE_ID);
        assertThat(callersCopy.getAttempt()).isEqualTo(2);
        assertThat(callersCopy.getJobStatus()).isEqualTo(JobStatus.Queue);
        assertThat(callersCopy.isJobSend()).isFalse();
        assertThat(callersCopy.getEndTime()).isNull();
        assertThat(callersCopy.getNextAttemptAt()).isEqualTo(stored.getNextAttemptAt());
    }

    @Test
    void theRetrySaysWhichAttemptFailedAndWhenTheNextIsDue() {
        JobQueue stored = this.storedRun(2);
        this.given(5, 60, stored);

        this.bulkAction.scheduleRetry(QUEUE_ID, JOB_ID, "connection reset");

        assertThat(stored.getJobStatusMessage())
            .isEqualTo("Attempt 2 of 5 failed: connection reset. Retrying at " + stored.getNextAttemptAt() + ".");
        verify(this.transactionService).saveJobAuditLogs(QUEUE_ID,
            "Attempt 2 of 5 failed: connection reset. Queued for attempt 3 at " + stored.getNextAttemptAt() + ".");
    }
}

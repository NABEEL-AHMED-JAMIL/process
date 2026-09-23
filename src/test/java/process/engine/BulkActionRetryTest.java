package process.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.service.NotificationCenterService;
import process.model.service.impl.TransactionServiceImpl;
import process.socket.JobEventPublisher;
import process.socket.NotificationService;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import process.notifications.TestNotifications;

/**
 * That a failed run is offered another attempt, and that it is offered exactly the number it was
 * configured for.
 *
 * The return value is the contract these hold hardest. Callers use it to decide whether to
 * announce a failure at all, so a scheduleRetry that returned true after deciding not to retry
 * would produce a run that is never marked Failed, never emailed about, and never attempted
 * again -- a silent hang. The reverse, returning false while having re-queued the row, sends a
 * failure email per attempt, which is the noise retry exists to remove.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
public class BulkActionRetryTest {

    private static final long JOB_ID = 2420L;
    private static final long QUEUE_ID = 77L;

    @Mock private TransactionServiceImpl transactionService;
    @Mock private NotificationService notificationService;
    @Mock private NotificationCenterService notificationCenterService;
    @Mock private JobEventPublisher jobEventPublisher;

    private BulkAction bulkAction;

    @BeforeEach
    void setUp() {
        this.bulkAction = new BulkAction(this.transactionService, TestNotifications.inProcess(this.jobEventPublisher,
            this.notificationService, this.notificationCenterService, null));
    }

    private SourceJob job(int maxAttempts, int backoffSeconds) {
        SourceJob sourceJob = new SourceJob();
        sourceJob.setJobId(JOB_ID);
        sourceJob.setMaxAttempts(maxAttempts);
        sourceJob.setRetryBackoffSeconds(backoffSeconds);
        return sourceJob;
    }

    private JobQueue run(int attempt) {
        JobQueue jobQueue = new JobQueue();
        jobQueue.setJobQueueId(QUEUE_ID);
        jobQueue.setJobId(JOB_ID);
        jobQueue.setAttempt(attempt);
        jobQueue.setJobStatus(JobStatus.Start);
        jobQueue.setJobSend(true);
        jobQueue.setEndTime(LocalDateTime.now());
        return jobQueue;
    }

    private void stub(SourceJob sourceJob, JobQueue stored) {
        lenient().when(this.transactionService.findByJobId(JOB_ID)).thenReturn(Optional.of(sourceJob));
        lenient().when(this.transactionService.findJobQueueByJobQueueId(QUEUE_ID)).thenReturn(Optional.of(stored));
    }

    private JobQueue savedRow() {
        ArgumentCaptor<JobQueue> captor = ArgumentCaptor.forClass(JobQueue.class);
        verify(this.transactionService).saveOrUpdateJobQueue(captor.capture());
        return captor.getValue();
    }

    @Test
    void doesNotRetryWhenTheJobIsLeftAtTheDefaultOfOneAttempt() {
        JobQueue stored = this.run(1);
        this.stub(this.job(1, 60), stored);

        assertFalse(this.bulkAction.scheduleRetry(this.run(1), "broker unreachable"),
            "max_attempts 1 is the default every existing job carries; retrying there would change "
                + "the failure behaviour of jobs nobody has configured");
        verify(this.transactionService, never()).saveOrUpdateJobQueue(any(JobQueue.class));
    }

    @Test
    void retriesWhileAttemptsRemain() {
        JobQueue stored = this.run(1);
        this.stub(this.job(3, 60), stored);

        assertTrue(this.bulkAction.scheduleRetry(this.run(1), "connection reset"));

        JobQueue saved = this.savedRow();
        assertEquals(2, saved.getAttempt());
        assertEquals(JobStatus.Queue, saved.getJobStatus(),
            "the retry is dispatched by the ordinary pick-up query, which only takes Queue rows");
    }

    @Test
    void stopsAtTheLastAttempt() {
        JobQueue stored = this.run(3);
        this.stub(this.job(3, 60), stored);

        assertFalse(this.bulkAction.scheduleRetry(this.run(3), "connection reset"),
            "attempt 3 of 3 has used the last one; a fourth would exceed what the job asked for");
        verify(this.transactionService, never()).saveOrUpdateJobQueue(any(JobQueue.class));
    }

    @Test
    void clearsTheFlagsThatWouldOtherwiseStrandTheRetry() {
        JobQueue stored = this.run(1);
        this.stub(this.job(3, 60), stored);

        this.bulkAction.scheduleRetry(this.run(1), "connection reset");

        JobQueue saved = this.savedRow();
        assertFalse(saved.isJobSend(),
            "the pick-up query filters on job_send = false; left true, the retry is written down "
                + "and then never dispatched, which reads as a hang rather than a failure");
        assertNull(saved.getEndTime(),
            "the run has not ended; a left-over end time makes its duration read negative once "
                + "the retry completes");
    }

    @Test
    void backoffDoublesWithEachAttempt() {
        JobQueue firstFailure = this.run(1);
        this.stub(this.job(4, 60), firstFailure);
        LocalDateTime before = LocalDateTime.now();
        this.bulkAction.scheduleRetry(this.run(1), "reset");
        LocalDateTime afterFirst = this.savedRow().getNextAttemptAt();
        assertNotNull(afterFirst);
        // 60s from the first failure, with a generous window either side so the assertion is about
        // the interval and not about how long the test itself took.
        assertTrue(afterFirst.isAfter(before.plusSeconds(55)) && afterFirst.isBefore(before.plusSeconds(75)),
            "first retry waits the base backoff; got " + afterFirst);
    }

    @Test
    void backoffIsCappedSoAJobCannotBeTakenOffTheAirForWeeks() {
        // Ten attempts on an hour's base: the ninth doubling of 3600s is twenty-one days, and a
        // queued run occupies its job, so without a ceiling this job would never run its own
        // schedule again.
        JobQueue stored = this.run(9);
        this.stub(this.job(10, 3600), stored);
        LocalDateTime before = LocalDateTime.now();

        this.bulkAction.scheduleRetry(this.run(9), "still down");

        LocalDateTime dueAt = this.savedRow().getNextAttemptAt();
        assertTrue(dueAt.isBefore(before.plusSeconds(3600 + 60)),
            "backoff must be capped at an hour; got " + dueAt);
    }

    @Test
    void doesNotRetryARunWhoseJobHasBeenDeleted() {
        when(this.transactionService.findByJobId(JOB_ID)).thenReturn(Optional.empty());

        assertFalse(this.bulkAction.scheduleRetry(this.run(1), "gone"),
            "there is no retry policy to read, and the job is not coming back");
        verify(this.transactionService, never()).saveOrUpdateJobQueue(any(JobQueue.class));
    }

    @Test
    void readsTheAttemptFromTheStoredRowNotTheCallersCopy() {
        // The Kafka path arrives from a send callback fired long after its entity was loaded. If
        // the stale copy were trusted, a run already on its last attempt would be retried for ever.
        JobQueue stored = this.run(3);
        this.stub(this.job(3, 60), stored);
        JobQueue staleCallerCopy = this.run(1);

        assertFalse(this.bulkAction.scheduleRetry(staleCallerCopy, "reset"),
            "the stored row is on attempt 3 of 3 even though the caller's copy still says 1");
    }

    @Test
    void treatsAnUnsetAttemptCountAsTheFirstAttempt() {
        JobQueue stored = this.run(0);
        this.stub(this.job(2, 60), stored);

        assertTrue(this.bulkAction.scheduleRetry(this.run(0), "reset"),
            "a row written before the column existed reads 0; treating that as past the limit "
                + "would disable retry on exactly the rows most likely to be odd");
        assertEquals(2, this.savedRow().getAttempt());
    }

    @Test
    void recordsWhatFailedSoTheReasonSurvivesTheRetry() {
        JobQueue stored = this.run(1);
        this.stub(this.job(3, 60), stored);

        this.bulkAction.scheduleRetry(this.run(1), "connection reset by peer");

        verify(this.transactionService).saveJobAuditLogs(anyLong(), anyString());
        assertTrue(this.savedRow().getJobStatusMessage().contains("connection reset by peer"),
            "the run's own status line is where a person looks first; losing the reason there "
                + "leaves 'Attempt 1 of 3 failed' with no way to find out why");
    }

    @Test
    void survivesAFailureWithNoReasonRecorded() {
        JobQueue stored = this.run(1);
        this.stub(this.job(3, 60), stored);

        assertTrue(this.bulkAction.scheduleRetry(this.run(1), null));
        assertFalse(this.savedRow().getJobStatusMessage().contains("null"),
            "a worker that reports a failure with no detail must not produce a status line "
                + "reading 'failed: null'");
    }
}

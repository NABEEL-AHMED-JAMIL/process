package process.model.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.callback.CallbackKeys;
import process.callback.CallbackReceipts;
import process.callback.ReplayedResponse;
import process.engine.BulkAction;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.notifications.JobMail;
import process.notifications.TestNotifications;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * MIG-33 and MIG-18: a redelivered worker callback is answered, not applied again.
 *
 * The job row is held at the status the first delivery saw, which is exactly the race at-least-once
 * delivery produces: two deliveries of one Failed both read Running before either commits, both pass
 * the transition table, and -- before the receipt -- both wrote the audit line and sent the email.
 * The receipts here are an in-memory stand-in with the same claim semantics as worker_callback_receipt;
 * CallbackReceiptsPostgresTest holds the real table to them, race included.
 */
@ExtendWith(MockitoExtension.class)
class WorkerCallbackIdempotencyTest {

    private static final long TENANT = 1001L;
    private static final long JOB_ID = 2410L;
    private static final long QUEUE_ID = 5705L;

    @Mock private BulkAction bulkAction;
    @Mock private JobMail jobMail;
    @Mock private TransactionServiceImpl transactionService;
    @Mock private TestNotifications.FeedSink feed;

    private final InMemoryReceipts receipts = new InMemoryReceipts();
    private NotifyServiceImpl service;
    private JobQueue run;

    /** worker_callback_receipt's claim semantics, sequentially: first claim wins, the rest see its answer. */
    static final class InMemoryReceipts implements CallbackReceipts {

        final Map<String, String[]> rows = new HashMap<>();

        @Override
        public Optional<Receipt> claim(Long jobQueueId, String key, String request, LocalDateTime receivedAt) {
            String id = jobQueueId + "/" + key;
            if (this.rows.containsKey(id)) {
                return this.find(jobQueueId, key);
            }
            this.rows.put(id, new String[] {request, null, null});
            return Optional.empty();
        }

        @Override
        public void record(Long jobQueueId, String key, String outcomeStatus, String outcomeMessage) {
            String[] row = this.rows.get(jobQueueId + "/" + key);
            row[1] = outcomeStatus;
            row[2] = outcomeMessage;
        }

        @Override
        public Optional<Receipt> find(Long jobQueueId, String key) {
            String[] row = this.rows.get(jobQueueId + "/" + key);
            return row == null ? Optional.empty() : Optional.of(new Receipt(row[0], row[1], row[2]));
        }

        @Override
        public int purgeReceivedBefore(LocalDateTime cutoff) {
            return 0;
        }
    }

    @BeforeEach
    void setUp() {
        this.service = new NotifyServiceImpl(this.bulkAction, this.jobMail, this.transactionService,
            TestNotifications.recording(this.feed, null, null), this.receipts);
        this.run = new JobQueue();
        this.run.setJobQueueId(QUEUE_ID);
        this.run.setJobId(JOB_ID);
        this.run.setAttempt(1);
        this.run.setCallbackTokenAttempt(1);
        lenient().when(this.transactionService.findJobQueueByJobQueueId(QUEUE_ID)).thenReturn(Optional.of(this.run));
    }

    private void givenJobRow(JobStatus runningStatus) {
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID);
        job.setTenantId(TENANT);
        job.setJobRunningStatus(runningStatus);
        job.setFailJob(true);
        job.setCompleteJob(true);
        lenient().when(this.transactionService.findByJobIdAndJobStatus(JOB_ID, Status.Active)).thenReturn(Optional.of(job));
    }

    private static SourceJobQueueDto reports(JobStatus status, String message) {
        SourceJobQueueDto dto = new SourceJobQueueDto();
        dto.setJobId(JOB_ID);
        dto.setJobQueueId(QUEUE_ID);
        dto.setJobStatus(status);
        dto.setJobStatusMessage(message);
        dto.setEndTime(LocalDateTime.of(2026, 9, 24, 9, 30));
        return dto;
    }

    // ---- MIG-33's two tests --------------------------------------------------------------------------------

    /** The gap MIG-33 names: the first Failed replayed passed validation and sent the mail again. */
    @Test
    void anIdenticalFailedDeliveredTwiceSendsOneEmailAndWritesOneAuditLine() {
        this.givenJobRow(JobStatus.Running);

        ResponseDto first = this.service.changeState(reports(JobStatus.Failed, "source refused the connection"), null);
        ResponseDto second = this.service.changeState(reports(JobStatus.Failed, "source refused the connection"), null);

        verify(this.jobMail, times(1)).send(any(SourceJobQueueDto.class), eq(JobStatus.Failed));
        verify(this.bulkAction, times(1)).saveJobAuditLogs(QUEUE_ID, "source refused the connection");
        verify(this.bulkAction, times(1)).changeJobStatus(JOB_ID, JobStatus.Failed);
        verify(this.bulkAction, times(1)).scheduleRetry(eq(QUEUE_ID), eq(JOB_ID), anyString());
        assertThat(second).isInstanceOf(ReplayedResponse.class);
        assertThat(second.getMessage()).isEqualTo(first.getMessage());
        assertThat(second.getStatus()).isEqualTo(first.getStatus());
    }

    /** The heartbeat exemption: ten Running-to-Running reports are ten reports. */
    @Test
    void tenHeartbeatsWithoutAKeyAreTenHeartbeats() {
        this.givenJobRow(JobStatus.Running);

        for (int beat = 0; beat < 10; beat++) {
            this.service.changeState(reports(JobStatus.Running, "still going"), null);
        }

        verify(this.bulkAction, times(10)).changeJobStatus(JOB_ID, JobStatus.Running);
        verify(this.bulkAction, times(10)).saveJobAuditLogs(QUEUE_ID, "still going");
        verify(this.bulkAction, times(10)).sendJobStatusNotification(JOB_ID, QUEUE_ID, false);
        assertThat(this.receipts.rows).as("a heartbeat claims no key").isEmpty();
    }

    @Test
    void tenHeartbeatsEachWithItsOwnKeyAreTenHeartbeats() {
        this.givenJobRow(JobStatus.Running);

        for (int beat = 0; beat < 10; beat++) {
            this.service.changeState(reports(JobStatus.Running, "still going"), "beat-000000" + beat);
        }

        verify(this.bulkAction, times(10)).changeJobStatus(JOB_ID, JobStatus.Running);
    }

    // ---- MIG-18: each callback replayed five times -----------------------------------------------------------

    @Test
    void aCompletedReplayedFiveTimesWritesOnceAndMailsOnce() {
        this.givenJobRow(JobStatus.Running);

        for (int delivery = 0; delivery < 5; delivery++) {
            this.service.changeState(reports(JobStatus.Completed, "all rows written"), "done-7f3a9c2e");
        }

        verify(this.bulkAction, times(1)).saveJobAuditLogs(QUEUE_ID, "all rows written");
        verify(this.bulkAction, times(1)).sendJobStatusNotification(JOB_ID, QUEUE_ID, true);
        verify(this.jobMail, times(1)).send(any(SourceJobQueueDto.class), eq(JobStatus.Completed));
    }

    @Test
    void aLogLineReplayedFiveTimesIsWrittenAndAnnouncedOnce() {
        this.givenJobRow(JobStatus.Running);

        for (int delivery = 0; delivery < 5; delivery++) {
            this.service.addLogs(reports(null, "read 4,200 rows"), "line-0000042");
        }

        verify(this.bulkAction, times(1)).saveJobAuditLogs(QUEUE_ID, "read 4,200 rows");
        verify(this.feed, times(1)).publishLog(eq(TENANT), eq(JOB_ID), eq(QUEUE_ID), anyString());
    }

    @Test
    void aLogBatchReplayedFiveTimesIsWrittenAndAnnouncedOnce() {
        this.givenJobRow(JobStatus.Running);
        List<String> lines = Arrays.asList("opened source", "read 4,200 rows");

        for (int delivery = 0; delivery < 5; delivery++) {
            this.service.addLogsBatch(JOB_ID, QUEUE_ID, lines, "batch-0000007");
        }

        verify(this.bulkAction, times(1)).saveJobAuditLogs(QUEUE_ID, lines);
        verify(this.feed, times(2)).publishLog(eq(TENANT), eq(JOB_ID), eq(QUEUE_ID), anyString());
    }

    /** Two identical lines without a key are two lines: a worker can log the same thing twice. */
    @Test
    void identicalLogLinesWithoutAKeyAreAllWritten() {
        this.givenJobRow(JobStatus.Running);

        this.service.addLogs(reports(null, "retrying the source"), null);
        this.service.addLogs(reports(null, "retrying the source"), null);

        verify(this.bulkAction, times(2)).saveJobAuditLogs(QUEUE_ID, "retrying the source");
    }

    // ---- what a key does not do ---------------------------------------------------------------------------------

    /** A callback reporting a NEW state after a seen one is still processed. */
    @Test
    void aNewStateAfterASeenOneIsApplied() {
        this.givenJobRow(JobStatus.Running);

        this.service.changeState(reports(JobStatus.Running, "started"), "k-running-01");
        this.service.changeState(reports(JobStatus.Completed, "finished"), "k-complete-01");

        verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Running);
        verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Completed);
    }

    /** One key cannot answer two different callbacks: the second is refused, and nothing is written. */
    @Test
    void aKeyReusedForADifferentCallbackIsRefused() {
        this.givenJobRow(JobStatus.Running);

        this.service.changeState(reports(JobStatus.Running, "started"), "k-reused-001");
        ResponseDto reused = this.service.changeState(reports(JobStatus.Completed, "finished"), "k-reused-001");

        assertThat(reused.getStatus()).isEqualTo("ERROR");
        assertThat(reused.getMessage()).contains("Idempotency-Key");
        verify(this.bulkAction, never()).changeJobStatus(JOB_ID, JobStatus.Completed);
    }

    /**
     * The derived key is the attempt the TOKEN was minted for. Attempt 1's Failed puts the run back in
     * the queue as attempt 2; attempt 1's Failed redelivered is still attempt 1's, and is a replay --
     * one retry, not two. Attempt 2's own Failed, once it is dispatched, is a new callback.
     */
    @Test
    void theDerivedKeyIsTheAttemptTheTokenWasMintedFor() {
        this.givenJobRow(JobStatus.Running);
        lenient().when(this.bulkAction.scheduleRetry(eq(QUEUE_ID), eq(JOB_ID), anyString())).thenAnswer(inv -> {
            this.run.setAttempt(2);
            return true;
        });

        this.service.changeState(reports(JobStatus.Failed, "timeout"), null);
        this.service.changeState(reports(JobStatus.Failed, "timeout"), null);
        verify(this.bulkAction, times(1)).scheduleRetry(eq(QUEUE_ID), eq(JOB_ID), anyString());

        this.run.setCallbackTokenAttempt(2);
        this.service.changeState(reports(JobStatus.Failed, "timeout"), null);
        verify(this.bulkAction, times(2)).scheduleRetry(eq(QUEUE_ID), eq(JOB_ID), anyString());
    }

    /** A refused transition is an answer too: its redelivery gets the same refusal, and writes nothing. */
    @Test
    void aRefusedCallbackIsRefusedAgainTheSameWay() {
        this.givenJobRow(JobStatus.Start);

        ResponseDto first = this.service.changeState(reports(JobStatus.Completed, "done"), "k-refused-01");
        ResponseDto second = this.service.changeState(reports(JobStatus.Completed, "done"), "k-refused-01");

        assertThat(first.getStatus()).isEqualTo("ERROR");
        assertThat(second.getMessage()).isEqualTo(first.getMessage());
        verify(this.bulkAction, never()).changeJobStatus(anyLong(), any());
    }

    // ---- the run that is over -------------------------------------------------------------------------------------

    /** The replay door the controller uses once the run is over: the recorded answer, and nothing else. */
    @Test
    void aFinishedRunAnswersAReplayOfItsLastCallbackFromTheReceipt() {
        this.givenJobRow(JobStatus.Running);
        ResponseDto first = this.service.changeState(reports(JobStatus.Completed, "all rows written"), null);

        Optional<ResponseDto> replay = this.service.replay(QUEUE_ID, JobStatus.Completed,
            CallbackKeys.changeState(JobStatus.Completed), null);
        Optional<ResponseDto> neverSent = this.service.replay(QUEUE_ID, JobStatus.Running,
            CallbackKeys.changeState(JobStatus.Running), null);
        Optional<ResponseDto> otherLine = this.service.replay(QUEUE_ID, null, CallbackKeys.ADD_LOGS, "line-unknown-1");

        assertThat(replay).isPresent();
        assertThat(replay.get().getMessage()).isEqualTo(first.getMessage());
        assertThat(neverSent).as("a heartbeat has no receipt to replay").isEmpty();
        assertThat(otherLine).isEmpty();
        verify(this.bulkAction, times(1)).changeJobStatus(JOB_ID, JobStatus.Completed);
        verify(this.bulkAction, never()).saveJobAuditLogs(anyLong(), anyList());
    }
}

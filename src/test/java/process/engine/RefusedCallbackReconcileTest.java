package process.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.security.RunCallbackTokens;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-63: a run whose own worker is known to be unable to report is closed by the sweep's next pass.
 *
 * The documented incident: a worker did all its work and wrote every output, but each status callback
 * was refused, so its run sat in Start looking busy -- and because Start is in flight, the job could not
 * be dispatched again. A refused report is still refused (NotifyResetApi); what changes is that a
 * refusal of the run's OWN token for expiry is noted on the run, and the sweep takes that as the end of
 * the run instead of waiting six hours. The verdict is the sweep's usual one: Interrupt, never the
 * Completed or Failed the worker claimed, since the claim was never accepted.
 */
@ExtendWith(MockitoExtension.class)
class RefusedCallbackReconcileTest {

    private static final long JOB_ID = 2410L;

    @Mock private BulkAction bulkAction;
    @Mock private TransactionServiceImpl transactionService;
    @Mock private JobMail jobMail;
    @Mock private RunCallbackTokens runCallbackTokens;

    private ProducerBulkEngine engine;

    @BeforeEach
    void setUp() {
        this.engine = new ProducerBulkEngine(this.bulkAction, this.transactionService, this.jobMail, this.runCallbackTokens, null);
    }

    private static JobQueue refused(long jobQueueId, JobStatus status, String reported, LocalDateTime at) {
        JobQueue run = new JobQueue();
        run.setJobQueueId(jobQueueId);
        run.setJobId(JOB_ID);
        run.setJobStatus(status);
        run.setStartTime(LocalDateTime.now().minusMinutes(40));
        run.setRefusedCallbackAt(at);
        run.setRefusedCallbackStatus(reported);
        return run;
    }

    @Test
    void aRunWhoseWorkerWasRefusedIsClosedOnTheNextPassNotAfterSixHours() {
        LocalDateTime at = LocalDateTime.of(2026, 9, 24, 9, 12, 5);
        JobQueue run = refused(5705L, JobStatus.Start, "Completed", at);
        when(this.transactionService.findStalledRuns(any())).thenReturn(Collections.emptyList());
        when(this.transactionService.findRunsWithRefusedCallbacks()).thenReturn(Collections.singletonList(run));
        when(this.bulkAction.getCountForInQueueJobByJobId(JOB_ID)).thenReturn(0);

        this.engine.reconcileStalledRuns();

        assertThat(run.getJobStatus()).isEqualTo(JobStatus.Interrupt);
        assertThat(run.getEndTime()).isNotNull();
        assertThat(run.getJobStatusMessage()).isEqualTo("Job 2410's worker reported Completed at 2026-09-24T09:12:05, "
            + "but its callback token had expired, so the report was refused. Closed as interrupted -- check the "
            + "output before running it again.");
        verify(this.transactionService).saveJobQueue(run);
        verify(this.bulkAction).saveJobAuditLogs(5705L, "Run closed automatically: the worker's report "
            + "(Completed) at 2026-09-24T09:12:05 was refused because its callback token had expired.");
        verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Interrupt);
        verify(this.bulkAction).sendJobStatusNotification(JOB_ID);
    }

    /** The claim was never accepted, so it is quoted, not recorded: no Completed, no Failed, no mail, no retry. */
    @Test
    void theWorkersClaimIsQuotedNeverApplied() {
        JobQueue claimsCompleted = refused(1L, JobStatus.Running, "Completed", LocalDateTime.now().minusMinutes(5));
        JobQueue claimsFailed = refused(2L, JobStatus.Running, "Failed", LocalDateTime.now().minusMinutes(5));
        when(this.transactionService.findStalledRuns(any())).thenReturn(Collections.emptyList());
        when(this.transactionService.findRunsWithRefusedCallbacks()).thenReturn(Arrays.asList(claimsCompleted, claimsFailed));

        this.engine.reconcileStalledRuns();

        verify(this.bulkAction, never()).changeJobStatus(anyLong(), eq(JobStatus.Completed));
        verify(this.bulkAction, never()).changeJobStatus(anyLong(), eq(JobStatus.Failed));
        verify(this.bulkAction, never()).scheduleRetry(any(JobQueue.class), any());
        verify(this.bulkAction, never()).scheduleRetry(anyLong(), anyLong(), any());
        verify(this.jobMail, never()).send(any(), any());
    }

    /** A log line is a report too: it says the worker is alive and cannot be heard. */
    @Test
    void aRefusedLogLineIsDescribedAsOne() {
        JobQueue run = refused(5706L, JobStatus.Running, null, LocalDateTime.of(2026, 9, 24, 9, 0));
        when(this.transactionService.findStalledRuns(any())).thenReturn(Collections.emptyList());
        when(this.transactionService.findRunsWithRefusedCallbacks()).thenReturn(Collections.singletonList(run));

        this.engine.reconcileStalledRuns();

        assertThat(run.getJobStatusMessage()).startsWith("Job 2410's worker reported a log line at 2026-09-24T09:00");
    }

    /** A run that is both stalled and refused is closed once, with the more specific reason. */
    @Test
    void aRunOnBothListsIsClosedOnceWithTheRefusal() {
        JobQueue run = refused(5707L, JobStatus.Start, "Completed", LocalDateTime.of(2026, 9, 24, 9, 0));
        when(this.transactionService.findStalledRuns(any())).thenReturn(Collections.singletonList(run));
        when(this.transactionService.findRunsWithRefusedCallbacks()).thenReturn(Collections.singletonList(run));

        this.engine.reconcileStalledRuns();

        verify(this.transactionService, times(1)).saveJobQueue(run);
        verify(this.bulkAction, times(1)).saveJobAuditLogs(eq(5707L), anyString());
        assertThat(run.getJobStatusMessage()).contains("callback token had expired");
    }
}

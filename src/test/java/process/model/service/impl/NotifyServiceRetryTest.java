package process.model.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.notifications.JobMail;
import process.engine.BulkAction;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import process.notifications.TestNotifications;

/**
 * That a failed run reported by the live worker is offered another attempt.
 *
 * <b>This is the callback that actually fires in production, and its absence here is how retry
 * came to be wired to the wrong service first.</b> The retry was fitted to
 * MessageQServiceImpl.changeJobStatus -- which has the same shape, takes the same kind of message
 * and also marks runs Failed -- and every unit test passed, because they tested the path that had
 * been changed rather than the path the worker uses. A real run against a job that fails every
 * time went straight to Failed with one attempt and mailed about it, which is what caught it.
 * These tests exist so that cannot be true again silently.
 *
 * The email assertions are the point rather than a detail: a job given three attempts must not
 * produce three failure emails, and the early return is the only thing preventing that.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
public class NotifyServiceRetryTest {

    private static final long TENANT_A = 1001L;
    private static final long JOB_ID = 2410L;
    private static final long QUEUE_ID = 5705L;

    @Mock private BulkAction bulkAction;
    @Mock private JobMail jobMail;
    @Mock private TransactionServiceImpl transactionService;
    @Mock private TestNotifications.FeedSink jobEventPublisher;

    private NotifyServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new NotifyServiceImpl(this.bulkAction, this.jobMail,
            this.transactionService, TestNotifications.recording(this.jobEventPublisher, null, null));
    }

    /** A job mid-run that wants failure mail, so a suppressed one is visible as suppressed. */
    private void givenRunningJob() {
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID);
        job.setTenantId(TENANT_A);
        job.setJobRunningStatus(JobStatus.Running);
        job.setFailJob(true);
        lenient().when(this.transactionService.findByJobIdAndJobStatus(JOB_ID, Status.Active))
            .thenReturn(Optional.of(job));
        JobQueue queue = new JobQueue();
        queue.setJobQueueId(QUEUE_ID);
        queue.setJobId(JOB_ID);
        lenient().when(this.transactionService.findJobQueueByJobQueueId(QUEUE_ID))
            .thenReturn(Optional.of(queue));
    }

    private SourceJobQueueDto workerReports(JobStatus status) {
        SourceJobQueueDto dto = new SourceJobQueueDto();
        dto.setJobId(JOB_ID);
        dto.setJobQueueId(QUEUE_ID);
        dto.setJobStatus(status);
        dto.setJobStatusMessage("F768930 found no .csv objects under etl-bucket/etl-demo/does-not-exist");
        return dto;
    }

    @Test
    void aFailureWithAttemptsLeftIsRetriedAndNothingAnnouncesAFailure() {
        this.givenRunningJob();
        when(this.bulkAction.scheduleRetry(eq(QUEUE_ID), eq(JOB_ID), anyString())).thenReturn(true);

        this.service.changeState(this.workerReports(JobStatus.Failed));

        verify(this.bulkAction, never()).changeJobStatus(eq(JOB_ID), eq(JobStatus.Failed));
        verify(this.bulkAction, never()).changeJobQueueEndDate(anyLong(), any());
        verify(this.jobMail, never())
            .send(any(SourceJobQueueDto.class), any(JobStatus.class));
    }

    @Test
    void aFailureWithNoAttemptsLeftIsAnnouncedExactlyAsBefore() {
        this.givenRunningJob();
        when(this.bulkAction.scheduleRetry(eq(QUEUE_ID), eq(JOB_ID), anyString())).thenReturn(false);

        this.service.changeState(this.workerReports(JobStatus.Failed));

        verify(this.bulkAction).changeJobStatus(eq(JOB_ID), eq(JobStatus.Failed));
        verify(this.jobMail)
            .send(any(SourceJobQueueDto.class), eq(JobStatus.Failed));
    }

    @Test
    void theWorkersOwnExplanationIsWhatGetsCarriedIntoTheRetry() {
        this.givenRunningJob();
        when(this.bulkAction.scheduleRetry(anyLong(), anyLong(), anyString())).thenReturn(true);

        this.service.changeState(this.workerReports(JobStatus.Failed));

        // It is the only account of what actually went wrong, and the retry is where it would
        // otherwise be dropped -- the run's own status line gets rewritten by the retry.
        verify(this.bulkAction).scheduleRetry(eq(QUEUE_ID), eq(JOB_ID),
            eq("F768930 found no .csv objects under etl-bucket/etl-demo/does-not-exist"));
    }

    @Test
    void aCompletedRunIsNeverOfferedARetry() {
        this.givenRunningJob();

        this.service.changeState(this.workerReports(JobStatus.Completed));

        verify(this.bulkAction, never()).scheduleRetry(anyLong(), anyLong(), anyString());
        verify(this.bulkAction).changeJobStatus(eq(JOB_ID), eq(JobStatus.Completed));
    }

    @Test
    void aRunningHeartbeatIsNeverOfferedARetry() {
        this.givenRunningJob();

        this.service.changeState(this.workerReports(JobStatus.Running));

        verify(this.bulkAction, never()).scheduleRetry(anyLong(), anyLong(), anyString());
    }

    @Test
    void aRunOfAnInactiveJobIsRefusedBeforeAnyRetryIsConsidered() {
        when(this.transactionService.findByJobIdAndJobStatus(JOB_ID, Status.Active))
            .thenReturn(Optional.empty());

        this.service.changeState(this.workerReports(JobStatus.Failed));

        // The existing guard runs first, and must keep running first: a job that has been
        // deactivated will not succeed on a second attempt either.
        verify(this.bulkAction, never()).scheduleRetry(anyLong(), anyLong(), anyString());
    }

    // ---- what a callback tells Notifications (MIG-191) -------------------------------------------

    /**
     * A worker reporting Running on a run already Running is a heartbeat: the browser still gets
     * its live push, but it is not a new transition, so no notification-centre row or outcome
     * notice may come of it. Core decides this flag; the Notifications contract only carries it.
     */
    @Test
    void aRunningHeartbeatPushesLiveStateButIsNotANewTransition() {
        this.givenRunningJob();

        this.service.changeState(this.workerReports(JobStatus.Running));

        verify(this.bulkAction).sendJobStatusNotification(JOB_ID, QUEUE_ID, false);
        verify(this.bulkAction, never()).sendJobStatusNotification(JOB_ID, QUEUE_ID, true);
    }

    @Test
    void aRunThatFinishesIsANewTransition() {
        this.givenRunningJob();

        this.service.changeState(this.workerReports(JobStatus.Completed));

        verify(this.bulkAction).sendJobStatusNotification(JOB_ID, QUEUE_ID, true);
    }
}

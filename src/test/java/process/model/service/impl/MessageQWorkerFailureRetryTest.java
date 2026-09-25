package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.notifications.JobMail;
import process.engine.BulkAction;
import process.model.dto.QueueMessageStatusDto;
import process.model.dto.ResponseDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.security.TenantContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * That the worker reporting a failed run is offered a retry, and that nothing announces a failure
 * while one is pending.
 *
 * This is the callback that matters most. Every failure the dispatcher can produce is
 * infrastructure -- a broker it could not reach, a job row it could not read -- whereas this is
 * the task itself saying it could not finish, which is the ordinary way an ETL run fails and the
 * one a second attempt most often clears.
 *
 * The email assertions are the point of the test rather than a detail of it. A run given three
 * attempts must not produce three failure emails, and the only thing standing between the old
 * behaviour and exactly that is the early return this holds in place.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
public class MessageQWorkerFailureRetryTest {

    private static final long TENANT_A = 1001L;
    private static final long JOB_ID = 2420L;
    private static final long QUEUE_ID = 91422L;

    @Mock private BulkAction bulkAction;
    @Mock private QueryService queryService;
    @Mock private JobQueueRepository jobQueueRepository;
    @Mock private SourceJobRepository sourceJobRepository;
    @Mock private JobMail jobMail;

    private MessageQServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new MessageQServiceImpl(this.bulkAction, this.queryService,
            this.jobQueueRepository, this.sourceJobRepository, this.jobMail);
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "a@example.com");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** A job that wants a failure email, so a suppressed one is visible as a suppressed one. */
    private void jobWantsFailureMail() {
        SourceJob sourceJob = new SourceJob();
        sourceJob.setJobId(JOB_ID);
        sourceJob.setTenantId(TENANT_A);
        // The caller's own job (user 1): a TENANT_USER acts only on the jobs that name them (JobOwnership).
        sourceJob.setAssignedUserId(1L);
        sourceJob.setJobStatus(Status.Active);
        sourceJob.setFailJob(true);
        lenient().when(this.sourceJobRepository.findById(JOB_ID)).thenReturn(Optional.of(sourceJob));

        JobQueue jobQueue = new JobQueue();
        jobQueue.setJobQueueId(QUEUE_ID);
        jobQueue.setJobId(JOB_ID);
        jobQueue.setJobStatus(JobStatus.Running);
        lenient().when(this.jobQueueRepository.findById(QUEUE_ID)).thenReturn(Optional.of(jobQueue));
    }

    private ResponseDto workerReports(JobStatus status) {
        QueueMessageStatusDto message = new QueueMessageStatusDto();
        message.setMessageType("QUEUE_DETAIL");
        message.setJobQueueId(QUEUE_ID);
        message.setJobStatus(status);
        message.setLogsDetail("source refused the connection");
        return this.service.changeJobStatus(message);
    }

    @Test
    void aFailureWithAttemptsLeftIsRetriedAndNotAnnounced() {
        this.jobWantsFailureMail();
        when(this.bulkAction.scheduleRetry(any(JobQueue.class), anyString())).thenReturn(true);

        ResponseDto response = this.workerReports(JobStatus.Failed);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(this.bulkAction, never()).changeJobStatus(eq(JOB_ID), eq(JobStatus.Failed));
        verify(this.jobMail, never())
            .send(any(), any(JobStatus.class));
    }

    @Test
    void aFailureWithNoAttemptsLeftIsAnnouncedExactlyAsBefore() {
        this.jobWantsFailureMail();
        when(this.bulkAction.scheduleRetry(any(JobQueue.class), anyString())).thenReturn(false);

        this.workerReports(JobStatus.Failed);

        verify(this.bulkAction).changeJobStatus(eq(JOB_ID), eq(JobStatus.Failed));
        verify(this.jobMail).send(any(), eq(JobStatus.Failed));
    }

    @Test
    void theWorkersOwnExplanationIsWhatGetsRecorded() {
        this.jobWantsFailureMail();
        when(this.bulkAction.scheduleRetry(any(JobQueue.class), anyString())).thenReturn(true);

        this.workerReports(JobStatus.Failed);

        // Passed through rather than replaced by a generic "run failed": it is the only account
        // of what actually went wrong, and the retry is where it would otherwise be dropped.
        verify(this.bulkAction).scheduleRetry(any(JobQueue.class), eq("source refused the connection"));
    }

    @Test
    void aSuccessfulRunIsNeverOfferedARetry() {
        this.jobWantsFailureMail();

        this.workerReports(JobStatus.Completed);

        verify(this.bulkAction, never()).scheduleRetry(any(JobQueue.class), anyString());
        verify(this.bulkAction).changeJobStatus(eq(JOB_ID), eq(JobStatus.Completed));
    }

    @Test
    void aSkippedRunIsNeverOfferedARetry() {
        this.jobWantsFailureMail();

        this.workerReports(JobStatus.Skip);

        // A skip is a decision, not a failure -- retrying one would re-run work the platform
        // deliberately declined to do.
        verify(this.bulkAction, never()).scheduleRetry(any(JobQueue.class), anyString());
    }

    @Test
    void anAuditLogMessageIsNeverOfferedARetry() {
        QueueMessageStatusDto message = new QueueMessageStatusDto();
        message.setMessageType("AUDIT_LOG");
        message.setJobQueueId(QUEUE_ID);
        message.setLogsDetail("row 41 of 900");
        JobQueue jobQueue = new JobQueue();
        jobQueue.setJobQueueId(QUEUE_ID);
        jobQueue.setJobId(JOB_ID);
        SourceJob sourceJob = new SourceJob();
        sourceJob.setJobId(JOB_ID);
        sourceJob.setTenantId(TENANT_A);
        // The caller's own job (user 1): a TENANT_USER acts only on the jobs that name them (JobOwnership).
        sourceJob.setAssignedUserId(1L);
        when(this.jobQueueRepository.findById(QUEUE_ID)).thenReturn(Optional.of(jobQueue));
        lenient().when(this.sourceJobRepository.findById(JOB_ID)).thenReturn(Optional.of(sourceJob));

        this.service.changeJobStatus(message);

        // A worker posting progress carries no status at all; reading one here would retry a run
        // that is still happily going.
        verify(this.bulkAction, never()).scheduleRetry(any(JobQueue.class), anyString());
        verify(this.bulkAction).saveJobAuditLogs(anyLong(), anyString());
    }
}

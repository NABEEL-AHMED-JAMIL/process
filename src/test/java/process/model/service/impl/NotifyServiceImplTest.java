package process.model.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.emailer.EmailMessagesFactory;
import process.engine.BulkAction;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.socket.JobEventPublisher;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static process.util.ProcessUtil.ERROR;

/**
 * The worker callback is how nearly every run reports its outcome, and it had no test at all --
 * which is how the two notification subscriptions came to be wired to each other's event, so a
 * failure was silent and a completion mailed whoever had asked about failures.
 *
 * Also covers the pairing of jobQueueId to jobId. The two ids arrive independently in the
 * callback path and nothing tied them together, so a caller could report against one job and
 * write to another tenant's run.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class NotifyServiceImplTest {

    private static final long TENANT_A = 1001L;
    private static final long JOB_ID = 1196L;
    private static final long QUEUE_ID = 91422L;
    private static final long QUEUE_OF_ANOTHER_JOB = 91423L;

    @Mock private BulkAction bulkAction;
    @Mock private EmailMessagesFactory emailMessagesFactory;
    @Mock private TransactionServiceImpl transactionService;
    @Mock private JobEventPublisher jobEventPublisher;

    private NotifyServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new NotifyServiceImpl(this.bulkAction, this.emailMessagesFactory,
            this.transactionService, this.jobEventPublisher);
    }

    /** A job mid-run, so both Failed and Completed are valid next states. */
    private SourceJob runningJob(boolean failJob, boolean completeJob) {
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID);
        job.setTenantId(TENANT_A);
        job.setJobRunningStatus(JobStatus.Running);
        job.setFailJob(failJob);
        job.setCompleteJob(completeJob);
        return job;
    }

    private SourceJobQueueDto callback(JobStatus status) {
        SourceJobQueueDto dto = new SourceJobQueueDto();
        dto.setJobId(JOB_ID);
        dto.setJobQueueId(QUEUE_ID);
        dto.setJobStatus(status);
        dto.setJobStatusMessage("upstream unavailable");
        return dto;
    }

    private JobQueue queueOf(Long jobId) {
        JobQueue queue = new JobQueue();
        queue.setJobQueueId(QUEUE_ID);
        queue.setJobId(jobId);
        return queue;
    }

    private void givenJob(SourceJob job) {
        when(this.transactionService.findByJobIdAndJobStatus(JOB_ID, Status.Active))
            .thenReturn(Optional.of(job));
        when(this.transactionService.findJobQueueByJobQueueId(QUEUE_ID))
            .thenReturn(Optional.of(queueOf(JOB_ID)));
    }

    @Test
    void aFailureMailsWhoeverSubscribedToFailures() {
        givenJob(runningJob(true, false));

        this.service.changeState(callback(JobStatus.Failed));

        verify(this.emailMessagesFactory).sendSourceJobEmail(any(SourceJobQueueDto.class), eq(JobStatus.Failed));
    }

    @Test
    void aFailureDoesNotMailACompletionSubscriber() {
        // The inversion this test exists for: completeJob alone used to send on Failed.
        givenJob(runningJob(false, true));

        this.service.changeState(callback(JobStatus.Failed));

        verify(this.emailMessagesFactory, never()).sendSourceJobEmail(any(SourceJobQueueDto.class), any(JobStatus.class));
    }

    @Test
    void aCompletionMailsWhoeverSubscribedToCompletions() {
        givenJob(runningJob(false, true));

        this.service.changeState(callback(JobStatus.Completed));

        verify(this.emailMessagesFactory).sendSourceJobEmail(any(SourceJobQueueDto.class), eq(JobStatus.Completed));
    }

    @Test
    void aCompletionDoesNotMailAFailureSubscriber() {
        givenJob(runningJob(true, false));

        this.service.changeState(callback(JobStatus.Completed));

        verify(this.emailMessagesFactory, never()).sendSourceJobEmail(any(SourceJobQueueDto.class), any(JobStatus.class));
    }

    @Test
    void bothSubscriptionsAreHonouredIndependently() {
        givenJob(runningJob(true, true));

        this.service.changeState(callback(JobStatus.Failed));
        verify(this.emailMessagesFactory).sendSourceJobEmail(any(SourceJobQueueDto.class), eq(JobStatus.Failed));

        this.service.changeState(callback(JobStatus.Completed));
        verify(this.emailMessagesFactory).sendSourceJobEmail(any(SourceJobQueueDto.class), eq(JobStatus.Completed));
    }

    @Test
    void aJobSubscribedToNothingIsNeverMailed() {
        givenJob(runningJob(false, false));

        this.service.changeState(callback(JobStatus.Failed));
        this.service.changeState(callback(JobStatus.Completed));

        verify(this.emailMessagesFactory, never()).sendSourceJobEmail(any(SourceJobQueueDto.class), any(JobStatus.class));
    }

    @Test
    void progressIsNeverMailed() {
        givenJob(runningJob(true, true));

        this.service.changeState(callback(JobStatus.Running));

        verify(this.emailMessagesFactory, never()).sendSourceJobEmail(any(SourceJobQueueDto.class), any(JobStatus.class));
    }

    @Test
    void aQueueBelongingToAnotherJobIsRefused() {
        SourceJobQueueDto dto = callback(JobStatus.Failed);
        dto.setJobQueueId(QUEUE_OF_ANOTHER_JOB);
        when(this.transactionService.findByJobIdAndJobStatus(JOB_ID, Status.Active))
            .thenReturn(Optional.of(runningJob(true, true)));
        JobQueue foreign = new JobQueue();
        foreign.setJobQueueId(QUEUE_OF_ANOTHER_JOB);
        foreign.setJobId(4102L);
        when(this.transactionService.findJobQueueByJobQueueId(QUEUE_OF_ANOTHER_JOB))
            .thenReturn(Optional.of(foreign));

        ResponseDto response = this.service.changeState(dto);

        assertThat(response.getStatus()).isEqualTo(ERROR);
        verify(this.bulkAction, never()).changeJobStatus(anyLong(), any(JobStatus.class));
        verify(this.bulkAction, never()).saveJobAuditLogs(anyLong(), anyString());
        verify(this.emailMessagesFactory, never()).sendSourceJobEmail(any(SourceJobQueueDto.class), any(JobStatus.class));
    }

    @Test
    void logsForAQueueThatIsNotThisJobsAreRefused() {
        SourceJobQueueDto dto = callback(JobStatus.Running);
        dto.setJobQueueId(QUEUE_OF_ANOTHER_JOB);
        when(this.transactionService.findByJobIdAndJobStatus(JOB_ID, Status.Active))
            .thenReturn(Optional.of(runningJob(false, false)));
        when(this.transactionService.findJobQueueByJobQueueId(QUEUE_OF_ANOTHER_JOB))
            .thenReturn(Optional.empty());

        ResponseDto response = this.service.addLogs(dto);

        assertThat(response.getStatus()).isEqualTo(ERROR);
        verify(this.bulkAction, never()).saveJobAuditLogs(anyLong(), anyString());
    }

    @Test
    void batchedLogsForAQueueThatIsNotThisJobsAreRefused() {
        List<String> messages = Arrays.asList("line one", "line two");
        when(this.transactionService.findByJobIdAndJobStatus(JOB_ID, Status.Active))
            .thenReturn(Optional.of(runningJob(false, false)));
        JobQueue foreign = new JobQueue();
        foreign.setJobQueueId(QUEUE_OF_ANOTHER_JOB);
        foreign.setJobId(4102L);
        when(this.transactionService.findJobQueueByJobQueueId(QUEUE_OF_ANOTHER_JOB))
            .thenReturn(Optional.of(foreign));

        ResponseDto response = this.service.addLogsBatch(JOB_ID, QUEUE_OF_ANOTHER_JOB, messages);

        assertThat(response.getStatus()).isEqualTo(ERROR);
        verify(this.bulkAction, never()).saveJobAuditLogs(anyLong(), anyList());
        verify(this.jobEventPublisher, never()).publishLog(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void logsForTheJobsOwnQueueAreWritten() {
        SourceJobQueueDto dto = callback(JobStatus.Running);
        when(this.transactionService.findByJobIdAndJobStatus(JOB_ID, Status.Active))
            .thenReturn(Optional.of(runningJob(false, false)));
        when(this.transactionService.findJobQueueByJobQueueId(QUEUE_ID))
            .thenReturn(Optional.of(queueOf(JOB_ID)));

        this.service.addLogs(dto);

        verify(this.bulkAction).saveJobAuditLogs(QUEUE_ID, "upstream unavailable");
    }
}

package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import process.api.MessageQRestApi;
import process.engine.BulkAction;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.notifications.JobMail;
import process.security.TenantContext;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * C7b writer D of four (MIG-140): MessageQServiceImpl.failJobLogs -- the operator's "Mark as failed".
 *
 * Call path: DELETE /message.json/failJobLogs?jobQId= on MessageQRestApi, JWT and
 * hasRole('TENANT_USER'), from the queue screen's confirm dialog for a run that is stuck.
 *
 * Validation: the RUN row must be in flight -- JobStatus.IN_FLIGHT, Queue, Start or Running. The job
 * row is not consulted at all, the reverse of writer B.
 *
 * Retry: NEVER, deliberately, whatever attempts the job has left. A person decided this run is over;
 * trying it again would overrule them. So the failure is written and mailed at once.
 *
 * The other three writers: FailedByDispatchTest, FailedByWorkerCallbackTest, FailedByChangeJobStatusTest.
 */
@ExtendWith(MockitoExtension.class)
class FailedByOperatorTest {

    private static final long TENANT = 1001L;
    private static final long JOB_ID = 66L;
    private static final long QUEUE_ID = 91422L;

    @Mock private BulkAction bulkAction;
    @Mock private QueryService queryService;
    @Mock private JobQueueRepository jobQueueRepository;
    @Mock private SourceJobRepository sourceJobRepository;
    @Mock private JobMail jobMail;

    private MessageQServiceImpl service;
    private JobQueue run;
    private SourceJob job;

    @BeforeEach
    void setUp() {
        this.service = new MessageQServiceImpl(this.bulkAction, this.queryService, this.jobQueueRepository,
            this.sourceJobRepository, this.jobMail);
        TenantContext.set(TENANT, "TENANT_USER", 1L, "a@example.com");
        this.job = new SourceJob();
        this.job.setJobId(JOB_ID);
        this.job.setTenantId(TENANT);
        // The caller's own job (user 1): a TENANT_USER acts only on the jobs that name them (JobOwnership).
        this.job.setAssignedUserId(1L);
        this.job.setJobStatus(Status.Active);
        this.job.setFailJob(true);
        // Plenty of attempts left, so a retry would be available if this writer ever asked.
        this.job.setMaxAttempts(3);
        this.run = new JobQueue();
        this.run.setJobQueueId(QUEUE_ID);
        this.run.setJobId(JOB_ID);
        this.run.setAttempt(1);
        lenient().when(this.jobQueueRepository.findById(QUEUE_ID)).thenReturn(Optional.of(this.run));
        lenient().when(this.sourceJobRepository.findById(JOB_ID)).thenReturn(Optional.of(this.job));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @ParameterizedTest
    @EnumSource(JobStatus.class)
    void onlyARunStillInFlightCanBeMarkedFailed(JobStatus held) {
        this.run.setJobStatus(held);

        ResponseDto response = this.service.failJobLogs(QUEUE_ID);

        if (JobStatus.IN_FLIGHT.contains(held)) {
            assertThat(response.getStatus()).as("%s", held).isEqualTo("SUCCESS");
            verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Failed);
        } else {
            assertThat(response.getStatus()).as("%s", held).isEqualTo("ERROR");
            assertThat(response.getMessage())
                .isEqualTo("Only a run still in flight ('Queue', 'Start', 'Running') can be failed.");
            verify(this.bulkAction, never()).changeJobStatus(anyLong(), any());
            verifyNoInteractions(this.jobMail);
        }
    }

    @Test
    void aRunWithNoStatusAtAllIsRefused() {
        this.run.setJobStatus(null);

        assertThat(this.service.failJobLogs(QUEUE_ID).getStatus()).isEqualTo("ERROR");
        verify(this.bulkAction, never()).changeJobStatus(anyLong(), any());
    }

    /** Attempts left, and still no retry: failed at once, and mailed at once. */
    @Test
    void theOperatorsVerdictIsNeverRetried() {
        this.run.setJobStatus(JobStatus.Running);

        this.service.failJobLogs(QUEUE_ID);

        verify(this.bulkAction, never()).scheduleRetry(any(JobQueue.class), any());
        verify(this.bulkAction, never()).scheduleRetry(anyLong(), anyLong(), any());
        InOrder order = inOrder(this.bulkAction, this.jobMail);
        order.verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Failed);
        order.verify(this.bulkAction).changeJobQueueStatus(QUEUE_ID, JobStatus.Failed, "Job 66 fail by manual.");
        order.verify(this.bulkAction).saveJobAuditLogs(QUEUE_ID, "Job 66 fail by manual.");
        order.verify(this.bulkAction).changeJobQueueEndDate(eq(QUEUE_ID), any(LocalDateTime.class));
        order.verify(this.jobMail).send(any(SourceJobQueueDto.class), eq(JobStatus.Failed));
        verify(this.bulkAction, never()).sendJobStatusNotification(anyLong(), any(), eq(true));
    }

    /** The run row decides; the job row -- here Completed, written by some other run -- is not read. */
    @Test
    void theJobRowIsNotConsulted() {
        this.run.setJobStatus(JobStatus.Start);
        this.job.setJobRunningStatus(JobStatus.Completed);

        assertThat(this.service.failJobLogs(QUEUE_ID).getStatus()).isEqualTo("SUCCESS");
        verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Failed);
    }

    @Test
    void theFailureMailFollowsTheJobsFailedPreferenceOnly() {
        this.run.setJobStatus(JobStatus.Running);
        this.job.setFailJob(false);
        this.job.setSkipJob(true);
        this.job.setCompleteJob(true);

        this.service.failJobLogs(QUEUE_ID);

        verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Failed);
        verifyNoInteractions(this.jobMail);
    }

    @Test
    void theEndpointIsATenantUsersDeleteUnderMessageJson() throws Exception {
        assertThat(MessageQRestApi.class.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('TENANT_USER')");
        RequestMapping mapping = MessageQRestApi.class.getMethod("failJobLogs", Long.class).getAnnotation(RequestMapping.class);
        assertThat(mapping.value()).containsExactly("/failJobLogs");
        assertThat(mapping.method()).containsExactly(RequestMethod.DELETE);
    }
}

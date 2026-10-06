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
import process.model.dto.QueueMessageStatusDto;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C7b writer C of four (MIG-140): MessageQServiceImpl.changeJobStatus.
 *
 * Call path: PUT /message.json/changeJobStatus on MessageQRestApi, under a JWT and
 * hasRole('TENANT_USER'); the caller must own the run's job (or be a platform admin).
 *
 * Validation: NONE. Whatever status the message carries is written -- Failed over a run that is
 * still in Start, or over one already Completed. The C7 table that holds writer B does not apply.
 *
 * Retry: offered for a Failed QUEUE_DETAIL message, before any write, and its answer is the email
 * gate exactly as on writer B. Unlike A and B, nothing here sends the owner's outcome notice
 * (sendJobStatusNotification); the job feed still hears it through changeJobStatus.
 *
 * The other three writers: FailedByDispatchTest, FailedByWorkerCallbackTest, FailedByOperatorTest.
 */
@ExtendWith(MockitoExtension.class)
class FailedByChangeJobStatusTest {

    private static final long TENANT = 1001L;
    private static final long JOB_ID = 2420L;
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
        this.run = new JobQueue();
        this.run.setJobQueueId(QUEUE_ID);
        this.run.setJobId(JOB_ID);
        lenient().when(this.jobQueueRepository.findById(QUEUE_ID)).thenReturn(Optional.of(this.run));
        lenient().when(this.sourceJobRepository.findById(JOB_ID)).thenReturn(Optional.of(this.job));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ResponseDto reportFailed() {
        QueueMessageStatusDto message = new QueueMessageStatusDto();
        message.setMessageType("QUEUE_DETAIL");
        message.setJobQueueId(QUEUE_ID);
        message.setJobStatus(JobStatus.Failed);
        message.setLogsDetail("source refused the connection");
        message.setEndTime(LocalDateTime.of(2026, 9, 24, 9, 30));
        return this.service.changeJobStatus(message);
    }

    /** Neither the run row nor the job row is consulted: Failed lands on every state there is. */
    @ParameterizedTest
    @EnumSource(JobStatus.class)
    void failedIsWrittenOverAnyStatusAtAll(JobStatus held) {
        this.run.setJobStatus(held);
        this.job.setJobRunningStatus(held);
        when(this.bulkAction.scheduleRetry(any(JobQueue.class), anyString())).thenReturn(false);

        ResponseDto response = this.reportFailed();

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Failed);
        verify(this.bulkAction).changeJobQueueStatus(QUEUE_ID, JobStatus.Failed, "source refused the connection");
    }

    @Test
    void theRetryIsAskedBeforeAnyWriteAndTheFailureMailedOnceWhenItIsDeclined() {
        this.run.setJobStatus(JobStatus.Running);
        when(this.bulkAction.scheduleRetry(any(JobQueue.class), anyString())).thenReturn(false);

        this.reportFailed();

        InOrder order = inOrder(this.bulkAction, this.jobMail);
        order.verify(this.bulkAction).scheduleRetry(this.run, "source refused the connection");
        order.verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Failed);
        order.verify(this.bulkAction).changeJobQueueStatus(QUEUE_ID, JobStatus.Failed, "source refused the connection");
        order.verify(this.bulkAction).saveJobAuditLogs(QUEUE_ID, "source refused the connection");
        order.verify(this.bulkAction).changeJobQueueEndDate(QUEUE_ID, LocalDateTime.of(2026, 9, 24, 9, 30));
        order.verify(this.jobMail).send(any(SourceJobQueueDto.class), eq(JobStatus.Failed));
        // The owner's outcome notice is writer A's and B's; this writer never sends it.
        verify(this.bulkAction, never()).sendJobStatusNotification(anyLong(), any(), eq(true));
        verify(this.bulkAction, never()).sendJobStatusNotification(anyLong());
    }

    @Test
    void aRetriedAttemptSendsNoEmailAndWritesNoFailure() {
        this.run.setJobStatus(JobStatus.Running);
        when(this.bulkAction.scheduleRetry(any(JobQueue.class), anyString())).thenReturn(true);

        ResponseDto response = this.reportFailed();

        assertThat(response.getMessage()).isEqualTo("Run failed and has been queued for another attempt.");
        verifyNoInteractions(this.jobMail);
        verify(this.bulkAction, never()).changeJobStatus(anyLong(), any());
        verify(this.bulkAction, never()).changeJobQueueStatus(anyLong(), any(), any());
        verify(this.bulkAction, never()).saveJobAuditLogs(anyLong(), anyString());
    }

    @Test
    void theEndpointIsATenantUsersPutUnderMessageJson() throws Exception {
        assertThat(MessageQRestApi.class.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('TENANT_USER')");
        assertThat(MessageQRestApi.class.getAnnotation(RequestMapping.class).value()).containsExactly("/message.json");
        RequestMapping mapping = MessageQRestApi.class.getMethod("changeJobStatus", QueueMessageStatusDto.class)
            .getAnnotation(RequestMapping.class);
        assertThat(mapping.value()).containsExactly("/changeJobStatus");
        assertThat(mapping.method()).containsExactly(RequestMethod.PUT);
    }
}

package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.emailer.EmailMessagesFactory;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * changeJobStatus writes audit lines and run status against a job_queue row, and neither
 * job_queue nor job_audit_logs carries a tenant_id -- so the only thing keeping one tenant out
 * of another's run history is the ownership check in this method. It used to run only when the
 * caller happened to supply a jobId, which made omitting jobId a way around it entirely.
 *
 * Driven through TenantContext rather than a JWT: the context is what the check actually reads.
 */
@ExtendWith(MockitoExtension.class)
class MessageQServiceImplTenantIsolationTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 2002L;
    private static final long JOB_OWNED_BY_B = 55L;
    private static final long JOB_OWNED_BY_A = 66L;
    private static final long QUEUE_OF_B = 91422L;

    @Mock private BulkAction bulkAction;
    @Mock private QueryService queryService;
    @Mock private JobQueueRepository jobQueueRepository;
    @Mock private SourceJobRepository sourceJobRepository;
    @Mock private EmailMessagesFactory emailMessagesFactory;

    private MessageQServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new MessageQServiceImpl(this.bulkAction, this.queryService,
            this.jobQueueRepository, this.sourceJobRepository, this.emailMessagesFactory);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static JobQueue queueFor(long jobId) {
        JobQueue jobQueue = new JobQueue();
        jobQueue.setJobQueueId(QUEUE_OF_B);
        jobQueue.setJobId(jobId);
        jobQueue.setJobStatus(JobStatus.Running);
        return jobQueue;
    }

    private static SourceJob jobOwnedBy(long jobId, long tenantId) {
        SourceJob sourceJob = new SourceJob();
        sourceJob.setJobId(jobId);
        sourceJob.setTenantId(tenantId);
        sourceJob.setJobStatus(Status.Active);
        return sourceJob;
    }

    private static QueueMessageStatusDto auditLogFor(Long jobId, Long jobQueueId) {
        QueueMessageStatusDto queueMessageStatus = new QueueMessageStatusDto();
        queueMessageStatus.setMessageType("AUDIT_LOG");
        queueMessageStatus.setJobId(jobId);
        queueMessageStatus.setJobQueueId(jobQueueId);
        queueMessageStatus.setLogsDetail("Reconciliation completed, 0 discrepancies");
        return queueMessageStatus;
    }

    // ---- omitting jobId must not skip the check ------------------------------------------

    @Test
    void anAuditLineIsRefusedOnAnotherTenantsRunEvenWithNoJobIdSupplied() {
        // The whole point: no jobId in the body used to mean no ownership check at all.
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "a@example.com");
        when(this.jobQueueRepository.findById(QUEUE_OF_B)).thenReturn(Optional.of(queueFor(JOB_OWNED_BY_B)));
        when(this.sourceJobRepository.findById(JOB_OWNED_BY_B))
            .thenReturn(Optional.of(jobOwnedBy(JOB_OWNED_BY_B, TENANT_B)));

        ResponseDto response = this.service.changeJobStatus(auditLogFor(null, QUEUE_OF_B));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("not found");
        verify(this.bulkAction, never()).saveJobAuditLogs(anyLong(), anyString());
    }

    @Test
    void aQueueDetailUpdateIsRefusedOnAnotherTenantsRun() {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "a@example.com");
        when(this.jobQueueRepository.findById(QUEUE_OF_B)).thenReturn(Optional.of(queueFor(JOB_OWNED_BY_B)));
        when(this.sourceJobRepository.findById(JOB_OWNED_BY_B))
            .thenReturn(Optional.of(jobOwnedBy(JOB_OWNED_BY_B, TENANT_B)));
        QueueMessageStatusDto queueMessageStatus = auditLogFor(null, QUEUE_OF_B);
        queueMessageStatus.setMessageType("QUEUE_DETAIL");
        queueMessageStatus.setJobStatus(JobStatus.Failed);

        ResponseDto response = this.service.changeJobStatus(queueMessageStatus);

        assertThat(response.getStatus()).isEqualTo("ERROR");
        verify(this.bulkAction, never()).changeJobQueueStatus(anyLong(), any(), anyString());
        verify(this.bulkAction, never()).changeJobStatus(anyLong(), any());
    }

    // ---- a jobId that disagrees with the queue row ----------------------------------------

    @Test
    void aJobIdThatDoesNotMatchTheQueueRowIsRefused() {
        // Both jobs belong to the caller, so ownership alone would let this through -- the
        // request still points at one job while writing to another job's run.
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "a@example.com");
        when(this.jobQueueRepository.findById(QUEUE_OF_B)).thenReturn(Optional.of(queueFor(JOB_OWNED_BY_A)));
        when(this.sourceJobRepository.findById(JOB_OWNED_BY_A))
            .thenReturn(Optional.of(jobOwnedBy(JOB_OWNED_BY_A, TENANT_A)));

        ResponseDto response = this.service.changeJobStatus(auditLogFor(999L, QUEUE_OF_B));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        verify(this.bulkAction, never()).saveJobAuditLogs(anyLong(), anyString());
    }

    // ---- the request has to name a queue row at all ---------------------------------------

    @Test
    void aMissingJobQueueIdIsRejected() {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "a@example.com");

        ResponseDto response = this.service.changeJobStatus(auditLogFor(JOB_OWNED_BY_A, null));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        verify(this.bulkAction, never()).saveJobAuditLogs(anyLong(), anyString());
    }

    @Test
    void anUnknownJobQueueIsRejected() {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "a@example.com");
        when(this.jobQueueRepository.findById(QUEUE_OF_B)).thenReturn(Optional.empty());

        ResponseDto response = this.service.changeJobStatus(auditLogFor(null, QUEUE_OF_B));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        verify(this.bulkAction, never()).saveJobAuditLogs(anyLong(), anyString());
    }

    // ---- the same call must still work on your own run ------------------------------------

    @Test
    void anAuditLineIsAcceptedOnTheCallersOwnRun() {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "a@example.com");
        when(this.jobQueueRepository.findById(QUEUE_OF_B)).thenReturn(Optional.of(queueFor(JOB_OWNED_BY_A)));
        when(this.sourceJobRepository.findById(JOB_OWNED_BY_A))
            .thenReturn(Optional.of(jobOwnedBy(JOB_OWNED_BY_A, TENANT_A)));

        ResponseDto response = this.service.changeJobStatus(auditLogFor(JOB_OWNED_BY_A, QUEUE_OF_B));

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(this.bulkAction).saveJobAuditLogs(QUEUE_OF_B, "Reconciliation completed, 0 discrepancies");
    }

    @Test
    void aPlatformAdminReachesEveryTenantsRun() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@example.com");
        when(this.jobQueueRepository.findById(QUEUE_OF_B)).thenReturn(Optional.of(queueFor(JOB_OWNED_BY_B)));

        ResponseDto response = this.service.changeJobStatus(auditLogFor(JOB_OWNED_BY_B, QUEUE_OF_B));

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(this.bulkAction).saveJobAuditLogs(QUEUE_OF_B, "Reconciliation completed, 0 discrepancies");
    }

    @Test
    void anEmptyContextIsTreatedAsUntrusted() {
        // No TenantContext at all -- e.g. a code path that forgot to set it.
        when(this.jobQueueRepository.findById(QUEUE_OF_B)).thenReturn(Optional.of(queueFor(JOB_OWNED_BY_B)));
        when(this.sourceJobRepository.findById(JOB_OWNED_BY_B))
            .thenReturn(Optional.of(jobOwnedBy(JOB_OWNED_BY_B, TENANT_B)));

        ResponseDto response = this.service.changeJobStatus(auditLogFor(null, QUEUE_OF_B));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        verify(this.bulkAction, never()).saveJobAuditLogs(anyLong(), anyString());
    }
}

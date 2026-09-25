package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.identity.TestIdentity;
import process.engine.ProducerBulkEngine;
import process.model.dto.ResponseDto;
import process.model.dto.SchedulerDto;
import process.model.dto.SourceJobDto;
import process.model.enums.Execution;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.Scheduler;
import process.model.pojo.SourceJob;
import process.model.repository.*;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.OpenSearchAuditLogClient;
import process.util.UserNameResolver;

import javax.persistence.EntityManager;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import process.notifications.TestNotifications;
import org.junit.jupiter.api.Test;

/**
 * Owner decision 2026-09-24 (MIG-166 follow-up): a Suspended or Inactive workspace's jobs are paused. "Run now"
 * for one is refused with a sentence -- 200 and the ERROR envelope, as every other refusal here -- and never
 * reaches the queue. The answer comes from Core's local view of the workspace (the engine's
 * workspacePause), never from a call to Identity.
 */
@ExtendWith(MockitoExtension.class)
class RunNowPausedWorkspaceTest {

    private static final long TENANT_A = 1001L;
    private static final long CALLER_ID = 77L;
    private static final long JOB_ID = 1244L;

    @Mock private SourceJobRepository sourceJobRepository;
    @Mock private SchedulerRepository schedulerRepository;
    @Mock private SourceTaskRepository sourceTaskRepository;
    @Mock private JobAuditLogRepository jobAuditLogRepository;
    @Mock private TestNotifications.FeedSink jobEventPublisher;
    @Mock private JobQueueRepository jobQueueRepository;
    @Mock private TaskReferenceRepository taskReferenceRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private ProducerBulkEngine producerBulkEngine;
    @Mock private TenantFilterHelper tenantFilterHelper;
    @Mock private OpenSearchAuditLogClient openSearchAuditLogClient;
    @Mock private TestNotifications.NoticeSink notificationCenterService;
    @Mock private EntityManager entityManager;
    @Mock private UserNameResolver userNameResolver;

    private SourceJobServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        this.service = new SourceJobServiceImpl(this.sourceJobRepository, this.schedulerRepository,
            this.sourceTaskRepository, this.jobAuditLogRepository,
            this.jobQueueRepository, this.taskReferenceRepository, TestIdentity.over(this.appUserRepository, null),
            this.producerBulkEngine, this.tenantFilterHelper, this.openSearchAuditLogClient,
            TestNotifications.recording(this.jobEventPublisher, this.notificationCenterService, null), this.userNameResolver);
        Field em = SourceJobServiceImpl.class.getDeclaredField("entityManager");
        em.setAccessible(true);
        em.set(this.service, this.entityManager);
        lenient().doNothing().when(this.tenantFilterHelper).enableIfNeeded(any());
        TenantContext.set(TENANT_A, "TENANT_ADMIN", CALLER_ID, "admin-a@example.com");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private SourceJob autoJobWhoseLastRunIs(JobStatus runningStatus) {
        SourceJob sourceJob = new SourceJob();
        sourceJob.setJobId(JOB_ID);
        sourceJob.setTenantId(TENANT_A);
        sourceJob.setJobStatus(Status.Active);
        sourceJob.setExecution(Execution.Auto);
        sourceJob.setJobRunningStatus(runningStatus);
        when(this.sourceJobRepository.findByJobIdAndJobStatus(JOB_ID, Status.Active))
            .thenReturn(Optional.of(sourceJob));
        return sourceJob;
    }

    private static SourceJobDto request() {
        SourceJobDto sourceJobDto = new SourceJobDto();
        sourceJobDto.setJobId(JOB_ID);
        return sourceJobDto;
    }

    @Test
    void runNowIsRefusedWithASentenceWhileTheWorkspaceIsPaused() throws Exception {
        this.autoJobWhoseLastRunIs(JobStatus.Completed);
        when(this.producerBulkEngine.workspacePause(TENANT_A)).thenReturn(Optional.of("Suspended"));

        ResponseDto response = this.service.runSourceJob(request());

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("Suspended").contains("paused").endsWith(".");
        verify(this.producerBulkEngine, never()).addManualJobInQueue(any());
    }

    @Test
    void runNowGoesAheadWhenTheWorkspaceIsNotPaused() throws Exception {
        this.autoJobWhoseLastRunIs(JobStatus.Completed);
        when(this.producerBulkEngine.workspacePause(TENANT_A)).thenReturn(Optional.empty());

        ResponseDto response = this.service.runSourceJob(request());

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(this.producerBulkEngine).addManualJobInQueue(any());
    }
}

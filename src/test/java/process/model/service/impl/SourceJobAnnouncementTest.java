package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.identity.TestIdentity;
import process.engine.ProducerBulkEngine;
import process.model.dto.SourceJobDto;
import process.model.dto.SourceTaskDto;
import process.model.enums.Execution;
import process.model.enums.Status;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.repository.*;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.OpenSearchAuditLogClient;
import process.util.UserNameResolver;

import javax.persistence.EntityManager;
import java.lang.reflect.Field;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import process.notifications.TestNotifications;

/**
 * That creating, editing, pausing or deleting a job is announced to the other people looking at
 * it.
 *
 * TestNotifications.FeedSink.publishChanged was written for exactly this and had no caller anywhere in the
 * backend -- the publisher was injected into this service and never touched. The jobs table
 * already branches on job.updated, job.toggled and job.deleted, so those branches were simply
 * unreachable: an edit made in one tab, or by a colleague, stayed invisible in another until
 * someone pressed Refresh, on a screen that presents itself as live.
 *
 * The announcement is deliberately made through publishChangedAfterCommit rather than
 * publishChanged. The client answers these events by re-reading the row, and a read started
 * before the writing transaction commits is served the old values.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
public class SourceJobAnnouncementTest {

    private static final long TENANT_A = 1001L;
    private static final long CALLER_ID = 77L;
    private static final long TASK_ID = 4200L;
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

    private static SourceJob jobOwnedByA(Status status) {
        SourceJob sourceJob = new SourceJob();
        sourceJob.setJobId(JOB_ID);
        sourceJob.setTenantId(TENANT_A);
        sourceJob.setJobName("nightly load");
        sourceJob.setJobStatus(status);
        return sourceJob;
    }

    private static SourceJobDto byId() {
        SourceJobDto sourceJobDto = new SourceJobDto();
        sourceJobDto.setJobId(JOB_ID);
        return sourceJobDto;
    }

    @Test
    void creatingAJobAnnouncesItSoOtherOpenListsGainTheRow() throws Exception {
        SourceTask sourceTask = new SourceTask();
        sourceTask.setTaskDetailId(TASK_ID);
        sourceTask.setTenantId(TENANT_A);
        sourceTask.setTaskName("orders nightly");
        sourceTask.setTaskStatus(Status.Active);
        AppUser caller = new AppUser();
        caller.setAppUserId(CALLER_ID);
        caller.setUsername("admin-a@example.com");
        caller.setTenantId(TENANT_A);
        caller.setStatus(Status.Active);
        caller.setUserRole(UserRole.TENANT_ADMIN);
        when(this.sourceTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(sourceTask));
        when(this.appUserRepository.findById(CALLER_ID)).thenReturn(Optional.of(caller));
        // The database assigns the id on save, and the announcement carries it. Left to the bare
        // mock the id stays null, publishChangedAfterCommit drops the event on its null guard,
        // and this would be asserting about a job that was never really created.
        when(this.sourceJobRepository.saveAndFlush(any(SourceJob.class))).thenAnswer(invocation -> {
            SourceJob saved = invocation.getArgument(0);
            saved.setJobId(JOB_ID);
            return saved;
        });

        SourceTaskDto taskDetail = new SourceTaskDto();
        taskDetail.setTaskDetailId(TASK_ID);
        SourceJobDto sourceJobDto = new SourceJobDto();
        sourceJobDto.setJobName("nightly load (copy)");
        sourceJobDto.setTaskDetail(taskDetail);
        sourceJobDto.setExecution(Execution.Manual);
        sourceJobDto.setPriority(1);

        this.service.addSourceJob(sourceJobDto);

        verify(this.jobEventPublisher).publishChangedAfterCommit(TENANT_A, JOB_ID, "job.updated");
    }

    @Test
    void pausingAJobAnnouncesItAsToggled() throws Exception {
        when(this.sourceJobRepository.findById(JOB_ID)).thenReturn(Optional.of(jobOwnedByA(Status.Active)));

        this.service.toggleSourceJobStatus(byId());

        verify(this.jobEventPublisher).publishChangedAfterCommit(TENANT_A, JOB_ID, "job.toggled");
    }

    @Test
    void deletingAJobAnnouncesItSoOtherOpenListsDropTheRow() throws Exception {
        when(this.sourceJobRepository.findById(JOB_ID)).thenReturn(Optional.of(jobOwnedByA(Status.Active)));

        this.service.deleteSourceJob(byId());

        verify(this.jobEventPublisher).publishChangedAfterCommit(TENANT_A, JOB_ID, "job.deleted");
    }

    /** A refused call changed nothing, so it must not claim it did. */
    @Test
    void aJobThatCouldNotBeFoundAnnouncesNothing() throws Exception {
        when(this.sourceJobRepository.findById(JOB_ID)).thenReturn(Optional.empty());

        this.service.toggleSourceJobStatus(byId());

        verifyNoInteractions(this.jobEventPublisher);
    }
}

package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import process.util.UserNameResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.engine.ProducerBulkEngine;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobDto;
import process.model.enums.Status;
import process.model.pojo.SourceJob;
import process.model.repository.*;
import process.model.service.NotificationCenterService;
import process.model.dto.UserActivityDto;
import java.sql.Timestamp;
import java.util.Collections;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.socket.JobEventPublisher;
import process.util.OpenSearchAuditLogClient;

import javax.persistence.EntityManager;
import java.lang.reflect.Field;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Jobs are the core tenant-scoped record, and nothing tested that one tenant cannot reach
 * another's by id. The Hibernate filter hides them from list queries, but a findById by a
 * guessed id bypasses that entirely -- isOwnedByCaller is the only thing standing in the way,
 * so it is what these exercise.
 *
 * Driven through TenantContext rather than a JWT: the context is what every check actually
 * reads, and it needs no running server or minted token to set.
 */
@ExtendWith(MockitoExtension.class)
class SourceJobServiceImplTenantIsolationTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 2002L;
    private static final long JOB_OWNED_BY_B = 55L;

    @Mock private SourceJobRepository sourceJobRepository;
    @Mock private SchedulerRepository schedulerRepository;
    @Mock private SourceTaskRepository sourceTaskRepository;
    @Mock private JobAuditLogRepository jobAuditLogRepository;
    @Mock private JobEventPublisher jobEventPublisher;
    @Mock private JobQueueRepository jobQueueRepository;
    @Mock private LookupDataRepository lookupDataRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private ProducerBulkEngine producerBulkEngine;
    @Mock private TenantFilterHelper tenantFilterHelper;
    @Mock private OpenSearchAuditLogClient openSearchAuditLogClient;
    @Mock private NotificationCenterService notificationCenterService;
    @Mock private EntityManager entityManager;
    @Mock private UserNameResolver userNameResolver;

    private SourceJobServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        this.service = new SourceJobServiceImpl(this.sourceJobRepository, this.schedulerRepository,
            this.sourceTaskRepository, this.jobAuditLogRepository, this.jobEventPublisher,
            this.jobQueueRepository, this.lookupDataRepository, this.appUserRepository,
            this.producerBulkEngine, this.tenantFilterHelper, this.openSearchAuditLogClient,
            this.notificationCenterService, this.userNameResolver);
        // entityManager is injected, not constructor-supplied.
        Field em = SourceJobServiceImpl.class.getDeclaredField("entityManager");
        em.setAccessible(true);
        em.set(this.service, this.entityManager);
        lenient().doNothing().when(this.tenantFilterHelper).enableIfNeeded(any());
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private static SourceJob jobOwnedBy(long tenantId) {
        SourceJob job = new SourceJob();
        job.setJobId(JOB_OWNED_BY_B);
        job.setJobName("Tenant B's nightly export");
        job.setTenantId(tenantId);
        job.setJobStatus(Status.Active);
        return job;
    }

    private static SourceJobDto dtoFor(long jobId) {
        SourceJobDto dto = new SourceJobDto();
        dto.setJobId(jobId);
        return dto;
    }

    // ---- a tenant user must not reach another tenant's job -------------------------------

    @Test
    void deleteRefusesAJobBelongingToAnotherTenant() throws Exception {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "a@example.com");
        when(this.sourceJobRepository.findById(JOB_OWNED_BY_B))
            .thenReturn(Optional.of(jobOwnedBy(TENANT_B)));

        ResponseDto response = this.service.deleteSourceJob(dtoFor(JOB_OWNED_BY_B));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        // The message must not confirm the job exists, or it becomes an id oracle.
        assertThat(response.getMessage()).contains("not found");
        verify(this.sourceJobRepository, never()).save(any());
    }

    @Test
    void toggleStatusRefusesAJobBelongingToAnotherTenant() throws Exception {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "a@example.com");
        when(this.sourceJobRepository.findById(JOB_OWNED_BY_B))
            .thenReturn(Optional.of(jobOwnedBy(TENANT_B)));

        ResponseDto response = this.service.toggleSourceJobStatus(dtoFor(JOB_OWNED_BY_B));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("not found");
        verify(this.sourceJobRepository, never()).save(any());
    }

    @Test
    void aTenantAdminIsStillConfinedToItsOwnTenant() throws Exception {
        // Being an admin of tenant A grants nothing in tenant B.
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 1L, "admin-a@example.com");
        when(this.sourceJobRepository.findById(JOB_OWNED_BY_B))
            .thenReturn(Optional.of(jobOwnedBy(TENANT_B)));

        ResponseDto response = this.service.deleteSourceJob(dtoFor(JOB_OWNED_BY_B));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        verify(this.sourceJobRepository, never()).save(any());
    }

    // ---- the same operations must still work on your own tenant's job --------------------

    @Test
    void deleteAcceptsAJobInTheCallersOwnTenant() throws Exception {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "a@example.com");
        when(this.sourceJobRepository.findById(JOB_OWNED_BY_B))
            .thenReturn(Optional.of(jobOwnedBy(TENANT_A)));

        ResponseDto response = this.service.deleteSourceJob(dtoFor(JOB_OWNED_BY_B));

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(this.sourceJobRepository).save(any());
    }

    @Test
    void aPlatformAdminReachesEveryTenant() throws Exception {
        // The one role that legitimately crosses tenants.
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@example.com");
        when(this.sourceJobRepository.findById(JOB_OWNED_BY_B))
            .thenReturn(Optional.of(jobOwnedBy(TENANT_B)));

        ResponseDto response = this.service.deleteSourceJob(dtoFor(JOB_OWNED_BY_B));

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(this.sourceJobRepository).save(any());
    }

    // ---- a caller with no tenant at all --------------------------------------------------

    @Test
    void aContextWithNoTenantCannotReachATenantsJob() throws Exception {
        // A null tenant must not compare equal to a real one.
        TenantContext.set(null, "TENANT_USER", 1L, "orphan@example.com");
        when(this.sourceJobRepository.findById(JOB_OWNED_BY_B))
            .thenReturn(Optional.of(jobOwnedBy(TENANT_B)));

        ResponseDto response = this.service.deleteSourceJob(dtoFor(JOB_OWNED_BY_B));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        verify(this.sourceJobRepository, never()).save(any());
    }

    @Test
    void anEmptyContextIsTreatedAsUntrusted() throws Exception {
        // No TenantContext at all -- e.g. a code path that forgot to set it.
        when(this.sourceJobRepository.findById(JOB_OWNED_BY_B))
            .thenReturn(Optional.of(jobOwnedBy(TENANT_B)));

        ResponseDto response = this.service.deleteSourceJob(dtoFor(JOB_OWNED_BY_B));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        verify(this.sourceJobRepository, never()).save(any());
    }

    // ---- myActivity: scoped to the caller, never to a parameter ----------------------------

    /**
     * The screen used to fetch every job in the tenant and keep the ones whose assignedUsername
     * matched the signed-in person. This asserts the replacement asks the database about one id --
     * the caller's own, taken from the context rather than from the request.
     */
    @Test
    void myActivityAsksOnlyAboutTheCallersOwnId() throws Exception {
        TenantContext.set(5L, "TENANT_USER", 1000L, "someone@tenant.example");
        when(this.sourceJobRepository.countAssignedTo(1000L))
            .thenReturn(Collections.singletonList(new Object[] { 7L, 0L }));
        when(this.jobQueueRepository.countRecentRunsForAssignee(eq(1000L), any()))
            .thenReturn(Collections.singletonList(new Object[] { 2L, 0L }));
        when(this.jobQueueRepository.findRecentRunsForAssignee(1000L, 8))
            .thenReturn(Collections.singletonList(new Object[] {
                5073L, 2004L, "Pacific hurricanes 2000-2004", "Completed",
                Timestamp.valueOf("2026-08-27 12:25:03"), Timestamp.valueOf("2026-08-27 12:26:09"), "ok" }));
        when(this.sourceJobRepository.outcomesForAssignee(1000L))
            .thenReturn(Collections.singletonList(new Object[] { "Completed", 2L }));

        ResponseDto response = this.service.fetchMyActivity(8, 7);
        UserActivityDto activity = (UserActivityDto) response.getData();

        assertThat(activity.getJobsAssigned()).isEqualTo(7L);
        assertThat(activity.getRecentRuns()).isEqualTo(2L);
        assertThat(activity.getWindowDays()).isEqualTo(7);
        assertThat(activity.getRuns()).hasSize(1);
        assertThat(activity.getRuns().get(0).getJobName()).isEqualTo("Pacific hurricanes 2000-2004");
        assertThat(activity.getRuns().get(0).getStartTime()).isNotNull();
        assertThat(activity.getOutcomes()).hasSize(1);
        // No other id was ever asked about.
        verify(this.sourceJobRepository, never()).countAssignedTo(argThat(id -> !Long.valueOf(1000L).equals(id)));
    }

    /** A completed run's message is noise on a profile; only a failure has something to explain. */
    @Test
    void onlyAFailedRunCarriesItsMessage() throws Exception {
        TenantContext.set(5L, "TENANT_USER", 1000L, "someone@tenant.example");
        when(this.jobQueueRepository.findRecentRunsForAssignee(1000L, 8)).thenReturn(java.util.Arrays.asList(
            new Object[] { 1L, 2L, "Fine job", "Completed", Timestamp.valueOf("2026-08-27 12:00:00"),
                Timestamp.valueOf("2026-08-27 12:01:00"), "finished cleanly" },
            new Object[] { 2L, 3L, "Broken job", "Failed", Timestamp.valueOf("2026-08-27 13:00:00"),
                Timestamp.valueOf("2026-08-27 13:00:30"), "connection refused" }));

        UserActivityDto activity = (UserActivityDto) this.service.fetchMyActivity(8, 7).getData();

        assertThat(activity.getRuns().get(0).getJobStatusMessage()).isNull();
        assertThat(activity.getRuns().get(1).getJobStatusMessage()).isEqualTo("connection refused");
    }

    /** Nobody signed in is an empty card, not a failed page -- the activity is supplementary. */
    @Test
    void noSignedInUserYieldsAnEmptyActivityRatherThanAnError() throws Exception {
        ResponseDto response = this.service.fetchMyActivity(8, 7);

        UserActivityDto activity = (UserActivityDto) response.getData();
        assertThat(activity.getJobsAssigned()).isZero();
        assertThat(activity.getRuns()).isEmpty();
        verifyNoInteractions(this.jobQueueRepository);
    }

}

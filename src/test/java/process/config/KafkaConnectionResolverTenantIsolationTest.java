package process.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.enums.Status;
import process.model.pojo.KafkaConnectionProfile;
import process.model.pojo.SourceTaskType;
import process.model.pojo.TenantTaskTypeKafkaRoute;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantTaskTypeKafkaRouteRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The resolver decides which brokers a dispatch talks to, and it used to take the task type's
 * profile on trust: a task type belonging to tenant B, reached from a job belonging to tenant A,
 * handed back tenant B's Kafka profile and the job's payload went to B's brokers.
 *
 * The scope it checks against is the tenant of the row being dispatched, not a signed-in
 * principal -- these calls come from scheduler, startup and Kafka threads that have no principal
 * -- so the tests below drive it exactly as those threads do, with no TenantContext set.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class KafkaConnectionResolverTenantIsolationTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 1002L;
    private static final long TASK_TYPE_ID = 2001L;
    private static final long PROFILE_OF_B = 3001L;
    private static final long PROFILE_OF_A = 3002L;
    private static final long PLATFORM_PROFILE = 3003L;

    @Mock private TenantTaskTypeKafkaRouteRepository routeRepository;
    @Mock private SourceTaskTypeRepository sourceTaskTypeRepository;
    @Mock private KafkaConnectionProfileRepository profileRepository;

    private KafkaConnectionResolver resolver;

    @BeforeEach
    void setUp() {
        this.resolver = new KafkaConnectionResolver(this.routeRepository,
            this.sourceTaskTypeRepository, this.profileRepository);
    }

    @Test
    void aTaskTypeBelongingToAnotherTenantDoesNotLendItsProfile() {
        when(this.routeRepository.findByTenantIdAndSourceTaskTypeId(TENANT_A, TASK_TYPE_ID))
            .thenReturn(Optional.empty());
        when(this.sourceTaskTypeRepository.findById(TASK_TYPE_ID))
            .thenReturn(Optional.of(taskType(TENANT_B, PROFILE_OF_B)));
        when(this.profileRepository.findByTenantIdAndIsDefaultTrueAndStatus(TENANT_A, Status.Active))
            .thenReturn(Optional.of(profile(PROFILE_OF_A, TENANT_A)));

        Optional<KafkaConnectionProfile> resolved = this.resolver.resolve(TENANT_A, TASK_TYPE_ID);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getKafkaConnectionProfileId()).isEqualTo(PROFILE_OF_A);
    }

    @Test
    void aTaskTypeOwnedByTheSameTenantStillLendsItsProfile() {
        when(this.routeRepository.findByTenantIdAndSourceTaskTypeId(TENANT_A, TASK_TYPE_ID))
            .thenReturn(Optional.empty());
        when(this.sourceTaskTypeRepository.findById(TASK_TYPE_ID))
            .thenReturn(Optional.of(taskType(TENANT_A, PROFILE_OF_A)));
        when(this.profileRepository.findById(PROFILE_OF_A))
            .thenReturn(Optional.of(profile(PROFILE_OF_A, TENANT_A)));

        Optional<KafkaConnectionProfile> resolved = this.resolver.resolve(TENANT_A, TASK_TYPE_ID);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getKafkaConnectionProfileId()).isEqualTo(PROFILE_OF_A);
    }

    @Test
    void aPlatformOwnedTaskTypeLendsItsProfileToEveryTenant() {
        when(this.routeRepository.findByTenantIdAndSourceTaskTypeId(TENANT_A, TASK_TYPE_ID))
            .thenReturn(Optional.empty());
        when(this.sourceTaskTypeRepository.findById(TASK_TYPE_ID))
            .thenReturn(Optional.of(taskType(null, PLATFORM_PROFILE)));
        when(this.profileRepository.findById(PLATFORM_PROFILE))
            .thenReturn(Optional.of(profile(PLATFORM_PROFILE, null)));

        Optional<KafkaConnectionProfile> resolved = this.resolver.resolve(TENANT_A, TASK_TYPE_ID);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getKafkaConnectionProfileId()).isEqualTo(PLATFORM_PROFILE);
    }

    @Test
    void aRoutePointingAtAnotherTenantsProfileIsIgnored() {
        TenantTaskTypeKafkaRoute route = new TenantTaskTypeKafkaRoute();
        route.setTenantId(TENANT_A);
        route.setSourceTaskTypeId(TASK_TYPE_ID);
        route.setKafkaConnectionProfileId(PROFILE_OF_B);
        when(this.routeRepository.findByTenantIdAndSourceTaskTypeId(TENANT_A, TASK_TYPE_ID))
            .thenReturn(Optional.of(route));
        when(this.profileRepository.findById(PROFILE_OF_B))
            .thenReturn(Optional.of(profile(PROFILE_OF_B, TENANT_B)));
        when(this.sourceTaskTypeRepository.findById(TASK_TYPE_ID))
            .thenReturn(Optional.of(taskType(TENANT_A, null)));
        when(this.profileRepository.findByTenantIdAndIsDefaultTrueAndStatus(TENANT_A, Status.Active))
            .thenReturn(Optional.of(profile(PROFILE_OF_A, TENANT_A)));

        Optional<KafkaConnectionProfile> resolved = this.resolver.resolve(TENANT_A, TASK_TYPE_ID);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getKafkaConnectionProfileId()).isEqualTo(PROFILE_OF_A);
    }

    /**
     * A dispatch that names no tenant is the platform's own, so it reaches platform-owned rows
     * only. It still resolves -- to the platform default -- because refusing it outright would
     * stop the background dispatch rather than confine it.
     */
    @Test
    void aScopelessDispatchFallsBackToThePlatformDefaultRatherThanBorrowingATenantsProfile() {
        when(this.sourceTaskTypeRepository.findById(TASK_TYPE_ID))
            .thenReturn(Optional.of(taskType(TENANT_B, PROFILE_OF_B)));
        when(this.profileRepository.findByTenantIdIsNullAndIsDefaultTrueAndStatus(Status.Active))
            .thenReturn(Optional.of(profile(PLATFORM_PROFILE, null)));

        Optional<KafkaConnectionProfile> resolved = this.resolver.resolve(null, TASK_TYPE_ID);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getKafkaConnectionProfileId()).isEqualTo(PLATFORM_PROFILE);
    }

    @Test
    void anInactiveProfileIsStillSkipped() {
        when(this.routeRepository.findByTenantIdAndSourceTaskTypeId(TENANT_A, TASK_TYPE_ID))
            .thenReturn(Optional.empty());
        when(this.sourceTaskTypeRepository.findById(TASK_TYPE_ID))
            .thenReturn(Optional.of(taskType(TENANT_A, PROFILE_OF_A)));
        KafkaConnectionProfile inactive = profile(PROFILE_OF_A, TENANT_A);
        inactive.setStatus(Status.Inactive);
        when(this.profileRepository.findById(PROFILE_OF_A)).thenReturn(Optional.of(inactive));
        when(this.profileRepository.findByTenantIdAndIsDefaultTrueAndStatus(TENANT_A, Status.Active))
            .thenReturn(Optional.empty());
        when(this.profileRepository.findByTenantIdIsNullAndIsDefaultTrueAndStatus(Status.Active))
            .thenReturn(Optional.of(profile(PLATFORM_PROFILE, null)));

        Optional<KafkaConnectionProfile> resolved = this.resolver.resolve(TENANT_A, TASK_TYPE_ID);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getKafkaConnectionProfileId()).isEqualTo(PLATFORM_PROFILE);
    }

    private static SourceTaskType taskType(Long tenantId, Long kafkaConnectionProfileId) {
        SourceTaskType sourceTaskType = new SourceTaskType();
        sourceTaskType.setSourceTaskTypeId(TASK_TYPE_ID);
        sourceTaskType.setTenantId(tenantId);
        sourceTaskType.setKafkaConnectionProfileId(kafkaConnectionProfileId);
        return sourceTaskType;
    }

    private static KafkaConnectionProfile profile(Long profileId, Long tenantId) {
        KafkaConnectionProfile profile = new KafkaConnectionProfile();
        profile.setKafkaConnectionProfileId(profileId);
        profile.setTenantId(tenantId);
        profile.setStatus(Status.Active);
        return profile;
    }

}

package process.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.enums.Status;
import process.model.pojo.KafkaConnectionProfile;
import process.model.pojo.SourceTaskType;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantTaskTypeKafkaRouteRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * MIG-45: where a workspace's dispatch goes when neither a route nor a task-type default names a
 * connection -- characterised as it stands before the fallback is removed.
 */
@ExtendWith(MockitoExtension.class)
class KafkaRouteResolutionTest {

    private static final long TENANT = 4501L;
    private static final long TASK_TYPE_ID = 4502L;
    private static final long OWN_PROFILE = 4503L;
    private static final long PLATFORM_DEFAULT = 1L;

    @Mock private TenantTaskTypeKafkaRouteRepository routeRepository;
    @Mock private SourceTaskTypeRepository sourceTaskTypeRepository;
    @Mock private KafkaConnectionProfileRepository profileRepository;

    private KafkaConnectionResolver resolver;

    @BeforeEach
    void setUp() {
        this.resolver = new KafkaConnectionResolver(this.routeRepository, this.sourceTaskTypeRepository, this.profileRepository);
        lenient().when(this.routeRepository.findByTenantIdAndSourceTaskTypeId(TENANT, TASK_TYPE_ID)).thenReturn(Optional.empty());
        lenient().when(this.sourceTaskTypeRepository.findById(TASK_TYPE_ID)).thenReturn(Optional.of(taskType()));
        lenient().when(this.profileRepository.findByTenantIdAndIsDefaultTrueAndStatus(TENANT, Status.Active))
            .thenReturn(Optional.empty());
        lenient().when(this.profileRepository.findByTenantIdIsNullAndIsDefaultTrueAndStatus(Status.Active))
            .thenReturn(Optional.of(profile(PLATFORM_DEFAULT, null)));
    }

    /** Characterisation (before MIG-45's rule): a workspace with a profile of its own, but no route, lands on the platform's. */
    @Test
    void today_aTenantWithItsOwnProfileButNoRouteTakesThePlatformDefault() {
        lenient().when(this.profileRepository.countByTenantIdAndStatusNot(TENANT, Status.Delete)).thenReturn(1L);

        assertThat(this.resolver.resolve(TENANT, TASK_TYPE_ID))
            .map(KafkaConnectionProfile::getKafkaConnectionProfileId).contains(PLATFORM_DEFAULT);
    }

    /** Tier 4 proper: a workspace with no Kafka of its own is on the platform's brokers. That is resolution, not fallback. */
    @Test
    void aTenantWithNoProfilesOfItsOwnTakesThePlatformDefault() {
        lenient().when(this.profileRepository.countByTenantIdAndStatusNot(TENANT, Status.Delete)).thenReturn(0L);

        assertThat(this.resolver.resolve(TENANT, TASK_TYPE_ID))
            .map(KafkaConnectionProfile::getKafkaConnectionProfileId).contains(PLATFORM_DEFAULT);
    }

    /** Tier 3: the workspace's own default wins over the platform's. */
    @Test
    void aTenantDefaultIsUsedBeforeThePlatformDefault() {
        when(this.profileRepository.findByTenantIdAndIsDefaultTrueAndStatus(TENANT, Status.Active))
            .thenReturn(Optional.of(profile(OWN_PROFILE, TENANT)));

        assertThat(this.resolver.resolve(TENANT, TASK_TYPE_ID))
            .map(KafkaConnectionProfile::getKafkaConnectionProfileId).contains(OWN_PROFILE);
    }

    private static SourceTaskType taskType() {
        SourceTaskType taskType = new SourceTaskType();
        taskType.setSourceTaskTypeId(TASK_TYPE_ID);
        taskType.setServiceName("claims-intake");
        taskType.setTenantId(TENANT);
        return taskType;
    }

    private static KafkaConnectionProfile profile(Long profileId, Long tenantId) {
        KafkaConnectionProfile profile = new KafkaConnectionProfile();
        profile.setKafkaConnectionProfileId(profileId);
        profile.setTenantId(tenantId);
        profile.setStatus(Status.Active);
        return profile;
    }
}

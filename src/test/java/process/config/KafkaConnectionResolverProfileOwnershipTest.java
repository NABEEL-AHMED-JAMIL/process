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
 * The ownership check the resolver makes on a source task type, in the one shape where it decides
 * the answer on its own.
 *
 * KafkaConnectionResolverTenantIsolationTest covers this branch with a profile owned by another
 * tenant, which activeProfile refuses a second time -- so those cases resolve the same way whether
 * the check on the task type runs or not. A platform-owned profile passes that second check, being
 * shared with everyone by design, and then only the check on the task type stands between a
 * dispatch and the brokers a different scope chose. That is what these pin.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class KafkaConnectionResolverProfileOwnershipTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 1002L;
    private static final long TASK_TYPE_ID = 2001L;
    private static final long PROFILE_OF_A = 3002L;
    private static final long PLATFORM_DEFAULT = 3003L;
    private static final long PLATFORM_PROFILE_B_PICKED = 3004L;

    @Mock private TenantTaskTypeKafkaRouteRepository routeRepository;
    @Mock private SourceTaskTypeRepository sourceTaskTypeRepository;
    @Mock private KafkaConnectionProfileRepository profileRepository;

    private KafkaConnectionResolver resolver;

    @BeforeEach
    void setUp() {
        this.resolver = new KafkaConnectionResolver(this.routeRepository,
            this.sourceTaskTypeRepository, this.profileRepository);
    }

    /**
     * Tenant B names a platform profile as its task type's default. A job of tenant A's that runs
     * through that task type keeps tenant A's own default instead, so the payload goes to the
     * brokers tenant A was configured for rather than the ones tenant B picked.
     */
    @Test
    void aTaskTypeOfAnotherTenantDoesNotChooseThePlatformProfileForThisDispatch() {
        when(this.routeRepository.findByTenantIdAndSourceTaskTypeId(TENANT_A, TASK_TYPE_ID))
            .thenReturn(Optional.empty());
        when(this.sourceTaskTypeRepository.findById(TASK_TYPE_ID))
            .thenReturn(Optional.of(taskType(TENANT_B, PLATFORM_PROFILE_B_PICKED)));
        // On the repository and Active, so nothing but the ownership check keeps it out of the answer.
        lenient().when(this.profileRepository.findById(PLATFORM_PROFILE_B_PICKED))
            .thenReturn(Optional.of(profile(PLATFORM_PROFILE_B_PICKED, null)));
        when(this.profileRepository.findByTenantIdAndIsDefaultTrueAndStatus(TENANT_A, Status.Active))
            .thenReturn(Optional.of(profile(PROFILE_OF_A, TENANT_A)));

        Optional<KafkaConnectionProfile> resolved = this.resolver.resolve(TENANT_A, TASK_TYPE_ID);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getKafkaConnectionProfileId()).isEqualTo(PROFILE_OF_A);
    }

    /**
     * And the same rule read the other way: a dispatch that names no tenant is the platform's own,
     * so it takes the platform default rather than the platform profile a tenant happened to attach
     * to its task type.
     */
    @Test
    void aScopelessDispatchTakesThePlatformDefaultNotATenantTaskTypesChoice() {
        when(this.sourceTaskTypeRepository.findById(TASK_TYPE_ID))
            .thenReturn(Optional.of(taskType(TENANT_B, PLATFORM_PROFILE_B_PICKED)));
        lenient().when(this.profileRepository.findById(PLATFORM_PROFILE_B_PICKED))
            .thenReturn(Optional.of(profile(PLATFORM_PROFILE_B_PICKED, null)));
        when(this.profileRepository.findByTenantIdIsNullAndIsDefaultTrueAndStatus(Status.Active))
            .thenReturn(Optional.of(profile(PLATFORM_DEFAULT, null)));

        Optional<KafkaConnectionProfile> resolved = this.resolver.resolve(null, TASK_TYPE_ID);

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getKafkaConnectionProfileId()).isEqualTo(PLATFORM_DEFAULT);
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

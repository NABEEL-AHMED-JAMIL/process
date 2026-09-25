package process.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * MIG-45 (DEF-127), the owner's decision of 2026-09-24: the silent Kafka fallback is deleted. A send no
 * connection resolves for is refused with a sentence a person can act on, and every refusal is counted
 * (process.kafka.route.unresolved) -- the meter that used to count fallback sends counts refusals now.
 * The template provider has no template for "no profile" at all.
 */
@ExtendWith(MockitoExtension.class)
class KafkaRouteRefusalTest {

    private static final long TENANT = 4501L;
    private static final long TASK_TYPE_ID = 4502L;

    @Mock private TenantTaskTypeKafkaRouteRepository routeRepository;
    @Mock private SourceTaskTypeRepository sourceTaskTypeRepository;
    @Mock private KafkaConnectionProfileRepository profileRepository;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private KafkaConnectionResolver resolver;

    @BeforeEach
    void setUp() {
        this.resolver = new KafkaConnectionResolver(this.routeRepository, this.sourceTaskTypeRepository, this.profileRepository);
        this.resolver.setMeterRegistry(this.meters);
        lenient().when(this.routeRepository.findByTenantIdAndSourceTaskTypeId(TENANT, TASK_TYPE_ID)).thenReturn(Optional.empty());
        SourceTaskType taskType = new SourceTaskType();
        taskType.setSourceTaskTypeId(TASK_TYPE_ID);
        taskType.setServiceName("claims-intake");
        taskType.setTenantId(TENANT);
        lenient().when(this.sourceTaskTypeRepository.findById(TASK_TYPE_ID)).thenReturn(Optional.of(taskType));
        lenient().when(this.profileRepository.findByTenantIdAndIsDefaultTrueAndStatus(TENANT, Status.Active))
            .thenReturn(Optional.empty());
        lenient().when(this.profileRepository.countByTenantIdAndStatusNot(TENANT, Status.Delete)).thenReturn(1L);
        lenient().when(this.profileRepository.findByTenantIdIsNullAndIsDefaultTrueAndStatus(Status.Active))
            .thenReturn(Optional.of(platformDefault()));
    }

    @Test
    void aWorkspaceWithItsOwnProfilesButNoRouteIsRefusedInWordsAPersonCanActOn() {
        assertThatThrownBy(() -> this.resolver.require(TENANT, TASK_TYPE_ID))
            .isInstanceOf(KafkaRouteUnresolvedException.class)
            .hasMessage("No Kafka connection is set for task type 'claims-intake' in this workspace: "
                + "set a route or a default connection.");
        assertThat(this.meters.counter(KafkaConnectionResolver.UNRESOLVED_METER).count()).isEqualTo(1.0);
    }

    @Test
    void aWorkspaceEventWithoutADefaultIsRefusedToo() {
        assertThatThrownBy(() -> this.resolver.require(TENANT, null))
            .isInstanceOf(KafkaRouteUnresolvedException.class)
            .hasMessage("No Kafka connection is set for this workspace: set a default connection.");
    }

    @Test
    void thePlatformWithoutADefaultIsRefusedToo() {
        when(this.profileRepository.findByTenantIdIsNullAndIsDefaultTrueAndStatus(Status.Active)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> this.resolver.require(null, null))
            .isInstanceOf(KafkaRouteUnresolvedException.class)
            .hasMessage("No platform default Kafka connection is set: set one on Kafka connections.");
    }

    @Test
    void aWorkspaceWithNoProfilesOfItsOwnIsResolvedNotRefused() {
        when(this.profileRepository.countByTenantIdAndStatusNot(TENANT, Status.Delete)).thenReturn(0L);

        assertThat(this.resolver.require(TENANT, TASK_TYPE_ID).getKafkaConnectionProfileId()).isEqualTo(1L);
        assertThat(this.meters.counter(KafkaConnectionResolver.UNRESOLVED_METER).count()).as("not a refusal").isZero();
    }

    @Test
    void withoutARegistryTheRefusalIsStillThrown() {
        KafkaConnectionResolver bare = new KafkaConnectionResolver(this.routeRepository, this.sourceTaskTypeRepository,
            this.profileRepository);

        assertThatThrownBy(() -> bare.require(TENANT, TASK_TYPE_ID)).isInstanceOf(KafkaRouteUnresolvedException.class);
    }

    /** The provider has no fallback template to hand back: asked without a profile, it refuses. */
    @Test
    void theTemplateProviderHasNoTemplateForNoProfile() {
        assertThatThrownBy(() -> new KafkaTemplateProvider(null, null).getTemplate(null))
            .isInstanceOf(KafkaRouteUnresolvedException.class);
    }

    private static KafkaConnectionProfile platformDefault() {
        KafkaConnectionProfile profile = new KafkaConnectionProfile();
        profile.setKafkaConnectionProfileId(1L);
        profile.setStatus(Status.Active);
        profile.setIsDefault(true);
        return profile;
    }
}

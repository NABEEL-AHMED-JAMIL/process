package process.config;

import org.springframework.stereotype.Component;
import process.model.enums.Status;
import process.model.pojo.KafkaConnectionProfile;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantTaskTypeKafkaRouteRepository;
import java.util.Optional;

/**
 * Decides which Kafka cluster a given (tenant, SourceTaskType) publish should go to. Read-only
 * -- doesn't build/cache any producer itself, see KafkaTemplateProvider for that.
 *
 * Resolution order (first Active hit wins):
 *   1. tenant_task_type_kafka_route  -- this tenant's explicit override for this task type
 *   2. source_task_type.kafka_connection_profile_id -- the task type's own default cluster
 *   3. kafka_connection_profile where tenantId = this tenant AND isDefault -- the tenant's own default
 *   4. kafka_connection_profile where tenantId IS NULL AND isDefault -- the platform-wide shared default
 *   5. (caller falls back to the env-var-driven default KafkaTemplate -- this class returns
 *      empty and lets KafkaTemplateProvider handle that fallback, exactly as it already does
 *      today when nothing is configured)
 * @author Nabeel Ahmed
 */
@Component
public class KafkaConnectionResolver {

    private final TenantTaskTypeKafkaRouteRepository routeRepository;
    private final SourceTaskTypeRepository sourceTaskTypeRepository;
    private final KafkaConnectionProfileRepository profileRepository;

    public KafkaConnectionResolver(TenantTaskTypeKafkaRouteRepository routeRepository,
        SourceTaskTypeRepository sourceTaskTypeRepository,
        KafkaConnectionProfileRepository profileRepository) {
        this.routeRepository = routeRepository;
        this.sourceTaskTypeRepository = sourceTaskTypeRepository;
        this.profileRepository = profileRepository;
    }

    /**
     * Method use to resolve the Kafka connection profile a (tenant, sourceTaskType) publish
     * should use, following the 5-step order in the class javadoc. tenantId may be null (a
     * PLATFORM_ADMIN-owned job) -- in that case steps 1 and 3 are skipped (there's no tenant to
     * check an override/default for) and resolution goes straight to the task type's own
     * default, then the platform-wide shared default.
     * @param tenantId
     * @param sourceTaskTypeId
     * @return Optional<KafkaConnectionProfile> -- empty means "use the env-var-driven fallback"
     * */
    public Optional<KafkaConnectionProfile> resolve(Long tenantId, Long sourceTaskTypeId) {
        if (tenantId != null && sourceTaskTypeId != null) {
            Optional<KafkaConnectionProfile> viaRoute = this.routeRepository
                .findByTenantIdAndSourceTaskTypeId(tenantId, sourceTaskTypeId)
                .flatMap(route -> this.activeProfile(route.getKafkaConnectionProfileId()));
            if (viaRoute.isPresent()) {
                return viaRoute;
            }
        }
        if (sourceTaskTypeId != null) {
            Optional<KafkaConnectionProfile> viaTaskTypeDefault = this.sourceTaskTypeRepository.findById(sourceTaskTypeId)
                .map(type -> type.getKafkaConnectionProfileId())
                .flatMap(this::activeProfile);
            if (viaTaskTypeDefault.isPresent()) {
                return viaTaskTypeDefault;
            }
        }
        if (tenantId != null) {
            Optional<KafkaConnectionProfile> viaTenantDefault =
                this.profileRepository.findByTenantIdAndIsDefaultTrueAndStatus(tenantId, Status.Active);
            if (viaTenantDefault.isPresent()) {
                return viaTenantDefault;
            }
        }
        return this.profileRepository.findByTenantIdIsNullAndIsDefaultTrueAndStatus(Status.Active);
    }

    private Optional<KafkaConnectionProfile> activeProfile(Long profileId) {
        if (profileId == null) {
            return Optional.empty();
        }
        return this.profileRepository.findById(profileId)
            .filter(profile -> profile.getStatus() == Status.Active);
    }

}

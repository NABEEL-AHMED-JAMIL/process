package process.config;

import org.springframework.stereotype.Component;
import process.model.enums.Status;
import process.model.pojo.KafkaConnectionProfile;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantTaskTypeKafkaRouteRepository;
import java.util.Optional;

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

package process.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import process.model.enums.Status;
import process.model.pojo.KafkaConnectionProfile;
import process.model.pojo.SourceTaskType;
import process.model.repository.KafkaConnectionProfileRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantTaskTypeKafkaRouteRepository;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 * */
@Component
public class KafkaConnectionResolver {

    private final Logger logger = LoggerFactory.getLogger(KafkaConnectionResolver.class);

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
     * Which brokers a dispatch should go to, most specific binding first.
     *
     * tenantId is the scope of the row being dispatched -- the job's tenant, or the task type's --
     * and deliberately not a signed-in principal. Every caller here runs on a scheduler, startup
     * or Kafka thread where a system dispatch legitimately has no principal at all, so reading
     * TenantContext would refuse the background work that makes up nearly all of it. The scope
     * travels with the data instead, which is available on every one of those threads.
     *
     * A null scope means the platform rather than "any tenant", so a dispatch that names no tenant
     * reaches platform-owned rows only. Nothing here fails hard on a mismatch: a binding that does
     * not belong to the scope is ignored and the next fallback applies, so a mis-bound row
     * degrades to the tenant or platform default instead of silently borrowing another tenant's
     * brokers -- and instead of stopping the dispatch.
     */
    public Optional<KafkaConnectionProfile> resolve(Long tenantId, Long sourceTaskTypeId) {
        if (tenantId != null && sourceTaskTypeId != null) {
            Optional<KafkaConnectionProfile> viaRoute = this.routeRepository
                .findByTenantIdAndSourceTaskTypeId(tenantId, sourceTaskTypeId)
                .flatMap(route -> this.activeProfile(tenantId, route.getKafkaConnectionProfileId()));
            if (viaRoute.isPresent()) {
                return viaRoute;
            }
        }
        if (sourceTaskTypeId != null) {
            Optional<SourceTaskType> sourceTaskType = this.sourceTaskTypeRepository.findById(sourceTaskTypeId);
            if (sourceTaskType.isPresent()) {
                Long ownerTenantId = sourceTaskType.get().getTenantId();
                if (this.isUsableBy(tenantId, ownerTenantId)) {
                    Optional<KafkaConnectionProfile> viaTaskTypeDefault =
                        this.activeProfile(tenantId, sourceTaskType.get().getKafkaConnectionProfileId());
                    if (viaTaskTypeDefault.isPresent()) {
                        return viaTaskTypeDefault;
                    }
                } else {
                    this.logger.warn("Source task type {} belongs to tenant {}, not to tenant {} -- its Kafka "
                        + "profile is not used for this dispatch.", sourceTaskTypeId, ownerTenantId, tenantId);
                }
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

    private Optional<KafkaConnectionProfile> activeProfile(Long tenantId, Long profileId) {
        if (profileId == null) {
            return Optional.empty();
        }
        return this.profileRepository.findById(profileId)
            .filter(profile -> profile.getStatus() == Status.Active)
            .filter(profile -> {
                if (this.isUsableBy(tenantId, profile.getTenantId())) {
                    return true;
                }
                this.logger.warn("Kafka connection profile {} belongs to tenant {}, not to tenant {} -- ignoring it.",
                    profileId, profile.getTenantId(), tenantId);
                return false;
            });
    }

    /**
     * Whether a row owned by ownerTenantId may be used by a dispatch scoped to tenantId.
     *
     * A null owner is the platform's and is shared with every tenant, which is the same rule the
     * entities state in SQL through their tenant filters.
     */
    private boolean isUsableBy(Long tenantId, Long ownerTenantId) {
        return ownerTenantId == null || ownerTenantId.equals(tenantId);
    }

}

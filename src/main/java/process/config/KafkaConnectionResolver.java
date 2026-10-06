package process.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** Counts sends refused because no Kafka connection resolved for them (MIG-45). There is no fallback to count. */
    public static final String UNRESOLVED_METER = "process.kafka.route.unresolved";

    private MeterRegistry meterRegistry;

    /** Optional, so the tests that build this by hand need not supply one; the refusal is thrown either way. */
    @Autowired(required = false)
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
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
     * reaches platform-owned rows only. A binding that does not belong to the scope is ignored and
     * the next tier applies, so a mis-bound row never borrows another tenant's brokers.
     *
     * The platform default (tier 4) is for a workspace with no Kafka of its own -- that is
     * resolution, not a fallback. A workspace with ANY profile of its own (inactive included; only a
     * deleted one does not count) has brought its own brokers, and is never put on the platform's
     * because a route or a default is missing: it resolves to nothing, and the send is refused
     * (MIG-45, owner decision 2026-09-24). Empty means refuse -- there is no template behind it.
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
            if (this.hasProfilesOfItsOwn(tenantId)) {
                return Optional.empty();
            }
        }
        return this.profileRepository.findByTenantIdIsNullAndIsDefaultTrueAndStatus(Status.Active);
    }

    /**
     * The profile a send must use, or a refusal: KafkaRouteUnresolvedException, its message the
     * sentence the run's status line shows. Every refusal is counted on UNRESOLVED_METER.
     */
    public KafkaConnectionProfile require(Long tenantId, Long sourceTaskTypeId) {
        Optional<KafkaConnectionProfile> resolved = this.resolve(tenantId, sourceTaskTypeId);
        if (resolved.isPresent()) {
            return resolved.get();
        }
        if (this.meterRegistry != null) {
            this.meterRegistry.counter(UNRESOLVED_METER).increment();
        }
        throw new KafkaRouteUnresolvedException(this.unresolvedMessage(tenantId, sourceTaskTypeId));
    }

    /** What a person is told when nothing resolves: what is missing, and what to set. */
    String unresolvedMessage(Long tenantId, Long sourceTaskTypeId) {
        if (tenantId == null) {
            return "No platform default Kafka connection is set: set one on Kafka connections.";
        }
        if (sourceTaskTypeId == null) {
            return "No Kafka connection is set for this workspace: set a default connection.";
        }
        String taskType = this.sourceTaskTypeRepository.findById(sourceTaskTypeId)
            .map(SourceTaskType::getServiceName)
            .filter(name -> !name.trim().isEmpty())
            .map(name -> "'" + name.trim() + "'")
            .orElse(String.valueOf(sourceTaskTypeId));
        return "No Kafka connection is set for task type " + taskType
            + " in this workspace: set a route or a default connection.";
    }

    private boolean hasProfilesOfItsOwn(Long tenantId) {
        return this.profileRepository.countByTenantIdAndStatusNot(tenantId, Status.Delete) > 0;
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

package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.TenantTaskTypeKafkaRoute;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Repository
public interface TenantTaskTypeKafkaRouteRepository extends JpaRepository<TenantTaskTypeKafkaRoute, Long> {

    /**
     * Note :- Method use to find a tenant's routing override for a given source task type, if
     * one exists -- this is KafkaConnectionResolver's first (highest-priority) resolution step.
     * @param tenantId
     * @param sourceTaskTypeId
     * @return Optional<TenantTaskTypeKafkaRoute>
     * */
    Optional<TenantTaskTypeKafkaRoute> findByTenantIdAndSourceTaskTypeId(Long tenantId, Long sourceTaskTypeId);

    /**
     * Note :- Method use to delete a tenant's routing override for a given source task type
     * (falls back to the type's own default the next time it resolves).
     * @param tenantId
     * @param sourceTaskTypeId
     * */
    @Transactional
    void deleteByTenantIdAndSourceTaskTypeId(Long tenantId, Long sourceTaskTypeId);

    /**
     * Note :- Method use to check whether any tenant still routes through a given profile --
     * used to block deleting a profile that's still referenced (same "can't delete what's in
     * use" pattern as SourceTaskServiceImpl.deleteSourceTask's job-link check).
     * @param kafkaConnectionProfileId
     * @return boolean
     * */
    boolean existsByKafkaConnectionProfileId(Long kafkaConnectionProfileId);

}

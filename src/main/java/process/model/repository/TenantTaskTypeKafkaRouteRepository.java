package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.TenantTaskTypeKafkaRoute;
import java.util.Optional;

@Repository
public interface TenantTaskTypeKafkaRouteRepository extends JpaRepository<TenantTaskTypeKafkaRoute, Long> {

    Optional<TenantTaskTypeKafkaRoute> findByTenantIdAndSourceTaskTypeId(Long tenantId, Long sourceTaskTypeId);

    @Transactional
    void deleteByTenantIdAndSourceTaskTypeId(Long tenantId, Long sourceTaskTypeId);

    boolean existsByKafkaConnectionProfileId(Long kafkaConnectionProfileId);

}

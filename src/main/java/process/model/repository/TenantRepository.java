package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.enums.TenantStatus;
import process.model.pojo.Tenant;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    List<Tenant> findByStatusNotOrderByTenantIdDesc(TenantStatus status);

    Optional<Tenant> findByTenantCode(String tenantCode);

}

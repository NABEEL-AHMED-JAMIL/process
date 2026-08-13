package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.enums.TenantStatus;
import process.model.pojo.Tenant;
import java.util.List;
import java.util.Optional;

@Repository
public interface TenantRepository extends JpaRepository<Tenant, Long> {

    public List<Tenant> findByStatusNotOrderByTenantIdDesc(TenantStatus status);

    public Optional<Tenant> findByTenantCode(String tenantCode);

}

package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import process.model.pojo.TenantRequest;

import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface TenantRequestRepository extends JpaRepository<TenantRequest, Long> {

    List<TenantRequest> findAllByOrderByTenantRequestIdDesc();

    /**
     * An open request for this address. Matched case-insensitively, because the address someone
     * types into a public form is not the address they typed last time.
     */
    @Query(value = "select * from tenant_request where lower(contact_email) = lower(?1) "
        + "and status = 'Pending' limit 1", nativeQuery = true)
    Optional<TenantRequest> findOpenByEmail(String contactEmail);
}

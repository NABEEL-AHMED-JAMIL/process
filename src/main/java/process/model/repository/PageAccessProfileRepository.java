package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.PageAccessProfile;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface PageAccessProfileRepository extends JpaRepository<PageAccessProfile, Long> {

    Optional<PageAccessProfile> findByTenantIdAndDefaultProfileTrueAndStatus(Long tenantId, Status status);
}

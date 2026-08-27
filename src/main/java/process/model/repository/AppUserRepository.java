package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.AppUser;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    public Optional<AppUser> findByUsernameAndStatusNot(String username, Status status);

    public List<AppUser> findByTenantIdAndStatusNotOrderByAppUserIdDesc(Long tenantId, Status status);

    public long countByTenantIdAndStatusNot(Long tenantId, Status status);

    public Optional<AppUser> findByUuid(String uuid);

}

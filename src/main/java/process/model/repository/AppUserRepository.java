package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    public Optional<AppUser> findByUsernameAndStatusNot(String username, Status status);

    /**
     * The live account a name belongs to, whatever its case and whichever tenant holds it (MIG-17).
     *
     * At most one row: ux_app_user_username_lower makes lower(username) unique over the whole table,
     * which is why this is a plain find and not the findFirst it replaces -- that one existed because
     * the answer might not have been unique. Native SQL on purpose: the Hibernate tenant filter does
     * not reach it, and must not -- signing in happens before there is a tenant at all.
     */
    @Query(value = "SELECT * FROM app_user WHERE lower(username) = lower(:username) AND status <> 'Delete'", nativeQuery = true)
    public Optional<AppUser> findLiveByUsernameIgnoringCase(@Param("username") String username);

    /**
     * Whether a new account may take this name: false when any row holds it in any case, in any
     * tenant, deleted rows included -- the unique index covers those too, so answering "free" for
     * one only moves the refusal to a constraint violation at save. Native for the same reason as
     * above: whether a name is taken is a platform-wide fact, not a tenant-scoped one.
     */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM app_user WHERE lower(username) = lower(:username))", nativeQuery = true)
    public boolean isUsernameTaken(@Param("username") String username);

    public List<AppUser> findByTenantIdAndStatusNotOrderByAppUserIdDesc(Long tenantId, Status status);

    public Optional<AppUser> findFirstByTenantIdAndUserRoleAndStatusOrderByAppUserIdAsc(Long tenantId, UserRole userRole, Status status);

    public long countByTenantIdAndStatusNot(Long tenantId, Status status);

    public Optional<AppUser> findByUuid(String uuid);

    /** Who still holds a profile -- what stops a profile from being deleted. */
    public long countByPageAccessProfileIdAndStatusNot(Long pageAccessProfileId, Status status);

    public List<AppUser> findByPageAccessProfileIdAndStatusNot(Long pageAccessProfileId, Status status);

}

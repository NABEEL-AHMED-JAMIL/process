package process.model.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import process.model.enums.Status;
import process.model.pojo.AppUser;
import java.util.Collection;
import java.util.List;

/**
 * @author Nabeel Ahmed
 * */
@Repository
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    /**
     * People by id, whichever tenant they are in -- for putting a name to an id, never for deciding
     * what a caller may touch (MIG-13).
     *
     * findAllById is a query, so the tenant filter reaches it: a tenant's job created by a platform
     * admin would lose its author's name. This is the explicit exception, native so the filter
     * cannot reach it, and named for what it crosses so nobody mistakes it for a scoped read.
     */
    @Query(value = "SELECT * FROM app_user WHERE app_user_id IN (:ids)", nativeQuery = true)
    public List<AppUser> findAllByIdAcrossTenants(@Param("ids") Collection<Long> ids);

    /** The person's current token version, or null when there is no such row. Native: not tenant-filtered. */
    @Query(value = "SELECT token_version FROM app_user WHERE app_user_id = :id", nativeQuery = true)
    public Integer findTokenVersion(@Param("id") Long appUserId);

    public List<AppUser> findByTenantIdAndStatusNotOrderByAppUserIdDesc(Long tenantId, Status status);

    /**
     * Every live account on the platform, newest first: IdentityPort.members for an all-tenants scope while
     * Identity runs in process (MIG-92, P5). It replaced a bare findAll(), which read every tenant because a
     * filter happened to be off -- an omission nobody could see in review. This is a named grant.
     */
    @Query(value = "SELECT * FROM app_user WHERE status <> 'Delete' ORDER BY app_user_id DESC", nativeQuery = true)
    public List<AppUser> findAllLiveAcrossTenants();

    public long countByTenantIdAndStatusNot(Long tenantId, Status status);

}

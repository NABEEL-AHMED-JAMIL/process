package process.model.repository;

import org.barco.platform.tenancy.TenantScope;
import process.model.enums.Status;
import process.model.pojo.AppUser;

import java.util.List;

/**
 * app_user reads that take a {@link TenantScope} (MIG-93): the caller hands over a scope and never picks
 * between a scoped and an unscoped query by hand. A static helper rather than a default method on the
 * repository, so a mocked repository still answers what a test stubs.
 *
 * @author Nabeel Ahmed
 */
public final class ScopedAppUserReads {

    private ScopedAppUserReads() {
    }

    /** Every tenant's live accounts for AllTenants, one tenant's otherwise, none for a caller scoped to nothing. */
    public static List<AppUser> findLive(AppUserRepository users, TenantScope scope) {
        if (scope.isAllTenants()) {
            return users.findAllLiveAcrossTenants();
        }
        return users.findByTenantIdAndStatusNotOrderByAppUserIdDesc(((TenantScope.Scoped) scope).tenantId(), Status.Delete);
    }
}

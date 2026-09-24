package process.security;

import org.barco.platform.tenancy.TenantScope;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import javax.persistence.EntityManager;

/**
 * @author Nabeel Ahmed
 * */
@Component
public class TenantFilterHelper {

    private static final String FILTER_NAME = "tenantFilter";

    private final Logger logger = LoggerFactory.getLogger(TenantFilterHelper.class);

    /**
     * Turns the tenant filter on for the caller, and on no account leaves it off by accident.
     *
     * A null tenant used to be treated exactly like a platform admin -- filter disabled, every
     * tenant's rows visible. That is backwards: TenantOwnership already refuses a tenantless
     * caller every row it checks by id, on the principle that a context with no tenant owns
     * nothing, so the list paths were handing out what the by-id paths were carefully refusing.
     * The two cases are separated here: only PLATFORM_ADMIN turns the filter off; anyone else
     * without a tenant is filtered to a tenant that does not exist.
     *
     * And when the filter cannot be turned on at all -- the EntityManager is not Hibernate's, or is
     * a proxy that will not unwrap, or the filter is not registered -- this throws (MIG-11). It
     * used to log at ERROR and return, and the caller then ran its query with no filter: a
     * cross-tenant read whose only trace was a log line. Now the request fails instead.
     */
    public void enableIfNeeded(EntityManager entityManager) {
        Long tenantId = TenantContext.getTenantId();
        Session session;
        try {
            session = entityManager.unwrap(Session.class);
        } catch (Exception ex) {
            this.logger.error("Refusing the read: could not unwrap a Hibernate Session to turn the tenant filter on: {}",
                ex.getMessage(), ex);
            throw new TenantIsolationException("Could not unwrap a Hibernate Session to turn the tenant filter on.", ex);
        }
        // The scope decides, not an if on the role (MIG-93): AllTenants is a named, audited grant, and
        // anyone else is filtered to their tenant -- or, with none, to TenantScope.NO_TENANT_MATCHES,
        // which no row carries (tenant ids start at 1000).
        TenantScope scope = TenantContext.scope();
        if (scope.isAllTenants()) {
            if (session.getEnabledFilter(FILTER_NAME) != null) {
                session.disableFilter(FILTER_NAME);
            }
            return;
        }
        tenantId = ((TenantScope.Scoped) scope).tenantId();
        if (tenantId == TenantScope.NO_TENANT_MATCHES) {
            this.logger.warn("A caller with role {} reached a tenant-scoped read with no tenant; "
                + "filtering it to nothing. Its app_user row is missing a tenant_id.", TenantContext.getUserRole());
        }
        try {
            session.enableFilter(FILTER_NAME).setParameter("tenantId", tenantId);
        } catch (Exception ex) {
            this.logger.error("Refusing the read: could not enable the tenant filter for tenantId {}: {}", tenantId,
                ex.getMessage(), ex);
            throw new TenantIsolationException("Could not enable the tenant filter for tenantId " + tenantId + ".", ex);
        }
    }

}

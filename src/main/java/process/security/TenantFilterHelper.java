package process.security;

import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import javax.persistence.EntityManager;

/**
 * Enables Hibernate's "tenantFilter" (declared on SourceJob/SourceTask/DynamicForm/AiAgent/
 * PdfHighlighterTask -- see each entity's @FilterDef/@Filter) for the CURRENT query.
 *
 * This used to be done once per request in TenantFilterInterceptor (a HandlerInterceptor,
 * running before the controller). That was a silent, total failure: enableFilter needs a real
 * transactional Hibernate Session, which doesn't exist yet in preHandle (before any
 * @Transactional service method has started one) -- every call threw "No transactional
 * EntityManager available", was caught and logged as an error, and fell through to "filter
 * left disabled". Even after making preHandle itself @Transactional (so the unwrap() call
 * stopped throwing), the filter still didn't apply to later queries: enabling a filter on the
 * Session bound to preHandle's own short-lived transaction does not carry over to the separate
 * Session/transaction each @Transactional service method opens for itself, despite
 * spring.jpa.open-in-view defaulting to true. Confirmed by testing an actual from-scratch
 * tenant with zero rows of its own and getting every other tenant's data back regardless, with
 * zero errors logged either way.
 *
 * The fix: call this.enableIfNeeded(entityManager) as the first line of every @Transactional
 * service method that queries a filtered entity, so the enable-filter call and the query it's
 * scoping run in the exact same transaction/Session -- verified this actually filters results
 * (0 rows for a fresh tenant, confirmed via manual testing) where the interceptor never did.
 * @author Nabeel Ahmed
 */
@Component
public class TenantFilterHelper {

    private static final String FILTER_NAME = "tenantFilter";

    private final Logger logger = LoggerFactory.getLogger(TenantFilterHelper.class);

    /**
     * Method use to enable the tenant filter on the given (must be transactionally-active)
     * EntityManager's Session, scoped to TenantContext's tenantId. No-op for PLATFORM_ADMIN
     * (unscoped, cross-tenant by design) or a request with no tenantId at all.
     * @param entityManager
     * */
    public void enableIfNeeded(EntityManager entityManager) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null || TenantContext.isPlatformAdmin()) {
            return;
        }
        try {
            Session session = entityManager.unwrap(Session.class);
            session.enableFilter(FILTER_NAME).setParameter("tenantId", tenantId);
        } catch (Exception ex) {
            this.logger.error("Could not enable tenant filter for tenantId {}: {}", tenantId, ex.getMessage(), ex);
        }
    }

}

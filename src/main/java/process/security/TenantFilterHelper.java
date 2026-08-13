package process.security;

import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import javax.persistence.EntityManager;

@Component
public class TenantFilterHelper {

    private static final String FILTER_NAME = "tenantFilter";

    private final Logger logger = LoggerFactory.getLogger(TenantFilterHelper.class);

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

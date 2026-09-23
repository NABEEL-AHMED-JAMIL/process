package process.analytics;

import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;

/**
 * Stamps the storage connection's id beside every analytics alias on every write (MIG-53 part b): one
 * listener on the five analytics entities, so no write path can forget. An alias is unique only within
 * a workspace now, and Storage is leaving process; the id is what names the exact connection a saved
 * query, dataset, analysis, run or benchmark was written against. An alias that names nothing leaves
 * the id empty rather than guessing.
 *
 * A plain JPA listener, like AuditListener; its resolver is installed at startup by
 * JdbcConnectionIdResolver.
 */
public class AnalyticsConnectionStamp {

    private static volatile ConnectionIdResolver installed;

    /**
     * The only constructor, and it must stay that way: Hibernate builds entity listeners through
     * Spring, on the thread bootstrapping the EntityManagerFactory. A constructor with a dependency
     * made Spring autowire it there, waiting on the singleton lock that the main thread held while
     * waiting for that same factory -- process never finished starting. The resolver is installed at
     * startup by JdbcConnectionIdResolver instead. (EntityListenersTest guards every listener.)
     */
    public AnalyticsConnectionStamp() {
    }

    static void use(ConnectionIdResolver resolver) {
        installed = resolver;
    }

    @PrePersist
    @PreUpdate
    public void stamp(Object entity) {
        if (!(entity instanceof ConnectionAliased)) {
            return;
        }
        ConnectionIdResolver ids = installed;
        if (ids == null) {
            return;
        }
        ConnectionAliased row = (ConnectionAliased) entity;
        row.setStorageConnectionId(ids.resolve(row.getTenantId(), row.getConnectionAlias()));
        row.setSecondStorageConnectionId(row.getSecondConnectionAlias() == null ? null
            : ids.resolve(row.getTenantId(), row.getSecondConnectionAlias()));
    }
}

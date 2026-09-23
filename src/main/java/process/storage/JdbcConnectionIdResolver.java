package process.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import process.model.pojo.ConnectionIdResolver;
import process.model.pojo.StorageConnectionStamp;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;

/**
 * ConnectionIdResolver over plain JDBC, for StorageConnectionStamp. Plain JDBC and not the JPA
 * repository because the stamp runs inside Hibernate's flush, where using the entity manager is unsafe.
 * The rule is StorageConnectionLookup.forRow's.
 */
// Off once Storage owns the table: RemoteStorageDirectory resolves through storage-service instead.
@Component
@ConditionalOnProperty(name = "storage.remote", havingValue = "false", matchIfMissing = true)
public class JdbcConnectionIdResolver implements ConnectionIdResolver {

    private final JdbcTemplate jdbc;

    public JdbcConnectionIdResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void install() {
        StorageConnectionStamp.use(this);
    }

    @Override
    public Long resolve(Long tenantId, String alias) {
        if (alias == null || alias.trim().isEmpty()) {
            return null;
        }
        if (tenantId != null) {
            List<Long> ids = this.jdbc.queryForList("SELECT storage_connection_id FROM storage_connection WHERE alias = ? "
                + "AND (tenant_id = ? OR tenant_id IS NULL) ORDER BY (tenant_id IS NULL) LIMIT 1", Long.class, alias.trim(), tenantId);
            return ids.isEmpty() ? null : ids.get(0);
        }
        // No workspace (a platform admin's row): the platform's, else the ONE workspace's using the name.
        List<Long> platform = this.jdbc.queryForList(
            "SELECT storage_connection_id FROM storage_connection WHERE tenant_id IS NULL AND alias = ?", Long.class, alias.trim());
        if (!platform.isEmpty()) {
            return platform.get(0);
        }
        List<Long> workspaces = this.jdbc.queryForList(
            "SELECT storage_connection_id FROM storage_connection WHERE tenant_id IS NOT NULL AND alias = ? LIMIT 2", Long.class, alias.trim());
        return workspaces.size() == 1 ? workspaces.get(0) : null;
    }
}

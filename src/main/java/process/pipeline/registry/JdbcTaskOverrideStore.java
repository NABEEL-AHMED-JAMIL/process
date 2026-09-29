package process.pipeline.registry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * task_registry_override (V186) through plain JDBC, as the step store writes its tables. Row security keeps every
 * statement to the session's workspace; the tenant_id in each statement says the same thing out loud, so a caller
 * with no workspace set reads and writes nothing.
 */
@Component
public class JdbcTaskOverrideStore implements TaskOverrideStore {

    private final JdbcTemplate jdbc;

    public JdbcTaskOverrideStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Boolean> overrides(long tenantId) {
        Map<String, Boolean> overrides = new LinkedHashMap<>();
        this.jdbc.query("SELECT task_code, enabled FROM task_registry_override WHERE tenant_id = ? ORDER BY task_code",
            row -> {
                overrides.put(row.getString("task_code"), row.getBoolean("enabled"));
            }, tenantId);
        return overrides;
    }

    @Override
    public void set(long tenantId, String taskCode, boolean enabled, Long userId) {
        this.jdbc.update("INSERT INTO task_registry_override (tenant_id, task_code, enabled, created_by, updated_by) "
            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (tenant_id, task_code) DO UPDATE SET enabled = EXCLUDED.enabled, "
            + "updated_by = EXCLUDED.updated_by, date_updated = now()", tenantId, taskCode, enabled, userId, userId);
    }

    @Override
    public boolean clear(long tenantId, String taskCode) {
        return this.jdbc.update("DELETE FROM task_registry_override WHERE tenant_id = ? AND task_code = ?", tenantId, taskCode) > 0;
    }
}

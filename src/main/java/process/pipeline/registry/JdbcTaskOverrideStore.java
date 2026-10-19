package process.pipeline.registry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * task_registry_override (V186), task_registry_block and task_registry_audit (V215) through plain JDBC, as the step store
 * writes its tables. Row security keeps every statement to the session's workspace (a platform administrator's names
 * the workspace); the tenant_id in each statement says the same thing out loud, so a caller with no workspace set reads
 * and writes nothing.
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
    public Map<String, Switch> switchRows(long tenantId) {
        Map<String, Switch> rows = new LinkedHashMap<>();
        this.jdbc.query("SELECT task_code, enabled, updated_by, updated_by_role, COALESCE(date_updated, date_created) AS at "
            + "FROM task_registry_override WHERE tenant_id = ? ORDER BY task_code", row -> {
                rows.put(row.getString("task_code"), new Switch(row.getBoolean("enabled"), longOrNull(row, "updated_by"),
                    row.getString("updated_by_role"), row.getObject("at", OffsetDateTime.class)));
            }, tenantId);
        return rows;
    }

    @Override
    public void set(long tenantId, String taskCode, boolean enabled, Long userId) {
        this.set(tenantId, taskCode, enabled, userId, null);
    }

    @Override
    public void set(long tenantId, String taskCode, boolean enabled, Long userId, String role) {
        this.jdbc.update("INSERT INTO task_registry_override (tenant_id, task_code, enabled, created_by, updated_by, updated_by_role) "
            + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (tenant_id, task_code) DO UPDATE SET enabled = EXCLUDED.enabled, "
            + "updated_by = EXCLUDED.updated_by, updated_by_role = EXCLUDED.updated_by_role, date_updated = now()",
            tenantId, taskCode, enabled, userId, userId, role);
    }

    @Override
    public boolean clear(long tenantId, String taskCode) {
        return this.jdbc.update("DELETE FROM task_registry_override WHERE tenant_id = ? AND task_code = ?", tenantId, taskCode) > 0;
    }

    @Override
    public Map<String, Block> blocks(long tenantId) {
        Map<String, Block> blocks = new LinkedHashMap<>();
        this.jdbc.query("SELECT task_code, reason, blocked_by, date_blocked FROM task_registry_block WHERE tenant_id = ? ORDER BY task_code",
            row -> {
                blocks.put(row.getString("task_code"), new Block(row.getString("reason"), row.getLong("blocked_by"),
                    row.getObject("date_blocked", OffsetDateTime.class)));
            }, tenantId);
        return blocks;
    }

    @Override
    public void block(long tenantId, String taskCode, String reason, long blockedBy) {
        this.jdbc.update("INSERT INTO task_registry_block (tenant_id, task_code, reason, blocked_by) VALUES (?, ?, ?, ?) "
            + "ON CONFLICT (tenant_id, task_code) DO UPDATE SET reason = EXCLUDED.reason, blocked_by = EXCLUDED.blocked_by, "
            + "date_blocked = now()", tenantId, taskCode, reason, blockedBy);
    }

    @Override
    public boolean unblock(long tenantId, String taskCode) {
        return this.jdbc.update("DELETE FROM task_registry_block WHERE tenant_id = ? AND task_code = ?", tenantId, taskCode) > 0;
    }

    @Override
    public void audit(long tenantId, String taskCode, String action, String before, String after, String note, Long actorUserId,
        String actorRole) {
        this.jdbc.update("INSERT INTO task_registry_audit (tenant_id, task_code, action, before_value, after_value, note, actor_user_id, "
            + "actor_role) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", tenantId, taskCode, action, before, after, note, actorUserId, actorRole);
    }

    @Override
    public List<Audit> audits(long tenantId, String taskCode, int limit) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        String where = "tenant_id = ?";
        if (taskCode != null) {
            where += " AND task_code = ?";
            args.add(taskCode);
        }
        args.add(Math.max(1, Math.min(limit, 500)));
        return this.jdbc.query("SELECT audit_id, task_code, action, before_value, after_value, note, actor_user_id, actor_role, date_created "
            + "FROM task_registry_audit WHERE " + where + " ORDER BY audit_id DESC LIMIT ?", (rs, n) -> {
                Audit a = new Audit();
                a.id = rs.getLong("audit_id");
                a.taskCode = rs.getString("task_code");
                a.action = rs.getString("action");
                a.before = rs.getString("before_value");
                a.after = rs.getString("after_value");
                a.note = rs.getString("note");
                a.actorUserId = longOrNull(rs, "actor_user_id");
                a.actorRole = rs.getString("actor_role");
                a.at = rs.getObject("date_created", OffsetDateTime.class);
                return a;
            }, args.toArray());
    }

    private static Long longOrNull(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}

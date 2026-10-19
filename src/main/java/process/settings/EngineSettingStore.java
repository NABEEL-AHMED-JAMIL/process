package process.settings;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.barco.platform.tenancy.RowSecurity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Owner #24: a workspace's own engine values (engine_setting_override) and every change to an engine setting
 * (engine_setting_audit, platform and workspace alike). Written only through EngineSettingsService, by a platform
 * administrator, so every statement runs across workspaces on purpose (RowSecurityContractTest).
 */
@Component
public class EngineSettingStore {

    /** One line of the history: what changed, from what, to what, by whom, when. */
    public static final class Change {
        public final long id;
        public final String key;
        public final Long tenantId;
        public final String action;
        public final String oldValue;
        public final String newValue;
        public final Long by;
        public final Timestamp at;

        Change(long id, String key, Long tenantId, String action, String oldValue, String newValue, Long by, Timestamp at) {
            this.id = id;
            this.key = key;
            this.tenantId = tenantId;
            this.action = action;
            this.oldValue = oldValue;
            this.newValue = newValue;
            this.by = by;
            this.at = at;
        }
    }

    private final JdbcTemplate jdbc;

    public EngineSettingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> override(long tenantId, EngineDial dial) {
        List<String> found = RowSecurity.acrossTenants("the engine settings screen (owner #24, platform administrators) reads one "
            + "workspace's own value of an engine setting before it changes it", () -> this.jdbc.queryForList(
                "SELECT setting_value FROM engine_setting_override WHERE tenant_id = ? AND setting_key = ?", String.class, tenantId,
                dial.key()));
        return found.isEmpty() ? Optional.empty() : Optional.ofNullable(found.get(0));
    }

    public void setOverride(long tenantId, EngineDial dial, long value, Long by) {
        RowSecurity.acrossTenants("the engine settings screen (owner #24, platform administrators) sets one workspace's own value "
            + "of an engine setting", () -> this.jdbc.update("INSERT INTO engine_setting_override (tenant_id, setting_key, setting_value, "
                + "updated_by, updated_at) VALUES (?, ?, ?, ?, now()) ON CONFLICT (tenant_id, setting_key) DO UPDATE SET "
                + "setting_value = EXCLUDED.setting_value, updated_by = EXCLUDED.updated_by, updated_at = now()",
                tenantId, dial.key(), Long.toString(value), by));
    }

    public void removeOverride(long tenantId, EngineDial dial) {
        RowSecurity.acrossTenants("the engine settings screen (owner #24, platform administrators) removes one workspace's own "
            + "value of an engine setting (reset to the platform's)", () -> this.jdbc.update(
                "DELETE FROM engine_setting_override WHERE tenant_id = ? AND setting_key = ?", tenantId, dial.key()));
    }

    /** When and by whom each workspace's own value was last set, by workspace then key. */
    public Map<Long, Map<String, Object[]>> overrideStamps() {
        Map<Long, Map<String, Object[]>> out = new LinkedHashMap<>();
        RowSecurity.acrossTenants("the engine settings screen (owner #24, platform administrators) lists every workspace's own "
            + "engine values with who set them", () -> {
                this.jdbc.query("SELECT tenant_id, setting_key, updated_at, updated_by FROM engine_setting_override ORDER BY tenant_id, "
                    + "setting_key", rs -> {
                        long by = rs.getLong(4);
                        out.computeIfAbsent(rs.getLong(1), k -> new LinkedHashMap<>()).put(rs.getString(2),
                            new Object[]{rs.getTimestamp(3), rs.wasNull() ? null : by});
                    });
                return null;
            });
        return out;
    }

    public void record(EngineDial dial, Long tenantId, String action, String oldValue, String newValue, Long by) {
        RowSecurity.acrossTenants("the engine settings screen (owner #24, platform administrators) records a change to an engine "
            + "setting, the platform's or one workspace's", () -> this.jdbc.update("INSERT INTO engine_setting_audit (setting_key, "
                + "tenant_id, action, old_value, new_value, changed_by) VALUES (?, ?, ?, ?, ?, ?)",
                dial.key(), tenantId, action, oldValue, newValue, by));
    }

    /** The newest changes first, at most {@code limit}; one setting's when {@code key} is set. */
    public List<Change> history(String key, int limit) {
        int size = Math.max(1, Math.min(limit, 500));
        return RowSecurity.acrossTenants("the engine settings screen (owner #24, platform administrators) reads the history of "
            + "engine setting changes, the platform's and every workspace's", () -> key == null
                ? this.jdbc.query("SELECT * FROM engine_setting_audit ORDER BY changed_at DESC, audit_id DESC LIMIT ?", EngineSettingStore::change, size)
                : this.jdbc.query("SELECT * FROM engine_setting_audit WHERE setting_key = ? ORDER BY changed_at DESC, audit_id DESC LIMIT ?",
                    EngineSettingStore::change, key, size));
    }

    private static Change change(ResultSet rs, int row) throws SQLException {
        long tenant = rs.getLong("tenant_id");
        Long tenantId = rs.wasNull() ? null : tenant;
        long by = rs.getLong("changed_by");
        Long changedBy = rs.wasNull() ? null : by;
        return new Change(rs.getLong("audit_id"), rs.getString("setting_key"), tenantId, rs.getString("action"),
            rs.getString("old_value"), rs.getString("new_value"), changedBy, rs.getTimestamp("changed_at"));
    }

    static List<Change> none() {
        return new ArrayList<>();
    }
}

package process.directory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import process.identity.IdentityPort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The continuous control for the demoted tenant keys (MIG-166).
 *
 * No foreign key says a Core row's tenant_id names a workspace any more. This anti-joins every tenant_id
 * Core's own tables hold against Identity's workspaces and reports:
 * - ORPHANS: ids Identity does not know, with how many rows hold each, table by table. Reported, never
 *   changed -- nobody knows whose they are;
 * - RETIRED: workspaces Identity deleted whose jobs were still active -- a tenant.deleted Core missed. The
 *   reaction is re-applied (it is idempotent) and reported.
 * Identity out of reach is "could not check": never "every workspace is an orphan".
 *
 * Core's own tables are found, not listed: every base table in public with a tenant_id, except Identity's
 * six, the user_directory projection, and every table moved to another service (read-only here, by a
 * trigger named *_read_only, or renamed *_moved_mig*). A table added tomorrow is audited without anyone
 * remembering to add it.
 *
 * @author Nabeel Ahmed
 */
@Component
public class TenantOrphanAudit {

    /** Identity's /internal/identity/workspaces answers at most this many ids at once. */
    static final int IDENTITY_BATCH = 500;

    static final Set<String> NOT_CORES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "tenant", "app_user", "tenant_request", "page_access_profile", "page_access_profile_page", "user_page_access",
        "user_directory")));

    private static final Logger logger = LoggerFactory.getLogger(TenantOrphanAudit.class);

    /** What one run found. */
    public static final class Report {
        private final boolean checked;
        private final String reason;
        private final List<String> tables;
        private final Map<Long, Map<String, Long>> orphans;
        private final Map<Long, Integer> retired;

        Report(boolean checked, String reason, List<String> tables, Map<Long, Map<String, Long>> orphans, Map<Long, Integer> retired) {
            this.checked = checked;
            this.reason = reason;
            this.tables = tables;
            this.orphans = orphans;
            this.retired = retired;
        }

        public boolean isChecked() { return this.checked; }
        public String getReason() { return this.reason; }
        public List<String> getTables() { return this.tables; }
        public Map<Long, Map<String, Long>> getOrphans() { return this.orphans; }
        public Map<Long, Integer> getRetired() { return this.retired; }
    }

    private final JdbcTemplate jdbc;
    private final IdentityPort identity;
    private final WorkspaceRetirement retirement;

    public TenantOrphanAudit(JdbcTemplate jdbc, IdentityPort identity, WorkspaceRetirement retirement) {
        this.jdbc = jdbc;
        this.identity = identity;
        this.retirement = retirement;
    }

    /** Core's own tenant-scoped tables, found in the catalogue. */
    List<String> tables() {
        List<String> found = new ArrayList<>();
        for (String table : this.jdbc.queryForList("SELECT c.table_name FROM information_schema.columns c "
            + "JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name "
            + "WHERE c.table_schema = 'public' AND c.column_name = 'tenant_id' AND t.table_type = 'BASE TABLE' "
            + "AND c.table_name NOT LIKE '%\\_moved\\_mig%' "
            + "AND NOT EXISTS (SELECT 1 FROM pg_trigger g JOIN pg_class r ON r.oid = g.tgrelid "
            + "  JOIN pg_namespace n ON n.oid = r.relnamespace "
            + "  WHERE n.nspname = 'public' AND r.relname = c.table_name AND NOT g.tgisinternal AND g.tgname LIKE '%\\_read\\_only') "
            + "ORDER BY c.table_name", String.class)) {
            if (!NOT_CORES.contains(table)) {
                found.add(table);
            }
        }
        return found;
    }

    public Report run() {
        List<String> tables = this.tables();
        // tenant id -> table -> rows
        Map<Long, Map<String, Long>> held = new TreeMap<>();
        for (String table : tables) {
            this.jdbc.query("SELECT tenant_id, count(*) FROM \"" + table + "\" WHERE tenant_id IS NOT NULL GROUP BY tenant_id",
                rs -> {
                    held.computeIfAbsent(rs.getLong(1), id -> new LinkedHashMap<>()).put(table, rs.getLong(2));
                });
        }
        Map<Long, String> statusById = new TreeMap<>();
        List<Long> ids = new ArrayList<>(new TreeSet<>(held.keySet()));
        try {
            for (int from = 0; from < ids.size(); from += IDENTITY_BATCH) {
                for (IdentityPort.Workspace workspace : this.identity.workspaces(ids.subList(from, Math.min(ids.size(), from + IDENTITY_BATCH)))) {
                    statusById.put(workspace.getTenantId(), workspace.getStatus());
                }
            }
        } catch (IdentityPort.Unavailable unavailable) {
            logger.warn("Tenant orphan audit could not check {} workspace id(s): {}", ids.size(), unavailable.getMessage());
            return new Report(false, unavailable.getMessage(), tables, new TreeMap<>(), new TreeMap<>());
        }
        Map<Long, Map<String, Long>> orphans = new TreeMap<>();
        Map<Long, Integer> retired = new TreeMap<>();
        for (Map.Entry<Long, Map<String, Long>> tenant : held.entrySet()) {
            String status = statusById.get(tenant.getKey());
            if (status == null) {
                orphans.put(tenant.getKey(), tenant.getValue());
            } else if ("Delete".equals(status)) {
                int stopped = this.retirement.retire(tenant.getKey());
                if (stopped > 0) {
                    retired.put(tenant.getKey(), stopped);
                }
            }
        }
        if (!orphans.isEmpty()) {
            logger.warn("Tenant orphan audit: {} tenant id(s) that Identity does not know are held by Core's rows: {}",
                orphans.size(), orphans);
        }
        if (!retired.isEmpty()) {
            logger.warn("Tenant orphan audit: workspaces deleted in Identity still had active jobs, now Inactive: {}", retired);
        }
        logger.info("Tenant orphan audit checked {} workspace id(s) across {} table(s): {} orphan(s), {} retired.", ids.size(),
            tables.size(), orphans.size(), retired.size());
        return new Report(true, null, tables, orphans, retired);
    }
}

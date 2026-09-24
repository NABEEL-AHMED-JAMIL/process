package process.directory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import process.identity.IdentityPort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The nightly anti-join for the demoted audit stamps (MIG-153).
 *
 * created_by / updated_by / assigned_user_id across Core are plain bigints now; nothing in the database
 * says they name anyone. This checks, against Identity:
 * - user_directory itself: a row that drifted (a missed rename) is REPAIRED; a row Identity does not know
 *   is reported as UNKNOWN_TO_IDENTITY;
 * - every author Core's rows name that the directory lacks: known to Identity, it is BACKFILLED into the
 *   directory; known to no one, it is reported as DANGLING with the columns and row counts that name it.
 * The oldest updated_at in the directory, taken before the repair, is reported: staleness measured, not
 * assumed. Identity out of reach is "could not check" and nothing is touched.
 *
 * @author Nabeel Ahmed
 */
@Component
public class UserDirectoryReconciliation {

    static final int IDENTITY_BATCH = 500;
    private static final int PAGE = 1000;
    private static final Logger logger = LoggerFactory.getLogger(UserDirectoryReconciliation.class);

    /** What one run found. */
    public static final class Report {
        private boolean checked = true;
        private String reason;
        private Instant oldestUpdatedAt;
        private final List<Long> repaired = new ArrayList<>();
        private final List<Long> unknownToIdentity = new ArrayList<>();
        private final List<Long> backfilled = new ArrayList<>();
        private final Map<Long, Map<String, Long>> danglingAuthors = new TreeMap<>();

        public boolean isChecked() { return this.checked; }
        public String getReason() { return this.reason; }
        public Instant getOldestUpdatedAt() { return this.oldestUpdatedAt; }
        public List<Long> getRepaired() { return this.repaired; }
        public List<Long> getUnknownToIdentity() { return this.unknownToIdentity; }
        public List<Long> getBackfilled() { return this.backfilled; }
        public Map<Long, Map<String, Long>> getDanglingAuthors() { return this.danglingAuthors; }

        Report couldNotCheck(String why) {
            this.checked = false;
            this.reason = why;
            return this;
        }
    }

    private final JdbcTemplate jdbc;
    private final IdentityPort identity;
    private final UserDirectory directory;

    public UserDirectoryReconciliation(JdbcTemplate jdbc, IdentityPort identity, UserDirectory directory) {
        this.jdbc = jdbc;
        this.identity = identity;
        this.directory = directory;
    }

    /** Core's own audit-stamp columns, found the way TenantOrphanAudit finds its tables. */
    List<String[]> authorColumns() {
        List<String[]> columns = new ArrayList<>();
        this.jdbc.query("SELECT c.table_name, c.column_name FROM information_schema.columns c "
            + "JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name "
            + "WHERE c.table_schema = 'public' AND c.column_name IN ('created_by', 'updated_by', 'assigned_user_id') "
            + "AND t.table_type = 'BASE TABLE' AND c.table_name NOT LIKE '%\\_moved\\_mig%' "
            + "AND NOT EXISTS (SELECT 1 FROM pg_trigger g JOIN pg_class r ON r.oid = g.tgrelid "
            + "  JOIN pg_namespace n ON n.oid = r.relnamespace "
            + "  WHERE n.nspname = 'public' AND r.relname = c.table_name AND NOT g.tgisinternal AND g.tgname LIKE '%\\_read\\_only') "
            + "ORDER BY c.table_name, c.column_name", rs -> {
                if (!TenantOrphanAudit.NOT_CORES.contains(rs.getString(1))) {
                    columns.add(new String[] {rs.getString(1), rs.getString(2)});
                }
            });
        return columns;
    }

    public Report run() {
        Report report = new Report();
        report.oldestUpdatedAt = this.directory.oldestUpdatedAt();
        try {
            this.checkDirectory(report);
            this.checkAuthors(report);
        } catch (IdentityPort.Unavailable unavailable) {
            logger.warn("User directory reconciliation could not check against Identity: {}", unavailable.getMessage());
            return report.couldNotCheck(unavailable.getMessage());
        }
        if (!report.unknownToIdentity.isEmpty() || !report.danglingAuthors.isEmpty()) {
            logger.warn("User directory reconciliation: {} directory row(s) Identity does not know {}; {} author id(s) no one knows {}",
                report.unknownToIdentity.size(), report.unknownToIdentity, report.danglingAuthors.size(), report.danglingAuthors);
        }
        logger.info("User directory reconciliation: {} repaired, {} backfilled, oldest state held from {}.", report.repaired.size(),
            report.backfilled.size(), report.oldestUpdatedAt);
        return report;
    }

    private void checkDirectory(Report report) {
        long after = 0;
        while (true) {
            List<UserDirectory.Entry> page = this.directory.page(after, PAGE);
            if (page.isEmpty()) {
                return;
            }
            for (int from = 0; from < page.size(); from += IDENTITY_BATCH) {
                List<UserDirectory.Entry> batch = page.subList(from, Math.min(page.size(), from + IDENTITY_BATCH));
                List<Long> ids = new ArrayList<>();
                batch.forEach(entry -> ids.add(entry.getAppUserId()));
                Instant asked = Instant.now();
                Map<Long, IdentityPort.Person> known = this.identity.people(ids);
                for (UserDirectory.Entry entry : batch) {
                    IdentityPort.Person person = known.get(entry.getAppUserId());
                    if (person == null) {
                        report.unknownToIdentity.add(entry.getAppUserId());
                    } else if (!entry.sameAs(person) && this.directory.apply(UserDirectory.Entry.of(person, asked))) {
                        report.repaired.add(entry.getAppUserId());
                    }
                }
            }
            after = page.get(page.size() - 1).getAppUserId();
        }
    }

    private void checkAuthors(Report report) {
        // author id -> "table.column" -> rows, for every author the directory does not hold
        Map<Long, Map<String, Long>> missing = new TreeMap<>();
        for (String[] column : this.authorColumns()) {
            String table = column[0];
            String name = column[1];
            this.jdbc.query("SELECT a." + name + ", count(*) FROM \"" + table + "\" a WHERE a." + name + " IS NOT NULL "
                + "AND NOT EXISTS (SELECT 1 FROM user_directory d WHERE d.app_user_id = a." + name + ") GROUP BY a." + name,
                rs -> {
                    missing.computeIfAbsent(rs.getLong(1), id -> new LinkedHashMap<>()).put(table + "." + name, rs.getLong(2));
                });
        }
        List<Long> ids = new ArrayList<>(new TreeSet<>(missing.keySet()));
        Map<Long, IdentityPort.Person> known = new HashMap<>();
        Instant asked = Instant.now();
        for (int from = 0; from < ids.size(); from += IDENTITY_BATCH) {
            known.putAll(this.identity.people(ids.subList(from, Math.min(ids.size(), from + IDENTITY_BATCH))));
        }
        for (Long id : ids) {
            IdentityPort.Person person = known.get(id);
            if (person == null) {
                report.danglingAuthors.put(id, missing.get(id));
            } else {
                this.directory.apply(UserDirectory.Entry.of(person, asked));
                report.backfilled.add(id);
            }
        }
    }
}

package process.retention;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MIG-354: the retention tables (V212). Every statement names its workspace and runs as the caller -- the workspace's
 * administrator, or the pass inside RowSecurity.forTenant -- except the platform's one-row settings, which have none.
 */
@Component
public class RetentionStore {

    private final JdbcTemplate jdbc;

    public RetentionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------------------------------------------------- platform

    /** The platform's settings (V212 writes the row; its defaults stand if it was ever lost). */
    public static final class Platform {
        public int auditFloorDays = 2190;
        public int maxSharePct = 20;
        public Long updatedBy;
        public Instant updatedAt;
    }

    public Platform platform() {
        List<Platform> rows = this.jdbc.query("SELECT audit_floor_days, max_share_pct, updated_by, date_updated FROM retention_platform WHERE id = 1",
            (rs, i) -> {
                Platform p = new Platform();
                p.auditFloorDays = rs.getInt(1);
                p.maxSharePct = rs.getInt(2);
                p.updatedBy = longOrNull(rs, 3);
                p.updatedAt = instant(rs, 4);
                return p;
            });
        return rows.isEmpty() ? new Platform() : rows.get(0);
    }

    public void savePlatform(int auditFloorDays, int maxSharePct, Long by) {
        int changed = this.jdbc.update("UPDATE retention_platform SET audit_floor_days = ?, max_share_pct = ?, updated_by = ?, date_updated = now() "
            + "WHERE id = 1", auditFloorDays, maxSharePct, by);
        if (changed == 0) {
            this.jdbc.update("INSERT INTO retention_platform (id, audit_floor_days, max_share_pct, updated_by) VALUES (1, ?, ?, ?)",
                auditFloorDays, maxSharePct, by);
        }
    }

    // ------------------------------------------------------------------------------------------------------------ policy

    /** A class's rule: its days (null: forever) and an administrator's confirmation of a pass over the share. */
    public static final class Rule {
        public Integer keepDays;
        public Long confirmedCount;
        public Long confirmedBy;
        public Instant confirmedAt;
    }

    /** A workspace's policy: off with every class forever unless saved otherwise. */
    public static final class Policy {
        public boolean enabled;
        public Long updatedBy;
        public Instant updatedAt;
        public final Map<DataClass, Rule> rules = new EnumMap<>(DataClass.class);

        public Rule rule(DataClass dataClass) {
            return this.rules.computeIfAbsent(dataClass, c -> new Rule());
        }
    }

    public Policy policy(long tenantId) {
        Policy policy = new Policy();
        this.jdbc.query("SELECT enabled, updated_by, date_updated FROM retention_policy WHERE tenant_id = ?", rs -> {
            policy.enabled = rs.getBoolean(1);
            policy.updatedBy = longOrNull(rs, 2);
            policy.updatedAt = instant(rs, 3);
        }, tenantId);
        this.jdbc.query("SELECT data_class, keep_days, confirmed_count, confirmed_by, confirmed_at FROM retention_rule WHERE tenant_id = ?", rs -> {
            Optional<DataClass> known = DataClass.of(rs.getString(1));
            if (!known.isPresent()) {
                return;
            }
            Rule rule = policy.rule(known.get());
            int days = rs.getInt(2);
            rule.keepDays = rs.wasNull() ? null : days;
            rule.confirmedCount = longOrNull(rs, 3);
            rule.confirmedBy = longOrNull(rs, 4);
            rule.confirmedAt = instant(rs, 5);
        }, tenantId);
        return policy;
    }

    public void saveSwitch(long tenantId, boolean enabled, Long by) {
        this.jdbc.update("INSERT INTO retention_policy (tenant_id, enabled, updated_by, date_updated) VALUES (?, ?, ?, now()) "
            + "ON CONFLICT (tenant_id) DO UPDATE SET enabled = EXCLUDED.enabled, updated_by = EXCLUDED.updated_by, date_updated = now()",
            tenantId, enabled, by);
    }

    /** A class's days; a change of days drops a confirmation given for the old ones. */
    public void saveDays(long tenantId, DataClass dataClass, Integer keepDays, Long by) {
        this.jdbc.update("INSERT INTO retention_rule (tenant_id, data_class, keep_days, updated_by, date_updated) VALUES (?, ?, ?, ?, now()) "
            + "ON CONFLICT (tenant_id, data_class) DO UPDATE SET keep_days = EXCLUDED.keep_days, updated_by = EXCLUDED.updated_by, "
            + "date_updated = now(), confirmed_count = CASE WHEN retention_rule.keep_days IS NOT DISTINCT FROM EXCLUDED.keep_days "
            + "THEN retention_rule.confirmed_count END, confirmed_by = CASE WHEN retention_rule.keep_days IS NOT DISTINCT FROM "
            + "EXCLUDED.keep_days THEN retention_rule.confirmed_by END, confirmed_at = CASE WHEN retention_rule.keep_days IS NOT DISTINCT "
            + "FROM EXCLUDED.keep_days THEN retention_rule.confirmed_at END", tenantId, dataClass.name(), keepDays, by);
    }

    public void confirm(long tenantId, DataClass dataClass, long count, Long by) {
        this.jdbc.update("UPDATE retention_rule SET confirmed_count = ?, confirmed_by = ?, confirmed_at = now() WHERE tenant_id = ? "
            + "AND data_class = ?", count, by, tenantId, dataClass.name());
    }

    /** The confirmation is spent once the class has nothing more over its cutoff. */
    public void clearConfirmation(long tenantId, DataClass dataClass) {
        this.jdbc.update("UPDATE retention_rule SET confirmed_count = NULL, confirmed_by = NULL, confirmed_at = NULL WHERE tenant_id = ? "
            + "AND data_class = ? AND confirmed_at IS NOT NULL", tenantId, dataClass.name());
    }

    /** The workspaces whose policy is on (the scheduler reads this across workspaces). */
    public List<Long> enabledWorkspaces() {
        return this.jdbc.queryForList("SELECT tenant_id FROM retention_policy WHERE enabled ORDER BY tenant_id", Long.class);
    }

    // ------------------------------------------------------------------------------------------------------------- holds

    public static final class Hold {
        public long id;
        public String scope;
        public Long jobId;
        public Long runId;
        public String reason;
        public Long placedBy;
        public Instant placedAt;
        public Long releasedBy;
        public Instant releasedAt;
        public String releaseReason;
    }

    private static final String HOLD_COLUMNS = "legal_hold_id, scope, job_id, job_queue_id, reason, placed_by, placed_at, released_by, "
        + "released_at, release_reason";

    private static Hold hold(ResultSet rs, int i) throws SQLException {
        Hold hold = new Hold();
        hold.id = rs.getLong(1);
        hold.scope = rs.getString(2);
        hold.jobId = longOrNull(rs, 3);
        hold.runId = longOrNull(rs, 4);
        hold.reason = rs.getString(5);
        hold.placedBy = longOrNull(rs, 6);
        hold.placedAt = instant(rs, 7);
        hold.releasedBy = longOrNull(rs, 8);
        hold.releasedAt = instant(rs, 9);
        hold.releaseReason = rs.getString(10);
        return hold;
    }

    /** The workspace's holds: the active ones first, then the latest released (at most {@code limit} in all). */
    public List<Hold> holds(long tenantId, int limit) {
        return this.jdbc.query("SELECT " + HOLD_COLUMNS + " FROM legal_hold WHERE tenant_id = ? ORDER BY (released_at IS NULL) DESC, "
            + "placed_at DESC LIMIT " + Math.max(1, Math.min(limit, 200)), RetentionStore::hold, tenantId);
    }

    public Optional<Hold> hold(long tenantId, long id) {
        return this.jdbc.query("SELECT " + HOLD_COLUMNS + " FROM legal_hold WHERE tenant_id = ? AND legal_hold_id = ?", RetentionStore::hold,
            tenantId, id).stream().findFirst();
    }

    /** The active holds, as a pass applies them. */
    public Holds activeHolds(long tenantId) {
        boolean[] workspace = {false};
        Set<Long> jobs = new LinkedHashSet<>();
        Set<Long> runs = new LinkedHashSet<>();
        this.jdbc.query("SELECT scope, job_id, job_queue_id FROM legal_hold WHERE tenant_id = ? AND released_at IS NULL", rs -> {
            String scope = rs.getString(1);
            if ("WORKSPACE".equals(scope)) {
                workspace[0] = true;
            } else if ("PIPELINE".equals(scope)) {
                jobs.add(rs.getLong(2));
            } else if ("RUN".equals(scope)) {
                runs.add(rs.getLong(3));
            }
        }, tenantId);
        return new Holds(workspace[0], jobs, runs);
    }

    public long placeHold(long tenantId, String scope, Long jobId, Long runId, String reason, Long by) {
        KeyHolder keys = new GeneratedKeyHolder();
        this.jdbc.update(connection -> {
            PreparedStatement insert = connection.prepareStatement("INSERT INTO legal_hold (tenant_id, scope, job_id, job_queue_id, reason, "
                + "placed_by) VALUES (?, ?, ?, ?, ?, ?)", new String[]{"legal_hold_id"});
            insert.setLong(1, tenantId);
            insert.setString(2, scope);
            setLong(insert, 3, jobId);
            setLong(insert, 4, runId);
            insert.setString(5, reason);
            setLong(insert, 6, by);
            return insert;
        }, keys);
        return keys.getKey() == null ? 0 : keys.getKey().longValue();
    }

    public boolean releaseHold(long tenantId, long id, String reason, Long by) {
        return this.jdbc.update("UPDATE legal_hold SET released_at = now(), released_by = ?, release_reason = ? WHERE tenant_id = ? "
            + "AND legal_hold_id = ? AND released_at IS NULL", by, reason, tenantId, id) == 1;
    }

    /** The runs of held pipelines made before the cutoff, at most {@code max} + 1 (the caller knows when there are too many). */
    public List<Long> heldPipelineRuns(long tenantId, Set<Long> jobIds, Instant cutoff, int max) {
        if (jobIds.isEmpty()) {
            return new ArrayList<>();
        }
        return this.jdbc.queryForList("SELECT job_queue_id FROM job_queue WHERE tenant_id = ? AND job_id = ANY (?) AND date_created < ? "
            + "ORDER BY job_queue_id LIMIT " + (max + 1), Long.class, tenantId, new BigintArray(jobIds), Timestamp.from(cutoff));
    }

    // ------------------------------------------------------------------------------------------------------------ passes

    public static final class Pass {
        public long id;
        public long tenantId;
        public String startedHow;
        public String state;
        public Long startedBy;
        public Instant startedAt;
        public Instant heartbeatAt;
        public Instant finishedAt;
        public long deleted;
        public long bytes;
        public String note;
        public final List<ClassResult> classes = new ArrayList<>();
    }

    public static final class ClassResult {
        public DataClass dataClass;
        public String state;
        public Integer keepDays;
        public Instant cutoff;
        public long eligible;
        public long total;
        public long deleted;
        public long bytes;
        public Instant oldest;
        public String note;
        public Instant startedAt;
        public Instant finishedAt;
    }

    private static final String PASS_COLUMNS = "retention_pass_id, tenant_id, started_how, state, started_by, started_at, heartbeat_at, "
        + "finished_at, deleted, bytes, note";

    private static Pass pass(ResultSet rs, int i) throws SQLException {
        Pass pass = new Pass();
        pass.id = rs.getLong(1);
        pass.tenantId = rs.getLong(2);
        pass.startedHow = rs.getString(3);
        pass.state = rs.getString(4);
        pass.startedBy = longOrNull(rs, 5);
        pass.startedAt = instant(rs, 6);
        pass.heartbeatAt = instant(rs, 7);
        pass.finishedAt = instant(rs, 8);
        pass.deleted = rs.getLong(9);
        pass.bytes = rs.getLong(10);
        pass.note = rs.getString(11);
        return pass;
    }

    /** Starts a pass; empty when one is already running in the workspace (ux_retention_pass_running). */
    public Optional<Long> startPass(long tenantId, String how, Long by) {
        try {
            return Optional.ofNullable(this.jdbc.queryForObject("INSERT INTO retention_pass (tenant_id, started_how, started_by) VALUES (?, ?, ?) "
                + "RETURNING retention_pass_id", Long.class, tenantId, how, by));
        } catch (DuplicateKeyException running) {
            return Optional.empty();
        }
    }

    public void heartbeat(long passId) {
        this.jdbc.update("UPDATE retention_pass SET heartbeat_at = now() WHERE retention_pass_id = ? AND state = 'RUNNING'", passId);
    }

    public void finishPass(long passId, String state, long deleted, long bytes, String note) {
        this.jdbc.update("UPDATE retention_pass SET state = ?, finished_at = now(), heartbeat_at = now(), deleted = ?, bytes = ?, note = ? "
            + "WHERE retention_pass_id = ? AND state = 'RUNNING'", state, deleted, bytes, cut(note, 1000), passId);
    }

    /** A running pass whose heartbeat stopped (the process died): INTERRUPTED, so the next tick resumes it. */
    public int interruptStale(long tenantId, Instant heartbeatBefore) {
        return this.jdbc.update("UPDATE retention_pass SET state = 'INTERRUPTED', finished_at = now(), note = 'Stopped without finishing "
            + "(the service restarted); the next pass continues.' WHERE tenant_id = ? AND state = 'RUNNING' AND heartbeat_at < ?", tenantId,
            Timestamp.from(heartbeatBefore));
    }

    public Optional<Pass> latestPass(long tenantId) {
        return this.jdbc.query("SELECT " + PASS_COLUMNS + " FROM retention_pass WHERE tenant_id = ? ORDER BY started_at DESC, retention_pass_id DESC "
            + "LIMIT 1", RetentionStore::pass, tenantId).stream().findFirst();
    }

    public Optional<Pass> pass(long tenantId, long passId) {
        Optional<Pass> found = this.jdbc.query("SELECT " + PASS_COLUMNS + " FROM retention_pass WHERE tenant_id = ? AND retention_pass_id = ?",
            RetentionStore::pass, tenantId, passId).stream().findFirst();
        found.ifPresent(p -> p.classes.addAll(this.classResults(tenantId, p.id)));
        return found;
    }

    public List<Pass> passes(long tenantId, int limit) {
        List<Pass> passes = this.jdbc.query("SELECT " + PASS_COLUMNS + " FROM retention_pass WHERE tenant_id = ? ORDER BY started_at DESC, "
            + "retention_pass_id DESC LIMIT " + Math.max(1, Math.min(limit, 100)), RetentionStore::pass, tenantId);
        for (Pass pass : passes) {
            pass.classes.addAll(this.classResults(tenantId, pass.id));
        }
        return passes;
    }

    public List<ClassResult> classResults(long tenantId, long passId) {
        return this.jdbc.query("SELECT data_class, state, keep_days, cutoff, eligible, total, deleted, bytes, oldest, note, started_at, finished_at "
            + "FROM retention_pass_class WHERE tenant_id = ? AND retention_pass_id = ? ORDER BY started_at, data_class", (rs, i) -> {
                ClassResult c = new ClassResult();
                c.dataClass = DataClass.of(rs.getString(1)).orElse(null);
                c.state = rs.getString(2);
                int days = rs.getInt(3);
                c.keepDays = rs.wasNull() ? null : days;
                c.cutoff = instant(rs, 4);
                c.eligible = rs.getLong(5);
                c.total = rs.getLong(6);
                c.deleted = rs.getLong(7);
                c.bytes = rs.getLong(8);
                c.oldest = instant(rs, 9);
                c.note = rs.getString(10);
                c.startedAt = instant(rs, 11);
                c.finishedAt = instant(rs, 12);
                return c;
            }, tenantId, passId);
    }

    /** Each class's latest result in the workspace (the console's "last pass" column). */
    public Map<DataClass, ClassResult> latestByClass(long tenantId) {
        Map<DataClass, ClassResult> out = new EnumMap<>(DataClass.class);
        this.jdbc.query("SELECT DISTINCT ON (data_class) data_class, state, deleted, eligible, total, bytes, note, finished_at, started_at, "
            + "keep_days FROM retention_pass_class WHERE tenant_id = ? ORDER BY data_class, started_at DESC", rs -> {
                Optional<DataClass> known = DataClass.of(rs.getString(1));
                if (!known.isPresent()) {
                    return;
                }
                ClassResult c = new ClassResult();
                c.dataClass = known.get();
                c.state = rs.getString(2);
                c.deleted = rs.getLong(3);
                c.eligible = rs.getLong(4);
                c.total = rs.getLong(5);
                c.bytes = rs.getLong(6);
                c.note = rs.getString(7);
                c.finishedAt = instant(rs, 8);
                c.startedAt = instant(rs, 9);
                int days = rs.getInt(10);
                c.keepDays = rs.wasNull() ? null : days;
                out.put(c.dataClass, c);
            }, tenantId);
        return out;
    }

    public void startClass(long passId, long tenantId, DataClass dataClass, Integer keepDays, Instant cutoff) {
        this.jdbc.update("INSERT INTO retention_pass_class (retention_pass_id, tenant_id, data_class, state, keep_days, cutoff) "
            + "VALUES (?, ?, ?, 'RUNNING', ?, ?) ON CONFLICT (retention_pass_id, data_class) DO NOTHING", passId, tenantId, dataClass.name(),
            keepDays, cutoff == null ? null : Timestamp.from(cutoff));
    }

    public void classTally(long passId, DataClass dataClass, long eligible, long total, Instant oldest) {
        this.jdbc.update("UPDATE retention_pass_class SET eligible = ?, total = ?, oldest = ? WHERE retention_pass_id = ? AND data_class = ?",
            eligible, total, oldest == null ? null : Timestamp.from(oldest), passId, dataClass.name());
    }

    public void classProgress(long passId, DataClass dataClass, long deleted, long bytes) {
        this.jdbc.update("UPDATE retention_pass_class SET deleted = ?, bytes = ? WHERE retention_pass_id = ? AND data_class = ?", deleted, bytes,
            passId, dataClass.name());
    }

    public void finishClass(long passId, DataClass dataClass, String state, String note) {
        this.jdbc.update("UPDATE retention_pass_class SET state = ?, note = ?, finished_at = now() WHERE retention_pass_id = ? AND data_class = ?",
            state, cut(note, 1000), passId, dataClass.name());
    }

    // ------------------------------------------------------------------------------------------------------------- audit

    public static final class AuditLine {
        public long id;
        public Instant at;
        public Long actor;
        public String action;
        public String dataClass;
        public Long passId;
        public String detail;
    }

    public void audit(long tenantId, Long actor, String action, DataClass dataClass, Long passId, String detail) {
        this.jdbc.update("INSERT INTO retention_audit (tenant_id, actor_user_id, action, data_class, retention_pass_id, detail) VALUES (?, ?, ?, ?, ?, ?)",
            ps -> {
                ps.setLong(1, tenantId);
                setLong(ps, 2, actor);
                ps.setString(3, action);
                ps.setString(4, dataClass == null ? null : dataClass.name());
                setLong(ps, 5, passId);
                ps.setString(6, cut(detail, 2000));
            });
    }

    public List<AuditLine> auditLines(long tenantId, int limit) {
        return this.jdbc.query("SELECT retention_audit_id, at, actor_user_id, action, data_class, retention_pass_id, detail FROM retention_audit "
            + "WHERE tenant_id = ? ORDER BY at DESC, retention_audit_id DESC LIMIT " + Math.max(1, Math.min(limit, 500)), (rs, i) -> {
                AuditLine line = new AuditLine();
                line.id = rs.getLong(1);
                line.at = instant(rs, 2);
                line.actor = longOrNull(rs, 3);
                line.action = rs.getString(4);
                line.dataClass = rs.getString(5);
                line.passId = longOrNull(rs, 6);
                line.detail = rs.getString(7);
                return line;
            }, tenantId);
    }

    // ------------------------------------------------------------------------------------------------------------ checks

    /** The pipeline is the workspace's (a hold names only its own). */
    public boolean pipelineExists(long tenantId, long jobId) {
        Integer n = this.jdbc.queryForObject("SELECT count(*) FROM source_job WHERE tenant_id = ? AND job_id = ?", Integer.class, tenantId, jobId);
        return n != null && n > 0;
    }

    /** The run is the workspace's. */
    public boolean runExists(long tenantId, long runId) {
        Integer n = this.jdbc.queryForObject("SELECT count(*) FROM job_queue WHERE tenant_id = ? AND job_queue_id = ?", Integer.class, tenantId, runId);
        return n != null && n > 0;
    }

    /**
     * The workspace's RETENTION alert rule (its administrators by bell and mail), made the first time retention is turned
     * on; one already there -- kept, changed or turned off by an administrator -- is left as it is. True when one was made.
     */
    public boolean ensureAlertRule(long tenantId, Long by) {
        Integer have = this.jdbc.queryForObject("SELECT count(*) FROM alert_rule WHERE tenant_id = ? AND kind = 'RETENTION'", Integer.class,
            tenantId);
        if (have != null && have > 0) {
            return false;
        }
        // recipient_roles is left to its default: the workspace's administrators (V210).
        return this.jdbc.update("INSERT INTO alert_rule (tenant_id, kind, name, enabled, severity, threshold, window_minutes, notify_bell, "
            + "notify_email, notify_webhook, created_by) VALUES (?, 'RETENTION', 'Retention needs attention', true, 'WARNING', "
            + "1, 60, true, true, false, ?)", ps -> {
                ps.setLong(1, tenantId);
                setLong(ps, 2, by);
            }) == 1;
    }

    // ----------------------------------------------------------------------------------------------------------- helpers

    static Long longOrNull(ResultSet rs, int column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    static String cut(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 3) + "...";
    }
}

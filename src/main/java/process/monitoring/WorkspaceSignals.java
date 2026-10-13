package process.monitoring;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.barco.platform.tenancy.RowSecurity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlTypeValue;
import org.springframework.stereotype.Component;

/**
 * MIG-355: what the workspace rules read, one statement per kind for every workspace that has such a rule -- never a
 * statement per rule, pipeline or run. Each answers a bounded list ({@value #MAX_ROWS} rows at most), over a small partial
 * index V210 made for it. These are the evaluator's cross-workspace reads (no caller): ids, times and names, then each
 * workspace's alerts are written as that workspace.
 */
@Component
public class WorkspaceSignals {

    /** The most rows one signal answers in a pass; a busier platform is still evaluated, on its newest rows. */
    static final int MAX_ROWS = 5000;
    /** "Usual" is the pipeline's p95 over this many days of completed runs... */
    static final Duration USUAL_OVER = Duration.ofDays(30);
    /** ...and needs this many to say anything. */
    static final int USUAL_NEEDS = 5;
    /** A schedule's slot is overdue past this. */
    static final Duration OVERDUE_GRACE = Duration.ofMinutes(5);
    /** p95s are kept this long between passes: they move slowly and cost a scan. */
    static final Duration USUAL_KEPT = Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;
    private final Map<Long, Usual> usual = new ConcurrentHashMap<>();
    private volatile Instant usualAt = Instant.EPOCH;

    public WorkspaceSignals(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A run of the window that failed (Failed, or Interrupt by the stall sweep); a person's own closes are not counted. */
    public static final class Failure {
        public long tenantId;
        public long jobId;
        public long runId;
        public String status;
        public Instant endedAt;
        public String jobName;
        public String message;
        /** MIG-347: the backfill the run is part of; null for any other run. */
        public Long backfillId;
    }

    /** A schedule's slot with no run: Skip (its previous run was still going) or Missed (the platform was down). */
    public static final class Skip {
        public long tenantId;
        public long jobId;
        public long runId;
        public String status;
        public Instant at;
        public String jobName;
    }

    /** A schedule whose next slot is overdue: the scheduler did not take it. */
    public static final class Overdue {
        public long tenantId;
        public long jobId;
        public Instant due;
        public String jobName;
    }

    /** A run going now, or that ended in the window, with when it started. */
    public static final class Timed {
        public long tenantId;
        public long jobId;
        public long runId;
        public Instant started;
        public Instant ended;
        public String jobName;
    }

    /** A pipeline's usual: the p95 of its completed runs' durations, and over how many. */
    public static final class Usual {
        public final double p95Seconds;
        public final long runs;

        Usual(double p95Seconds, long runs) {
            this.p95Seconds = p95Seconds;
            this.runs = runs;
        }
    }

    /** A review or an Approval step's request still waiting. */
    public static final class Waiting {
        public long tenantId;
        public long jobId;
        public long runId;
        public String what;
        public Long approvalId;
        public String title;
        public Instant since;
        public String jobName;
    }

    public List<Failure> failures(Collection<Long> tenants, Instant since) {
        // MIG-347: a backfill's failed run counts while its period is still FAILED on it: retried and completed, it is not a failure now.
        return this.across(tenants, (array) -> this.jdbc.query("SELECT q.tenant_id, q.job_id, q.job_queue_id, q.job_status, "
            + "COALESCE(q.end_time, q.skip_time) AS ended_at, j.job_name, q.job_status_message, q.backfill_id FROM job_queue q "
            + "JOIN source_job j ON j.job_id = q.job_id WHERE q.job_status IN ('Failed', 'Interrupt') AND COALESCE(q.end_time, q.skip_time) >= ? "
            + "AND q.tenant_id = ANY (?) AND (q.end_reason IS NULL OR q.end_reason <> 'OPERATOR') "
            + "AND (q.backfill_id IS NULL OR EXISTS (SELECT 1 FROM backfill_slot s WHERE s.job_queue_id = q.job_queue_id AND s.state = 'FAILED')) "
            + "ORDER BY COALESCE(q.end_time, q.skip_time) DESC LIMIT " + MAX_ROWS, (rs, i) -> {
                Failure f = new Failure();
                f.tenantId = rs.getLong(1);
                f.jobId = rs.getLong(2);
                f.runId = rs.getLong(3);
                f.status = rs.getString(4);
                f.endedAt = instant(rs, 5);
                f.jobName = rs.getString(6);
                f.message = rs.getString(7);
                f.backfillId = (Long) rs.getObject(8);
                return f;
            }, Timestamp.from(since), array));
    }

    /** When each of these pipelines last completed a run, since {@code since} (MIG-347: a live run -- a backfill's past date says nothing of now). */
    public Map<Long, Instant> lastCompleted(Collection<Long> jobIds, Instant since) {
        Map<Long, Instant> out = new HashMap<>();
        if (jobIds.isEmpty()) {
            return out;
        }
        RowSecurity.acrossTenants("the alert evaluator (MIG-355) reads when the failing pipelines of every workspace with a failed-run rule last "
            + "completed a run (ids and times)", () -> this.jdbc.execute((ConnectionCallback<Void>) connection -> {
            Array ids = connection.createArrayOf("bigint", jobIds.toArray());
            try (PreparedStatement query = connection.prepareStatement("SELECT job_id, max(end_time) FROM job_queue "
                + "WHERE job_status = 'Completed' AND job_id = ANY (?) AND end_time >= ? AND backfill_id IS NULL GROUP BY job_id")) {
                query.setArray(1, ids);
                query.setTimestamp(2, Timestamp.from(since));
                try (ResultSet rs = query.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getLong(1), instant(rs, 2));
                    }
                }
            }
            return null;
        }));
        return out;
    }

    public List<Skip> skips(Collection<Long> tenants, Instant since) {
        return this.across(tenants, (array) -> this.jdbc.query("SELECT q.tenant_id, q.job_id, q.job_queue_id, q.job_status, "
            + "COALESCE(q.end_time, q.skip_time, q.date_created) AS at, j.job_name FROM job_queue q JOIN source_job j ON j.job_id = q.job_id "
            + "WHERE q.job_status IN ('Skip', 'Missed') AND COALESCE(q.end_time, q.skip_time) >= ? AND q.tenant_id = ANY (?) "
            + "AND (q.job_status = 'Missed' OR (NOT COALESCE(q.skip_manual, false) AND q.job_status_message LIKE '%already in queue%')) "
            + "ORDER BY 5 DESC LIMIT " + MAX_ROWS, (rs, i) -> {
                Skip s = new Skip();
                s.tenantId = rs.getLong(1);
                s.jobId = rs.getLong(2);
                s.runId = rs.getLong(3);
                s.status = rs.getString(4);
                s.at = instant(rs, 5);
                s.jobName = rs.getString(6);
                return s;
            }, Timestamp.from(since), array));
    }

    public List<Overdue> overdue(Collection<Long> tenants, Instant now) {
        return this.across(tenants, (array) -> this.jdbc.query("SELECT s.tenant_id, s.job_id, s.next_run_at, j.job_name FROM scheduler s "
            + "JOIN source_job j ON j.job_id = s.job_id WHERE s.dispatch_eligible AND NOT s.expired AND s.paused_since IS NULL "
            + "AND s.next_run_at < ? AND j.job_status = 'Active' AND s.tenant_id = ANY (?) ORDER BY s.next_run_at LIMIT " + MAX_ROWS, (rs, i) -> {
                Overdue o = new Overdue();
                o.tenantId = rs.getLong(1);
                o.jobId = rs.getLong(2);
                o.due = instant(rs, 3);
                o.jobName = rs.getString(4);
                return o;
            }, Timestamp.from(now.minus(OVERDUE_GRACE)), array));
    }

    /** Runs going now (started, not ended). */
    public List<Timed> running(Collection<Long> tenants) {
        return this.across(tenants, (array) -> this.jdbc.query("SELECT q.tenant_id, q.job_id, q.job_queue_id, q.start_time, NULL, j.job_name "
            + "FROM job_queue q JOIN source_job j ON j.job_id = q.job_id WHERE upper(q.job_status) IN ('START', 'RUNNING') "
            + "AND q.start_time IS NOT NULL AND q.tenant_id = ANY (?) LIMIT " + MAX_ROWS, WorkspaceSignals::timed, array));
    }

    /** Runs that completed since {@code since}. */
    public List<Timed> completed(Collection<Long> tenants, Instant since) {
        return this.across(tenants, (array) -> this.jdbc.query("SELECT q.tenant_id, q.job_id, q.job_queue_id, q.start_time, q.end_time, "
            + "j.job_name FROM job_queue q JOIN source_job j ON j.job_id = q.job_id WHERE q.job_status = 'Completed' "
            + "AND COALESCE(q.end_time, q.skip_time) >= ? AND q.start_time IS NOT NULL AND q.tenant_id = ANY (?) "
            + "ORDER BY COALESCE(q.end_time, q.skip_time) DESC LIMIT " + MAX_ROWS, WorkspaceSignals::timed, Timestamp.from(since), array));
    }

    private static Timed timed(ResultSet rs, int i) throws SQLException {
        Timed t = new Timed();
        t.tenantId = rs.getLong(1);
        t.jobId = rs.getLong(2);
        t.runId = rs.getLong(3);
        t.started = instant(rs, 4);
        t.ended = instant(rs, 5);
        t.jobName = rs.getString(6);
        return t;
    }

    /**
     * Each pipeline's usual duration (p95 of its completed runs over {@link #USUAL_OVER}, at least {@value #USUAL_NEEDS}
     * of them) for these workspaces; kept {@link #USUAL_KEPT} between passes.
     */
    public Map<Long, Usual> usual(Collection<Long> tenants, Instant now) {
        if (tenants.isEmpty()) {
            return new HashMap<>();
        }
        if (Duration.between(this.usualAt, now).compareTo(USUAL_KEPT) < 0) {
            return new HashMap<>(this.usual);
        }
        Map<Long, Usual> fresh = new HashMap<>();
        this.across(tenants, (array) -> this.jdbc.query("SELECT q.job_id, percentile_cont(0.95) WITHIN GROUP "
            + "(ORDER BY extract(epoch FROM q.end_time - q.start_time)) AS p95, count(*) FROM job_queue q WHERE q.job_status = 'Completed' "
            + "AND q.start_time IS NOT NULL AND q.end_time IS NOT NULL AND COALESCE(q.end_time, q.skip_time) >= ? AND q.tenant_id = ANY (?) "
            + "GROUP BY q.job_id HAVING count(*) >= " + USUAL_NEEDS + " LIMIT " + MAX_ROWS, (rs, i) -> {
                fresh.put(rs.getLong(1), new Usual(rs.getDouble(2), rs.getLong(3)));
                return null;
            }, Timestamp.from(now.minus(USUAL_OVER)), array));
        this.usual.clear();
        this.usual.putAll(fresh);
        this.usualAt = now;
        return fresh;
    }

    /** Reviews and Approval steps' requests waiting since before {@code before}. */
    public List<Waiting> waiting(Collection<Long> tenants, Instant before) {
        return this.across(tenants, (array) -> this.jdbc.query("SELECT r.tenant_id, q.job_id, r.job_queue_id, 'review' AS what, "
            + "NULL::bigint AS approval_id, NULL AS title, r.date_created AS since, j.job_name FROM run_review r "
            + "JOIN job_queue q ON q.job_queue_id = r.job_queue_id JOIN source_job j ON j.job_id = q.job_id "
            + "WHERE r.status = 'PENDING' AND r.date_created < ? AND r.tenant_id = ANY (?) "
            + "UNION ALL SELECT a.tenant_id, q.job_id, a.job_queue_id, 'approval', a.run_approval_id, a.title, a.requested_at, j.job_name "
            + "FROM run_approval a JOIN job_queue q ON q.job_queue_id = a.job_queue_id JOIN source_job j ON j.job_id = q.job_id "
            + "WHERE a.status = 'PENDING' AND a.requested_at < ? AND a.tenant_id = ANY (?) ORDER BY 7 LIMIT " + MAX_ROWS, (rs, i) -> {
                Waiting w = new Waiting();
                w.tenantId = rs.getLong(1);
                w.jobId = rs.getLong(2);
                w.runId = rs.getLong(3);
                w.what = rs.getString(4);
                long approval = rs.getLong(5);
                w.approvalId = rs.wasNull() ? null : approval;
                w.title = rs.getString(6);
                w.since = instant(rs, 7);
                w.jobName = rs.getString(8);
                return w;
            }, Timestamp.from(before), array, Timestamp.from(before), array));
    }

    /** MIG-354: a workspace's retention trouble -- a class whose latest result PAUSED or FAILED, or a last pass that FAILED. */
    public static final class RetentionTrouble {
        public long tenantId;
        /** The class (RUN_OUTPUTS, ...) or null for the pass as a whole. */
        public String dataClass;
        public String state;
        public String note;
        public long passId;
        public Instant at;
    }

    /** MIG-354: each workspace's latest retention result per class, and its latest pass, where they need attention. */
    public List<RetentionTrouble> retention(Collection<Long> tenants) {
        return this.across(tenants, (array) -> this.jdbc.query("SELECT c.tenant_id, c.data_class, c.state, c.note, c.retention_pass_id, "
            + "c.started_at FROM (SELECT DISTINCT ON (tenant_id, data_class) tenant_id, data_class, state, note, retention_pass_id, started_at "
            + "FROM retention_pass_class WHERE tenant_id = ANY (?) ORDER BY tenant_id, data_class, started_at DESC) c "
            + "WHERE c.state IN ('PAUSED', 'FAILED') "
            + "UNION ALL SELECT p.tenant_id, NULL, p.state, p.note, p.retention_pass_id, p.started_at FROM (SELECT DISTINCT ON (tenant_id) "
            + "tenant_id, state, note, retention_pass_id, started_at FROM retention_pass WHERE tenant_id = ANY (?) AND state <> 'RUNNING' "
            + "ORDER BY tenant_id, started_at DESC) p WHERE p.state = 'FAILED' LIMIT " + MAX_ROWS, (rs, i) -> {
                RetentionTrouble t = new RetentionTrouble();
                t.tenantId = rs.getLong(1);
                t.dataClass = rs.getString(2);
                t.state = rs.getString(3);
                t.note = rs.getString(4);
                t.passId = rs.getLong(5);
                t.at = instant(rs, 6);
                return t;
            }, array, array));
    }

    /** Runs one query across workspaces, with the workspaces as a bigint[] argument. */
    private <T> T across(Collection<Long> tenants, Function<Object, T> query) {
        return RowSecurity.acrossTenants("the alert evaluator (MIG-355) reads every workspace with a rule of this kind (ids, times and names); "
            + "each workspace's alerts are then written as that workspace", () -> query.apply(new BigintArray(tenants)));
    }

    /** A bigint[] parameter for JdbcTemplate: bound with the connection's own createArrayOf. */
    static final class BigintArray implements SqlTypeValue {
        private final Collection<Long> values;

        BigintArray(Collection<Long> values) {
            this.values = values;
        }

        @Override
        public void setTypeValue(PreparedStatement ps, int index, int sqlType, String typeName) throws SQLException {
            ps.setArray(index, ps.getConnection().createArrayOf("bigint", this.values.toArray()));
        }
    }

    static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp at = rs.getTimestamp(column);
        return at == null ? null : at.toInstant();
    }
}

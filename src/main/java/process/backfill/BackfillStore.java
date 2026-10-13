package process.backfill;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Arrays;

/**
 * MIG-347: backfill and backfill_slot (V211) and the job_queue rows a backfill makes, in SQL. Every statement runs as the
 * caller's workspace (row security on the application pool): a request's, or the dispatcher's RowSecurity.forTenant;
 * the dispatcher's few reads across workspaces say so where they are made.
 */
@Component
public class BackfillStore {

    static final String OPEN = "'RUNNING', 'PAUSED'";
    static final String IN_FLIGHT = "UPPER(%s.job_status) IN ('QUEUE', 'START', 'RUNNING')";

    private final JdbcTemplate jdbc;

    public BackfillStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    JdbcTemplate jdbc() {
        return this.jdbc;
    }

    // ------------------------------------------------------------------------------------------------------- shapes

    /** The schedule a backfill runs, as its row and its task have it. */
    public static final class Job {
        public long jobId;
        public long tenantId;
        public String name;
        public String status;
        public Long createdBy;
        public Long assignedUserId;
        public Long taskDetailId;
        public String pipelineId;
        public String bucket;
        public String outputFolder;
    }

    public static final class Backfill {
        public long id;
        public long tenantId;
        public long jobId;
        public String jobName;
        public Long jobCreatedBy;
        public Long jobAssignedUserId;
        public String status;
        public String step;
        public LocalDate from;
        public LocalDate to;
        public String zone;
        public int concurrency;
        public boolean rerunCompleted;
        public String params;
        public int total;
        public BigDecimal estimatePerRun;
        public BigDecimal estimateTotal;
        public String estimateCurrency;
        public String estimateBasis;
        public String holdReason;
        public Long createdBy;
        public String createdVia;
        public String clientId;
        public Instant dateCreated;
        public Instant endedAt;
        public Instant pausedAt;
        public Instant dateUpdated;
        public Counts counts = new Counts();

        public boolean isOpen() {
            return "RUNNING".equals(this.status) || "PAUSED".equals(this.status);
        }
    }

    /** How many of a backfill's periods are in each state. */
    public static final class Counts {
        public int pending;
        public int queued;
        public int completed;
        public int failed;
        public int skipped;
        public int cancelled;

        public int done() {
            return this.completed + this.failed + this.skipped + this.cancelled;
        }
    }

    public static final class Slot {
        public long backfillId;
        public Instant start;
        public Instant end;
        public LocalDate date;
        public String state;
        public Long jobQueueId;
        public int tries;
        public String note;
        /** The run's own status and times, when it has one. */
        public String runStatus;
        public Instant runStartedAt;
        public Instant runEndedAt;
    }

    // ---------------------------------------------------------------------------------------------------------- jobs

    private static final String JOB_COLUMNS = "j.job_id, j.tenant_id, j.job_name, j.job_status, j.created_by, j.assigned_user_id, "
        + "t.task_detail_id, t.pipeline_id, t.bucket, t.output_folder";

    /** The schedule, as the caller's workspace sees it (a deleted one too: the caller decides). */
    public Optional<Job> job(long jobId) {
        List<Job> found = this.jdbc.query("SELECT " + JOB_COLUMNS + " FROM source_job j LEFT JOIN source_task t ON t.task_detail_id = "
            + "j.task_detail_id WHERE j.job_id = ?", BackfillStore::job, jobId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private static Job job(ResultSet rs, int i) throws SQLException {
        Job job = new Job();
        job.jobId = rs.getLong("job_id");
        job.tenantId = rs.getLong("tenant_id");
        job.name = rs.getString("job_name");
        job.status = rs.getString("job_status");
        job.createdBy = (Long) rs.getObject("created_by");
        job.assignedUserId = (Long) rs.getObject("assigned_user_id");
        job.taskDetailId = (Long) rs.getObject("task_detail_id");
        job.pipelineId = rs.getString("pipeline_id");
        job.bucket = rs.getString("bucket");
        job.outputFolder = rs.getString("output_folder");
        return job;
    }

    // ----------------------------------------------------------------------------------------------------- creating

    /** Writes the backfill and its periods; answers its id. One statement for the periods, however many. */
    public long insert(Backfill b, List<BackfillPeriods.Period> periods) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        this.jdbc.update(connection -> {
            PreparedStatement insert = connection.prepareStatement("INSERT INTO backfill (tenant_id, job_id, status, step, range_from, "
                + "range_to, zone, concurrency, rerun_completed, params, total, estimate_per_run, estimate_total, estimate_currency, "
                + "estimate_basis, created_by, created_via, client_id, date_created) VALUES (?, ?, 'RUNNING', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + "?, ?, ?, ?)", new String[] {"backfill_id"});
            int c = 1;
            insert.setLong(c++, b.tenantId);
            insert.setLong(c++, b.jobId);
            insert.setString(c++, b.step);
            insert.setObject(c++, b.from);
            insert.setObject(c++, b.to);
            insert.setString(c++, b.zone);
            insert.setInt(c++, b.concurrency);
            insert.setBoolean(c++, b.rerunCompleted);
            insert.setString(c++, b.params);
            insert.setInt(c++, b.total);
            insert.setBigDecimal(c++, b.estimatePerRun);
            insert.setBigDecimal(c++, b.estimateTotal);
            insert.setString(c++, b.estimateCurrency);
            insert.setString(c++, b.estimateBasis);
            insert.setObject(c++, b.createdBy);
            insert.setString(c++, b.createdVia);
            insert.setString(c++, b.clientId);
            insert.setTimestamp(c, Timestamp.from(b.dateCreated));
            return insert;
        }, keys);
        long id = keys.getKey().longValue();
        List<Object[]> rows = new ArrayList<>(periods.size());
        for (BackfillPeriods.Period p : periods) {
            rows.add(new Object[] {id, Timestamp.from(p.start), Timestamp.from(p.end), p.date, b.tenantId, b.jobId});
        }
        this.jdbc.batchUpdate("INSERT INTO backfill_slot (backfill_id, slot_start, slot_end, run_date, tenant_id, job_id, state) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'PENDING')", rows);
        return id;
    }

    /** Serialises the starts and reopenings of one workspace's backfills (its cap), until the transaction ends. */
    public void lockWorkspace(long tenantId) {
        this.jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "backfill:" + tenantId);
    }

    /** The workspace's open backfills (RUNNING or PAUSED). */
    public int openCount(long tenantId) {
        Integer n = this.jdbc.queryForObject("SELECT count(*) FROM backfill WHERE tenant_id = ? AND status IN (" + OPEN + ")", Integer.class,
            tenantId);
        return n == null ? 0 : n;
    }

    /** The first of these periods another open backfill of the job already holds: {backfillId, date}; empty when none. */
    public Optional<Object[]> heldElsewhere(long jobId, Collection<Instant> starts, Long except) {
        if (starts.isEmpty()) {
            return Optional.empty();
        }
        List<Object[]> found = this.jdbc.execute((ConnectionCallback<List<Object[]>>) connection -> {
            Array array = connection.createArrayOf("timestamptz", starts.stream().map(Timestamp::from).toArray());
            try (PreparedStatement query = connection.prepareStatement("SELECT backfill_id, run_date FROM backfill_slot WHERE job_id = ? "
                + "AND state IN ('PENDING', 'QUEUED') AND slot_start = ANY (?) AND backfill_id <> ? ORDER BY slot_start LIMIT 1")) {
                query.setLong(1, jobId);
                query.setArray(2, array);
                query.setLong(3, except == null ? -1L : except);
                List<Object[]> out = new ArrayList<>();
                try (ResultSet rs = query.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Object[] {rs.getLong(1), rs.getObject(2, LocalDate.class)});
                    }
                }
                return out;
            }
        });
        return found == null || found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /**
     * Which of these periods already have a completed run of the job -- one whose period (job_queue.run_slot_at: a scheduled
     * slot, a backfill period) falls in it. Answers the periods' starts. One read over the whole range.
     */
    public Set<Instant> covered(long jobId, List<BackfillPeriods.Period> periods) {
        Set<Instant> out = new HashSet<>();
        if (periods.isEmpty()) {
            return out;
        }
        Instant from = periods.get(0).start;
        Instant to = periods.get(0).end;
        for (BackfillPeriods.Period p : periods) {
            from = p.start.isBefore(from) ? p.start : from;
            to = p.end.isAfter(to) ? p.end : to;
        }
        List<Instant> done = this.jdbc.query("SELECT run_slot_at FROM job_queue WHERE job_id = ? AND run_slot_at >= ? AND run_slot_at < ? "
            + "AND UPPER(job_status) = 'COMPLETED'", (rs, i) -> rs.getTimestamp(1).toInstant(), jobId, Timestamp.from(from), Timestamp.from(to));
        if (done.isEmpty()) {
            return out;
        }
        Collections.sort(done);
        for (BackfillPeriods.Period p : periods) {
            int at = Collections.binarySearch(done, p.start);
            int first = at >= 0 ? at : -at - 1;
            if (first < done.size() && done.get(first).isBefore(p.end)) {
                out.add(p.start);
            }
        }
        return out;
    }

    /** Whether one period already has a completed run of the job. */
    public boolean covered(long jobId, Instant start, Instant end) {
        Boolean any = this.jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM job_queue WHERE job_id = ? AND run_slot_at >= ? AND run_slot_at < ? "
            + "AND UPPER(job_status) = 'COMPLETED')", Boolean.class, jobId, Timestamp.from(start), Timestamp.from(end));
        return Boolean.TRUE.equals(any);
    }

    // ------------------------------------------------------------------------------------------------------ reading

    private static final String BACKFILL_COLUMNS = "b.*, j.job_name, j.created_by AS job_created_by, j.assigned_user_id AS job_assigned_user_id, "
        + "c.pending, c.queued, c.completed, c.failed, c.skipped, c.cancelled";
    private static final String COUNTS = " LEFT JOIN LATERAL (SELECT count(*) FILTER (WHERE s.state = 'PENDING') AS pending, "
        + "count(*) FILTER (WHERE s.state = 'QUEUED') AS queued, count(*) FILTER (WHERE s.state = 'COMPLETED') AS completed, "
        + "count(*) FILTER (WHERE s.state = 'FAILED') AS failed, count(*) FILTER (WHERE s.state = 'SKIPPED') AS skipped, "
        + "count(*) FILTER (WHERE s.state = 'CANCELLED') AS cancelled FROM backfill_slot s WHERE s.backfill_id = b.backfill_id) c ON true ";

    public Optional<Backfill> find(long backfillId) {
        List<Backfill> found = this.jdbc.query("SELECT " + BACKFILL_COLUMNS + " FROM backfill b JOIN source_job j ON j.job_id = b.job_id"
            + COUNTS + "WHERE b.backfill_id = ?", BackfillStore::backfill, backfillId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** The backfill's row locked for this transaction, skipped when another holds it (the dispatcher's next tick takes it). */
    public Optional<Backfill> lock(long backfillId, boolean skipLocked) {
        List<Long> ids = this.jdbc.queryForList("SELECT backfill_id FROM backfill WHERE backfill_id = ? FOR UPDATE" + (skipLocked ? " SKIP LOCKED" : ""),
            Long.class, backfillId);
        return ids.isEmpty() ? Optional.empty() : this.find(backfillId);
    }

    /** A page of the caller's workspace's backfills, newest first: {rows, total}. {@code ownerPredicate} is JobOwnership's. */
    public Map<String, Object> list(Long jobId, List<String> statuses, String ownerPredicate, int page, int limit) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (jobId != null) {
            where.append(" AND b.job_id = ?");
            args.add(jobId);
        }
        if (statuses != null && !statuses.isEmpty()) {
            where.append(" AND b.status IN (");
            for (int i = 0; i < statuses.size(); i++) {
                where.append(i == 0 ? "?" : ", ?");
                args.add(statuses.get(i));
            }
            where.append(")");
        }
        where.append(ownerPredicate == null ? "" : ownerPredicate);
        Integer total = this.jdbc.queryForObject("SELECT count(*) FROM backfill b JOIN source_job j ON j.job_id = b.job_id" + where,
            Integer.class, args.toArray());
        List<Object> paged = new ArrayList<>(args);
        paged.add(limit);
        paged.add((long) (page - 1) * limit);
        List<Backfill> rows = this.jdbc.query("SELECT " + BACKFILL_COLUMNS + " FROM backfill b JOIN source_job j ON j.job_id = b.job_id"
            + COUNTS + where + " ORDER BY b.backfill_id DESC LIMIT ? OFFSET ?", BackfillStore::backfill, paged.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", total == null ? 0 : total);
        return out;
    }

    /** A page of a backfill's periods, oldest first, with each one's run; {@code state} null for every state. */
    public List<Slot> slots(long backfillId, String state, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        args.add(backfillId);
        String filter = "";
        if (state != null) {
            filter = " AND s.state = ?";
            args.add(state);
        }
        args.add(limit);
        args.add(offset);
        return this.jdbc.query("SELECT s.*, q.job_status AS run_status, q.start_time AS run_started_at, q.end_time AS run_ended_at "
            + "FROM backfill_slot s LEFT JOIN job_queue q ON q.job_queue_id = s.job_queue_id WHERE s.backfill_id = ?" + filter
            + " ORDER BY s.slot_start LIMIT ? OFFSET ?", BackfillStore::slot, args.toArray());
    }

    /** The backfill a run is part of and its period: {backfillId, date, step, state}; empty for a run of none. */
    public Optional<Map<String, Object>> ofRun(long jobQueueId) {
        List<Map<String, Object>> found = this.jdbc.query("SELECT b.backfill_id, b.step, b.status, b.job_id, s.run_date, s.slot_start, s.slot_end, "
            + "s.state FROM job_queue q JOIN backfill b ON b.backfill_id = q.backfill_id LEFT JOIN backfill_slot s ON s.backfill_id = b.backfill_id "
            + "AND s.slot_start = q.run_slot_at WHERE q.job_queue_id = ?", (rs, i) -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("backfillId", rs.getLong("backfill_id"));
                out.put("jobId", rs.getLong("job_id"));
                out.put("step", rs.getString("step"));
                out.put("status", rs.getString("status"));
                out.put("date", rs.getObject("run_date", LocalDate.class) == null ? null : rs.getObject("run_date", LocalDate.class).toString());
                out.put("periodStart", stamp(rs, "slot_start"));
                out.put("periodEnd", stamp(rs, "slot_end"));
                out.put("periodState", rs.getString("state"));
                return out;
            }, jobQueueId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** Every run a backfill made (each date's, retries included). */
    public List<Long> runsOf(long backfillId) {
        return this.jdbc.queryForList("SELECT job_queue_id FROM job_queue WHERE backfill_id = ? ORDER BY job_queue_id LIMIT 2000", Long.class,
            backfillId);
    }

    /**
     * Seconds the backfill's own completed runs took on average, else the job's last 20 completed runs'; null with none.
     * What the ETA is made of.
     */
    public Double averageRunSeconds(long backfillId, long jobId) {
        Double own = this.jdbc.queryForObject("SELECT avg(EXTRACT(EPOCH FROM (end_time - start_time))) FROM job_queue WHERE backfill_id = ? "
            + "AND job_status = 'Completed' AND end_time IS NOT NULL AND start_time IS NOT NULL", Double.class, backfillId);
        if (own != null) {
            return own;
        }
        return this.jdbc.queryForObject("SELECT avg(EXTRACT(EPOCH FROM (end_time - start_time))) FROM (SELECT end_time, start_time FROM job_queue "
            + "WHERE job_id = ? AND job_status = 'Completed' AND end_time IS NOT NULL AND start_time IS NOT NULL ORDER BY job_queue_id DESC "
            + "LIMIT 20) recent", Double.class, jobId);
    }

    /** The job's finished runs of the last 30 days, newest first, for the cost estimate; {@code byPipeline}: every job of its task. */
    public List<Long> recentFinishedRuns(Job job, boolean byPipeline, int limit) {
        if (byPipeline) {
            if (job.taskDetailId == null) {
                return Collections.emptyList();
            }
            return this.jdbc.queryForList("SELECT q.job_queue_id FROM job_queue q JOIN source_job j ON j.job_id = q.job_id WHERE j.task_detail_id = ? "
                + "AND q.job_status IN ('Completed', 'Failed') AND q.end_time >= now() - interval '30 days' ORDER BY q.job_queue_id DESC LIMIT ?",
                Long.class, job.taskDetailId, limit);
        }
        return this.jdbc.queryForList("SELECT job_queue_id FROM job_queue WHERE job_id = ? AND job_status IN ('Completed', 'Failed') "
            + "AND end_time >= now() - interval '30 days' ORDER BY job_queue_id DESC LIMIT ?", Long.class, job.jobId, limit);
    }

    // ------------------------------------------------------------------------------------------------------ changing

    /** Moves the backfill from one of {@code from} to {@code to}; false when it was in none of them. */
    public boolean move(long backfillId, Collection<String> from, String to, Long by) {
        String ended = "CANCELLED".equals(to) || "COMPLETED".equals(to) || "FAILED".equals(to) ? "now()" : "NULL";
        String paused = "PAUSED".equals(to) ? "now()" : "NULL";
        String who = "PAUSED".equals(to) ? ", paused_by = ?" : "CANCELLED".equals(to) ? ", cancelled_by = ?" : "";
        List<Object> args = new ArrayList<>();
        args.add(to);
        if (!who.isEmpty()) {
            args.add(by);
        }
        args.add(backfillId);
        StringBuilder in = new StringBuilder();
        for (String state : from) {
            in.append(in.length() == 0 ? "?" : ", ?");
            args.add(state);
        }
        return this.jdbc.update("UPDATE backfill SET status = ?, ended_at = " + ended + ", paused_at = " + paused + who
            + ", date_updated = now() WHERE backfill_id = ? AND status IN (" + in + ")", args.toArray()) == 1;
    }

    public void hold(long backfillId, String reason) {
        this.jdbc.update("UPDATE backfill SET hold_reason = ?, date_updated = now() WHERE backfill_id = ? AND hold_reason IS DISTINCT FROM ?",
            reason, backfillId, reason);
    }

    /** Every period still waiting is cancelled. */
    public int cancelPending(long backfillId) {
        return this.jdbc.update("UPDATE backfill_slot SET state = 'CANCELLED', note = 'Cancelled before it ran.', date_updated = now() "
            + "WHERE backfill_id = ? AND state = 'PENDING'", backfillId);
    }

    /** Failed periods back to waiting, but for any another open backfill of the job holds now; answers how many. */
    public int reopenFailed(long backfillId, long jobId) {
        return this.jdbc.update("UPDATE backfill_slot s SET state = 'PENDING', note = 'Retry asked for.', date_updated = now() "
            + "WHERE s.backfill_id = ? AND s.state = 'FAILED' AND NOT EXISTS (SELECT 1 FROM backfill_slot o WHERE o.job_id = ? "
            + "AND o.slot_start = s.slot_start AND o.state IN ('PENDING', 'QUEUED'))", backfillId, jobId);
    }

    /** The next waiting periods, oldest first. */
    public List<Slot> pending(long backfillId, int limit) {
        return this.jdbc.query("SELECT s.*, NULL AS run_status, NULL AS run_started_at, NULL AS run_ended_at FROM backfill_slot s "
            + "WHERE s.backfill_id = ? AND s.state = 'PENDING' ORDER BY s.slot_start LIMIT ?", BackfillStore::slot, backfillId, limit);
    }

    public void skip(Slot slot, String note) {
        this.jdbc.update("UPDATE backfill_slot SET state = 'SKIPPED', note = ?, date_updated = now() WHERE backfill_id = ? AND slot_start = ? "
            + "AND state = 'PENDING'", note, slot.backfillId, Timestamp.from(slot.start));
    }

    /** The lanes (1-4) the job's backfill runs hold now. */
    public Set<Integer> lanesInUse(long jobId) {
        return new HashSet<>(this.jdbc.queryForList("SELECT run_lane FROM job_queue q WHERE q.job_id = ? AND q.run_lane > 0 AND "
            + String.format(IN_FLIGHT, "q"), Integer.class, jobId));
    }

    /** How many of this backfill's runs are in flight. */
    public int inFlight(long backfillId) {
        Integer n = this.jdbc.queryForObject("SELECT count(*) FROM job_queue q WHERE q.backfill_id = ? AND q.run_lane > 0 AND "
            + String.format(IN_FLIGHT, "q"), Integer.class, backfillId);
        return n == null ? 0 : n;
    }

    /**
     * A queued run for the period, in {@code lane}: the job's run, as Run now makes one (the task's bucket and output folder,
     * not by hand), with its run.* values and parameters, its period and its backfill. The period is then QUEUED with it.
     * The one-in-flight index refuses a lane another run holds (the caller's transaction rolls back).
     */
    public long queueRun(Job job, Backfill backfill, Slot slot, int lane, String runParams, String correlationId, String message) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        this.jdbc.update(connection -> {
            PreparedStatement insert = connection.prepareStatement("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_send, job_status, "
                + "job_status_message, run_manual, start_time, status, bucket, output_folder, attempt, tenant_id, correlation_id, run_params, "
                + "run_slot_at, run_lane, backfill_id) VALUES (nextval('job_queue_source_seq'), now(), ?, false, 'Queue', ?, false, now(), "
                + "'Active', ?, ?, 1, ?, ?, ?, ?, ?, ?)", new String[] {"job_queue_id"});
            int c = 1;
            insert.setLong(c++, job.jobId);
            insert.setString(c++, message);
            insert.setString(c++, job.bucket);
            insert.setString(c++, job.outputFolder);
            insert.setLong(c++, job.tenantId);
            insert.setString(c++, correlationId);
            insert.setString(c++, runParams);
            insert.setTimestamp(c++, Timestamp.from(slot.start));
            insert.setShort(c++, (short) lane);
            insert.setLong(c, backfill.id);
            return insert;
        }, keys);
        long run = keys.getKey().longValue();
        this.jdbc.update("UPDATE backfill_slot SET state = 'QUEUED', job_queue_id = ?, tries = tries + 1, note = NULL, date_updated = now() "
            + "WHERE backfill_id = ? AND slot_start = ?", run, slot.backfillId, Timestamp.from(slot.start));
        return run;
    }

    /** Closes the backfill when nothing is waiting or in flight: COMPLETED, or FAILED with a failed period. Answers the new state. */
    public Optional<String> finishIfDone(long backfillId) {
        Counts c = this.find(backfillId).map(b -> b.counts).orElse(null);
        if (c == null || c.pending > 0 || c.queued > 0) {
            return Optional.empty();
        }
        String to = c.failed > 0 ? "FAILED" : "COMPLETED";
        return this.move(backfillId, Arrays.asList("RUNNING", "PAUSED"), to, null) ? Optional.of(to) : Optional.empty();
    }

    // ------------------------------------------------------------------------------------------------ the dispatcher

    /**
     * Every QUEUED period whose run has ended takes its run's outcome: COMPLETED, else FAILED with the run's last line. Answers
     * {backfill id, tenant id} of each period moved. The caller decides whose rows these are (the dispatcher: every workspace's).
     */
    public List<long[]> settleEndedRuns() {
        return this.jdbc.query("UPDATE backfill_slot s SET state = CASE WHEN q.job_status = 'Completed' THEN 'COMPLETED' ELSE 'FAILED' END, "
            + "note = CASE WHEN q.job_status = 'Completed' THEN NULL ELSE left(q.job_status_message, 500) END, date_updated = now() "
            + "FROM job_queue q WHERE s.state = 'QUEUED' AND q.job_queue_id = s.job_queue_id AND NOT " + String.format(IN_FLIGHT, "q")
            + " RETURNING s.backfill_id, s.tenant_id", (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)});
    }

    /** The open backfills, oldest first: {backfill id, tenant id}. */
    public List<long[]> open() {
        return this.jdbc.query("SELECT backfill_id, tenant_id FROM backfill WHERE status IN (" + OPEN + ") ORDER BY backfill_id",
            (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)});
    }

    /** Every backfill run in flight, in every lane of every job. */
    public int inFlightEverywhere() {
        Integer n = this.jdbc.queryForObject("SELECT count(*) FROM job_queue q WHERE q.run_lane > 0 AND " + String.format(IN_FLIGHT, "q"),
            Integer.class);
        return n == null ? 0 : n;
    }

    // -------------------------------------------------------------------------------------------------- rows to Java

    static Backfill backfill(ResultSet rs, int i) throws SQLException {
        Backfill b = new Backfill();
        b.id = rs.getLong("backfill_id");
        b.tenantId = rs.getLong("tenant_id");
        b.jobId = rs.getLong("job_id");
        b.jobName = rs.getString("job_name");
        b.jobCreatedBy = (Long) rs.getObject("job_created_by");
        b.jobAssignedUserId = (Long) rs.getObject("job_assigned_user_id");
        b.status = rs.getString("status");
        b.step = rs.getString("step");
        b.from = rs.getObject("range_from", LocalDate.class);
        b.to = rs.getObject("range_to", LocalDate.class);
        b.zone = rs.getString("zone");
        b.concurrency = rs.getInt("concurrency");
        b.rerunCompleted = rs.getBoolean("rerun_completed");
        b.params = rs.getString("params");
        b.total = rs.getInt("total");
        b.estimatePerRun = rs.getBigDecimal("estimate_per_run");
        b.estimateTotal = rs.getBigDecimal("estimate_total");
        b.estimateCurrency = rs.getString("estimate_currency");
        b.estimateBasis = rs.getString("estimate_basis");
        b.holdReason = rs.getString("hold_reason");
        b.createdBy = (Long) rs.getObject("created_by");
        b.createdVia = rs.getString("created_via");
        b.clientId = rs.getString("client_id");
        b.dateCreated = instant(rs, "date_created");
        b.endedAt = instant(rs, "ended_at");
        b.pausedAt = instant(rs, "paused_at");
        b.dateUpdated = instant(rs, "date_updated");
        b.counts.pending = rs.getInt("pending");
        b.counts.queued = rs.getInt("queued");
        b.counts.completed = rs.getInt("completed");
        b.counts.failed = rs.getInt("failed");
        b.counts.skipped = rs.getInt("skipped");
        b.counts.cancelled = rs.getInt("cancelled");
        return b;
    }

    static Slot slot(ResultSet rs, int i) throws SQLException {
        Slot s = new Slot();
        s.backfillId = rs.getLong("backfill_id");
        s.start = instant(rs, "slot_start");
        s.end = instant(rs, "slot_end");
        s.date = rs.getObject("run_date", LocalDate.class);
        s.state = rs.getString("state");
        s.jobQueueId = (Long) rs.getObject("job_queue_id");
        s.tries = rs.getInt("tries");
        s.note = rs.getString("note");
        s.runStatus = rs.getString("run_status");
        s.runStartedAt = instant(rs, "run_started_at");
        s.runEndedAt = instant(rs, "run_ended_at");
        return s;
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp at = rs.getTimestamp(column);
        return at == null ? null : at.toInstant();
    }

    private static String stamp(ResultSet rs, String column) throws SQLException {
        Instant at = instant(rs, column);
        return at == null ? null : at.toString();
    }
}

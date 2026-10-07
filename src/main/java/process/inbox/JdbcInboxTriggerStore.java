package process.inbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import process.pipeline.RunInputs;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link InboxTriggerStore} through the same DataSource as the JPA work around it, so an arrival's row commits with the
 * run it started. tenant_id is left to V185's triggers: the job's own.
 */
@Component
public class JdbcInboxTriggerStore implements InboxTriggerStore, RunInputs {

    private final JdbcTemplate jdbc;

    public JdbcInboxTriggerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** MIG-360: the advisory-lock namespace of "start this job's next inbox run" (any fixed number, the same everywhere). */
    static final int JOB_LOCK_SPACE = 0x1B0C_0360;

    @Override
    public List<Trigger> enabledFor(long tenantId) {
        return this.jdbc.query("SELECT job_id, tenant_id, enabled, file_pattern, date_updated, batch_size FROM job_inbox_trigger "
            + "WHERE tenant_id = ? AND enabled ORDER BY job_id", JdbcInboxTriggerStore::trigger, tenantId);
    }

    @Override
    public Optional<Trigger> find(long jobId) {
        return this.jdbc.query("SELECT job_id, tenant_id, enabled, file_pattern, COALESCE(date_updated, date_created) AS date_updated, "
            + "batch_size FROM job_inbox_trigger WHERE job_id = ?", JdbcInboxTriggerStore::trigger, jobId).stream().findFirst();
    }

    @Override
    public void save(long jobId, boolean enabled, String filePattern, int batchSize, Long actor) {
        this.jdbc.update("INSERT INTO job_inbox_trigger (job_id, enabled, file_pattern, batch_size, created_by, updated_by) "
            + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (job_id) DO UPDATE SET enabled = EXCLUDED.enabled, file_pattern = EXCLUDED.file_pattern, "
            + "batch_size = EXCLUDED.batch_size, updated_by = EXCLUDED.updated_by, date_updated = now()", jobId, enabled, filePattern,
            batchSize, actor, actor);
    }

    @Override
    public boolean delete(long jobId) {
        return this.jdbc.update("DELETE FROM job_inbox_trigger WHERE job_id = ?", jobId) > 0;
    }

    @Override
    public boolean recorded(String arrivalId, long jobId) {
        Long found = this.jdbc.queryForObject("SELECT count(*) FROM inbox_arrival WHERE arrival_id = ? AND job_id = ?", Long.class,
            arrivalId, jobId);
        return found != null && found > 0;
    }

    @Override
    public boolean record(InboxArrival a, long jobId, String outcome, String reason, Long jobQueueId) {
        return this.jdbc.update("INSERT INTO inbox_arrival (arrival_id, job_id, bucket, storage_key, file_name, bytes, outcome, reason, "
            + "job_queue_id, event_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            + (STARTED.equals(outcome) ? "" : " ON CONFLICT (arrival_id, job_id) DO NOTHING"),
            a.getArrivalId(), jobId, a.getAlias(), a.getKey(), a.getFileName(), a.getBytes(), outcome, reason, jobQueueId, a.getEventId()) > 0;
    }

    @Override
    public void lockJob(long jobId) {
        // Transaction-scoped: released at the commit or rollback of the caller's transaction, never held by a pooled session.
        this.jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", rs -> null, JOB_LOCK_SPACE, (int) (jobId ^ (jobId >>> 32)));
    }

    @Override
    public boolean inFlight(long jobId) {
        // The predicate of V83's ux_job_queue_one_in_flight_per_job, written as it is, so the planner reads that index.
        Boolean busy = this.jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM job_queue WHERE job_id = ? "
            + "AND UPPER(job_status) IN ('QUEUE', 'START', 'RUNNING'))", Boolean.class, jobId);
        return Boolean.TRUE.equals(busy);
    }

    @Override
    public List<Waiting> waiting(long jobId, int limit) {
        return this.jdbc.query("SELECT inbox_arrival_id, arrival_id, bucket, storage_key, file_name FROM inbox_arrival "
            + "WHERE job_id = ? AND outcome = 'Waiting' ORDER BY inbox_arrival_id LIMIT ? FOR UPDATE", (rs, i) -> new Waiting(
                rs.getLong("inbox_arrival_id"), rs.getString("arrival_id"), rs.getString("bucket"), rs.getString("storage_key"),
                rs.getString("file_name")), jobId, limit);
    }

    @Override
    public int started(List<Long> inboxArrivalIds, long jobQueueId) {
        if (inboxArrivalIds.isEmpty()) {
            return 0;
        }
        return this.jdbc.update(connection -> {
            PreparedStatement update = connection.prepareStatement("UPDATE inbox_arrival SET outcome = 'Started', "
                + "job_queue_id = ?, reason = NULL, started_at = now() WHERE inbox_arrival_id = ANY (?) AND outcome = 'Waiting'");
            update.setLong(1, jobQueueId);
            update.setArray(2, connection.createArrayOf("bigint", inboxArrivalIds.toArray()));
            return update;
        });
    }

    @Override
    public int skipWaiting(long jobId, String reason) {
        return this.jdbc.update("UPDATE inbox_arrival SET outcome = 'Skipped', reason = ? WHERE job_id = ? AND outcome = 'Waiting'",
            reason, jobId);
    }

    @Override
    public List<String> keysOf(long jobQueueId) {
        return this.jdbc.queryForList("SELECT storage_key FROM inbox_arrival WHERE job_queue_id = ? AND outcome = 'Started' "
            + "ORDER BY inbox_arrival_id", String.class, jobQueueId);
    }

    @Override
    public int waitingCount(long jobId) {
        Integer count = this.jdbc.queryForObject("SELECT count(*) FROM inbox_arrival WHERE job_id = ? AND outcome = 'Waiting'",
            Integer.class, jobId);
        return count == null ? 0 : count;
    }

    @Override
    public List<Map<String, Object>> arrivals(long jobId, int limit) {
        // A waiting file's place in its job's line (1: the next run takes it), from the partial index of the waiting files.
        return this.jdbc.query("SELECT a.arrival_id, a.bucket, a.storage_key, a.file_name, a.bytes, a.outcome, a.reason, a.job_queue_id, "
            + "a.date_created, a.started_at, CASE WHEN a.outcome = 'Waiting' THEN (SELECT count(*) FROM inbox_arrival w "
            + "WHERE w.job_id = a.job_id AND w.outcome = 'Waiting' AND w.inbox_arrival_id <= a.inbox_arrival_id) END AS place "
            + "FROM inbox_arrival a WHERE a.job_id = ? ORDER BY a.date_created DESC, a.inbox_arrival_id DESC LIMIT ?", (rs, i) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("arrivalId", rs.getString("arrival_id"));
                row.put("bucket", rs.getString("bucket"));
                row.put("key", rs.getString("storage_key"));
                row.put("fileName", rs.getString("file_name"));
                row.put("bytes", rs.getLong("bytes"));
                row.put("outcome", rs.getString("outcome"));
                row.put("reason", rs.getString("reason"));
                row.put("jobQueueId", rs.getObject("job_queue_id", Long.class));
                Timestamp created = rs.getTimestamp("date_created");
                row.put("dateCreated", created == null ? null : created.toInstant().toString());
                Timestamp started = rs.getTimestamp("started_at");
                row.put("startedAt", started == null ? null : started.toInstant().toString());
                row.put("place", rs.getObject("place", Long.class));
                return row;
            }, jobId, limit);
    }

    private static Trigger trigger(ResultSet rs, int row) throws SQLException {
        Timestamp updated = rs.getTimestamp("date_updated");
        return new Trigger(rs.getLong("job_id"), rs.getLong("tenant_id"), rs.getBoolean("enabled"), rs.getString("file_pattern"),
            updated == null ? null : updated.toInstant(), rs.getInt("batch_size"));
    }
}

package process.inbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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
public class JdbcInboxTriggerStore implements InboxTriggerStore {

    private final JdbcTemplate jdbc;

    public JdbcInboxTriggerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Trigger> enabledFor(long tenantId) {
        return this.jdbc.query("SELECT job_id, tenant_id, enabled, file_pattern, date_updated FROM job_inbox_trigger "
            + "WHERE tenant_id = ? AND enabled ORDER BY job_id", JdbcInboxTriggerStore::trigger, tenantId);
    }

    @Override
    public Optional<Trigger> find(long jobId) {
        return this.jdbc.query("SELECT job_id, tenant_id, enabled, file_pattern, COALESCE(date_updated, date_created) AS date_updated "
            + "FROM job_inbox_trigger WHERE job_id = ?", JdbcInboxTriggerStore::trigger, jobId).stream().findFirst();
    }

    @Override
    public void save(long jobId, boolean enabled, String filePattern, Long actor) {
        this.jdbc.update("INSERT INTO job_inbox_trigger (job_id, enabled, file_pattern, created_by, updated_by) VALUES (?, ?, ?, ?, ?) "
            + "ON CONFLICT (job_id) DO UPDATE SET enabled = EXCLUDED.enabled, file_pattern = EXCLUDED.file_pattern, "
            + "updated_by = EXCLUDED.updated_by, date_updated = now()", jobId, enabled, filePattern, actor, actor);
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
    public void record(InboxArrival a, long jobId, String outcome, String reason, Long jobQueueId) {
        this.jdbc.update("INSERT INTO inbox_arrival (arrival_id, job_id, bucket, storage_key, file_name, bytes, outcome, reason, "
            + "job_queue_id, event_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            + (STARTED.equals(outcome) ? "" : " ON CONFLICT (arrival_id, job_id) DO NOTHING"),
            a.getArrivalId(), jobId, a.getAlias(), a.getKey(), a.getFileName(), a.getBytes(), outcome, reason, jobQueueId, a.getEventId());
    }

    @Override
    public List<Map<String, Object>> arrivals(long jobId, int limit) {
        return this.jdbc.query("SELECT arrival_id, bucket, storage_key, file_name, bytes, outcome, reason, job_queue_id, date_created "
            + "FROM inbox_arrival WHERE job_id = ? ORDER BY date_created DESC, inbox_arrival_id DESC LIMIT ?", (rs, i) -> {
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
                return row;
            }, jobId, limit);
    }

    private static Trigger trigger(ResultSet rs, int row) throws SQLException {
        Timestamp updated = rs.getTimestamp("date_updated");
        return new Trigger(rs.getLong("job_id"), rs.getLong("tenant_id"), rs.getBoolean("enabled"), rs.getString("file_pattern"),
            updated == null ? null : updated.toInstant());
    }
}

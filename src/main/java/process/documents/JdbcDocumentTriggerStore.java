package process.documents;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import process.pipeline.RunDocuments;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link DocumentTriggerStore} through the same DataSource as the JPA work around it, so an approval's row commits with
 * the run it started. tenant_id is left to V207's triggers: the job's own. Also the step engine's {@link RunDocuments}:
 * the documents a run was started for are the approvals that name it.
 */
@Component
public class JdbcDocumentTriggerStore implements DocumentTriggerStore, RunDocuments {

    /** The advisory-lock namespace of "start this job's next document run" (any fixed number, the same everywhere). */
    static final int JOB_LOCK_SPACE = 0x1B0C_0364;

    private final JdbcTemplate jdbc;

    public JdbcDocumentTriggerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Trigger> enabledFor(long tenantId, long documentTypeId) {
        return this.jdbc.query("SELECT job_id, tenant_id, document_type_id, enabled, batch_size, COALESCE(date_updated, date_created) AS date_updated "
            + "FROM job_document_trigger WHERE tenant_id = ? AND document_type_id = ? AND enabled ORDER BY job_id", JdbcDocumentTriggerStore::trigger,
            tenantId, documentTypeId);
    }

    @Override
    public Optional<Trigger> find(long jobId) {
        return this.jdbc.query("SELECT job_id, tenant_id, document_type_id, enabled, batch_size, COALESCE(date_updated, date_created) AS date_updated "
            + "FROM job_document_trigger WHERE job_id = ?", JdbcDocumentTriggerStore::trigger, jobId).stream().findFirst();
    }

    @Override
    public List<Map<String, Object>> listed(long tenantId, String ownership) {
        return this.jdbc.query("SELECT t.job_id, j.job_name, j.job_status, t.document_type_id, t.enabled FROM job_document_trigger t "
            + "JOIN source_job j ON j.job_id = t.job_id WHERE t.tenant_id = ? AND j.job_status <> 'Delete'" + (ownership == null ? "" : ownership)
            + " ORDER BY t.document_type_id, lower(j.job_name), t.job_id", (rs, i) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("jobId", rs.getLong("job_id"));
                row.put("jobName", rs.getString("job_name"));
                row.put("jobStatus", rs.getString("job_status"));
                row.put("documentTypeId", rs.getLong("document_type_id"));
                row.put("enabled", rs.getBoolean("enabled"));
                return row;
            }, tenantId);
    }

    @Override
    public void save(long jobId, long documentTypeId, boolean enabled, int batchSize, Long actor) {
        this.jdbc.update("INSERT INTO job_document_trigger (job_id, document_type_id, enabled, batch_size, created_by, updated_by) "
            + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (job_id) DO UPDATE SET document_type_id = EXCLUDED.document_type_id, "
            + "enabled = EXCLUDED.enabled, batch_size = EXCLUDED.batch_size, updated_by = EXCLUDED.updated_by, date_updated = now()",
            jobId, documentTypeId, enabled, batchSize, actor, actor);
    }

    @Override
    public boolean delete(long jobId) {
        return this.jdbc.update("DELETE FROM job_document_trigger WHERE job_id = ?", jobId) > 0;
    }

    @Override
    public boolean recorded(long extractionId, int typeVersion, long jobId) {
        Boolean found = this.jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM document_approval WHERE extraction_id = ? AND type_version = ? "
            + "AND job_id = ?)", Boolean.class, extractionId, typeVersion, jobId);
        return Boolean.TRUE.equals(found);
    }

    @Override
    public boolean record(DocumentApproved a, long jobId, String outcome, String reason) {
        return this.jdbc.update("INSERT INTO document_approval (job_id, extraction_id, type_version, document_type_id, ocr_document_id, approval, "
            + "outcome, reason, event_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (extraction_id, type_version, job_id) DO NOTHING",
            jobId, a.getExtractionId(), a.getTypeVersion(), a.getDocumentTypeId(), a.getOcrDocumentId(), a.getApproval(), outcome, reason,
            a.getEventId()) > 0;
    }

    @Override
    public void lockJob(long jobId) {
        // Transaction-scoped: released at the commit or rollback of the caller's transaction, never held by a pooled session.
        this.jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", rs -> null, JOB_LOCK_SPACE, (int) (jobId ^ (jobId >>> 32)));
    }

    @Override
    public boolean inFlight(long jobId) {
        // The predicate of ux_job_queue_one_in_flight_per_job (V83, per lane since V211), written as it is, so the planner
        // reads that index: the job's own lane -- a backfill's runs (MIG-347) do not hold its live runs back, nor (V220) a
        // workflow's runs, one per request beside the job's own.
        Boolean busy = this.jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM job_queue WHERE job_id = ? AND run_lane = 0 "
            + "AND UPPER(job_status) IN ('QUEUE', 'START', 'RUNNING') AND workflow_instance_id IS NULL AND called_by_run_id IS NULL)", Boolean.class, jobId);
        return Boolean.TRUE.equals(busy);
    }

    @Override
    public List<Waiting> waiting(long jobId, int limit) {
        return this.jdbc.query("SELECT document_approval_id, extraction_id, type_version FROM document_approval "
            + "WHERE job_id = ? AND outcome = 'Waiting' ORDER BY document_approval_id LIMIT ? FOR UPDATE", (rs, i) -> new Waiting(
                rs.getLong("document_approval_id"), rs.getLong("extraction_id"), rs.getInt("type_version")), jobId, limit);
    }

    @Override
    public int started(List<Long> documentApprovalIds, long jobQueueId) {
        if (documentApprovalIds.isEmpty()) {
            return 0;
        }
        return this.jdbc.update(connection -> {
            PreparedStatement update = connection.prepareStatement("UPDATE document_approval SET outcome = 'Started', job_queue_id = ?, "
                + "reason = NULL, started_at = now() WHERE document_approval_id = ANY (?) AND outcome = 'Waiting'");
            update.setLong(1, jobQueueId);
            update.setArray(2, connection.createArrayOf("bigint", documentApprovalIds.toArray()));
            return update;
        });
    }

    @Override
    public int skipWaiting(long jobId, String reason) {
        return this.jdbc.update("UPDATE document_approval SET outcome = 'Skipped', reason = ? WHERE job_id = ? AND outcome = 'Waiting'",
            reason, jobId);
    }

    @Override
    public int waitingCount(long jobId) {
        Integer count = this.jdbc.queryForObject("SELECT count(*) FROM document_approval WHERE job_id = ? AND outcome = 'Waiting'",
            Integer.class, jobId);
        return count == null ? 0 : count;
    }

    @Override
    public List<Map<String, Object>> approvals(long jobId, int limit) {
        return this.jdbc.query("SELECT a.extraction_id, a.type_version, a.document_type_id, a.ocr_document_id, a.approval, a.outcome, a.reason, "
            + "a.job_queue_id, a.date_created, a.started_at, CASE WHEN a.outcome = 'Waiting' THEN (SELECT count(*) FROM document_approval w "
            + "WHERE w.job_id = a.job_id AND w.outcome = 'Waiting' AND w.document_approval_id <= a.document_approval_id) END AS place "
            + "FROM document_approval a WHERE a.job_id = ? ORDER BY a.date_created DESC, a.document_approval_id DESC LIMIT ?", (rs, i) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("extractionId", rs.getLong("extraction_id"));
                row.put("typeVersion", rs.getInt("type_version"));
                row.put("documentTypeId", rs.getLong("document_type_id"));
                row.put("ocrDocumentId", rs.getObject("ocr_document_id", Long.class));
                row.put("approval", rs.getString("approval"));
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

    // ---- the step engine's ------------------------------------------------------------------------------------------

    @Override
    public List<RunDocuments.Document> documentsOf(long jobQueueId) {
        return this.jdbc.query("SELECT extraction_id, document_type_id, type_version FROM document_approval WHERE job_queue_id = ? "
            + "AND outcome = 'Started' ORDER BY document_approval_id", (rs, i) -> new RunDocuments.Document(rs.getLong("extraction_id"),
                rs.getLong("document_type_id"), rs.getInt("type_version")), jobQueueId);
    }

    /** The documents each of these runs was started for (the console's run list), by run. */
    public Map<Long, List<Map<String, Object>>> documentsOfRuns(List<Long> jobQueueIds) {
        Map<Long, List<Map<String, Object>>> byRun = new LinkedHashMap<>();
        if (jobQueueIds.isEmpty()) {
            return byRun;
        }
        this.jdbc.query(connection -> {
            PreparedStatement select = connection.prepareStatement("SELECT job_queue_id, extraction_id, document_type_id, type_version, approval "
                + "FROM document_approval WHERE job_queue_id = ANY (?) AND outcome = 'Started' ORDER BY job_queue_id, document_approval_id");
            select.setArray(1, connection.createArrayOf("bigint", jobQueueIds.toArray()));
            return select;
        }, (ResultSet rs) -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("extractionId", rs.getLong("extraction_id"));
            d.put("documentTypeId", rs.getLong("document_type_id"));
            d.put("typeVersion", rs.getInt("type_version"));
            d.put("approval", rs.getString("approval"));
            byRun.computeIfAbsent(rs.getLong("job_queue_id"), k -> new ArrayList<>()).add(d);
        });
        return byRun;
    }

    private static Trigger trigger(ResultSet rs, int row) throws SQLException {
        Timestamp updated = rs.getTimestamp("date_updated");
        return new Trigger(rs.getLong("job_id"), rs.getLong("tenant_id"), rs.getLong("document_type_id"), rs.getBoolean("enabled"),
            rs.getInt("batch_size"), updated == null ? null : updated.toInstant());
    }
}

package process.pipeline.approval;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@link RunApprovalStore} in plain SQL (V206), as the caller's workspace under row security. */
@Repository
public class JdbcRunApprovalStore implements RunApprovalStore {

    private static final String COLUMNS = "run_approval_id, tenant_id, job_queue_id, attempt, step_execution_id, step_index, step_key, "
        + "status, title, reviewers_kind, reviewers_value, records_in, requested_at, expires_at, decided_at, decided_by_user_id, "
        + "decided_by_name, comment, workflow_instance_id, workflow_task_id";

    private final JdbcTemplate jdbc;

    public JdbcRunApprovalStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insert(Approval a) {
        return this.jdbc.queryForObject("INSERT INTO run_approval (job_queue_id, attempt, step_execution_id, step_index, step_key, title, "
                + "reviewers_kind, reviewers_value, records_in, requested_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "RETURNING run_approval_id", Long.class, a.jobQueueId, a.attempt, a.stepExecutionId, a.stepIndex, a.stepKey,
            cut(a.title, 255), a.reviewersKind, a.reviewersValue, a.recordsIn, Timestamp.from(a.requestedAt), Timestamp.from(a.expiresAt));
    }

    @Override
    public Optional<Approval> find(long runApprovalId) {
        return this.jdbc.query("SELECT " + COLUMNS + " FROM run_approval WHERE run_approval_id = ?", JdbcRunApprovalStore::row, runApprovalId)
            .stream().findFirst();
    }

    @Override
    public Optional<Approval> decide(long runApprovalId, String status, Long userId, String name, String comment, Long instanceId,
                                     Long taskId, Instant at) {
        return this.jdbc.query("UPDATE run_approval SET status = ?, decided_at = ?, decided_by_user_id = ?, decided_by_name = ?, comment = ?, "
                + "workflow_instance_id = COALESCE(?, workflow_instance_id), workflow_task_id = COALESCE(?, workflow_task_id), "
                + "date_updated = now() WHERE run_approval_id = ? AND status = 'PENDING' RETURNING " + COLUMNS, JdbcRunApprovalStore::row,
            status, Timestamp.from(at), userId, cut(name, 255), cut(comment, 2000), instanceId, taskId, runApprovalId).stream().findFirst();
    }

    @Override
    public List<Approval> expired(Instant now, int limit) {
        return this.jdbc.query("SELECT " + COLUMNS + " FROM run_approval WHERE status = 'PENDING' AND expires_at <= ? "
            + "ORDER BY expires_at, run_approval_id LIMIT ?", JdbcRunApprovalStore::row, Timestamp.from(now), limit);
    }

    @Override
    public List<Approval> orphaned(int limit) {
        return this.jdbc.query("SELECT " + COLUMNS.replaceAll("(^|, )", "$1a.") + " FROM run_approval a JOIN job_queue q ON q.job_queue_id = a.job_queue_id "
            + "WHERE a.status = 'PENDING' AND (UPPER(q.job_status) <> 'RUNNING' OR q.attempt <> a.attempt) "
            + "ORDER BY a.run_approval_id LIMIT ?", JdbcRunApprovalStore::row, limit);
    }

    @Override
    public Map<Long, Approval> ofSteps(Collection<Long> stepExecutionIds) {
        Map<Long, Approval> out = new HashMap<>();
        if (stepExecutionIds == null || stepExecutionIds.isEmpty()) {
            return out;
        }
        new NamedParameterJdbcTemplate(this.jdbc).query("SELECT " + COLUMNS + " FROM run_approval WHERE step_execution_id IN (:ids)",
            new MapSqlParameterSource("ids", stepExecutionIds), rs -> {
                Approval a = row(rs, 0);
                out.put(a.stepExecutionId, a);
            });
        return out;
    }

    static Approval row(ResultSet rs, int n) throws SQLException {
        Approval a = new Approval();
        a.runApprovalId = rs.getLong("run_approval_id");
        a.tenantId = rs.getLong("tenant_id");
        a.jobQueueId = rs.getLong("job_queue_id");
        a.attempt = rs.getInt("attempt");
        a.stepExecutionId = rs.getLong("step_execution_id");
        a.stepIndex = rs.getInt("step_index");
        a.stepKey = rs.getString("step_key");
        a.status = rs.getString("status");
        a.title = rs.getString("title");
        a.reviewersKind = rs.getString("reviewers_kind");
        a.reviewersValue = rs.getString("reviewers_value");
        a.recordsIn = (Long) rs.getObject("records_in");
        a.requestedAt = instant(rs, "requested_at");
        a.expiresAt = instant(rs, "expires_at");
        a.decidedAt = instant(rs, "decided_at");
        a.decidedByUserId = (Long) rs.getObject("decided_by_user_id");
        a.decidedByName = rs.getString("decided_by_name");
        a.comment = rs.getString("comment");
        a.workflowInstanceId = (Long) rs.getObject("workflow_instance_id");
        a.workflowTaskId = (Long) rs.getObject("workflow_task_id");
        return a;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
        return at == null ? null : at.toInstant();
    }

    private static String cut(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}

package process.pipeline;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.time.Instant;

/** {@link RunAttemptStore} in run_attempt (V205), as the caller's session sees it (row security). */
@Component
public class JdbcRunAttemptStore implements RunAttemptStore {

    private static final String COLUMNS = "SELECT job_queue_id, attempt, previous_attempt, mode, from_step_index, from_step_key, "
        + "reused_steps, pipeline_definition_id, requested_via, requested_by_user_id, requested_by_client_id, requested_at FROM run_attempt ";

    private static final RowMapper<Attempt> ROW = (rs, n) -> {
        Attempt attempt = new Attempt();
        attempt.jobQueueId = rs.getLong("job_queue_id");
        attempt.attempt = rs.getInt("attempt");
        attempt.previousAttempt = rs.getInt("previous_attempt");
        attempt.mode = rs.getString("mode");
        attempt.fromStepIndex = (Integer) rs.getObject("from_step_index");
        attempt.fromStepKey = rs.getString("from_step_key");
        attempt.reusedSteps = rs.getInt("reused_steps");
        attempt.pipelineDefinitionId = (Long) rs.getObject("pipeline_definition_id");
        attempt.requestedVia = rs.getString("requested_via");
        attempt.requestedByUserId = (Long) rs.getObject("requested_by_user_id");
        attempt.requestedByClientId = rs.getString("requested_by_client_id");
        OffsetDateTime at = rs.getObject("requested_at", OffsetDateTime.class);
        attempt.requestedAt = at == null ? null : at.toInstant();
        return attempt;
    };

    private final JdbcTemplate jdbc;

    public JdbcRunAttemptStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(Attempt attempt) {
        this.jdbc.update("INSERT INTO run_attempt (job_queue_id, attempt, previous_attempt, mode, from_step_index, from_step_key, "
            + "reused_steps, pipeline_definition_id, requested_via, requested_by_user_id, requested_by_client_id, requested_at, created_by) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", attempt.jobQueueId, attempt.attempt, attempt.previousAttempt, attempt.mode,
            attempt.fromStepIndex, attempt.fromStepKey, attempt.reusedSteps, attempt.pipelineDefinitionId, attempt.requestedVia,
            attempt.requestedByUserId, attempt.requestedByClientId, Timestamp.from(attempt.requestedAt == null ? Instant.now()
                : attempt.requestedAt), attempt.requestedByUserId);
    }

    @Override
    public Optional<Attempt> find(long jobQueueId, int attempt) {
        return this.jdbc.query(COLUMNS + "WHERE job_queue_id = ? AND attempt = ?", ROW, jobQueueId, attempt).stream().findFirst();
    }

    @Override
    public List<Attempt> ofRun(long jobQueueId) {
        return this.jdbc.query(COLUMNS + "WHERE job_queue_id = ? ORDER BY attempt", ROW, jobQueueId);
    }
}

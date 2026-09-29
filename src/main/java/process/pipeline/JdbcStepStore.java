package process.pipeline;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import process.util.BusinessTime;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link StepStore} on the application's DataSource, under row-level security: the engine writes as the run's
 * workspace (RowSecurity.forTenant), the console reads as its caller. tenant_id is left to the triggers (V102's
 * tenant_id_from_parent), so a row is always its run's workspace's. Each write is its own statement, committed as it
 * happens: the timeline shows a step Running while it runs.
 */
@Component
public class JdbcStepStore implements StepStore {

    /** A step_log line longer than this is cut: a log is for reading, and one runaway line must not fill a table. */
    static final int MAX_LINE = 4000;
    static final int MAX_STATUS_MESSAGE = 1024;

    private static final String STEP_COLUMNS = "step_execution_id, job_queue_id, attempt, step_index, step_key, task_code, status, "
        + "started_at, ended_at, records_in, records_out, tries, on_error, status_message, error::text AS error, pipeline_definition_id";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    public JdbcStepStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Also seals the run's callbacks: a run with no token hash would accept the shared legacy worker secret
     * (RunCallbackTokens), and no worker has any business reporting on a run the engine runs. The hash is of nothing
     * anyone holds, so every worker callback on the run is refused as a mismatch.
     */
    @Override
    public boolean claimForEngine(long jobQueueId, String correlationId) {
        return this.jdbc.update("UPDATE job_queue SET job_send = true, prepared_at = now(), prepare_lease_until = NULL, "
            + "correlation_id = COALESCE(correlation_id, ?), callback_token_hash = ?, callback_token_attempt = GREATEST(attempt, 1), "
            + "callback_token_expires_at = NULL WHERE job_queue_id = ? AND UPPER(job_status) = 'QUEUE' "
            + "AND job_send = false AND prepared_at IS NULL", correlationId, unheldHash(), jobQueueId) == 1;
    }

    private static String unheldHash() {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        StringBuilder hex = new StringBuilder(64);
        for (byte b : random) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    @Override
    public Optional<Long> pinnedDefinition(long jobQueueId) {
        List<Long> found = this.jdbc.queryForList("SELECT pipeline_definition_id FROM step_execution WHERE job_queue_id = ? "
            + "AND pipeline_definition_id IS NOT NULL ORDER BY attempt, step_index LIMIT 1", Long.class, jobQueueId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public List<Long> plan(long jobQueueId, int attempt, long pipelineDefinitionId, List<Planned> steps) {
        for (Planned step : steps) {
            this.jdbc.update("INSERT INTO step_execution (job_queue_id, attempt, step_index, task_code, step_key, on_error, "
                + "pipeline_definition_id, status) VALUES (?, ?, ?, ?, ?, ?, ?, 'Queue') "
                + "ON CONFLICT (job_queue_id, attempt, step_index) DO NOTHING", jobQueueId, attempt, step.index, step.task,
                step.key, step.onError, pipelineDefinitionId);
        }
        List<Long> ids = new ArrayList<>();
        for (Planned step : steps) {
            ids.add(this.jdbc.queryForObject("SELECT step_execution_id FROM step_execution WHERE job_queue_id = ? AND attempt = ? "
                + "AND step_index = ?", Long.class, jobQueueId, attempt, step.index));
        }
        return ids;
    }

    @Override
    public void started(long stepExecutionId, Long recordsIn) {
        this.jdbc.update("UPDATE step_execution SET status = 'Running', started_at = now(), records_in = ?, date_updated = now() "
            + "WHERE step_execution_id = ?", recordsIn, stepExecutionId);
    }

    @Override
    public void tried(long stepExecutionId, int tries) {
        this.jdbc.update("UPDATE step_execution SET tries = ?, date_updated = now() WHERE step_execution_id = ?", tries, stepExecutionId);
    }

    @Override
    public void ended(long stepExecutionId, String status, Long recordsOut, String errorJson, String message) {
        this.jdbc.update("UPDATE step_execution SET status = ?, ended_at = GREATEST(now(), COALESCE(started_at, now())), "
            + "records_out = ?, error = ?::jsonb, status_message = ?, date_updated = now() WHERE step_execution_id = ?", status,
            recordsOut, errorJson, cut(message, MAX_STATUS_MESSAGE), stepExecutionId);
    }

    @Override
    public void notRun(long stepExecutionId, String status, String message) {
        this.jdbc.update("UPDATE step_execution SET status = ?, status_message = ?, date_updated = now() WHERE step_execution_id = ?",
            status, cut(message, MAX_STATUS_MESSAGE), stepExecutionId);
    }

    @Override
    public void log(long stepExecutionId, int lineNo, String level, String message) {
        this.jdbc.update("INSERT INTO step_log (step_execution_id, line_no, level, message) VALUES (?, ?, ?, ?) "
            + "ON CONFLICT (step_execution_id, line_no) DO NOTHING", stepExecutionId, lineNo, level, cut(message, MAX_LINE));
    }

    @Override
    public long dataset(long stepExecutionId, String name, String storageKey, long rows, String columnsJson, Instant expiresAt) {
        return this.jdbc.queryForObject("INSERT INTO run_dataset (step_execution_id, name, storage_key, row_count, columns, expires_at) "
            + "VALUES (?, ?, ?, ?, ?::jsonb, ?) ON CONFLICT (step_execution_id, name) DO UPDATE SET storage_key = EXCLUDED.storage_key, "
            + "row_count = EXCLUDED.row_count, columns = EXCLUDED.columns, expires_at = EXCLUDED.expires_at, date_updated = now() "
            + "RETURNING run_dataset_id", Long.class, stepExecutionId, name, storageKey, rows, columnsJson,
            expiresAt == null ? null : Timestamp.from(expiresAt));
    }

    @Override
    public List<StepRow> stepsOfRun(long jobQueueId) {
        return this.jdbc.query("SELECT " + STEP_COLUMNS + " FROM step_execution WHERE job_queue_id = ? ORDER BY attempt, step_index",
            JdbcStepStore::step, jobQueueId);
    }

    @Override
    public Optional<StepRow> stepById(long stepExecutionId) {
        List<StepRow> found = this.jdbc.query("SELECT " + STEP_COLUMNS + " FROM step_execution WHERE step_execution_id = ?",
            JdbcStepStore::step, stepExecutionId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    @Override
    public List<LogLine> logOf(long stepExecutionId) {
        return this.jdbc.query("SELECT line_no, level, message, logged_at FROM step_log WHERE step_execution_id = ? ORDER BY line_no",
            (rs, n) -> {
                LogLine line = new LogLine();
                line.lineNo = rs.getInt("line_no");
                line.level = rs.getString("level");
                line.message = rs.getString("message");
                line.loggedAt = wallClock(rs, "logged_at");
                return line;
            }, stepExecutionId);
    }

    @Override
    public List<DatasetRow> datasetsOfRun(long jobQueueId) {
        return this.jdbc.query("SELECT d.run_dataset_id, d.step_execution_id, d.name, d.row_count, d.columns::text AS columns, d.expires_at "
            + "FROM run_dataset d JOIN step_execution s ON s.step_execution_id = d.step_execution_id WHERE s.job_queue_id = ? "
            + "ORDER BY s.attempt, s.step_index, d.name", (rs, n) -> {
                DatasetRow row = new DatasetRow();
                row.runDatasetId = rs.getLong("run_dataset_id");
                row.stepExecutionId = rs.getLong("step_execution_id");
                row.name = rs.getString("name");
                row.rowCount = (Long) rs.getObject("row_count");
                row.columns = rs.getString("columns");
                row.expiresAt = wallClock(rs, "expires_at");
                return row;
            }, jobQueueId);
    }

    private static StepRow step(ResultSet rs, int n) throws SQLException {
        StepRow row = new StepRow();
        row.stepExecutionId = rs.getLong("step_execution_id");
        row.jobQueueId = rs.getLong("job_queue_id");
        row.attempt = rs.getInt("attempt");
        row.stepIndex = rs.getInt("step_index");
        row.stepKey = rs.getString("step_key");
        row.taskCode = rs.getString("task_code");
        row.status = rs.getString("status");
        OffsetDateTime started = rs.getObject("started_at", OffsetDateTime.class);
        OffsetDateTime ended = rs.getObject("ended_at", OffsetDateTime.class);
        row.startedAt = started == null ? null : BusinessTime.wallClockOf(started.toInstant());
        row.endedAt = ended == null ? null : BusinessTime.wallClockOf(ended.toInstant());
        row.durationMs = started == null || ended == null ? null : ended.toInstant().toEpochMilli() - started.toInstant().toEpochMilli();
        row.recordsIn = (Long) rs.getObject("records_in");
        row.recordsOut = (Long) rs.getObject("records_out");
        row.tries = rs.getInt("tries");
        row.onError = rs.getString("on_error");
        row.statusMessage = rs.getString("status_message");
        row.error = rs.getString("error");
        row.pipelineDefinitionId = (Long) rs.getObject("pipeline_definition_id");
        return row;
    }

    private static LocalDateTime wallClock(ResultSet rs, String column) throws SQLException {
        OffsetDateTime at = rs.getObject(column, OffsetDateTime.class);
        return at == null ? null : BusinessTime.wallClockOf(at.toInstant());
    }

    private static String cut(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max - 3) + "...";
    }
}

package process.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@link ModelChoiceStore} through the same DataSource as the JPA work around it, so a step's row commits with the
 * verdict it belongs to (PreDispatchPhase writes both in one transaction). source_job.model_profiles is written only
 * here -- JPA maps it read-only -- so no job edit can put back a setting someone changed.
 */
@Component
public class JdbcModelChoiceStore implements ModelChoiceStore {

    private final JdbcTemplate jdbc;

    public JdbcModelChoiceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int saveScheduleProfiles(Long jobId, Long tenantId, String profiles, Long updatedBy) {
        return this.jdbc.update("UPDATE source_job SET model_profiles = ?, updated_by = COALESCE(?, updated_by) "
            + "WHERE job_id = ? AND tenant_id = ?", profiles, updatedBy, jobId, tenantId);
    }

    @Override
    public String scheduleProfiles(Long jobId, Long tenantId) {
        List<String> found = this.jdbc.queryForList("SELECT model_profiles FROM source_job WHERE job_id = ? AND tenant_id = ?",
            String.class, jobId, tenantId);
        return found.isEmpty() ? null : found.get(0);
    }

    @Override
    public void recordSteps(List<RunAiStep> steps) {
        for (RunAiStep s : steps) {
            // tenant_id is left to the trigger: the run's own (V102's tenant_id_from_parent).
            this.jdbc.update(connection -> {
                PreparedStatement p = connection.prepareStatement("INSERT INTO run_ai_step (job_queue_id, attempt, step_key, "
                    + "run_in, prompt_id, prompt_version, model_profile, profile_source, outcome, model, connection_id, model_option_id, "
                    + "model_choice, reused, error) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT (job_queue_id, attempt, step_key) DO UPDATE SET run_in = EXCLUDED.run_in, "
                    + "prompt_id = EXCLUDED.prompt_id, prompt_version = EXCLUDED.prompt_version, model_profile = EXCLUDED.model_profile, "
                    + "profile_source = EXCLUDED.profile_source, outcome = EXCLUDED.outcome, model = EXCLUDED.model, "
                    + "connection_id = EXCLUDED.connection_id, model_option_id = EXCLUDED.model_option_id, "
                    + "model_choice = EXCLUDED.model_choice, reused = EXCLUDED.reused, error = EXCLUDED.error, date_updated = now()");
                int i = 0;
                p.setLong(++i, s.jobQueueId);
                p.setInt(++i, s.attempt == null ? 1 : s.attempt);
                p.setString(++i, s.stepKey);
                p.setString(++i, s.runIn);
                setLong(p, ++i, s.promptId);
                if (s.promptVersion == null) p.setNull(++i, Types.INTEGER);
                else p.setInt(++i, s.promptVersion);
                p.setString(++i, s.modelProfile);
                p.setString(++i, s.profileSource);
                p.setString(++i, s.outcome);
                p.setString(++i, s.model);
                setLong(p, ++i, s.connectionId);
                setLong(p, ++i, s.modelOptionId);
                p.setString(++i, s.modelChoice);
                p.setBoolean(++i, s.reused);
                p.setString(++i, s.error);
                return p;
            });
        }
    }

    @Override
    public List<RunAiStep> stepsOfRun(Long jobQueueId) {
        return this.jdbc.query("SELECT job_queue_id, attempt, step_key, run_in, prompt_id, prompt_version, model_profile, profile_source, "
            + "outcome, model, connection_id, model_option_id, model_choice, reused, error, date_created FROM run_ai_step "
            + "WHERE job_queue_id = ? ORDER BY attempt, run_ai_step_id", JdbcModelChoiceStore::row, jobQueueId);
    }

    private static RunAiStep row(ResultSet rs, int n) throws SQLException {
        RunAiStep s = new RunAiStep();
        s.jobQueueId = rs.getLong("job_queue_id");
        s.attempt = rs.getInt("attempt");
        s.stepKey = rs.getString("step_key");
        s.runIn = rs.getString("run_in");
        s.promptId = (Long) rs.getObject("prompt_id");
        s.promptVersion = (Integer) rs.getObject("prompt_version");
        s.modelProfile = rs.getString("model_profile");
        s.profileSource = rs.getString("profile_source");
        s.outcome = rs.getString("outcome");
        s.model = rs.getString("model");
        s.connectionId = (Long) rs.getObject("connection_id");
        s.modelOptionId = (Long) rs.getObject("model_option_id");
        s.modelChoice = rs.getString("model_choice");
        s.reused = rs.getBoolean("reused");
        s.error = rs.getString("error");
        s.dateCreated = rs.getObject("date_created", OffsetDateTime.class);
        return s;
    }

    private static void setLong(PreparedStatement p, int i, Long value) throws SQLException {
        if (value == null) {
            p.setNull(i, Types.BIGINT);
        } else {
            p.setLong(i, value);
        }
    }
}

package process.ai;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes a run's result into result_record (V180) with the model that made it (V182, MIG-242): when the result is an
 * AI step's answer, its prompt, prompt version, model connection, model, option and how the model was chosen are
 * copied from that step's latest attempt in run_ai_step -- what ai-service answered it ran on -- so the result names
 * them exactly as the run's manifest does. A result no AI step made (stepKey null) carries none of them.
 *
 * Every result starts PENDING (MIG-221); its workspace is its run's (the V102 trigger). The run's result producer --
 * the worker's result.json on completion, with the customer intake (MIG-236) -- is not built yet; this is the one
 * place it writes through.
 */
@Component
public class ResultRecords {

    private final JdbcTemplate jdbc;

    public ResultRecords(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param resultJson        the result, a JSON object (the table refuses anything else)
     * @param subjectRef        the keyed hash of the case's subject, or null
     * @param contractVersionId the OUT contract version it was checked against, or null
     * @param stepKey           the AI step whose answer it is, or null
     * @return the new result_record_id
     */
    public long write(Long jobQueueId, String resultJson, String subjectRef, Long contractVersionId, String stepKey) {
        return this.jdbc.queryForObject("INSERT INTO result_record (job_queue_id, result, subject_ref, contract_version_id, step_key, "
            + "prompt_id, prompt_version, model_connection_id, model, model_option_id, model_choice) "
            + "SELECT ?, ?::jsonb, ?, ?, ?, s.prompt_id, CASE WHEN s.prompt_id IS NULL THEN NULL ELSE s.prompt_version END, "
            + "s.connection_id, s.model, s.model_option_id, s.model_choice "
            + "FROM (SELECT 1) one LEFT JOIN LATERAL (SELECT * FROM run_ai_step r WHERE r.job_queue_id = ? AND r.step_key = ? "
            + "ORDER BY r.attempt DESC LIMIT 1) s ON true RETURNING result_record_id",
            Long.class, jobQueueId, resultJson, subjectRef, contractVersionId, stepKey, jobQueueId, stepKey);
    }
}

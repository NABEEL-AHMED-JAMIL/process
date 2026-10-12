package process.documents;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import process.pipeline.backing.DocumentReadMarks;

/**
 * {@link DocumentReadMarks} in run_document_read (V207), as the run's workspace (the step engine runs a run as its
 * workspace; row security keeps both statements to it). A run's row is replaced by its later tries and attempts.
 */
@Component
public class JdbcDocumentReadMarks implements DocumentReadMarks {

    private final JdbcTemplate jdbc;

    public JdbcDocumentReadMarks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long watermark(long jobId, String stepKey, long documentTypeId) {
        // Only a run that completed moves the mark: a failed or interrupted run's documents are read again by the next.
        Long mark = this.jdbc.queryForObject("SELECT max(r.up_to) FROM run_document_read r JOIN job_queue q ON q.job_queue_id = r.job_queue_id "
            + "WHERE r.job_id = ? AND r.step_key = ? AND r.document_type_id = ? AND UPPER(q.job_status) = 'COMPLETED'", Long.class, jobId, stepKey,
            documentTypeId);
        return mark == null ? 0 : mark;
    }

    @Override
    public void record(long jobQueueId, String stepKey, long jobId, long documentTypeId, long upTo, long rows, int attempt) {
        this.jdbc.update("INSERT INTO run_document_read (job_queue_id, step_key, job_id, document_type_id, up_to, rows_read, attempt) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (job_queue_id, step_key) DO UPDATE SET job_id = EXCLUDED.job_id, "
            + "document_type_id = EXCLUDED.document_type_id, up_to = EXCLUDED.up_to, rows_read = EXCLUDED.rows_read, attempt = EXCLUDED.attempt, "
            + "date_updated = now()", jobQueueId, stepKey, jobId, documentTypeId, upTo, rows, attempt);
    }
}

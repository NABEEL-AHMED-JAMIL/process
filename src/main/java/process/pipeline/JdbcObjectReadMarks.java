package process.pipeline;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import process.pipeline.backing.ObjectReadMarks;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * {@link ObjectReadMarks} in run_object_read (V216), as the run's workspace (the step engine runs a run as its workspace;
 * row security keeps both statements to it). A run's row is replaced by its later tries and attempts.
 */
@Component
public class JdbcObjectReadMarks implements ObjectReadMarks {

    private final JdbcTemplate jdbc;

    public JdbcObjectReadMarks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Mark> watermark(long jobId, String stepKey, String bucket, String prefix) {
        // Only a run that completed moves the mark: a failed or interrupted run's files are read again by the next.
        List<Mark> marks = this.jdbc.query("SELECT r.up_to_modified, r.up_to_key FROM run_object_read r JOIN job_queue q "
            + "ON q.job_queue_id = r.job_queue_id WHERE r.job_id = ? AND r.step_key = ? AND r.bucket_alias = ? AND r.prefix = ? "
            + "AND UPPER(q.job_status) = 'COMPLETED' AND r.up_to_modified IS NOT NULL ORDER BY r.up_to_modified DESC, r.up_to_key DESC LIMIT 1",
            (rs, i) -> new Mark(rs.getTimestamp(1).toInstant(), rs.getString(2)), jobId, stepKey, bucket, prefix);
        return marks.isEmpty() ? Optional.empty() : Optional.of(marks.get(0));
    }

    @Override
    public void record(long jobQueueId, String stepKey, long jobId, String bucket, String prefix, Mark upTo, long objects, int attempt) {
        this.jdbc.update("INSERT INTO run_object_read (job_queue_id, step_key, job_id, bucket_alias, prefix, up_to_modified, up_to_key, "
            + "objects_read, attempt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (job_queue_id, step_key) DO UPDATE SET "
            + "job_id = EXCLUDED.job_id, bucket_alias = EXCLUDED.bucket_alias, prefix = EXCLUDED.prefix, up_to_modified = EXCLUDED.up_to_modified, "
            + "up_to_key = EXCLUDED.up_to_key, objects_read = EXCLUDED.objects_read, attempt = EXCLUDED.attempt, date_updated = now()",
            jobQueueId, stepKey, jobId, bucket, prefix, upTo == null ? null : Timestamp.from(upTo.modified), upTo == null ? null : upTo.key,
            objects, attempt);
    }
}

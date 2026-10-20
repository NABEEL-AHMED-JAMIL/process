package process.pipeline.backing;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 2026-10-19 ({@code read_runs}): a pipeline's runs in the run's workspace, each with its review, as rows -- for a
 * pipeline that reports on a review process (approved against rejected, who decided, how long runs and reviews took).
 * The engine reads as the run's workspace (row-level security), so another workspace's runs are never seen.
 */
public interface RunHistory {

    /** The columns of every row, in order. */
    List<String> COLUMNS = Collections.unmodifiableList(Arrays.asList("run_id", "job_id", "pipeline_id",
        "attempt", "status", "message", "input_key", "input_name", "run_date", "created_at", "started_at", "ended_at",
        "duration_seconds", "review_status", "review_decision", "reviewer_id", "reviewer_name", "review_comment",
        "review_reason", "review_decided_at", "review_wait_minutes"));

    /** Why runs cannot be read now; empty when they can. */
    default Optional<String> unavailable() {
        return Optional.empty();
    }

    /**
     * The pipeline's runs created at or after {@code since}, oldest first, at most {@code limit} (one more tells the
     * caller there were more); {@code finishedOnly} leaves out runs still queued or running. Each row has {@link #COLUMNS}.
     */
    List<Map<String, Object>> runs(long tenantId, String pipelineId, Instant since, boolean finishedOnly, int limit);
}

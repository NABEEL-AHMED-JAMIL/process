package process.pipeline.review;

import org.springframework.jdbc.core.JdbcTemplate;
import process.util.SqlTimes;
import org.springframework.stereotype.Component;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.SourceJobRepository;
import process.pipeline.backing.RunHistory;
import process.util.BusinessTime;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@link RunHistory} on process's own tables, through the application's pool: the step engine runs as the run's
 * workspace, so row-level security keeps every statement to it (and the queries name the tenant as well).
 *
 * <p>A run's review status is {@link RunReviews#statuses}' -- the rule every run read uses (the version the run follows
 * decides whether it needs a review; a run that ended without results has none to give) -- and its decision columns are
 * the last decision recorded on it (with two parties, the one that settled it). Times are the platform's wall clock
 * (America/Chicago) as {@code yyyy-MM-dd HH:mm:ss}; {@code duration_seconds} is the run's own (start to end) and
 * {@code review_wait_minutes} from its end to the decision.
 */
@Component
public class PipelineRunHistory implements RunHistory {

    /** Runs read per review lookup (each an IN list). */
    static final int CHUNK = 500;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT);

    private final JdbcTemplate jdbc;
    private final SourceJobRepository jobs;
    private final RunReviews reviews;
    private final RunReviewStore store;

    public PipelineRunHistory(JdbcTemplate jdbc, SourceJobRepository jobs, RunReviews reviews, RunReviewStore store) {
        this.jdbc = jdbc;
        this.jobs = jobs;
        this.reviews = reviews;
        this.store = store;
    }

    @Override
    public List<Map<String, Object>> runs(long tenantId, String pipelineId, Instant since, boolean finishedOnly, int limit) {
        List<JobQueue> runs = new ArrayList<>();
        Map<Long, Map<String, Object>> facts = new LinkedHashMap<>();
        String inFlight = finishedOnly ? "AND q.job_status NOT IN ('Queue', 'Start', 'Running') " : "";
        // input_name: the file's name as it arrived (an inbox arrival's), else the key's last part.
        this.jdbc.query("SELECT q.job_queue_id, q.job_id, q.attempt, q.job_status, q.job_status_message, q.input_key, q.date_created, "
            + "q.start_time, q.end_time, a.file_name FROM job_queue q "
            + "JOIN source_job j ON j.job_id = q.job_id AND j.tenant_id = q.tenant_id "
            + "JOIN source_task t ON t.task_detail_id = j.task_detail_id "
            + "LEFT JOIN LATERAL (SELECT x.file_name FROM inbox_arrival x WHERE x.job_queue_id = q.job_queue_id "
            + "ORDER BY x.inbox_arrival_id LIMIT 1) a ON true "
            + "WHERE q.tenant_id = ? AND t.pipeline_id = ? AND j.job_status <> 'Delete' AND q.date_created >= ? " + inFlight
            + "ORDER BY q.job_queue_id LIMIT ?", rs -> {
                JobQueue run = new JobQueue();
                run.setJobQueueId(rs.getLong("job_queue_id"));
                run.setJobId(rs.getLong("job_id"));
                run.setJobStatus(status(rs.getString("job_status")));
                run.setDateCreated(rs.getTimestamp("date_created"));
                runs.add(run);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("attempt", rs.getInt("attempt"));
                row.put("status", rs.getString("job_status"));
                row.put("message", rs.getString("job_status_message"));
                row.put("input_key", rs.getString("input_key"));
                row.put("file_name", rs.getString("file_name"));
                row.put("created", rs.getTimestamp("date_created"));
                row.put("started", rs.getTimestamp("start_time"));
                row.put("ended", rs.getTimestamp("end_time"));
                facts.put(run.getJobQueueId(), row);
            }, tenantId, pipelineId, Timestamp.from(since), limit);
        if (runs.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> jobIds = new LinkedHashSet<>();
        runs.forEach(run -> jobIds.add(run.getJobId()));
        Map<Long, SourceJob> jobsById = new HashMap<>();
        for (SourceJob job : this.jobs.findAllById(jobIds)) {
            jobsById.put(job.getJobId(), job);
        }
        Map<Long, RunReviewStatus> statuses = new HashMap<>();
        Map<Long, List<RunReviewStore.Decision>> decisions = new HashMap<>();
        for (int from = 0; from < runs.size(); from += CHUNK) {
            List<JobQueue> chunk = runs.subList(from, Math.min(runs.size(), from + CHUNK));
            statuses.putAll(this.reviews.statuses(chunk, jobsById));
            List<Long> ids = new ArrayList<>(chunk.size());
            chunk.forEach(run -> ids.add(run.getJobQueueId()));
            decisions.putAll(this.store.decisionsOf(ids));
        }
        List<Map<String, Object>> out = new ArrayList<>(runs.size());
        for (JobQueue run : runs) {
            Map<String, Object> fact = facts.get(run.getJobQueueId());
            List<RunReviewStore.Decision> made = decisions.getOrDefault(run.getJobQueueId(), Collections.emptyList());
            RunReviewStore.Decision last = made.isEmpty() ? null : made.get(made.size() - 1);   // oldest first
            RunReviewStatus review = statuses.get(run.getJobQueueId());
            Timestamp started = SqlTimes.timestamp(fact.get("started"));
            Timestamp ended = SqlTimes.timestamp(fact.get("ended"));
            String key = (String) fact.get("input_key");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("run_id", run.getJobQueueId());
            row.put("job_id", run.getJobId());
            row.put("pipeline_id", pipelineId);
            row.put("attempt", fact.get("attempt"));
            row.put("status", fact.get("status"));
            row.put("message", fact.get("message"));
            row.put("input_key", key);
            Object arrived = fact.get("file_name");
            row.put("input_name", arrived != null ? arrived : key == null ? null : key.substring(key.lastIndexOf('/') + 1));
            LocalDateTime created = BusinessTime.wallClockOf(SqlTimes.timestamp(fact.get("created")));
            row.put("run_date", created == null ? null : created.format(DAY));
            row.put("created_at", text(created));
            row.put("started_at", text(BusinessTime.wallClockOf(started)));
            row.put("ended_at", text(BusinessTime.wallClockOf(ended)));
            row.put("duration_seconds", started == null || ended == null ? null
                : Math.round(Duration.between(started.toInstant(), ended.toInstant()).toMillis() / 100.0) / 10.0);
            row.put("review_status", review == null ? "none" : word(review));
            row.put("review_decision", last == null || last.decision == null ? null : last.decision.name().toLowerCase(Locale.ROOT));
            row.put("reviewer_id", last == null ? null : last.reviewerUserId);
            row.put("reviewer_name", last == null ? null : last.reviewerName);
            row.put("review_comment", last == null ? null : last.comment);
            row.put("review_reason", last == null ? null : last.reason);
            row.put("review_decided_at", last == null ? null : text(BusinessTime.wallClockOf(last.decidedAt)));
            row.put("review_wait_minutes", last == null || last.decidedAt == null || ended == null ? null
                : Math.round(Duration.between(ended.toInstant(), last.decidedAt).getSeconds() / 6.0) / 10.0);
            out.add(row);
        }
        return out;
    }

    /** none, pending, approved, rejected, not_required or not_applicable: the review status in the words rows use. */
    static String word(RunReviewStatus status) {
        return status.name().toLowerCase(Locale.ROOT);
    }

    private static String text(LocalDateTime time) {
        return time == null ? null : time.format(TIME);
    }

    private static JobStatus status(String text) {
        for (JobStatus status : JobStatus.values()) {
            if (status.name().equalsIgnoreCase(text)) {
                return status;
            }
        }
        return null;
    }
}

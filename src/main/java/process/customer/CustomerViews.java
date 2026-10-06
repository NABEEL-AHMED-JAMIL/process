package process.customer;

import org.barco.platform.api.ApiTimes;
import process.pipeline.StepStore;
import process.pipeline.data.FileFormats;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The customer API's shapes for what Core holds (MIG-334; the OpenAPI document's Run, Step, Review and File): ids as
 * strings, times RFC 3339 in UTC, words in lower case, and never a bucket, a storage key or a person's id.
 */
final class CustomerViews {

    /** A status line is the platform's sentence; the API shows at most this much of it. */
    static final int MAX_MESSAGE = 500;

    private CustomerViews() {
    }

    /** The Run schema. {@code review} is the ReviewStatus word. */
    static Map<String, Object> run(CustomerRunStore.Row row, String review) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("id", String.valueOf(row.runId));
        run.put("pipelineId", String.valueOf(row.jobId));
        run.put("status", RunIntake.statusOf(row.jobStatus));
        run.put("reference", row.reference);
        run.put("createdAt", ApiTimes.utc(row.createdAt));
        run.put("startedAt", ApiTimes.utc(row.startedAt));
        run.put("endedAt", ApiTimes.utc(row.endedAt));
        run.put("message", cut(row.message));
        run.put("attempt", row.attempt);
        run.put("review", review);
        return run;
    }

    /** The API's word for a step's status (step_execution.status): pending, running, completed, failed, skipped, interrupted. */
    static String stepStatusOf(String status) {
        if (status == null) {
            return "pending";
        }
        switch (status) {
            case "Running":
                return "running";
            case "Completed":
                return "completed";
            case "Failed":
                return "failed";
            case "Skip":
            case "Missed":
                return "skipped";
            case "Interrupt":
                return "interrupted";
            default:
                return "pending";
        }
    }

    /** The ReviewStatus word for RunReviewStatus's name: not_required, pending, approved, rejected. */
    static String reviewWordOf(Object status) {
        return status == null ? "not_required" : status.toString().toLowerCase(Locale.ROOT);
    }

    /** The Review schema from RunReviews.summary: status, required, decisions (party, decision, reason, comment, time). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> review(Map<String, Object> summary) {
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("status", reviewWordOf(summary.get("reviewStatus")));
        review.put("required", summary.get("required"));
        review.put("decidedAt", ApiTimes.utc(summary.get("decidedAt")));
        Object rerun = summary.get("rerunJobQueueId");
        review.put("rerunRunId", rerun == null ? null : String.valueOf(rerun));
        List<Map<String, Object>> decisions = new ArrayList<>();
        Object listed = summary.get("decisions");
        if (listed instanceof List) {
            for (Map<String, Object> d : (List<Map<String, Object>>) listed) {
                Map<String, Object> decision = new LinkedHashMap<>();
                decision.put("party", d.get("party"));
                decision.put("decision", d.get("decision") == null ? null : d.get("decision").toString().toLowerCase(Locale.ROOT));
                decision.put("reason", d.get("reason"));
                decision.put("comment", d.get("comment"));
                decision.put("decidedAt", ApiTimes.utc(d.get("decidedAt")));
                decisions.add(decision);
            }
        }
        review.put("decisions", decisions);
        return review;
    }

    /** The File schema (with the manifest's step, rows and expired) for a file a run made: role report for a PDF, else result. */
    static Map<String, Object> madeFile(StepStore.OutputRow output, String fileId, Instant now) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("id", fileId);
        file.put("name", output.name);
        file.put("role", "pdf".equals(output.format) ? "report" : "result");
        file.put("contentType", FileFormats.contentType(output.format));
        file.put("bytes", output.byteCount == null ? 0L : output.byteCount);
        file.put("sha256", output.sha256);
        file.put("createdAt", ApiTimes.utc(output.recordedAt));
        file.put("expiresAt", ApiTimes.utc(output.expiresAt));
        file.put("step", output.stepKey);
        file.put("rows", output.rowCount);
        file.put("expired", expired(output, now));
        return file;
    }

    /** The File schema for a file the workspace uploaded (POST /v1/files), as storage-service's directory answers it. */
    static Map<String, Object> uploadedFile(Map<String, Object> found) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("id", found.get("id"));
        file.put("name", found.get("name"));
        Object role = found.get("role");
        file.put("role", role == null ? "input" : role.toString().toLowerCase(Locale.ROOT));
        file.put("contentType", found.get("contentType"));
        file.put("bytes", found.get("bytes"));
        file.put("sha256", found.get("sha256"));
        file.put("createdAt", found.get("createdAt"));
        file.put("expiresAt", found.get("expiresAt"));
        file.put("step", null);
        file.put("rows", null);
        file.put("expired", false);
        return file;
    }

    /** A made file kept with the run (Save File, a report) is gone once its expiry has passed; an upload to a bucket is not. */
    static boolean expired(StepStore.OutputRow output, Instant now) {
        return output.expiresAt != null && !output.expiresAt.isAfter(now);
    }

    static String cut(String text) {
        if (text == null || text.length() <= MAX_MESSAGE) {
            return text;
        }
        return text.substring(0, MAX_MESSAGE - 3) + "...";
    }
}

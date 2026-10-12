package process.pipeline.approval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.platform.event.PlatformEvent;
import org.springframework.stereotype.Component;
import process.outbox.OutboxWriter;
import process.pipeline.review.RunReviewEvents;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MIG-346: an Approval step's request as events for the Task inbox, on the run-review topic ({@value RunReviewEvents#TOPIC},
 * keyed by the run, so a request is read before its close), beside MIG-361's run reviews. workflow-service's
 * RunReviewTasks starts a request of its built-in {@value #DEFINITION_KEY} workflow for each, with the subject
 * {@code pipeline-approval:<run_approval_id>}:
 *
 * <ul>
 *   <li>{@value #REQUESTED}: {approvalId, jobQueueId, jobId, jobName, pipelineId, attempt, stepKey, stepIndex, title,
 *       instructions, fields, rowCount, requestedAt, expiresAt, link, reviewers {kind, value}} -- the run's facts for the
 *       task, never a bucket, a key or a row's values beyond the fields its author chose;</li>
 *   <li>{@value #CLOSED}: {approvalId, jobQueueId, status} -- the request was settled another way (expired, or its run was
 *       closed): its live task is cancelled.</li>
 * </ul>
 * Written to the outbox in the caller's transaction, so an event goes out only with the row it announces.
 */
@Component
public class RunApprovalEvents {

    public static final String REQUESTED = "run-approval.requested";
    public static final String CLOSED = "run-approval.closed";
    /** workflow-service's built-in workflow for an Approval step (RunReviewTasks.APPROVAL_KEY there). */
    public static final String DEFINITION_KEY = "pipeline-step-approval";
    public static final String SUBJECT_PREFIX = "pipeline-approval:";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OutboxWriter outbox;

    public RunApprovalEvents(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    /** The facts of one request, as the task shows them. */
    public static final class Requested {
        public long approvalId;
        public long jobQueueId;
        public Long jobId;
        public String jobName;
        public String pipelineId;
        public int attempt;
        public String stepKey;
        public int stepIndex;
        public String title;
        public String instructions;
        public Map<String, String> fields;
        public long rowCount;
        public Instant requestedAt;
        public Instant expiresAt;
        public String reviewersKind;
        public String reviewersValue;
    }

    public void requested(long tenantId, Requested r) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalId", r.approvalId);
        payload.put("jobQueueId", r.jobQueueId);
        payload.put("jobId", r.jobId);
        payload.put("jobName", r.jobName);
        payload.put("pipelineId", r.pipelineId);
        payload.put("attempt", r.attempt);
        payload.put("stepKey", r.stepKey);
        payload.put("stepIndex", r.stepIndex);
        payload.put("title", r.title);
        payload.put("instructions", r.instructions);
        payload.put("fields", r.fields);
        payload.put("rowCount", r.rowCount);
        payload.put("requestedAt", r.requestedAt.toString());
        payload.put("expiresAt", r.expiresAt.toString());
        payload.put("link", r.jobId == null ? null : String.format("/pipelines/schedules/%d/runs/%d/logs", r.jobId, r.jobQueueId));
        Map<String, Object> who = new LinkedHashMap<>();
        who.put("kind", r.reviewersKind);
        who.put("value", r.reviewersValue);
        payload.put("reviewers", who);
        this.write(REQUESTED, tenantId, r.jobQueueId, payload);
    }

    public void closed(long tenantId, long approvalId, long jobQueueId, String status) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalId", approvalId);
        payload.put("jobQueueId", jobQueueId);
        payload.put("status", status);
        this.write(CLOSED, tenantId, jobQueueId, payload);
    }

    private void write(String type, long tenantId, long jobQueueId, Map<String, Object> payload) {
        PlatformEvent<Object> event = PlatformEvent.at(Instant.now(), type, tenantId, "process", payload);
        event.setEventId(UUID.randomUUID().toString());
        try {
            this.outbox.write(RunReviewEvents.TOPIC, String.valueOf(jobQueueId), event.getEventId(), JSON.writeValueAsString(event));
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException("A " + type + " event could not be written", unwritable);
        }
    }
}

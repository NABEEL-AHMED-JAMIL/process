package process.pipeline.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import process.model.dto.ResponseDto;
import process.model.enums.ReviewDecision;
import process.util.UserNameResolver;

import java.io.IOException;
import java.util.Collections;
import process.pipeline.approval.RunApprovals;
import process.pipeline.approval.RunApprovalEvents;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * MIG-361: a run's internal review decided in the Task inbox. workflow-service announces every request's change on
 * {@value #INSTANCE_CHANGED}; a request of its built-in {@value #DEFINITION_KEY} workflow about a run ("pipeline-run:N",
 * a subject only a service may name) that ended Approved or Rejected carries the decision -- the task, who acted, their
 * comment -- and is recorded as that reviewer's internal decision ({@link RunReviewService#decideFromInbox}). Any other
 * request is not this listener's and is passed over (FormWorkflowListener reads the same topic for form approvals).
 *
 * Nobody is signed in on a listener's thread: the work runs as the event's workspace (RowSecurity.forTenant). A
 * redelivered decision finds the internal review decided and records nothing more; an unreadable event is logged and
 * skipped.
 */
@Component
public class RunReviewTaskListener {

    /** workflow-service's topic (WorkflowTopics.INSTANCE_CHANGED there). */
    static final String INSTANCE_CHANGED = "platform.workflow.instance-changed.v1";
    /** workflow-service's built-in workflow for a run's review (RunReviewTasks.DEFINITION_KEY there). */
    static final String DEFINITION_KEY = "pipeline-run-review";
    static final String SUBJECT_PREFIX = "pipeline-run:";

    private static final Logger logger = LoggerFactory.getLogger(RunReviewTaskListener.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RunReviewService reviews;
    private final UserNameResolver names;
    private final TransactionTemplate transactions;

    public RunReviewTaskListener(RunReviewService reviews, UserNameResolver names, PlatformTransactionManager transactionManager) {
        this.reviews = reviews;
        this.names = names;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** MIG-346: an Approval step's request decided in the Task inbox moves its run on; absent in hand-built tests. */
    private RunApprovals approvals;

    @Autowired(required = false)
    public void setApprovals(RunApprovals approvals) {
        this.approvals = approvals;
    }

    @KafkaListener(id = "run-review-tasks", topics = INSTANCE_CHANGED, groupId = "process-run-review-tasks",
        autoStartup = "${review.tasks.listen:true}", properties = {"auto.offset.reset=earliest"})
    public void onChange(String message) {
        JsonNode change;
        try {
            change = JSON.readTree(message).path("payload");
        } catch (IOException unreadable) {
            logger.warn("Skipped an unreadable {} event: {}", INSTANCE_CHANGED, unreadable.getMessage());
            return;
        }
        if (RunApprovalEvents.DEFINITION_KEY.equals(change.path("definitionKey").asText(null))) {
            this.onApproval(change);
            return;
        }
        Long run = runOf(change.path("subjectId").asText(null));
        if (run == null || !DEFINITION_KEY.equals(change.path("definitionKey").asText(null))) {
            return;
        }
        String state = change.path("state").asText("");
        ReviewDecision decision = "Approved".equals(state) ? ReviewDecision.APPROVED : "Rejected".equals(state) ? ReviewDecision.REJECTED : null;
        long tenantId = change.path("tenantId").asLong(0);
        long instanceId = change.path("instanceId").asLong(0);
        JsonNode decided = change.path("decision");
        if (decision == null || tenantId <= 0 || instanceId <= 0 || !decided.isObject()) {
            return;
        }
        Long reviewer = decided.path("actedBy").isIntegralNumber() ? decided.get("actedBy").asLong() : null;
        String comment = decided.path("comment").isTextual() ? decided.get("comment").asText() : null;
        long taskId = decided.path("taskId").asLong(0);
        String bulkId = bulkIdOf(decided);
        String name = reviewer == null ? null : this.names.namesFor(Collections.singleton(reviewer)).get(reviewer);
        // The decision and its audit line commit inside the workspace's scope: row security checks them as that workspace.
        ResponseDto recorded = RowSecurity.forTenant(tenantId, () -> this.transactions.execute(status -> this.reviews.decideFromInbox(tenantId,
            run, decision, reviewer, name, comment, instanceId, taskId, bulkId)));
        logger.info("Run {} of workspace {}: the Task inbox's {} (request {}, task {}, by {}{}): {}", run, tenantId, decision, instanceId,
            taskId, reviewer, bulkId == null ? "" : ", bulk " + bulkId, recorded.getMessage());
    }

    /**
     * MIG-346: a request of the built-in pipeline-step-approval workflow ("pipeline-approval:N", N the run_approval) that
     * ended Approved or Rejected: the run waiting at that Approval step continues, or fails with the reviewer's comment.
     */
    private void onApproval(JsonNode change) {
        String subject = change.path("subjectId").asText("");
        String state = change.path("state").asText("");
        long tenantId = change.path("tenantId").asLong(0);
        long instanceId = change.path("instanceId").asLong(0);
        JsonNode decided = change.path("decision");
        if (this.approvals == null || !subject.startsWith(RunApprovalEvents.SUBJECT_PREFIX)
            || !("Approved".equals(state) || "Rejected".equals(state)) || tenantId <= 0 || !decided.isObject()) {
            return;
        }
        long approvalId;
        try {
            approvalId = Long.parseLong(subject.substring(RunApprovalEvents.SUBJECT_PREFIX.length()));
        } catch (NumberFormatException notOne) {
            return;
        }
        Long reviewer = decided.path("actedBy").isIntegralNumber() ? decided.get("actedBy").asLong() : null;
        String comment = decided.path("comment").isTextual() ? decided.get("comment").asText() : null;
        long taskId = decided.path("taskId").asLong(0);
        String bulkId = bulkIdOf(decided);
        String name = reviewer == null ? null : this.names.namesFor(Collections.singleton(reviewer)).get(reviewer);
        RunApprovals.Outcome outcome = this.approvals.decideFromInbox(tenantId, approvalId, "Approved".equals(state),
            reviewer, name, comment, instanceId, taskId, bulkId);
        logger.info("Approval request {} of workspace {}: the Task inbox's {} (request {}, task {}, by {}): {}", approvalId, tenantId, state,
            instanceId, taskId, reviewer, outcome);
    }

    /**
     * Bulk review: the bulk decision the task was decided in -- the decision's bulkId, absent or null for a single decision.
     * One that is not a bulk id's shape (^[A-Za-z0-9-]{8,64}$) is not recorded.
     */
    static String bulkIdOf(JsonNode decided) {
        String bulkId = decided.path("bulkId").isTextual() ? decided.get("bulkId").asText() : null;
        if (bulkId != null && !RunReviewBulk.BULK_ID.matcher(bulkId).matches()) {
            logger.warn("Skipped a malformed bulkId on a {} event's decision; recorded as a single decision.", INSTANCE_CHANGED);
            return null;
        }
        return bulkId;
    }

    /** The run a request is about, or null for one that is not a run's review. */
    static Long runOf(String subjectId) {
        if (subjectId == null || !subjectId.startsWith(SUBJECT_PREFIX)) {
            return null;
        }
        try {
            return Long.valueOf(subjectId.substring(SUBJECT_PREFIX.length()));
        } catch (NumberFormatException notOne) {
            return null;
        }
    }
}

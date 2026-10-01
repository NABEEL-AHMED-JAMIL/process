package process.forms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Core's consumer of workflow-service's instance changes (MIG-279): a request that is a form submission's
 * ("form-submission:N") sets that submission's status -- Pending, Overdue, Approved, Rejected, Completed, Cancelled,
 * Failed -- and rewrites its dataset row. Any other request is not Core's and is passed over.
 *
 * Nobody is signed in on a listener's thread: the work runs as the event's workspace (RowSecurity.forTenant). An
 * unreadable event is logged and skipped; the events of one request arrive in order (keyed by it), and setting a status
 * twice is the same as once, so a redelivery is harmless.
 */
@Component
public class FormWorkflowListener {

    /** workflow-service's topic (WorkflowTopics.INSTANCE_CHANGED there). */
    static final String INSTANCE_CHANGED = "platform.workflow.instance-changed.v1";

    private static final Logger logger = LoggerFactory.getLogger(FormWorkflowListener.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final FormSubmissionService submissions;

    public FormWorkflowListener(FormSubmissionService submissions) {
        this.submissions = submissions;
    }

    @KafkaListener(id = "form-workflows", topics = INSTANCE_CHANGED, groupId = "process-form-workflows",
        autoStartup = "${forms.workflow.listen:true}", properties = {"auto.offset.reset=earliest"})
    public void onChange(String message) {
        JsonNode change;
        try {
            change = JSON.readTree(message).path("payload");
        } catch (IOException unreadable) {
            logger.warn("Skipped an unreadable {} event: {}", INSTANCE_CHANGED, unreadable.getMessage());
            return;
        }
        Long submissionId = FormWorkflows.submissionOf(change.path("subjectId").asText(null));
        long tenantId = change.path("tenantId").asLong(0);
        long instanceId = change.path("instanceId").asLong(0);
        if (submissionId == null || tenantId <= 0 || instanceId <= 0) {
            return;
        }
        String state = change.path("state").asText(null);
        boolean overdue = change.path("overdue").asBoolean(false);
        boolean followed = RowSecurity.forTenant(tenantId, () -> this.submissions.followWorkflow(tenantId, submissionId, instanceId, state,
            overdue));
        logger.info("Request {} ({}{}) {} submission {} in workspace {}.", instanceId, state, overdue ? ", overdue" : "",
            followed ? "updated" : "did not match", submissionId, tenantId);
    }
}

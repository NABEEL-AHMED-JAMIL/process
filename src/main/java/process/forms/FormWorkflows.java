package process.forms;

import java.util.Map;
import java.util.Optional;

/**
 * Workflows (workflow-service) as a form uses them (MIG-279): a form names one, checked when the form is saved, and each
 * submission starts a request of it -- the submission is its subject ("form-submission:N"). How the request goes comes
 * back as workflow-service's instance-changed events (FormWorkflowListener).
 */
public interface FormWorkflows {

    /** A workflow as the builder names it. */
    final class Workflow {
        public final String key;
        public final String name;
        public final String status;
        public final int currentVersion;

        public Workflow(String key, String name, String status, int currentVersion) {
            this.key = key;
            this.name = name;
            this.status = status;
            this.currentVersion = currentVersion;
        }
    }

    /** A request it started. */
    final class Started {
        public final long instanceId;
        public final String state;

        public Started(long instanceId, String state) {
            this.instanceId = instanceId;
            this.state = state;
        }
    }

    /** The workflow by its key in the workspace; empty when there is none. An exception when the service cannot say. */
    Optional<Workflow> find(long tenantId, String key);

    /** Starts a request of the workflow for a submission; the same submission twice starts one. */
    Started start(long tenantId, String key, long submissionId, String title, Map<String, Object> subject, Long requestedBy);

    /**
     * MIG-332: starts a request of the workflow for another subject -- an event the customer API received
     * ("api-event:N") -- once per eventId. Only workflow-service's own client can.
     */
    default Started startFor(long tenantId, String key, String subjectId, String eventId, String title, Map<String, Object> subject) {
        throw new IllegalStateException("Workflows cannot be started here.");
    }

    /** The subject id a submission's request carries. */
    static String subjectOf(long submissionId) {
        return "form-submission:" + submissionId;
    }

    /** The submission a request is about, or null for one that is not a form submission's. */
    static Long submissionOf(String subjectId) {
        if (subjectId == null || !subjectId.startsWith("form-submission:")) {
            return null;
        }
        try {
            return Long.valueOf(subjectId.substring("form-submission:".length()));
        } catch (NumberFormatException notOne) {
            return null;
        }
    }
}

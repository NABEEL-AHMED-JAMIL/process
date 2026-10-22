package process.pipeline.items;

import java.util.Map;
import java.util.Optional;

/**
 * MIG-421: a run's items sent to people to check, as a Task inbox task -- "Send to review" on the Items tab, and an OCR step's
 * pages it was not sure enough of ("Check by hand"). Through workflow-service's internal door: its built-in
 * {@value #DEFINITION_KEY} workflow (made the first time a workspace needs it; a workspace that edited it keeps its own, one
 * that switched it off gets no task), whose one step goes to the reviewers the request names -- a team (by id) or a role.
 * The task names the items and links to the run's Items tab showing exactly them.
 */
public interface ItemReviews {

    String DEFINITION_KEY = "pipeline-items-review";

    /** What one request asks: who, about what, under which key (the same key starts one request, however often sent). */
    final class ReviewRequest {
        public long tenantId;
        public String subjectId;
        public String eventId;
        public String title;
        /** What the task shows: the run, its schedule, the items (ref, label, why), the link; never a value of the items' data. */
        public Map<String, Object> subject;
        /** "team" (value: the team's id) or "role" (TENANT_ADMIN, TENANT_USER). */
        public String reviewersKind;
        public String reviewersValue;
    }

    /** The request started: workflow-service's instance id and state; or why there is none (the workflow is switched off). */
    final class Started {
        public final long instanceId;
        public final String state;

        public Started(long instanceId, String state) {
            this.instanceId = instanceId;
            this.state = state;
        }
    }

    /** Why Task inbox tasks cannot be made from here (no workflow-service); empty when they can. */
    Optional<String> unavailable();

    /** Starts the request; an exception says why it was not (workflow-service refused, could not be reached, the workflow is off). */
    Started send(ReviewRequest request);
}

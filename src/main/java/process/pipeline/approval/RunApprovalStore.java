package process.pipeline.approval;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** MIG-346: the Approval step's requests (run_approval, V206), under row security as the caller. */
public interface RunApprovalStore {

    String PENDING = "PENDING";
    String APPROVED = "APPROVED";
    String REJECTED = "REJECTED";
    String EXPIRED = "EXPIRED";
    String CANCELLED = "CANCELLED";

    /** One request. */
    final class Approval {
        public long runApprovalId;
        public long tenantId;
        public long jobQueueId;
        public int attempt;
        public long stepExecutionId;
        public int stepIndex;
        public String stepKey;
        public String status;
        public String title;
        public String reviewersKind;
        public String reviewersValue;
        public Long recordsIn;
        public Instant requestedAt;
        public Instant expiresAt;
        public Instant decidedAt;
        public Long decidedByUserId;
        public String decidedByName;
        public String comment;
        public Long workflowInstanceId;
        public Long workflowTaskId;
        /** Bulk review (V209): the bulk decision in the Task inbox this request was decided in; null otherwise. */
        public String bulkId;

        public boolean pending() {
            return PENDING.equals(this.status);
        }

        /** Who decided, as a line says it: "Ana Ruiz (user 4641)", "the platform" for an expiry or a cancel. */
        public String decider() {
            if (this.decidedByName == null && this.decidedByUserId == null) {
                return "the platform";
            }
            String name = this.decidedByName == null ? "an unnamed reviewer" : this.decidedByName;
            return this.decidedByUserId == null ? name : String.format("%s (user %d)", name, this.decidedByUserId);
        }
    }

    /** A new PENDING request; its id. */
    long insert(Approval approval);

    Optional<Approval> find(long runApprovalId);

    /**
     * Decides a PENDING request -- once: the row as decided, or empty when it was not PENDING any more (decided another
     * way first, or never there). {@code bulkId}: the bulk decision it was part of (bulk review), else null.
     */
    Optional<Approval> decide(long runApprovalId, String status, Long userId, String name, String comment, Long instanceId, Long taskId,
                              Instant at, String bulkId);

    /** PENDING requests past their expiry, oldest first (the caller reads across workspaces). */
    List<Approval> expired(Instant now, int limit);

    /** PENDING requests whose run is no longer waiting on them: closed, or on another attempt (the caller reads across workspaces). */
    List<Approval> orphaned(int limit);

    /** A run's requests, by step execution, for its timeline. */
    Map<Long, Approval> ofSteps(Collection<Long> stepExecutionIds);
}

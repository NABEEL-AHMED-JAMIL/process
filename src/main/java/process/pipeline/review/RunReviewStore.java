package process.pipeline.review;

import process.model.enums.ReviewDecision;
import process.model.enums.ReviewParty;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Where a run's review is kept (V189, MIG-237): run_review_decision, one decision per party per run and never changed,
 * and run_review, the run's status as last decided. Reads and writes are the caller's workspace's (row-level security).
 */
public interface RunReviewStore {

    /** One party's decision on a run. */
    final class Decision {
        public Long runReviewDecisionId;
        public long jobQueueId;
        public int attempt;
        public ReviewParty party;
        public ReviewDecision decision;
        public String comment;
        public String reason;
        public Long reviewerUserId;
        public String reviewerName;
        public Instant decidedAt;
    }

    /** A run's review status as last decided, the parties it was decided against, and the run a rejection queued. */
    final class Status {
        public long jobQueueId;
        public Set<ReviewParty> required;
        public RunReviewStatus status;
        public Instant decidedAt;
        public Long rerunJobQueueId;
    }

    /** The run's decisions, oldest first. */
    List<Decision> decisionsOf(long jobQueueId);

    /** The run's status row: absent until a party first decides on it. */
    Optional<Status> statusOf(long jobQueueId);

    /**
     * The run's status row, added PENDING with these parties when it has none, and held for this transaction: two
     * parties deciding at once each see the other's decision. {@code required} is not empty.
     */
    Status lockPending(long jobQueueId, Set<ReviewParty> required);

    /** Adds a decision; a second decision by the same party on the same run is the database's DuplicateKeyException. */
    long record(Decision decision);

    /** Moves the run's status on; {@code decidedAt} once it is APPROVED or REJECTED. */
    void settle(long jobQueueId, RunReviewStatus status, Instant decidedAt);

    /** The run a rejection queued again. */
    void linkRerun(long jobQueueId, long rerunJobQueueId);

    /** The run's results (result_record) still PENDING take the run's decision; how many did. */
    int settleResults(long jobQueueId, RunReviewStatus status);
}

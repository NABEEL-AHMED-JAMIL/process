package process.pipeline.review;

import process.model.enums.ReviewDecision;
import process.model.enums.ReviewParty;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     * MIG-326: {@link #statusOf} for a page of runs, by run id (a run with no row is left out). The default asks one run at a
     * time; the JDBC store answers in one query.
     */
    default Map<Long, Status> statusesOf(Collection<Long> jobQueueIds) {
        Map<Long, Status> found = new HashMap<>();
        for (Long id : jobQueueIds) {
            this.statusOf(id).ifPresent(status -> found.put(id, status));
        }
        return found;
    }

    /** MIG-326: {@link #decisionsOf(long)} for a page of runs, by run id, each oldest first (a run with none is left out). */
    default Map<Long, List<Decision>> decisionsOf(Collection<Long> jobQueueIds) {
        Map<Long, List<Decision>> found = new HashMap<>();
        for (Long id : jobQueueIds) {
            List<Decision> decisions = this.decisionsOf(id);
            if (!decisions.isEmpty()) {
                found.put(id, decisions);
            }
        }
        return found;
    }

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

    /**
     * MIG-325: the workspace's completed runs, newest first, of jobs whose pipeline has a review setting in some version,
     * that no party has settled yet -- the candidates for "waiting for review". The caller still applies the run's own
     * rule (RunReviews.summary), since the version a run follows decides.
     */
    List<Long> undecidedReviewedRuns(long tenantId, int limit);
}

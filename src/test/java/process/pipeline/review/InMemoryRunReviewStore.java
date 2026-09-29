package process.pipeline.review;

import org.springframework.dao.DuplicateKeyException;
import process.model.enums.ReviewParty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** {@link RunReviewStore} in memory, with the table's one-decision-per-party rule, for unit tests. */
public class InMemoryRunReviewStore implements RunReviewStore {

    public final List<Decision> decisions = new ArrayList<>();
    public final Map<Long, Status> statuses = new HashMap<>();
    final Map<Long, RunReviewStatus> settledResults = new HashMap<>();
    private long nextId = 1000;

    @Override
    public synchronized List<Decision> decisionsOf(long jobQueueId) {
        return this.decisions.stream().filter(d -> d.jobQueueId == jobQueueId).collect(Collectors.toList());
    }

    @Override
    public synchronized Optional<Status> statusOf(long jobQueueId) {
        return Optional.ofNullable(this.statuses.get(jobQueueId));
    }

    @Override
    public synchronized Status lockPending(long jobQueueId, Set<ReviewParty> required) {
        return this.statuses.computeIfAbsent(jobQueueId, id -> {
            Status status = new Status();
            status.jobQueueId = id;
            status.required = EnumSet.copyOf(required);
            status.status = RunReviewStatus.PENDING;
            return status;
        });
    }

    @Override
    public synchronized long record(Decision decision) {
        if (this.decisions.stream().anyMatch(d -> d.jobQueueId == decision.jobQueueId && d.party == decision.party)) {
            throw new DuplicateKeyException("ux_run_review_decision_run_party");
        }
        decision.runReviewDecisionId = this.nextId++;
        this.decisions.add(decision);
        return decision.runReviewDecisionId;
    }

    @Override
    public synchronized void settle(long jobQueueId, RunReviewStatus status, Instant decidedAt) {
        this.statuses.get(jobQueueId).status = status;
        this.statuses.get(jobQueueId).decidedAt = decidedAt;
    }

    @Override
    public synchronized void linkRerun(long jobQueueId, long rerunJobQueueId) {
        this.statuses.get(jobQueueId).rerunJobQueueId = rerunJobQueueId;
    }

    @Override
    public synchronized int settleResults(long jobQueueId, RunReviewStatus status) {
        this.settledResults.put(jobQueueId, status);
        return 1;
    }
}

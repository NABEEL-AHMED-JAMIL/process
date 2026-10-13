package process.pipeline.review;

import java.time.Instant;
import java.util.Optional;

/** Bulk review: a workspace's review policy (review_policy, V209), under row security as the caller. */
public interface ReviewPolicyStore {

    /** A workspace's saved policy. */
    final class Saved {
        public long tenantId;
        public boolean bulkApproveRuns;
        public Long updatedBy;
        public Instant updatedAt;
    }

    /** The workspace's saved policy; empty when nobody has saved one (the defaults apply). */
    Optional<Saved> find(long tenantId);

    /** Saves the workspace's policy, as {@code by}. */
    void save(long tenantId, boolean bulkApproveRuns, Long by);
}

package process.pipeline.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.ResponseDto;
import process.security.TenantContext;
import process.util.BusinessTime;
import process.util.UserNameResolver;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Bulk review (2026-10-12): a workspace's review policy for pipeline runs -- "Allow bulk approve", on unless an administrator
 * turns it off. Off, a bulk approve skips every run ("bulk-off"); a bulk reject stays allowed, and a run is always approved
 * on its own. A pipeline that requires individual review (settings.review.individual) is never decided in bulk, whatever
 * this says.
 *
 * GET sourceJob.json/review/policy: any member of the workspace; POST: its tenant administrators, each save logged with who
 * changed it.
 */
@Service
public class ReviewPolicy {

    private static final Logger logger = LoggerFactory.getLogger(ReviewPolicy.class);
    static final String NO_WORKSPACE = "The review policy belongs to a workspace; this account is not attached to one.";

    private final ReviewPolicyStore store;
    /** Who saved it, by name; absent in hand-built tests. */
    private UserNameResolver names;

    public ReviewPolicy(ReviewPolicyStore store) {
        this.store = store;
    }

    @Autowired(required = false)
    public void setNames(UserNameResolver names) {
        this.names = names;
    }

    /** Whether the workspace allows bulk approve of its pipeline runs: its saved choice, else on. */
    public boolean bulkApproveRuns(long tenantId) {
        return this.store.find(tenantId).map(saved -> saved.bulkApproveRuns).orElse(true);
    }

    /** {bulkApproveRuns, saved, updatedBy, updatedByName, updatedAt} of the caller's workspace. */
    @Transactional(readOnly = true)
    public ResponseDto read() {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return new ResponseDto(ERROR, NO_WORKSPACE);
        }
        Optional<ReviewPolicyStore.Saved> saved = this.store.find(tenantId);
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("bulkApproveRuns", saved.map(s -> s.bulkApproveRuns).orElse(true));
        policy.put("saved", saved.isPresent());
        Long by = saved.map(s -> s.updatedBy).orElse(null);
        policy.put("updatedBy", by);
        policy.put("updatedByName", by == null || this.names == null ? null : this.nameOf(by));
        policy.put("updatedAt", saved.map(s -> s.updatedAt).map(BusinessTime::wallClockOf).orElse(null));
        return new ResponseDto(SUCCESS, Boolean.TRUE.equals(policy.get("bulkApproveRuns"))
            ? "Pipeline runs may be approved in bulk." : "Pipeline runs are approved one by one (bulk approve is off).", policy);
    }

    /** Saves the caller's workspace's choice; the caller is a tenant administrator (the endpoint's @PreAuthorize). */
    @Transactional
    public ResponseDto save(Boolean bulkApproveRuns) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return new ResponseDto(ERROR, NO_WORKSPACE);
        }
        if (bulkApproveRuns == null) {
            return new ResponseDto(ERROR, "bulkApproveRuns is true or false.");
        }
        boolean before = this.bulkApproveRuns(tenantId);
        this.store.save(tenantId, bulkApproveRuns, TenantContext.getAppUserId());
        logger.info("Review policy of workspace {}: bulk approve of pipeline runs {} (was {}), changed by {} (user {}).", tenantId,
            bulkApproveRuns ? "on" : "off", before ? "on" : "off", TenantContext.getUsername(), TenantContext.getAppUserId());
        return this.read();
    }

    private String nameOf(long userId) {
        try {
            return this.names.namesFor(Collections.singleton(userId)).get(userId);
        } catch (RuntimeException unavailable) {
            // A name is a courtesy: the id stays in the answer.
            return null;
        }
    }
}

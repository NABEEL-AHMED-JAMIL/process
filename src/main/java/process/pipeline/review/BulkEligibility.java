package process.pipeline.review;

import org.barco.platform.tenancy.RowSecurity;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.pipeline.RunOwnership;
import process.pipeline.approval.RunApprovalEvents;
import process.pipeline.approval.RunApprovalStore;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Bulk review (2026-10-12): may these Task inbox subjects be decided in bulk -- workflow-service's question before its
 * bulkAct decides a run's review task ("pipeline-run:N", the run's internal review) or an Approval step's task
 * ("pipeline-approval:N", the run_approval request; its run's pipeline decides). Core answers its two rules only, as the
 * subjects' workspace (RowSecurity.forTenant): the pipeline requires individual review ("individual-review", approve and
 * reject), and the workspace does not allow bulk approve of runs ("bulk-off", approve only). Unknown, malformed or another
 * workspace's: "not-found". Who may act on a task stays workflow-service's.
 */
@Component
public class BulkEligibility {

    public static final int MAX_SUBJECTS = 500;

    private final JobQueueRepository runs;
    private final SourceJobRepository jobs;
    private final RunReviews reviews;
    private final RunApprovalStore approvals;
    private final ReviewPolicy policy;
    private final TransactionTemplate reads;

    public BulkEligibility(JobQueueRepository runs, SourceJobRepository jobs, RunReviews reviews, RunApprovalStore approvals,
                           ReviewPolicy policy, PlatformTransactionManager transactionManager) {
        this.runs = runs;
        this.jobs = jobs;
        this.reviews = reviews;
        this.approvals = approvals;
        this.policy = policy;
        this.reads = new TransactionTemplate(transactionManager);
        this.reads.setReadOnly(true);
    }

    /** Each subject's answer: {allowed: true, group} or {allowed: false, reason, message}, in the order asked. */
    public Map<String, Map<String, Object>> check(long tenantId, boolean approve, Collection<String> subjects) {
        return RowSecurity.forTenant(tenantId, () -> this.reads.execute(status -> {
            boolean bulkApprove = !approve || this.policy.bulkApproveRuns(tenantId);
            Map<String, Map<String, Object>> answers = new LinkedHashMap<>();
            for (String subject : subjects) {
                if (subject != null && !answers.containsKey(subject)) {
                    answers.put(subject, this.one(tenantId, subject, approve, bulkApprove));
                }
            }
            return answers;
        }));
    }

    private Map<String, Object> one(long tenantId, String subject, boolean approve, boolean bulkApprove) {
        Optional<RunOwnership.Owned> owned = this.runOf(tenantId, subject);
        if (!owned.isPresent()) {
            return refused(RunReviewRules.Refusal.NOT_FOUND, "Not found.");
        }
        if (this.reviews.individual(owned.get().run, owned.get().job)) {
            return refused(RunReviewRules.Refusal.INDIVIDUAL_REVIEW, "Its pipeline requires individual review: decide it on its own.");
        }
        if (approve && !bulkApprove) {
            return refused(RunReviewRules.Refusal.BULK_OFF, "This workspace does not allow bulk approve of pipeline runs: approve it on its own.");
        }
        Map<String, Object> allowed = new LinkedHashMap<>();
        allowed.put("allowed", true);
        allowed.put("group", owned.get().job.getJobName() == null ? "Job " + owned.get().job.getJobId() : owned.get().job.getJobName());
        return allowed;
    }

    /** The run a subject is about, of this workspace: the run itself, or an Approval step request's run. */
    private Optional<RunOwnership.Owned> runOf(long tenantId, String subject) {
        Long run = RunReviewTaskListener.runOf(subject);
        if (run != null) {
            return RunOwnership.ofWorkspace(this.runs, this.jobs, run, tenantId);
        }
        if (!subject.startsWith(RunApprovalEvents.SUBJECT_PREFIX)) {
            return Optional.empty();
        }
        long approvalId;
        try {
            approvalId = Long.parseLong(subject.substring(RunApprovalEvents.SUBJECT_PREFIX.length()));
        } catch (NumberFormatException notOne) {
            return Optional.empty();
        }
        return this.approvals.find(approvalId).filter(a -> a.tenantId == tenantId)
            .flatMap(a -> RunOwnership.ofWorkspace(this.runs, this.jobs, a.jobQueueId, tenantId));
    }

    private static Map<String, Object> refused(String reason, String message) {
        Map<String, Object> refused = new LinkedHashMap<>();
        refused.put("allowed", false);
        refused.put("reason", reason);
        refused.put("message", message);
        return refused;
    }
}

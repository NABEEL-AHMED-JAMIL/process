package process.pipeline.approval;

import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import process.util.AuditText;
import process.pipeline.review.RunReviewService;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.model.service.impl.TransactionServiceImpl;
import process.pipeline.RunOwnership;
import process.pipeline.StepEngine;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * MIG-346: an Approval step's request, decided -- in the Task inbox (workflow-service's instance-changed event, read by
 * RunReviewTaskListener), or by time running out -- and the run moved on: approved, the engine continues it from the next
 * step; rejected or expired, it fails with the reason. Each request is decided once (run_approval's PENDING is the
 * claim), so a redelivered event, or the inbox and the expiry racing, move the run once.
 *
 * The sweep ({@link RunApprovalSweep}: every minute, one replica) expires requests past their time and cancels those whose run was closed
 * meanwhile (an operator failed or interrupted it, or it moved to another attempt), closing their inbox tasks. Off with the
 * other schedulers where process.scheduling.enabled is false.
 */
@Component
public class RunApprovals {

    static final int BATCH = 100;

    private static final Logger logger = LoggerFactory.getLogger(RunApprovals.class);

    private final RunApprovalStore store;
    private final RunApprovalEvents events;
    private final StepEngine engine;
    private final JobQueueRepository runs;
    private final SourceJobRepository jobs;
    private final TransactionServiceImpl audit;
    private final TransactionTemplate transactions;

    public RunApprovals(RunApprovalStore store, RunApprovalEvents events, StepEngine engine, JobQueueRepository runs, SourceJobRepository jobs,
                        TransactionServiceImpl audit, PlatformTransactionManager transactionManager) {
        this.store = store;
        this.events = events;
        this.engine = engine;
        this.runs = runs;
        this.jobs = jobs;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** What a decision came to, for the listener's log. */
    public static final class Outcome {
        public final boolean moved;
        public final String message;

        Outcome(boolean moved, String message) {
            this.moved = moved;
            this.message = message;
        }

        @Override
        public String toString() {
            return this.message;
        }
    }

    /** {@link #decideFromInbox(long, long, boolean, Long, String, String, long, long, String)} for a single decision (no bulkId). */
    public Outcome decideFromInbox(long tenantId, long approvalId, boolean approved, Long reviewerUserId, String reviewerName, String comment,
                                   long instanceId, long taskId) {
        return this.decideFromInbox(tenantId, approvalId, approved, reviewerUserId, reviewerName, comment, instanceId, taskId, null);
    }

    /**
     * The Task inbox's decision on request {@code approvalId} of workspace {@code tenantId} (no one is signed in: run as that
     * workspace). Approved continues the run; rejected fails it with the reviewer's comment. {@code bulkId} (bulk review): the
     * bulk decision in the Task inbox it was part of -- recorded on the request and named at the end of the audit line -- or
     * null for a single decision.
     */
    public Outcome decideFromInbox(long tenantId, long approvalId, boolean approved, Long reviewerUserId, String reviewerName, String comment,
                                   long instanceId, long taskId, String bulkId) {
        return RowSecurity.forTenant(tenantId, () -> {
            String said = comment == null || comment.trim().isEmpty() ? null : comment.trim();
            Optional<RunApprovalStore.Approval> decided = this.transactions.execute(status -> {
                Optional<RunApprovalStore.Approval> row = this.store.decide(approvalId, approved ? RunApprovalStore.APPROVED
                    : RunApprovalStore.REJECTED, reviewerUserId, reviewerName, said, instanceId, taskId, Instant.now(), bulkId);
                row.ifPresent(a -> this.audit.saveJobAuditLogs(a.jobQueueId, String.format("Approval request %d at step <%s>: %s by %s in the "
                    + "Task inbox (request %d, task %d)%s%s", a.runApprovalId, a.stepKey, approved ? "approved" : "rejected", a.decider(),
                    instanceId, taskId, said == null ? "." : ": " + AuditText.sentence(said), RunReviewService.bulkSuffix(bulkId))));
                return row;
            });
            if (decided == null || !decided.isPresent()) {
                return new Outcome(false, String.format("Approval request %d is not waiting (decided already, or not this workspace's); "
                    + "nothing done.", approvalId));
            }
            RunApprovalStore.Approval approval = decided.get();
            String why = approved ? null : String.format("Rejected by %s: %s", approval.decider(), said == null ? "no reason given" : said);
            return this.moveRun(approval, approved, why);
        });
    }

    /** Expires what is past its time and cancels what no run waits on; answers {expired, cancelled}. */
    public int[] sweep(Instant now) {
        int expired = 0;
        int cancelled = 0;
        List<RunApprovalStore.Approval> late = RowSecurity.acrossTenants("the approval sweep (MIG-346) reads every workspace's approval "
            + "requests past their time; each is expired as its own workspace", () -> this.store.expired(now, BATCH));
        for (RunApprovalStore.Approval due : late) {
            try {
                Outcome outcome = RowSecurity.forTenant(due.tenantId, () -> {
                    Optional<RunApprovalStore.Approval> decided = this.close(due, RunApprovalStore.EXPIRED, "No decision by " + due.expiresAt);
                    return decided.map(a -> this.moveRun(a, false, String.format("No decision by %s: the approval request expired.",
                        a.expiresAt))).orElse(null);
                });
                if (outcome != null) {
                    expired++;
                }
            } catch (RuntimeException failed) {
                logger.warn("Approval request {} of workspace {} could not be expired: {}", due.runApprovalId, due.tenantId, failed.getMessage());
            }
        }
        List<RunApprovalStore.Approval> stray = RowSecurity.acrossTenants("the approval sweep (MIG-346) reads every workspace's approval "
            + "requests whose run no longer waits on them; each is cancelled as its own workspace", () -> this.store.orphaned(BATCH));
        for (RunApprovalStore.Approval orphan : stray) {
            try {
                boolean closed = RowSecurity.forTenant(orphan.tenantId, () -> this.close(orphan, RunApprovalStore.CANCELLED,
                    "The run was closed before anyone decided").isPresent());
                if (closed) {
                    cancelled++;
                }
            } catch (RuntimeException failed) {
                logger.warn("Approval request {} of workspace {} could not be cancelled: {}", orphan.runApprovalId, orphan.tenantId,
                    failed.getMessage());
            }
        }
        return new int[] {expired, cancelled};
    }

    /** Settles a request the platform's way (expired, cancelled) and closes its inbox task, in one transaction. */
    private Optional<RunApprovalStore.Approval> close(RunApprovalStore.Approval approval, String status, String comment) {
        return this.transactions.execute(tx -> {
            Optional<RunApprovalStore.Approval> row = this.store.decide(approval.runApprovalId, status, null, null, comment, null, null,
                Instant.now(), null);
            row.ifPresent(a -> {
                this.events.closed(a.tenantId, a.runApprovalId, a.jobQueueId, status);
                this.audit.saveJobAuditLogs(a.jobQueueId, String.format("Approval request %d at step <%s>: %s (%s); its Task inbox task is "
                    + "closed.", a.runApprovalId, a.stepKey, status.toLowerCase(), comment));
            });
            return row;
        });
    }

    /** Hands the decided request to the engine: continue, or fail with {@code why}. */
    private Outcome moveRun(RunApprovalStore.Approval approval, boolean approved, String why) {
        Optional<RunOwnership.Owned> owned = RunOwnership.ofWorkspace(this.runs, this.jobs, approval.jobQueueId, approval.tenantId);
        if (!owned.isPresent()) {
            return new Outcome(false, String.format("Run %d of approval request %d is not found; nothing done.", approval.jobQueueId,
                approval.runApprovalId));
        }
        Optional<StepEngine.StepPlan> plan = this.engine.planToContinue(owned.get().job, owned.get().run);
        if (!plan.isPresent()) {
            return new Outcome(false, String.format("Run %d has no step-engine plan any more; nothing done.", approval.jobQueueId));
        }
        this.engine.decided(plan.get(), approval, approved, why);
        return new Outcome(true, String.format("Run %d %s at step <%s> (approval request %d).", approval.jobQueueId,
            approved ? "continues" : "fails", approval.stepKey, approval.runApprovalId));
    }
}

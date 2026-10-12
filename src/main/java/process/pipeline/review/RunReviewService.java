package process.pipeline.review;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobDto;
import process.model.enums.JobStatus;
import process.model.enums.ReviewDecision;
import process.model.enums.ReviewParty;
import process.model.pojo.JobQueue;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.model.service.SourceJobService;
import process.model.service.impl.TransactionServiceImpl;
import process.customer.CustomerEventJournal;
import process.pipeline.PipelineDefinition;
import process.pipeline.RunOwnership;
import process.security.TenantContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Two-party result review of a run (MIG-237): the review a run shows, and a party's decision on it.
 *
 * <b>Who.</b> The caller's party comes from their token ({@link ReviewParties}), never from the request: the console
 * (sourceJob.json/review/decide) records the INTERNAL review, by a tenant administrator of the run's workspace; the
 * customer's review comes through POST /v1/runs/{id}/review (MIG-334, process.customer.CustomerReviews) as the API client, into
 * {@link #decide} -- which refuses either side recording the other's review, whichever endpoint calls it.
 *
 * <b>What.</b> A completed run the caller may see (RunOwnership: another workspace's, or for a tenant user a colleague's,
 * is not found). Its required parties and the state machine are {@link RunReviews} and {@link RunReviewRules}: PENDING
 * until every required party approves, REJECTED by either, never approved by itself; each party decides once.
 *
 * <b>Record.</b> Every decision is a run_review_decision row (insert-only) and a line in the run's audit log; the run's
 * status moves on in run_review, and once decided the run's PENDING result_records take the decision. A rejection may
 * ask for the job to be run again: that is Run now, with its rules (an active job, nothing in flight, the workspace not
 * paused); the new run is linked to the rejected one, and a refused re-run leaves the rejection standing.
 */
@Service
public class RunReviewService {

    static final String RUN_NOT_FOUND = "Run not found with jobQueueId.";
    static final int MAX_TEXT = 2000;

    private final JobQueueRepository runs;
    private final SourceJobRepository jobs;
    private final RunReviews reviews;
    private final RunReviewStore store;
    private final SourceJobService sourceJobs;
    private final TransactionServiceImpl transactions;
    /** MIG-334: how the customer's rejection runs the pipeline again; absent in hand-built tests. */
    private CustomerRunAgain customerRunAgain;
    /** MIG-333: run.review.decided is journalled with the decision; absent in hand-built tests. */
    private CustomerEventJournal events;

    public RunReviewService(JobQueueRepository runs, SourceJobRepository jobs, RunReviews reviews, RunReviewStore store,
                            SourceJobService sourceJobs, TransactionServiceImpl transactions) {
        this.runs = runs;
        this.jobs = jobs;
        this.reviews = reviews;
        this.store = store;
        this.sourceJobs = sourceJobs;
        this.transactions = transactions;
    }

    @Autowired(required = false)
    public void setCustomerRunAgain(CustomerRunAgain customerRunAgain) {
        this.customerRunAgain = customerRunAgain;
    }

    @Autowired(required = false)
    public void setEventJournal(CustomerEventJournal events) {
        this.events = events;
    }

    /** The run's review: its status, required parties and decisions, and whether the caller may decide now. */
    @Transactional(readOnly = true)
    public ResponseDto review(Long jobQueueId) {
        Optional<RunOwnership.Owned> owned = RunOwnership.owned(this.runs, this.jobs, jobQueueId);
        if (!owned.isPresent()) {
            return new ResponseDto(ERROR, RUN_NOT_FOUND);
        }
        JobQueue run = owned.get().run;
        Map<String, Object> review = this.head(run);
        review.putAll(this.reviews.summary(run, owned.get().job));
        Optional<String> refusal = this.whyNot(owned.get(), ReviewParty.INTERNAL);
        Map<String, Object> you = new LinkedHashMap<>();
        you.put("party", ReviewParties.ofCaller().map(PipelineDefinition.Review::wordOf).orElse(null));
        you.put("canDecide", !refusal.isPresent());
        you.put("refusal", refusal.orElse(null));
        review.put("you", you);
        return new ResponseDto(SUCCESS, String.format("The run's results are %s.", review.get("reviewStatus")), review);
    }

    /** How many runs "waiting for review" answers at most, and how many candidates it reads to find them. */
    static final int WAITING_SHOWN = 50;
    static final int WAITING_READ = 200;

    /**
     * MIG-325: the runs whose results wait for a review, newest first -- the reviewer's list across every job the
     * caller may see, so nobody opens runs one by one to find them. Each is checked by the run's own rule (the version
     * it follows) and the run reads' visibility (RunOwnership).
     */
    @Transactional(readOnly = true)
    public ResponseDto waiting(Integer limit) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return new ResponseDto(ERROR, "Reviews belong to a workspace; this account is not attached to one.");
        }
        int shown = limit == null ? WAITING_SHOWN : Math.max(1, Math.min(limit, WAITING_SHOWN));
        List<Map<String, Object>> waiting = new ArrayList<>();
        boolean more = false;
        for (Long jobQueueId : this.store.undecidedReviewedRuns(tenantId, WAITING_READ)) {
            Optional<RunOwnership.Owned> owned = RunOwnership.owned(this.runs, this.jobs, jobQueueId);
            if (!owned.isPresent()) {
                continue;
            }
            Map<String, Object> summary = this.reviews.summary(owned.get().run, owned.get().job);
            if (!RunReviewStatus.PENDING.name().equals(summary.get("reviewStatus"))) {
                continue;
            }
            if (waiting.size() == shown) {
                more = true;
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("jobQueueId", owned.get().run.getJobQueueId());
            row.put("jobId", owned.get().job.getJobId());
            row.put("jobName", owned.get().job.getJobName());
            row.put("finishedAt", owned.get().run.getEndTime());
            row.put("required", summary.get("required"));
            row.put("decisions", summary.get("decisions"));
            waiting.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("runs", waiting);
        data.put("more", more);
        return new ResponseDto(SUCCESS, waiting.isEmpty() ? "No run is waiting for review."
            : String.format("%d run%s waiting for review%s.", waiting.size(), waiting.size() == 1 ? " is" : "s are",
                more ? ", and more" : ""), data);
    }

    /** The console's decision: the internal review. A request naming another party is refused here. */
    @Transactional
    public ResponseDto consoleDecide(RunReviewRequest request) {
        if (request != null && request.getParty() != null && !"internal".equals(request.getParty().trim().toLowerCase(Locale.ROOT))) {
            if ("customer".equals(request.getParty().trim().toLowerCase(Locale.ROOT))) {
                return new ResponseDto(ERROR, ReviewParties.CUSTOMER_IS_THEIRS);
            }
            return new ResponseDto(ERROR, "party is internal (the console records the internal review only).");
        }
        return this.decide(request, ReviewParty.INTERNAL);
    }

    /**
     * {@code party}'s decision on a run, as the caller. The one way a decision is recorded: the console's endpoint
     * calls it for INTERNAL, the customer's (MIG-334) for CUSTOMER; the caller's token must be that party.
     */
    @Transactional
    public ResponseDto decide(RunReviewRequest request, ReviewParty party) {
        if (request == null || request.getJobQueueId() == null) {
            return new ResponseDto(ERROR, "JobQueueId missing.");
        }
        Optional<String> notThisCaller = ReviewParties.refusal(party);
        if (notThisCaller.isPresent()) {
            return new ResponseDto(ERROR, notThisCaller.get());
        }
        Optional<ReviewDecision> decision = decisionOf(request.getDecision());
        if (!decision.isPresent()) {
            return new ResponseDto(ERROR, "decision is APPROVED or REJECTED.");
        }
        String reason = trimmed(request.getReason());
        String comment = trimmed(request.getComment());
        boolean rerun = Boolean.TRUE.equals(request.getRerun());
        if (decision.get() == ReviewDecision.REJECTED && reason == null) {
            return new ResponseDto(ERROR, "A rejection says why: reason is required.");
        }
        if (rerun && decision.get() != ReviewDecision.REJECTED) {
            return new ResponseDto(ERROR, "rerun goes with a rejection only.");
        }
        if ((reason != null && reason.length() > MAX_TEXT) || (comment != null && comment.length() > MAX_TEXT)) {
            return new ResponseDto(ERROR, String.format("comment and reason are at most %d characters each.", MAX_TEXT));
        }
        // A person sees their workspace's runs by JobOwnership; the customer's API client acts for the whole workspace.
        Optional<RunOwnership.Owned> owned = party == ReviewParty.CUSTOMER
            ? RunOwnership.ownedByWorkspace(this.runs, this.jobs, request.getJobQueueId())
            : RunOwnership.owned(this.runs, this.jobs, request.getJobQueueId());
        if (!owned.isPresent()) {
            return new ResponseDto(ERROR, RUN_NOT_FOUND);
        }
        Optional<String> refused = this.whyNot(owned.get(), party);
        if (refused.isPresent()) {
            return new ResponseDto(ERROR, refused.get());
        }
        Recorded recorded = this.record(owned.get(), party, decision.get(), comment, reason, TenantContext.getAppUserId(),
            TenantContext.getUsername(), null);
        if (recorded.refusal != null) {
            return new ResponseDto(ERROR, recorded.refusal);
        }
        JobQueue run = owned.get().run;
        RunReviewStore.Decision record = recorded.decision;
        RunReviewStatus status = recorded.status;

        Map<String, Object> rerunOutcome = rerun ? this.runAgain(owned.get(), record) : null;
        Map<String, Object> answer = this.head(run);
        answer.putAll(this.reviews.summary(run, owned.get().job));
        String message = String.format("The %s review is recorded: the run's results are %s.", PipelineDefinition.Review.wordOf(party),
            status.name());
        if (rerunOutcome != null) {
            answer.put("rerun", rerunOutcome);
            message += Boolean.TRUE.equals(rerunOutcome.get("queued"))
                ? String.format(" Run %s runs the job again.", rerunOutcome.get("jobQueueId"))
                : " The job was not run again: " + rerunOutcome.get("message");
        }
        return new ResponseDto(SUCCESS, message, answer);
    }

    /** What {@link #record} did: the decision and the review's status after it, or why nothing was recorded. */
    private static final class Recorded {
        final RunReviewStore.Decision decision;
        final RunReviewStatus status;
        final String refusal;

        Recorded(RunReviewStore.Decision decision, RunReviewStatus status, String refusal) {
            this.decision = decision;
            this.status = status;
            this.refusal = refusal;
        }
    }

    /**
     * Records {@code party}'s decision on the run -- the one way a decision is written, whoever asked: the run's status row
     * held for the transaction and asked again (the other party may have decided meanwhile), the decision, the status after
     * it, the results settled once decided, a line in the run's audit log ({@code via} says where, when not the console's
     * or the API's own endpoint), and the customer's run.review.decided.
     */
    private Recorded record(RunOwnership.Owned owned, ReviewParty party, ReviewDecision decision, String comment, String reason,
        Long reviewerUserId, String reviewerName, String via) {
        JobQueue run = owned.run;
        RunReviewStore.Status held = this.store.lockPending(run.getJobQueueId(), this.reviews.required(run, owned.job));
        Map<ReviewParty, ReviewDecision> decided = this.reviews.decisionsByParty(this.store.decisionsOf(run.getJobQueueId()));
        Optional<String> late = RunReviewRules.refusal(held.required, decided, party);
        if (late.isPresent()) {
            return new Recorded(null, null, late.get());
        }
        Instant now = Instant.now();
        RunReviewStore.Decision record = new RunReviewStore.Decision();
        record.jobQueueId = run.getJobQueueId();
        record.attempt = Math.max(1, run.getAttempt());
        record.party = party;
        record.decision = decision;
        record.comment = comment;
        record.reason = reason;
        record.reviewerUserId = reviewerUserId;
        record.reviewerName = reviewerName;
        record.decidedAt = now;
        this.store.record(record);
        decided.put(party, decision);
        RunReviewStatus status = RunReviewRules.statusOf(held.required, decided);
        this.store.settle(run.getJobQueueId(), status, status.isDecided() ? now : null);
        if (status.isDecided()) {
            this.store.settleResults(run.getJobQueueId(), status);
        }
        this.transactions.saveJobAuditLogs(run.getJobQueueId(), auditLine(record, status) + (via == null ? "" : " " + via));
        if (this.events != null && run.getTenantId() != null) {
            // MIG-333: the customer's webhooks hear of every decision, either party's, with the review's status after it.
            this.events.reviewDecided(run.getTenantId(), run.getJobQueueId());
        }
        return new Recorded(record, status, null);
    }

    /**
     * MIG-361: the internal review decided in the Task inbox -- workflow-service's request for the run ended Approved or
     * Rejected by one of the reviewers the pipeline names (workflow-service let only them act on the task). Recorded as
     * that person's internal decision, with their comment (a rejection's reason too). No caller is signed in: run as the
     * run's workspace (RowSecurity.forTenant). Refused, and nothing recorded, when the run is not this workspace's, not
     * completed, needs no internal review, or the internal review is already decided (the console got there first).
     */
    @Transactional
    public ResponseDto decideFromInbox(long tenantId, long jobQueueId, ReviewDecision decision, Long reviewerUserId,
        String reviewerName, String comment, long instanceId, long taskId) {
        Optional<RunOwnership.Owned> owned = RunOwnership.ofWorkspace(this.runs, this.jobs, jobQueueId, tenantId);
        if (!owned.isPresent()) {
            return new ResponseDto(ERROR, RUN_NOT_FOUND);
        }
        if (owned.get().run.getJobStatus() != JobStatus.Completed) {
            return new ResponseDto(ERROR, String.format("Only a completed run's results can be reviewed; this run is %s.",
                owned.get().run.getJobStatus()));
        }
        if (!this.reviews.required(owned.get().run, owned.get().job).contains(ReviewParty.INTERNAL)) {
            return new ResponseDto(ERROR, "This run's results need no internal review.");
        }
        String said = trimmed(comment);
        if (said != null && said.length() > MAX_TEXT) {
            said = said.substring(0, MAX_TEXT);
        }
        String reason = decision == ReviewDecision.REJECTED ? (said != null ? said : "Rejected in the Task inbox") : null;
        Recorded recorded = this.record(owned.get(), ReviewParty.INTERNAL, decision, said, reason, reviewerUserId, reviewerName,
            String.format("Decided in the Task inbox (request %d, task %d).", instanceId, taskId));
        if (recorded.refusal != null) {
            return new ResponseDto(ERROR, recorded.refusal);
        }
        return new ResponseDto(SUCCESS, String.format("The internal review is recorded: the run's results are %s.", recorded.status.name()),
            this.head(owned.get().run));
    }

    /** Why this caller may not record {@code party}'s review of this run now; empty when they may. */
    private Optional<String> whyNot(RunOwnership.Owned owned, ReviewParty party) {
        Optional<String> caller = ReviewParties.refusal(party);
        if (caller.isPresent()) {
            return caller;
        }
        if (owned.run.getJobStatus() != JobStatus.Completed) {
            return Optional.of(String.format("Only a completed run's results can be reviewed; this run is %s.", owned.run.getJobStatus()));
        }
        Set<ReviewParty> required = this.reviews.required(owned.run, owned.job);
        return RunReviewRules.refusal(required, this.reviews.decisionsByParty(this.store.decisionsOf(owned.run.getJobQueueId())), party);
    }

    /**
     * Run now, for a rejected run: the new run linked to it, and a line in each run's audit log. The customer's rejection
     * (MIG-334) runs it again the API's way ({@link CustomerRunAgain}): an API client has no Run now of a person's.
     */
    private Map<String, Object> runAgain(RunOwnership.Owned owned, RunReviewStore.Decision rejection) {
        if (rejection.party == ReviewParty.CUSTOMER) {
            Map<String, Object> outcome = new LinkedHashMap<>();
            if (this.customerRunAgain == null) {
                outcome.put("queued", false);
                outcome.put("jobQueueId", null);
                outcome.put("message", "The customer's re-run is not available here.");
                return outcome;
            }
            outcome.putAll(this.customerRunAgain.runAgain(owned.run, owned.job));
            Object queuedId = outcome.get("jobQueueId");
            if (Boolean.TRUE.equals(outcome.get("queued")) && queuedId instanceof Number) {
                this.linkAgain(owned.run.getJobQueueId(), ((Number) queuedId).longValue(), rejection);
            }
            return outcome;
        }
        Map<String, Object> outcome = new LinkedHashMap<>();
        SourceJobDto again = new SourceJobDto();
        again.setJobId(owned.job.getJobId());
        ResponseDto queued;
        try {
            queued = this.sourceJobs.runSourceJob(again);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            // Run now declares Exception; nothing it throws is a refusal (those are answers), so the decision goes too.
            throw new IllegalStateException("The job could not be run again: " + ex.getMessage(), ex);
        }
        if (queued == null || !SUCCESS.equals(queued.getStatus())) {
            outcome.put("queued", false);
            outcome.put("jobQueueId", null);
            outcome.put("message", queued == null ? "Run now gave no answer." : queued.getMessage());
            return outcome;
        }
        long rejected = owned.run.getJobQueueId();
        // One max() in SQL, not the job's whole history (scale review P0 #1).
        Long rerunId = this.runs.findNewestRunIdAfter(owned.job.getJobId(), rejected);
        outcome.put("queued", true);
        outcome.put("jobQueueId", rerunId);
        outcome.put("message", queued.getMessage());
        if (rerunId != null) {
            this.linkAgain(rejected, rerunId, rejection);
        }
        return outcome;
    }

    /** The new run linked to the rejected one, and a line in each run's audit log. */
    private void linkAgain(long rejected, long rerunId, RunReviewStore.Decision rejection) {
        this.store.linkRerun(rejected, rerunId);
        this.transactions.saveJobAuditLogs(rejected, String.format("Result review: run %d runs the job again.", rerunId));
        this.transactions.saveJobAuditLogs(rerunId, String.format("Queued again: run %d's results were rejected (%s review by %s).",
            rejected, PipelineDefinition.Review.wordOf(rejection.party), reviewer(rejection)));
    }

    private Map<String, Object> head(JobQueue run) {
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("jobQueueId", run.getJobQueueId());
        head.put("jobId", run.getJobId());
        head.put("attempt", Math.max(1, run.getAttempt()));
        head.put("runStatus", run.getJobStatus());
        return head;
    }

    static String auditLine(RunReviewStore.Decision d, RunReviewStatus status) {
        StringBuilder line = new StringBuilder(String.format("Result review: %s %s by %s", PipelineDefinition.Review.wordOf(d.party),
            d.decision.name(), reviewer(d)));
        line.append(String.format(" (attempt %d).", d.attempt));
        if (d.reason != null) {
            line.append(" Reason: ").append(d.reason).append('.');
        }
        if (d.comment != null) {
            line.append(" Comment: ").append(d.comment).append('.');
        }
        line.append(String.format(" The run's results are now %s.", status.name()));
        return line.toString();
    }

    private static String reviewer(RunReviewStore.Decision d) {
        String name = d.reviewerName == null ? "an unnamed caller" : d.reviewerName;
        return d.reviewerUserId == null ? name : String.format("%s (user %d)", name, d.reviewerUserId);
    }

    private static Optional<ReviewDecision> decisionOf(String word) {
        if (word == null) {
            return Optional.empty();
        }
        String upper = word.trim().toUpperCase(Locale.ROOT);
        for (ReviewDecision decision : ReviewDecision.values()) {
            if (decision.name().equals(upper)) {
                return Optional.of(decision);
            }
        }
        return Optional.empty();
    }

    private static String trimmed(String text) {
        return text == null || text.trim().isEmpty() ? null : text.trim();
    }
}

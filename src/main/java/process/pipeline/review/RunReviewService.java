package process.pipeline.review;

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
import process.pipeline.PipelineDefinition;
import process.pipeline.RunOwnership;
import process.security.TenantContext;

import java.time.Instant;
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
 * customer's review will come through POST /v1/executions/{id}/review (MIG-234, deferred) as the API client, into
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

    public RunReviewService(JobQueueRepository runs, SourceJobRepository jobs, RunReviews reviews, RunReviewStore store,
                            SourceJobService sourceJobs, TransactionServiceImpl transactions) {
        this.runs = runs;
        this.jobs = jobs;
        this.reviews = reviews;
        this.store = store;
        this.sourceJobs = sourceJobs;
        this.transactions = transactions;
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
     * calls it for INTERNAL, the customer's (deferred) will for CUSTOMER; the caller's token must be that party.
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
        JobQueue run = owned.get().run;

        // Held for the rest of the transaction, then asked again: the other party may have decided meanwhile.
        RunReviewStore.Status held = this.store.lockPending(run.getJobQueueId(), this.reviews.required(run, owned.get().job));
        Map<ReviewParty, ReviewDecision> decided = this.reviews.decisionsByParty(this.store.decisionsOf(run.getJobQueueId()));
        Optional<String> late = RunReviewRules.refusal(held.required, decided, party);
        if (late.isPresent()) {
            return new ResponseDto(ERROR, late.get());
        }

        Instant now = Instant.now();
        RunReviewStore.Decision record = new RunReviewStore.Decision();
        record.jobQueueId = run.getJobQueueId();
        record.attempt = Math.max(1, run.getAttempt());
        record.party = party;
        record.decision = decision.get();
        record.comment = comment;
        record.reason = reason;
        record.reviewerUserId = TenantContext.getAppUserId();
        record.reviewerName = TenantContext.getUsername();
        record.decidedAt = now;
        this.store.record(record);
        decided.put(party, decision.get());
        RunReviewStatus status = RunReviewRules.statusOf(held.required, decided);
        this.store.settle(run.getJobQueueId(), status, status.isDecided() ? now : null);
        if (status.isDecided()) {
            this.store.settleResults(run.getJobQueueId(), status);
        }
        this.transactions.saveJobAuditLogs(run.getJobQueueId(), auditLine(record, status));

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

    /** Run now, for a rejected run: the new run linked to it, and a line in each run's audit log. */
    private Map<String, Object> runAgain(RunOwnership.Owned owned, RunReviewStore.Decision rejection) {
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
            this.store.linkRerun(rejected, rerunId);
            this.transactions.saveJobAuditLogs(rejected, String.format("Result review: run %d runs the job again.", rerunId));
            this.transactions.saveJobAuditLogs(rerunId, String.format("Queued again: run %d's results were rejected (%s review by %s).",
                rejected, PipelineDefinition.Review.wordOf(rejection.party), reviewer(rejection)));
        }
        return outcome;
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

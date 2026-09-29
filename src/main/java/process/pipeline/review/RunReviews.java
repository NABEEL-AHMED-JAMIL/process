package process.pipeline.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import process.model.enums.ReviewDecision;
import process.model.enums.ReviewParty;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.pipeline.PipelineDefinition;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.StepStore;
import process.util.BusinessTime;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A run's review as the console reads it (MIG-237): which parties its results need, their decisions, and the status
 * they make -- for the review endpoint, the run's timeline and its result manifest alike.
 *
 * <b>Which parties.</b> Once a party has decided, the parties recorded then (run_review), so a later edit of the
 * pipeline never reopens or closes a decided review. Before that, the settings.review of the definition the run
 * follows: the one it is pinned to (a step-engine run, step_execution), else its pipeline's latest definition as it
 * stood when the run was made (a run a worker ran). A pipeline without a definition, or without a review setting,
 * requires nobody: NOT_REQUIRED. Otherwise the run is PENDING until its parties decide -- never approved by itself.
 */
@Component
public class RunReviews {

    private static final Logger logger = LoggerFactory.getLogger(RunReviews.class);

    private final StepStore steps;
    private final PipelineDefinitionStore definitions;
    private final RunReviewStore store;

    public RunReviews(StepStore steps, PipelineDefinitionStore definitions, RunReviewStore store) {
        this.steps = steps;
        this.definitions = definitions;
        this.store = store;
    }

    /** The parties this run's results need, by the rule above. */
    public Set<ReviewParty> required(JobQueue run, SourceJob job) {
        Optional<RunReviewStore.Status> decided = this.store.statusOf(run.getJobQueueId());
        if (decided.isPresent()) {
            return decided.get().required;
        }
        return this.requiredByDefinition(run, job);
    }

    Set<ReviewParty> requiredByDefinition(JobQueue run, SourceJob job) {
        Optional<PipelineDefinitionStore.Stored> stored = this.steps.pinnedDefinition(run.getJobQueueId()).flatMap(this.definitions::byId);
        if (!stored.isPresent() && job.getTenantId() != null && job.getTaskDetail() != null) {
            stored = this.definitions.latestFor(job.getTenantId(), job.getTaskDetail().getPipelineId(), run.getDateCreated());
        }
        if (!stored.isPresent()) {
            return EnumSet.noneOf(ReviewParty.class);
        }
        try {
            PipelineDefinition definition = stored.get().definition();
            return definition.effectiveSettings().requiredReviews();
        } catch (IllegalStateException unreadable) {
            logger.warn("Run {}: definition {} cannot be read, so its review setting is not known: {}", run.getJobQueueId(),
                stored.get().id, unreadable.getMessage());
            return EnumSet.noneOf(ReviewParty.class);
        }
    }

    /** The run's decisions by party. */
    Map<ReviewParty, ReviewDecision> decisionsByParty(List<RunReviewStore.Decision> decisions) {
        Map<ReviewParty, ReviewDecision> byParty = new EnumMap<>(ReviewParty.class);
        decisions.forEach(decision -> byParty.put(decision.party, decision.decision));
        return byParty;
    }

    /** reviewStatus, required, decisions, decidedAt and rerunJobQueueId: the review as every run read shows it. */
    public Map<String, Object> summary(JobQueue run, SourceJob job) {
        Optional<RunReviewStore.Status> decided = this.store.statusOf(run.getJobQueueId());
        List<RunReviewStore.Decision> decisions = this.store.decisionsOf(run.getJobQueueId());
        Set<ReviewParty> required = decided.map(status -> status.required).orElseGet(() -> this.requiredByDefinition(run, job));
        RunReviewStatus status = decided.map(row -> row.status).orElseGet(() -> RunReviewRules.statusOf(required,
            this.decisionsByParty(decisions)));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("reviewStatus", status.name());
        summary.put("required", required.stream().map(PipelineDefinition.Review::wordOf).collect(Collectors.toList()));
        summary.put("decisions", decisions.stream().map(RunReviews::decision).collect(Collectors.toList()));
        summary.put("decidedAt", decided.map(row -> row.decidedAt).map(BusinessTime::wallClockOf).orElse(null));
        summary.put("rerunJobQueueId", decided.map(row -> row.rerunJobQueueId).orElse(null));
        return summary;
    }

    private static Map<String, Object> decision(RunReviewStore.Decision d) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runReviewDecisionId", d.runReviewDecisionId);
        out.put("party", PipelineDefinition.Review.wordOf(d.party));
        out.put("decision", d.decision.name());
        out.put("attempt", d.attempt);
        out.put("comment", d.comment);
        out.put("reason", d.reason);
        out.put("reviewerUserId", d.reviewerUserId);
        out.put("reviewer", d.reviewerName);
        out.put("decidedAt", d.decidedAt == null ? null : BusinessTime.wallClockOf(d.decidedAt));
        return out;
    }
}

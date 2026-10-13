package process.pipeline.review;

import process.model.enums.ReviewDecision;
import process.model.enums.ReviewParty;
import process.pipeline.PipelineDefinition;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * MIG-237's state machine for a run's results, with nothing else in it: which status the required parties and their
 * decisions make, and whether a party may decide now.
 *
 * <pre>
 *   required []                       -> NOT_REQUIRED, and no decision is taken
 *   required [p, ...], no decision    -> PENDING (a draft: never approved by itself)
 *   any required party REJECTED       -> REJECTED (the other side need not decide)
 *   every required party APPROVED     -> APPROVED
 *   APPROVED or REJECTED              -> final: no more decisions; a rejected run is run again as a new run
 * </pre>
 *
 * Each required party decides once; a party the pipeline does not require has no say.
 */
public final class RunReviewRules {

    private RunReviewRules() {}

    public static RunReviewStatus statusOf(Set<ReviewParty> required, Map<ReviewParty, ReviewDecision> decisions) {
        if (required.isEmpty()) {
            return RunReviewStatus.NOT_REQUIRED;
        }
        boolean allApproved = true;
        for (ReviewParty party : required) {
            ReviewDecision decision = decisions.get(party);
            if (decision == ReviewDecision.REJECTED) {
                return RunReviewStatus.REJECTED;
            }
            allApproved &= decision == ReviewDecision.APPROVED;
        }
        return allApproved ? RunReviewStatus.APPROVED : RunReviewStatus.PENDING;
    }

    /** Why this party may not record a decision now, in words a person reads; empty when it may. */
    public static Optional<String> refusal(Set<ReviewParty> required, Map<ReviewParty, ReviewDecision> decisions, ReviewParty party) {
        return refused(required, decisions, party).map(refused -> refused.message);
    }

    /**
     * {@link #refusal} with its kind, as bulk review's skip reasons name it: {@link Refusal#NO_REVIEW} when the pipeline asks
     * for no review (or none by this party), {@link Refusal#ALREADY_DECIDED} when the review or this party's part of it is
     * already decided.
     */
    public static Optional<Refusal> refused(Set<ReviewParty> required, Map<ReviewParty, ReviewDecision> decisions, ReviewParty party) {
        if (required.isEmpty()) {
            return Optional.of(new Refusal(Refusal.NO_REVIEW, "This run's pipeline asks for no review of its results."));
        }
        String word = PipelineDefinition.Review.wordOf(party);
        if (!required.contains(party)) {
            return Optional.of(new Refusal(Refusal.NO_REVIEW, String.format("This run's pipeline does not ask for %s %s review.",
                article(word), word)));
        }
        RunReviewStatus status = statusOf(required, decisions);
        if (status.isDecided()) {
            return Optional.of(new Refusal(Refusal.ALREADY_DECIDED, String.format("This run's results are already %s.",
                status.name().toLowerCase(Locale.ROOT))));
        }
        ReviewDecision earlier = decisions.get(party);
        if (earlier != null) {
            return Optional.of(new Refusal(Refusal.ALREADY_DECIDED, String.format("The %s review of this run is already recorded: %s.",
                word, earlier.name().toLowerCase(Locale.ROOT))));
        }
        return Optional.empty();
    }

    /** Why a decision is refused: its kind (bulk review's skip reason code) and the words a person reads. */
    public static final class Refusal {
        public static final String NOT_FOUND = "not-found";
        public static final String NOT_YOURS = "not-yours";
        public static final String NOT_COMPLETED = "not-completed";
        public static final String NO_REVIEW = "no-review";
        public static final String ALREADY_DECIDED = "already-decided";
        public static final String INDIVIDUAL_REVIEW = "individual-review";
        public static final String BULK_OFF = "bulk-off";

        public final String code;
        public final String message;

        public Refusal(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    private static String article(String word) {
        return "aeiou".indexOf(word.charAt(0)) >= 0 ? "an" : "a";
    }
}

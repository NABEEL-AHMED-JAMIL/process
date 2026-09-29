package process.pipeline.review;

import org.junit.jupiter.api.Test;
import process.model.enums.ReviewDecision;
import process.model.enums.ReviewParty;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static process.model.enums.ReviewDecision.APPROVED;
import static process.model.enums.ReviewDecision.REJECTED;
import static process.model.enums.ReviewParty.CUSTOMER;
import static process.model.enums.ReviewParty.INTERNAL;

/**
 * MIG-237's state machine, for every combination of required reviews -- [], [internal], [customer], [internal,
 * customer] -- and every order the parties can decide in: PENDING until every required party approves, REJECTED by
 * either side, never approved by itself, and nothing after it is decided.
 */
class RunReviewRulesTest {

    private static final Set<ReviewParty> NOBODY = EnumSet.noneOf(ReviewParty.class);
    private static final Set<ReviewParty> OURS = EnumSet.of(INTERNAL);
    private static final Set<ReviewParty> THEIRS = EnumSet.of(CUSTOMER);
    private static final Set<ReviewParty> BOTH = EnumSet.of(INTERNAL, CUSTOMER);

    /** Applies the decisions in order, each one refused or recorded by the rules; answers the status after each. */
    private static RunReviewStatus[] walk(Set<ReviewParty> required, Object... partyThenDecision) {
        Map<ReviewParty, ReviewDecision> decided = new EnumMap<>(ReviewParty.class);
        RunReviewStatus[] seen = new RunReviewStatus[partyThenDecision.length / 2 + 1];
        seen[0] = RunReviewRules.statusOf(required, decided);
        for (int i = 0; i < partyThenDecision.length; i += 2) {
            ReviewParty party = (ReviewParty) partyThenDecision[i];
            assertThat(RunReviewRules.refusal(required, decided, party)).as("%s may decide after %s", party, decided).isEmpty();
            decided.put(party, (ReviewDecision) partyThenDecision[i + 1]);
            seen[i / 2 + 1] = RunReviewRules.statusOf(required, decided);
        }
        return seen;
    }

    @Test
    void nobodyRequiredIsNotRequiredAndTakesNoDecision() {
        assertThat(RunReviewRules.statusOf(NOBODY, Collections.emptyMap())).isEqualTo(RunReviewStatus.NOT_REQUIRED);
        for (ReviewParty party : ReviewParty.values()) {
            assertThat(RunReviewRules.refusal(NOBODY, Collections.emptyMap(), party))
                .contains("This run's pipeline asks for no review of its results.");
        }
    }

    @Test
    void internalOnlyIsApprovedOrRejectedByInternalAndTheCustomerHasNoSay() {
        assertThat(walk(OURS, INTERNAL, APPROVED)).containsExactly(RunReviewStatus.PENDING, RunReviewStatus.APPROVED);
        assertThat(walk(OURS, INTERNAL, REJECTED)).containsExactly(RunReviewStatus.PENDING, RunReviewStatus.REJECTED);
        assertThat(RunReviewRules.refusal(OURS, Collections.emptyMap(), CUSTOMER))
            .contains("This run's pipeline does not ask for a customer review.");
    }

    @Test
    void customerOnlyIsApprovedOrRejectedByTheCustomerAndInternalHasNoSay() {
        assertThat(walk(THEIRS, CUSTOMER, APPROVED)).containsExactly(RunReviewStatus.PENDING, RunReviewStatus.APPROVED);
        assertThat(walk(THEIRS, CUSTOMER, REJECTED)).containsExactly(RunReviewStatus.PENDING, RunReviewStatus.REJECTED);
        assertThat(RunReviewRules.refusal(THEIRS, Collections.emptyMap(), INTERNAL))
            .contains("This run's pipeline does not ask for an internal review.");
    }

    @Test
    void bothRequiredIsApprovedOnlyWhenBothApproveInEitherOrder() {
        assertThat(walk(BOTH, INTERNAL, APPROVED, CUSTOMER, APPROVED))
            .containsExactly(RunReviewStatus.PENDING, RunReviewStatus.PENDING, RunReviewStatus.APPROVED);
        assertThat(walk(BOTH, CUSTOMER, APPROVED, INTERNAL, APPROVED))
            .containsExactly(RunReviewStatus.PENDING, RunReviewStatus.PENDING, RunReviewStatus.APPROVED);
    }

    @Test
    void bothRequiredIsRejectedByEitherSideFirstOrSecond() {
        assertThat(walk(BOTH, INTERNAL, REJECTED)).containsExactly(RunReviewStatus.PENDING, RunReviewStatus.REJECTED);
        assertThat(walk(BOTH, CUSTOMER, REJECTED)).containsExactly(RunReviewStatus.PENDING, RunReviewStatus.REJECTED);
        assertThat(walk(BOTH, INTERNAL, APPROVED, CUSTOMER, REJECTED))
            .containsExactly(RunReviewStatus.PENDING, RunReviewStatus.PENDING, RunReviewStatus.REJECTED);
        assertThat(walk(BOTH, CUSTOMER, APPROVED, INTERNAL, REJECTED))
            .containsExactly(RunReviewStatus.PENDING, RunReviewStatus.PENDING, RunReviewStatus.REJECTED);
    }

    @Test
    void aRejectedRunTakesNoMoreDecisionsAndTheOtherSideNeedNotDecide() {
        Map<ReviewParty, ReviewDecision> rejected = new EnumMap<>(ReviewParty.class);
        rejected.put(INTERNAL, REJECTED);
        assertThat(RunReviewRules.refusal(BOTH, rejected, CUSTOMER)).contains("This run's results are already rejected.");
        assertThat(RunReviewRules.refusal(BOTH, rejected, INTERNAL)).contains("This run's results are already rejected.");
    }

    @Test
    void anApprovedRunTakesNoMoreDecisions() {
        Map<ReviewParty, ReviewDecision> approved = new EnumMap<>(ReviewParty.class);
        approved.put(INTERNAL, APPROVED);
        assertThat(RunReviewRules.refusal(OURS, approved, INTERNAL)).contains("This run's results are already approved.");
    }

    @Test
    void aPartyDecidesOnceAndIsNotTakenBack() {
        Map<ReviewParty, ReviewDecision> half = new EnumMap<>(ReviewParty.class);
        half.put(INTERNAL, APPROVED);
        assertThat(RunReviewRules.statusOf(BOTH, half)).isEqualTo(RunReviewStatus.PENDING);
        assertThat(RunReviewRules.refusal(BOTH, half, INTERNAL))
            .contains("The internal review of this run is already recorded: approved.");
        assertThat(RunReviewRules.refusal(BOTH, half, CUSTOMER)).isEmpty();
    }

    /** Never auto-approved: with a review required, no decisions is PENDING, and a party's approval never stands for another's. */
    @Test
    void aRunThatRequiresReviewIsNeverApprovedWithoutEveryRequiredApproval() {
        for (Set<ReviewParty> required : Arrays.asList(OURS, THEIRS, BOTH)) {
            assertThat(RunReviewRules.statusOf(required, Collections.emptyMap())).isEqualTo(RunReviewStatus.PENDING);
        }
        Map<ReviewParty, ReviewDecision> ours = new EnumMap<>(ReviewParty.class);
        ours.put(INTERNAL, APPROVED);
        assertThat(RunReviewRules.statusOf(THEIRS, ours)).as("a decision nobody asked for counts for nothing")
            .isEqualTo(RunReviewStatus.PENDING);
    }
}

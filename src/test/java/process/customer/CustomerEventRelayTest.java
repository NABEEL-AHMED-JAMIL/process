package process.customer;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** MIG-333: which run statuses are news, and the run as a journal row saw it. */
class CustomerEventRelayTest {

    @Test
    void onlyRunningCompletedAndFailedAreAnnounced() {
        assertThat(CustomerEventTypes.ofRunStatus("Running")).isEqualTo("run.started");
        assertThat(CustomerEventTypes.ofRunStatus("Completed")).isEqualTo("run.completed");
        assertThat(CustomerEventTypes.ofRunStatus("Failed")).isEqualTo("run.failed");
        for (String quiet : new String[] {"Queue", "Start", "Skip", "Missed", "Interrupt", null}) {
            assertThat(CustomerEventTypes.ofRunStatus(quiet)).as(String.valueOf(quiet)).isNull();
        }
    }

    @Test
    void aRunStillInTheJournalledStatusIsReadAsItIsNow() {
        CustomerRunStore.Row now = row("Completed");
        CustomerEventRelay.Pending pending = pending("Completed");
        assertThat(CustomerEventRelay.asItStood(now, pending)).isSameAs(now);
    }

    @Test
    void aRunThatMovedOnIsReadAsItStoodThen() {
        CustomerRunStore.Row now = row("Completed");
        CustomerEventRelay.Pending pending = pending("Running");
        CustomerRunStore.Row then = CustomerEventRelay.asItStood(now, pending);
        assertThat(then.jobStatus).isEqualTo("Running");
        assertThat(then.endedAt).isNull();
        assertThat(then.message).isEqualTo("then");
        assertThat(then.reference).isEqualTo("ref-1");
        assertThat(then.runId).isEqualTo(now.runId);
        Map<String, Object> view = CustomerViews.run(then, "not_required");
        assertThat(view.get("status")).isEqualTo("running");
        assertThat(view.get("endedAt")).isNull();
        assertThat(view.get("startedAt")).isEqualTo("2026-10-06T14:00:00Z");
    }

    @Test
    void theReviewIsRequestedOnlyWhenTheCustomerIsAParty() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("required", Arrays.asList("internal", "customer"));
        assertThat(CustomerEventRelay.asksTheCustomer(summary)).isTrue();
        summary.put("required", Collections.singletonList("internal"));
        assertThat(CustomerEventRelay.asksTheCustomer(summary)).isFalse();
        summary.remove("required");
        assertThat(CustomerEventRelay.asksTheCustomer(summary)).isFalse();
    }

    /** MIG-336: a decision made before the completion is relayed does not change what the completion announces. */
    @Test
    void aCompletionAnnouncesTheReviewAsItStoodThenEvenIfDecidedSince() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("reviewStatus", "APPROVED");
        summary.put("required", Collections.singletonList("customer"));
        summary.put("decidedAt", LocalDateTime.parse("2026-10-06T09:00:01"));
        summary.put("rerunJobQueueId", 9L);
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("party", "customer");
        decision.put("decision", "APPROVED");
        summary.put("decisions", Collections.singletonList(decision));
        Map<String, Object> then = CustomerEventRelay.reviewAtCompletion(summary);
        assertThat(then.get("status")).isEqualTo("pending");
        assertThat(then.get("required")).isEqualTo(Collections.singletonList("customer"));
        assertThat((List<?>) then.get("decisions")).isEmpty();
        assertThat(then.get("decidedAt")).isNull();
        assertThat(then.get("rerunRunId")).isNull();
        summary.put("required", Collections.emptyList());
        summary.put("reviewStatus", null);
        assertThat(CustomerEventRelay.reviewAtCompletion(summary).get("status")).isEqualTo("not_required");
    }

    private static CustomerRunStore.Row row(String status) {
        CustomerRunStore.Row row = new CustomerRunStore.Row();
        row.runId = 7;
        row.jobId = 3;
        row.tenantId = 2946;
        row.jobStatus = status;
        row.message = "now";
        row.createdAt = Instant.parse("2026-10-06T13:59:00Z");
        row.startedAt = Instant.parse("2026-10-06T14:00:00Z");
        row.endedAt = Instant.parse("2026-10-06T14:05:00Z");
        row.attempt = 1;
        row.reference = "ref-1";
        return row;
    }

    private static CustomerEventRelay.Pending pending(String status) {
        CustomerEventRelay.Pending pending = new CustomerEventRelay.Pending();
        pending.kind = "run_status";
        pending.jobStatus = status;
        pending.statusMessage = "then";
        pending.startedAt = Instant.parse("2026-10-06T14:00:00Z");
        pending.attempt = 1;
        return pending;
    }
}

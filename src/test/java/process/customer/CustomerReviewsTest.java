package process.customer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import process.model.dto.ResponseDto;
import process.model.enums.ReviewParty;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.pipeline.review.RunReviewRequest;
import process.pipeline.review.RunReviewService;
import process.pipeline.review.RunReviews;
import process.security.TenantContext;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * MIG-334 (MIG-237's customer half): the customer's review through the API goes through RunReviewService.decide as the
 * CUSTOMER party -- the console's rules -- after the API's own checks: reviews:write, a request that says what it
 * decides (422 with the field), the workspace's run (404). The run's state refusing is a 409 in the console's words; the
 * answer is the Review in the API's shape; the same key replays the first answer and decides nothing twice.
 */
class CustomerReviewsTest {

    static final long TENANT = 2946L;
    static final long RUN = 9100L;

    private final CustomerRuns runs = mock(CustomerRuns.class);
    private final RunReviews reviews = mock(RunReviews.class);
    private final RunReviewService service = mock(RunReviewService.class);
    private final CustomerReviews customerReviews = new CustomerReviews(this.runs, this.reviews, this.service, new Idempotency(new MemoryReceipts()));

    @BeforeEach
    void asAClient() {
        this.client("runs:read", "reviews:write");
        CustomerRunStore.Row row = CustomerRunsTest.row(RUN, "Completed");
        JobQueue run = new JobQueue();
        run.setJobQueueId(RUN);
        SourceJob job = new SourceJob();
        when(this.runs.found(String.valueOf(RUN))).thenReturn(Optional.of(new CustomerRuns.Found(row, run, job)));
        when(this.runs.found("404")).thenReturn(Optional.empty());
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void client(String... scopes) {
        TenantContext.set(TENANT, TenantContext.API_CLIENT, null, "client:cl_test");
        TenantContext.setApiClient("cl_test", new LinkedHashSet<>(Arrays.asList(scopes)));
    }

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, Object> decided(String status, String decision) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("party", "customer");
        d.put("decision", decision);
        d.put("reason", null);
        d.put("comment", "Looks right");
        d.put("reviewerUserId", 4602L);
        d.put("reviewer", "Somebody");
        d.put("decidedAt", LocalDateTime.parse("2026-10-06T10:00:00"));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("jobQueueId", RUN);
        summary.put("reviewStatus", status);
        summary.put("required", Collections.singletonList("customer"));
        summary.put("decisions", Collections.singletonList(d));
        summary.put("decidedAt", LocalDateTime.parse("2026-10-06T10:00:00"));
        summary.put("rerunJobQueueId", null);
        return summary;
    }

    @Test
    void readingNeedsRunsReadAndDecidingNeedsReviewsWrite() {
        this.client("runs:read");
        assertThat(this.customerReviews.decide(String.valueOf(RUN), json("{\"decision\":\"approved\"}"), "review-key-0001").status)
            .isEqualTo(403);
        this.client("reviews:write");
        assertThat(this.customerReviews.get(String.valueOf(RUN)).status).isEqualTo(403);
        verify(this.service, never()).decide(any(), any());
    }

    @Test
    void aRequestThatDoesNotSayWhatItDecidesIs422WithTheField() {
        String[][] cases = {
            {"{}", "decision"},
            {"{\"decision\":\"maybe\"}", "decision"},
            {"{\"decision\":\"rejected\"}", "reason"},
            {"{\"decision\":\"approved\",\"rerun\":true}", "rerun"},
            {"{\"decision\":\"approved\",\"comment\":7}", "comment"},
        };
        int i = 0;
        for (String[] c : cases) {
            CustomerAnswer answer = this.customerReviews.decide(String.valueOf(RUN), json(c[0]), "review-key-bad" + (i++));
            assertThat(answer.status).as(c[0]).isEqualTo(422);
            assertThat(answer.body.toString()).as(c[0]).contains("path=" + c[1]);
        }
        assertThat(this.customerReviews.decide(String.valueOf(RUN), json("[1]"), "review-key-array").status).isEqualTo(400);
        assertThat(this.customerReviews.decide(String.valueOf(RUN), json("{\"decision\":\"approved\"}"), null).status).isEqualTo(400);
        verify(this.service, never()).decide(any(), any());
    }

    @Test
    void anotherWorkspacesRunIsNotFound() {
        assertThat(this.customerReviews.get("404").status).isEqualTo(404);
        assertThat(this.customerReviews.decide("404", json("{\"decision\":\"approved\"}"), "review-key-404").status).isEqualTo(404);
        verify(this.service, never()).decide(any(), any());
    }

    @Test
    void anApprovalIsTheCustomersDecisionThroughTheConsolesRulesAndReplaysOnce() {
        when(this.service.decide(any(), eq(ReviewParty.CUSTOMER))).thenReturn(new ResponseDto(SUCCESS, "recorded", decided("APPROVED", "APPROVED")));
        CustomerAnswer first = this.customerReviews.decide(String.valueOf(RUN), json("{\"decision\":\"Approved\",\"comment\":\"Looks right\"}"),
            "review-key-0001");
        CustomerAnswer again = this.customerReviews.decide(String.valueOf(RUN), json("{\"decision\":\"Approved\",\"comment\":\"Looks right\"}"),
            "review-key-0001");

        assertThat(first.status).isEqualTo(200);
        assertThat(first.body).containsEntry("status", "approved").containsEntry("decidedAt", "2026-10-06T15:00:00Z");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> decisions = (List<Map<String, Object>>) first.body.get("decisions");
        assertThat(decisions.get(0)).containsEntry("party", "customer").containsEntry("decision", "approved")
            .containsEntry("decidedAt", "2026-10-06T15:00:00Z").doesNotContainKeys("reviewerUserId", "reviewer");
        assertThat(again.replayed).isTrue();
        assertThat(again.body).isEqualTo(first.body);
        ArgumentCaptor<RunReviewRequest> asked = ArgumentCaptor.forClass(RunReviewRequest.class);
        verify(this.service, times(1)).decide(asked.capture(), eq(ReviewParty.CUSTOMER));
        assertThat(asked.getValue().getJobQueueId()).isEqualTo(RUN);
        assertThat(asked.getValue().getDecision()).isEqualTo("APPROVED");
        assertThat(asked.getValue().getParty()).as("the party is the caller's, never the request's").isNull();
    }

    @Test
    void whatTheRunsStateRefusesIsA409InTheConsolesWords() {
        when(this.service.decide(any(), eq(ReviewParty.CUSTOMER))).thenReturn(new ResponseDto(ERROR,
            "This run's pipeline does not ask for a customer review."));
        CustomerAnswer answer = this.customerReviews.decide(String.valueOf(RUN), json("{\"decision\":\"approved\"}"), "review-key-409");
        assertThat(answer.status).isEqualTo(409);
        assertThat(answer.body).containsEntry("type", "/problems/review-not-open")
            .containsEntry("detail", "This run's pipeline does not ask for a customer review.");
    }

    @Test
    void aRejectionWithRerunSaysWhatTheRerunDid() {
        Map<String, Object> summary = decided("REJECTED", "REJECTED");
        summary.put("rerunJobQueueId", 9200L);
        Map<String, Object> rerun = new LinkedHashMap<>();
        rerun.put("queued", true);
        rerun.put("jobQueueId", 9200L);
        rerun.put("message", "Run 9200 runs the pipeline again with the same intake.");
        summary.put("rerun", rerun);
        when(this.service.decide(any(), eq(ReviewParty.CUSTOMER))).thenReturn(new ResponseDto(SUCCESS, "recorded", summary));
        CustomerAnswer answer = this.customerReviews.decide(String.valueOf(RUN), json("{\"decision\":\"rejected\",\"reason\":\"Totals are off\","
            + "\"rerun\":true}"), "review-key-rerun");
        assertThat(answer.body).containsEntry("status", "rejected").containsEntry("rerunRunId", "9200");
        @SuppressWarnings("unchecked")
        Map<String, Object> rerunView = (Map<String, Object>) answer.body.get("rerun");
        assertThat(rerunView).containsEntry("queued", true).containsEntry("runId", "9200");
    }
}

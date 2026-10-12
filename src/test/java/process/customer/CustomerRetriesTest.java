package process.customer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.pipeline.RunRetries;
import process.security.TenantContext;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.lang.reflect.Constructor;

/**
 * MIG-345: POST /v1/runs/{runId}/retry -- runs:write; the Idempotency-Key required and the same key answering the first
 * answer again without retrying twice; 202 with the Run and Location; 409 problem+json whose type is the reason; a body
 * that does not say failed_step or start is 422; another workspace's run (or none) is 404 and nothing is retried.
 */
class CustomerRetriesTest {

    static final long TENANT = 2961L;
    static final long RUN = 9345L;
    static final long OTHERS = 9999L;

    private final CustomerRuns runs = mock(CustomerRuns.class);
    private final CustomerRunStore store = mock(CustomerRunStore.class);
    private final RunRetries retries = mock(RunRetries.class);
    private final CustomerRetries customerRetries = new CustomerRetries(this.runs, this.store, this.retries,
        new Idempotency(new MemoryReceipts()));
    private JobQueue run;
    private SourceJob job;

    @BeforeEach
    void asAClient() {
        this.client("runs:read", "runs:write");
        CustomerRunStore.Row failed = CustomerRunsTest.row(RUN, "Failed");
        failed.tenantId = TENANT;
        this.run = new JobQueue();
        this.run.setJobQueueId(RUN);
        this.run.setJobStatus(JobStatus.Failed);
        this.job = new SourceJob();
        when(this.runs.found(String.valueOf(RUN))).thenReturn(Optional.of(new CustomerRuns.Found(failed, this.run, this.job)));
        // Another workspace's run is not this workspace's: found() answers nothing for it, as for a run that does not exist.
        when(this.runs.found(String.valueOf(OTHERS))).thenReturn(Optional.empty());
        when(this.runs.reviewSummary(any())).thenReturn(Collections.singletonMap("reviewStatus", "NOT_REQUIRED"));
        CustomerRunStore.Row queued = CustomerRunsTest.row(RUN, "Queue");
        queued.tenantId = TENANT;
        queued.attempt = 2;
        queued.endedAt = null;
        queued.retryMode = "resume";
        queued.retryFromStep = "check";
        queued.retryPreviousAttempt = 1;
        queued.retryReusedSteps = 2;
        queued.retryVia = "api";
        when(this.store.find(TENANT, RUN)).thenReturn(Optional.of(queued));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void client(String... scopes) {
        TenantContext.set(TENANT, TenantContext.API_CLIENT, null, "client:cl_meridian");
        TenantContext.setApiClient("cl_meridian", new LinkedHashSet<>(Arrays.asList(scopes)));
    }

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private RunRetries.Outcome queued() {
        return outcome(null, null);
    }

    private static RunRetries.Outcome outcome(String kind, String reason) {
        try {
            Constructor<RunRetries.Outcome> made = RunRetries.Outcome.class.getDeclaredConstructor(RunRetries.Plan.class,
                String.class, String.class, String.class);
            made.setAccessible(true);
            return made.newInstance(new RunRetries.Plan(), "resume", kind, reason);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void aRetryIs202WithTheRunItsNewAttemptAndLocation() {
        when(this.retries.retry(eq(this.run), eq(this.job), eq(false), any())).thenReturn(this.queued());

        CustomerAnswer answer = this.customerRetries.retry(String.valueOf(RUN), null, "retry-key-0001");

        assertThat(answer.status).isEqualTo(202);
        assertThat(answer.location).isEqualTo("/v1/runs/9345");
        assertThat(answer.body).containsEntry("id", "9345").containsEntry("status", "queued").containsEntry("attempt", 2);
        @SuppressWarnings("unchecked")
        Map<String, Object> retry = (Map<String, Object>) answer.body.get("retry");
        assertThat(retry).containsEntry("from", "failed_step").containsEntry("fromStep", "check").containsEntry("previousAttempt", 1)
            .containsEntry("reusedSteps", 2).containsEntry("requestedThrough", "api");
    }

    @Test
    void fromStartRunsEveryStepAgainAndTheClientIsWhoAsked() {
        when(this.retries.retry(eq(this.run), eq(this.job), eq(true), any())).thenReturn(this.queued());

        assertThat(this.customerRetries.retry(String.valueOf(RUN), json("{\"from\":\"start\"}"), "retry-key-0002").status).isEqualTo(202);
        verify(this.retries).retry(eq(this.run), eq(this.job), eq(true), any(RunRetries.Actor.class));
    }

    @Test
    void theSameKeyAnswersTheFirstAnswerAgainAndRetriesOnce() {
        when(this.retries.retry(eq(this.run), eq(this.job), eq(false), any())).thenReturn(this.queued());

        CustomerAnswer first = this.customerRetries.retry(String.valueOf(RUN), null, "retry-key-same");
        CustomerAnswer again = this.customerRetries.retry(String.valueOf(RUN), null, "retry-key-same");

        assertThat(first.status).isEqualTo(202);
        assertThat(again.status).isEqualTo(202);
        assertThat(again.replayed).isTrue();
        assertThat(again.location).isEqualTo("/v1/runs/9345");
        assertThat(again.body.get("attempt")).isEqualTo(first.body.get("attempt"));
        verify(this.retries, times(1)).retry(any(), any(), anyBoolean(), any());
        // The same key with another request is a conflict, not a second retry.
        assertThat(this.customerRetries.retry(String.valueOf(RUN), json("{\"from\":\"start\"}"), "retry-key-same").body.get("type"))
            .isEqualTo("/problems/idempotency-conflict");
        verify(this.retries, times(1)).retry(any(), any(), anyBoolean(), any());
    }

    @Test
    void anIdempotencyKeyIsRequired() {
        CustomerAnswer answer = this.customerRetries.retry(String.valueOf(RUN), null, null);
        assertThat(answer.status).isEqualTo(400);
        verify(this.retries, never()).retry(any(), any(), anyBoolean(), any());
    }

    @Test
    void aRunThatIsNotRetryableIs409WhoseTypeIsTheReason() {
        String[][] reasons = {
            {RunRetries.NOT_FAILED, "Run 9345 is running, not failed: only a failed run is retried."},
            {RunRetries.CHANGED, "The pipeline changed before the failed step <check> since this run (version 1, now 2): step 2 <shape>'s settings."},
            {RunRetries.INPUT_CHANGED, "The file intake/orders.csv that step <read> read has changed (sha256 ...)."},
            {RunRetries.EXPIRED, "The kept output of step <shape> expired at 2026-10-06T10:00:00Z."},
            {RunRetries.IN_FLIGHT, "Another run of this pipeline is still in flight."},
        };
        int i = 0;
        for (String[] reason : reasons) {
            when(this.retries.retry(any(), any(), anyBoolean(), any())).thenReturn(outcome(reason[0], reason[1]));
            CustomerAnswer answer = this.customerRetries.retry(String.valueOf(RUN), null, "retry-key-409-" + (i++));
            assertThat(answer.status).as(reason[0]).isEqualTo(409);
            assertThat(answer.body).as(reason[0]).containsEntry("type", "/problems/" + reason[0]).containsEntry("detail", reason[1])
                .containsEntry("instance", "/v1/runs/9345/retry");
        }
    }

    @Test
    void anotherWorkspacesRunIs404AndNothingIsRetried() {
        CustomerAnswer answer = this.customerRetries.retry(String.valueOf(OTHERS), null, "retry-key-other");
        assertThat(answer.status).isEqualTo(404);
        assertThat(answer.body).containsEntry("detail", "No such run.");
        assertThat(this.customerRetries.retry("not-a-run", null, "retry-key-other2").status).isEqualTo(404);
        verify(this.retries, never()).retry(any(), any(), anyBoolean(), any());
    }

    @Test
    void runsWriteIsNeededAndTheBodySaysFailedStepOrStart() {
        this.client("runs:read");
        assertThat(this.customerRetries.retry(String.valueOf(RUN), null, "retry-key-scope").status).isEqualTo(403);
        this.client("runs:write");
        CustomerAnswer bad = this.customerRetries.retry(String.valueOf(RUN), json("{\"from\":\"step-2\"}"), "retry-key-bad1");
        assertThat(bad.status).isEqualTo(422);
        assertThat(bad.body.toString()).contains("path=from");
        assertThat(this.customerRetries.retry(String.valueOf(RUN), json("[1]"), "retry-key-bad2").status).isEqualTo(400);
        assertThat(this.customerRetries.retry(String.valueOf(RUN), json("{oops"), "retry-key-bad3").status).isEqualTo(400);
        verify(this.retries, never()).retry(any(), any(), anyBoolean(), any());
    }
}

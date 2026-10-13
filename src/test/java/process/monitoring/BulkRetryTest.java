package process.monitoring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import process.model.dto.ResponseDto;
import process.pipeline.StepTimelineService;
import process.security.TenantContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MIG-355: the bulk retry -- its cap, its rate, its dry run, and a per-run answer through the single retry's path. */
class BulkRetryTest {

    final StepTimelineService runs = mock(StepTimelineService.class);
    final AtomicLong now = new AtomicLong(1_000_000L);
    final BulkRetry bulk = new BulkRetry(this.runs, this.now::get);

    @BeforeEach
    void caller() {
        TenantContext.set(2960L, "TENANT_ADMIN", 4640L, "admin@riverside.example");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    static Map<String, Object> body(Object ids, Object... more) {
        Map<String, Object> body = new HashMap<>();
        body.put("jobQueueIds", ids);
        for (int i = 0; i + 1 < more.length; i += 2) {
            body.put(String.valueOf(more[i]), more[i + 1]);
        }
        return body;
    }

    static Map<String, Object> plan(long job, boolean resumable, String kind, String refusal) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("jobId", job);
        view.put("resumable", resumable);
        view.put("refusalKind", kind);
        view.put("refusal", refusal);
        view.put("fromKey", "report");
        return view;
    }

    @Test
    void atMostFiftyDistinctRunsAndAtLeastOne() {
        List<Long> fiftyOne = new ArrayList<>();
        for (long i = 1; i <= 51; i++) {
            fiftyOne.add(i);
        }
        assertThatThrownBy(() -> this.bulk.retry(body(fiftyOne))).isInstanceOf(AlertRules.Refused.class).hasMessage("At most 50 runs at once; 51 were listed.");
        assertThatThrownBy(() -> this.bulk.retry(body(new ArrayList<>()))).hasMessage("List the runs to retry (jobQueueIds).");
        assertThatThrownBy(() -> this.bulk.retry(body(Arrays.asList(1), "from", "middle"))).hasMessage("from is failed_step or start.");
        assertThatThrownBy(() -> this.bulk.retry(body(Arrays.asList(1), "bulkId", "x"))).hasMessage("bulkId is 8 to 64 letters, digits and '-'.");
        verify(this.runs, never()).retry(anyLong(), anyString(), anyString());
    }

    @Test
    void eachRunIsRetriedOnItsOwnAndAnsweredOnItsOwn() {
        when(this.runs.retry(eq(1L), eq("failed_step"), eq("bulk-test-0001")))
            .thenReturn(new ResponseDto("SUCCESS", "Run 1 queued to resume from step 2 <report> as attempt 2, reusing 1 step(s).", plan(10, true, null, null)));
        when(this.runs.retry(eq(2L), eq("failed_step"), eq("bulk-test-0001")))
            .thenReturn(new ResponseDto("ERROR", "Another run of this pipeline is still in flight", plan(10, false, "run-in-flight", "Another run is in flight.")));
        when(this.runs.retry(eq(3L), eq("failed_step"), eq("bulk-test-0001"))).thenThrow(new IllegalStateException("boom"));
        ResponseDto answer = this.bulk.retry(body(Arrays.asList(1, 2, 2, 3), "bulkId", "bulk-test-0001"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) answer.getData();
        assertThat(answer.getMessage()).isEqualTo("1 retried, 1 skipped, 1 failed.");
        assertThat(data.get("requested")).isEqualTo(3);
        assertThat(data.get("bulkId")).isEqualTo("bulk-test-0001");
        assertThat((List<?>) data.get("done")).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> skipped = ((List<Map<String, Object>>) data.get("skipped")).get(0);
        assertThat(skipped.get("reason")).isEqualTo("run-in-flight");
        assertThat(skipped.get("link")).isEqualTo("/pipelines/schedules/10/runs/2");
        assertThat((List<?>) data.get("failed")).hasSize(1);
    }

    @Test
    void aDryRunOnlyAsksForThePlans() {
        when(this.runs.retryPlan(1L)).thenReturn(new ResponseDto("SUCCESS", "Resumable.", plan(10, true, null, null)));
        when(this.runs.retryPlan(2L)).thenReturn(new ResponseDto("SUCCESS", "Changed.", plan(10, false, "pipeline-changed", "Step 1 changed.")));
        ResponseDto answer = this.bulk.retry(body(Arrays.asList(1, 2), "dryRun", true));
        assertThat(answer.getMessage()).isEqualTo("1 would be retried, 1 skipped.");
        verify(this.runs, never()).retry(anyLong(), anyString(), anyString());
    }

    @Test
    void tenBulkRetriesAMinuteThenA429() {
        when(this.runs.retryPlan(1L)).thenReturn(new ResponseDto("SUCCESS", "Resumable.", plan(10, true, null, null)));
        for (int i = 0; i < BulkRetry.RATE_CAP; i++) {
            this.bulk.retry(body(Arrays.asList(1), "dryRun", true));
        }
        assertThatThrownBy(() -> this.bulk.retry(body(Arrays.asList(1), "dryRun", true)))
            .matches(e -> ((AlertRules.Refused) e).getStatus() == HttpStatus.TOO_MANY_REQUESTS);
        this.now.addAndGet(60_001L);
        assertThat(this.bulk.retry(body(Arrays.asList(1), "dryRun", true)).getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void aCallerWithNoWorkspaceIsRefused() {
        TenantContext.set(null, "PLATFORM_ADMIN", 4639L, "qa-platform-admin@platform.local");
        assertThatThrownBy(() -> this.bulk.retry(body(Arrays.asList(1)))).hasMessageContaining("not attached to one");
    }
}

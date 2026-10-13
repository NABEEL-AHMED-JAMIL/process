package process.pipeline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import process.model.dto.SourceTaskDto;
import process.model.service.impl.QueryService;
import process.security.TenantContext;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * UX review 2026-10-13 (Pipelines list): each row says how many steps and outputs its pipeline has, the sensitivity it
 * says, whether its results wait for a review, and its newest run -- for the whole page in two queries, from the latest
 * definition and the Task Registry's kinds.
 */
class PipelineSummariesTest {

    private final PipelineDefinitionStore store = mock(PipelineDefinitionStore.class);
    private final QueryService queries = mock(QueryService.class);
    private final PipelineSummaries summaries = new PipelineSummaries(this.store, new AllTasks().tasks(), this.queries);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static SourceTaskDto row(long id) {
        SourceTaskDto row = new SourceTaskDto();
        row.setTaskDetailId(id);
        return row;
    }

    private static PipelineDefinitionStore.TaskDefinition definition(long id, String json) {
        PipelineDefinitionStore.TaskDefinition task = new PipelineDefinitionStore.TaskDefinition();
        task.taskDetailId = id;
        task.taskName = "P" + id;
        task.stored = new PipelineDefinitionStore.Stored();
        task.stored.id = id * 10;
        task.stored.json = json;
        return task;
    }

    @Test
    void stepsOutputsSensitivityReviewAndTheNewestRunForThePageInTwoQueries() {
        TenantContext.set(2960L, "TENANT_ADMIN", 4640L, "admin@riverside.test");
        when(this.store.latestForTasks(eq(2960L), anyCollection())).thenReturn(Arrays.asList(
            definition(1, "{\"version\":1,\"settings\":{\"sensitivity\":\"sensitive\",\"review\":{\"required\":[\"internal\"]}},\"steps\":["
                + "{\"key\":\"r\",\"task\":\"read_api\",\"config\":{\"requestId\":1}},"
                + "{\"key\":\"pdf\",\"task\":\"render_pdf\",\"config\":{}},"
                + "{\"key\":\"up\",\"task\":\"upload_bucket\",\"config\":{\"bucket\":\"b\",\"key\":\"k\"}}]}"),
            definition(2, "{\"version\":1,\"steps\":[{\"key\":\"s\",\"task\":\"sample\",\"config\":{\"rows\":[]}},"
                + "{\"key\":\"ok\",\"task\":\"approval\",\"config\":{}}]}"),
            definition(4, "{\"version\":1,\"steps\":[{\"key\":\"legacy\",\"task\":\"legacy\",\"config\":{\"pipelineId\":\"F1\"}}]}"),
            definition(5, "{broken")));
        when(this.queries.lastRunsOfTasks(any())).thenReturn("last runs");
        Timestamp at = Timestamp.from(Instant.parse("2026-10-12T22:27:31.500Z"));
        List<Object[]> runs = new ArrayList<>();
        runs.add(new Object[] {1L, 8214L, 2907L, "Completed", at});
        runs.add(new Object[] {2L, 8301L, 2908L, "RUNNING", null});
        when(this.queries.executeQuery("last runs")).thenReturn(runs);

        List<SourceTaskDto> page = Arrays.asList(row(1), row(2), row(3), row(4), row(5));
        this.summaries.attach(page);

        SourceTaskDto first = page.get(0);
        assertThat(first.getStepCount()).isEqualTo(3);
        assertThat(first.getOutputCount()).as("Render PDF and Upload to bucket are Outputs").isEqualTo(2);
        assertThat(first.getSensitivity()).isEqualTo("sensitive");
        assertThat(first.getReviewRequired()).isTrue();
        assertThat(first.getLastRun()).containsEntry("jobQueueId", 8214L).containsEntry("jobId", 2907L).containsEntry("jobStatus", "Completed")
            .containsEntry("at", LocalDateTime.of(2026, 10, 12, 17, 27, 31));

        SourceTaskDto second = page.get(1);
        assertThat(second.getStepCount()).isEqualTo(2);
        assertThat(second.getOutputCount()).isZero();
        assertThat(second.getSensitivity()).as("not said").isNull();
        assertThat(second.getReviewRequired()).as("an approval step waits for a person").isTrue();
        assertThat(second.getLastRun()).containsEntry("jobStatus", "Running").containsEntry("at", null);

        for (SourceTaskDto legacy : Arrays.asList(page.get(2), page.get(3), page.get(4))) {
            assertThat(legacy.getStepCount()).as("legacy, or no definition read").isZero();
            assertThat(legacy.getOutputCount()).isZero();
            assertThat(legacy.getReviewRequired()).isFalse();
            assertThat(legacy.getLastRun()).isNull();
        }
        verify(this.store, times(1)).latestForTasks(eq(2960L), anyCollection());
        verify(this.queries, times(1)).executeQuery(anyString());
    }

    @Test
    void anEmptyPageAsksNothing() {
        this.summaries.attach(Collections.emptyList());
        this.summaries.attach(null);
        verifyNoInteractions(this.store, this.queries);
    }
}

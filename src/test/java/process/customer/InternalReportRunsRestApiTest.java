package process.customer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import process.pipeline.Dataset;
import process.pipeline.DatasetStore;
import process.pipeline.FileAccessLog;
import process.pipeline.RunResults;
import process.pipeline.SensitiveFiles;
import process.pipeline.StepStore;
import process.security.TenantContext;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Report Studio's scheduled reports: media-service asks Core about a workspace's pipeline and reads a completed run's rows,
 * with the internal token, as the workspace it names. Without the token nothing is read; another workspace's pipeline or run
 * is a 404; the rows are one JSON array -- the final output or a named step's -- with their count, the run's sensitivity and
 * the pipeline's name up front, and a run that did not complete, an expired output or rows over maxBytes say so.
 */
class InternalReportRunsRestApiTest {

    private static final long RIVERSIDE = 1006L;
    private static final long MERIDIAN = 1007L;
    private static final long JOB = 5100L;
    private static final long RUN = 7400L;
    private static final String TOKEN = "internal-secret";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> ROWS = new TypeReference<List<Map<String, Object>>>() {};

    private final PipelineCatalogue catalogue = mock(PipelineCatalogue.class);
    private final CustomerRunStore store = mock(CustomerRunStore.class);
    private final StepStore steps = mock(StepStore.class);
    private final Memory datasets = new Memory();
    private final RunResults results = new RunResults(this.steps, this.datasets);
    private final SensitiveFiles sensitiveFiles = mock(SensitiveFiles.class);
    private final FileAccessLog accessLog = mock(FileAccessLog.class);
    private final InternalReportRunsRestApi api = new InternalReportRunsRestApi(this.catalogue, this.store, this.results, this.datasets,
        this.sensitiveFiles, this.accessLog, TOKEN);

    /** Run datasets in memory, as the file store gives them back. */
    private static final class Memory implements DatasetStore {
        final Map<String, Dataset> kept = new HashMap<>();

        @Override
        public void write(String storageKey, Dataset dataset) {
            this.kept.put(storageKey, dataset);
        }

        @Override
        public Dataset read(String storageKey) throws Exception {
            Dataset found = this.kept.get(storageKey);
            if (found == null) {
                throw new FileNotFoundException(storageKey);
            }
            return found;
        }

        @Override
        public void delete(String storageKey) {
            this.kept.remove(storageKey);
        }
    }

    @BeforeEach
    void riversidesPipelineAndRun() {
        when(this.catalogue.find(eq(RIVERSIDE), eq(String.valueOf(JOB)))).thenReturn(Optional.of(
            new PipelineCatalogue.Entry(JOB, RIVERSIDE, "Wound care & visits", "Active", "Completed", "WOUNDS", null)));
        when(this.catalogue.find(eq(MERIDIAN), anyString())).thenReturn(Optional.empty());
        when(this.store.find(RIVERSIDE, RUN)).thenReturn(Optional.of(row(RUN, "Completed")));
        when(this.store.find(eq(MERIDIAN), anyLong())).thenReturn(Optional.empty());
        // Two steps of the latest attempt: read (kept its output) and summarize (the final output).
        when(this.steps.stepsOfRun(RUN)).thenReturn(Arrays.asList(step(11, 0, "read"), step(12, 1, "summarize")));
        when(this.steps.keptOutputsOf(any())).thenAnswer(asked -> {
            Map<Long, StepStore.KeptOutput> out = new HashMap<>();
            for (Object id : (Collection<?>) asked.getArgument(0)) {
                long step = (Long) id;
                out.put(step, new StepStore.KeptOutput(step, "datasets/7400/1/s" + step + "/output.json", 2L,
                    Instant.now().plusSeconds(3600), false));
            }
            return out;
        });
        this.datasets.write("datasets/7400/1/s11/output.json", dataset(Arrays.asList("mrn"),
            Arrays.asList(row("mrn", "M-1"), row("mrn", "M-2"), row("mrn", "M-3"))));
        this.datasets.write("datasets/7400/1/s12/output.json", dataset(Arrays.asList("mrn", "visits"),
            Arrays.asList(row("mrn", "M-1", "visits", 3), row("mrn", "M-2", "visits", 1))));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private static Dataset dataset(List<String> columns, List<Map<String, Object>> rows) {
        return new Dataset(columns, new ArrayList<>(rows));
    }

    private static StepStore.StepRow step(long id, int index, String key) {
        StepStore.StepRow step = new StepStore.StepRow();
        step.stepExecutionId = id;
        step.jobQueueId = RUN;
        step.attempt = 1;
        step.stepIndex = index;
        step.stepKey = key;
        step.status = "Completed";
        return step;
    }

    private static CustomerRunStore.Row row(long runId, String status) {
        CustomerRunStore.Row row = new CustomerRunStore.Row();
        row.runId = runId;
        row.jobId = JOB;
        row.tenantId = RIVERSIDE;
        row.jobStatus = status;
        row.startedAt = Instant.parse("2026-11-02T14:00:05Z");
        row.endedAt = "Completed".equals(status) || "Failed".equals(status) ? Instant.parse("2026-11-02T14:01:05.250Z") : null;
        row.attempt = 1;
        return row;
    }

    private static String body(ResponseEntity<StreamingResponseBody> answer) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (answer.getBody() != null) {
            answer.getBody().writeTo(out);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------------------------------------------------ the token

    @Test
    void withoutTheInternalTokenNothingIsRead() throws Exception {
        for (String presented : Arrays.asList(null, "guess", "")) {
            assertThat(this.api.pipelineRun(presented, RIVERSIDE, JOB).getStatusCode().value()).isEqualTo(401);
            ResponseEntity<StreamingResponseBody> rows = this.api.runRows(presented, RIVERSIDE, RUN, null, null);
            assertThat(rows.getStatusCode().value()).isEqualTo(401);
            assertThat(body(rows)).doesNotContain("M-1");
        }
        verifyNoInteractions(this.catalogue, this.store, this.steps, this.accessLog);
    }

    @Test
    void anUnconfiguredTokenAdmitsNobody() {
        InternalReportRunsRestApi unconfigured = new InternalReportRunsRestApi(this.catalogue, this.store, this.results, this.datasets,
            this.sensitiveFiles, this.accessLog, "");
        assertThat(unconfigured.pipelineRun("", RIVERSIDE, JOB).getStatusCode().value()).isEqualTo(401);
        assertThat(unconfigured.runRows("", RIVERSIDE, RUN, null, null).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void aWorkspaceIsAlwaysNamed() {
        for (Long none : Arrays.asList(null, 0L, -1L)) {
            assertThat(this.api.pipelineRun(TOKEN, none, JOB).getStatusCode().value()).isEqualTo(400);
            assertThat(this.api.runRows(TOKEN, none, RUN, null, null).getStatusCode().value()).isEqualTo(400);
        }
        assertThat(this.api.pipelineRun(TOKEN, RIVERSIDE, null).getStatusCode().value()).isEqualTo(400);
        assertThat(this.api.runRows(TOKEN, RIVERSIDE, null, null, null).getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(this.catalogue, this.store);
    }

    // ------------------------------------------------------------------------------------------------------- pipelineRun

    @Test
    @SuppressWarnings("unchecked")
    void aPipelinesRunsAreItsRunningOneItsLatestFinishedAndItsLastSuccess() {
        when(this.store.page(eq(RIVERSIDE), isNull(), eq(1), eq(JOB), eq(InternalReportRunsRestApi.IN_FLIGHT), isNull(), isNull()))
            .thenReturn(Collections.singletonList(row(7402L, "Running")));
        when(this.store.page(eq(RIVERSIDE), isNull(), eq(1), eq(JOB), eq(InternalReportRunsRestApi.FINISHED), isNull(), isNull()))
            .thenReturn(Collections.singletonList(row(7401L, "Failed")));
        when(this.store.page(eq(RIVERSIDE), isNull(), eq(1), eq(JOB), eq(Collections.singletonList("Completed")), isNull(), isNull()))
            .thenReturn(Collections.singletonList(row(RUN, "Completed")));

        ResponseEntity<?> answer = this.api.pipelineRun(TOKEN, RIVERSIDE, JOB);

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = (Map<String, Object>) answer.getBody();
        assertThat(body).containsOnlyKeys("pipelineId", "pipelineName", "sensitive", "running", "latest", "lastSucceeded");
        assertThat(body).containsEntry("pipelineId", JOB).containsEntry("pipelineName", "Wound care & visits").containsEntry("sensitive", false);
        assertThat((Map<String, Object>) body.get("running")).containsEntry("jobQueueId", 7402L).containsEntry("status", "running")
            .containsEntry("startedAt", "2026-11-02T14:00:05Z");
        assertThat((Map<String, Object>) body.get("latest")).containsEntry("jobQueueId", 7401L).containsEntry("status", "failed")
            .containsEntry("startedAt", "2026-11-02T14:00:05Z").containsEntry("endedAt", "2026-11-02T14:01:05.250Z");
        assertThat((Map<String, Object>) body.get("lastSucceeded")).containsOnlyKeys("jobQueueId", "endedAt").containsEntry("jobQueueId", RUN)
            .containsEntry("endedAt", "2026-11-02T14:01:05.250Z");
        assertThat(TenantContext.getTenantId()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aPipelineThatNeverRanHasNoRunsAndASucceededLatestIsItsLastSuccess() {
        when(this.store.page(eq(RIVERSIDE), isNull(), eq(1), eq(JOB), any(), isNull(), isNull())).thenReturn(Collections.emptyList());
        Map<String, Object> never = (Map<String, Object>) this.api.pipelineRun(TOKEN, RIVERSIDE, JOB).getBody();
        assertThat(never).containsEntry("running", null).containsEntry("latest", null).containsEntry("lastSucceeded", null);

        when(this.store.page(eq(RIVERSIDE), isNull(), eq(1), eq(JOB), eq(InternalReportRunsRestApi.FINISHED), isNull(), isNull()))
            .thenReturn(Collections.singletonList(row(RUN, "Completed")));
        Map<String, Object> once = (Map<String, Object>) this.api.pipelineRun(TOKEN, RIVERSIDE, JOB).getBody();
        assertThat((Map<String, Object>) once.get("latest")).containsEntry("status", "completed");
        assertThat((Map<String, Object>) once.get("lastSucceeded")).containsEntry("jobQueueId", RUN);
    }

    @Test
    void anotherWorkspacesPipelineIsNotFound() {
        assertThat(this.api.pipelineRun(TOKEN, MERIDIAN, JOB).getStatusCode().value()).isEqualTo(404);
        verify(this.store, never()).page(anyLong(), any(), anyInt(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------------------------------------------------------ runRows

    @Test
    void aCompletedRunsFinalRowsAreOneJsonArrayWithTheirCountSensitivityAndPipeline() throws Exception {
        when(this.sensitiveFiles.sensitiveRun(RUN)).thenReturn(true);

        ResponseEntity<StreamingResponseBody> answer = this.api.runRows(TOKEN, RIVERSIDE, RUN, null, null);

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getHeaders().getContentType().toString()).isEqualTo("application/json");
        assertThat(answer.getHeaders().getFirst("X-Rows")).isEqualTo("2");
        assertThat(answer.getHeaders().getFirst("X-Run-Sensitive")).isEqualTo("true");
        assertThat(answer.getHeaders().getFirst("X-Pipeline-Name")).isEqualTo("Wound%20care%20%26%20visits");
        String json = body(answer);
        assertThat(answer.getHeaders().getContentLength()).isEqualTo(json.getBytes(StandardCharsets.UTF_8).length);
        assertThat(JSON.readValue(json, ROWS)).containsExactly(row("mrn", "M-1", "visits", 3), row("mrn", "M-2", "visits", 1));
        verify(this.accessLog).serviceRunRows(RIVERSIDE, "media-service", RUN, "run-7400-summarize.json", true, 200, null);
    }

    @Test
    void aNamedStepsRowsAreItsOwn() throws Exception {
        ResponseEntity<StreamingResponseBody> answer = this.api.runRows(TOKEN, RIVERSIDE, RUN, "read", null);

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getHeaders().getFirst("X-Rows")).isEqualTo("3");
        assertThat(answer.getHeaders().getFirst("X-Run-Sensitive")).isEqualTo("false");
        assertThat(JSON.readValue(body(answer), ROWS)).hasSize(3);

        assertThat(this.api.runRows(TOKEN, RIVERSIDE, RUN, "nope", null).getStatusCode().value()).isEqualTo(404);
        assertThat(this.api.runRows(TOKEN, RIVERSIDE, RUN, "../x", null).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void anotherWorkspacesRunIsNotFoundAndNothingOfItIsRead() throws Exception {
        ResponseEntity<StreamingResponseBody> answer = this.api.runRows(TOKEN, MERIDIAN, RUN, null, null);

        assertThat(answer.getStatusCode().value()).isEqualTo(404);
        assertThat(body(answer)).doesNotContain("M-1");
        verify(this.steps, never()).stepsOfRun(anyLong());
        verify(this.accessLog).serviceRunRows(eq(MERIDIAN), eq("media-service"), isNull(), isNull(), eq(false), eq(404), anyString());
    }

    @Test
    void aRunThatDidNotCompleteIs409() throws Exception {
        for (String status : Arrays.asList("Queue", "Running", "Failed", "Interrupt", "Skip")) {
            when(this.store.find(RIVERSIDE, RUN)).thenReturn(Optional.of(row(RUN, status)));
            ResponseEntity<StreamingResponseBody> answer = this.api.runRows(TOKEN, RIVERSIDE, RUN, null, null);
            assertThat(answer.getStatusCode().value()).as(status).isEqualTo(409);
            assertThat(answer.getHeaders().getContentLength()).isEqualTo(body(answer).getBytes(StandardCharsets.UTF_8).length);
        }
        verify(this.steps, never()).stepsOfRun(anyLong());
    }

    @Test
    void anOutputPastItsRetentionIsGone() {
        doAnswer(asked -> {
            Map<Long, StepStore.KeptOutput> out = new HashMap<>();
            for (Object id : (Collection<?>) asked.getArgument(0)) {
                out.put((Long) id, new StepStore.KeptOutput((Long) id, "datasets/7400/1/s12/output.json", 2L, Instant.now().minusSeconds(60), false));
            }
            return out;
        }).when(this.steps).keptOutputsOf(any());
        assertThat(this.api.runRows(TOKEN, RIVERSIDE, RUN, null, null).getStatusCode().value()).isEqualTo(410);
        verify(this.accessLog).serviceRunRows(eq(RIVERSIDE), eq("media-service"), eq(RUN), anyString(), eq(false), eq(410), anyString());
    }

    @Test
    void anOutputWhoseContentIsGoneIsGoneToo() {
        this.datasets.delete("datasets/7400/1/s12/output.json");
        assertThat(this.api.runRows(TOKEN, RIVERSIDE, RUN, null, null).getStatusCode().value()).isEqualTo(410);
    }

    @Test
    void aRunWithNoKeptOutputIsNotFound() {
        doReturn(new HashMap<>()).when(this.steps).keptOutputsOf(any());
        assertThat(this.api.runRows(TOKEN, RIVERSIDE, RUN, null, null).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void rowsOverMaxBytesAre413AndNeverACutOffAnswer() throws Exception {
        ResponseEntity<StreamingResponseBody> answer = this.api.runRows(TOKEN, RIVERSIDE, RUN, null, 20L);

        assertThat(answer.getStatusCode().value()).isEqualTo(413);
        assertThat(body(answer)).contains("more than 20 bytes").doesNotContain("M-1");
        verify(this.accessLog, never()).serviceRunRows(anyLong(), anyString(), any(), any(), eq(true), anyInt(), any());

        assertThat(this.api.runRows(TOKEN, RIVERSIDE, RUN, null, 0L).getStatusCode().value()).isEqualTo(400);
        assertThat(this.api.runRows(TOKEN, RIVERSIDE, RUN, null, 500L * 1024 * 1024).getStatusCode().value()).isEqualTo(400);
        assertThat(this.api.runRows(TOKEN, RIVERSIDE, RUN, null, 4096L).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void theWorkspaceIsSetForTheReadsAndClearedAfter() {
        when(this.store.find(RIVERSIDE, RUN)).thenAnswer(asked -> {
            assertThat(TenantContext.getTenantId()).isEqualTo(RIVERSIDE);
            assertThat(TenantContext.getUserRole()).isEqualTo("TENANT_ADMIN");
            return Optional.of(row(RUN, "Completed"));
        });
        this.api.runRows(TOKEN, RIVERSIDE, RUN, null, null);
        assertThat(TenantContext.getTenantId()).isNull();
        verify(this.accessLog, never()).serviceRunRows(anyLong(), anyString(), any(), any(), anyBoolean(), eq(503), any());
    }
}

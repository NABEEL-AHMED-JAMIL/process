package process.customer;

import org.barco.platform.api.Cursors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.RunOutput;
import process.pipeline.StepStore;
import process.pipeline.review.RunReviews;
import process.security.TenantContext;

import java.time.Instant;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-334: the customer API's runs. Each read checks runs:read; the list pages by cursor and filters by the API's own
 * words (a status word covers the run states it names); a run that is not the workspace's is 404; times are UTC; the
 * steps are the latest attempt's (a run without steps is its one legacy step); the manifest lists the latest attempt's
 * made files and the files the run was given, by file id, and gives a file recorded before file ids its id.
 */
class CustomerRunsTest {

    static final long TENANT = 2946L;
    static final long JOB = 5100L;
    static final long RUN = 9100L;

    private final CustomerRunStore store = mock(CustomerRunStore.class);
    private final JobQueueRepository runRepository = mock(JobQueueRepository.class);
    private final SourceJobRepository jobRepository = mock(SourceJobRepository.class);
    private final RunReviews reviews = mock(RunReviews.class);
    private final StepStore steps = mock(StepStore.class);
    private final PipelineDefinitionStore definitions = mock(PipelineDefinitionStore.class);
    private final RunFiles files = mock(RunFiles.class);
    private final CustomerRuns runs = new CustomerRuns(this.store, this.runRepository, this.jobRepository, this.reviews, this.steps,
        this.definitions, this.files);

    @BeforeEach
    void asAClient() {
        this.client("runs:read");
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("reviewStatus", "PENDING");
        summary.put("required", Collections.singletonList("customer"));
        summary.put("decisions", Collections.emptyList());
        summary.put("decidedAt", null);
        summary.put("rerunJobQueueId", null);
        when(this.reviews.summary(any(), any())).thenReturn(summary);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void client(String... scopes) {
        TenantContext.set(TENANT, TenantContext.API_CLIENT, null, "client:cl_test");
        TenantContext.setApiClient("cl_test", new LinkedHashSet<>(Arrays.asList(scopes)));
    }

    static CustomerRunStore.Row row(long runId, String status) {
        CustomerRunStore.Row row = new CustomerRunStore.Row();
        row.runId = runId;
        row.jobId = JOB;
        row.tenantId = TENANT;
        row.jobStatus = status;
        row.message = "Job 5100 completed.";
        row.createdAt = Instant.parse("2026-10-06T14:00:00Z");
        row.startedAt = Instant.parse("2026-10-06T14:00:05Z");
        row.endedAt = Instant.parse("2026-10-06T14:01:05.250Z");
        row.attempt = 1;
        row.reference = "po-7";
        return row;
    }

    private void exists(long runId, String status) {
        when(this.store.find(TENANT, runId)).thenReturn(Optional.of(row(runId, status)));
        JobQueue run = new JobQueue();
        run.setJobQueueId(runId);
        run.setJobId(JOB);
        run.setTenantId(TENANT);
        run.setJobStatus(JobStatus.valueOf(status));
        when(this.runRepository.findById(runId)).thenReturn(Optional.of(run));
        when(this.runRepository.findAllById(any())).thenReturn(Collections.singletonList(run));
        SourceJob job = new SourceJob();
        job.setJobId(JOB);
        job.setTenantId(TENANT);
        when(this.jobRepository.findById(JOB)).thenReturn(Optional.of(job));
        when(this.jobRepository.findAllById(any())).thenReturn(Collections.singletonList(job));
    }

    @Test
    void everyReadNeedsRunsRead() {
        this.client("runs:write", "files:read");
        assertThat(this.runs.list(null, null, null, null, null, null).status).isEqualTo(403);
        assertThat(this.runs.get(String.valueOf(RUN)).status).isEqualTo(403);
        assertThat(this.runs.steps(String.valueOf(RUN)).status).isEqualTo(403);
        assertThat(this.runs.outputs(String.valueOf(RUN)).body.get("type")).isEqualTo("/problems/insufficient-scope");
        verify(this.store, never()).find(anyLong(), anyLong());
    }

    @Test
    void aRunThatIsNotTheWorkspacesIsNotFound() {
        when(this.store.find(eq(TENANT), anyLong())).thenReturn(Optional.empty());
        for (CustomerAnswer answer : Arrays.asList(this.runs.get("9999"), this.runs.steps("9999"), this.runs.outputs("9999"),
            this.runs.get("not-a-number"))) {
            assertThat(answer.status).isEqualTo(404);
            assertThat(answer.body.get("detail")).isEqualTo("No such run.");
        }
    }

    @Test
    void oneRunIsItsStatusTimesInUtcReferenceAttemptAndReviewStatus() {
        this.exists(RUN, "Completed");
        CustomerAnswer answer = this.runs.get(String.valueOf(RUN));
        assertThat(answer.status).isEqualTo(200);
        assertThat(answer.body).containsEntry("id", String.valueOf(RUN)).containsEntry("pipelineId", String.valueOf(JOB))
            .containsEntry("status", "completed").containsEntry("reference", "po-7").containsEntry("createdAt", "2026-10-06T14:00:00Z")
            .containsEntry("startedAt", "2026-10-06T14:00:05Z").containsEntry("endedAt", "2026-10-06T14:01:05.250Z")
            .containsEntry("attempt", 1).containsEntry("review", "pending");
    }

    @Test
    void theListPagesByCursorAndFiltersInTheApisWords() {
        this.exists(RUN, "Completed");
        when(this.store.page(eq(TENANT), isNull(), eq(3), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(Arrays.asList(row(RUN, "Completed"), row(RUN - 1, "Failed"), row(RUN - 2, "Queue")));
        CustomerAnswer first = this.runs.list(2, null, null, null, null, null);
        assertThat(first.status).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) first.body.get("data");
        assertThat(data).extracting(r -> r.get("status")).containsExactly("completed", "failed");
        assertThat(first.body.get("nextCursor")).isEqualTo(Cursors.after(RUN - 1));

        this.runs.list(10, Cursors.after(RUN - 1), String.valueOf(JOB), "queued", "2026-10-01T00:00:00Z",
            "2026-10-02T00:00:00+02:00");
        verify(this.store).page(TENANT, RUN - 1, 11, JOB, Arrays.asList("Queue", "Start"), Instant.parse("2026-10-01T00:00:00Z"),
            Instant.parse("2026-10-01T22:00:00Z"));
    }

    @Test
    void aFilterTheApiDoesNotKnowIsA400AndAnUnknownPipelineHasNoRuns() {
        assertThat(this.runs.list(null, null, null, "done", null, null).status).isEqualTo(400);
        assertThat(this.runs.list(null, "not-a-cursor", null, null, null, null).status).isEqualTo(400);
        assertThat(this.runs.list(null, null, null, null, "yesterday", null).status).isEqualTo(400);
        assertThat(this.runs.list(null, null, null, null, null, "2026-10-01T00:00:00").status).as("no offset").isEqualTo(400);
        assertThat(this.runs.list(500, null, null, null, null, null).status).isEqualTo(400);
        CustomerAnswer none = this.runs.list(null, null, "ORDERS", null, null, null);
        assertThat(none.status).isEqualTo(200);
        assertThat((List<?>) none.body.get("data")).isEmpty();
        verify(this.store, never()).page(anyLong(), any(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void theStepsAreTheLatestAttemptsInOrderAndARunWithoutStepsIsItsLegacyStep() {
        this.exists(RUN, "Completed");
        StepStore.StepRow early = step(1, 0, "read", "Failed");
        StepStore.StepRow read = step(2, 0, "read", "Completed");
        StepStore.StepRow save = step(2, 1, "save", "Skip");
        when(this.steps.stepsOfRun(RUN)).thenReturn(Arrays.asList(early, read, save));
        when(this.steps.pinnedDefinition(RUN)).thenReturn(Optional.empty());
        CustomerAnswer answer = this.runs.steps(String.valueOf(RUN));
        assertThat(answer.body.get("attempt")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) answer.body.get("data");
        assertThat(data).extracting(s -> s.get("key") + ":" + s.get("status")).containsExactly("read:completed", "save:skipped");
        assertThat(data.get(0)).containsEntry("rowsIn", 10L).containsEntry("rowsOut", 9L).containsEntry("startedAt", "2026-10-06T14:00:00Z")
            .containsEntry("task", "read_file");

        when(this.steps.stepsOfRun(RUN)).thenReturn(Collections.emptyList());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> legacy = (List<Map<String, Object>>) this.runs.steps(String.valueOf(RUN)).body.get("data");
        assertThat(legacy).hasSize(1);
        assertThat(legacy.get(0)).containsEntry("key", "legacy").containsEntry("task", "legacy").containsEntry("status", "completed")
            .containsEntry("durationMs", 60250L);
    }

    @Test
    void theManifestListsTheGivenAndMadeFilesByIdAndGivesAnOldOneItsId() {
        this.exists(RUN, "Completed");
        CustomerRunStore.Intake intake = new CustomerRunStore.Intake();
        intake.fileIds = Collections.singletonList("01JINPUT000000000000000000");
        when(this.store.intakeOf(TENANT, RUN)).thenReturn(Optional.of(intake));
        Map<String, Object> upload = new LinkedHashMap<>();
        upload.put("id", "01JINPUT000000000000000000");
        upload.put("name", "order.csv");
        upload.put("bucket", "northwind-inbox");
        upload.put("key", "intake/api/order.csv");
        upload.put("bytes", 25L);
        upload.put("role", "INPUT");
        upload.put("createdAt", "2026-10-06T13:59:00Z");
        when(this.files.of(eq(TENANT), any())).thenReturn(Collections.singletonMap("01JINPUT000000000000000000", upload));
        when(this.steps.stepsOfRun(RUN)).thenReturn(Collections.singletonList(step(1, 0, "save", "Completed")));
        StepStore.OutputRow kept = output(77L, "result.csv", "csv", null);
        StepStore.OutputRow report = output(78L, "report.pdf", "pdf", "01JREPORT00000000000000000");
        report.expiresAt = Instant.now().minusSeconds(60);
        when(this.steps.outputsOfRun(RUN)).thenReturn(Arrays.asList(kept, report));
        when(this.steps.fileIdOf(77L)).thenReturn("01JRESULT00000000000000000");

        CustomerAnswer answer = this.runs.outputs(String.valueOf(RUN));
        assertThat(answer.status).isEqualTo(200);
        assertThat(answer.body).containsEntry("status", "completed").containsEntry("attempt", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> listed = (List<Map<String, Object>>) answer.body.get("files");
        assertThat(listed).extracting(f -> f.get("id") + ":" + f.get("role") + ":" + f.get("expired"))
            .containsExactly("01JINPUT000000000000000000:input:false", "01JRESULT00000000000000000:result:false",
                "01JREPORT00000000000000000:report:true");
        assertThat(listed.toString()).doesNotContain("northwind-inbox").doesNotContain("intake/api").doesNotContain("bucket");
        assertThat(((Map<?, ?>) answer.body.get("review")).get("status")).isEqualTo("pending");
        verify(this.steps).fileIdOf(77L);
        verify(this.steps, never()).fileIdOf(78L);
    }

    @Test
    void storageThatCannotAnswerIsA503NotAShortManifest() {
        this.exists(RUN, "Completed");
        CustomerRunStore.Intake intake = new CustomerRunStore.Intake();
        intake.fileIds = Collections.singletonList("01JINPUT000000000000000000");
        when(this.store.intakeOf(TENANT, RUN)).thenReturn(Optional.of(intake));
        when(this.files.of(eq(TENANT), any())).thenThrow(new IllegalStateException("storage-service is down"));
        assertThat(this.runs.outputs(String.valueOf(RUN)).status).isEqualTo(503);
    }

    static StepStore.StepRow step(int attempt, int index, String key, String status) {
        StepStore.StepRow row = new StepStore.StepRow();
        row.jobQueueId = RUN;
        row.attempt = attempt;
        row.stepIndex = index;
        row.stepKey = key;
        row.taskCode = "read".equals(key) ? "read_file" : "save_file";
        row.status = status;
        row.recordsIn = 10L;
        row.recordsOut = 9L;
        row.startedAt = LocalDateTime.parse("2026-10-06T09:00:00");
        row.endedAt = LocalDateTime.parse("2026-10-06T09:00:01");
        return row;
    }

    static StepStore.OutputRow output(long id, String name, String format, String fileId) {
        StepStore.OutputRow row = new StepStore.OutputRow();
        row.runOutputId = id;
        row.jobQueueId = RUN;
        row.attempt = 1;
        row.stepKey = "save";
        row.kind = RunOutput.FILE;
        row.name = name;
        row.format = format;
        row.rowCount = 9L;
        row.byteCount = 120L;
        row.runDatasetId = id + 1000;
        row.expiresAt = Instant.now().plusSeconds(3600);
        row.recordedAt = LocalDateTime.parse("2026-10-06T09:01:00");
        row.fileId = fileId;
        return row;
    }
}

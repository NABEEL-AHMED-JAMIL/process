package process.pipeline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.ai.InMemoryModelChoiceStore;
import process.api.StepTimelineRestApi;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import process.model.dto.ResponseDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.security.TenantContext;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wave 4: a run dataset downloads as a file, and a run's manifest lists the files its steps wrote -- for the caller whose
 * run it is (the timeline's rule), refused as not found for anyone else, and gone (410) once expired.
 */
class RunDatasetDownloadTest {

    private static final long TENANT = 2924L;
    private static final long JOB = 2834L;
    private static final long RUN = 7383L;

    private final JobQueueRepository runs = mock(JobQueueRepository.class);
    private final SourceJobRepository jobs = mock(SourceJobRepository.class);
    private final InMemoryStepStore steps = new InMemoryStepStore();
    private final InMemoryDatasetStore datasets = new InMemoryDatasetStore();
    private final StepTimelineService service = new StepTimelineService(this.runs, this.jobs, this.steps, new InMemoryModelChoiceStore(),
        this.datasets);
    private long shape;
    private long keep;
    private long send;
    private long output;
    private long file;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT, "TENANT_ADMIN", 7L, "admin@example");
        JobQueue run = new JobQueue();
        run.setJobQueueId(RUN);
        run.setJobId(JOB);
        run.setTenantId(TENANT);
        run.setAttempt(1);
        run.setJobStatus(JobStatus.Completed);
        SourceJob job = new SourceJob();
        job.setJobId(JOB);
        job.setTenantId(TENANT);
        job.setJobStatus(Status.Active);
        when(this.runs.findById(RUN)).thenReturn(Optional.of(run));
        when(this.jobs.findById(JOB)).thenReturn(Optional.of(job));

        List<Long> planned = this.steps.plan(RUN, 1, 1001L, Arrays.asList(new StepStore.Planned(0, "shape", "select", "fail"),
            new StepStore.Planned(1, "keep", "save_file", "fail"), new StepStore.Planned(2, "send", "upload_bucket", "fail")));
        this.shape = planned.get(0);
        this.keep = planned.get(1);
        this.send = planned.get(2);
        Instant later = Instant.now().plus(Duration.ofHours(24));
        String outputKey = DatasetStore.keyOf(RUN, 1, "shape", "output");
        this.datasets.write(outputKey, Dataset.of(Arrays.asList(row("id", 1, "name", "Acme, Inc"), row("id", 2, "name", "Beta"))));
        this.output = this.steps.dataset(this.shape, "output", outputKey, 2, "[\"id\",\"name\"]", later);
        String fileKey = DatasetStore.fileKeyOf(RUN, 1, "keep", "claims.csv");
        this.datasets.writeFile(fileKey, "id,name\r\n1,Acme\r\n".getBytes(StandardCharsets.UTF_8));
        this.file = this.steps.dataset(this.keep, "claims.csv", fileKey, 1, "[\"id\",\"name\"]", later);
        this.steps.output(this.keep, RunOutput.file("claims.csv", "csv", 1, 16), this.file, later);
        this.steps.output(this.send, RunOutput.bucket("exports", "out/7383.json", "json", 2, 40), null, null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    private static String body(StepTimelineService.Download download) throws Exception {
        assertThat(download.refusal).as(download.refusal == null ? "" : download.refusal.getMessage()).isNull();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        download.body.writeTo(out);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // ---- download ----------------------------------------------------------------------------------------------------

    @Test
    void aStepsOutputDownloadsAsCsvByDefaultAndAsJsonWhenAsked() throws Exception {
        StepTimelineService.Download csv = this.service.download(this.output, null);
        assertThat(csv.status).isEqualTo(200);
        assertThat(csv.fileName).isEqualTo("run-7383-attempt-1-shape-output.csv");
        assertThat(csv.contentType).startsWith("text/csv");
        assertThat(body(csv)).isEqualTo("id,name\r\n1,\"Acme, Inc\"\r\n2,Beta\r\n");

        StepTimelineService.Download json = this.service.download(this.output, "JSON");
        assertThat(json.fileName).endsWith("-output.json");
        assertThat(body(json)).isEqualTo("[{\"id\":1,\"name\":\"Acme, Inc\"},{\"id\":2,\"name\":\"Beta\"}]");
    }

    @Test
    void aKeptFileDownloadsAsItWasWrittenOrConverted() throws Exception {
        StepTimelineService.Download asIs = this.service.download(this.file, null);
        assertThat(asIs.fileName).isEqualTo("claims.csv");
        assertThat(body(asIs)).isEqualTo("id,name\r\n1,Acme\r\n");

        StepTimelineService.Download converted = this.service.download(this.file, "jsonl");
        assertThat(converted.fileName).isEqualTo("claims.jsonl");
        assertThat(converted.contentType).startsWith("application/x-ndjson");
        assertThat(body(converted)).isEqualTo("{\"id\":\"1\",\"name\":\"Acme\"}\n");
    }

    @Test
    void aFormatItDoesNotWriteIsA400() {
        StepTimelineService.Download parquet = this.service.download(this.output, "parquet");
        assertThat(parquet.status).isEqualTo(400);
        assertThat(parquet.refusal.getStatus()).isEqualTo("ERROR");
        assertThat(parquet.refusal.getMessage()).contains("csv, json or jsonl").contains("parquet");
    }

    @Test
    void anExpiredDatasetIsGone() {
        this.steps.datasetExpiry.put(this.output, Instant.now().minusSeconds(60));
        StepTimelineService.Download gone = this.service.download(this.output, "csv");
        assertThat(gone.status).isEqualTo(410);
        assertThat(gone.refusal.getMessage()).startsWith("This dataset expired at ").contains("(Chicago)")
            .contains("datasetRetentionHours");
    }

    /** After the sweep has removed an expired kept file, the manifest still names it: gone, not "not found". */
    @Test
    void aSweptKeptFileIsStillGoneNotNotFound() {
        this.steps.datasets.removeIf(d -> d.runDatasetId == this.file);
        this.steps.output(this.keep, RunOutput.file("claims.csv", "csv", 1, 16), this.file, Instant.now().minusSeconds(60));
        StepTimelineService.Download gone = this.service.download(this.file, null);
        assertThat(gone.status).isEqualTo(410);
        assertThat(gone.refusal.getMessage()).contains("expired");
        // A swept intermediate dataset has no manifest row: simply not found.
        this.steps.datasets.removeIf(d -> d.runDatasetId == this.output);
        assertThat(this.service.download(this.output, null).status).isEqualTo(404);
    }

    @Test
    void aDatasetWhoseContentIsLostIsGone() {
        this.datasets.stored.clear();
        StepTimelineService.Download lost = this.service.download(this.output, null);
        assertThat(lost.status).isEqualTo(410);
        assertThat(lost.refusal.getMessage()).contains("no longer available");
    }

    @Test
    void anotherWorkspacesOrAMissingDatasetIsNotFound() {
        assertThat(this.service.download(999999L, null).refusal.getMessage()).isEqualTo("Dataset not found with runDatasetId.");
        assertThat(this.service.download(null, null).status).isEqualTo(404);
        TenantContext.set(4242L, "TENANT_ADMIN", 8L, "other@example");
        for (long dataset : new long[] {this.output, this.file}) {
            StepTimelineService.Download refused = this.service.download(dataset, "csv");
            assertThat(refused.status).isEqualTo(404);
            assertThat(refused.refusal.getMessage()).isEqualTo("Dataset not found with runDatasetId.");
            assertThat(refused.body).isNull();
        }
    }

    // ---- manifest ----------------------------------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void theManifestListsTheKeptFileAndTheUploadNeverAStorageKey() {
        ResponseDto answer = this.service.outputs(RUN, null);
        assertThat(answer.getStatus()).isEqualTo("SUCCESS");
        assertThat(answer.getMessage()).isEqualTo("2 file(s).");
        List<Map<String, Object>> outputs = (List<Map<String, Object>>) ((Map<String, Object>) answer.getData()).get("outputs");
        assertThat(outputs.get(0)).containsEntry("kind", "file").containsEntry("name", "claims.csv").containsEntry("stepKey", "keep")
            .containsEntry("task", "save_file").containsEntry("runDatasetId", this.file).containsEntry("expired", false)
            .containsEntry("rowCount", 1L).containsEntry("byteCount", 16L).doesNotContainKey("bucket");
        assertThat(outputs.get(1)).containsEntry("kind", "bucket").containsEntry("bucket", "exports").containsEntry("key", "out/7383.json")
            .containsEntry("name", "7383.json").containsEntry("format", "json").doesNotContainKey("runDatasetId");
        assertThat(String.valueOf(outputs)).doesNotContain("datasets/");
        assertThat(((List<?>) ((Map<String, Object>) this.service.outputs(RUN, 2).getData()).get("outputs"))).isEmpty();
    }

    @Test
    void anotherWorkspacesManifestIsNotFound() {
        TenantContext.set(4242L, "TENANT_ADMIN", 8L, "other@example");
        assertThat(this.service.outputs(RUN, null).getMessage()).isEqualTo("Run not found with jobQueueId.");
        assertThat(this.service.outputs(null, null).getStatus()).isEqualTo("ERROR");
    }

    // Through Spring MVC, as the live endpoint answers: a ResponseEntity<?> holding a StreamingResponseBody is not
    // streamed (Spring streams only a declared ResponseEntity<StreamingResponseBody>); JSON came back as "{}" and CSV
    // as a 500 "No converter" (live, 2026-09-29, run 7405). Calling the service directly could not see that.

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new StepTimelineRestApi(this.service)).build();
    }

    @Test
    void aKeptFileDownloadsOverHttpAsItsBytes() throws Exception {
        MvcResult started = this.mvc().perform(get("/sourceJob.json/runDataset").param("runDatasetId", "" + this.file))
            .andExpect(request().asyncStarted()).andReturn();
        this.mvc().perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"claims.csv\""))
            .andExpect(content().string("id,name\r\n1,Acme\r\n"));
    }

    @Test
    void aStepOutputDownloadsOverHttpInTheAskedFormat() throws Exception {
        MvcResult started = this.mvc().perform(get("/sourceJob.json/runDataset").param("runDatasetId", "" + this.output)
            .param("format", "json")).andExpect(request().asyncStarted()).andReturn();
        String body = this.mvc().perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("Acme, Inc").contains("Beta").isNotEqualTo("{}");
    }

    @Test
    void aRefusalOverHttpIsTheJsonEnvelope() throws Exception {
        MvcResult started = this.mvc().perform(get("/sourceJob.json/runDataset").param("runDatasetId", "999999"))
            .andExpect(request().asyncStarted()).andReturn();
        this.mvc().perform(asyncDispatch(started))
            .andExpect(status().isNotFound())
            .andExpect(content().string(containsString("\"status\":\"ERROR\"")));
    }
}

package process.pipeline.items;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.ai.InMemoryModelChoiceStore;
import process.identity.IdentityPort;
import process.model.dto.ResponseDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.pipeline.Dataset;
import process.pipeline.DatasetStore;
import process.pipeline.DefinitionCodec;
import process.pipeline.Definitions;
import process.pipeline.InMemoryDatasetStore;
import process.pipeline.InMemoryStepStore;
import process.pipeline.PipelineDefinition;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.RunOutput;
import process.pipeline.SensitiveFiles;
import process.pipeline.StepStore;
import process.pipeline.StepTimelineService;
import process.pipeline.review.RunReviews;
import process.security.TenantContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ZT-M21 (owner 2026-10-26): the files a pipeline marked sensitive keeps are its real output and never masked, so their bytes
 * are the access profile's Download sensitive files (page key sensitive-files). A member without it sees each file in the
 * run's outputs and an item's files (name, size, type: listed, "downloadable": false) and is refused the download, View and
 * preview, an item's file and its zip link, in one plain sentence; with it, or as an administrator, they download as before;
 * a pipeline that is not sensitive is unchanged; Identity that cannot be asked refuses (fail closed). Synthetic files.
 */
class SensitiveFilesTest {

    private static final long TENANT = 1006L;
    private static final long JOB = 1470L;
    private static final long RUN = 2140L;
    private static final long MEMBER = 1018L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JobQueueRepository runs = mock(JobQueueRepository.class);
    private final SourceJobRepository jobs = mock(SourceJobRepository.class);
    private final InMemoryStepStore steps = new InMemoryStepStore();
    private final InMemoryDatasetStore datasets = new InMemoryDatasetStore();
    private final PipelineDefinitionStore definitions = mock(PipelineDefinitionStore.class);
    private final IdentityPort identity = mock(IdentityPort.class);
    private final SensitiveFiles sensitiveFiles = new SensitiveFiles(this.steps, this.definitions);
    private StepTimelineService timeline;
    private RunItems items;
    private long file;
    private long output;
    private final InMemoryRunItemStore store = new InMemoryRunItemStore();
    private long fileRef;

    @BeforeEach
    void setUp() throws Exception {
        this.sensitiveFiles.useIdentity(this.identity);
        this.timeline = new StepTimelineService(this.runs, this.jobs, this.steps, new InMemoryModelChoiceStore(), this.datasets, mock(RunReviews.class));
        this.timeline.useSensitiveFiles(this.sensitiveFiles);
        this.items = new RunItems(this.store, this.runs, this.jobs, this.steps, this.definitions, Definitions.builtInTasks(), this.datasets,
            mock(ItemAccessLog.class));
        CatalogMasks catalog = mock(CatalogMasks.class);
        when(catalog.forCaller()).thenReturn(JSON.readTree("[]"));
        this.items.useCatalog(catalog);
        this.items.useLinks(new ItemLinks("test-service-token"));
        this.items.useSensitiveFiles(this.sensitiveFiles);
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
        job.setCreatedBy(MEMBER);
        when(this.runs.findById(RUN)).thenReturn(Optional.of(run));
        when(this.jobs.findById(JOB)).thenReturn(Optional.of(job));
        List<Long> planned = this.steps.plan(RUN, 1, 1L, Arrays.asList(new StepStore.Planned(0, "read", "sample", "fail"),
            new StepStore.Planned(1, "keep", "save_file", "fail")));
        Instant later = Instant.now().plus(Duration.ofHours(24));
        String outputKey = DatasetStore.keyOf(RUN, 1, "read", "output");
        this.datasets.write(outputKey, Dataset.of(Collections.singletonList(Definitions.row("record_id", "ZT-M21-F1", "risk", "High"))));
        this.output = this.steps.dataset(planned.get(0), "output", outputKey, 1, "[\"record_id\",\"risk\"]", later);
        String fileKey = DatasetStore.fileKeyOf(RUN, 1, "keep", "signed-care-plan.pdf");
        this.datasets.writeFile(fileKey, "%PDF-1.7 signed care plan (synthetic)".getBytes(StandardCharsets.UTF_8));
        this.file = this.steps.dataset(planned.get(1), "signed-care-plan.pdf", fileKey, 1, "[]", later);
        this.steps.output(planned.get(1), RunOutput.file("signed-care-plan.pdf", "pdf", 1, 37), this.file, later);
        // One item with that file, as Save File per row records it.
        ItemRow item = new ItemRow();
        item.jobQueueId = RUN;
        item.attempt = 1;
        item.itemKey = "ZT-M21-F1";
        item.itemRef = "1";
        item.label = "ZT-M21-F1";
        item.position = 1;
        item.status = ItemRow.DONE;
        ItemRow.StepEntry entry = new ItemRow.StepEntry();
        entry.i = 1;
        entry.key = "keep";
        entry.task = "save_file";
        entry.status = "done";
        entry.ds = this.output;
        entry.rows = Collections.singletonList(0);
        entry.n = 1;
        item.steps.add(entry);
        this.store.save(Collections.singletonList(item));
        ItemRow.FileRow f = new ItemRow.FileRow();
        f.jobQueueId = RUN;
        f.attempt = 1;
        f.itemKey = "ZT-M21-F1";
        f.stepIndex = 1;
        f.stepKey = "keep";
        f.role = ItemRow.FileRow.OUTPUT;
        f.fileName = "signed-care-plan.pdf";
        f.format = "pdf";
        f.byteCount = 37L;
        f.runDatasetId = this.file;
        this.store.addFiles(Collections.singletonList(f));
        this.fileRef = this.store.files.get(this.store.files.size() - 1).runItemFileId;
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void marked(String sensitivity) {
        PipelineDefinition definition = Definitions.of(Definitions.step("read", "sample"), Definitions.step("keep", "save_file"));
        PipelineDefinition.Settings settings = new PipelineDefinition.Settings();
        settings.setSensitivity(sensitivity);
        definition.setSettings(settings);
        PipelineDefinitionStore.Stored stored = new PipelineDefinitionStore.Stored();
        stored.id = 1L;
        stored.tenantId = TENANT;
        stored.version = 1;
        stored.json = DefinitionCodec.toJson(definition);
        when(this.definitions.byId(1L)).thenReturn(Optional.of(stored));
    }

    private void member(boolean holdsTheKey) {
        TenantContext.set(TENANT, "TENANT_USER", MEMBER, "member@riverside.test");
        when(this.identity.pageDecision(eq("TENANT_USER"), eq(MEMBER), eq(SensitiveFiles.PATH)))
            .thenReturn(new IdentityPort.PageDecision(holdsTheKey, holdsTheKey ? null : "Download sensitive files is not part of your access."));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> keptOutput() {
        ResponseDto manifest = this.timeline.outputs(RUN, null);
        Map<String, Object> data = (Map<String, Object>) manifest.getData();
        for (Map<String, Object> o : (List<Map<String, Object>>) data.get("outputs")) {
            if ("signed-care-plan.pdf".equals(o.get("name"))) {
                return o;
            }
        }
        throw new AssertionError("no kept file in the manifest");
    }

    @Test
    void aMemberWithoutThePermissionSeesTheFileAndIsRefusedItsBytes() {
        this.marked("sensitive");
        this.member(false);
        Map<String, Object> listed = this.keptOutput();
        assertThat(listed).containsEntry("name", "signed-care-plan.pdf").containsEntry("byteCount", 37L).containsEntry("sensitive", true)
            .containsEntry("downloadable", false).containsEntry("refusal", SensitiveFiles.PERSON_REFUSAL);
        StepTimelineService.Download download = this.timeline.download(this.file, null);
        assertThat(download.status).isEqualTo(403);
        assertThat(download.refusal.getMessage()).isEqualTo(SensitiveFiles.PERSON_REFUSAL);
        assertThat(this.timeline.download(this.file, "pdf").status).as("View asks the same").isEqualTo(403);
        // The step's rows are masked data, not a file: they still download (masked for this member).
        assertThat(this.timeline.download(this.output, "json").status).isEqualTo(200);
        // An item's file, its preview and its zip link.
        RunItems.Answer one = this.items.file(RUN, "1", this.fileRef, false);
        assertThat(one.status).isEqualTo(403);
        assertThat(one.body.getMessage()).isEqualTo(SensitiveFiles.PERSON_REFUSAL);
        assertThat(this.items.file(RUN, "1", this.fileRef, true).status).isEqualTo(403);
        assertThat(this.items.zipLink(RUN, "1", null).status).isEqualTo(403);
        RunItems.FilesRequest files = new RunItems.FilesRequest();
        files.jobQueueId = RUN;
        files.ref = "1";
        Map<?, ?> page = (Map<?, ?>) this.items.files(files).body.getData();
        assertThat(page.get("total")).isEqualTo(1);
        assertThat(page.get("downloadable")).isEqualTo(false);
    }

    @Test
    void theAdministratorAndAMemberWithThePermissionDownloadAsBefore() {
        this.marked("sensitive");
        this.member(true);
        assertThat(this.keptOutput()).containsEntry("downloadable", true).doesNotContainKey("refusal");
        assertThat(this.timeline.download(this.file, null).status).isEqualTo(200);
        assertThat(this.items.file(RUN, "1", this.fileRef, false).status).isEqualTo(200);
        assertThat(this.items.zipLink(RUN, "1", null).status).isEqualTo(200);
        TenantContext.set(TENANT, "TENANT_ADMIN", 1015L, "admin@riverside.test");
        assertThat(this.timeline.download(this.file, null).status).isEqualTo(200);
    }

    @Test
    void aPipelineThatIsNotSensitiveIsUnchanged() {
        this.marked("internal");
        this.member(false);
        assertThat(this.keptOutput()).doesNotContainKey("sensitive").doesNotContainKey("downloadable");
        assertThat(this.timeline.download(this.file, null).status).isEqualTo(200);
        assertThat(this.items.file(RUN, "1", this.fileRef, false).status).isEqualTo(200);
    }

    @Test
    void identityThatCannotBeAskedRefusesFailClosed() {
        this.marked("sensitive");
        TenantContext.set(TENANT, "TENANT_USER", MEMBER, "member@riverside.test");
        when(this.identity.pageDecision(any(), anyLong(), any())).thenThrow(new IdentityPort.Unavailable("down", null));
        StepTimelineService.Download download = this.timeline.download(this.file, null);
        assertThat(download.status).isEqualTo(403);
        assertThat(download.refusal.getMessage()).isEqualTo(SensitiveFiles.UNCHECKED);
    }
}

package process.pipeline.items;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import process.ai.ModelChoiceStore;
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
import process.pipeline.StepStore;
import process.pipeline.StepTimelineService;
import process.pipeline.review.RunReviews;
import process.security.TenantContext;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ZT-M21: a pipeline marked sensitive with ONE synthetic patient (or two) is masked on every surface that hands its rows to a
 * member -- an item's data at a step, its fields in the table, the CSV, item.json in a zip, the items-across-runs list, a
 * compare, and the run's output viewed or downloaded (sourceJob.json/runDataset). Value detection (SensitiveValues.columnKind)
 * needs three values to decide a column, and each of these reads one item's one row: before, every value showed. A pipeline
 * that is not marked sensitive is unchanged: its values show where no catalog tag masks them.
 */
class SensitiveFewRowsMaskingTest {

    private static final long TENANT = 1006L;
    private static final long JOB = 1460L;
    private static final long RUN = 2101L;
    private static final long EARLIER = 2100L;
    private static final long MEMBER = 1016L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String NAME = "Pat Synthetic";
    private static final String EMAIL = "pat.synthetic@example.test";
    private static final String PHONE = "+1 312-555-0147";
    private static final String SSN = "123-45-6789";
    private static final String SECOND = "Robin Testcase";

    private final InMemoryRunItemStore store = new InMemoryRunItemStore();
    private final InMemoryStepStore steps = new InMemoryStepStore();
    private final InMemoryDatasetStore datasets = new InMemoryDatasetStore();
    private final JobQueueRepository runs = mock(JobQueueRepository.class);
    private final SourceJobRepository jobs = mock(SourceJobRepository.class);
    private final PipelineDefinitionStore definitions = mock(PipelineDefinitionStore.class);
    private final CatalogMasks catalog = mock(CatalogMasks.class);
    private RunItems items;
    private ItemsAcrossRuns across;
    private StepTimelineService timeline;
    private final Map<Long, Long> outputs = new LinkedHashMap<>();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    /** Two runs of one schedule, each with these patients (one item a row), read by a member the catalog tags nothing for. */
    private void given(String sensitivity, List<Map<String, Object>> patients) throws Exception {
        PipelineDefinition definition = Definitions.of(Definitions.step("intake", "select"));
        PipelineDefinition.Settings settings = new PipelineDefinition.Settings();
        settings.setSensitivity(sensitivity);
        definition.setSettings(settings);
        PipelineDefinitionStore.Stored stored = new PipelineDefinitionStore.Stored();
        stored.id = 1L;
        stored.tenantId = TENANT;
        stored.version = 1;
        stored.json = DefinitionCodec.toJson(definition);
        when(this.definitions.byId(1L)).thenReturn(Optional.of(stored));
        this.items = new RunItems(this.store, this.runs, this.jobs, this.steps, this.definitions, Definitions.builtInTasks(), this.datasets,
            mock(ItemAccessLog.class));
        this.items.useCatalog(this.catalog);
        this.items.useLinks(new ItemLinks("test-service-token"));
        this.across = new ItemsAcrossRuns(this.items, this.store, this.jobs, this.steps, this.definitions, this.datasets);
        this.timeline = new StepTimelineService(this.runs, this.jobs, this.steps, mock(ModelChoiceStore.class), this.datasets, mock(RunReviews.class));
        this.timeline.useItems(this.items);
        SourceJob job = new SourceJob();
        job.setJobId(JOB);
        job.setTenantId(TENANT);
        job.setJobStatus(Status.Active);
        job.setCreatedBy(MEMBER);
        when(this.jobs.findById(JOB)).thenReturn(Optional.of(job));
        // The day before, the same patients as an older record said them: a compare shows both sides.
        List<Map<String, Object>> before = new ArrayList<>();
        for (Map<String, Object> p : patients) {
            Map<String, Object> was = new LinkedHashMap<>(p);
            was.put("patient_name", p.get("patient_name") + " Old");
            was.put("email", "old." + p.get("email"));
            was.put("risk", "Moderate");
            before.add(was);
        }
        this.run(EARLIER, before, Instant.parse("2026-10-25T14:00:00Z"));
        this.run(RUN, patients, Instant.parse("2026-10-26T14:00:00Z"));
        // A member (not an administrator) the Data Catalog masks nothing for: only the pipeline's sensitivity can mask.
        TenantContext.set(TENANT, "TENANT_USER", MEMBER, "member@riverside.test");
        when(this.catalog.forCaller()).thenReturn(JSON.readTree("[]"));
    }

    private void run(long id, List<Map<String, Object>> patients, Instant started) {
        JobQueue run = new JobQueue();
        run.setJobQueueId(id);
        run.setJobId(JOB);
        run.setTenantId(TENANT);
        run.setAttempt(1);
        run.setJobStatus(JobStatus.Completed);
        when(this.runs.findById(id)).thenReturn(Optional.of(run));
        long step = this.steps.plan(id, 1, 1L, Collections.singletonList(new StepStore.Planned(0, "intake", "select", "fail"))).get(0);
        String key = DatasetStore.keyOf(id, 1, "intake", "output");
        List<String> columns = new ArrayList<>(patients.get(0).keySet());
        this.datasets.write(key, new Dataset(columns, patients));
        long ds = this.steps.dataset(step, "output", key, patients.size(), "[]", Instant.now().plusSeconds(3600));
        this.outputs.put(id, ds);
        List<ItemRow> rows = new ArrayList<>();
        for (int i = 0; i < patients.size(); i++) {
            ItemRow item = new ItemRow();
            item.jobQueueId = id;
            item.attempt = 1;
            item.itemKey = String.valueOf(patients.get(i).get("mrn"));
            item.itemRef = String.valueOf(i + 1);
            item.label = item.itemKey;
            item.position = i + 1;
            item.status = ItemRow.DONE;
            item.pipelineVersion = 1;
            item.fields = new LinkedHashMap<>(patients.get(i));
            item.fieldsDs = ds;
            ItemRow.StepEntry entry = new ItemRow.StepEntry();
            entry.i = 0;
            entry.key = "intake";
            entry.task = "select";
            entry.status = "done";
            entry.ds = ds;
            entry.rows = Collections.singletonList(i);
            entry.n = 1;
            item.steps.add(entry);
            rows.add(item);
        }
        this.store.save(rows);
        this.store.run(id, JOB, "Completed", started, "manual");
    }

    private static Map<String, Object> patient(String mrn, String name, String email, String phone, String ssn) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("mrn", mrn);
        row.put("patient_name", name);
        row.put("email", email);
        row.put("phone", phone);
        row.put("ssn", ssn);
        row.put("risk", "High");
        return row;
    }

    private static List<Map<String, Object>> one() {
        return Collections.singletonList(patient("ZT-M21-001", NAME, EMAIL, PHONE, SSN));
    }

    private static List<Map<String, Object>> two() {
        return Arrays.asList(patient("ZT-M21-001", NAME, EMAIL, PHONE, SSN),
            patient("ZT-M21-002", SECOND, "robin.testcase@example.test", "+1 312-555-0188", "234-56-7890"));
    }

    private static JsonNode data(RunItems.Answer answer) {
        assertThat(answer.status).as(answer.body == null ? "" : answer.body.getMessage()).isEqualTo(200);
        return JSON.valueToTree(answer.body.getData());
    }

    private static String text(RunItems.Answer answer) throws Exception {
        assertThat(answer.status).isEqualTo(200);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        answer.stream.writeTo(out);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Every surface's text for the latest run, as the member reads it. */
    private Map<String, String> everySurface(int patients) throws Exception {
        Map<String, String> seen = new LinkedHashMap<>();
        seen.put("item data at a step", data(this.items.data(RUN, "1", "intake", null)).toString());
        RunItems.ListRequest list = new RunItems.ListRequest();
        list.jobQueueId = RUN;
        list.fields = Arrays.asList("patient_name", "email", "phone", "ssn", "risk");
        seen.put("items table fields", data(this.items.list(list)).toString());
        seen.put("items CSV", text(this.items.csv(RUN, null, null, list.fields)));
        RunItems.ListRequest selection = new RunItems.ListRequest();
        selection.jobQueueId = RUN;
        List<String> refs = new ArrayList<>();
        for (int i = 1; i <= patients; i++) {
            refs.add(String.valueOf(i));
        }
        selection.refs = refs;
        String token = data(this.items.zipLinkOfMany(selection, null)).path("token").asText();
        RunItems.Answer zip = this.items.zip(RUN, token);
        assertThat(zip.status).isEqualTo(200);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        zip.stream.writeTo(out);
        Map<String, String> entries = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                entries.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(entries).hasSize(patients);
        seen.put("item.json in a zip", entries.toString());
        ItemsAcrossRuns.Request across = new ItemsAcrossRuns.Request();
        across.jobId = JOB;
        across.search = "ZT-M21";
        across.fields = list.fields;
        JsonNode found = data(this.across.list(across));
        assertThat(found.path("total").asInt()).isEqualTo(2 * patients);
        seen.put("items across runs (run_item.fields)", found.toString());
        seen.put("compare", data(this.across.compare(JOB, "ZT-M21-001", RUN, EARLIER)).toString());
        for (String format : new String[] {"csv", "json", "jsonl"}) {
            StepTimelineService.Download download = this.timeline.download(this.outputs.get(RUN), format);
            assertThat(download.refusal).as(download.refusal == null ? "" : download.refusal.getMessage()).isNull();
            ByteArrayOutputStream file = new ByteArrayOutputStream();
            download.body.writeTo(file);
            seen.put("run output " + format, new String(file.toByteArray(), StandardCharsets.UTF_8));
        }
        return seen;
    }

    private static void assertMasked(Map<String, String> seen, String... values) {
        seen.forEach((surface, shown) -> {
            for (String value : values) {
                assertThat(shown).as(surface + " shows " + value).doesNotContain(value);
            }
            assertThat(shown).as(surface + " keeps what is not personal").contains("High");
        });
    }

    @Test
    void aSensitivePipelinesOnePatientIsMaskedOnEverySurface() throws Exception {
        this.given("sensitive", one());
        assertMasked(this.everySurface(1), NAME, EMAIL, PHONE, SSN);
    }

    @Test
    void aSensitivePipelinesTwoPatientsAreMaskedOnEverySurface() throws Exception {
        this.given("sensitive", two());
        assertMasked(this.everySurface(2), NAME, EMAIL, PHONE, SSN, SECOND, "robin.testcase@example.test", "234-56-7890");
    }

    /** Two patients read at one step together (a step shared by the item): both rows masked, the columns named masked. */
    @Test
    void twoRowsReadTogetherAreMaskedAndSaidMasked() throws Exception {
        this.given("sensitive", two());
        ItemRow shared = this.store.current(RUN).stream().filter(r -> "1".equals(r.itemRef)).findFirst().orElseThrow(IllegalStateException::new);
        shared.steps.get(0).rows = Arrays.asList(0, 1);
        this.store.save(Collections.singletonList(shared));
        JsonNode data = data(this.items.data(RUN, "1", "intake", null));
        assertThat(data.path("rows")).hasSize(2);
        assertThat(data.toString()).doesNotContain(NAME).doesNotContain(SECOND).contains("High");
        assertThat(data.path("masked").toString()).contains("patient_name").contains("email").contains("phone").contains("ssn");
    }

    /** Not marked sensitive, and no catalog tag: the member sees the values, on every surface, as before. */
    @Test
    void aPipelineNotMarkedSensitiveIsUnchanged() throws Exception {
        this.given("internal", one());
        Map<String, String> seen = this.everySurface(1);
        seen.forEach((surface, shown) -> assertThat(shown).as(surface).contains(NAME).contains(EMAIL));
    }

    /** An administrator reads a sensitive pipeline's values (the data policy's access), as before. */
    @Test
    void anAdministratorStillReadsTheValues() throws Exception {
        this.given("sensitive", one());
        TenantContext.set(TENANT, "TENANT_ADMIN", 1015L, "admin@riverside.test");
        assertThat(data(this.items.data(RUN, "1", "intake", null)).toString()).contains(NAME);
    }

    /** A column the catalog tags is masked whatever the row count, sensitive or not. */
    @Test
    void aTaggedColumnIsMaskedFromOneRowInAnyPipeline() throws Exception {
        this.given("internal", one());
        when(this.catalog.forCaller()).thenReturn(JSON.readTree("[{\"columns\":{\"patient_name\":\"person_name\"},\"kinds\":[]}]"));
        Map<String, String> seen = this.everySurface(1);
        seen.forEach((surface, shown) -> assertThat(shown).as(surface).doesNotContain(NAME));
    }
}

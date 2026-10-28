package process.pipeline.items;

import java.time.Clock;
import java.time.Duration;
import process.pipeline.StepStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.pipeline.Dataset;
import process.pipeline.DatasetStore;
import process.pipeline.InMemoryDatasetStore;
import process.pipeline.InMemoryStepStore;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.StepTasks;
import process.security.TenantContext;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipEntry;
import java.util.TreeMap;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-420, the Run Items view's reads: masking holds in the item's JSON, its fields, the table and the CSV for a person the
 * Data Catalog masks, an administrator sees the values, a catalog that cannot be asked masks every pattern everywhere, the
 * platform administrator's own sign-in is refused, another run's item is not found, a signed zip link is the person's,
 * the run's and the item's alone and expires, and searches by a value find their items.
 */
class RunItemsTest {

    private static final long TENANT = 1006L;
    private static final long RUN = 9020L;
    private static final long JOB = 1078L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final InMemoryRunItemStore store = new InMemoryRunItemStore();
    private final InMemoryStepStore steps = new InMemoryStepStore();
    private final InMemoryDatasetStore datasets = new InMemoryDatasetStore();
    private final JobQueueRepository runs = mock(JobQueueRepository.class);
    private final SourceJobRepository jobs = mock(SourceJobRepository.class);
    private final CatalogMasks catalog = mock(CatalogMasks.class);
    private final ItemAccessLog log = mock(ItemAccessLog.class);
    private RunItems items;
    private long ds;

    @BeforeEach
    void setUp() throws Exception {
        this.items = new RunItems(this.store, this.runs, this.jobs, this.steps, mock(PipelineDefinitionStore.class), new StepTasks(new ArrayList<>()),
            this.datasets, this.log);
        this.items.useCatalog(this.catalog);
        this.items.useLinks(new ItemLinks("test-service-token"));
        JobQueue run = new JobQueue();
        run.setJobQueueId(RUN);
        run.setJobId(JOB);
        run.setTenantId(TENANT);
        run.setJobStatus(JobStatus.Completed);
        SourceJob job = new SourceJob();
        job.setJobId(JOB);
        job.setTenantId(TENANT);
        job.setCreatedBy(1016L);
        when(this.runs.findById(RUN)).thenReturn(Optional.of(run));
        when(this.jobs.findById(JOB)).thenReturn(Optional.of(job));
        // The step's output: one row per referral, a person's name and an AI answer with a nested name.
        long step = this.steps.plan(RUN, 1, 1L, Collections.singletonList(new StepStore.Planned(1, "extract", "ai_prompt",
            "fail"))).get(0);
        String key = DatasetStore.keyOf(RUN, 1, "extract", "output");
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("referrals/r-1.pdf", "Ana Lopez (synthetic)", "High", "{\"patient_name\":\"Ana Lopez (synthetic)\",\"risk\":\"High\"}"));
        rows.add(row("referrals/r-2.pdf", "Ben Ortiz (synthetic)", "Low", "{\"patient_name\":\"Ben Ortiz (synthetic)\",\"risk\":\"Low\"}"));
        this.datasets.write(key, new Dataset(Arrays.asList("key", "patient_name", "risk", "answer"), rows));
        this.ds = this.steps.dataset(step, "output", key, 2, "[]", Instant.now().plusSeconds(3600));
        this.item("referrals/r-1.pdf", "1", 0, "done");
        this.item("referrals/r-2.pdf", "2", 1, "failed");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static Map<String, Object> row(String key, String name, String risk, String answer) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("patient_name", name);
        row.put("risk", risk);
        row.put("answer", answer);
        return row;
    }

    private void item(String key, String ref, int position, String status) {
        ItemRow row = new ItemRow();
        row.jobQueueId = RUN;
        row.attempt = 1;
        row.itemKey = key;
        row.itemRef = ref;
        row.label = key.substring(key.lastIndexOf('/') + 1);
        row.position = Integer.parseInt(ref);
        row.status = status;
        if ("failed".equals(status)) {
            row.failedStepIndex = 1;
            row.failedStepKey = "extract";
            row.reason = "The answer did not match the schema.";
        }
        ItemRow.StepEntry entry = new ItemRow.StepEntry();
        entry.i = 1;
        entry.key = "extract";
        entry.task = "ai_prompt";
        entry.status = status;
        entry.ds = this.ds;
        entry.rows = Collections.singletonList(position);
        entry.n = 1;
        row.steps.add(entry);
        this.store.save(Collections.singletonList(row));
    }

    private void as(String role, Long user) {
        TenantContext.set(TENANT, role, user, "someone@riverside.test");
    }

    private JsonNode data(RunItems.Answer answer) {
        assertThat(answer.status).as(answer.body == null ? "" : answer.body.getMessage()).isEqualTo(200);
        return JSON.valueToTree(answer.body.getData());
    }

    private void maskedForThePerson() throws Exception {
        when(this.catalog.forCaller()).thenReturn(JSON.readTree("[{\"connection\":\"riverside-health-s3\",\"path\":\"referrals/\","
            + "\"columns\":{\"patient_name\":\"person_name\"},\"kinds\":[]}]"));
    }

    @Test
    void aMaskedColumnStaysMaskedInTheJsonTheFieldsAndTheCsv() throws Exception {
        this.as("TENANT_ADMIN", 1015L);
        // An administrator: the values.
        assertThat(this.data(this.items.data(RUN, "1", "extract", null)).path("rows").get(0).path("patient_name").asText())
            .isEqualTo("Ana Lopez (synthetic)");
        this.as("TENANT_USER", 1016L);
        this.maskedForThePerson();
        JsonNode data = this.data(this.items.data(RUN, "1", "extract", null));
        JsonNode first = data.path("rows").get(0);
        assertThat(first.path("patient_name").asText()).doesNotContain("Ana").doesNotContain("Lopez");
        assertThat(first.path("answer").asText()).as("a JSON answer's field of that name is masked too").doesNotContain("Ana Lopez")
            .contains("\"risk\":\"High\"");
        assertThat(first.path("risk").asText()).isEqualTo("High");
        assertThat(data.path("masked").toString()).contains("patient_name").contains("answer");

        RunItems.ListRequest list = new RunItems.ListRequest();
        list.jobQueueId = RUN;
        list.fields = Arrays.asList("patient_name", "risk");
        JsonNode listed = this.data(this.items.list(list));
        assertThat(listed.path("masked").toString()).contains("patient_name");
        assertThat(listed.toString()).doesNotContain("Ana Lopez").doesNotContain("Ben Ortiz").contains("\"risk\":\"High\"");

        RunItems.Answer csv = this.items.csv(RUN, null, null, Arrays.asList("patient_name", "risk"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        csv.stream.writeTo(out);
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        assertThat(text).contains("patient_name").contains("High").doesNotContain("Ana Lopez").doesNotContain("Ben Ortiz");
    }

    @Test
    void aCatalogThatCannotBeAskedMasksEveryPatternEverywhere() throws Exception {
        this.as("TENANT_USER", 1016L);
        when(this.catalog.forCaller()).thenReturn(null);
        JsonNode data = this.data(this.items.data(RUN, "1", "extract", null));
        assertThat(data.path("maskNote").asText()).contains("masked everywhere");
    }

    @Test
    void aSearchByAValueFindsItsItems() throws Exception {
        this.as("TENANT_ADMIN", 1015L);
        RunItems.ListRequest list = new RunItems.ListRequest();
        list.jobQueueId = RUN;
        list.search = "risk = high";
        assertThat(this.data(this.items.list(list)).path("items")).extracting(n -> n.path("ref").asText()).containsExactly("1");
        list.search = "r-2";
        assertThat(this.data(this.items.list(list)).path("items")).extracting(n -> n.path("ref").asText()).containsExactly("2");
        list.search = null;
        list.status = "failed";
        JsonNode failed = this.data(this.items.list(list));
        assertThat(failed.path("items")).extracting(n -> n.path("ref").asText()).containsExactly("2");
        assertThat(failed.path("summary").path("failedItems").asInt()).isEqualTo(1);
        assertThat(failed.path("summary").path("retryable").asBoolean()).isTrue();
    }

    @Test
    void thePlatformAdministratorsOwnSignInReadsNothingAndAnotherRunIsNotFound() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1001L, "qa-platform-admin@platform.local");
        RunItems.ListRequest list = new RunItems.ListRequest();
        list.jobQueueId = RUN;
        assertThat(this.items.list(list).status).isEqualTo(403);
        assertThat(this.items.detail(RUN, "1", null).status).isEqualTo(403);
        assertThat(this.items.data(RUN, "1", "extract", null).status).isEqualTo(403);
        assertThat(this.items.zipLink(RUN, "1", null).status).isEqualTo(403);
        this.as("TENANT_ADMIN", 1015L);
        list.jobQueueId = 4242L;
        assertThat(this.items.list(list).status).isEqualTo(404);
        assertThat(this.items.detail(RUN, "99", null).status).isEqualTo(404);
    }

    private void file(String step, String name) {
        ItemRow.FileRow file = new ItemRow.FileRow();
        file.jobQueueId = RUN;
        file.attempt = 1;
        file.itemKey = "referrals/r-1.pdf";
        file.stepIndex = "report".equals(step) ? 2 : 3;
        file.stepKey = step;
        file.role = "output";
        file.fileName = name;
        file.format = name.substring(name.lastIndexOf('.') + 1);
        this.store.addFiles(Collections.singletonList(file));
    }

    /**
     * ZT-I35 (live run 2950, "RPT-0086"): an item's report PDF says which Report Studio report it is -- id, number, version and
     * pages -- in the item's detail and its files, for its preview, its approval and Verify; a plain PDF and a JSON file say
     * nothing of the kind, and a report the run kept with no number yet is not named.
     */
    @Test
    void anItemsReportPdfSaysWhichReportItIsAndAPlainFileDoesNot() throws Exception {
        this.as("TENANT_ADMIN", 1015L);
        this.file("report", "Patient_intake_S-1151_v1.pdf");
        this.file("json", "intake-S-1151.json");
        this.file("notes", "notes.pdf");
        RunItemStore.KeptReport kept = new RunItemStore.KeptReport();
        kept.jobQueueId = RUN;
        kept.stepKey = "report";
        kept.itemTag = "t1";
        kept.itemKey = "referrals/r-1.pdf";
        kept.dataHash = "h1";
        kept.reportId = "01M5Y3GYFKZRAV9KKQ74D413D6";
        kept.reportLabel = "RPT-0086";
        kept.reportVersion = 1;
        kept.fileName = "Patient_intake_S-1151_v1.pdf";
        kept.pages = 4;
        kept.madeAttempt = 1;
        this.store.keepReport(kept);
        RunItems.FilesRequest request = new RunItems.FilesRequest();
        request.jobQueueId = RUN;
        request.ref = "1";
        Map<String, JsonNode> byName = new LinkedHashMap<>();
        for (JsonNode f : this.data(this.items.files(request)).path("files")) {
            byName.put(f.path("name").asText(), f);
        }
        JsonNode report = byName.get("Patient_intake_S-1151_v1.pdf").path("report");
        assertThat(report.path("reportId").asText()).isEqualTo("01M5Y3GYFKZRAV9KKQ74D413D6");
        assertThat(report.path("number").asText()).isEqualTo("RPT-0086");
        assertThat(report.path("version").asInt()).isEqualTo(1);
        assertThat(report.path("pages").asInt()).isEqualTo(4);
        assertThat(byName.get("intake-S-1151.json").has("report")).as("a JSON file is no report").isFalse();
        assertThat(byName.get("notes.pdf").has("report")).as("a PDF no report step kept").isFalse();
        JsonNode inDetail = this.data(this.items.detail(RUN, "1", null)).path("files");
        assertThat(inDetail.findValues("report")).extracting(r -> r.path("number").asText()).containsExactly("RPT-0086");

        kept.reportLabel = null;
        assertThat(this.data(this.items.files(request)).path("files").findValues("report")).as("no number yet: not named").isEmpty();
    }

    /** ZT-I35 (live run 2950): a step's one file is named in the item's cell -- its name and type -- and two or more are counted. */
    @Test
    void aStepsOneFileIsNamedInTheItemsCellAndMoreAreCounted() throws Exception {
        this.as("TENANT_ADMIN", 1015L);
        ItemRow row = new ItemRow();
        row.jobQueueId = RUN;
        row.attempt = 1;
        row.itemKey = "referrals/r-3.pdf";
        row.itemRef = "3";
        row.label = "r-3.pdf";
        row.position = 3;
        row.status = "done";
        String[][] made = {{"2", "report", "1"}, {"3", "json", "1"}, {"4", "pages", "2"}};
        for (String[] m : made) {
            ItemRow.StepEntry entry = new ItemRow.StepEntry();
            entry.i = Integer.parseInt(m[0]);
            entry.key = m[1];
            entry.status = "done";
            entry.files = Integer.valueOf(m[2]);
            row.steps.add(entry);
        }
        this.store.save(Collections.singletonList(row));
        String[][] files = {{"report", "Patient_intake_S-1151_v1.pdf"}, {"json", "intake-S-1151.json"}, {"pages", "page-01.png"},
            {"pages", "page-02.png"}};
        for (String[] f : files) {
            ItemRow.FileRow file = new ItemRow.FileRow();
            file.jobQueueId = RUN;
            file.attempt = 1;
            file.itemKey = "referrals/r-3.pdf";
            file.stepKey = f[0];
            file.role = "output";
            file.fileName = f[1];
            this.store.addFiles(Collections.singletonList(file));
        }
        RunItems.ListRequest list = new RunItems.ListRequest();
        list.jobQueueId = RUN;
        JsonNode item = null;
        for (JsonNode i : this.data(this.items.list(list)).path("items")) {
            if ("3".equals(i.path("ref").asText())) {
                item = i;
            }
        }
        assertThat(item).isNotNull();
        JsonNode cells = item.path("cells");
        assertThat(cells.path("report").path("file").path("name").asText()).isEqualTo("Patient_intake_S-1151_v1.pdf");
        assertThat(cells.path("report").path("file").path("format").asText()).isEqualTo("pdf");
        assertThat(cells.path("json").path("file").path("name").asText()).isEqualTo("intake-S-1151.json");
        assertThat(cells.path("pages").has("file")).as("two files are counted, not named").isFalse();
        assertThat(cells.path("pages").path("files").asInt()).isEqualTo(2);
    }

    @Test
    void aZipLinkIsThePersonsTheRunsAndTheItemsAloneAndExpires() throws Exception {
        this.as("TENANT_ADMIN", 1015L);
        ItemRow.FileRow file = new ItemRow.FileRow();
        file.jobQueueId = RUN;
        file.attempt = 1;
        file.itemKey = "referrals/r-1.pdf";
        file.stepIndex = 1;
        file.stepKey = "extract";
        file.role = "output";
        file.fileName = "r-1.json";
        this.store.addFiles(Collections.singletonList(file));
        RunItems.Answer link = this.items.zipLink(RUN, "1", null);
        String token = this.data(link).path("token").asText();
        assertThat(this.items.zip(RUN, token).status).isEqualTo(200);
        assertThat(this.items.zip(RUN + 1, token).status).as("another run").isEqualTo(403);
        this.as("TENANT_USER", 1016L);
        assertThat(this.items.zip(RUN, token).status).as("another person").isEqualTo(403);
        TenantContext.set(1007L, "TENANT_ADMIN", 1015L, "x");
        assertThat(this.items.zip(RUN, token).status).as("another workspace").isEqualTo(403);
        // Expired: a link from a clock three minutes back.
        ItemLinks past = new ItemLinks("test-service-token", Clock.offset(Clock.systemUTC(), Duration.ofMinutes(-3)));
        String old = past.issue(TENANT, 1015L, RUN, "1", null)[0];
        assertThat(new ItemLinks("test-service-token").check(old)).isNull();
        assertThat(new ItemLinks("another-token").check(token)).as("signed by another key").isNull();
    }

    /**
     * MIG-421, ZT-I21 (live run 1855): a zip of selected items is a folder per item -- its files and its item.json, masked for
     * the person -- even for items with no file of their own (rows of a table pipeline), which v2 first refused ("no files").
     */
    @Test
    void aZipOfSelectedItemsIsAFolderEachWithItsMaskedValuesThoughTheyHaveNoFiles() throws Exception {
        this.as("TENANT_USER", 1016L);
        this.maskedForThePerson();
        RunItems.ListRequest selection = new RunItems.ListRequest();
        selection.jobQueueId = RUN;
        selection.refs = Arrays.asList("1", "2");
        JsonNode counted = this.data(this.items.selection(selection));
        assertThat(counted.path("items").asInt()).isEqualTo(2);
        assertThat(counted.path("failed").asInt()).isEqualTo(1);
        JsonNode link = this.data(this.items.zipLinkOfMany(selection, null));
        assertThat(link.path("items").asInt()).isEqualTo(2);
        RunItems.Answer zip = this.items.zip(RUN, link.path("token").asText());
        assertThat(zip.status).isEqualTo(200);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        zip.stream.writeTo(out);
        Map<String, String> entries = new TreeMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                entries.put(e.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(entries.keySet()).containsExactly("1-r-1.pdf/item.json", "2-r-2.pdf/item.json");
        assertThat(entries.get("1-r-1.pdf/item.json")).contains("\"risk\" : \"High\"").doesNotContain("Ana Lopez");
        assertThat(entries.get("2-r-2.pdf/item.json")).contains("\"status\" : \"failed\"").doesNotContain("Ben Ortiz");
        // All matching less the unticked.
        RunItems.ListRequest all = new RunItems.ListRequest();
        all.jobQueueId = RUN;
        all.except = Collections.singletonList("1");
        assertThat(this.data(this.items.selection(all)).path("refs").toString()).isEqualTo("[\"2\"]");
    }
}

package process.pipeline.items;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.pipeline.Dataset;
import process.pipeline.DatasetStore;
import process.pipeline.InMemoryDatasetStore;
import process.pipeline.InMemoryStepStore;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.StepStore;
import process.pipeline.StepTasks;
import process.pipeline.backing.ReportStudio;
import process.security.TenantContext;

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
 * MIG-421: a schedule's items across its runs -- found by name or by a value as the person sees it (a masked value is never
 * found by its text), newest first -- and one item in two runs compared field by field, also when the same file came from
 * another folder (ZT-I22: its key differs, its name does not).
 */
class ItemsAcrossRunsTest {

    private static final long TENANT = 1009L;
    private static final long JOB = 1037L;
    private static final long OLD = 1850L;
    private static final long NEW = 1857L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final InMemoryRunItemStore store = new InMemoryRunItemStore();
    private final InMemoryStepStore steps = new InMemoryStepStore();
    private final InMemoryDatasetStore datasets = new InMemoryDatasetStore();
    private final JobQueueRepository runs = mock(JobQueueRepository.class);
    private final SourceJobRepository jobs = mock(SourceJobRepository.class);
    private final CatalogMasks catalog = mock(CatalogMasks.class);
    private ItemsAcrossRuns across;

    @BeforeEach
    void setUp() throws Exception {
        RunItems items = new RunItems(this.store, this.runs, this.jobs, this.steps, mock(PipelineDefinitionStore.class), new StepTasks(new ArrayList<>()),
            this.datasets, mock(ItemAccessLog.class));
        items.useCatalog(this.catalog);
        this.across = new ItemsAcrossRuns(items, this.store, this.jobs, this.steps, mock(PipelineDefinitionStore.class), this.datasets);
        SourceJob job = new SourceJob();
        job.setJobId(JOB);
        job.setTenantId(TENANT);
        job.setJobStatus(Status.Active);
        job.setCreatedBy(1025L);
        when(this.jobs.findById(JOB)).thenReturn(Optional.of(job));
        this.run(OLD, "voice-intake/mig420-0038/call-a.m4a", "High", "Pat Doe (synthetic)", Instant.parse("2026-10-21T14:00:00Z"));
        this.run(NEW, "voice-intake/mig420-0231/call-a.m4a", "Moderate", "Pat Doe (synthetic)", Instant.parse("2026-10-22T07:30:00Z"));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void run(long id, String key, String risk, String name, Instant started) throws Exception {
        JobQueue run = new JobQueue();
        run.setJobQueueId(id);
        run.setJobId(JOB);
        run.setTenantId(TENANT);
        run.setJobStatus(JobStatus.Completed);
        when(this.runs.findById(id)).thenReturn(Optional.of(run));
        long step = this.steps.plan(id, 1, 1L, Collections.singletonList(new StepStore.Planned(3, "insight", "ai_prompt", "fail"))).get(0);
        String dataset = DatasetStore.keyOf(id, 1, "insight", "output");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("status.risk", risk);
        row.put("patient.name", name);
        row.put("transcript", "The same words, word for word.");
        this.datasets.write(dataset, new Dataset(new ArrayList<>(row.keySet()), Collections.singletonList(row)));
        long ds = this.steps.dataset(step, "output", dataset, 1, "[]", Instant.now().plusSeconds(3600));
        ItemRow item = new ItemRow();
        item.jobQueueId = id;
        item.attempt = 1;
        item.itemKey = key;
        item.itemRef = "1";
        item.label = "call-a.m4a";
        item.position = 1;
        item.status = ItemRow.DONE;
        item.pipelineVersion = 3;
        item.fields = new LinkedHashMap<>(row);
        item.fieldsDs = ds;
        ItemRow.StepEntry entry = new ItemRow.StepEntry();
        entry.i = 3;
        entry.key = "insight";
        entry.task = "ai_prompt";
        entry.status = "done";
        entry.ds = ds;
        entry.rows = Collections.singletonList(0);
        entry.n = 1;
        item.steps.add(entry);
        this.store.save(Collections.singletonList(item));
        this.store.run(id, JOB, "Completed", started, "manual");
    }

    private JsonNode data(RunItems.Answer answer) {
        assertThat(answer.status).as(answer.body == null ? "" : answer.body.getMessage()).isEqualTo(200);
        return JSON.valueToTree(answer.body.getData());
    }

    private static ItemsAcrossRuns.Request search(String text) {
        ItemsAcrossRuns.Request r = new ItemsAcrossRuns.Request();
        r.jobId = JOB;
        r.search = text;
        r.fields = Arrays.asList("status.risk", "patient.name");
        return r;
    }

    @Test
    void anItemsRunsAreListedNewestFirstAndAValueIsFoundOnlyAsThePersonSeesIt() throws Exception {
        TenantContext.set(TENANT, "TENANT_ADMIN", 1025L, "manager@zanium.test");
        JsonNode found = this.data(this.across.list(search("call-a.m4a")));
        assertThat(found.path("total").asInt()).isEqualTo(2);
        List<Long> order = new ArrayList<>();
        found.path("items").forEach(i -> order.add(i.path("jobQueueId").asLong()));
        assertThat(order).containsExactly(NEW, OLD);
        assertThat(found.path("items").get(0).path("fields").path("status.risk").asText()).isEqualTo("Moderate");
        assertThat(found.path("items").get(0).path("triggerLabel").asText()).isEqualTo("Run now");
        assertThat(this.data(this.across.list(search("risk = High"))).path("total").asInt()).as("the last part of a field's name").isEqualTo(1);

        // A person the catalog masks names for: the name is masked in the list and never found by its text.
        TenantContext.set(TENANT, "TENANT_USER", 1025L, "manager@zanium.test");
        when(this.catalog.forCaller()).thenReturn(JSON.readTree("[{\"columns\":{\"patient.name\":\"person_name\"},\"kinds\":[]}]"));
        JsonNode masked = this.data(this.across.list(search("call-a.m4a")));
        assertThat(masked.toString()).doesNotContain("Pat Doe");
        assertThat(masked.path("masked").toString()).contains("patient.name");
        assertThat(this.data(this.across.list(search("patient.name = Pat Doe (synthetic)"))).path("total").asInt()).isZero();
        assertThat(this.data(this.across.list(search("Pat Doe"))).path("total").asInt()).isZero();
    }

    @Test
    void oneItemInTwoRunsIsComparedFieldByFieldThoughItCameFromAnotherFolder() {
        TenantContext.set(TENANT, "TENANT_ADMIN", 1025L, "manager@zanium.test");
        JsonNode c = this.data(this.across.compare(JOB, "voice-intake/mig420-0231/call-a.m4a", NEW, OLD, "voice-intake/mig420-0038/call-a.m4a"));
        assertThat(c.path("a").path("jobQueueId").asLong()).isEqualTo(NEW);
        assertThat(c.path("changed").asInt()).as("the folder is said once, not as a change at every step").isEqualTo(1);
        assertThat(c.path("keyNote").asText()).contains("voice-intake/mig420-0038/call-a.m4a in #1850")
            .contains("voice-intake/mig420-0231/call-a.m4a in #1857");
        JsonNode step = c.path("steps").get(0);
        JsonNode risk = null;
        for (JsonNode ch : step.path("changes")) {
            if ("status.risk".equals(ch.path("path").asText())) {
                risk = ch;
            }
        }
        assertThat(risk).isNotNull();
        assertThat(risk.path("before").asText()).isEqualTo("\"High\"");
        assertThat(risk.path("after").asText()).isEqualTo("\"Moderate\"");
        assertThat(step.path("changes").toString()).doesNotContain("transcript");
        assertThat(c.path("pipeline").path("note").asText()).contains("pipeline version 3");
        // Not the same item (another name) is refused; a run of another schedule is not found.
        assertThat(this.across.compare(JOB, "voice-intake/mig420-0231/call-a.m4a", NEW, OLD, "nope").status).isEqualTo(404);
        assertThat(this.across.compare(9999L, "voice-intake/mig420-0231/call-a.m4a", NEW, OLD, null).status).isEqualTo(404);
    }

    /**
     * ZT-I23 (live: runs 1860 and 1857 of call-a.m4a, "3 steps · 14 fields changed"): an AI step's insight_json and the
     * columns flattened from it, carried on by two later steps, listed one change five times. Each change is said once,
     * under the step that made it, by its clearest name; a later step's own new field is its own; a case-only change is
     * marked so; the count is of those.
     */
    @Test
    void eachChangeIsSaidOnceUnderTheStepThatMadeItAndTheCountIsOfThose() throws Exception {
        long older = 1870L;
        long newer = 1871L;
        this.stepsRun(older, "voice-intake/a/call-a.m4a", Instant.parse("2026-10-21T14:00:00Z"), "Synthetic test patient", "Call Pat on Monday.");
        this.stepsRun(newer, "voice-intake/b/call-a.m4a", Instant.parse("2026-10-22T14:00:00Z"), "synthetic test patient", "Call Pat on Tuesday.");
        TenantContext.set(TENANT, "TENANT_ADMIN", 1025L, "manager@zanium.test");
        JsonNode c = this.data(this.across.compare(JOB, "voice-intake/b/call-a.m4a", newer, older, "voice-intake/a/call-a.m4a"));
        Map<String, List<String>> byStep = new LinkedHashMap<>();
        Map<String, Integer> carried = new LinkedHashMap<>();
        for (JsonNode step : c.path("steps")) {
            List<String> paths = new ArrayList<>();
            step.path("changes").forEach(ch -> paths.add(ch.path("path").asText() + (ch.path("caseOnly").asBoolean(false) ? " (case only)" : "")));
            byStep.put(step.path("key").asText(), paths);
            carried.put(step.path("key").asText(), step.path("carried").asInt());
        }
        assertThat(byStep.get("insight")).containsExactly("followUp", "person.program (case only)");
        assertThat(byStep.get("fields")).as("a field the step made is its own; the carried ones are not said again")
            .containsExactly("patient.program (case only)");
        assertThat(byStep.get("save")).isEmpty();
        assertThat(carried).containsEntry("fields", 2).containsEntry("save", 3);
        assertThat(c.path("changed").asInt()).isEqualTo(3);
        JsonNode followUp = c.path("steps").get(0).path("changes").get(0);
        assertThat(followUp.path("alsoAt").toString()).contains("insight_json.followUp");
        assertThat(followUp.path("caseOnly").isMissingNode()).as("a real change is not case only").isTrue();
        assertThat(c.path("steps").get(0).path("same").asBoolean()).isFalse();
    }

    /** A run of call-a.m4a whose AI step keeps insight_json and its flattened columns, carried by Report fields and Save file. */
    private void stepsRun(long id, String key, Instant started, String program, String followUp) throws Exception {
        JobQueue run = new JobQueue();
        run.setJobQueueId(id);
        run.setJobId(JOB);
        run.setTenantId(TENANT);
        run.setJobStatus(JobStatus.Completed);
        when(this.runs.findById(id)).thenReturn(Optional.of(run));
        List<StepStore.Planned> plan = Arrays.asList(new StepStore.Planned(3, "insight", "ai_prompt", "fail"),
            new StepStore.Planned(4, "fields", "compute", "fail"), new StepStore.Planned(5, "save", "save_file", "fail"));
        List<Long> ids = this.steps.plan(id, 1, 1L, plan);
        Map<String, Object> insight = new LinkedHashMap<>();
        insight.put("key", key);
        insight.put("insight_json", "{\"followUp\":\"" + followUp + "\",\"person\":{\"program\":\"" + program + "\"}}");
        insight.put("followUp", followUp);
        insight.put("person.program", program);
        Map<String, Object> fields = new LinkedHashMap<>(insight);
        fields.put("patient.program", program);
        Map<String, Object> save = new LinkedHashMap<>(fields);
        save.put("file", "insight-call-a.json");
        List<Map<String, Object>> rows = Arrays.asList(insight, fields, save);
        ItemRow item = new ItemRow();
        item.jobQueueId = id;
        item.attempt = 1;
        item.itemKey = key;
        item.itemRef = "1";
        item.label = "call-a.m4a";
        item.position = 1;
        item.status = ItemRow.DONE;
        item.pipelineVersion = 3;
        for (int i = 0; i < plan.size(); i++) {
            String dataset = DatasetStore.keyOf(id, 1, plan.get(i).key, "output");
            this.datasets.write(dataset, new Dataset(new ArrayList<>(rows.get(i).keySet()), Collections.singletonList(rows.get(i))));
            long ds = this.steps.dataset(ids.get(i), "output", dataset, 1, "[]", Instant.now().plusSeconds(3600));
            ItemRow.StepEntry entry = new ItemRow.StepEntry();
            entry.i = plan.get(i).index;
            entry.key = plan.get(i).key;
            entry.task = plan.get(i).task;
            entry.status = "done";
            entry.ds = ds;
            entry.rows = Collections.singletonList(0);
            entry.n = 1;
            item.steps.add(entry);
        }
        this.store.save(Collections.singletonList(item));
        this.store.run(id, JOB, "Completed", started, "manual");
    }

    /**
     * ZT-I24 (live: #1860 "RPT-0019", #1857 and #1850 "Call_insight_call-a.m4a_v1.pdf"): a run older than its record of its
     * reports listed its report by the PDF's name. Report Studio names it by its number, matched by the PDF's sha256; a plain
     * file no report made stays its file's name, with no number or version.
     */
    @Test
    void aReportIsItsNumberAndVersionInEveryRunAndOnlyAPlainFileIsItsName() throws Exception {
        long plain = 1849L;
        this.run(plain, "voice-intake/mig420-0001/call-a.m4a", "Low", "Pat Doe (synthetic)", Instant.parse("2026-10-20T14:00:00Z"));
        this.store.keepReport(report(NEW, "RPT-0019", 1, "Call_insight_call-a.m4a_v1.pdf", null));
        this.store.keepReport(report(OLD, null, 0, "Call_insight_call-a.m4a_v1.pdf", "8c407e1e32dbaed2713eb0e8ca806049e3b6120a2bd6f2715418b2a04b30fea7"));
        this.store.keepReport(report(plain, null, 0, "call-a-notes.pdf", "0000000000000000000000000000000000000000000000000000000000000000"));
        ReportStudio studio = mock(ReportStudio.class);
        when(studio.runReports(TENANT, OLD)).thenReturn(JSON.readTree("[{\"reportId\":\"01M5H8G4NMK2WD3CF0QK2266MP\",\"reportLabel\":\"RPT-0013\","
            + "\"version\":1,\"sha256\":\"8c407e1e32dbaed2713eb0e8ca806049e3b6120a2bd6f2715418b2a04b30fea7\"}]"));
        when(studio.runReports(TENANT, plain)).thenReturn(JSON.readTree("[]"));
        this.across.useStudio(studio);
        TenantContext.set(TENANT, "TENANT_ADMIN", 1025L, "manager@zanium.test");
        Map<Long, JsonNode> byRun = new LinkedHashMap<>();
        for (JsonNode item : this.data(this.across.list(search("call-a.m4a"))).path("items")) {
            byRun.put(item.path("jobQueueId").asLong(), item.path("report"));
        }
        assertThat(byRun.get(NEW).path("number").asText()).isEqualTo("RPT-0019");
        assertThat(byRun.get(NEW).path("version").asInt()).isEqualTo(1);
        assertThat(byRun.get(OLD).path("number").asText()).as("named by its number, not its file").isEqualTo("RPT-0013");
        assertThat(byRun.get(OLD).path("version").asInt()).isEqualTo(1);
        assertThat(byRun.get(plain).path("number").isNull()).as("a plain file has no number").isTrue();
        assertThat(byRun.get(plain).path("version").isNull()).isTrue();
        assertThat(byRun.get(plain).path("label").asText()).isEqualTo("call-a-notes.pdf");
    }

    private static RunItemStore.KeptReport report(long run, String label, int version, String fileName, String sha256) {
        RunItemStore.KeptReport r = new RunItemStore.KeptReport();
        r.jobQueueId = run;
        r.stepKey = "report";
        r.itemTag = "t" + run;
        r.dataHash = "h" + run;
        r.reportLabel = label;
        r.reportVersion = version;
        r.fileName = fileName;
        r.sha256 = sha256;
        r.madeAttempt = 1;
        r.itemKey = run == NEW ? "voice-intake/mig420-0231/call-a.m4a" : run == OLD ? "voice-intake/mig420-0038/call-a.m4a"
            : "voice-intake/mig420-0001/call-a.m4a";
        return r;
    }

    @Test
    void thePlatformAdministratorsOwnSignInReadsNone() {
        TenantContext.set(TENANT, "PLATFORM_ADMIN", 1001L, "platform@etl.test");
        assertThat(this.across.list(search("call-a.m4a")).status).isEqualTo(403);
        assertThat(this.across.compare(JOB, "k", NEW, OLD, null).status).isEqualTo(403);
    }

    /**
     * Owner 2026-10-27 ("add a sort on each table"): sorted by Run, the oldest run's item comes first ascending and last
     * descending, one per page of one with no repeat; an unknown column is a 400 naming the four the list sorts by.
     */
    @Test
    void theItemsAcrossRunsSortByAColumnAndAnUnknownOneIsRefused() {
        TenantContext.set(TENANT, "TENANT_ADMIN", 1025L, "manager@zanium.test");
        for (String dir : new String[] {"asc", "desc"}) {
            List<Long> order = new ArrayList<>();
            for (int page = 0; page < 2; page++) {
                ItemsAcrossRuns.Request r = search(null);
                r.sort = "run";
                r.dir = dir;
                r.page = page;
                r.size = 1;
                this.data(this.across.list(r)).path("items").forEach(i -> order.add(i.path("jobQueueId").asLong()));
            }
            assertThat(order).as(dir).containsExactlyElementsOf("asc".equals(dir) ? Arrays.asList(OLD, NEW) : Arrays.asList(NEW, OLD));
        }
        ItemsAcrossRuns.Request unknown = search(null);
        unknown.sort = "pipelineVersion";
        RunItems.Answer refused = this.across.list(unknown);
        assertThat(refused.status).isEqualTo(400);
        assertThat(refused.body.getMessage()).isEqualTo("sort must be one of: run, label, startedAt, status.");
    }
}

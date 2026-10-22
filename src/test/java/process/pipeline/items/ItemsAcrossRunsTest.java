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

    @Test
    void thePlatformAdministratorsOwnSignInReadsNone() {
        TenantContext.set(TENANT, "PLATFORM_ADMIN", 1001L, "platform@etl.test");
        assertThat(this.across.list(search("call-a.m4a")).status).isEqualTo(403);
        assertThat(this.across.compare(JOB, "k", NEW, OLD, null).status).isEqualTo(403);
    }
}

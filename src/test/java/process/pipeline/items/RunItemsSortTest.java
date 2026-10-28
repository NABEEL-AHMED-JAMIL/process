package process.pipeline.items;

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
import process.pipeline.StepStore;
import process.pipeline.StepTasks;
import process.security.TenantContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Owner 2026-10-27 ("add a sort on each table"): a run's Items table sorts by # (position), Item (its name, ignoring case),
 * Status (failed, sub-items failed, check by hand, running, done) and Time, both ways -- before the page is cut, an item
 * with no time last either way, ties in item order -- and anything else is a 400 naming the four.
 */
class RunItemsSortTest {

    private static final long TENANT = 1006L;
    private static final long RUN = 9021L;
    private static final long JOB = 1079L;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final InMemoryRunItemStore store = new InMemoryRunItemStore();
    private final InMemoryStepStore steps = new InMemoryStepStore();
    private final InMemoryDatasetStore datasets = new InMemoryDatasetStore();
    private final JobQueueRepository runs = mock(JobQueueRepository.class);
    private final SourceJobRepository jobs = mock(SourceJobRepository.class);
    private RunItems items;
    private long ds;

    @BeforeEach
    void setUp() throws Exception {
        this.items = new RunItems(this.store, this.runs, this.jobs, this.steps, mock(PipelineDefinitionStore.class), new StepTasks(new ArrayList<>()),
            this.datasets, mock(ItemAccessLog.class));
        this.items.useCatalog(mock(CatalogMasks.class));
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
        long step = this.steps.plan(RUN, 1, 1L, Collections.singletonList(new StepStore.Planned(1, "read", "read_s3", "fail"))).get(0);
        String key = DatasetStore.keyOf(RUN, 1, "read", "output");
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("key", "synthetic");
        rows.add(one);
        this.datasets.write(key, new Dataset(Collections.singletonList("key"), rows));
        this.ds = this.steps.dataset(step, "output", key, 1, "[]", Instant.now().plusSeconds(3600));
        // # , name, status, time (ms), flag, failed sub-items.
        this.item(0, "delta.pdf", "done", 300L, null, 0);
        this.item(1, "Alpha.pdf", "failed", 100L, null, 0);
        this.item(2, "charlie.pdf", "running", null, null, 0);
        this.item(3, "bravo.pdf", "done", 300L, "check", 0);
        this.item(4, "echo.pdf", "done", 50L, null, 2);
        this.item(5, "alpha.pdf", "done", null, null, 0);
        this.item(6, "foxtrot.pdf", "done", 200L, null, 0);
        TenantContext.set(TENANT, "TENANT_ADMIN", 1015L, "admin@riverside.test");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void item(int position, String label, String status, Long ms, String flag, int failedChildren) {
        ItemRow row = new ItemRow();
        row.jobQueueId = RUN;
        row.attempt = 1;
        row.itemKey = "inbox/" + label;
        row.itemRef = String.valueOf(position + 1);
        row.label = label;
        row.position = position;
        row.status = status;
        row.durationMs = ms;
        row.flag = flag;
        row.childCount = failedChildren > 0 ? failedChildren + 1 : 0;
        row.childFailed = failedChildren;
        if ("failed".equals(status)) {
            row.failedStepIndex = 1;
            row.failedStepKey = "read";
            row.reason = "The file could not be read.";
        }
        ItemRow.StepEntry entry = new ItemRow.StepEntry();
        entry.i = 1;
        entry.key = "read";
        entry.task = "read_s3";
        entry.status = status;
        entry.ds = this.ds;
        entry.rows = Collections.singletonList(0);
        entry.n = 1;
        row.steps.add(entry);
        this.store.save(Collections.singletonList(row));
    }

    private RunItems.Answer list(String sort, String dir, int page, int size) {
        RunItems.ListRequest request = new RunItems.ListRequest();
        request.jobQueueId = RUN;
        request.sort = sort;
        request.dir = dir;
        request.page = page;
        request.size = size;
        return this.items.list(request);
    }

    private JsonNode data(RunItems.Answer answer) {
        assertThat(answer.status).as(answer.body == null ? "" : answer.body.getMessage()).isEqualTo(200);
        return JSON.valueToTree(answer.body.getData());
    }

    /** The refs (position + 1) of every item, read a page of {@code size} at a time. */
    private List<String> refs(String sort, String dir, int size) {
        List<String> out = new ArrayList<>();
        for (int page = 0; page * size < 7; page++) {
            for (JsonNode item : this.data(this.list(sort, dir, page, size)).path("items")) {
                out.add(item.path("ref").asText());
            }
        }
        return out;
    }

    @Test
    void everyColumnSortsBothWaysAcrossPagesWithoutRepeatsOrGaps() {
        assertThat(this.refs("position", "asc", 3)).containsExactly("1", "2", "3", "4", "5", "6", "7");
        assertThat(this.refs("position", "desc", 3)).containsExactly("7", "6", "5", "4", "3", "2", "1");
        // Ignoring case; the two alphas tie and keep item order, reversed when descending.
        assertThat(this.refs("label", "asc", 3)).containsExactly("2", "6", "4", "3", "1", "5", "7");
        assertThat(this.refs("label", "desc", 3)).containsExactly("7", "5", "1", "3", "4", "6", "2");
        // Failed, then a document with failed pages, check by hand, running, done -- as the chips read.
        assertThat(this.refs("status", "asc", 3)).containsExactly("2", "5", "4", "3", "1", "6", "7");
        assertThat(this.refs("status", "desc", 3)).containsExactly("7", "6", "1", "3", "4", "5", "2");
        // No time yet (running, or never said) goes last both ways.
        assertThat(this.refs("time", "asc", 3)).containsExactly("5", "2", "7", "1", "4", "3", "6");
        assertThat(this.refs("time", "desc", 3)).containsExactly("4", "1", "7", "2", "5", "6", "3");
        // One page of two at a time: the same order, every item once.
        assertThat(this.refs("time", "desc", 2)).containsExactly("4", "1", "7", "2", "5", "6", "3").doesNotHaveDuplicates();
    }

    @Test
    void theAnswerSaysWhichOrderItIsInAndNoSortIsItemOrder() {
        JsonNode sorted = this.data(this.list("label", "desc", 0, 50));
        assertThat(sorted.path("sort").asText()).isEqualTo("label");
        assertThat(sorted.path("dir").asText()).isEqualTo("desc");
        JsonNode plain = this.data(this.list(null, null, 0, 50));
        assertThat(plain.has("sort")).isFalse();
        assertThat(plain.path("items")).extracting(n -> n.path("ref").asText()).containsExactly("1", "2", "3", "4", "5", "6", "7");
    }

    @Test
    void anUnknownColumnOrDirectionIsA400NamingTheColumns() {
        RunItems.Answer unknown = this.list("reason", "asc", 0, 50);
        assertThat(unknown.status).isEqualTo(400);
        assertThat(unknown.body.getMessage()).isEqualTo("sort must be one of: position, label, status, time.");
        RunItems.Answer sideways = this.list("time", "sideways", 0, 50);
        assertThat(sideways.status).isEqualTo(400);
        assertThat(sideways.body.getMessage()).isEqualTo("dir must be asc or desc.");
        assertThat(RunItems.SORTS).containsExactly("position", "label", "status", "time");
    }
}

package process.pipeline;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;
import process.engine.BulkAction;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.service.NotifyService;
import process.model.service.impl.TransactionServiceImpl;
import process.pipeline.backing.BucketStore;
import process.pipeline.items.InMemoryRunItemStore;
import process.pipeline.items.ItemRow;
import process.pipeline.registry.InMemoryTaskOverrideStore;
import process.pipeline.registry.ItemRole;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskRegistry;
import process.pipeline.registry.TaskSpec;
import process.pipeline.tasks.AggregateStepTask;
import process.pipeline.tasks.SampleStepTask;
import process.pipeline.tasks.ReadS3StepTask;
import process.pipeline.tasks.SaveFileStepTask;
import process.pipeline.tasks.SplitPagesStepTask;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;
import static process.pipeline.Definitions.sample;
import static process.pipeline.Definitions.step;

/**
 * MIG-420, the Run Items view, through the step engine and RunRetries: an item pipeline's items, each with its status, its
 * reason, its steps' pointers and its files; a big table read whole makes none; a fan-out's pages are sub-items; and a retry
 * of the failed items runs only them -- each per-row call keyed by its item, the same in both attempts, so the others are
 * neither called nor billed again -- each retried item a new version, the earlier kept.
 */
class RunItemsEngineTest {

    private static final long TENANT = 1009L;
    private static final long JOB_ID = 1037L;
    private static final long RUN_ID = 8814L;
    private static final long USER = 1025L;

    private final InMemoryStepStore steps = new InMemoryStepStore();
    private final InMemoryDatasetStore datasets = new InMemoryDatasetStore();
    private final InMemoryRunItemStore items = new InMemoryRunItemStore();
    private final RunRetriesTest.MemoryAttempts attempts = new RunRetriesTest.MemoryAttempts();
    private final PipelineDefinitionStore definitions = mock(PipelineDefinitionStore.class);
    private final TransactionServiceImpl transactions = mock(TransactionServiceImpl.class);
    private final BulkAction bulk = mock(BulkAction.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Pdfs buckets = new Pdfs();
    private final ExecutorService tryThreads = Executors.newCachedThreadPool();
    private final Map<Long, PipelineDefinitionStore.Stored> versions = new LinkedHashMap<>();
    private final List<String> audit = Collections.synchronizedList(new ArrayList<>());
    /** Each per-row "call" a step made: "attempt <n> <key> <tag>" -- what a metered service would bill. */
    private final List<String> billed = new CopyOnWriteArrayList<>();
    /** The keys whose call fails (the model's answer does not match the schema); fixed by emptying it. */
    private final Set<String> broken = ConcurrentHashMap.newKeySet();
    private final List<String> reports = new CopyOnWriteArrayList<>();

    private StepEngine engine;
    private RunRetries retries;
    private JobQueue run;
    private SourceJob job;
    private long nextVersionId = 1200;

    @BeforeEach
    void setUp() {
        StepTasks tasks = new StepTasks(new ArrayList<>(Arrays.asList(new SampleStepTask(), new PerItem(), new SaveFileStepTask(),
            new AggregateStepTask(), new SplitPagesStepTask(this.buckets), new ReadS3StepTask(this.buckets))));
        DefinitionValidator validator = new DefinitionValidator(new TaskRegistry(tasks, new InMemoryTaskOverrideStore()));
        this.engine = new StepEngine(this.definitions, this.steps, tasks, validator, this.datasets, new Worker(), this.transactions,
            TransactionOperations.withoutTransaction(), Executors.newSingleThreadExecutor(), this.tryThreads, duration -> { });
        this.engine.useAttempts(this.attempts);
        this.engine.useItems(this.items);
        this.retries = new RunRetries(this.steps, this.attempts, this.definitions, validator, tasks, this.bulk, this.jdbc,
            TransactionOperations.withoutTransaction());
        this.run = new JobQueue();
        this.run.setJobQueueId(RUN_ID);
        this.run.setJobId(JOB_ID);
        this.run.setTenantId(TENANT);
        this.run.setAttempt(1);
        this.run.setJobStatus(JobStatus.Queue);
        this.run.setInputBucket("zanium-ai-s3");
        SourceTask task = new SourceTask();
        task.setPipelineId("VOICE");
        task.setTaskPayload("<pipeline><x>1</x></pipeline>");
        this.job = new SourceJob();
        this.job.setJobId(JOB_ID);
        this.job.setTenantId(TENANT);
        this.job.setJobStatus(Status.Active);
        this.job.setTaskDetail(task);
        when(this.transactions.findJobQueueByJobQueueId(RUN_ID)).thenAnswer(call -> Optional.of(this.run));
        doAnswer(call -> this.audit.add(call.getArgument(1))).when(this.transactions).saveJobAuditLogs(anyLong(), anyString());
        when(this.definitions.byId(anyLong())).thenAnswer(call -> Optional.ofNullable(this.versions.get((Long) call.getArgument(0))));
        when(this.definitions.latestFor(TENANT, "VOICE")).thenAnswer(call -> this.versions.values().stream().reduce((a, b) -> b));
        when(this.jdbc.update(anyString(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            Object[] args = Arrays.copyOfRange(call.getArguments(), 1, 6);
            if (!this.run.getJobStatus().name().equals(args[3]) || this.run.getAttempt() != ((Number) args[4]).intValue()) {
                return 0;
            }
            this.run.setAttempt(((Number) args[0]).intValue());
            this.run.setJobStatus(JobStatus.Queue);
            this.run.setJobSend(false);
            this.run.setJobStatusMessage((String) args[1]);
            return 1;
        });
    }

    @AfterEach
    void tearDown() {
        this.tryThreads.shutdownNow();
    }

    /** The worker callback, as far as these tests need it: the run's status, as reported. */
    final class Worker implements NotifyService {
        @Override public synchronized ResponseDto changeState(SourceJobQueueDto dto) {
            reports.add(dto.getJobStatus() + ": " + dto.getJobStatusMessage());
            run.setJobStatus(dto.getJobStatus());
            run.setJobStatusMessage(dto.getJobStatusMessage());
            return new ResponseDto("SUCCESS", "ok");
        }

        @Override public ResponseDto addLogs(SourceJobQueueDto dto) { throw new UnsupportedOperationException(); }

        @Override public ResponseDto addLogsBatch(Long jobId, Long jobQueueId, List<String> messages) { throw new UnsupportedOperationException(); }

        @Override public ResponseDto changeState(SourceJobQueueDto dto, String key) { throw new UnsupportedOperationException(); }

        @Override public ResponseDto addLogs(SourceJobQueueDto dto, String key) { throw new UnsupportedOperationException(); }

        @Override public ResponseDto addLogsBatch(Long jobId, Long jobQueueId, List<String> messages, String key) { throw new UnsupportedOperationException(); }

        @Override public void noteRefusedCallback(Long jobQueueId, JobStatus reportedStatus) { throw new UnsupportedOperationException(); }

        @Override public Optional<ResponseDto> replay(Long jobQueueId, JobStatus s, String request, String key) { throw new UnsupportedOperationException(); }
    }

    /** A per-item step (an AI prompt's shape): one "call" per row, keyed by context.rowTag; a broken key's call fails (kept, empty). */
    final class PerItem implements StepTask {
        private final TaskSpec spec;

        PerItem() {
            Map<String, Object> free = new LinkedHashMap<>();
            free.put("type", "object");
            this.spec = TaskSpec.builder("per_item", "Per item", TaskKind.PROCESS).description("one call a row").config(free)
                .items(ItemRole.EACH).build();
        }

        @Override public String code() { return "per_item"; }

        @Override public String description() { return "one call a row"; }

        @Override public TaskSpec spec() { return this.spec; }

        @Override public List<DefinitionProblem> check(Map<String, Object> config) { return Collections.emptyList(); }

        @Override public StepResult run(StepContext context) {
            List<Map<String, Object>> out = new ArrayList<>();
            List<Map<String, Object>> rows = context.input().getRows();
            for (int i = 0; i < rows.size(); i++) {
                Map<String, Object> row = rows.get(i);
                Object key = row.get("key") != null && row.get("page") != null ? row.get("key") + "#" + row.get("page") : row.get("key");
                billed.add("attempt " + context.attempt() + " " + key + " " + context.rowTag(row, i));
                Map<String, Object> answered = new LinkedHashMap<>(row);
                if (broken.contains(String.valueOf(key)) || broken.contains(key + "@" + context.stepKey())) {
                    context.itemFailed(row, "The model's answer did not match the schema.");
                    answered.put("answer", null);
                } else {
                    answered.put("answer", "ok:" + key);
                    context.itemTook(row, 1200);
                }
                out.add(answered);
            }
            return StepResult.of(new Dataset(new ArrayList<>(out.isEmpty() ? context.input().getColumns() : out.get(0).keySet()), out));
        }
    }

    private long saves(PipelineDefinition definition) {
        PipelineDefinitionStore.Stored stored = new PipelineDefinitionStore.Stored();
        stored.id = this.nextVersionId++;
        stored.tenantId = TENANT;
        stored.version = this.versions.size() + 1;
        stored.json = DefinitionCodec.toJson(definition);
        this.versions.put(stored.id, stored);
        return stored.id;
    }

    private void runs() {
        Optional<StepEngine.StepPlan> plan = this.engine.planFor(this.job, this.run);
        assertThat(plan).isPresent();
        this.run.setJobSend(true);
        this.engine.run(plan.get());
        this.job.setJobRunningStatus(this.run.getJobStatus());
    }

    private static PipelineDefinition voice() {
        return Definitions.of(sample("read", row("key", "voice-intake/call-a.m4a"), row("key", "voice-intake/call-b.m4a"),
                row("key", "voice-intake/call-c.m4a")),
            step("ask", "per_item"), step("save", "save_file", config("fileName", "{{row}}-insight.json", "format", "json", "perRow", true)));
    }

    private ItemRow item(String key) {
        return this.items.currentOf(RUN_ID, key);
    }

    @Test
    void anItemPipelineKeepsEachItemWithItsStatusReasonPointersAndFiles() {
        this.broken.add("voice-intake/call-b.m4a");
        this.saves(voice());

        this.runs();

        assertThat(this.run.getJobStatus()).as("a row that failed alone does not fail the run").isEqualTo(JobStatus.Completed);
        assertThat(this.run.getJobStatusMessage()).isEqualTo("3 of 3 step(s) completed. Items: 2 of 3 done; 1 failed at ask: call-b.m4a.");
        List<ItemRow> current = this.items.current(RUN_ID);
        assertThat(current).extracting(r -> r.itemRef + " " + r.label + " " + r.status)
            .containsExactly("1 call-a.m4a done", "2 call-b.m4a failed", "3 call-c.m4a done");
        ItemRow b = this.item("voice-intake/call-b.m4a");
        assertThat(b.failedStepKey).isEqualTo("ask");
        assertThat(b.failedStepIndex).isEqualTo(1);
        assertThat(b.reason).isEqualTo("The model's answer did not match the schema.");
        ItemRow a = this.item("voice-intake/call-a.m4a");
        assertThat(a.steps).extracting(e -> e.key + " " + e.status).containsExactly("ask done", "save done");
        ItemRow.StepEntry ask = a.step(1);
        assertThat(ask.ds).as("a pointer into the step's kept output").isNotNull();
        assertThat(ask.rows).containsExactly(0);
        assertThat(ask.ms).isEqualTo(1200L);
        assertThat(ask.idc).as("the step that named the items says by which column").isEqualTo("key");
        assertThat(this.item("voice-intake/call-c.m4a").step(1).rows).containsExactly(2);
        // One file per row: each is its item's, by the step and the file's name.
        assertThat(this.items.files).filteredOn(f -> "output".equals(f.role)).extracting(f -> f.itemKey + " " + f.fileName)
            .containsExactlyInAnyOrder("voice-intake/call-a.m4a 1-insight.json", "voice-intake/call-b.m4a 2-insight.json",
                "voice-intake/call-c.m4a 3-insight.json");
        assertThat(a.fileCount).as("its recording, from the run's bucket, and its insight").isEqualTo(2);
        assertThat(this.items.files).filteredOn(f -> "input".equals(f.role)).extracting(f -> f.bucketAlias + "/" + f.objectKey)
            .contains("zanium-ai-s3/voice-intake/call-a.m4a");
        assertThat(this.items.journaled).as("run.item.completed for each item").hasSize(3);
        assertThat(this.audit).contains("Items: 3 items, named by key at step 2 <ask>.");
    }

    @Test
    void retryingTheFailedItemRunsOnlyItWithTheSameCallKeyAndKeepsItsEarlierVersion() {
        this.broken.add("voice-intake/call-b.m4a");
        this.saves(voice());
        this.runs();
        List<String> first = new ArrayList<>(this.billed);
        assertThat(first).hasSize(3);
        String bTag = first.get(1).substring(first.get(1).lastIndexOf(' ') + 1);
        assertThat(bTag).as("an item's call key is its token, not its row number").startsWith("i").hasSize(17);
        assertThat(first.stream().map(l -> l.substring(l.lastIndexOf(' ') + 1)).collect(Collectors.toSet())).hasSize(3);
        this.broken.clear();

        RunRetries.Outcome outcome = this.retries.retryItems(this.run, this.job, 1, Collections.singletonList("voice-intake/call-b.m4a"),
            "failed_step", RunRetries.Actor.person(USER, false));
        assertThat(outcome.queued()).as(String.valueOf(outcome.refusal)).isTrue();
        assertThat(this.attempts.ofRun(RUN_ID).get(0).itemKeys).containsExactly("voice-intake/call-b.m4a");
        assertThat(this.run.getJobStatusMessage()).isEqualTo("Attempt 2 queued by user 1025: retry 1 item from the failed step 2 <ask>, "
            + "reusing 1 step(s) of attempt 1; only it is run again.");
        this.runs();

        assertThat(this.run.getJobStatus()).isEqualTo(JobStatus.Completed);
        assertThat(this.billed.subList(3, this.billed.size())).as("only the retried item was called again, under its own key")
            .containsExactly("attempt 2 voice-intake/call-b.m4a " + bTag);
        String aTag = first.get(0).substring(first.get(0).lastIndexOf(' ') + 1);
        assertThat(bTag).as("call-b is row 1 now; it never takes the key row 1 or call-a had").isNotEqualTo(aTag).isNotEqualTo("1");
        ItemRow b = this.item("voice-intake/call-b.m4a");
        assertThat(b.attempt).isEqualTo(2);
        assertThat(b.status).isEqualTo("done");
        assertThat(b.reason).isNull();
        assertThat(b.step(0)).as("the source step is before the items").isNull();
        assertThat(b.step(1).reusedFrom).isNull();
        assertThat(this.items.versions(RUN_ID, "voice-intake/call-b.m4a")).extracting(v -> v.attempt + " " + v.status + " " + v.current)
            .containsExactly("2 done true", "1 failed false");
        assertThat(this.item("voice-intake/call-a.m4a").attempt).as("the others keep their first attempt's version").isEqualTo(1);
        assertThat(this.item("voice-intake/call-c.m4a").attempt).isEqualTo(1);
        assertThat(this.run.getJobStatusMessage()).endsWith("Items: 3 of 3 done.");
        // The retried step read only the retried item's row; the save made only its file.
        assertThat(this.steps.row(RUN_ID, 2, "ask").recordsIn).isEqualTo(1L);
        assertThat(this.items.files).filteredOn(f -> f.attempt == 2 && "output".equals(f.role)).extracting(f -> f.itemKey)
            .containsExactly("voice-intake/call-b.m4a");
        assertThat(this.steps.logText(RUN_ID, 2, "ask")).anyMatch(l -> l.contains("this step reads their 1 of the 3 row(s) its input has"));
    }

    @Test
    void retryingFromTheFailedStepsReusesWhatAnItemPassedBeforeItsOwnFailedStep() {
        // ZT-I11: b failed at <ask>, a at <again>; a retry of both from their failed steps runs <ask> for b alone -- a's
        // earlier answer there is reused, neither called nor billed again -- and <again> for both.
        this.broken.add("in/b.m4a@ask");
        this.broken.add("in/a.m4a@again");
        PipelineDefinition twoSteps = Definitions.of(sample("read", row("key", "in/a.m4a"), row("key", "in/b.m4a"), row("key", "in/c.m4a")),
            step("ask", "per_item"), step("again", "per_item"));
        this.saves(twoSteps);
        this.runs();
        assertThat(this.item("in/a.m4a").failedStepKey).isEqualTo("again");
        assertThat(this.item("in/b.m4a").failedStepKey).isEqualTo("ask");
        this.broken.clear();
        int before = this.billed.size();

        assertThat(this.retries.retryItems(this.run, this.job, 1, Arrays.asList("in/a.m4a", "in/b.m4a"), "failed_step",
            RunRetries.Actor.person(USER, false)).queued()).isTrue();
        this.runs();

        assertThat(this.billed.subList(before, this.billed.size())).extracting(l -> l.substring(0, l.lastIndexOf(' ')))
            .as("a's <ask> call is not made again").containsExactlyInAnyOrder("attempt 2 in/b.m4a", "attempt 2 in/a.m4a", "attempt 2 in/b.m4a");
        assertThat(this.billed.subList(before, before + 1)).allMatch(l -> l.contains(" in/b.m4a "));
        assertThat(this.run.getJobStatus()).isEqualTo(JobStatus.Completed);
        assertThat(this.run.getJobStatusMessage()).endsWith("Items: 3 of 3 done.");
        ItemRow a = this.item("in/a.m4a");
        assertThat(a.attempt).isEqualTo(2);
        assertThat(a.status).isEqualTo("done");
        assertThat(a.step(1).reusedFrom).as("its <ask> answer is attempt 1's").isEqualTo(1);
        assertThat(a.step(1).ms).isEqualTo(1200L);
        assertThat(a.step(2).reusedFrom).isNull();
        ItemRow b = this.item("in/b.m4a");
        assertThat(b.status).isEqualTo("done");
        assertThat(b.step(1).reusedFrom).isNull();
        assertThat(this.steps.row(RUN_ID, 2, "ask").recordsIn).as("<ask> read b's row alone").isEqualTo(1L);
        assertThat(this.steps.row(RUN_ID, 2, "again").recordsIn).as("<again> ran both, a with its attempt-1 answer").isEqualTo(2L);
        assertThat(this.steps.logText(RUN_ID, 2, "ask")).anyMatch(l -> l.contains("2 items not run again here"));
        assertThat(this.item("in/c.m4a").attempt).isEqualTo(1);
    }

    @Test
    void aRetrysStepsOverTheWholeTableStillReadEveryItemsRows() {
        // ZT-I12 (live run 1853): a retry of one item ran its per-item steps for it alone and the steps over the whole table
        // (a whole-table file, an aggregate) then had only its row -- the run's latest file lost the other items. The others'
        // earlier rows are reused at the per-item steps, in the run's order, so the whole-table steps read them all again.
        this.broken.add("in/b.m4a");
        this.saves(Definitions.of(sample("read", row("key", "in/a.m4a"), row("key", "in/b.m4a"), row("key", "in/c.m4a")),
            step("ask", "per_item"), step("total", "aggregate", config("groupBy", Collections.singletonList("key"),
                "aggregations", Collections.singletonList(config("op", "count", "as", "rows"))))));
        this.runs();
        this.broken.clear();
        int before = this.billed.size();

        assertThat(this.retries.retryItems(this.run, this.job, 1, Collections.singletonList("in/b.m4a"), "failed_step",
            RunRetries.Actor.person(USER, false)).queued()).isTrue();
        this.runs();

        assertThat(this.billed.subList(before, this.billed.size())).as("b alone is called again").hasSize(1).allMatch(l -> l.contains(" in/b.m4a "));
        assertThat(this.steps.row(RUN_ID, 2, "ask").recordsIn).isEqualTo(1L);
        assertThat(this.steps.row(RUN_ID, 2, "total").recordsIn).as("the aggregate reads every item's row").isEqualTo(3L);
        assertThat(this.steps.row(RUN_ID, 2, "total").recordsOut).isEqualTo(3L);
        assertThat(this.steps.logText(RUN_ID, 2, "ask")).anyMatch(l -> l.contains("in the run's order"));
        assertThat(this.item("in/b.m4a").status).isEqualTo("done");
        assertThat(this.item("in/a.m4a").attempt).isEqualTo(1);
        assertThat(this.run.getJobStatusMessage()).endsWith("Items: 3 of 3 done.");
    }

    @Test
    void retryingAWholeItemRerunsItsStepsFromTheOneAfterItsSourceAndReusesTheStepsBefore() {
        this.broken.add("in/c.m4a");
        PipelineDefinition twoSteps = Definitions.of(sample("read", row("key", "in/a.m4a"), row("key", "in/b.m4a"), row("key", "in/c.m4a")),
            step("ask", "per_item"), step("again", "per_item"));
        this.saves(twoSteps);
        this.runs();
        ItemRow c = this.item("in/c.m4a");
        assertThat(c.failedStepKey).isEqualTo("ask");
        assertThat(c.step(2).status).as("its row went on and failed again; the item stays failed where it first failed").isEqualTo("failed");
        this.broken.clear();
        int before = this.billed.size();

        assertThat(this.retries.retryItems(this.run, this.job, 1, Collections.singletonList("in/c.m4a"), "item",
            RunRetries.Actor.person(USER, true)).queued()).isTrue();
        this.runs();

        assertThat(this.billed.subList(before, this.billed.size())).hasSize(2).allMatch(l -> l.contains(" in/c.m4a "));
        assertThat(this.item("in/c.m4a").status).isEqualTo("done");
        assertThat(this.item("in/a.m4a").attempt).isEqualTo(1);
    }

    @Test
    void aBigTableReadWholeHasNoItems() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            rows.add(row("region", i % 2 == 0 ? "north" : "south", "amount", i));
        }
        this.saves(Definitions.of(step("read", "sample", config("rows", rows)), step("total", "aggregate", config("groupBy", Collections.singletonList("region"),
            "aggregations", Collections.singletonList(config("column", "amount", "op", "sum", "as", "total"))))));

        this.runs();

        assertThat(this.run.getJobStatus()).as(String.valueOf(this.run.getJobStatusMessage())).isEqualTo(JobStatus.Completed);
        assertThat(this.items.rows).as("no per-item step: not an item pipeline").isEmpty();
        assertThat(this.run.getJobStatusMessage()).doesNotContain("Items:");
    }

    @Test
    void aPdfsPagesAreSubItemsAndOneBadPageNeverFailsTheDocument() throws Exception {
        this.buckets.objects.put("riverside-health-s3/referrals/referral-0412.pdf", pdf("Patient referral", null, "Plan of care"));
        this.saves(Definitions.of(sample("read", row("key", "referrals/referral-0412.pdf", "bucket", "riverside-health-s3")),
            step("pages", "split_pages"), step("read_page", "per_item")));

        this.runs();

        assertThat(this.run.getJobStatus()).isEqualTo(JobStatus.Completed);
        List<ItemRow> current = this.items.current(RUN_ID);
        assertThat(current).extracting(r -> r.itemRef + " " + r.label + " " + r.status).containsExactly("1 referral-0412.pdf done",
            "1.1 Page 1 done", "1.2 Page 2 failed", "1.3 Page 3 done");
        ItemRow doc = this.item("referrals/referral-0412.pdf");
        assertThat(doc.childCount).isEqualTo(3);
        assertThat(doc.childFailed).isEqualTo(1);
        assertThat(doc.step(1).note).isEqualTo("3 pages: 2 done, 1 failed");
        ItemRow page2 = this.item("referrals/referral-0412.pdf#2");
        assertThat(page2.parentKey).isEqualTo("referrals/referral-0412.pdf");
        assertThat(page2.reason).isEqualTo("Page 2 has no text (0 characters): a scanned page needs OCR.");
        assertThat(page2.failedStepKey).isEqualTo("pages");
        assertThat(this.items.files).filteredOn(f -> "output".equals(f.role)).extracting(f -> f.itemKey + " " + f.fileName)
            .containsExactly("referrals/referral-0412.pdf#1 referral-0412-page-01.png", "referrals/referral-0412.pdf#2 referral-0412-page-02.png",
                "referrals/referral-0412.pdf#3 referral-0412-page-03.png");
        assertThat(this.items.files).filteredOn(f -> "input".equals(f.role)).extracting(f -> f.bucketAlias + "/" + f.objectKey)
            .containsExactly("riverside-health-s3/referrals/referral-0412.pdf");
        assertThat(this.run.getJobStatusMessage()).endsWith("Items: 0 of 1 done; 1 page of referral-0412.pdf failed.");

        // The page fixed in its file, then "Retry page": only page 2 is read again by the per-page step.
        this.buckets.objects.put("riverside-health-s3/referrals/referral-0412.pdf", pdf("Patient referral", "Medications", "Plan of care"));
        int before = this.billed.size();
        assertThat(this.retries.retryItems(this.run, this.job, 1, Collections.singletonList("referrals/referral-0412.pdf#2"), "item",
            RunRetries.Actor.person(USER, true)).queued()).isTrue();
        this.runs();

        assertThat(this.billed.subList(before, this.billed.size())).containsExactly("attempt 2 referrals/referral-0412.pdf#2 "
            + this.billed.get(1).substring(this.billed.get(1).lastIndexOf(' ') + 1));
        assertThat(this.item("referrals/referral-0412.pdf#2").status).isEqualTo("done");
        assertThat(this.item("referrals/referral-0412.pdf#2").attempt).isEqualTo(2);
        assertThat(this.item("referrals/referral-0412.pdf#1").attempt).isEqualTo(1);
        ItemRow fixed = this.item("referrals/referral-0412.pdf");
        assertThat(fixed.childFailed).as("the document recounts its pages").isZero();
        assertThat(this.run.getJobStatusMessage()).endsWith("Items: 1 of 1 done.");
    }

    /**
     * ZT-I9, ZT-I10 (live run 1849): with a Read S3 listing before the split, "Retry page" read none of the document's rows --
     * the listing's row was taken for one of its pages' (a page keeps copies of its document's earlier entries) -- and the page
     * was then marked done though no step had read it. The listing's row is the document's, the page is split again, and a
     * retried item no step reaches keeps its earlier outcome.
     */
    @Test
    void retryingAPageAfterAListingReadsItsDocumentAgainAndOnlyThatPage() throws Exception {
        this.buckets.objects.put("riverside-health-s3/referrals/referral-0412.pdf", pdf("Patient referral", null, "Plan of care"));
        this.saves(Definitions.of(step("list", "read_s3", config("bucket", "riverside-health-s3", "prefix", "referrals/", "format", "list")),
            step("pages", "split_pages", config("bucket", "riverside-health-s3")), step("read_page", "per_item")));
        this.runs();
        assertThat(this.item("referrals/referral-0412.pdf#2").status).isEqualTo("failed");
        this.buckets.objects.put("riverside-health-s3/referrals/referral-0412.pdf", pdf("Patient referral", "Medications", "Plan of care"));
        int before = this.billed.size();

        assertThat(this.retries.retryItems(this.run, this.job, 1, Collections.singletonList("referrals/referral-0412.pdf#2"), "item",
            RunRetries.Actor.person(USER, true)).queued()).isTrue();
        this.runs();

        assertThat(this.steps.row(RUN_ID, 2, "pages").recordsIn).as("the document's row, read again").isEqualTo(1L);
        assertThat(this.billed.subList(before, this.billed.size())).hasSize(1).allMatch(l -> l.contains("referral-0412.pdf#2 "));
        assertThat(this.item("referrals/referral-0412.pdf#2").status).isEqualTo("done");
        assertThat(this.item("referrals/referral-0412.pdf").childFailed).isZero();
    }

    /**
     * ZT-I14 (live run 1854): retrying one page split every other document of the run again -- the split's reuse of a
     * document's pages was lost when its pages' own rows were taken for theirs. Only the page's document is split again; the
     * other documents' pages are reused, and a step over the whole table still counts every page.
     */
    @Test
    void retryingAPageSplitsOnlyItsDocumentAndTheOthersPagesAreReused() throws Exception {
        this.buckets.objects.put("riverside-health-s3/referrals/a.pdf", pdf("Patient referral", null, "Plan of care"));
        this.buckets.objects.put("riverside-health-s3/referrals/b.pdf", pdf("Lab results", "Imaging"));
        this.saves(Definitions.of(step("list", "read_s3", config("bucket", "riverside-health-s3", "prefix", "referrals/", "format", "list")),
            step("pages", "split_pages", config("bucket", "riverside-health-s3")), step("read_page", "per_item"),
            step("per_doc", "aggregate", config("groupBy", Collections.singletonList("key"),
                "aggregations", Collections.singletonList(config("op", "count", "as", "pages"))))));
        this.runs();
        assertThat(this.item("referrals/a.pdf#2").status).isEqualTo("failed");
        this.buckets.objects.put("riverside-health-s3/referrals/a.pdf", pdf("Patient referral", "Medications", "Plan of care"));
        int before = this.billed.size();

        assertThat(this.retries.retryItems(this.run, this.job, 1, Collections.singletonList("referrals/a.pdf#2"), "item",
            RunRetries.Actor.person(USER, true)).queued()).isTrue();
        this.runs();

        assertThat(this.steps.row(RUN_ID, 2, "pages").recordsIn).as("a.pdf alone is split again").isEqualTo(1L);
        assertThat(this.steps.logText(RUN_ID, 2, "pages")).noneMatch(l -> l.contains("ran again here"));
        assertThat(this.billed.subList(before, this.billed.size())).hasSize(1).allMatch(l -> l.contains("a.pdf#2 "));
        assertThat(this.steps.row(RUN_ID, 2, "per_doc").recordsIn).as("every page of both documents").isEqualTo(5L);
        assertThat(this.item("referrals/a.pdf#2").status).isEqualTo("done");
        assertThat(this.item("referrals/b.pdf").attempt).isEqualTo(1);
        assertThat(this.run.getJobStatusMessage()).endsWith("Items: 2 of 2 done.");
    }

    @Test
    void aRetriedItemNoStepReachesKeepsItsEarlierOutcome() throws Exception {
        this.buckets.objects.put("riverside-health-s3/referrals/referral-0412.pdf", pdf("Patient referral", null, "Plan of care"));
        this.saves(Definitions.of(step("list", "read_s3", config("bucket", "riverside-health-s3", "prefix", "referrals/", "format", "list")),
            step("pages", "split_pages", config("bucket", "riverside-health-s3")), step("read_page", "per_item")));
        this.runs();
        // The document is gone from its folder: the retry's split has no page 2 to make.
        this.buckets.objects.put("riverside-health-s3/referrals/referral-0412.pdf", pdf("Patient referral"));

        assertThat(this.retries.retryItems(this.run, this.job, 1, Collections.singletonList("referrals/referral-0412.pdf#2"), "item",
            RunRetries.Actor.person(USER, true)).queued()).isTrue();
        this.runs();

        ItemRow page = this.item("referrals/referral-0412.pdf#2");
        assertThat(page.status).as("never done when nothing read it").isEqualTo("failed");
        assertThat(page.reason).startsWith("Not retried: no step of the retry read this item's rows.");
    }

    /** A PDF whose pages say these lines; a null line is a page with no text. */
    static byte[] pdf(String... pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pages) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (text != null) {
                    try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                        content.beginText();
                        content.setFont(PDType1Font.HELVETICA, 14);
                        content.newLineAtOffset(72, 700);
                        content.showText(text + " (synthetic)");
                        content.endText();
                    }
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** A workspace bucket of PDFs. */
    static final class Pdfs implements BucketStore {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        @Override public Optional<String> unavailable() { return Optional.empty(); }

        @Override public Listing list(long tenantId, String bucket, String prefix, int limit) {
            List<Listed> found = new ArrayList<>();
            this.objects.forEach((key, bytes) -> {
                if (key.startsWith(bucket + "/" + prefix)) {
                    found.add(new Listed(key.substring(bucket.length() + 1), bytes.length));
                }
            });
            return new Listing(found, false);
        }

        @Override public byte[] read(long tenantId, String bucket, String key, long maxBytes) {
            byte[] content = this.objects.get(bucket + "/" + key);
            if (content == null) {
                throw new IllegalStateException("No object at " + bucket + "/" + key + ".");
            }
            return content;
        }

        @Override public void upload(long tenantId, String bucket, String key, byte[] content, String contentType) {
            this.objects.put(bucket + "/" + key, content);
        }
    }

    static Set<String> keys(List<ItemRow> rows) {
        return rows.stream().map(r -> r.itemKey).collect(Collectors.toCollection(HashSet::new));
    }
}

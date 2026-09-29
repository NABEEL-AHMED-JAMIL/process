package process.pipeline;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.service.NotifyService;
import process.model.service.impl.NotifyServiceImpl;
import process.model.service.impl.TransactionServiceImpl;
import process.pipeline.registry.InMemoryTaskOverrideStore;
import process.pipeline.registry.TaskRegistry;
import process.pipeline.data.Values;
import org.springframework.transaction.support.TransactionOperations;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
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
 * MIG-230: the step engine, one test per way a step can end and per on-error mode. The run is reported through a
 * NotifyService that holds every report to NotifyServiceImpl's own transition table (read reflectively, as
 * JobStatusTransitionTableTest reads it), so a sequence the live callback would refuse fails here.
 */
class StepEngineTest {

    private static final long TENANT = 2924L;
    private static final long JOB_ID = 2834L;
    private static final long RUN_ID = 7401L;
    private static final long DEFINITION_ID = 1001L;

    private final InMemoryStepStore steps = new InMemoryStepStore();
    private final InMemoryDatasetStore datasets = new InMemoryDatasetStore();
    private final PipelineDefinitionStore definitions = mock(PipelineDefinitionStore.class);
    private final TransactionServiceImpl transactions = mock(TransactionServiceImpl.class);
    private final ReportingWorker worker = new ReportingWorker();
    private final List<String> audit = Collections.synchronizedList(new ArrayList<>());
    private final List<Duration> waited = Collections.synchronizedList(new ArrayList<>());
    private final ExecutorService tryThreads = Executors.newCachedThreadPool();
    private final Map<String, AtomicInteger> tries = new ConcurrentHashMap<>();

    private final AllTasks all = new AllTasks();
    private final InMemoryTaskOverrideStore switches = new InMemoryTaskOverrideStore();
    private StepTasks tasks;
    private StepEngine engine;
    private JobQueue run;
    private SourceJob job;

    @BeforeEach
    void setUp() {
        List<StepTask> registered = this.all.list();
        registered.addAll(Arrays.asList(new Flaky(), new Slow(), new Counts()));
        this.tasks = new StepTasks(registered);
        this.engine = new StepEngine(this.definitions, this.steps, this.tasks,
            new DefinitionValidator(new TaskRegistry(this.tasks, this.switches)), this.datasets,
            this.worker, this.transactions, TransactionOperations.withoutTransaction(), Executors.newSingleThreadExecutor(),
            this.tryThreads, this.waited::add);
        this.run = new JobQueue();
        this.run.setJobQueueId(RUN_ID);
        this.run.setJobId(JOB_ID);
        this.run.setTenantId(TENANT);
        this.run.setAttempt(1);
        this.run.setJobStatus(JobStatus.Queue);
        SourceTask task = new SourceTask();
        task.setPipelineId("CLAIMS");
        task.setTaskPayload("<pipeline><claim_id>C-17</claim_id><amount>250</amount><meta><x>1</x></meta></pipeline>");
        this.job = new SourceJob();
        this.job.setJobId(JOB_ID);
        this.job.setTenantId(TENANT);
        this.job.setJobStatus(Status.Active);
        this.job.setTaskDetail(task);
        when(this.transactions.findJobQueueByJobQueueId(RUN_ID)).thenAnswer(call -> Optional.of(this.run));
        doAnswer(call -> this.audit.add(call.getArgument(1))).when(this.transactions).saveJobAuditLogs(anyLong(), anyString());
    }

    @AfterEach
    void tearDown() {
        this.tryThreads.shutdownNow();
    }

    // ---- the worker the engine reports to --------------------------------------------------------------------

    /** A NotifyService that applies the live callback's transition table to the run and keeps what it was told. */
    final class ReportingWorker implements NotifyService {
        final List<String> reports = new ArrayList<>();
        private final Method table;
        private final NotifyServiceImpl rules = new NotifyServiceImpl(null, null, null, null);

        ReportingWorker() {
            try {
                this.table = NotifyServiceImpl.class.getDeclaredMethod("isValidStatusTransition", JobStatus.class, JobStatus.class);
                this.table.setAccessible(true);
            } catch (NoSuchMethodException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public synchronized ResponseDto changeState(SourceJobQueueDto dto) {
            JobStatus current = run.getJobStatus() == JobStatus.Queue && run.isJobSend() ? JobStatus.Start : run.getJobStatus();
            boolean legal;
            try {
                legal = (Boolean) this.table.invoke(this.rules, current, dto.getJobStatus());
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
            if (!legal) {
                this.reports.add("REFUSED " + current + "->" + dto.getJobStatus());
                return new ResponseDto("ERROR", "refused");
            }
            this.reports.add(dto.getJobStatus() + ": " + dto.getJobStatusMessage());
            run.setJobStatus(dto.getJobStatus());
            if (dto.getJobStatus() == JobStatus.Failed || dto.getJobStatus() == JobStatus.Completed) {
                assertThat(dto.getEndTime()).as("a terminal report carries its end time, as NotifyResetApi sets it").isNotNull();
            }
            return new ResponseDto("SUCCESS", "ok");
        }

        List<JobStatus> statuses() {
            List<JobStatus> statuses = new ArrayList<>();
            for (String report : this.reports) {
                statuses.add(report.startsWith("REFUSED") ? null : JobStatus.valueOf(report.substring(0, report.indexOf(':'))));
            }
            return statuses;
        }

        String last() {
            return this.reports.get(this.reports.size() - 1);
        }

        @Override public ResponseDto addLogs(SourceJobQueueDto dto) { throw new UnsupportedOperationException(); }
        @Override public ResponseDto addLogsBatch(Long jobId, Long jobQueueId, List<String> messages) { throw new UnsupportedOperationException(); }
        @Override public ResponseDto changeState(SourceJobQueueDto dto, String key) { throw new UnsupportedOperationException(); }
        @Override public ResponseDto addLogs(SourceJobQueueDto dto, String key) { throw new UnsupportedOperationException(); }
        @Override public ResponseDto addLogsBatch(Long jobId, Long jobQueueId, List<String> messages, String key) { throw new UnsupportedOperationException(); }
        @Override public void noteRefusedCallback(Long jobQueueId, JobStatus reportedStatus) { throw new UnsupportedOperationException(); }
        @Override public Optional<ResponseDto> replay(Long jobQueueId, JobStatus s, String request, String key) { throw new UnsupportedOperationException(); }
    }

    // ---- test tasks --------------------------------------------------------------------------------------------

    /** Fails its first {@code failTimes} tries with {@code message}, then passes its input on. */
    final class Flaky implements StepTask {
        @Override public String code() { return "flaky"; }
        @Override public String description() { return "fails, then passes"; }
        @Override public List<DefinitionProblem> check(Map<String, Object> config) { return Collections.emptyList(); }
        @Override public StepResult run(StepContext context) {
            int failTimes = ((Number) context.config().getOrDefault("failTimes", Integer.MAX_VALUE)).intValue();
            int n = tries.computeIfAbsent(context.stepKey(), k -> new AtomicInteger()).incrementAndGet();
            if (n <= failTimes) {
                throw new IllegalStateException(String.valueOf(context.config().getOrDefault("message", "boom")));
            }
            context.log("passed on try " + context.tryNumber());
            return StepResult.of(context.input());
        }
    }

    /** Sleeps {@code millis}, interruptibly, then passes its input on. */
    static final class Slow implements StepTask {
        @Override public String code() { return "slow"; }
        @Override public String description() { return "sleeps"; }
        @Override public List<DefinitionProblem> check(Map<String, Object> config) { return Collections.emptyList(); }
        @Override public StepResult run(StepContext context) throws Exception {
            Thread.sleep(((Number) context.config().get("millis")).longValue());
            return StepResult.of(context.input());
        }
    }

    /** Acts on its input and makes no dataset: the next step reads what this one read. */
    static final class Counts implements StepTask {
        @Override public String code() { return "counts"; }
        @Override public String description() { return "counts"; }
        @Override public List<DefinitionProblem> check(Map<String, Object> config) { return Collections.emptyList(); }
        @Override public StepResult run(StepContext context) {
            context.log(context.input().size() + " record(s) counted");
            return StepResult.nothing((long) context.input().size());
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private void runs(PipelineDefinition definition) {
        PipelineDefinitionStore.Stored stored = new PipelineDefinitionStore.Stored();
        stored.id = DEFINITION_ID;
        stored.tenantId = TENANT;
        stored.version = 3;
        stored.json = DefinitionCodec.toJson(definition);
        when(this.definitions.latestFor(TENANT, "CLAIMS")).thenReturn(Optional.of(stored));
        when(this.definitions.byId(DEFINITION_ID)).thenReturn(Optional.of(stored));
        Optional<StepEngine.StepPlan> plan = this.engine.planFor(this.job, this.run);
        assertThat(plan).as("a stored definition that is not the legacy wrap is the engine's").isPresent();
        this.run.setJobSend(true); // what claimForEngine writes
        this.engine.run(plan.get());
    }

    private static PipelineDefinition.Step failing(String key, String message, String onError) {
        PipelineDefinition.Step step = step(key, "flaky", config("message", message));
        step.setOnError(onError);
        return step;
    }

    private Map<String, String> statuses() {
        return this.steps.statuses(RUN_ID, 1);
    }

    // ---- the tests ---------------------------------------------------------------------------------------------

    @Test
    void aMultiStepPipelineRunsItsStepsInOrderAndReportsAsAWorkerWould() {
        this.runs(Definitions.of(sample("read", row("id", 1, "name", "Ada"), row("id", 2, "name", "Bo")),
            step("keep", "select", config("columns", config("name", "patient"))), step("count", "counts")));

        assertThat(this.worker.statuses()).containsExactly(JobStatus.Start, JobStatus.Running, JobStatus.Completed);
        assertThat(this.worker.reports.get(0)).isEqualTo("Start: Taken by the step engine: pipeline CLAIMS, definition version 3, 3 step(s).");
        assertThat(this.worker.reports.get(1)).isEqualTo("Running: Running 3 step(s): read, keep, count.");
        assertThat(this.worker.last()).isEqualTo("Completed: 3 of 3 step(s) completed.");
        assertThat(this.statuses()).containsExactly(entry("read", "Completed"), entry("keep", "Completed"), entry("count", "Completed"));

        StepStore.StepRow keep = this.steps.row(RUN_ID, 1, "keep");
        assertThat(keep.recordsIn).isEqualTo(2L);
        assertThat(keep.recordsOut).isEqualTo(2L);
        assertThat(keep.tries).isEqualTo(1);
        assertThat(keep.pipelineDefinitionId).isEqualTo(DEFINITION_ID);
        assertThat(keep.onError).isEqualTo("fail");
        assertThat(keep.startedAt).isNotNull();
        assertThat(keep.endedAt).isNotNull();
        assertThat(this.steps.row(RUN_ID, 1, "read").recordsIn).as("a source of none is no records").isZero();
        assertThat(this.steps.row(RUN_ID, 1, "count").recordsOut).isEqualTo(2L);

        // Datasets pass by reference: each output is kept under its key and read back by the next step.
        assertThat(this.datasets.stored.keySet()).containsExactly("datasets/7401/1/read/output.json", "datasets/7401/1/keep/output.json");
        assertThat(this.datasets.stored.get("datasets/7401/1/keep/output.json").getRows())
            .containsExactly(row("patient", "Ada"), row("patient", "Bo"));
        assertThat(this.steps.datasets).extracting(d -> d.name + ":" + d.rowCount + ":" + d.columns)
            .containsExactly("output:2:[\"id\",\"name\"]", "output:2:[\"patient\"]");
        assertThat(this.datasets.reads).as("keep reads read's output, count reads keep's").isEqualTo(2);

        assertThat(this.steps.logText(RUN_ID, 1, "read")).containsExactly("INFO 2 sample row(s).");
        assertThat(this.steps.logText(RUN_ID, 1, "count")).containsExactly("INFO 2 record(s) counted");
        assertThat(this.audit).hasSize(6).first().asString().startsWith("Step 1/3 <read> (sample) started on 0 record(s).");
        assertThat(this.audit.get(3)).startsWith("Step 2/3 <keep> (select) completed: 2 in, 2 out");
    }

    @Test
    void onErrorFailFailsTheRunAndSkipsTheRest() {
        this.runs(Definitions.of(sample("read", row("id", 1)), failing("check", "the amount is negative", null), step("count", "counts")));

        assertThat(this.worker.statuses()).containsExactly(JobStatus.Start, JobStatus.Running, JobStatus.Failed);
        assertThat(this.worker.last()).isEqualTo("Failed: Step 2 <check> failed: the amount is negative");
        assertThat(this.statuses()).containsExactly(entry("read", "Completed"), entry("check", "Failed"), entry("count", "Skip"));
        StepStore.StepRow check = this.steps.row(RUN_ID, 1, "check");
        assertThat(check.error).isEqualTo("{\"message\":\"the amount is negative\",\"tries\":1,\"timedOut\":false}");
        assertThat(check.statusMessage).isEqualTo("Failed after 1 try: the amount is negative (on error: fail).");
        assertThat(this.steps.row(RUN_ID, 1, "count").statusMessage).isEqualTo("Skipped: step <check> failed and its on-error is fail.");
        assertThat(this.steps.logText(RUN_ID, 1, "check")).containsExactly("ERROR Try 1 of 1 failed: the amount is negative");
    }

    @Test
    void onErrorContinueGoesOnWithTheLatestOutputAndCompletesTheRun() {
        this.runs(Definitions.of(sample("read", row("id", 1), row("id", 2)), failing("enrich", "the API said 503", "continue"),
            step("count", "counts")));

        assertThat(this.worker.statuses()).containsExactly(JobStatus.Start, JobStatus.Running, JobStatus.Completed);
        assertThat(this.worker.last()).isEqualTo("Completed: 2 of 3 step(s) completed, 1 failed and continued.");
        assertThat(this.statuses()).containsExactly(entry("read", "Completed"), entry("enrich", "Failed"), entry("count", "Completed"));
        assertThat(this.steps.row(RUN_ID, 1, "count").recordsIn).as("count read read's output").isEqualTo(2L);
    }

    @Test
    void onErrorContinueStillFailsAStepThatNamesTheFailedStepAsItsInput() {
        PipelineDefinition.Step count = step("count", "counts");
        count.setInput("enrich");
        count.setOnError("continue");
        this.runs(Definitions.of(sample("read", row("id", 1)), failing("enrich", "503", "continue"), count));

        assertThat(this.worker.last()).isEqualTo("Completed: 1 of 3 step(s) completed, 2 failed and continued.");
        StepStore.StepRow row = this.steps.row(RUN_ID, 1, "count");
        assertThat(row.status).isEqualTo("Failed");
        assertThat(row.statusMessage).isEqualTo("Failed: its input <enrich> produced no output.");
        assertThat(row.tries).isZero();
    }

    @Test
    void onErrorSkipRestSkipsTheRestAndCompletesTheRun() {
        this.runs(Definitions.of(sample("read", row("id", 1)), failing("guard", "nothing new today", "skip_rest"), step("count", "counts"),
            step("again", "counts")));

        assertThat(this.worker.statuses()).containsExactly(JobStatus.Start, JobStatus.Running, JobStatus.Completed);
        assertThat(this.worker.last()).isEqualTo("Completed: 1 of 4 step(s) completed; stopped after <guard> failed (skip_rest), 2 skipped.");
        assertThat(this.statuses()).containsExactly(entry("read", "Completed"), entry("guard", "Failed"), entry("count", "Skip"),
            entry("again", "Skip"));
        assertThat(this.steps.row(RUN_ID, 1, "again").statusMessage).isEqualTo("Skipped: step <guard> failed and its on-error is skip_rest.");
    }

    @Test
    void theDefaultOnErrorComesFromTheSettings() {
        PipelineDefinition definition = Definitions.of(sample("read", row("id", 1)), failing("enrich", "503", null), step("count", "counts"));
        PipelineDefinition.Settings settings = new PipelineDefinition.Settings();
        settings.setDefaultOnError("continue");
        definition.setSettings(settings);
        this.runs(definition);
        assertThat(this.worker.last()).startsWith("Completed: 2 of 3");
        assertThat(this.steps.row(RUN_ID, 1, "enrich").onError).isEqualTo("continue");
    }

    @Test
    void aStepIsTriedUpToItsMaxAttemptsWithItsDelayBetweenTries() {
        PipelineDefinition.Step flaky = step("fetch", "flaky", config("failTimes", 2, "message", "connection reset"));
        flaky.setRetry(PipelineDefinition.Retry.of(3, 5));
        this.runs(Definitions.of(sample("read", row("id", 1)), flaky));

        assertThat(this.worker.last()).isEqualTo("Completed: 2 of 2 step(s) completed.");
        StepStore.StepRow row = this.steps.row(RUN_ID, 1, "fetch");
        assertThat(row.tries).isEqualTo(3);
        assertThat(row.status).isEqualTo("Completed");
        assertThat(row.statusMessage).endsWith(", on try 3.");
        assertThat(this.waited).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(5));
        assertThat(this.steps.logText(RUN_ID, 1, "fetch")).containsExactly("INFO Try 1 of 3.", "WARN Try 1 of 3 failed: connection reset",
            "INFO Try 2 of 3.", "WARN Try 2 of 3 failed: connection reset", "INFO Try 3 of 3.", "INFO passed on try 3");
    }

    @Test
    void aStepThatFailsEveryTryFailsWithItsLastErrorAndNoDelayAfterTheLast() {
        PipelineDefinition.Step flaky = failing("fetch", "connection reset", null);
        flaky.setRetry(PipelineDefinition.Retry.of(2, 1));
        this.runs(Definitions.of(flaky));
        assertThat(this.steps.row(RUN_ID, 1, "fetch").statusMessage).isEqualTo("Failed after 2 tries: connection reset (on error: fail).");
        assertThat(this.waited).containsExactly(Duration.ofSeconds(1));
        assertThat(this.worker.last()).isEqualTo("Failed: Step 1 <fetch> failed: connection reset");
    }

    @Test
    void aTryThatOverrunsItsTimeoutIsInterruptedAndCountsAsFailed() {
        PipelineDefinition.Step slow = step("slow", "slow", config("millis", 30000));
        slow.setTimeoutSeconds(1);
        long began = System.currentTimeMillis();
        this.runs(Definitions.of(slow));

        assertThat(System.currentTimeMillis() - began).as("not the full thirty seconds").isLessThan(10000);
        StepStore.StepRow row = this.steps.row(RUN_ID, 1, "slow");
        assertThat(row.status).isEqualTo("Failed");
        assertThat(row.error).isEqualTo("{\"message\":\"timed out after 1 s\",\"tries\":1,\"timedOut\":true}");
        assertThat(this.worker.last()).isEqualTo("Failed: Step 1 <slow> failed: timed out after 1 s");
    }

    @Test
    void theTaskSourceIsTheTaskPayloadAsOneRowParsedAsTheAiStepsParseIt() {
        PipelineDefinition definition = Definitions.of(step("keep", "select", config("columns", Arrays.asList("claim_id", "amount"))));
        definition.setSource(PipelineDefinition.Source.of("task"));
        this.runs(definition);

        assertThat(this.worker.last()).startsWith("Completed");
        assertThat(this.steps.row(RUN_ID, 1, "keep").recordsIn).isEqualTo(1L);
        assertThat(this.datasets.stored.get("datasets/7401/1/keep/output.json").getRows())
            .containsExactly(row("claim_id", "C-17", "amount", "250"));
    }

    @Test
    void aTaskPayloadThatIsNotXmlFailsTheRunInWords() {
        this.job.getTaskDetail().setTaskPayload("<pipeline><unclosed></pipeline>");
        PipelineDefinition definition = Definitions.of(step("count", "counts"));
        definition.setSource(PipelineDefinition.Source.of("task"));
        this.runs(definition);
        assertThat(this.worker.last()).startsWith("Failed: The step engine stopped: ").contains("not well-formed");
    }

    @Test
    void aRunTakenElsewhereIsLeftAlone() {
        this.steps.claimable = false;
        this.runs(Definitions.of(sample("read", row("id", 1))));
        assertThat(this.worker.reports).isEmpty();
        assertThat(this.steps.rows).isEmpty();
    }

    @Test
    void aRunClosedByAnOperatorWhileItsStepsRunStopsAndReportsNothingMore() {
        PipelineDefinition.Step close = step("close", "counts");
        StepTask closer = new StepTask() {
            @Override public String code() { return "closer"; }
            @Override public String description() { return "an operator marks the run failed meanwhile"; }
            @Override public List<DefinitionProblem> check(Map<String, Object> config) { return Collections.emptyList(); }
            @Override public StepResult run(StepContext context) {
                run.setJobStatus(JobStatus.Failed);
                return StepResult.nothing(0L);
            }
        };
        this.tasks = Definitions.builtInTasks(closer, new Counts());
        this.engine = new StepEngine(this.definitions, this.steps, this.tasks, new DefinitionValidator(this.tasks), this.datasets,
            this.worker, this.transactions, TransactionOperations.withoutTransaction(), Executors.newSingleThreadExecutor(),
            this.tryThreads, this.waited::add);
        this.runs(Definitions.of(step("first", "closer"), close, step("last", "counts")));

        assertThat(this.worker.statuses()).containsExactly(JobStatus.Start, JobStatus.Running);
        assertThat(this.statuses()).containsExactly(entry("first", "Completed"), entry("close", "Interrupt"), entry("last", "Interrupt"));
    }

    @Test
    void aRunPinnedToADefinitionThatNoLongerValidatesIsDeclinedNotRetried() {
        PipelineDefinition definition = Definitions.of(step("gone", "flaky"));
        this.tasks = Definitions.builtInTasks();
        this.engine = new StepEngine(this.definitions, this.steps, this.tasks, new DefinitionValidator(this.tasks), this.datasets,
            this.worker, this.transactions, TransactionOperations.withoutTransaction(), Executors.newSingleThreadExecutor(),
            this.tryThreads, this.waited::add);
        this.runs(definition);

        assertThat(this.worker.statuses()).as("Start -> Failed: a decline, which the callback never retries or meters")
            .containsExactly(JobStatus.Start, JobStatus.Failed);
        assertThat(this.worker.last()).isEqualTo("Failed: Declined by the step engine: definition version 3 no longer validates: "
            + "steps[0].task: no task 'flaky' is registered");
        assertThat(this.statuses()).as("shown, not run, so the timeline says why").containsExactly(entry("gone", "Skip"));
        assertThat(this.steps.row(RUN_ID, 1, "gone").statusMessage).startsWith("Not run: Declined by the step engine");
    }

    @Test
    void aStoredVersionThisBuildCannotReadIsDeclinedNotHandedToAWorker() {
        PipelineDefinitionStore.Stored stored = new PipelineDefinitionStore.Stored();
        stored.id = DEFINITION_ID;
        stored.version = 4;
        stored.json = "{\"version\":1,\"steps\":[{\"key\":\"read\",\"task\":\"sample\",\"parallel\":true}]}";
        when(this.definitions.latestFor(TENANT, "CLAIMS")).thenReturn(Optional.of(stored));
        Optional<StepEngine.StepPlan> plan = this.engine.planFor(this.job, this.run);
        assertThat(plan).as("the engine's, never today's path").isPresent();
        this.run.setJobSend(true);
        this.engine.run(plan.get());
        assertThat(this.worker.statuses()).containsExactly(JobStatus.Start, JobStatus.Failed);
        assertThat(this.worker.last()).startsWith("Failed: Declined by the step engine: definition version 4 cannot be read: ")
            .contains("unknown field 'parallel'");
    }

    @Test
    void anOutputThatCannotBeKeptStopsTheRunAsFailed() {
        this.datasets.failWrites = new IllegalStateException("disk full");
        this.runs(Definitions.of(sample("read", row("id", 1))));
        assertThat(this.worker.last()).isEqualTo("Failed: The step engine stopped: The output of step <read> could not be kept: disk full");
    }

    @Test
    void aRetriedRunRunsItsStepsAgainUnderTheNextAttemptOnTheSameDefinition() {
        this.runs(Definitions.of(failing("fetch", "503", null)));
        assertThat(this.worker.last()).startsWith("Failed");
        // BulkAction.scheduleRetry put the same row back in the queue as attempt 2 (the callback's own retry).
        this.run.setAttempt(2);
        this.run.setJobStatus(JobStatus.Queue);
        this.run.setJobSend(false);
        this.worker.reports.clear();
        when(this.definitions.latestFor(TENANT, "CLAIMS")).thenReturn(Optional.empty());
        Optional<StepEngine.StepPlan> plan = this.engine.planFor(this.job, this.run);
        assertThat(plan).as("pinned by attempt 1's rows, though the pipeline's latest is gone").isPresent();
        this.run.setJobSend(true);
        this.engine.run(plan.get());
        assertThat(this.steps.statuses(RUN_ID, 1)).containsExactly(entry("fetch", "Failed"));
        assertThat(this.steps.statuses(RUN_ID, 2)).containsExactly(entry("fetch", "Failed"));
        assertThat(this.steps.row(RUN_ID, 2, "fetch").pipelineDefinitionId).isEqualTo(DEFINITION_ID);
    }

    @Test
    void theLegacyWrapNoDefinitionAndAnInactiveJobAreNotTheEngines() {
        assertThat(this.engine.planFor(this.job, this.run)).as("no stored definition").isEmpty();

        PipelineDefinitionStore.Stored legacy = new PipelineDefinitionStore.Stored();
        legacy.id = 1002L;
        legacy.json = DefinitionCodec.toJson(PipelineDefinition.legacy("CLAIMS"));
        when(this.definitions.latestFor(TENANT, "CLAIMS")).thenReturn(Optional.of(legacy));
        assertThat(this.engine.planFor(this.job, this.run)).as("a stored legacy wrap").isEmpty();

        PipelineDefinitionStore.Stored steps = new PipelineDefinitionStore.Stored();
        steps.id = 1003L;
        steps.json = DefinitionCodec.toJson(Definitions.of(sample("read", row("id", 1))));
        when(this.definitions.latestFor(TENANT, "CLAIMS")).thenReturn(Optional.of(steps));
        assertThat(this.engine.planFor(this.job, this.run)).isPresent();
        this.job.setJobStatus(Status.Inactive);
        assertThat(this.engine.planFor(this.job, this.run)).as("today's path closes it").isEmpty();
        this.job.setJobStatus(Status.Active);
        this.job.setTaskDetail(null);
        assertThat(this.engine.planFor(this.job, this.run)).isEmpty();
        assertThat(this.engine.planFor(null, this.run)).isEmpty();
    }

    // ---- MIG-231: the Task Registry's tasks in the engine -----------------------------------------------------

    /** The acceptance's pipeline: read -> validate -> transform -> save, end to end on the engine, with a small dataset. */
    @Test
    void aPipelineReadsValidatesTransformsAndSaves() {
        this.all.buckets.objects.put("lake/in/claims.csv",
            "claim_id,name,amount\nC-1,acme,10.50\nC-2,beta,oops\nC-3,gamma,7\n".getBytes(StandardCharsets.UTF_8));
        this.all.contracts.holds = candidate -> Values.number(candidate.get("amount")) != null;
        this.runs(Definitions.of(
            step("read", "read_file", config("bucket", "lake", "key", "in/claims.csv")),
            step("check", "validate", config("contractName", "claims", "onInvalid", "drop")),
            step("shape", "transform", config("mappings", Arrays.asList(
                config("target", "claim", "source", "claim_id"),
                config("target", "customer", "op", "upper", "source", "name"),
                config("target", "amount", "op", "cast", "source", "amount", "to", "number")))),
            step("save", "save_file", config("fileName", "claims-clean.csv"))));

        assertThat(this.worker.statuses()).containsExactly(JobStatus.Start, JobStatus.Running, JobStatus.Completed);
        assertThat(this.worker.last()).isEqualTo("Completed: 4 of 4 step(s) completed.");
        assertThat(this.steps.row(RUN_ID, 1, "read").recordsOut).isEqualTo(3L);
        assertThat(this.steps.row(RUN_ID, 1, "check").recordsOut).isEqualTo(2L);
        assertThat(this.steps.row(RUN_ID, 1, "save").recordsOut).isEqualTo(2L);
        assertThat(this.datasets.stored.get("datasets/7401/1/shape/output.json").getRows())
            .containsExactly(row("claim", "C-1", "customer", "ACME", "amount", 10.5), row("claim", "C-3", "customer", "GAMMA", "amount", 7L));
        assertThat(new String(this.datasets.files.get("datasets/7401/1/save/files/claims-clean.csv"), StandardCharsets.UTF_8))
            .isEqualTo("claim,customer,amount\r\nC-1,ACME,10.5\r\nC-3,GAMMA,7\r\n");
        assertThat(this.steps.datasets).extracting(d -> d.name + ":" + d.rowCount)
            .containsExactly("output:3", "output:2", "output:2", "claims-clean.csv:2");
        assertThat(this.all.buckets.lastTenant).as("the bucket is read as the run's workspace").isEqualTo(TENANT);
        assertThat(this.all.contracts.calls.get(0).tenantId).isEqualTo(TENANT);
        assertThat(this.steps.logText(RUN_ID, 1, "check")).contains("WARN 1 row(s) dropped: they do not hold to claims v3.");
    }

    @Test
    void aJoinReadsAnEarlierStepsOutputByItsKey() {
        this.runs(Definitions.of(
            sample("customers", row("id", 1, "name", "Acme")),
            sample("orders", row("order", "A1", "customer_id", "1"), row("order", "A2", "customer_id", "2")),
            step("joined", "join", config("with", "customers", "type", "left", "on",
                Collections.singletonList(config("left", "customer_id", "right", "id"))))));

        assertThat(this.worker.last()).isEqualTo("Completed: 3 of 3 step(s) completed.");
        assertThat(this.datasets.stored.get("datasets/7401/1/joined/output.json").getRows())
            .containsExactly(row("order", "A1", "customer_id", "1", "name", "Acme"), row("order", "A2", "customer_id", "2", "name", null));
    }

    /** A task switched off in the run's workspace after the definition was saved: the run is declined, as a task gone. */
    @Test
    void aRunNamingATaskSwitchedOffInItsWorkspaceIsDeclined() {
        this.switches.set(TENANT, "select", false, 7L);
        this.runs(Definitions.of(sample("read", row("id", 1)), step("keep", "select", config("columns", Collections.singletonList("id")))));

        assertThat(this.worker.statuses()).containsExactly(JobStatus.Start, JobStatus.Failed);
        assertThat(this.worker.last()).isEqualTo("Failed: Declined by the step engine: definition version 3 no longer validates: "
            + "steps[1].task: the task 'select' is disabled in this workspace");
        assertThat(this.statuses()).containsExactly(entry("read", "Skip"), entry("keep", "Skip"));
    }

    /** A step that says nothing of retry takes its task's registry default: Read API is tried three times, 10 s apart. */
    @Test
    void aStepWithoutItsOwnRetryTakesItsTasksDefault() {
        this.all.api.outcome = "FAILED";
        PipelineDefinition.Step once = step("once", "read_api", config("requestId", 4));
        once.setRetry(PipelineDefinition.Retry.of(1, 0));
        once.setOnError("continue");
        this.runs(Definitions.of(step("fetch", "read_api", config("requestId", 4)), once));

        assertThat(this.steps.row(RUN_ID, 1, "fetch").tries).isEqualTo(3);
        assertThat(this.waited).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(10));
        assertThat(this.all.api.calls).hasSize(3);
        assertThat(this.worker.last()).startsWith("Failed: Step 1 <fetch> failed: The API request failed (HTTP 500)");
    }

    // ---- Wave 4: the run's result manifest ------------------------------------------------------------------------

    @Test
    void aSavedFileAndAnUploadAreInTheRunsManifest() {
        this.runs(Definitions.of(step("read", "sample", config("rows", Arrays.asList(config("id", 1), config("id", 2)))),
            step("keep", "save_file", config("fileName", "claims.csv")),
            step("send", "upload_bucket", config("bucket", "exports", "key", "out/{{run}}.json", "format", "json"))));

        assertThat(this.statuses()).containsExactly(entry("read", "Completed"), entry("keep", "Completed"), entry("send", "Completed"));
        List<StepStore.OutputRow> manifest = this.steps.outputsOfRun(RUN_ID);
        assertThat(manifest).extracting(o -> o.stepKey + ":" + o.kind + ":" + o.name + ":" + o.format + ":" + o.rowCount + ":"
            + o.bucketAlias + ":" + o.objectKey).containsExactly("keep:file:claims.csv:csv:2:null:null",
            "send:bucket:" + RUN_ID + ".json:json:2:exports:out/" + RUN_ID + ".json");
        StepStore.OutputRow file = manifest.get(0);
        StepStore.DatasetFile kept = this.steps.datasetById(file.runDatasetId).get();
        assertThat(kept.name).isEqualTo("claims.csv");
        assertThat(kept.storageKey).isEqualTo("datasets/" + RUN_ID + "/1/keep/files/claims.csv");
        assertThat(file.expiresAt).isNotNull();
        assertThat(file.byteCount).isEqualTo((long) this.datasets.files.get(kept.storageKey).length);
        assertThat(manifest.get(1).runDatasetId).isNull();
        assertThat(manifest.get(1).expiresAt).isNull();
    }

    /** Recorded once per step per attempt: a try that kept and recorded, then failed, is replaced by the try that passed. */
    @Test
    void aRetriedStepHasOneManifestRow() {
        StepTask keepsThenFails = new StepTask() {
            @Override public String code() { return "keeps_then_fails"; }
            @Override public String description() { return "keeps a file, fails the first try"; }
            @Override public List<DefinitionProblem> check(Map<String, Object> config) { return Collections.emptyList(); }
            @Override public StepResult run(StepContext context) throws Exception {
                byte[] content = ("try " + context.tryNumber()).getBytes();
                context.keepFile("out.csv", content, context.tryNumber(), Collections.singletonList("n"));
                context.recordOutput(RunOutput.file("out.csv", "csv", context.tryNumber(), content.length));
                if (context.tryNumber() == 1) {
                    throw new IllegalStateException("first try fails");
                }
                return StepResult.nothing(0L);
            }
        };
        this.tasks = Definitions.builtInTasks(keepsThenFails);
        this.engine = new StepEngine(this.definitions, this.steps, this.tasks, new DefinitionValidator(this.tasks), this.datasets,
            this.worker, this.transactions, TransactionOperations.withoutTransaction(), Executors.newSingleThreadExecutor(),
            this.tryThreads, this.waited::add);
        PipelineDefinition.Step step = step("keep", "keeps_then_fails");
        step.setRetry(PipelineDefinition.Retry.of(2, 1));

        this.runs(Definitions.of(step));

        assertThat(this.statuses()).containsExactly(entry("keep", "Completed"));
        assertThat(this.steps.outputsOfRun(RUN_ID)).extracting(o -> o.attempt + ":" + o.name + ":" + o.rowCount)
            .containsExactly("1:out.csv:2");
    }

    /** A step may not put a file in the manifest that it did not keep with the run. */
    @Test
    void aFileThatWasNotKeptIsNotRecorded() {
        StepTask claims = new StepTask() {
            @Override public String code() { return "claims_a_file"; }
            @Override public String description() { return "records a file it never kept"; }
            @Override public List<DefinitionProblem> check(Map<String, Object> config) { return Collections.emptyList(); }
            @Override public StepResult run(StepContext context) throws Exception {
                context.recordOutput(RunOutput.file("ghost.csv", "csv", 1, 1));
                return StepResult.nothing(0L);
            }
        };
        this.tasks = Definitions.builtInTasks(claims);
        this.engine = new StepEngine(this.definitions, this.steps, this.tasks, new DefinitionValidator(this.tasks), this.datasets,
            this.worker, this.transactions, TransactionOperations.withoutTransaction(), Executors.newSingleThreadExecutor(),
            this.tryThreads, this.waited::add);

        this.runs(Definitions.of(step("claim", "claims_a_file")));

        assertThat(this.statuses()).containsExactly(entry("claim", "Failed"));
        assertThat(this.steps.outputsOfRun(RUN_ID)).isEmpty();
    }

    private static Map.Entry<String, String> entry(String key, String value) {
        return new AbstractMap.SimpleEntry<>(key, value);
    }
}

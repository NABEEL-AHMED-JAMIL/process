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
import org.springframework.transaction.support.TransactionOperations;

import java.lang.reflect.Method;
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

    private StepTasks tasks;
    private StepEngine engine;
    private JobQueue run;
    private SourceJob job;

    @BeforeEach
    void setUp() {
        this.tasks = Definitions.builtInTasks(new Flaky(), new Slow(), new Counts());
        this.engine = new StepEngine(this.definitions, this.steps, this.tasks, new DefinitionValidator(this.tasks), this.datasets,
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
        assertThat(this.steps.rows).isEmpty();
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

    private static Map.Entry<String, String> entry(String key, String value) {
        return new AbstractMap.SimpleEntry<>(key, value);
    }
}

package process.pipeline;

import process.pipeline.tasks.ReadS3StepTask;
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
import process.pipeline.registry.InMemoryTaskOverrideStore;
import process.pipeline.registry.TaskRegistry;
import process.pipeline.tasks.ReadFileStepTask;
import process.pipeline.tasks.SampleStepTask;
import process.pipeline.tasks.SelectStepTask;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;
import static process.pipeline.Definitions.sample;
import static process.pipeline.Definitions.step;
import java.util.AbstractMap;

/**
 * MIG-345: a failed run retried from the step that failed, as a new attempt of the same run, through RunRetries and the
 * step engine -- the reused steps not run again (their tasks never called), their outputs read by reference, only the
 * re-run steps' work done (and so metered); every refusal with its reason; the restart; the attempt recorded.
 */
class RunRetriesTest {

    private static final long TENANT = 2961L;
    private static final long JOB_ID = 3450L;
    private static final long RUN_ID = 9345L;
    private static final long USER = 4643L;

    private final InMemoryStepStore steps = new InMemoryStepStore();
    private final InMemoryDatasetStore datasets = new InMemoryDatasetStore();
    private final MemoryAttempts attempts = new MemoryAttempts();
    private final PipelineDefinitionStore definitions = mock(PipelineDefinitionStore.class);
    private final TransactionServiceImpl transactions = mock(TransactionServiceImpl.class);
    private final BulkAction bulk = mock(BulkAction.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DigestingBuckets buckets = new DigestingBuckets();
    private final ExecutorService tryThreads = Executors.newCachedThreadPool();
    /** How many times each step's task ran, by step key, over every attempt. */
    private final Map<String, AtomicInteger> ran = new ConcurrentHashMap<>();
    /** What a metered step "billed": one line per row it did work for, by attempt and step (an AI step's calls). */
    private final List<String> billed = new CopyOnWriteArrayList<>();
    private final Map<Long, PipelineDefinitionStore.Stored> versions = new LinkedHashMap<>();
    private final List<String> audit = Collections.synchronizedList(new ArrayList<>());

    private final Worker worker = new Worker();
    private StepEngine engine;
    private RunRetries retries;
    private JobQueue run;
    private SourceJob job;
    private long nextVersionId = 1100;

    @BeforeEach
    void setUp() {
        List<StepTask> registered = new ArrayList<>(Arrays.asList(new SampleStepTask(), new SelectStepTask(), new Counted(), new Gate(),
            new Metered(), new ReadFileStepTask(this.buckets), new ReadS3StepTask(this.buckets)));
        StepTasks tasks = new StepTasks(registered);
        DefinitionValidator validator = new DefinitionValidator(new TaskRegistry(tasks, new InMemoryTaskOverrideStore()));
        this.engine = new StepEngine(this.definitions, this.steps, tasks, validator, this.datasets, this.worker, this.transactions,
            TransactionOperations.withoutTransaction(), Executors.newSingleThreadExecutor(), this.tryThreads, duration -> { });
        this.engine.useAttempts(this.attempts);
        this.retries = new RunRetries(this.steps, this.attempts, this.definitions, validator, tasks, this.bulk, this.jdbc,
            TransactionOperations.withoutTransaction());
        this.retries.useBuckets(this.buckets);

        this.run = new JobQueue();
        this.run.setJobQueueId(RUN_ID);
        this.run.setJobId(JOB_ID);
        this.run.setTenantId(TENANT);
        this.run.setAttempt(1);
        this.run.setJobStatus(JobStatus.Queue);
        SourceTask task = new SourceTask();
        task.setPipelineId("ORDERS");
        task.setTaskPayload("<pipeline><order>7</order></pipeline>");
        this.job = new SourceJob();
        this.job.setJobId(JOB_ID);
        this.job.setTenantId(TENANT);
        this.job.setJobStatus(Status.Active);
        this.job.setTaskDetail(task);
        when(this.transactions.findJobQueueByJobQueueId(RUN_ID)).thenAnswer(call -> Optional.of(this.run));
        doAnswer(call -> this.audit.add(call.getArgument(1))).when(this.transactions).saveJobAuditLogs(anyLong(), anyString());
        when(this.definitions.byId(anyLong())).thenAnswer(call -> Optional.ofNullable(this.versions.get((Long) call.getArgument(0))));
        when(this.definitions.latestFor(TENANT, "ORDERS")).thenAnswer(call -> this.versions.values().stream()
            .reduce((a, b) -> b));
        // The requeue: the run back in Queue under its next attempt, as the UPDATE writes it.
        when(this.jdbc.update(anyString(), any(), any(), any(), any(), any())).thenAnswer(call -> {
            // attempt, message, run, the status it must still have (Failed; in flight for the platform's resume), its attempt
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

    // ---- test tasks ---------------------------------------------------------------------------------------------

    /** Passes its input on, counting each time it runs. */
    final class Counted implements StepTask {
        @Override public String code() { return "counted"; }

        @Override public String description() { return "passes its input on"; }

        @Override public List<DefinitionProblem> check(Map<String, Object> config) { return Collections.emptyList(); }

        @Override public StepResult run(StepContext context) {
            ran.computeIfAbsent(context.stepKey(), k -> new AtomicInteger()).incrementAndGet();
            return StepResult.of(context.input());
        }
    }

    /** A check that fails unless its config says pass: true -- a validate step against a contract later loosened. */
    final class Gate implements StepTask {
        @Override public String code() { return "gate"; }

        @Override public String description() { return "fails until loosened"; }

        @Override public List<DefinitionProblem> check(Map<String, Object> config) { return Collections.emptyList(); }

        @Override public StepResult run(StepContext context) {
            ran.computeIfAbsent(context.stepKey(), k -> new AtomicInteger()).incrementAndGet();
            if (!Boolean.TRUE.equals(context.config().get("pass"))) {
                throw new IllegalStateException("3 row(s) break the contract: amount must be at most 100");
            }
            return StepResult.of(context.input());
        }
    }

    /** Work that is billed per row where it happens (an AI step's calls): each row it does is a line on the bill. */
    final class Metered implements StepTask {
        @Override public String code() { return "metered"; }

        @Override public String description() { return "billed per row"; }

        @Override public List<DefinitionProblem> check(Map<String, Object> config) { return Collections.emptyList(); }

        @Override public StepResult run(StepContext context) {
            ran.computeIfAbsent(context.stepKey(), k -> new AtomicInteger()).incrementAndGet();
            for (int i = 0; i < context.input().size(); i++) {
                billed.add("attempt " + context.attempt() + " " + context.stepKey() + " row " + (i + 1));
            }
            return StepResult.of(context.input());
        }
    }

    /** A bucket that fingerprints what a step reads, as TrustedBucketStore does. */
    static final class DigestingBuckets implements BucketStore {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        final AtomicInteger opened = new AtomicInteger();

        @Override public Optional<String> unavailable() { return Optional.empty(); }

        @Override public Listing list(long tenantId, String bucket, String prefix, int limit) {
            return new Listing(Collections.emptyList(), false);
        }

        @Override public byte[] read(long tenantId, String bucket, String key, long maxBytes) {
            byte[] content = this.content(bucket, key);
            InputDigests.whole(bucket, key, content);
            return content;
        }

        @Override public InputStream open(long tenantId, String bucket, String key, long maxBytes) {
            this.opened.incrementAndGet();
            return InputDigests.digesting(bucket, key, new ByteArrayInputStream(this.content(bucket, key)));
        }

        @Override public void upload(long tenantId, String bucket, String key, byte[] content, String contentType) {
            this.objects.put(bucket + "/" + key, content);
        }

        private byte[] content(String bucket, String key) {
            byte[] content = this.objects.get(bucket + "/" + key);
            if (content == null) {
                throw new IllegalStateException("No object at " + bucket + "/" + key + ".");
            }
            return content;
        }
    }

    /** The worker callback's outcome on the run: its status and status line. */
    final class Worker implements NotifyService {
        final List<String> reports = new CopyOnWriteArrayList<>();

        @Override
        public synchronized ResponseDto changeState(SourceJobQueueDto dto) {
            this.reports.add(dto.getJobStatus() + ": " + dto.getJobStatusMessage());
            run.setJobStatus(dto.getJobStatus());
            run.setJobStatusMessage(dto.getJobStatusMessage());
            return new ResponseDto("SUCCESS", "ok");
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

    /** run_attempt in memory. */
    static final class MemoryAttempts implements RunAttemptStore {
        final List<Attempt> rows = new CopyOnWriteArrayList<>();

        @Override public void record(Attempt attempt) {
            if (this.rows.stream().anyMatch(a -> a.jobQueueId == attempt.jobQueueId && a.attempt == attempt.attempt)) {
                throw new IllegalStateException("duplicate key value violates unique constraint ux_run_attempt_run_attempt");
            }
            this.rows.add(attempt);
        }

        @Override public Optional<Attempt> find(long jobQueueId, int attempt) {
            return this.rows.stream().filter(a -> a.jobQueueId == jobQueueId && a.attempt == attempt).findFirst();
        }

        @Override public List<Attempt> ofRun(long jobQueueId) {
            List<Attempt> of = new ArrayList<>();
            this.rows.stream().filter(a -> a.jobQueueId == jobQueueId).forEach(of::add);
            return of;
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Saves the pipeline's next version; the latest is what latestFor answers. */
    private long saves(PipelineDefinition definition) {
        PipelineDefinitionStore.Stored stored = new PipelineDefinitionStore.Stored();
        stored.id = this.nextVersionId++;
        stored.tenantId = TENANT;
        stored.version = this.versions.size() + 1;
        stored.json = DefinitionCodec.toJson(definition);
        this.versions.put(stored.id, stored);
        return stored.id;
    }

    /** One pass of the engine over the run, as the pre-dispatch phase hands it over. */
    private void runs() {
        Optional<StepEngine.StepPlan> plan = this.engine.planFor(this.job, this.run);
        assertThat(plan).isPresent();
        this.run.setJobSend(true);
        this.engine.run(plan.get());
        if (this.run.getJobStatus() == JobStatus.Failed || this.run.getJobStatus() == JobStatus.Completed) {
            this.job.setJobRunningStatus(this.run.getJobStatus());
        }
    }

    private static PipelineDefinition orders(boolean pass) {
        return Definitions.of(sample("read", row("id", 1, "amount", 50), row("id", 2, "amount", 120), row("id", 3, "amount", 400)),
            step("shape", "counted"), step("check", "gate", config("pass", pass)), step("after", "counted"));
    }

    private RunRetries.Outcome resume() {
        return this.retries.retry(this.run, this.job, false, RunRetries.Actor.person(USER, false));
    }

    private int ran(String key) {
        AtomicInteger count = this.ran.get(key);
        return count == null ? 0 : count.get();
    }

    /** A first attempt that fails at step 3 <check>. */
    private void failsAtStepThree() {
        this.saves(orders(false));
        this.runs();
        assertThat(this.run.getJobStatus()).isEqualTo(JobStatus.Failed);
        assertThat(this.steps.statuses(RUN_ID, 1)).containsExactly(entry("read", "Completed"), entry("shape", "Completed"),
            entry("check", "Failed"), entry("after", "Skip"));
    }

    private static Map.Entry<String, String> entry(String key, String value) {
        return new AbstractMap.SimpleEntry<>(key, value);
    }

    // ---- resume ------------------------------------------------------------------------------------------------

    @Test
    void aResumeReusesTheOutputsBeforeTheFailedStepAndRunsOnlyItAndTheStepsAfter() {
        this.failsAtStepThree();
        int readsBefore = this.datasets.reads;
        // Fixed where it failed: the contract loosened, the earlier steps untouched.
        long fixed = this.saves(orders(true));

        RunRetries.Outcome outcome = this.resume();

        assertThat(outcome.queued()).as(String.valueOf(outcome.refusal)).isTrue();
        assertThat(this.run.getAttempt()).isEqualTo(2);
        assertThat(this.run.getJobStatus()).isEqualTo(JobStatus.Queue);
        assertThat(outcome.plan.fromIndex).isEqualTo(2);
        assertThat(outcome.plan.fromKey).isEqualTo("check");
        assertThat(outcome.plan.reused).extracting(r -> r.key).containsExactly("read", "shape");
        verify(this.bulk).changeJobStatus(JOB_ID, JobStatus.Queue);
        verify(this.bulk).saveJobAuditLogs(eq(RUN_ID), eq("Attempt 2 queued by user 4643: retry from step 3 <check>, reusing 2 step(s) "
            + "of attempt 1."));

        this.runs();

        assertThat(this.run.getJobStatus()).isEqualTo(JobStatus.Completed);
        assertThat(this.run.getJobStatusMessage()).isEqualTo("4 of 4 step(s) completed (2 reused from attempt 1).");
        assertThat(this.ran("shape")).as("step 2 was not executed again").isEqualTo(1);
        assertThat(this.ran("check")).as("the failed step ran again").isEqualTo(2);
        assertThat(this.ran("after")).isEqualTo(1);
        assertThat(this.steps.statuses(RUN_ID, 2)).containsExactly(entry("read", "Completed"), entry("shape", "Completed"),
            entry("check", "Completed"), entry("after", "Completed"));
        StepStore.StepRow read1 = this.steps.row(RUN_ID, 1, "read");
        StepStore.StepRow shape1 = this.steps.row(RUN_ID, 1, "shape");
        StepStore.StepRow read2 = this.steps.row(RUN_ID, 2, "read");
        StepStore.StepRow shape2 = this.steps.row(RUN_ID, 2, "shape");
        assertThat(read2.reusedFrom).isEqualTo(read1.stepExecutionId);
        assertThat(shape2.reusedFrom).isEqualTo(shape1.stepExecutionId);
        assertThat(shape2.startedAt).as("not run now: no times").isNull();
        assertThat(shape2.tries).isZero();
        assertThat(shape2.recordsOut).isEqualTo(3L);
        assertThat(shape2.statusMessage).isEqualTo("Reused from attempt 1: not run again (3 in, 3 out).");
        assertThat(this.steps.logText(RUN_ID, 2, "shape")).containsExactly("INFO Reused from attempt 1: not run again (3 in, 3 out).");
        assertThat(this.steps.row(RUN_ID, 2, "check").startedAt).isNotNull();
        assertThat(this.steps.row(RUN_ID, 2, "check").pipelineDefinitionId).as("the fixed version runs").isEqualTo(fixed);
        assertThat(this.steps.row(RUN_ID, 2, "check").recordsIn).as("it read shape's output from attempt 1").isEqualTo(3L);
        // Only the re-run steps wrote anything; the reused outputs were read by reference, never rewritten or loaded to reuse.
        assertThat(this.datasets.stored.keySet()).containsExactly("datasets/9345/1/read/output.json", "datasets/9345/1/shape/output.json",
            "datasets/9345/2/check/output.json", "datasets/9345/2/after/output.json");
        assertThat(this.datasets.reads - readsBefore).as("check read shape's kept output once, after read check's: nothing more")
            .isEqualTo(2);
        assertThat(this.audit).contains("Step 1/4 <read> (sample) reused from attempt 1: not run again.",
            "Step 2/4 <shape> (counted) reused from attempt 1: not run again.");
        assertThat(this.engineReports()).contains("Running: Resuming from step 3 <check>: 2 step(s) reused from attempt 1 (read, shape), "
            + "running check, after.");

        // The attempt someone asked for, and who.
        assertThat(this.attempts.ofRun(RUN_ID)).hasSize(1);
        RunAttemptStore.Attempt asked = this.attempts.ofRun(RUN_ID).get(0);
        assertThat(asked.attempt).isEqualTo(2);
        assertThat(asked.previousAttempt).isEqualTo(1);
        assertThat(asked.mode).isEqualTo("resume");
        assertThat(asked.fromStepIndex).isEqualTo(2);
        assertThat(asked.fromStepKey).isEqualTo("check");
        assertThat(asked.reusedSteps).isEqualTo(2);
        assertThat(asked.pipelineDefinitionId).isEqualTo(fixed);
        assertThat(asked.requestedVia).isEqualTo("console");
        assertThat(asked.requestedByUserId).isEqualTo(USER);
    }

    private List<String> engineReports() {
        return this.worker.reports;
    }

    @Test
    void onlyTheReRunStepsWorkIsBilledTheReusedStepsBillNothingAgain() {
        this.saves(Definitions.of(sample("read", row("id", 1), row("id", 2), row("id", 3)), step("ask", "metered"),
            step("check", "gate", config("pass", false)), step("after", "metered")));
        this.runs();
        assertThat(this.billed).hasSize(3).allMatch(line -> line.startsWith("attempt 1 ask"));
        this.saves(Definitions.of(sample("read", row("id", 1), row("id", 2), row("id", 3)), step("ask", "metered"),
            step("check", "gate", config("pass", true)), step("after", "metered")));

        assertThat(this.resume().queued()).isTrue();
        this.runs();

        assertThat(this.run.getJobStatus()).isEqualTo(JobStatus.Completed);
        assertThat(this.ran("ask")).as("the metered step before the failed one did not run again").isEqualTo(1);
        assertThat(this.billed).as("attempt 2 billed only its re-run steps' rows: after's three, none of ask's")
            .containsExactly("attempt 1 ask row 1", "attempt 1 ask row 2", "attempt 1 ask row 3", "attempt 2 after row 1",
                "attempt 2 after row 2", "attempt 2 after row 3");
    }

    @Test
    void aChangeToTheFailedStepOrLaterStepsIsAllowedAndTheNewVersionRuns() {
        this.failsAtStepThree();
        PipelineDefinition fixed = orders(true);
        fixed.getSteps().add(step("extra", "counted"));
        fixed.getSteps().get(1).setName("Shape the orders");
        fixed.getSteps().get(1).setOnError("continue");
        long version = this.saves(fixed);

        RunRetries.Outcome outcome = this.resume();

        assertThat(outcome.queued()).as(String.valueOf(outcome.refusal)).isTrue();
        assertThat(outcome.plan.definitionId).isEqualTo(version);
        this.runs();
        assertThat(this.steps.statuses(RUN_ID, 2)).containsOnlyKeys("read", "shape", "check", "after", "extra")
            .containsValue("Completed").doesNotContainValue("Failed");
        assertThat(this.ran("shape")).isEqualTo(1);
    }

    // ---- the refusals --------------------------------------------------------------------------------------------

    @Test
    void aChangeBeforeTheFailedStepRefusesTheResumeButNotTheRestart() {
        this.failsAtStepThree();
        PipelineDefinition changed = orders(true);
        changed.getSteps().set(1, step("shape", "select", config("columns", config("id", "id"))));
        this.saves(changed);

        RunRetries.Plan plan = this.retries.plan(this.run, this.job, true);

        assertThat(plan.resumable()).isFalse();
        assertThat(plan.refusalKind).isEqualTo(RunRetries.CHANGED);
        assertThat(plan.refusal).startsWith("The pipeline changed before the failed step <check> since this run (version 1, now 2): "
            + "step 2 <shape>'s task, settings.");
        assertThat(plan.restartable()).isTrue();
        RunRetries.Outcome refused = this.resume();
        assertThat(refused.queued()).isFalse();
        assertThat(refused.refusalKind).isEqualTo(RunRetries.CHANGED);
        assertThat(this.run.getAttempt()).as("nothing queued").isEqualTo(1);
        assertThat(this.attempts.rows).isEmpty();
        verify(this.bulk, never()).changeJobStatus(anyLong(), any());
    }

    @Test
    void aChangedSourceRefusesTheResume() {
        this.failsAtStepThree();
        PipelineDefinition changed = orders(true);
        changed.setSource(PipelineDefinition.Source.of(PipelineDefinition.Source.TASK));
        this.saves(changed);

        assertThat(this.retries.plan(this.run, this.job, true).refusal).contains("its source");
    }

    @Test
    void anExpiredOrSweptKeptOutputRefusesTheResume() {
        this.failsAtStepThree();
        long shapeOutput = this.steps.datasets.stream().filter(d -> d.stepExecutionId == this.steps.row(RUN_ID, 1, "shape").stepExecutionId)
            .findFirst().get().runDatasetId;
        this.steps.datasetExpiry.put(shapeOutput, Instant.now().minusSeconds(60));

        RunRetries.Plan expired = this.retries.plan(this.run, this.job, true);
        assertThat(expired.refusalKind).isEqualTo(RunRetries.EXPIRED);
        assertThat(expired.refusal).startsWith("The kept output of step <shape> expired at ");

        this.steps.datasetExpiry.put(shapeOutput, Instant.now().plusSeconds(3600));
        this.steps.swept.add(shapeOutput);
        RunRetries.Plan swept = this.retries.plan(this.run, this.job, true);
        assertThat(swept.refusalKind).isEqualTo(RunRetries.EXPIRED);
        assertThat(swept.refusal).contains("has been removed (past its retention)");
        assertThat(swept.restartable()).isTrue();
    }

    @Test
    void anOutputThatExpiresBetweenTheRetryAndItsStartDeclinesTheAttempt() {
        this.failsAtStepThree();
        this.saves(orders(true));
        assertThat(this.resume().queued()).isTrue();
        long shapeOutput = this.steps.datasets.stream().filter(d -> d.stepExecutionId == this.steps.row(RUN_ID, 1, "shape").stepExecutionId)
            .findFirst().get().runDatasetId;
        this.steps.datasetExpiry.put(shapeOutput, Instant.now().minusSeconds(1));

        this.runs();

        assertThat(this.run.getJobStatus()).isEqualTo(JobStatus.Failed);
        assertThat(this.run.getJobStatusMessage()).startsWith("Declined by the step engine: attempt 2 cannot resume from step 3: the kept "
            + "output of step <shape> expired at ");
        assertThat(this.steps.statuses(RUN_ID, 2).values()).containsOnly("Skip");
        assertThat(this.ran("check")).isEqualTo(1);
    }

    @Test
    void aRunThatIsNotFailedIsNotRetried() {
        this.saves(orders(true));
        this.runs();
        assertThat(this.run.getJobStatus()).isEqualTo(JobStatus.Completed);

        RunRetries.Outcome resume = this.resume();
        RunRetries.Outcome restart = this.retries.retry(this.run, this.job, true, RunRetries.Actor.person(USER, true));

        assertThat(resume.refusalKind).isEqualTo(RunRetries.NOT_FAILED);
        assertThat(resume.refusal).isEqualTo("Run 9345 is completed, not failed: only a failed run is retried.");
        assertThat(restart.refusalKind).isEqualTo(RunRetries.NOT_FAILED);
        assertThat(this.run.getAttempt()).isEqualTo(1);
    }

    @Test
    void aBackfillsRunIsRetriedByItsBackfillAndThePlanSaysWhich() {
        this.failsAtStepThree();
        this.run.setRunLane((short) 1);
        this.run.setBackfillId(7L);

        RunRetries.Plan plan = this.retries.plan(this.run, this.job, false);

        assertThat(plan.resumable()).isFalse();
        assertThat(plan.restartable()).isFalse();
        assertThat(plan.backfillId).isEqualTo(7L);
        assertThat(plan.refusal).isEqualTo("Run 9345 is part of backfill #7: retry its failed dates from the backfill (Runs › Backfills).");
        this.run.setRunLane((short) 0);
        this.run.setBackfillId(null);
        assertThat(this.retries.plan(this.run, this.job, false).backfillId).isNull();
    }

    @Test
    void anotherRunInFlightAnInactiveScheduleAndTooManyAttemptsRefuse() {
        this.failsAtStepThree();
        this.job.setJobRunningStatus(JobStatus.Running);
        assertThat(this.resume().refusalKind).isEqualTo(RunRetries.IN_FLIGHT);
        this.job.setJobRunningStatus(JobStatus.Failed);
        this.job.setJobStatus(Status.Inactive);
        assertThat(this.resume().refusalKind).isEqualTo(RunRetries.INACTIVE);
        this.job.setJobStatus(Status.Active);
        this.run.setAttempt(RunRetries.MAX_ATTEMPTS);
        RunRetries.Outcome many = this.resume();
        assertThat(many.refusalKind).isEqualTo(RunRetries.NOT_RESUMABLE);
        assertThat(many.refusal).isEqualTo("This run has had 20 attempts, the most a run has; start a new run instead.");
    }

    /**
     * ZT-I7 (MIG-420): a Read S3 that lists the objects reads no file's bytes, so it has no fingerprint to keep -- a resume
     * after it was refused as "read its files before their fingerprints were kept", and so was every Retry failed items of an
     * item pipeline that starts with a listing.
     */
    @Test
    void aStepThatListsTheObjectsHasNoFilesToVouchForAndTheResumeIsAllowed() {
        this.saves(Definitions.of(step("list", "read_s3", config("bucket", "intake", "prefix", "calls/", "format", "list")),
            step("check", "gate", config("pass", false)), step("after", "counted")));
        this.runs();
        assertThat(this.run.getJobStatus()).isEqualTo(JobStatus.Failed);
        this.saves(Definitions.of(step("list", "read_s3", config("bucket", "intake", "prefix", "calls/", "format", "list")),
            step("check", "gate", config("pass", true)), step("after", "counted")));

        RunRetries.Plan plan = this.retries.plan(this.run, this.job, true);

        assertThat(plan.refusal).isNull();
        assertThat(plan.resumable()).isTrue();
        assertThat(plan.fromKey).isEqualTo("check");
    }

    @Test
    void aChangedInputFileRefusesTheResumeAndAnUnchangedOneDoesNot() {
        this.buckets.objects.put("intake/orders.csv", "id,amount\n1,50\n2,120\n".getBytes(StandardCharsets.UTF_8));
        PipelineDefinition reading = Definitions.of(step("read", "read_file", config("bucket", "intake", "key", "orders.csv")),
            step("check", "gate", config("pass", false)), step("after", "counted"));
        this.saves(reading);
        this.runs();
        assertThat(this.run.getJobStatus()).isEqualTo(JobStatus.Failed);
        long read = this.steps.row(RUN_ID, 1, "read").stepExecutionId;
        assertThat(this.steps.inputs.get(read)).as("the file's sha256, taken on the way while the step read it").singleElement()
            .satisfies(r -> {
                assertThat(r.bucket + "/" + r.key).isEqualTo("intake/orders.csv");
                assertThat(r.bytes).isEqualTo("id,amount\n1,50\n2,120\n".length());
                assertThat(r.whole).isTrue();
                assertThat(r.sha256).hasSize(64);
            });
        PipelineDefinition fixed = Definitions.of(step("read", "read_file", config("bucket", "intake", "key", "orders.csv")),
            step("check", "gate", config("pass", true)), step("after", "counted"));
        this.saves(fixed);

        this.buckets.objects.put("intake/orders.csv", "id,amount\n1,50\n2,999\n".getBytes(StandardCharsets.UTF_8));
        RunRetries.Outcome changed = this.resume();
        assertThat(changed.refusalKind).isEqualTo(RunRetries.INPUT_CHANGED);
        assertThat(changed.refusal).startsWith("The file intake/orders.csv that step <read> read has changed (sha256 ");

        this.buckets.objects.put("intake/orders.csv", "id,amount\n1,50\n2,120\n3,7\n".getBytes(StandardCharsets.UTF_8));
        assertThat(this.resume().refusal).contains("is longer than the 21 bytes the step read");

        this.buckets.objects.remove("intake/orders.csv");
        assertThat(this.resume().refusal).contains("is no longer there");

        this.buckets.objects.put("intake/orders.csv", "id,amount\n1,50\n2,120\n".getBytes(StandardCharsets.UTF_8));
        int opened = this.buckets.opened.get();
        RunRetries.Outcome same = this.resume();
        assertThat(same.queued()).as(String.valueOf(same.refusal)).isTrue();
        assertThat(same.plan.inputsChecked).isTrue();
        assertThat(this.buckets.opened.get() - opened).as("checked by reading it once more").isEqualTo(1);
        this.runs();
        assertThat(this.run.getJobStatus()).isEqualTo(JobStatus.Completed);
        assertThat(this.buckets.opened.get() - opened).as("the reused read step did not read it again").isEqualTo(1);
    }

    @Test
    void aReusedFileStepWithoutFingerprintsOrWithTooManyFilesCannotBeVouchedFor() {
        this.buckets.objects.put("intake/orders.csv", "id\n1\n".getBytes(StandardCharsets.UTF_8));
        this.saves(Definitions.of(step("read", "read_file", config("bucket", "intake", "key", "orders.csv")),
            step("check", "gate", config("pass", false))));
        this.runs();
        long read = this.steps.row(RUN_ID, 1, "read").stepExecutionId;

        this.steps.inputs.remove(read);
        RunRetries.Plan unrecorded = this.retries.plan(this.run, this.job, true);
        assertThat(unrecorded.refusalKind).isEqualTo(RunRetries.NOT_RESUMABLE);
        assertThat(unrecorded.refusal).startsWith("Step <read> read its files before their fingerprints were kept");

        this.steps.inputs.put(read, new ArrayList<>(Collections.singletonList(InputDigests.Read.overflow(1500))));
        assertThat(this.retries.plan(this.run, this.job, true).refusal).startsWith("Step <read> read more than 1,000 files");
    }

    @Test
    void aRunThatRanNoStepsOrWhoseStepsAllCompletedHasNothingToResume() {
        this.saves(orders(false));
        this.run.setJobStatus(JobStatus.Failed);
        RunRetries.Plan none = this.retries.plan(this.run, this.job, true);
        assertThat(none.refusalKind).isEqualTo(RunRetries.NOT_RESUMABLE);
        assertThat(none.refusal).startsWith("Attempt 1 ran no steps of its own");
        assertThat(none.restartable()).isTrue();
    }

    // ---- restart -----------------------------------------------------------------------------------------------

    @Test
    void runAgainFromTheStartIsAnAdministratorsAndRunsEveryStepOnTheLatestVersion() {
        this.failsAtStepThree();
        PipelineDefinition changed = orders(true);
        changed.getSteps().set(1, step("shape", "counted", config("note", "changed")));
        long latest = this.saves(changed);

        RunRetries.Outcome notAdmin = this.retries.retry(this.run, this.job, true, RunRetries.Actor.person(USER, false));
        assertThat(notAdmin.queued()).isFalse();
        assertThat(notAdmin.refusal).isEqualTo("Running a failed run again from the start is a workspace administrator's.");

        RunRetries.Outcome restart = this.retries.retry(this.run, this.job, true, RunRetries.Actor.person(USER, true));
        assertThat(restart.queued()).isTrue();
        this.runs();

        assertThat(this.run.getJobStatus()).isEqualTo(JobStatus.Completed);
        assertThat(this.run.getJobStatusMessage()).isEqualTo("4 of 4 step(s) completed.");
        assertThat(this.ran("shape")).as("every step ran again").isEqualTo(2);
        assertThat(this.steps.row(RUN_ID, 2, "shape").reusedFrom).isNull();
        assertThat(this.steps.row(RUN_ID, 2, "shape").pipelineDefinitionId).isEqualTo(latest);
        RunAttemptStore.Attempt asked = this.attempts.find(RUN_ID, 2).get();
        assertThat(asked.mode).isEqualTo("restart");
        assertThat(asked.fromStepIndex).isNull();
        assertThat(asked.reusedSteps).isZero();
    }

    @Test
    void aSecondRetryOfTheSameFailureIsRefusedAndAResumedAttemptCanItselfBeResumed() {
        this.failsAtStepThree();
        assertThat(this.resume().queued()).as("resumes on the same version: the failure may have been passing").isTrue();
        RunRetries.Outcome twice = this.resume();
        assertThat(twice.refusalKind).isEqualTo(RunRetries.NOT_FAILED);

        this.runs();
        assertThat(this.run.getJobStatus()).as("still failing at check").isEqualTo(JobStatus.Failed);
        this.saves(orders(true));
        RunRetries.Outcome again = this.resume();
        assertThat(again.queued()).isTrue();
        assertThat(again.plan.attempt).isEqualTo(2);
        this.runs();

        assertThat(this.run.getJobStatus()).isEqualTo(JobStatus.Completed);
        assertThat(this.run.getAttempt()).isEqualTo(3);
        assertThat(this.ran("shape")).isEqualTo(1);
        assertThat(this.steps.row(RUN_ID, 3, "shape").reusedFrom).as("names the step that really ran it, in attempt 1")
            .isEqualTo(this.steps.row(RUN_ID, 1, "shape").stepExecutionId);
        assertThat(this.steps.row(RUN_ID, 3, "shape").statusMessage).isEqualTo("Reused from attempt 1: not run again (3 in, 3 out).");
        assertThat(this.attempts.ofRun(RUN_ID)).extracting(a -> a.attempt + ":" + a.previousAttempt + ":" + a.mode)
            .containsExactly("2:1:resume", "3:2:resume");
    }

    @Test
    void theRulesForWhatMayChangeBeforeTheFailedStep() {
        PipelineDefinition was = orders(false);
        PipelineDefinition renamed = orders(true);
        renamed.getSteps().get(0).setName("Read the orders");
        renamed.getSteps().get(1).setTimeoutSeconds(30);
        assertThat(RunRetries.changedBefore(was, renamed, 2)).as("name and timeout may change").isEmpty();
        PipelineDefinition rewired = orders(true);
        rewired.getSteps().get(1).setInput("read");
        assertThat(RunRetries.changedBefore(was, rewired, 2)).containsExactly("step 2 <shape>'s input");
        PipelineDefinition inserted = orders(true);
        inserted.getSteps().add(1, step("new", "counted"));
        assertThat(RunRetries.changedBefore(was, inserted, 2)).containsExactly("step 2 <shape>'s key");
        assertThat(RunRetries.changedBefore(was, orders(true), 0)).as("from step 1 anything may change").isEmpty();
    }
}

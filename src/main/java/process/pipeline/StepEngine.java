package process.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.platform.correlation.CorrelationId;
import org.barco.platform.correlation.CorrelationScope;
import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import process.ai.PayloadXml;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.service.NotifyService;
import process.model.service.impl.TransactionServiceImpl;
import process.pipeline.data.Limits;
import process.pipeline.data.RowSink;
import process.pipeline.data.RowSource;
import process.pipeline.data.RunMemory;
import process.util.BusinessTime;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Runs a pipeline as ordered steps (MIG-230), for a run whose pipeline has a stored definition that is not its legacy
 * wrap. Every other run -- every pipeline today -- never reaches it: {@link #planFor} answers nothing, and the
 * pre-dispatch phase prepares the run for its worker exactly as before.
 *
 * <b>The run's lifecycle is the worker's.</b> The engine is, to the rest of Core, the run's worker: it takes the run
 * (queued, unsent, unprepared -- {@link StepStore#claimForEngine}), and reports Start, Running and then Completed or
 * Failed through {@link NotifyService#changeState}, the callback the live worker calls. So the JobStatus transitions,
 * the transition rules, the retry (BulkAction.scheduleRetry on a failure, the job's own policy), the fail and complete
 * mails, the meter and the SLI's end reason are exactly a worker's. A run the engine cannot start (its pinned
 * definition names a task no longer registered) is declined -- Start then Failed -- which is never retried and never
 * metered, as a worker's decline. Skip-when-in-flight is the enqueuer's and untouched: an engine run is in flight
 * (Queue, Start, Running) like any other.
 *
 * <b>Steps.</b> In order, each on its input: the output of the step it names ({@code input}), else the latest output
 * before it, else the source (the task payload as one row, for {@code source: task}). Each step is tried up to its
 * retry.maxAttempts, each try bounded by its timeoutSeconds (a try that overruns is interrupted and counts as failed),
 * with retry.delaySeconds between tries. A step's output is written to the {@link DatasetStore} and recorded as a
 * run_dataset reference; its rows never pass through the database. Its step_execution row says Queue, Running,
 * Completed, Failed, Skip or Interrupt with its times, records in and out, tries and error, and its step_log its lines;
 * the run's own log (job_audit_logs) gets one line when a step starts and one when it ends.
 *
 * <b>A failed step</b> follows its on-error ({@link OnError}): fail (the default) fails the run and skips the rest;
 * continue goes on; skip_rest skips the rest and completes the run. A run moved on by someone else while it runs (an
 * operator marked it failed or interrupted it) stops at the next step: the remaining steps are Interrupt and nothing
 * more is reported.
 *
 * Runs on its own threads (process.pipeline.engine.threads, default 4), each run inside RowSecurity.forTenant of the
 * run's workspace; each try on a thread of its own, in the same workspace, so a timeout can interrupt it.
 *
 * <b>Streaming (MIG-344).</b> A step whose task streams ({@link StreamingStepTask}) reads its input a batch at a time
 * from the dataset store and writes its output a row at a time to a new dataset file, committed only when the try
 * succeeds: it holds a batch, not the table, and has no row or cell limit. A step whose task does not stream gets its
 * input in memory, as before, held to {@link Limits} -- an input bigger than that fails the step and says why. Each
 * run has a memory budget ({@code process.pipeline.run-memory-mb}, {@link RunMemory}) that a streaming step's batches
 * and an aggregate's groups count against; an aggregate spills to disk past it. Each step's log says the most it held
 * and what it spilled; a try's spill files are removed when it ends, a run's when the run ends.
 */
@Component
public class StepEngine {

    private static final Logger logger = LoggerFactory.getLogger(StepEngine.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A step's log stops here: a log is for reading, and one runaway step must not fill a table. */
    static final int MAX_LOG_LINES = 1000;

    /** Waits between a step's tries; a test's advances a clock instead. */
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    /** A run and the definition version it follows. */
    public static final class StepPlan {
        public final SourceJob job;
        public final JobQueue run;
        public final PipelineDefinitionStore.Stored stored;
        /** Null when the stored version cannot be read any more: the run is declined with {@link #unreadable}. */
        public final PipelineDefinition definition;
        public final String unreadable;

        StepPlan(SourceJob job, JobQueue run, PipelineDefinitionStore.Stored stored, PipelineDefinition definition, String unreadable) {
            this.job = job;
            this.run = run;
            this.stored = stored;
            this.definition = definition;
            this.unreadable = unreadable;
        }
    }

    private final PipelineDefinitionStore definitions;
    private final StepStore steps;
    private final StepTasks tasks;
    private final DefinitionValidator validator;
    private final DatasetStore datasets;
    private final NotifyService notify;
    private final TransactionServiceImpl transactions;
    /** One local transaction per audit line, as the pre-dispatch phase writes its notes. */
    private final TransactionOperations local;
    /** How many try threads each run thread may hold at once: its current try and a few timed-out ones unwinding. */
    static final int TRY_THREADS_PER_RUN_THREAD = 4;

    private final ExecutorService runThreads;
    private final ExecutorService tryThreads;
    private final Sleeper sleeper;
    /** MIG-243: how long a run's datasets are kept -- the definition's hours against the workspace's data policy. */
    private RetentionPolicy retention = RetentionPolicy.DEFINITION_ONLY;
    /** MIG-344: each run's memory budget, and the rows a streaming step reads at a time. */
    private int runMemoryMb = RunMemory.DEFAULT_MB;
    private int batchRows = RowSource.DEFAULT_BATCH;

    @Autowired
    public StepEngine(PipelineDefinitionStore definitions, StepStore steps, StepTasks tasks, DefinitionValidator validator,
        DatasetStore datasets, NotifyService notify, TransactionServiceImpl transactions, PlatformTransactionManager transactionManager,
        @Value("${process.pipeline.engine.threads:4}") int threads) {
        this(definitions, steps, tasks, validator, datasets, notify, transactions, new TransactionTemplate(transactionManager),
            newRunThreads(Math.max(1, threads)), newTryThreads(Math.max(1, threads)), duration -> Thread.sleep(duration.toMillis()));
    }

    /** For tests: the threads a run and a try run on, and the wait between tries. */
    public StepEngine(PipelineDefinitionStore definitions, StepStore steps, StepTasks tasks, DefinitionValidator validator,
        DatasetStore datasets, NotifyService notify, TransactionServiceImpl transactions, TransactionOperations local,
        ExecutorService runThreads, ExecutorService tryThreads, Sleeper sleeper) {
        this.definitions = definitions;
        this.steps = steps;
        this.tasks = tasks;
        this.validator = validator;
        this.datasets = datasets;
        this.notify = notify;
        this.transactions = transactions;
        this.local = local;
        this.runThreads = runThreads;
        this.tryThreads = tryThreads;
        this.sleeper = sleeper;
    }

    /** MIG-243: the data policy's retention (PolicyRetention); without one, the definition's hours alone, as before. */
    @Autowired(required = false)
    public void useRetention(RetentionPolicy retention) {
        this.retention = retention == null ? RetentionPolicy.DEFINITION_ONLY : retention;
    }

    /** MIG-344: a run's memory budget in MB, and how many rows a streaming step reads at a time. */
    @Autowired
    public void useMemory(@Value("${process.pipeline.run-memory-mb:128}") int runMemoryMb,
                          @Value("${process.pipeline.batch-rows:1024}") int batchRows) {
        this.runMemoryMb = Math.max(16, runMemoryMb);
        this.batchRows = Math.max(1, batchRows);
    }

    private static ExecutorService newRunThreads(int threads) {
        AtomicInteger count = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "step-engine-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * MIG-214: the threads the tries run on, bounded. A try that overruns is interrupted, but one blocked in a network
     * read ignores that until its own read timeout, so it keeps its thread a while: each run thread may have a few
     * of those unwinding. Beyond that a try is refused (and fails in words) rather than growing the pool without end.
     * Idle threads go after a minute.
     */
    static ExecutorService newTryThreads(int runThreads) {
        AtomicInteger count = new AtomicInteger();
        ThreadPoolExecutor pool = new ThreadPoolExecutor(runThreads, runThreads * TRY_THREADS_PER_RUN_THREAD, 60, TimeUnit.SECONDS,
            new SynchronousQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "step-try-" + count.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    @PreDestroy
    void stop() {
        this.runThreads.shutdownNow();
        this.tryThreads.shutdownNow();
    }

    /**
     * The definition this run follows, when the engine runs it: an earlier attempt's (a retry follows the version it
     * started on), else its pipeline's latest stored definition -- unless that is the legacy wrap. Empty for every run
     * of a pipeline without a stored definition, a run of a deleted or inactive job, and a job with no task: those take
     * today's path, which closes the ones that cannot run.
     */
    public Optional<StepPlan> planFor(SourceJob job, JobQueue run) {
        if (job == null || run == null || job.getTenantId() == null || job.getTaskDetail() == null
            || job.getJobStatus() != Status.Active) {
            return Optional.empty();
        }
        Optional<PipelineDefinitionStore.Stored> stored = this.steps.pinnedDefinition(run.getJobQueueId()).flatMap(this.definitions::byId);
        if (!stored.isPresent()) {
            stored = this.definitions.latestFor(job.getTenantId(), job.getTaskDetail().getPipelineId());
        }
        if (!stored.isPresent()) {
            return Optional.empty();
        }
        PipelineDefinition definition;
        try {
            definition = stored.get().definition();
        } catch (IllegalStateException unreadable) {
            // A stored version this build cannot read (the model changed under it): the engine's to decline, never
            // today's path -- the run must not be handed to a worker that has no such pipeline, nor left claimed.
            return Optional.of(new StepPlan(job, run, stored.get(), null, reasonOf(unreadable)));
        }
        if (definition.isLegacy()) {
            return Optional.empty();
        }
        return Optional.of(new StepPlan(job, run, stored.get(), definition, null));
    }

    /** Runs the plan on the engine's threads, in the run's workspace; {@code done} runs afterwards, whatever happened. */
    public void submit(StepPlan plan, Runnable done) {
        long tenantId = plan.job.getTenantId();
        this.runThreads.execute(() -> {
            try {
                RowSecurity.forTenant(tenantId, () -> this.run(plan));
            } finally {
                done.run();
            }
        });
    }

    /** One run, start to end. Never throws: what cannot be reported is logged, and the stall sweep closes the run. */
    void run(StepPlan plan) {
        JobQueue run = plan.run;
        if (run.getCorrelationId() == null) {
            run.setCorrelationId(CorrelationId.generate());
        }
        CorrelationScope scope = CorrelationScope.open(run.getCorrelationId());
        try {
            if (!this.steps.claimForEngine(run.getJobQueueId(), run.getCorrelationId())) {
                logger.info("Run {} was taken, sent or closed before the step engine could take it; left as it is.", run.getJobQueueId());
                return;
            }
            new Execution(plan).go();
        } catch (Throwable ex) {
            logger.error("The step engine stopped on run {}: {}", run.getJobQueueId(), ExceptionUtil.getRootCauseMessage(ex));
            try {
                this.report(run, JobStatus.Failed, "The step engine stopped: " + reasonOf(ex));
            } catch (RuntimeException unreported) {
                logger.error("Run {} could not be reported Failed; the stall sweep will close it: {}", run.getJobQueueId(),
                    ExceptionUtil.getRootCauseMessage(unreported));
            }
        } finally {
            scope.close();
        }
    }

    /** A state change as the run's worker would report it, through the worker callback's own rules. */
    private ResponseDto report(JobQueue run, JobStatus status, String message) {
        SourceJobQueueDto dto = new SourceJobQueueDto();
        dto.setJobId(run.getJobId());
        dto.setJobQueueId(run.getJobQueueId());
        dto.setJobStatus(status);
        dto.setJobStatusMessage(message);
        if (status == JobStatus.Failed || status == JobStatus.Completed) {
            dto.setEndTime(BusinessTime.now());
        }
        ResponseDto answer = this.notify.changeState(dto);
        if (answer != null && ProcessUtil.ERROR.equals(answer.getStatus())) {
            logger.warn("Run {}: the report {} was refused: {}", run.getJobQueueId(), status, answer.getMessage());
        }
        return answer;
    }

    private static boolean refused(ResponseDto answer) {
        return answer == null || ProcessUtil.ERROR.equals(answer.getStatus());
    }

    /** What went wrong, in the words of the exception that said it: its own message, else its cause's, else its type. */
    static String reasonOf(Throwable ex) {
        for (Throwable at = ex; at != null; at = at.getCause() == at ? null : at.getCause()) {
            if (at.getMessage() != null && !at.getMessage().trim().isEmpty()) {
                return at.getMessage().trim();
            }
        }
        return ex.getClass().getSimpleName();
    }

    /** One attempt of one run. */
    private final class Execution {

        private final StepPlan plan;
        private final JobQueue run;
        private final long tenantId;
        private final int attempt;
        private final PipelineDefinition definition;
        private final PipelineDefinition.Settings settings;
        private final List<PipelineDefinition.Step> stepList;
        /** Each step's output by its key: the dataset key it wrote, or the one it passed on. */
        private final Map<String, String> outputs = new HashMap<>();
        private Dataset sourceRows;
        /** MIG-243: how long this run's datasets are kept, read once when the first is written. */
        private Duration keptFor;
        /** MIG-344: each dataset's row count by its key, so a step knows its input's size before reading it. */
        private final Map<String, Long> sizes = new HashMap<>();
        /** MIG-344: the run's memory budget. */
        private final RunMemory memory = RunMemory.ofMegabytes(runMemoryMb);

        Execution(StepPlan plan) {
            this.plan = plan;
            this.run = plan.run;
            this.tenantId = plan.job.getTenantId();
            this.attempt = Math.max(1, plan.run.getAttempt());
            this.definition = plan.definition;
            this.settings = plan.definition == null ? new PipelineDefinition.Settings() : plan.definition.effectiveSettings();
            this.stepList = plan.definition == null ? Collections.emptyList() : plan.definition.getSteps();
        }

        void go() {
            String pipelineId = this.plan.job.getTaskDetail().getPipelineId();
            ResponseDto started = report(this.run, JobStatus.Start, String.format("Taken by the step engine: pipeline %s, definition "
                + "version %d, %d step(s).", pipelineId, this.plan.stored.version, this.stepList.size()));
            if (refused(started)) {
                logger.error("Run {} was taken by the step engine but its Start was refused; the stall sweep will close it.",
                    this.run.getJobQueueId());
                return;
            }
            if (this.definition == null) {
                report(this.run, JobStatus.Failed, String.format("Declined by the step engine: definition version %d cannot be read: %s",
                    this.plan.stored.version, this.plan.unreadable));
                return;
            }
            // In the run's workspace (its task switches, MIG-231); no role -- an admin saved it.
            List<DefinitionProblem> problems = validator.problems(this.definition, this.tenantId, null);
            List<StepStore.Planned> planned = new ArrayList<>();
            for (int i = 0; i < this.stepList.size(); i++) {
                PipelineDefinition.Step step = this.stepList.get(i);
                planned.add(new StepStore.Planned(i, step.getKey(), step.getTask(), step.effectiveOnError(this.settings).word()));
            }
            if (!problems.isEmpty()) {
                // The version the run is pinned to no longer runs here (a task it names is gone): declined, as a worker
                // declines a pipeline it does not have -- Start -> Failed, never retried, never metered. Its steps are
                // shown, not run, so the timeline says why.
                String declined = String.format("Declined by the step engine: definition version %d no longer validates: %s",
                    this.plan.stored.version, problems.stream().limit(3).map(DefinitionProblem::toString).collect(Collectors.joining("; ")));
                try {
                    this.stopAll(steps.plan(this.run.getJobQueueId(), this.attempt, this.plan.stored.id, planned), 0,
                        JobStatus.Skip.name(), "Not run: " + declined);
                } catch (RuntimeException unrecorded) {
                    logger.warn("Run {}: its declined steps could not be recorded: {}", this.run.getJobQueueId(), reasonOf(unrecorded));
                }
                report(this.run, JobStatus.Failed, declined);
                return;
            }
            List<Long> rows = steps.plan(this.run.getJobQueueId(), this.attempt, this.plan.stored.id, planned);
            ResponseDto running = report(this.run, JobStatus.Running, String.format("Running %d step(s): %s.", this.stepList.size(),
                this.stepList.stream().map(PipelineDefinition.Step::getKey).collect(Collectors.joining(", "))));
            if (refused(running)) {
                this.stopAll(rows, 0, "Interrupt", "Not run: the run was closed before its steps started.");
                return;
            }
            Outcome outcome;
            try {
                outcome = this.steps(rows);
            } finally {
                // MIG-344: whatever a try spilled and did not remove (one that timed out) goes with the run.
                datasets.removeScratch(this.run.getJobQueueId(), this.attempt);
            }
            if (outcome.interrupted) {
                logger.info("Run {} was moved on while its steps ran; its remaining steps are Interrupt.", this.run.getJobQueueId());
                return;
            }
            if (outcome.failure != null) {
                report(this.run, JobStatus.Failed, outcome.failure);
            } else {
                report(this.run, JobStatus.Completed, outcome.summary(this.stepList.size()));
            }
        }

        private Outcome steps(List<Long> rows) {
            Outcome outcome = new Outcome();
            Dataset source = this.source();
            this.sourceRows = source;
            // What a step reads is a reference: the dataset key of the output it reads (run_dataset.storage_key), or
            // null for the source. A step that makes no dataset passes on the reference it read.
            String latest = null;
            Map<String, String> outputs = this.outputs;
            int total = this.stepList.size();
            for (int i = 0; i < total; i++) {
                PipelineDefinition.Step step = this.stepList.get(i);
                long row = rows.get(i);
                if (outcome.stopReason != null) {
                    steps.notRun(row, JobStatus.Skip.name(), outcome.stopReason);
                    outcome.skipped++;
                    continue;
                }
                if (!this.stillRunning()) {
                    this.stopAll(rows, i, JobStatus.Interrupt.name(), "Not run: the run was closed while its steps ran.");
                    outcome.interrupted = true;
                    return outcome;
                }
                OnError onError = step.effectiveOnError(this.settings);
                String label = String.format("Step %d/%d <%s> (%s)", i + 1, total, step.getKey(), step.getTask());
                StepLog log = new StepLog(row);
                boolean named = step.getInput() != null;
                if (named && !outputs.containsKey(step.getInput())) {
                    // It names a step that failed (and continued): there is nothing to read.
                    steps.started(row, null);
                    String error = String.format("its input <%s> produced no output", step.getInput());
                    log.line("ERROR", "Not run: " + error + ".");
                    steps.ended(row, JobStatus.Failed.name(), null, errorJson(error, 0, false), "Failed: " + error + ".");
                    this.audit(String.format("%s failed: %s (on error: %s).", label, error, onError.word()));
                    outcome.failed(this.stepFailed(i, step, error), onError, step);
                    continue;
                }
                String inputKey = named ? outputs.get(step.getInput()) : latest;
                Input input = new Input(inputKey, source);
                steps.started(row, input.size());
                this.audit(String.format("%s started on %d record(s).", label, input.size()));
                long began = System.nanoTime();
                this.memory.resetPeak();
                Tried tried = this.tryStep(step, row, input, log);
                long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
                // MIG-344: what a streaming step held, on its log -- for a step that read a batch or more, or spilled (a small
                // step's log stays as it was).
                if (tried.streamed && (input.size() >= batchRows || this.memory.spilled() > 0)) {
                    log.line("INFO", String.format("Streamed in batches of %,d row(s): at most about %s held at once of the run's %s "
                        + "memory budget%s.", batchRows, RunMemory.megabytes(this.memory.peak()), RunMemory.megabytes(this.memory.budget()),
                        this.memory.spilled() > 0 ? String.format("; %s spilled to disk", RunMemory.megabytes(this.memory.spilled())) : ""));
                }
                if (tried.result != null) {
                    Dataset output = tried.result.getOutput();
                    Long out = tried.result.getRecordsOut();
                    String passed = tried.outputKey != null ? this.recordStreamed(row, tried)
                        : output != null ? this.keep(row, step, output) : inputKey;
                    outputs.put(step.getKey(), passed);
                    latest = passed;
                    String done = String.format("Completed in %.1f s: %d in, %s out%s.", millis / 1000.0, input.size(),
                        out == null ? "no records" : out.toString(), tried.tries > 1 ? String.format(", on try %d", tried.tries) : "");
                    steps.ended(row, JobStatus.Completed.name(), out, null, done);
                    this.audit(String.format("%s completed: %d in, %s out, %.1f s.", label, input.size(), out == null ? "0" : out.toString(),
                        millis / 1000.0));
                    outcome.completed++;
                } else {
                    String failedLine = String.format("Failed after %d %s: %s", tried.tries, tried.tries == 1 ? "try" : "tries", tried.error);
                    steps.ended(row, JobStatus.Failed.name(), null, errorJson(tried.error, tried.tries, tried.timedOut),
                        failedLine + String.format(" (on error: %s).", onError.word()));
                    this.audit(String.format("%s failed after %d %s: %s (on error: %s).", label, tried.tries,
                        tried.tries == 1 ? "try" : "tries", tried.error, onError.word()));
                    outcome.failed(this.stepFailed(i, step, tried.error), onError, step);
                }
            }
            return outcome;
        }

        /**
         * The dataset behind a reference, whole: the source for none, else what the dataset store holds under the key --
         * held to {@link Limits} (MIG-344: a streamed dataset may be bigger than a step can hold).
         */
        private Dataset load(String key, Dataset source, String what) {
            if (key == null) {
                return source;
            }
            try (RowSource rows = datasets.open(key, batchRows)) {
                return RowSource.materialize(rows, what);
            } catch (IllegalStateException tooBig) {
                throw tooBig;
            } catch (Exception ex) {
                throw new IllegalStateException(String.format("The dataset %s could not be read back: %s", key, reasonOf(ex)), ex);
            }
        }

        /** A step's input: a reference, read only when the task asks -- whole, or a batch at a time. */
        private final class Input {
            final String key;
            final Dataset source;
            private Dataset loaded;
            private Long size;

            Input(String key, Dataset source) {
                this.key = key;
                this.source = source;
            }

            long size() {
                if (this.key == null) {
                    return this.source.size();
                }
                if (this.size == null) {
                    this.size = sizes.get(this.key);
                }
                if (this.size == null) {
                    try (RowSource rows = datasets.open(this.key, 1)) {
                        this.size = rows.size();
                    } catch (Exception ex) {
                        throw new IllegalStateException(String.format("The dataset %s could not be read back: %s", this.key, reasonOf(ex)), ex);
                    }
                }
                return this.size;
            }

            synchronized Dataset whole() {
                if (this.loaded == null) {
                    this.loaded = load(this.key, this.source, "The step's input");
                }
                return this.loaded;
            }

            RowSource open() throws Exception {
                return this.key == null ? RowSource.of(this.source, batchRows) : datasets.open(this.key, batchRows);
            }

            /** The first row, or null: one row read, whatever the input's size. */
            synchronized Map<String, Object> first() throws Exception {
                Dataset held = this.key == null ? this.source : this.loaded;
                if (held != null) {
                    return held.size() == 0 ? null : held.getRows().get(0);
                }
                try (RowSource rows = datasets.open(this.key, 1)) {
                    List<Map<String, Object>> batch = rows.next();
                    return batch == null || batch.isEmpty() ? null : batch.get(0);
                } catch (Exception ex) {
                    throw new IllegalStateException(String.format("The dataset %s could not be read back: %s", this.key, reasonOf(ex)), ex);
                }
            }
        }

        /** MIG-344: where a step's output goes. */
        String datasetKey(String stepKey) {
            return datasets.outputKeyOf(this.run.getJobQueueId(), this.attempt, stepKey, "output");
        }

        DatasetStore store() {
            return datasets;
        }

        /** The run's status line when this step's failure fails it. */
        private String stepFailed(int index, PipelineDefinition.Step step, String error) {
            return String.format("Step %d <%s> failed: %s", index + 1, step.getKey(), error);
        }

        /** Every try of one step, each bounded by its timeout; the first success, or the last failure. */
        private Tried tryStep(PipelineDefinition.Step step, long row, Input input, StepLog log) {
            Optional<StepTask> task = tasks.find(step.getTask());
            // The step's own retry and timeout, else its task's registry defaults (MIG-231).
            int maxTries = task.map(found -> step.effectiveMaxAttempts(found.spec().maxAttempts())).orElse(step.effectiveMaxAttempts());
            int delay = task.map(found -> step.effectiveDelaySeconds(found.spec().delaySeconds())).orElse(step.effectiveDelaySeconds());
            int timeout = step.effectiveTimeoutSeconds(this.settings, task.map(found -> found.spec().timeoutSeconds()).orElse(null));
            Tried tried = new Tried();
            for (int t = 1; t <= maxTries; t++) {
                tried.tries = t;
                steps.tried(row, t);
                if (maxTries > 1) {
                    log.line("INFO", String.format("Try %d of %d.", t, maxTries));
                }
                boolean streams = task.isPresent() && task.get() instanceof StreamingStepTask;
                Context context = streams ? new StreamTry(this, step, row, input, t, log) : new Context(this, step, row, input, t, log);
                tried.streamed = streams;
                Future<StepResult> future;
                try {
                    future = tryThreads.submit(() -> RowSecurity.forTenant(this.tenantId, () -> {
                        try {
                            return streams ? ((StreamingStepTask) task.get()).stream((StreamTry) context) : task.get().run(context);
                        } catch (RuntimeException ex) {
                            throw ex;
                        } catch (Exception ex) {
                            throw new StepFailure(ex);
                        }
                    }));
                } catch (RejectedExecutionException full) {
                    // MIG-214: the bounded try pool is full of tries that timed out and are still unwinding.
                    tried.error = "no thread was free for this try: earlier tries that timed out are still unwinding";
                    tried.timedOut = false;
                    future = null;
                }
                if (future != null) {
                    try {
                        tried.result = future.get(timeout, TimeUnit.SECONDS);
                        String uncommitted = streams ? this.commitStreamed((StreamTry) context, tried) : null;
                        tried.timedOut = false;
                        if (uncommitted == null) {
                            tried.error = null;
                            return tried;
                        }
                        // The work succeeded but its output could not be kept: a failed try.
                        tried.result = null;
                        tried.error = uncommitted;
                    } catch (TimeoutException ex) {
                        future.cancel(true);
                        tried.error = String.format("timed out after %d s", timeout);
                        tried.timedOut = true;
                    } catch (ExecutionException ex) {
                        Throwable cause = ex.getCause() instanceof StepFailure ? ex.getCause().getCause() : ex.getCause();
                        tried.error = reasonOf(cause == null ? ex : cause);
                        tried.timedOut = false;
                    } catch (InterruptedException ex) {
                        future.cancel(true);
                        Thread.currentThread().interrupt();
                        tried.error = "the step engine was stopped";
                        if (streams) {
                            ((StreamTry) context).discard();
                        }
                        log.line("ERROR", String.format("Try %d stopped: the step engine is shutting down.", t));
                        return tried;
                    }
                }
                if (streams) {
                    ((StreamTry) context).discard();
                }
                log.line(t < maxTries ? "WARN" : "ERROR", String.format("Try %d of %d failed: %s", t, maxTries, tried.error));
                if (t < maxTries && delay > 0) {
                    try {
                        sleeper.sleep(Duration.ofSeconds(delay));
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        tried.error = "the step engine was stopped";
                        return tried;
                    }
                }
            }
            return tried;
        }

        /** The output, to the dataset store, and its reference, to run_dataset; the reference. */
        private String keep(long row, PipelineDefinition.Step step, Dataset output) {
            String key = datasets.outputKeyOf(this.run.getJobQueueId(), this.attempt, step.getKey(), "output");
            try {
                datasets.write(key, output);
            } catch (Exception ex) {
                throw new IllegalStateException(String.format("The output of step <%s> could not be kept: %s", step.getKey(),
                    reasonOf(ex)), ex);
            }
            String columns;
            try {
                columns = JSON.writeValueAsString(output.getColumns());
            } catch (JsonProcessingException ex) {
                columns = "[]";
            }
            steps.dataset(row, "output", key, output.size(), columns, this.expiry());
            this.sizes.put(key, (long) output.size());
            return key;
        }

        /**
         * MIG-344: a streaming try that succeeded -- its output file is committed (it was a partial file until now), or
         * dropped when the step made no dataset; its spill files go either way. Returns why the output could not be
         * kept, or null when it was.
         */
        private String commitStreamed(StreamTry context, Tried tried) {
            try {
                if (tried.result.isStreamed()) {
                    DatasetStore.Output output = context.output == null ? context.startOutput() : context.output;
                    output.commit();
                    tried.outputKey = context.outputKey;
                    tried.outputRows = output.size();
                    tried.outputColumns = output.columns();
                    // A streaming try's records out are the rows it wrote.
                    tried.result = StepResult.streamed(output.size());
                } else {
                    context.discard();
                }
                return null;
            } catch (Exception ex) {
                context.discard();
                return String.format("its output could not be kept: %s", reasonOf(ex));
            } finally {
                context.removeScratch();
            }
        }

        /** The run_dataset row of a streamed output; the reference. */
        private String recordStreamed(long row, Tried tried) {
            String columns;
            try {
                columns = JSON.writeValueAsString(tried.outputColumns);
            } catch (JsonProcessingException ex) {
                columns = "[]";
            }
            steps.dataset(row, "output", tried.outputKey, tried.outputRows, columns, this.expiry());
            this.sizes.put(tried.outputKey, tried.outputRows);
            return tried.outputKey;
        }

        /** The first step's input when it names none: the task payload as one row, or nothing. */
        private Dataset source() {
            if (!PipelineDefinition.Source.TASK.equals(this.definition.effectiveSourceType())) {
                return Dataset.EMPTY;
            }
            String payload = this.plan.job.getTaskDetail().getTaskPayload();
            try {
                Map<String, Object> row = new LinkedHashMap<>(PayloadXml.parse(payload).fields());
                return Dataset.of(Collections.singletonList(row));
            } catch (Exception ex) {
                throw new IllegalStateException("The task payload is not well-formed XML, so it cannot be the pipeline's source: "
                    + reasonOf(ex), ex);
            }
        }

        /** An earlier step's output, for a step that reads a second one (a join). */
        private Dataset outputOf(String stepKey) {
            if (!this.outputs.containsKey(stepKey)) {
                throw new IllegalStateException(String.format("step <%s> has no output to read: it is not an earlier step, or it failed",
                    stepKey));
            }
            return this.load(this.outputs.get(stepKey), this.sourceRows, String.format("The output of step <%s>", stepKey));
        }

        /** A file a step made, beside the run's datasets, and its run_dataset row. */
        /** Returns {run_dataset_id, expiry in epoch millis}: the manifest names the same dataset and expiry. */
        private long[] keepFile(long row, String stepKey, String fileName, byte[] content, long rows, List<String> columns) throws Exception {
            String key = DatasetStore.fileKeyOf(this.run.getJobQueueId(), this.attempt, stepKey, fileName);
            datasets.writeFile(key, content);
            return this.recordFile(row, key, fileName, rows, columns);
        }

        /** MIG-344: a file a streaming step writes, straight to the store; its run_dataset row; its size and sha256. */
        private StreamContext.KeptFile keepFile(long row, String stepKey, String fileName, List<String> columns, long rows,
                                                StreamContext.FileWriter writer, Map<String, long[]> kept) throws Exception {
            String key = DatasetStore.fileKeyOf(this.run.getJobQueueId(), this.attempt, stepKey, fileName);
            DatasetStore.FileOutput file = datasets.createFile(key);
            MessageDigest digest = Streams.sha256();
            long bytes;
            try {
                Streams.Bounded bounded = new Streams.Bounded(file.stream(), Limits.MAX_STREAM_FILE_BYTES, "The file");
                OutputStream out = new DigestOutputStream(bounded, digest);
                writer.write(out);
                out.flush();
                bytes = bounded.count();
                file.commit();
            } catch (Exception | Error failed) {
                file.abort();
                throw failed;
            }
            kept.put(fileName, this.recordFile(row, key, fileName, rows, columns));
            return new StreamContext.KeptFile(bytes, Streams.hex(digest.digest()));
        }

        private long[] recordFile(long row, String key, String fileName, long rows, List<String> columns) {
            String columnsJson;
            try {
                columnsJson = JSON.writeValueAsString(columns == null ? Collections.emptyList() : columns);
            } catch (JsonProcessingException ex) {
                columnsJson = "[]";
            }
            Instant expires = this.expiry();
            return new long[] {steps.dataset(row, fileName, key, rows, columnsJson, expires), expires.toEpochMilli()};
        }

        /**
         * When a dataset written now expires: the pipeline's datasetRetentionHours, cut to the workspace's data policy for
         * the pipeline's sensitivity (MIG-243, {@link RetentionPolicy}).
         */
        private Instant expiry() {
            if (this.keptFor == null) {
                this.keptFor = retention.retentionFor(this.tenantId, this.settings);
            }
            return Instant.now().truncatedTo(ChronoUnit.MILLIS).plus(this.keptFor);
        }

        /**
         * A file the step wrote, in the run's manifest (run_output): a kept file with the dataset holding it and its
         * expiry, an upload with its bucket and key. A file the step did not keep is refused: the manifest says only what
         * the run holds.
         */
        private void recordOutput(long row, RunOutput output, Map<String, long[]> kept) {
            if (RunOutput.FILE.equals(output.getKind())) {
                long[] dataset = kept.get(output.getName());
                if (dataset == null) {
                    throw new IllegalStateException(String.format("'%s' was not kept with the run, so it is not in its manifest.",
                        output.getName()));
                }
                steps.output(row, output, dataset[0], Instant.ofEpochMilli(dataset[1]));
            } else {
                steps.output(row, output, null, null);
            }
        }

        private boolean stillRunning() {
            return transactions.findJobQueueByJobQueueId(this.run.getJobQueueId())
                .map(current -> current.getJobStatus() == JobStatus.Running).orElse(false);
        }

        private void stopAll(List<Long> rows, int from, String status, String message) {
            for (int i = from; i < rows.size(); i++) {
                steps.notRun(rows.get(i), status, message);
            }
        }

        private void audit(String line) {
            local.execute(status -> {
                transactions.saveJobAuditLogs(this.run.getJobQueueId(), line);
                return null;
            });
        }
    }

    /** What a run's steps came to. */
    private static final class Outcome {
        String failure;
        String stopReason;
        boolean interrupted;
        int completed;
        int failedAndContinued;
        int skipped;
        String stoppedAt;

        void failed(String runFailure, OnError onError, PipelineDefinition.Step step) {
            switch (onError) {
                case CONTINUE:
                    this.failedAndContinued++;
                    break;
                case SKIP_REST:
                    this.stopReason = String.format("Skipped: step <%s> failed and its on-error is skip_rest.", step.getKey());
                    this.stoppedAt = step.getKey();
                    break;
                case FAIL:
                default:
                    this.failure = runFailure;
                    this.stopReason = String.format("Skipped: step <%s> failed and its on-error is fail.", step.getKey());
                    break;
            }
        }

        String summary(int total) {
            StringBuilder line = new StringBuilder(String.format("%d of %d step(s) completed", this.completed, total));
            if (this.failedAndContinued > 0) {
                line.append(String.format(", %d failed and continued", this.failedAndContinued));
            }
            if (this.stoppedAt != null) {
                line.append(String.format("; stopped after <%s> failed (skip_rest), %d skipped", this.stoppedAt, this.skipped));
            }
            return line.append('.').toString();
        }
    }

    /** One step's tries. */
    private static final class Tried {
        int tries;
        StepResult result;
        String error;
        boolean timedOut;
        /** MIG-344: the step streamed; its committed output's key, rows and columns (null key: no dataset). */
        boolean streamed;
        String outputKey;
        long outputRows;
        List<String> outputColumns;
    }

    /** A checked exception out of a task, carried through the try's thread. */
    private static final class StepFailure extends RuntimeException {
        StepFailure(Exception cause) {
            super(cause);
        }
    }

    /** One step's step_log, numbered across its tries; cut at {@link #MAX_LOG_LINES}. */
    private final class StepLog {
        private final long row;
        private final AtomicInteger lines = new AtomicInteger();

        StepLog(long row) {
            this.row = row;
        }

        void line(String level, String message) {
            int n = this.lines.incrementAndGet();
            if (n < MAX_LOG_LINES) {
                steps.log(this.row, n, level, message);
            } else if (n == MAX_LOG_LINES) {
                steps.log(this.row, n, "WARN", String.format("The log stops here: a step keeps at most %d lines.", MAX_LOG_LINES));
            }
        }
    }

    private static String errorJson(String message, int tries, boolean timedOut) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("tries", tries);
        error.put("timedOut", timedOut);
        try {
            return JSON.writeValueAsString(error);
        } catch (JsonProcessingException ex) {
            return "{}";
        }
    }

    /** What a task sees for one try. */
    private static class Context implements StepContext {
        final Execution execution;
        final PipelineDefinition.Step step;
        final long row;
        final Execution.Input input;
        private final int tryNumber;
        private final StepLog log;
        /** The files this try kept: name -> {run_dataset_id, expiry millis}. */
        final Map<String, long[]> kept = new HashMap<>();

        Context(Execution execution, PipelineDefinition.Step step, long row, Execution.Input input, int tryNumber, StepLog log) {
            this.execution = execution;
            this.step = step;
            this.row = row;
            this.input = input;
            this.tryNumber = tryNumber;
            this.log = log;
        }

        @Override
        public long tenantId() {
            return this.execution.tenantId;
        }

        @Override
        public long jobQueueId() {
            return this.execution.run.getJobQueueId();
        }

        @Override
        public int attempt() {
            return this.execution.attempt;
        }

        @Override
        public String stepKey() {
            return this.step.getKey();
        }

        @Override
        public int tryNumber() {
            return this.tryNumber;
        }

        @Override
        public Map<String, Object> config() {
            return this.step.effectiveConfig();
        }

        @Override
        public Dataset input() {
            return this.input.whole();
        }

        @Override
        public long inputSize() {
            return this.input.size();
        }

        @Override
        public Map<String, Object> firstInputRow() throws Exception {
            return this.input.first();
        }

        @Override
        public Long jobId() {
            return this.execution.run.getJobId();
        }

        @Override
        public String pipelineId() {
            return this.execution.plan.job.getTaskDetail() == null ? null : this.execution.plan.job.getTaskDetail().getPipelineId();
        }

        @Override
        public Long jobOwnerUserId() {
            return this.execution.plan.job.getCreatedBy();
        }

        @Override
        public String inputBucket() {
            return this.execution.run.getInputBucket();
        }

        @Override
        public String inputKey() {
            return this.execution.run.getInputKey();
        }

        @Override
        public Dataset dataset(String stepKey) {
            return this.execution.outputOf(stepKey);
        }

        @Override
        public void keepFile(String fileName, byte[] content, long rows, List<String> columns) throws Exception {
            this.kept.put(fileName, this.execution.keepFile(this.row, this.step.getKey(), fileName, content, rows, columns));
        }

        @Override
        public void recordOutput(RunOutput output) {
            this.execution.recordOutput(this.row, output, this.kept);
        }

        @Override
        public void log(String message) {
            this.log.line("INFO", message);
        }

        @Override
        public void warn(String message) {
            this.log.line("WARN", message);
        }
    }

    /** MIG-344: what a streaming task sees for one try -- its input as a stream, its output as a sink. */
    private static final class StreamTry extends Context implements StreamContext {
        DatasetStore.Output output;
        String outputKey;
        private Path scratch;
        private final RowSourceHolder sources = new RowSourceHolder();

        StreamTry(Execution execution, PipelineDefinition.Step step, long row, Execution.Input input, int tryNumber, StepLog log) {
            super(execution, step, row, input, tryNumber, log);
        }

        @Override
        public RowSource openInput() throws Exception {
            return this.sources.track(new Batched(this.input.open(), this.execution.memory));
        }

        @Override
        public synchronized RowSink output() {
            if (this.output == null) {
                try {
                    return this.startOutput();
                } catch (Exception ex) {
                    throw new IllegalStateException("The step's output could not be started: " + reasonOf(ex), ex);
                }
            }
            return this.output;
        }

        synchronized DatasetStore.Output startOutput() throws Exception {
            if (this.output == null) {
                this.outputKey = this.execution.datasetKey(this.step.getKey());
                this.output = this.execution.store().create(this.outputKey);
            }
            return this.output;
        }

        @Override
        public synchronized Path scratch() throws IOException {
            if (this.scratch == null) {
                this.scratch = this.execution.store().scratch(this.jobQueueId(), this.attempt(), this.step.getKey());
            }
            return this.scratch;
        }

        @Override
        public RunMemory memory() {
            return this.execution.memory;
        }

        @Override
        public long maxFileBytes() {
            return Limits.MAX_STREAM_FILE_BYTES;
        }

        @Override
        public long maxRows() {
            return Limits.MAX_STREAM_ROWS;
        }

        @Override
        public KeptFile keepFile(String fileName, List<String> columns, long rows, FileWriter writer) throws Exception {
            return this.execution.keepFile(this.row, this.step.getKey(), fileName, columns, rows, writer, this.kept);
        }

        /** Drops the output (a failed, timed-out or dataset-less try) and the spill files. */
        synchronized void discard() {
            if (this.output != null) {
                this.output.abort();
            }
            this.removeScratch();
            this.sources.closeAll();
        }

        synchronized void removeScratch() {
            Streams.deleteTree(this.scratch);
            this.scratch = null;
            this.sources.closeAll();
        }
    }

    /** The sources a try opened, closed when it ends whether or not the task closed them. */
    private static final class RowSourceHolder {
        private final List<RowSource> open = new ArrayList<>();

        synchronized RowSource track(RowSource source) {
            this.open.add(source);
            return source;
        }

        synchronized void closeAll() {
            for (RowSource source : this.open) {
                try {
                    source.close();
                } catch (Exception ignored) {
                    // closing
                }
            }
            this.open.clear();
        }
    }

    /** A step's input a batch at a time, each batch counted against the run's memory budget while the step holds it. */
    private static final class Batched implements RowSource {
        private final RowSource source;
        private final RunMemory memory;
        private long held;
        private double bytesPerRow = -1;

        Batched(RowSource source, RunMemory memory) {
            this.source = source;
            this.memory = memory;
        }

        @Override
        public List<String> columns() {
            return this.source.columns();
        }

        @Override
        public long size() {
            return this.source.size();
        }

        @Override
        public List<Map<String, Object>> next() throws Exception {
            this.memory.release(this.held);
            this.held = 0;
            List<Map<String, Object>> batch = this.source.next();
            if (batch != null) {
                if (this.bytesPerRow < 0) {
                    // The first batch is weighed row by row; the rest are taken to weigh the same per row.
                    this.bytesPerRow = (double) RunMemory.estimate(batch) / batch.size();
                }
                this.held = (long) (this.bytesPerRow * batch.size());
                this.memory.reserve(this.held);
            }
            return batch;
        }

        @Override
        public void close() throws Exception {
            this.memory.release(this.held);
            this.held = 0;
            this.source.close();
        }
    }
}

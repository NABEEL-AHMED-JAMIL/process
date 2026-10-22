package process.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.barco.platform.correlation.CorrelationId;
import org.barco.platform.correlation.CorrelationScope;
import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import process.pipeline.registry.TaskKind;
import process.model.enums.JobAuditMarker;
import process.settings.EngineDial;
import process.settings.EngineDials;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import process.util.AuditText;
import process.ai.ModelProfiles;
import process.ai.PayloadXml;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.service.NotifyService;
import process.model.service.impl.TransactionServiceImpl;
import process.pipeline.backing.DocumentDatasets;
import process.pipeline.data.Limits;
import process.pipeline.data.RowSink;
import process.pipeline.data.RowSource;
import process.pipeline.data.RunMemory;
import process.pipeline.data.Values;
import process.util.BusinessTime;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;

import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.TreeSet;
import process.pipeline.approval.RunApprovalStore;
import process.pipeline.approval.RunApprovalEvents;
import java.util.Set;

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
        /** MIG-345: the attempt someone asked for (a resume or a restart); null for a first attempt or the policy's retry. */
        public final RunAttemptStore.Attempt asked;

        StepPlan(SourceJob job, JobQueue run, PipelineDefinitionStore.Stored stored, PipelineDefinition definition, String unreadable,
                 RunAttemptStore.Attempt asked) {
            this.job = job;
            this.run = run;
            this.stored = stored;
            this.definition = definition;
            this.unreadable = unreadable;
            this.asked = asked;
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
    /** ZT-P16: how long the engine's hold on a run lasts, and how often it is renewed while the run's steps run. */
    static final int LEASE_SECONDS = 120;
    static volatile long leaseRenewMillis = 30_000;

    /** ZT-P7: how often a running step looks whether its run was closed (marked interrupted or failed) meanwhile. */
    static volatile long closeCheckMillis = 2000;

    private final ExecutorService runThreads;
    private final ExecutorService tryThreads;
    private final Sleeper sleeper;
    /** MIG-243: how long a run's datasets are kept -- the definition's hours against the workspace's data policy. */
    private RetentionPolicy retention = RetentionPolicy.DEFINITION_ONLY;
    /** MIG-344: each run's memory budget, and the rows a streaming step reads at a time. */
    private int runMemoryMb = RunMemory.DEFAULT_MB;
    private int batchRows = RowSource.DEFAULT_BATCH;

    /**
     * Owner #24: how many runs at once is Platform › Engine settings' "Runs at once" (its default
     * process.pipeline.engine.threads), read when Core starts -- a change takes effect at the next restart.
     */
    @Autowired
    public StepEngine(PipelineDefinitionStore definitions, StepStore steps, StepTasks tasks, DefinitionValidator validator,
        DatasetStore datasets, NotifyService notify, TransactionServiceImpl transactions, PlatformTransactionManager transactionManager,
        EngineDials dials) {
        this(definitions, steps, tasks, validator, datasets, notify, transactions, transactionManager,
            dials.platformInt(EngineDial.ENGINE_THREADS));
        this.dials = dials;
    }

    public StepEngine(PipelineDefinitionStore definitions, StepStore steps, StepTasks tasks, DefinitionValidator validator,
        DatasetStore datasets, NotifyService notify, TransactionServiceImpl transactions, PlatformTransactionManager transactionManager,
        int threads) {
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

    /** MIG-360: the files a run took when it took a batch (the inbox's store); without it, a run's own file alone. */
    private RunInputs runInputs;
    /** MIG-347: a run's {{param.*}} and {{run.*}} values; without one wired, the business zone and the defaults. */
    private RunValuesResolver runValues = RunValuesResolver.plain();
    /** MIG-345: the attempts someone asked for (a resume, a restart); without it, every attempt starts at the first step. */
    private RunAttemptStore attempts;

    /** MIG-346: the Approval step's requests and their events; without them an Approval step fails, saying so. */
    private RunApprovalStore approvals;
    private RunApprovalEvents approvalEvents;

    @Autowired(required = false)
    public void useApprovals(RunApprovalStore approvals, RunApprovalEvents approvalEvents) {
        this.approvals = approvals;
        this.approvalEvents = approvalEvents;
    }

    @Autowired(required = false)
    public void useAttempts(RunAttemptStore attempts) {
        this.attempts = attempts;
    }

    @Autowired(required = false)
    public void useRunInputs(RunInputs runInputs) {
        this.runInputs = runInputs;
    }

    @Autowired(required = false)
    public void useRunValues(RunValuesResolver runValues) {
        this.runValues = runValues == null ? RunValuesResolver.plain() : runValues;
    }

    /** MIG-364: the documents a run was started for (the document trigger's store) and where their values are read. */
    private RunDocuments runDocuments;
    private DocumentDatasets documentDatasets;

    @Autowired(required = false)
    public void useRunDocuments(RunDocuments runDocuments, DocumentDatasets documentDatasets) {
        this.runDocuments = runDocuments;
        this.documentDatasets = documentDatasets;
    }

    /** MIG-243: the data policy's retention (PolicyRetention); without one, the definition's hours alone, as before. */
    @Autowired(required = false)
    public void useRetention(RetentionPolicy retention) {
        this.retention = retention == null ? RetentionPolicy.DEFINITION_ONLY : retention;
    }

    /** MIG-344: a fixed memory budget in MB and batch, for an engine built without the engine settings (tests). */
    public void useMemory(int runMemoryMb, int batchRows) {
        this.runMemoryMb = Math.max(16, runMemoryMb);
        this.batchRows = Math.max(1, batchRows);
    }

    /**
     * Owner #24: Platform › Engine settings -- each run's memory budget (the workspace's own, else the platform's) and the
     * rows a streaming step reads at a time, read when a run starts. Their defaults are process.pipeline.run-memory-mb and
     * process.pipeline.batch-rows.
     */
    private EngineDials dials;

    @Autowired(required = false)
    public void useDials(EngineDials dials) {
        this.dials = dials;
    }

    int runMemoryMbFor(long tenantId) {
        return this.dials == null ? this.runMemoryMb : this.dials.forWorkspaceInt(EngineDial.RUN_MEMORY_MB, tenantId);
    }

    int batchRows() {
        return this.dials == null ? this.batchRows : this.dials.platformInt(EngineDial.BATCH_ROWS);
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
        return this.planOf(job, run, true);
    }

    /**
     * MIG-346: the plan of a run already under way (one waiting at an Approval step): as {@link #planFor}, but its job may
     * have been switched off since -- a run in flight finishes, as one on a worker does.
     */
    public Optional<StepPlan> planToContinue(SourceJob job, JobQueue run) {
        return this.planOf(job, run, false);
    }

    private Optional<StepPlan> planOf(SourceJob job, JobQueue run, boolean mustBeActive) {
        if (job == null || run == null || job.getTenantId() == null || job.getTaskDetail() == null
            || (mustBeActive && job.getJobStatus() != Status.Active)) {
            return Optional.empty();
        }
        // MIG-345: an attempt someone asked for runs the version it was planned on (a resume may run a newer one).
        RunAttemptStore.Attempt asked = this.attempts == null ? null
            : this.attempts.find(run.getJobQueueId(), Math.max(1, run.getAttempt())).orElse(null);
        Optional<PipelineDefinitionStore.Stored> stored = asked != null && asked.pipelineDefinitionId != null
            ? this.definitions.byId(asked.pipelineDefinitionId) : Optional.empty();
        if (!stored.isPresent()) {
            stored = this.steps.pinnedDefinition(run.getJobQueueId()).flatMap(this.definitions::byId);
        }
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
            return Optional.of(new StepPlan(job, run, stored.get(), null, reasonOf(unreadable), asked));
        }
        if (definition.isLegacy()) {
            return Optional.empty();
        }
        return Optional.of(new StepPlan(job, run, stored.get(), definition, null, asked));
    }

    /** ZT-P16: the runs this engine holds now, by id, with their workspace (the renewal runs in it). */
    private final Map<Long, Long> leased = new ConcurrentHashMap<>();
    private volatile ScheduledExecutorService leaseTimer;

    private void lease(JobQueue run) {
        this.leased.put(run.getJobQueueId(), run.getTenantId());
        this.renew(run.getJobQueueId(), run.getTenantId());
        if (this.leaseTimer == null) {
            synchronized (this) {
                if (this.leaseTimer == null) {
                    this.leaseTimer = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "step-engine-lease");
                        t.setDaemon(true);
                        return t;
                    });
                    this.leaseTimer.scheduleAtFixedRate(() -> this.leased.forEach(this::renew), leaseRenewMillis, leaseRenewMillis,
                        TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    private void renew(Long jobQueueId, Long tenantId) {
        try {
            RowSecurity.forTenant(tenantId, () -> this.steps.engineLease(jobQueueId, LEASE_SECONDS));
        } catch (RuntimeException ex) {
            logger.warn("Run {}: the step engine's hold could not be renewed: {}", jobQueueId, ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    private void release(JobQueue run) {
        this.leased.remove(run.getJobQueueId());
        try {
            this.steps.engineLeaseEnd(run.getJobQueueId());
        } catch (RuntimeException ex) {
            logger.warn("Run {}: the step engine's hold could not be cleared: {}", run.getJobQueueId(), ExceptionUtil.getRootCauseMessage(ex));
        }
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

    /**
     * MIG-346: a run waiting at an Approval step, decided: approved, it continues from the next step; otherwise it fails with
     * {@code why}. On the engine's threads, in the run's workspace; nothing happens when the run no longer waits there.
     */
    public void decided(StepPlan plan, RunApprovalStore.Approval approval, boolean approved, String why) {
        long tenantId = plan.job.getTenantId();
        this.runThreads.execute(() -> RowSecurity.forTenant(tenantId, () -> {
            JobQueue run = plan.run;
            CorrelationScope scope = CorrelationScope.open(run.getCorrelationId() == null ? CorrelationId.generate() : run.getCorrelationId());
            this.lease(run);
            try {
                Execution execution = new Execution(plan);
                if (plan.definition == null) {
                    this.report(run, JobStatus.Failed, "The run's definition cannot be read any more: " + plan.unreadable);
                } else if (approved) {
                    execution.continueAfter(approval);
                } else {
                    execution.stopAfter(approval, why);
                }
            } catch (Throwable ex) {
                logger.error("The step engine stopped on run {} after its approval: {}", run.getJobQueueId(), ExceptionUtil.getRootCauseMessage(ex));
                try {
                    this.report(run, JobStatus.Failed, "The step engine stopped: " + reasonOf(ex));
                } catch (RuntimeException unreported) {
                    logger.error("Run {} could not be reported Failed; the stall sweep will close it: {}", run.getJobQueueId(),
                        ExceptionUtil.getRootCauseMessage(unreported));
                }
            } finally {
                this.release(run);
                scope.close();
            }
        }));
    }

    /** One run, start to end. Never throws: what cannot be reported is logged, and the stall sweep closes the run. */
    void run(StepPlan plan) {
        JobQueue run = plan.run;
        boolean claimed = false;
        if (run.getCorrelationId() == null) {
            run.setCorrelationId(CorrelationId.generate());
        }
        CorrelationScope scope = CorrelationScope.open(run.getCorrelationId());
        try {
            if (!this.steps.claimForEngine(run.getJobQueueId(), run.getCorrelationId())) {
                logger.info("Run {} was taken, sent or closed before the step engine could take it; left as it is.", run.getJobQueueId());
                return;
            }
            claimed = true;
            this.lease(run);
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
            if (claimed) {
                this.release(run);
            }
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

    /** A line ending in one full stop: "Approved by Ana: checked." rather than "checked..". */
    static String sentence(String text) {
        return AuditText.sentence(text);
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
        /** MIG-344: the run's memory budget (owner #24: the workspace's, read when the run starts). */
        private final RunMemory memory;
        /** MIG-360: the files the run was started for, read once, when a step first asks. */
        private volatile List<String> inputKeys;
        /** MIG-364: the documents the run was started for, and the first one's values, each read once, when a step first asks. */
        private volatile List<RunDocuments.Document> documents;
        private volatile Map<String, Object> documentValues;
        /** MIG-347: the run's parameter and run.* values, worked out once, when the attempt starts or a step first asks. */
        private volatile RunValuesResolver.Outcome values;
        /** The pipeline's name (pipeline.pipeline_name), read once, when a step first asks; empty when it has none. */
        private volatile Optional<String> pipelineName;

        Execution(StepPlan plan) {
            this.plan = plan;
            this.run = plan.run;
            this.tenantId = plan.job.getTenantId();
            this.memory = RunMemory.ofMegabytes(runMemoryMbFor(this.tenantId));
            this.attempt = Math.max(1, plan.run.getAttempt());
            this.definition = plan.definition;
            this.settings = plan.definition == null ? new PipelineDefinition.Settings() : plan.definition.effectiveSettings();
            this.stepList = plan.definition == null ? Collections.emptyList() : plan.definition.getSteps();
        }

        /** MIG-347: the run's values (RunValuesResolver), once per attempt. */
        RunValuesResolver.Outcome values() {
            RunValuesResolver.Outcome found = this.values;
            if (found == null) {
                found = runValues.resolve(this.plan.job, this.run, this.definition);
                this.values = found;
            }
            return found;
        }

        /** MIG-347: {{param.*}} and {{run.*}} by placeholder name; empty when the run's parameters do not stand. */
        Map<String, Object> placeholders() {
            RunValuesResolver.Outcome found = this.values();
            return found.values == null ? Collections.<String, Object>emptyMap() : found.values.placeholders();
        }

        /**
         * The name of the pipeline this run's definition belongs to -- one query, the first time a step asks, in the run's
         * workspace (a step runs inside RowSecurity.forTenant); null when the plan has no stored definition or the
         * pipeline has no name.
         */
        String pipelineName() {
            Optional<String> found = this.pipelineName;
            if (found == null) {
                found = this.plan.stored == null || definitions == null ? Optional.<String>empty()
                    : definitions.pipelineName(this.tenantId, this.plan.stored.pipelineKey);
                this.pipelineName = found == null ? Optional.<String>empty() : found;
                found = this.pipelineName;
            }
            return found.orElse(null);
        }

        /** MIG-360: the run's files -- the batch it took, else its own file, else none. One query, the first time. */
        List<String> inputKeys() {
            List<String> keys = this.inputKeys;
            if (keys == null) {
                String own = this.run.getInputKey();
                if (own == null) {
                    keys = Collections.emptyList();
                } else {
                    List<String> taken = runInputs == null ? Collections.emptyList() : runInputs.keysOf(this.run.getJobQueueId());
                    keys = taken.size() > 1 ? Collections.unmodifiableList(new ArrayList<>(taken)) : Collections.singletonList(own);
                }
                this.inputKeys = keys;
            }
            return keys;
        }

        /** MIG-364: the run's approved documents -- one query, the first time (none for a run nothing was approved for). */
        List<RunDocuments.Document> documents() {
            List<RunDocuments.Document> found = this.documents;
            if (found == null) {
                found = runDocuments == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(runDocuments.documentsOf(this.run.getJobQueueId())));
                this.documents = found;
            }
            return found;
        }

        /**
         * MIG-364: the first document's values for {{document.<field>}}, read from Document Intelligence once per run (a
         * trusted read, billed to the workspace): its fields, its tables as JSON text, and id, file_name, type_version,
         * status, approval, approved_by, date_approved, dataset_row_id.
         */
        synchronized Map<String, Object> documentValues() throws Exception {
            if (this.documentValues != null) {
                return this.documentValues;
            }
            List<RunDocuments.Document> found = this.documents();
            if (found.isEmpty() || documentDatasets == null) {
                this.documentValues = Collections.emptyMap();
                return this.documentValues;
            }
            RunDocuments.Document first = found.get(0);
            DocumentDatasets.Request request = new DocumentDatasets.Request();
            request.tenantId = this.tenantId;
            request.documentTypeId = first.documentTypeId;
            request.extractionIds = Collections.singletonList(first.extractionId);
            request.limit = 1;
            request.jobQueueId = this.run.getJobQueueId();
            request.readKey = String.format("%d#%d#document", this.run.getJobQueueId(), this.attempt);
            DocumentDatasets.Page page = documentDatasets.read(request);
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("id", first.extractionId);
            values.put("type_version", first.typeVersion);
            if (!page.rows.isEmpty()) {
                JsonNode d = page.rows.get(0);
                values.put("file_name", Values.scalar(d.get("fileName")));
                values.put("status", Values.scalar(d.get("status")));
                values.put("approval", Values.scalar(d.get("approval")));
                values.put("approved_by", Values.scalar(d.get("approvedBy")));
                values.put("date_approved", Values.scalar(d.get("dateApproved")));
                values.put("dataset_row_id", Values.scalar(d.get("datasetRowId")));
                d.path("fields").properties().iterator().forEachRemaining(f -> values.putIfAbsent(f.getKey(), Values.scalar(f.getValue())));
                d.path("tables").properties().iterator().forEachRemaining(t -> values.putIfAbsent(t.getKey(), Values.scalar(t.getValue())));
            }
            this.documentValues = Collections.unmodifiableMap(values);
            return this.documentValues;
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
            // MIG-347: the run's parameters -- given, else defaults -- checked against the version it follows before a step runs.
            RunValuesResolver.Outcome values = this.values();
            if (values.refusal != null) {
                String declined = String.format("Declined by the step engine: %s", values.refusal);
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
            // MIG-345: a resume takes the earlier attempt's outputs of the steps before the one that failed -- read again at
            // the start, so an output that expired since the retry was asked for declines the attempt rather than fail a step.
            Resumed resumed = null;
            if (this.plan.asked != null && this.plan.asked.isResume()) {
                String cannot;
                try {
                    resumed = this.resumable();
                    cannot = null;
                } catch (IllegalStateException refusedResume) {
                    cannot = refusedResume.getMessage();
                }
                if (cannot != null) {
                    String declined = String.format("Declined by the step engine: attempt %d cannot resume from step %d: %s",
                        this.attempt, this.plan.asked.fromStepIndex + 1, cannot);
                    this.stopAll(rows, 0, JobStatus.Skip.name(), "Not run: " + declined);
                    report(this.run, JobStatus.Failed, declined);
                    return;
                }
            }
            String runningLine = resumed == null
                ? String.format("Running %d step(s): %s.", this.stepList.size(),
                    this.stepList.stream().map(PipelineDefinition.Step::getKey).collect(Collectors.joining(", ")))
                : String.format("Resuming from step %d <%s>: %d step(s) reused from %s (%s), running %s.", resumed.from + 1,
                    this.stepList.get(resumed.from).getKey(), resumed.from, resumed.attempts(),
                    this.stepList.subList(0, resumed.from).stream().map(PipelineDefinition.Step::getKey).collect(Collectors.joining(", ")),
                    this.stepList.subList(resumed.from, this.stepList.size()).stream().map(PipelineDefinition.Step::getKey)
                        .collect(Collectors.joining(", ")));
            if (values.values != null && (this.run.getRunSlotAt() != null || this.run.getRunParams() != null)) {
                runningLine += String.format(" For %s (%s to %s, %s)%s.", values.values.date, values.values.windowStart, values.values.windowEnd,
                    values.values.zone, values.values.params.isEmpty() ? "" : "; parameters " + values.values.params);
            }
            ResponseDto running = report(this.run, JobStatus.Running, runningLine);
            if (refused(running)) {
                this.stopAll(rows, 0, "Interrupt", "Not run: the run was closed before its steps started.");
                return;
            }
            // ZT-P17: the start marker a worker writes as it picks a run up (JobAuditMarker, WORKER-CONTRACT 4.1): the run
            // report measures execution from it, and without it every step-engine run read -1 (RunStartMarkerMonitor: 100%).
            this.audit(JobAuditMarker.JOB_STARTED.logDetail());
            Outcome outcome;
            try {
                outcome = this.steps(rows, resumed);
            } finally {
                // MIG-344: whatever a try spilled and did not remove (one that timed out) goes with the run.
                datasets.removeScratch(this.run.getJobQueueId(), this.attempt);
            }
            this.finish(outcome);
        }

        /** The run's end as its steps came to it: nothing for an interrupted or paused run, else Failed or Completed. */
        private void finish(Outcome outcome) {
            if (outcome.interrupted) {
                logger.info("Run {} was moved on while its steps ran; its remaining steps are Interrupt.", this.run.getJobQueueId());
                return;
            }
            if (outcome.paused) {
                logger.info("Run {} waits for an approval; it continues when its reviewers decide.", this.run.getJobQueueId());
                return;
            }
            if (outcome.failure != null) {
                report(this.run, JobStatus.Failed, outcome.failure);
            } else {
                report(this.run, JobStatus.Completed, outcome.summary(this.stepList.size()));
            }
        }

        /**
         * MIG-345: what a resume reuses -- the earlier attempt's steps before the one it starts at, each Completed under the
         * same key, each output still kept. Throws, saying why, when it cannot.
         */
        private Resumed resumable() {
            RunAttemptStore.Attempt asked = this.plan.asked;
            int from = asked.fromStepIndex == null ? 0 : asked.fromStepIndex;
            if (from >= this.stepList.size()) {
                throw new IllegalStateException(String.format("definition version %d has %d step(s), none at step %d",
                    this.plan.stored.version, this.stepList.size(), from + 1));
            }
            Map<Integer, StepStore.StepRow> earlier = new HashMap<>();
            Map<Long, StepStore.StepRow> byId = new HashMap<>();
            for (StepStore.StepRow row : steps.stepsOfRun(this.run.getJobQueueId())) {
                byId.put(row.stepExecutionId, row);
                if (row.attempt == asked.previousAttempt) {
                    earlier.put(row.stepIndex, row);
                }
            }
            // Each reused step as the step that really ran it: an earlier resume's reused step names the one it reused.
            List<StepStore.StepRow> reused = new ArrayList<>();
            for (int i = 0; i < from; i++) {
                StepStore.StepRow row = earlier.get(i);
                String key = this.stepList.get(i).getKey();
                if (row == null || !key.equals(row.stepKey) || !JobStatus.Completed.name().equals(row.status)) {
                    throw new IllegalStateException(String.format("step %d <%s> did not complete in attempt %d", i + 1, key,
                        asked.previousAttempt));
                }
                reused.add(RunRetries.origin(row, byId));
            }
            Map<Long, StepStore.KeptOutput> kept = steps.keptOutputsOf(reused.stream().map(row -> row.stepExecutionId)
                .collect(Collectors.toList()));
            Instant now = Instant.now();
            for (StepStore.StepRow row : reused) {
                StepStore.KeptOutput output = kept.get(row.stepExecutionId);
                if (output != null && output.expiredAt(now)) {
                    throw new IllegalStateException(String.format("the kept output of step <%s> expired%s", row.stepKey,
                        output.expiresAt == null ? "" : " at " + output.expiresAt));
                }
            }
            return new Resumed(from, reused, kept);
        }

        /**
         * MIG-345: the reused steps, recorded as such (Completed, reused_from, no times, nothing run), and the references
         * their outputs leave -- the same each would have passed on had it run: its kept output, else the input it read.
         * Nothing is read: a later step reads a reused output a batch at a time, as it reads any.
         */
        private String reuse(List<Long> rows, Resumed resumed) {
            String latest = null;
            int total = this.stepList.size();
            for (int i = 0; i < resumed.from; i++) {
                PipelineDefinition.Step step = this.stepList.get(i);
                StepStore.StepRow earlier = resumed.reused.get(i);
                StepStore.KeptOutput output = resumed.kept.get(earlier.stepExecutionId);
                String passed;
                if (output != null) {
                    passed = output.storageKey;
                    if (output.rows != null) {
                        this.sizes.put(passed, output.rows);
                    }
                } else {
                    passed = step.getInput() != null ? this.outputs.get(step.getInput()) : latest;
                }
                this.outputs.put(step.getKey(), passed);
                latest = passed;
                String line = String.format("Reused from attempt %d: not run again (%s in, %s out).", earlier.attempt,
                    earlier.recordsIn == null ? "no records" : earlier.recordsIn.toString(),
                    earlier.recordsOut == null ? "no records" : earlier.recordsOut.toString());
                steps.reused(rows.get(i), earlier.stepExecutionId, earlier.recordsIn, earlier.recordsOut, line);
                new StepLog(rows.get(i)).line("INFO", line);
                this.audit(String.format("Step %d/%d <%s> (%s) reused from attempt %d: not run again.", i + 1, total, step.getKey(),
                    step.getTask(), earlier.attempt));
            }
            return latest;
        }

        private Outcome steps(List<Long> rows, Resumed resumed) {
            Outcome outcome = new Outcome();
            Dataset source = this.source();
            this.sourceRows = source;
            String latest = null;
            int first = 0;
            if (resumed != null) {
                latest = this.reuse(rows, resumed);
                first = resumed.from;
                outcome.reused = resumed.from;
                outcome.reusedFrom = resumed.attempts();
            }
            return this.steps(rows, first, latest, outcome);
        }

        /** The steps from {@code first} on, reading {@code latest} where a step names no input (MIG-346: a run continued). */
        private Outcome steps(List<Long> rows, int first, String latest, Outcome outcome) {
            Dataset source = this.sourceRows;
            // What a step reads is a reference: the dataset key of the output it reads (run_dataset.storage_key), or
            // null for the source. A step that makes no dataset passes on the reference it read.
            Map<String, String> outputs = this.outputs;
            int total = this.stepList.size();
            for (int i = first; i < total; i++) {
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
                long recordsIn = this.recordsIn(step, input, outputs, source);
                steps.started(row, recordsIn);
                this.audit(String.format("%s started on %d record(s).", label, recordsIn));
                long began = System.nanoTime();
                this.memory.resetPeak();
                Tried tried = this.tryStep(step, row, input, log);
                long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began);
                if (tried.closed) {
                    steps.ended(row, JobStatus.Interrupt.name(), null, null, "Stopped: the run was closed while this step ran.");
                    this.stopAll(rows, i + 1, JobStatus.Interrupt.name(), "Not run: the run was closed while its steps ran.");
                    this.audit(String.format("%s stopped: the run was closed while it ran.", label));
                    outcome.interrupted = true;
                    return outcome;
                }
                // MIG-344: what a streaming step held, on its log -- for a step that read a batch or more, or spilled (a small
                // step's log stays as it was).
                if (tried.streamed && (input.size() >= batchRows() || this.memory.spilled() > 0)) {
                    log.line("INFO", String.format("Streamed in batches of %,d row(s): at most about %s held at once of the run's %s "
                        + "memory budget%s.", batchRows(), RunMemory.megabytes(this.memory.peak()), RunMemory.megabytes(this.memory.budget()),
                        this.memory.spilled() > 0 ? String.format("; %s spilled to disk", RunMemory.megabytes(this.memory.spilled())) : ""));
                }
                if (tried.result != null && tried.result.isAwaiting()) {
                    // MIG-346: an Approval step -- the run waits here; its reviewers' decision continues or fails it.
                    String refused = this.awaitApproval(i, step, row, input, tried.result.getApproval(), log, label);
                    if (refused == null) {
                        outcome.paused = true;
                        return outcome;
                    }
                    tried.result = null;
                    tried.error = refused;
                }
                if (tried.result != null) {
                    Dataset output = tried.result.getOutput();
                    Long out = tried.result.getRecordsOut();
                    String passed = tried.outputKey != null ? this.recordStreamed(row, tried)
                        : output != null ? this.keep(row, step, output) : inputKey;
                    outputs.put(step.getKey(), passed);
                    latest = passed;
                    this.recordReads(row, step, tried.reads);
                    String done = String.format("Completed in %.1f s: %d in, %s out%s.", millis / 1000.0, recordsIn,
                        out == null ? "no records" : out.toString(), tried.tries > 1 ? String.format(", on try %d", tried.tries) : "");
                    steps.ended(row, JobStatus.Completed.name(), out, null, done);
                    this.audit(String.format("%s completed: %d in, %s out, %.1f s.", label, recordsIn, out == null ? "0" : out.toString(),
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
         * MIG-346: an Approval step's request -- recorded (run_approval) with its Task inbox event in one transaction, the step
         * left Running and saying it waits, the run's status line saying so. Returns null when the run now waits, or why the
         * request could not be made (the step then fails as any step).
         */
        private String awaitApproval(int index, PipelineDefinition.Step step, long row, Input input, StepResult.ApprovalAsk ask, StepLog log,
                                     String label) {
            if (approvals == null || approvalEvents == null) {
                return "approvals are not available in this Core (no run_approval store)";
            }
            PipelineDefinition.Reviewers reviewers = ask.reviewersKind != null && ask.reviewersValue != null
                ? PipelineDefinition.Reviewers.of(ask.reviewersKind, ask.reviewersValue)
                : this.settings.getReview() != null ? this.settings.getReview().effectiveReviewers()
                : PipelineDefinition.Reviewers.of(PipelineDefinition.Reviewers.ROLE, PipelineDefinition.Reviewers.ADMINS);
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            RunApprovalStore.Approval approval = new RunApprovalStore.Approval();
            approval.jobQueueId = this.run.getJobQueueId();
            approval.attempt = this.attempt;
            approval.stepExecutionId = row;
            approval.stepIndex = index;
            approval.stepKey = step.getKey();
            approval.title = ask.title;
            approval.reviewersKind = reviewers.getKind();
            approval.reviewersValue = reviewers.getValue();
            approval.recordsIn = input.size();
            approval.requestedAt = now;
            approval.expiresAt = now.plus(Duration.ofHours(Math.max(1, ask.expiresAfterHours)));
            long id;
            try {
                id = local.execute(status -> {
                    long made = approvals.insert(approval);
                    RunApprovalEvents.Requested event = new RunApprovalEvents.Requested();
                    event.approvalId = made;
                    event.jobQueueId = approval.jobQueueId;
                    event.jobId = this.plan.job.getJobId();
                    event.jobName = this.plan.job.getJobName();
                    event.pipelineId = this.plan.job.getTaskDetail() == null ? null : this.plan.job.getTaskDetail().getPipelineId();
                    event.attempt = this.attempt;
                    event.stepKey = step.getKey();
                    event.stepIndex = index;
                    event.title = ask.title;
                    event.instructions = ask.instructions;
                    event.fields = ask.fields;
                    event.rowCount = approval.recordsIn;
                    event.requestedAt = approval.requestedAt;
                    event.expiresAt = approval.expiresAt;
                    event.reviewersKind = approval.reviewersKind;
                    event.reviewersValue = approval.reviewersValue;
                    approvalEvents.requested(this.tenantId, event);
                    return made;
                });
            } catch (RuntimeException failed) {
                return "the approval could not be requested: " + reasonOf(failed);
            }
            String who = PipelineDefinition.Reviewers.ROLE.equals(reviewers.getKind())
                ? (PipelineDefinition.Reviewers.ADMINS.equals(reviewers.getValue()) ? "the workspace's administrators" : "the role " + reviewers.getValue())
                : "the group " + reviewers.getValue();
            String waiting = String.format("Waiting for approval: %s", ask.title);
            steps.waiting(row, waiting);
            log.line("INFO", String.format("Waiting for approval by %s (request %d, in their Task inbox) until %s; approved, the run "
                + "continues with the next step; rejected or not decided by then, it fails.", who, id, approval.expiresAt));
            this.audit(String.format("%s waits for approval by %s (request %d): %s", label, who, id, ask.title));
            report(this.run, JobStatus.Running, String.format("Waiting for approval at step %d <%s>: %s", index + 1, step.getKey(), ask.title));
            return null;
        }

        /**
         * MIG-346: the run's reviewers approved -- the Approval step completes and the steps after it run, on what it read.
         * The outputs of the steps before it are the ones this attempt kept (nothing is read to find them).
         */
        void continueAfter(RunApprovalStore.Approval approval) {
            Waited waited = this.waited(approval);
            if (waited == null) {
                return;
            }
            String line = sentence(String.format("Approved by %s%s", approval.decider(), approval.comment == null ? "" : ": " + approval.comment));
            if (!steps.settleWaiting(waited.row, JobStatus.Completed.name(), approval.recordsIn, null, line)) {
                logger.info("Run {}: approval request {} was already settled by another hand-off; nothing to do.", this.run.getJobQueueId(),
                    approval.runApprovalId);
                return;
            }
            new StepLog(waited.row).line("INFO", line);
            this.audit(String.format("Step %d/%d <%s> (approval) completed: %s", approval.stepIndex + 1, this.stepList.size(),
                approval.stepKey, line));
            Outcome outcome = new Outcome();
            outcome.completed = waited.completedBefore + 1;
            outcome.failedAndContinued = waited.failedBefore;
            // A resumed attempt's reused steps, said as a resume says them.
            outcome.reused = waited.reused.size();
            TreeSet<Integer> from = new TreeSet<>(waited.reused);
            outcome.reusedFrom = (from.size() == 1 ? "attempt " : "attempts ") + from.stream().map(String::valueOf)
                .collect(Collectors.joining(", "));
            int next = approval.stepIndex + 1;
            ResponseDto running = report(this.run, JobStatus.Running, next < this.stepList.size()
                ? String.format("Approved by %s at step %d <%s>: running %s.", approval.decider(), approval.stepIndex + 1, approval.stepKey,
                    this.stepList.subList(next, this.stepList.size()).stream().map(PipelineDefinition.Step::getKey).collect(Collectors.joining(", ")))
                : String.format("Approved by %s at step %d <%s>, the last step.", approval.decider(), approval.stepIndex + 1, approval.stepKey));
            if (refused(running)) {
                this.stopAll(waited.rows, next, "Interrupt", "Not run: the run was closed before its steps continued.");
                return;
            }
            Outcome done;
            try {
                done = this.steps(waited.rows, next, waited.latest, outcome);
            } finally {
                datasets.removeScratch(this.run.getJobQueueId(), this.attempt);
            }
            this.finish(done);
        }

        /** MIG-346: rejected (or expired) -- the Approval step fails with the reason, the steps after it are skipped, the run fails. */
        void stopAfter(RunApprovalStore.Approval approval, String why) {
            Waited waited = this.waited(approval);
            if (waited == null) {
                return;
            }
            if (!steps.settleWaiting(waited.row, JobStatus.Failed.name(), null, errorJson(why, 1, false), "Failed: " + why)) {
                logger.info("Run {}: approval request {} was already settled by another hand-off; nothing to do.", this.run.getJobQueueId(),
                    approval.runApprovalId);
                return;
            }
            new StepLog(waited.row).line("ERROR", why);
            this.stopAll(waited.rows, approval.stepIndex + 1, JobStatus.Skip.name(),
                String.format("Skipped: step <%s> was not approved.", approval.stepKey));
            this.audit(String.format("Step %d/%d <%s> (approval) failed: %s", approval.stepIndex + 1, this.stepList.size(), approval.stepKey,
                why));
            report(this.run, JobStatus.Failed, String.format("Step %d <%s> failed: %s", approval.stepIndex + 1, approval.stepKey, why));
        }

        /**
         * The run as it waits at {@code approval}: this attempt's step rows, and what the steps before it passed on. Null, with
         * nothing changed, when the run is not waiting there any more (it was closed, or is on another attempt).
         */
        private Waited waited(RunApprovalStore.Approval approval) {
            if (approval.attempt != this.attempt || !this.stillRunning()) {
                logger.info("Run {}: approval request {} was decided, but the run no longer waits on it (attempt {}, now {}); nothing to do.",
                    this.run.getJobQueueId(), approval.runApprovalId, approval.attempt, this.attempt);
                return null;
            }
            Map<Long, StepStore.StepRow> byId = new HashMap<>();
            Map<Integer, StepStore.StepRow> mine = new HashMap<>();
            for (StepStore.StepRow row : steps.stepsOfRun(this.run.getJobQueueId())) {
                byId.put(row.stepExecutionId, row);
                if (row.attempt == this.attempt) {
                    mine.put(row.stepIndex, row);
                }
            }
            List<Long> rows = new ArrayList<>();
            for (int i = 0; i < this.stepList.size(); i++) {
                StepStore.StepRow row = mine.get(i);
                if (row == null || !this.stepList.get(i).getKey().equals(row.stepKey)) {
                    report(this.run, JobStatus.Failed, String.format("Step %d <%s> was approved, but the run's steps no longer match its "
                        + "definition; it cannot continue.", approval.stepIndex + 1, approval.stepKey));
                    return null;
                }
                rows.add(row.stepExecutionId);
            }
            StepStore.StepRow waiting = mine.get(approval.stepIndex);
            if (waiting.stepExecutionId != approval.stepExecutionId || !JobStatus.Running.name().equals(waiting.status)) {
                logger.info("Run {}: step <{}> is {} and no longer waits on approval request {}.", this.run.getJobQueueId(), approval.stepKey,
                    waiting.status, approval.runApprovalId);
                return null;
            }
            this.sourceRows = this.source();
            Waited waited = new Waited();
            waited.rows = rows;
            waited.row = waiting.stepExecutionId;
            // The steps before it, as the reuse of a resume rebuilds them: a kept output, else the input a step read.
            List<StepStore.StepRow> before = new ArrayList<>();
            for (int i = 0; i < approval.stepIndex; i++) {
                before.add(mine.get(i));
            }
            Map<Long, StepStore.StepRow> origins = new HashMap<>();
            for (StepStore.StepRow row : before) {
                origins.put(row.stepExecutionId, row.reusedFrom != null ? RunRetries.origin(row, byId) : row);
            }
            Map<Long, StepStore.KeptOutput> kept = steps.keptOutputsOf(origins.values().stream().map(r -> r.stepExecutionId)
                .collect(Collectors.toList()));
            String latest = null;
            for (int i = 0; i < approval.stepIndex; i++) {
                PipelineDefinition.Step step = this.stepList.get(i);
                StepStore.StepRow row = mine.get(i);
                if (JobStatus.Completed.name().equals(row.status)) {
                    StepStore.KeptOutput output = kept.get(origins.get(row.stepExecutionId).stepExecutionId);
                    String passed;
                    if (output != null) {
                        passed = output.storageKey;
                        if (output.rows != null) {
                            this.sizes.put(passed, output.rows);
                        }
                    } else {
                        passed = step.getInput() != null ? this.outputs.get(step.getInput()) : latest;
                    }
                    this.outputs.put(step.getKey(), passed);
                    latest = passed;
                    if (row.reusedFrom != null) {
                        waited.reused.add(origins.get(row.stepExecutionId).attempt);
                    } else {
                        waited.completedBefore++;
                    }
                } else {
                    waited.failedBefore++;
                }
            }
            PipelineDefinition.Step gate = this.stepList.get(approval.stepIndex);
            String read = gate.getInput() != null ? this.outputs.get(gate.getInput()) : latest;
            this.outputs.put(gate.getKey(), read);
            waited.latest = read;
            return waited;
        }

        /** MIG-345: what the step read of each bucket object, fingerprinted, for a later resume's input check. */
        private void recordReads(long row, PipelineDefinition.Step step, List<InputDigests.Read> reads) {
            if (reads == null) {
                return;
            }
            for (InputDigests.Read read : reads) {
                try {
                    steps.inputRead(row, read);
                } catch (RuntimeException unrecorded) {
                    // A resume then cannot vouch for this file and says so; the run itself is not failed for it.
                    logger.warn("Run {} step <{}>: the fingerprint of {} could not be recorded: {}", this.run.getJobQueueId(),
                        step.getKey(), read, reasonOf(unrecorded));
                }
            }
        }

        /**
         * The dataset behind a reference, whole: the source for none, else what the dataset store holds under the key --
         * held to {@link Limits} (MIG-344: a streamed dataset may be bigger than a step can hold).
         */
        private Dataset load(String key, Dataset source, String what) {
            if (key == null) {
                return source;
            }
            try (RowSource rows = datasets.open(key, batchRows())) {
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
                return this.key == null ? RowSource.of(this.source, batchRows()) : datasets.open(this.key, batchRows());
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
        /**
         * ZT-P2: the rows a step takes in, as its run page shows them. A read step that declares no input ignores the step before
         * it (TaskKind.READ; Read API declares the first row it reads), so it takes in nothing -- it showed the previous step's rows
         * (or the job's payload row), as if a file read had read them. An append takes in the outputs of the steps it joins, not the step before it.
         */
        private long recordsIn(PipelineDefinition.Step step, Input input, Map<String, String> outputs, Dataset source) {
            Optional<StepTask> task = tasks.find(step.getTask());
            if (task.isPresent() && task.get().spec().kind() == TaskKind.READ && task.get().spec().inputSchema() == null) {
                return 0;
            }
            if ("append".equals(step.getTask()) && step.getConfig() != null && step.getConfig().get("steps") instanceof List) {
                long total = 0;
                for (Object key : (List<?>) step.getConfig().get("steps")) {
                    if (key != null && outputs.containsKey(String.valueOf(key))) {
                        total += new Input(outputs.get(String.valueOf(key)), source).size();
                    }
                }
                return total;
            }
            return input.size();
        }

        private Tried tryStep(PipelineDefinition.Step step, long row, Input input, StepLog log) {
            Optional<StepTask> task = tasks.find(step.getTask());
            // The step's own retry and timeout, else its task's registry defaults (MIG-231).
            int maxTries = task.map(found -> step.effectiveMaxAttempts(found.spec().maxAttempts())).orElse(step.effectiveMaxAttempts());
            int delay = task.map(found -> step.effectiveDelaySeconds(found.spec().delaySeconds())).orElse(step.effectiveDelaySeconds());
            int timeout = step.effectiveTimeoutSeconds(this.settings, task.map(found -> found.spec().timeoutSeconds()).orElse(null));
            // Owner 2026-10-21: a step whose author set no retry, safe to repeat, is tried again when it fails with an error
            // that says to (PassingErrors: a service or storage away for a moment), after 5, 15 and 45 seconds.
            boolean authorSetRetry = step.getRetry() != null && step.getRetry().getMaxAttempts() != null;
            boolean passingRetries = !authorSetRetry && task.map(found -> found.spec().repeatSafe()).orElse(false);
            int planned = maxTries;
            Tried tried = new Tried();
            for (int t = 1; t <= maxTries; t++) {
                tried.passing = false;
                tried.tries = t;
                steps.tried(row, t);
                if (maxTries > 1) {
                    log.line("INFO", String.format("Try %d of %d.", t, maxTries));
                }
                boolean streams = task.isPresent() && task.get() instanceof StreamingStepTask;
                Context context = streams ? new StreamTry(this, step, row, input, t, log) : new Context(this, step, row, input, t, log);
                context.sqlFields = task.map(found -> RunParameters.sqlFields(found.spec().configSchema())).orElse(Collections.<String>emptySet());
                tried.streamed = streams;
                // MIG-345: the bucket objects this try reads, fingerprinted on the way.
                InputDigests.Recorder recorder = new InputDigests.Recorder();
                Future<StepResult> future;
                try {
                    future = tryThreads.submit(() -> RowSecurity.forTenant(this.tenantId, () -> {
                        try {
                            return InputDigests.recording(recorder, () -> streams ? ((StreamingStepTask) task.get()).stream((StreamTry) context)
                                : task.get().run(context));
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
                        boolean[] closed = {false};
                        tried.result = this.await(future, timeout, closed);
                        if (closed[0]) {
                            // ZT-P7: the run was marked interrupted (or failed) while this step ran: it stops now, not after
                            // every row it had left (an AI step went on calling the model, and was billed, for minutes).
                            future.cancel(true);
                            if (streams) {
                                ((StreamTry) context).discard();
                            }
                            tried.closed = true;
                            tried.error = "the run was closed while this step ran";
                            log.line("WARN", String.format("Try %d stopped: the run was closed while this step ran.", t));
                            return tried;
                        }
                        String uncommitted = streams ? this.commitStreamed((StreamTry) context, tried) : null;
                        tried.timedOut = false;
                        if (uncommitted == null) {
                            tried.error = null;
                            tried.reads = recorder.reads();
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
                        tried.passing = PassingErrors.is(cause == null ? ex : cause);
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
                int wait = delay;
                if (t == maxTries && passingRetries && tried.passing && maxTries - planned < PassingErrors.WAITS_SECONDS.length) {
                    wait = PassingErrors.WAITS_SECONDS[maxTries - planned];
                    maxTries++;
                    log.line("WARN", String.format("Try %d failed with an error that says to try again: %s. Trying again in %d s.", t,
                        tried.error, wait));
                    this.audit(String.format("Step <%s>: try %d failed with an error that says to try again; trying again in %d s.",
                        step.getKey(), t, wait));
                } else {
                    log.line(t < maxTries ? "WARN" : "ERROR", String.format("Try %d of %d failed: %s", t, maxTries, tried.error));
                }
                if (t < maxTries && wait > 0) {
                    try {
                        sleeper.sleep(Duration.ofSeconds(wait));
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        tried.error = "the step engine was stopped";
                        return tried;
                    }
                }
            }
            return tried;
        }

        /**
         * A try's answer, within its timeout; looks every closeCheckMillis whether the run is still running (ZT-P7), and when it
         * is not, answers null with {@code closed[0]} set.
         */
        private StepResult await(Future<StepResult> future, int timeout, boolean[] closed)
            throws InterruptedException, ExecutionException, TimeoutException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new TimeoutException();
                }
                try {
                    return future.get(Math.min(left, TimeUnit.MILLISECONDS.toNanos(closeCheckMillis)), TimeUnit.NANOSECONDS);
                } catch (TimeoutException slice) {
                    if (System.nanoTime() < deadline && !this.stillRunning()) {
                        closed[0] = true;
                        return null;
                    }
                }
            }
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

        /** MIG-346: an earlier step's output a batch at a time (Append), never held whole. */
        private RowSource openOutput(String stepKey) throws Exception {
            if (!this.outputs.containsKey(stepKey)) {
                throw new IllegalStateException(String.format("step <%s> has no output to read: it is not an earlier step, or it failed",
                    stepKey));
            }
            String key = this.outputs.get(stepKey);
            return key == null ? RowSource.of(this.sourceRows, batchRows()) : datasets.open(key, batchRows());
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
        /** MIG-345: the steps a resume reused, and the attempt whose they are. */
        int reused;
        String reusedFrom;
        /** MIG-346: the run waits at an Approval step: nothing more is reported until its reviewers decide. */
        boolean paused;

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
            StringBuilder line = new StringBuilder(String.format("%d of %d step(s) completed", this.completed + this.reused, total));
            if (this.reused > 0) {
                line.append(String.format(" (%d reused from %s)", this.reused, this.reusedFrom));
            }
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
        /** The last try failed with an error that says to try again (PassingErrors). */
        boolean passing;
        /** ZT-P7: stopped because the run was closed meanwhile. */
        boolean closed;
        int tries;
        StepResult result;
        String error;
        boolean timedOut;
        /** MIG-344: the step streamed; its committed output's key, rows and columns (null key: no dataset). */
        boolean streamed;
        String outputKey;
        long outputRows;
        List<String> outputColumns;
        /** MIG-345: the bucket objects the successful try read, fingerprinted. */
        List<InputDigests.Read> reads;
    }

    /** MIG-346: a run waiting at an Approval step: its step rows, the waiting one, and what the steps before it passed on. */
    private static final class Waited {
        List<Long> rows;
        long row;
        String latest;
        int completedBefore;
        int failedBefore;
        /** The attempt each reused step before it really ran in. */
        final List<Integer> reused = new ArrayList<>();
    }

    /** MIG-345: what a resume reuses: the steps before {@code from} as the attempts that ran them, and their kept outputs. */
    private static final class Resumed {
        final int from;
        final List<StepStore.StepRow> reused;
        final Map<Long, StepStore.KeptOutput> kept;

        Resumed(int from, List<StepStore.StepRow> reused, Map<Long, StepStore.KeptOutput> kept) {
            this.from = from;
            this.reused = reused;
            this.kept = kept;
        }

        /** "attempt 1", or "attempts 1, 2" when the reused steps ran in more than one. */
        String attempts() {
            TreeSet<Integer> ran = new TreeSet<>();
            this.reused.forEach(row -> ran.add(row.attempt));
            return (ran.size() == 1 ? "attempt " : "attempts ") + ran.stream().map(String::valueOf).collect(Collectors.joining(", "));
        }
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
        /** MIG-347: the task's SQL settings, whose filled-in text values have their quotes doubled. */
        Set<String> sqlFields = Collections.emptySet();
        private Map<String, Object> filled;

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

        /** MIG-347: the step's settings with {{param.*}} and {{run.*}} filled in (the step fills its own placeholders). */
        @Override
        public Map<String, Object> configAsWritten() {
            return this.step.effectiveConfig();
        }

        @Override
        public Map<String, Object> config() {
            Map<String, Object> config = this.filled;
            if (config == null) {
                config = RunParameters.fill(this.step.effectiveConfig(), this.execution.placeholders(), this.sqlFields);
                this.filled = config;
            }
            return config;
        }

        @Override
        public Map<String, Object> runValues() {
            return this.execution.placeholders();
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
        public String pipelineName() {
            return this.execution.pipelineName();
        }

        @Override
        public String modelProfile() {
            return this.asked().profile;
        }

        @Override
        public String modelProfileSource() {
            return this.asked().source;
        }

        /** The run's "Run with...", else its schedule's setting, for this step (ModelProfiles reads both defensively). */
        private ModelProfiles.Asked asked() {
            return ModelProfiles.of(this.execution.plan.job.getModelProfiles(), this.execution.run.getModelProfiles()).forStep(this.step.getKey());
        }

        @Override
        public Long sourceTaskId() {
            return this.execution.plan.job.getTaskDetail() == null ? null : this.execution.plan.job.getTaskDetail().getTaskDetailId();
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
        public List<String> inputKeys() {
            return this.execution.inputKeys();
        }

        @Override
        public List<RunDocuments.Document> documents() {
            return this.execution.documents();
        }

        @Override
        public Map<String, Object> documentValues() throws Exception {
            return this.execution.documentValues();
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
        public RowSource openOutputOf(String stepKey) throws Exception {
            return this.sources.track(new Batched(this.execution.openOutput(stepKey), this.execution.memory));
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

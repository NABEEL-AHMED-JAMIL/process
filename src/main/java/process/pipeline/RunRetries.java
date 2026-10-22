package process.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import process.engine.BulkAction;
import process.engine.OneRunInFlight;
import process.engine.ProducerBulkEngine;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.pipeline.backing.BucketStore;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.sql.SQLException;

/**
 * MIG-345: a failed run tried again as a new attempt of the same run -- from the step that failed (resume), reusing the
 * kept outputs of the steps before it, or from the start (restart). The console (a person) and the customer API (a
 * client) ask through here; the step engine runs the attempt ({@link StepEngine}, the reused steps recorded and not run).
 *
 * <b>The rules (decided here, ADR-026 "Retry from the failed step").</b>
 * <ul>
 *   <li><b>Only a failed run</b>, of an active pipeline, in a workspace that is not paused, with no other run of the
 *   pipeline in flight (one runs at a time), and fewer than {@link #MAX_ATTEMPTS} attempts.</li>
 *   <li><b>From the failed step</b>: the first step of the latest attempt that did not complete. Every step before it
 *   completed and is reused: its output is the earlier attempt's, read again a batch at a time by the steps that need it,
 *   never loaded whole for the resume, and never re-billed (it does not run, so nothing it would meter happens).</li>
 *   <li><b>The pipeline may have changed</b> in the failed step and the steps after it -- that is how a run is fixed --
 *   and the resume runs the pipeline's latest version. It may not have changed before the failed step: the source and
 *   each reused step's key, task, input and config must be what the run ran (its name, retry, timeout and on-error may
 *   change: they do not change what a completed step made). Otherwise only "Run again from the start" is offered.</li>
 *   <li><b>Its input files must be the same</b>: each bucket object a reused step read is read again and its sha256
 *   compared with the one taken when the step read it (run_input_file) -- over the bytes the step took, and to the end
 *   when it read to the end. A changed or missing file refuses the resume. A reused step that reads files but has no
 *   fingerprint (it ran before MIG-345), or read more than InputDigests.MAX_FILES files, cannot be vouched for and refuses
 *   it too. The check's read is a pipeline read like any (storage-service bills it); only files are checked -- a reused
 *   API or database read is the data it returned then, which is what a resume means.</li>
 *   <li><b>Its kept outputs must not have expired</b>: a reused step's output past its expiry (the definition's
 *   datasetRetentionHours, 24 h by default, cut by the workspace's data policy) or removed by the sweep refuses it. A
 *   retry does not extend an output's life. The sweep does not remove a dataset of a run in flight.</li>
 *   <li><b>From the start</b> skips every check but the first: the run's steps all run again on the pipeline's latest
 *   version. In the console it is a workspace administrator's; an API client with runs:write may ask for it.</li>
 * </ul>
 * One run is one run on the bill (pipeline.runs is keyed by the run, as the job's own retries are); the work its re-run
 * steps do is metered where it happens, as on any attempt.
 */
@Service
public class RunRetries {

    private static final Logger logger = LoggerFactory.getLogger(RunRetries.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The most attempts one run has; past it, start a new run. */
    public static final int MAX_ATTEMPTS = 20;

    /** Why a retry is refused: the customer API's problem kinds (/problems/&lt;kind&gt;), the console's words beside them. */
    public static final String NOT_FAILED = "run-not-failed";
    public static final String IN_FLIGHT = "run-in-flight";
    public static final String INACTIVE = "pipeline-inactive";
    public static final String PAUSED = "workspace-paused";
    public static final String CHANGED = "pipeline-changed";
    public static final String INPUT_CHANGED = "input-changed";
    public static final String EXPIRED = "outputs-expired";
    public static final String NOT_RESUMABLE = "not-resumable";

    /** Who asks: a person in the console, or an API client. */
    public static final class Actor {
        final String via;
        final Long userId;
        final String clientId;
        final boolean admin;
        /** MIG-355: the bulk retry this one is part of; the audit line ends " Bulk retry <bulkId>." */
        final String bulkId;

        private Actor(String via, Long userId, String clientId, boolean admin, String bulkId) {
            this.via = via;
            this.userId = userId;
            this.clientId = clientId;
            this.admin = admin;
            this.bulkId = bulkId;
        }

        public static Actor person(Long userId, boolean admin) {
            return new Actor(RunAttemptStore.VIA_CONSOLE, userId, null, admin, null);
        }

        public static Actor client(String clientId) {
            return new Actor(RunAttemptStore.VIA_API, null, clientId, true, null);
        }

        /** Owner 2026-10-21: Core resuming, once, a run its restart stopped (the run is still in flight, its engine gone). */
        public static Actor platform() {
            return new Actor(RunAttemptStore.VIA_PLATFORM, null, null, false, null);
        }

        boolean afterRestart() {
            return RunAttemptStore.VIA_PLATFORM.equals(this.via);
        }

        /** The same person, retrying as part of a bulk retry. */
        public Actor inBulk(String bulkId) {
            return new Actor(this.via, this.userId, this.clientId, this.admin, bulkId);
        }
    }

    /** A reused step, as the console shows it before the retry. */
    public static final class Reused {
        public int index;
        public String key;
        public String name;
        public String task;
        public Long rowsOut;
        /** When its kept output expires; null when it wrote none (it passed its input on). */
        public Instant expiresAt;
        public int inputFiles;
    }

    /** What a retry would do, or why it cannot. */
    public static final class Plan {
        public long jobQueueId;
        public long jobId;
        public int attempt;
        public int nextAttempt;
        /** Null when it can resume; else the problem kind and the reason in words. */
        public String refusalKind;
        public String refusal;
        public Integer fromIndex;
        public String fromKey;
        public String fromName;
        public List<Reused> reused = new ArrayList<>();
        /** The version it would run, and whether it is newer than the one the run ran (its changes the failed step's or later). */
        public Long definitionId;
        public Integer definitionVersion;
        public Integer ranVersion;
        /** Null when a restart may be asked for; else why not. */
        public String restartRefusalKind;
        public String restartRefusal;
        /** UX review 2026-10-13: the backfill this run is part of (its own Retry failed retries it), else null. */
        public Long backfillId;
        /** The input files the resume will check, and whether they were checked for this plan. */
        public int inputFiles;
        public boolean inputsChecked;
        /** Internal: the latest definition, and the rows reused. */
        Long latestDefinitionId;
        List<StepStore.StepRow> reusedRows = new ArrayList<>();

        public boolean resumable() {
            return this.refusal == null;
        }

        public boolean restartable() {
            return this.restartRefusal == null;
        }

        Plan refuse(String kind, String reason) {
            if (this.refusal == null) {
                this.refusalKind = kind;
                this.refusal = reason;
            }
            return this;
        }
    }

    /** A retry asked for: the new attempt, or the refusal. */
    public static final class Outcome {
        public final Plan plan;
        public final String mode;
        public final String refusalKind;
        public final String refusal;

        Outcome(Plan plan, String mode, String refusalKind, String refusal) {
            this.plan = plan;
            this.mode = mode;
            this.refusalKind = refusalKind;
            this.refusal = refusal;
        }

        public boolean queued() {
            return this.refusal == null;
        }
    }

    private final StepStore steps;
    private final RunAttemptStore attempts;
    private final PipelineDefinitionStore definitions;
    private final DefinitionValidator validator;
    private final StepTasks tasks;
    private final BulkAction bulk;
    private final JdbcTemplate jdbc;
    private final TransactionOperations transaction;
    private BucketStore buckets;
    private ProducerBulkEngine engine;

    @Autowired
    public RunRetries(StepStore steps, RunAttemptStore attempts, PipelineDefinitionStore definitions, DefinitionValidator validator,
                      StepTasks tasks, BulkAction bulk, JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this(steps, attempts, definitions, validator, tasks, bulk, jdbc, new TransactionTemplate(transactions));
    }

    /** For tests: the transaction the retry's writes share. */
    public RunRetries(StepStore steps, RunAttemptStore attempts, PipelineDefinitionStore definitions, DefinitionValidator validator,
                      StepTasks tasks, BulkAction bulk, JdbcTemplate jdbc, TransactionOperations transaction) {
        this.steps = steps;
        this.attempts = attempts;
        this.definitions = definitions;
        this.validator = validator;
        this.tasks = tasks;
        this.bulk = bulk;
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    /** The buckets a reused step read, for the input check; without them, a step that read a file cannot be vouched for. */
    @Autowired(required = false)
    public void useBuckets(BucketStore buckets) {
        this.buckets = buckets;
    }

    /** The workspace's pause (suspended, inactive), as Run now reads it. */
    @Autowired(required = false)
    public void useEngine(ProducerBulkEngine engine) {
        this.engine = engine;
    }

    /**
     * What a retry of this run would do. {@code checkInputs}: read the reused steps' files again and compare their sha256
     * (the retry itself always does; the console's preview does not, and says the check is still to come).
     */
    public Plan plan(JobQueue run, SourceJob job, boolean checkInputs) {
        return this.plan(run, job, checkInputs, false);
    }

    /**
     * {@code afterRestart}: the platform resuming a run whose step engine stopped with it in flight (owner 2026-10-21) -- the
     * run is resumed as it stands, not first failed, so a workflow waiting on it never sees it end.
     */
    private Plan plan(JobQueue run, SourceJob job, boolean checkInputs, boolean afterRestart) {
        Plan plan = new Plan();
        plan.jobQueueId = run.getJobQueueId();
        plan.jobId = job.getJobId();
        plan.attempt = Math.max(1, run.getAttempt());
        plan.nextAttempt = plan.attempt + 1;
        // What refuses both.
        String kind = null;
        String why = null;
        if (afterRestart ? run.getJobStatus() == null || !run.getJobStatus().isInFlight() : run.getJobStatus() != JobStatus.Failed) {
            kind = NOT_FAILED;
            why = String.format("Run %d is %s, not failed: only a failed run is retried.", run.getJobQueueId(), words(run.getJobStatus()));
        } else if (run.isBackfillRun()) {
            // MIG-347: a backfill's run is retried with its date by the backfill ("Retry failed"), in a free lane of its own.
            plan.backfillId = run.getBackfillId();
            kind = NOT_RESUMABLE;
            why = String.format("Run %d is part of backfill #%d: retry its failed dates from the backfill (Runs › Backfills).",
                run.getJobQueueId(), run.getBackfillId());
        } else if (job.getJobStatus() != Status.Active) {
            kind = INACTIVE;
            why = "The pipeline's schedule is not active; switch it on to retry its runs.";
        } else if (!afterRestart && job.getJobRunningStatus() != null && job.getJobRunningStatus().isInFlight()) {
            kind = IN_FLIGHT;
            why = "Another run of this pipeline is still in flight; one runs at a time. Retry once it has finished.";
        } else if (plan.attempt >= MAX_ATTEMPTS) {
            kind = NOT_RESUMABLE;
            why = String.format("This run has had %d attempts, the most a run has; start a new run instead.", plan.attempt);
        } else if (this.engine != null) {
            Optional<String> paused = this.engine.workspacePause(job.getTenantId());
            if (paused.isPresent()) {
                kind = PAUSED;
                why = "This workspace is " + paused.get() + ", so its runs are paused.";
            }
        }
        Optional<PipelineDefinitionStore.Stored> latest = job.getTenantId() == null || job.getTaskDetail() == null ? Optional.empty()
            : this.definitions.latestFor(job.getTenantId(), job.getTaskDetail().getPipelineId());
        plan.latestDefinitionId = latest.map(stored -> stored.id).orElse(null);
        if (why != null) {
            plan.restartRefusalKind = kind;
            plan.restartRefusal = why;
            return plan.refuse(kind, why);
        }
        return this.resumePlan(plan, run, job, latest, checkInputs);
    }

    private Plan resumePlan(Plan plan, JobQueue run, SourceJob job, Optional<PipelineDefinitionStore.Stored> latest, boolean checkInputs) {
        List<StepStore.StepRow> all = this.steps.stepsOfRun(run.getJobQueueId());
        Map<Long, StepStore.StepRow> byId = new HashMap<>();
        all.forEach(row -> byId.put(row.stepExecutionId, row));
        List<StepStore.StepRow> ofAttempt = all.stream().filter(row -> row.attempt == plan.attempt)
            .sorted((a, b) -> Integer.compare(a.stepIndex, b.stepIndex)).collect(Collectors.toList());
        if (ofAttempt.isEmpty()) {
            return plan.refuse(NOT_RESUMABLE, String.format("Attempt %d ran no steps of its own (a pipeline without steps, or one the "
                + "engine declined), so there is no step to resume from; run it again from the start.", plan.attempt));
        }
        StepStore.StepRow failed = ofAttempt.stream().filter(row -> !JobStatus.Completed.name().equals(row.status)).findFirst().orElse(null);
        if (failed == null) {
            return plan.refuse(NOT_RESUMABLE, String.format("Every step of attempt %d completed; there is no failed step to resume from. "
                + "Run it again from the start.", plan.attempt));
        }
        plan.fromIndex = failed.stepIndex;
        plan.fromKey = failed.stepKey;
        Long ranId = failed.pipelineDefinitionId;
        Optional<PipelineDefinitionStore.Stored> ran = ranId == null ? Optional.empty() : this.definitions.byId(ranId);
        PipelineDefinition was = ran.map(RunRetries::readable).orElse(null);
        PipelineDefinition now = latest.map(RunRetries::readable).orElse(null);
        plan.ranVersion = ran.map(stored -> stored.version).orElse(null);
        if (was == null || now == null) {
            return plan.refuse(CHANGED, "The pipeline's steps cannot be read as the run ran them; run it again from the start.");
        }
        plan.definitionId = latest.get().id;
        plan.definitionVersion = latest.get().version;
        if (now.isLegacy()) {
            return plan.refuse(CHANGED, "The pipeline no longer runs as steps; run it again from the start.");
        }
        List<String> changes = changedBefore(was, now, failed.stepIndex);
        if (!changes.isEmpty()) {
            return plan.refuse(CHANGED, String.format("The pipeline changed before the failed step <%s> since this run (version %d, now %d): "
                + "%s. Only the failed step and the steps after it may change; run it again from the start.", failed.stepKey,
                plan.ranVersion, plan.definitionVersion, String.join("; ", changes)));
        }
        if (now.getSteps().size() <= failed.stepIndex) {
            return plan.refuse(CHANGED, String.format("The pipeline has no step %d any more (the failed step <%s> was removed); run it "
                + "again from the start.", failed.stepIndex + 1, failed.stepKey));
        }
        PipelineDefinition.Step from = now.getSteps().get(failed.stepIndex);
        plan.fromKey = from.getKey();
        plan.fromName = from.getName() == null ? from.getKey() : from.getName();
        List<DefinitionProblem> problems = this.validator.problems(now, job.getTenantId(), null);
        if (!problems.isEmpty()) {
            return plan.refuse(NOT_RESUMABLE, String.format("The pipeline's version %d does not run here: %s.", plan.definitionVersion,
                problems.stream().limit(3).map(DefinitionProblem::toString).collect(Collectors.joining("; "))));
        }
        // Each reused step as the step that really ran it (an earlier resume's reused step names the one it reused).
        List<StepStore.StepRow> reused = ofAttempt.subList(0, failed.stepIndex).stream().map(row -> origin(row, byId))
            .collect(Collectors.toList());
        plan.reusedRows = new ArrayList<>(reused);
        List<Long> ids = reused.stream().map(row -> row.stepExecutionId).collect(Collectors.toList());
        Map<Long, StepStore.KeptOutput> kept = this.steps.keptOutputsOf(ids);
        Map<Long, List<InputDigests.Read>> reads = this.steps.inputsOf(ids);
        Instant at = Instant.now();
        for (StepStore.StepRow row : reused) {
            PipelineDefinition.Step step = now.getSteps().get(row.stepIndex);
            Reused shown = new Reused();
            shown.index = row.stepIndex;
            shown.key = row.stepKey;
            shown.name = step.getName() == null ? step.getKey() : step.getName();
            shown.task = row.taskCode;
            shown.rowsOut = row.recordsOut;
            StepStore.KeptOutput output = kept.get(row.stepExecutionId);
            shown.expiresAt = output == null ? null : output.expiresAt;
            shown.inputFiles = reads.getOrDefault(row.stepExecutionId, Collections.emptyList()).size();
            plan.inputFiles += shown.inputFiles;
            plan.reused.add(shown);
            if (output != null && output.expiredAt(at)) {
                plan.refuse(EXPIRED, String.format("The kept output of step <%s> %s: a resume needs every output before the failed "
                    + "step. Run it again from the start.", row.stepKey, output.removed ? "has been removed (past its retention)"
                    : "expired at " + output.expiresAt));
            }
        }
        if (!plan.resumable()) {
            return plan;
        }
        for (StepStore.StepRow row : reused) {
            List<InputDigests.Read> read = reads.getOrDefault(row.stepExecutionId, Collections.emptyList());
            String unvouched = this.unvouched(row, read);
            if (unvouched != null) {
                return plan.refuse(NOT_RESUMABLE, unvouched);
            }
        }
        if (checkInputs) {
            for (StepStore.StepRow row : reused) {
                for (InputDigests.Read read : reads.getOrDefault(row.stepExecutionId, Collections.emptyList())) {
                    String changed = this.changed(job.getTenantId(), read);
                    if (changed != null) {
                        return plan.refuse(INPUT_CHANGED, String.format("The file %s/%s that step <%s> read %s since this run; a resume "
                            + "would mix old and new data. Run it again from the start.", read.bucket, read.key, row.stepKey, changed));
                    }
                }
            }
            plan.inputsChecked = true;
        }
        return plan;
    }

    /** The step that really ran a reused step: its reused_from, followed to the attempt that ran it. */
    static StepStore.StepRow origin(StepStore.StepRow row, Map<Long, StepStore.StepRow> byId) {
        StepStore.StepRow at = row;
        for (int hops = 0; at.reusedFrom != null && byId.containsKey(at.reusedFrom) && hops < MAX_ATTEMPTS; hops++) {
            at = byId.get(at.reusedFrom);
        }
        return at;
    }

    /** Why a reused step's inputs cannot be vouched for, or null. */
    private String unvouched(StepStore.StepRow row, List<InputDigests.Read> read) {
        for (InputDigests.Read one : read) {
            if (one.overflow()) {
                return String.format("Step <%s> read more than %,d files, too many to check that none changed; run it again from the start.",
                    row.stepKey, InputDigests.MAX_FILES);
            }
        }
        if (read.isEmpty() && readsFiles(row.taskCode)) {
            return String.format("Step <%s> read its files before their fingerprints were kept, so a change cannot be ruled out; run it "
                + "again from the start.", row.stepKey);
        }
        if (!read.isEmpty() && this.buckets == null) {
            return "The files the reused steps read cannot be checked here; run it again from the start.";
        }
        return null;
    }

    /** A task that reads bucket objects as its rows: a run of it reads at least one, so none recorded means none was kept. */
    private boolean readsFiles(String taskCode) {
        Optional<StepTask> task = this.tasks.find(taskCode);
        if (!task.isPresent()) {
            return false;
        }
        TaskSpec spec = task.get().spec();
        return spec != null && spec.kind() == TaskKind.READ && TaskSpec.STORAGE.equals(spec.backingService());
    }

    /** How the object changed since the step read it, or null when the same bytes are there. Streams it; never holds it. */
    String changed(long tenantId, InputDigests.Read read) {
        MessageDigest digest = Streams.sha256();
        long count = 0;
        boolean longer = false;
        try (InputStream in = this.buckets.open(tenantId, read.bucket, read.key, Long.MAX_VALUE)) {
            byte[] chunk = new byte[64 * 1024];
            while (count < read.bytes) {
                int n = in.read(chunk, 0, (int) Math.min(chunk.length, read.bytes - count));
                if (n < 0) {
                    break;
                }
                digest.update(chunk, 0, n);
                count += n;
            }
            if (count == read.bytes && read.whole) {
                longer = in.read() >= 0;
            }
        } catch (Exception gone) {
            logger.info("Retry check: {}/{} could not be read: {}", read.bucket, read.key, StepEngine.reasonOf(gone));
            return "is no longer there (" + StepEngine.reasonOf(gone) + ")";
        }
        if (count < read.bytes) {
            return String.format("is shorter (%,d bytes, the step read %,d)", count, read.bytes);
        }
        if (longer) {
            return String.format("is longer than the %,d bytes the step read", read.bytes);
        }
        String sha = Streams.hex(digest.digest());
        return sha.equals(read.sha256) ? null : String.format("has changed (sha256 %s..., was %s...)", sha.substring(0, 12),
            read.sha256 == null ? "?" : read.sha256.substring(0, Math.min(12, read.sha256.length())));
    }

    /**
     * Retries the run as its next attempt: from the failed step ({@code restart} false) or from the start. The checks run
     * first (the input files read again outside any transaction); the run is then queued again in one transaction --
     * run_attempt recorded, the run back in Queue under its next attempt, the job's status Queue -- which refuses, as a
     * plain refusal, a second retry that got there first or a run of the job queued meanwhile.
     */
    public Outcome retry(JobQueue run, SourceJob job, boolean restart, Actor actor) {
        Plan plan = this.plan(run, job, !restart, actor.afterRestart());
        String mode = restart ? RunAttemptStore.RESTART : RunAttemptStore.RESUME;
        if (restart) {
            if (!plan.restartable()) {
                return new Outcome(plan, mode, plan.restartRefusalKind, plan.restartRefusal);
            }
            if (!actor.admin) {
                return new Outcome(plan, mode, NOT_RESUMABLE, "Running a failed run again from the start is a workspace administrator's.");
            }
        } else if (!plan.resumable()) {
            return new Outcome(plan, mode, plan.refusalKind, plan.refusal);
        }
        RunAttemptStore.Attempt attempt = new RunAttemptStore.Attempt();
        attempt.jobQueueId = run.getJobQueueId();
        attempt.attempt = plan.nextAttempt;
        attempt.previousAttempt = plan.attempt;
        attempt.mode = mode;
        attempt.fromStepIndex = restart ? null : plan.fromIndex;
        attempt.fromStepKey = restart ? null : plan.fromKey;
        attempt.reusedSteps = restart ? 0 : plan.reused.size();
        attempt.pipelineDefinitionId = restart ? plan.latestDefinitionId : plan.definitionId;
        attempt.requestedVia = actor.via;
        attempt.requestedByUserId = actor.userId;
        attempt.requestedByClientId = actor.clientId;
        attempt.requestedAt = Instant.now();
        String who = RunAttemptStore.VIA_API.equals(actor.via) ? "API client " + actor.clientId
            : actor.afterRestart() ? "the platform" : "user " + actor.userId;
        String said = actor.afterRestart()
            ? String.format("Attempt %d queued by the platform: Core restarted while step %d <%s> ran; resumed from it, reusing %d step(s) of "
                + "attempt %d.", attempt.attempt, plan.fromIndex + 1, plan.fromKey, plan.reused.size(), plan.attempt)
            : restart
            ? String.format("Attempt %d queued by %s: run again from the start (attempt %d failed).", attempt.attempt, who, plan.attempt)
            : String.format("Attempt %d queued by %s: retry from step %d <%s>, reusing %d step(s) of attempt %d.", attempt.attempt, who,
                plan.fromIndex + 1, plan.fromKey, plan.reused.size(), plan.attempt);
        String line = actor.bulkId == null ? said : said + " Bulk retry " + actor.bulkId + ".";
        String refused;
        try {
            refused = this.transaction.execute(status -> {
                int moved = this.jdbc.update("UPDATE job_queue SET attempt = ?, job_status = 'Queue', job_send = false, end_time = NULL, "
                    + "prepared_at = NULL, prepare_lease_until = NULL, dispatch_payload = NULL, end_reason = NULL, next_attempt_at = NULL, "
                    + "job_status_message = ? WHERE job_queue_id = ? AND job_status = ? AND attempt = ?", attempt.attempt, line,
                    run.getJobQueueId(), actor.afterRestart() ? run.getJobStatus().name() : JobStatus.Failed.name(), run.getAttempt());
                if (moved != 1) {
                    status.setRollbackOnly();
                    return "The run moved on meanwhile (another retry, or an operator); read it again.";
                }
                this.attempts.record(attempt);
                if (!run.isSideRun()) {
                    this.bulk.changeJobStatus(job.getJobId(), JobStatus.Queue);
                }
                this.bulk.saveJobAuditLogs(run.getJobQueueId(), line);
                return null;
            });
        } catch (RuntimeException failed) {
            if (OneRunInFlight.isViolation(failed)) {
                return new Outcome(plan, mode, IN_FLIGHT, "Another run of this pipeline is still in flight; one runs at a time. Retry once "
                    + "it has finished.");
            }
            if (isUniqueViolation(failed)) {
                return new Outcome(plan, mode, NOT_FAILED, "The run moved on meanwhile (another retry got there first); read it again.");
            }
            throw failed;
        }
        if (refused != null) {
            return new Outcome(plan, mode, NOT_FAILED, refused);
        }
        logger.info("Run {} of job {}: {}", run.getJobQueueId(), job.getJobId(), line);
        return new Outcome(plan, mode, null, null);
    }

    private static boolean isUniqueViolation(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof SQLException && "23505".equals(((SQLException) cause).getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /**
     * What changed before step {@code from} between the version the run ran and the latest: the source, and each earlier
     * step's position, key, task, input and config. A step's name, retry, timeout and on-error may change.
     */
    static List<String> changedBefore(PipelineDefinition was, PipelineDefinition now, int from) {
        List<String> changes = new ArrayList<>();
        if (!Objects.equals(tree(sourceOf(was)), tree(sourceOf(now)))) {
            changes.add("its source");
        }
        List<PipelineDefinition.Step> before = was.getSteps() == null ? Collections.emptyList() : was.getSteps();
        List<PipelineDefinition.Step> after = now.getSteps() == null ? Collections.emptyList() : now.getSteps();
        for (int i = 0; i < from; i++) {
            PipelineDefinition.Step a = i < before.size() ? before.get(i) : null;
            PipelineDefinition.Step b = i < after.size() ? after.get(i) : null;
            if (a == null || b == null) {
                changes.add(String.format("step %d is %s", i + 1, b == null ? "gone" : "new"));
                continue;
            }
            List<String> what = new ArrayList<>();
            if (!Objects.equals(a.getKey(), b.getKey())) {
                what.add("key");
            }
            if (!Objects.equals(a.getTask(), b.getTask())) {
                what.add("task");
            }
            if (!Objects.equals(a.getInput(), b.getInput())) {
                what.add("input");
            }
            if (!Objects.equals(tree(a.getConfig()), tree(b.getConfig()))) {
                what.add("settings");
            }
            if (!what.isEmpty()) {
                changes.add(String.format("step %d <%s>'s %s", i + 1, a.getKey(), String.join(", ", what)));
            }
        }
        return changes;
    }

    private static Map<String, Object> sourceOf(PipelineDefinition definition) {
        Map<String, Object> source = new HashMap<>();
        source.put("type", definition.effectiveSourceType());
        source.put("config", definition.getSource() == null ? null : definition.getSource().getConfig());
        return source;
    }

    /** A value as a JSON tree, so 1 and 1.0 written alike compare alike and a map's order does not matter. */
    private static JsonNode tree(Object value) {
        return value == null ? null : JSON.valueToTree(value);
    }

    private static PipelineDefinition readable(PipelineDefinitionStore.Stored stored) {
        try {
            return stored.definition();
        } catch (IllegalStateException unreadable) {
            return null;
        }
    }

    private static String words(JobStatus status) {
        if (status == null) {
            return "not started";
        }
        switch (status) {
            case Queue:
            case Start:
                return "queued";
            case Running:
                return "running";
            case Completed:
                return "completed";
            case Skip:
            case Missed:
                return "skipped";
            case Interrupt:
                return "interrupted";
            default:
                return status.name().toLowerCase();
        }
    }
}

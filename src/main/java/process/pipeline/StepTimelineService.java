package process.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.ai.ModelChoiceStore;
import process.ai.RunAiStep;
import process.model.dto.ResponseDto;
import process.model.pojo.JobQueue;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.pipeline.data.FileFormats;
import process.pipeline.data.RowSource;
import process.pipeline.data.RowCollector;
import process.pipeline.review.RunReaders;
import process.pipeline.review.RunReviews;
import process.util.BusinessTime;
import process.util.UserNameResolver;
import process.security.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.io.IOException;
import java.io.InputStream;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import process.pipeline.approval.RunApprovalStore;

/**
 * A run's steps for the console's timeline (MIG-230, for MIG-251's Executions detail): each step's status, times,
 * duration, records in and out, tries, on-error, error and datasets, one attempt at a time, and a step's own log.
 *
 * <b>Every run has a timeline.</b> A run of a legacy pipeline -- every run today -- has no step rows: it is shown as its
 * one legacy step, made from the run itself (its status, start and end, its status line), whose log is the run's own
 * (sourceJob.json/findSourceJobAuditLog). The AI steps the run's pipeline asked for (run_ai_step, MIG-242) are listed
 * with the attempt they belong to, so the worker path's per-step AI handling shows in the same timeline rather than a
 * second one.
 *
 * A run is the caller's when its job is (JobOwnership: the workspace, and for a tenant user the jobs that name them)
 * and the job is not deleted -- the rule, and the words, of the run reads beside it. Anything else is not found.
 */
@Service
public class StepTimelineService {

    static final String RUN_NOT_FOUND = "Run not found with jobQueueId.";
    static final String STEP_NOT_FOUND = "Step not found with stepExecutionId.";
    static final String DATASET_NOT_FOUND = "Dataset not found with runDatasetId.";

    /** A download's answer: a file to stream, or an HTTP status and the envelope that says why not. */
    public static final class Download {
        public final int status;
        public final ResponseDto refusal;
        public final String fileName;
        public final String contentType;
        public final StreamingResponseBody body;
        /** MIG-243: the run's workspace and the run, when the dataset is the caller's -- for the file access log. */
        public final Long tenantId;
        public final Long jobQueueId;

        private Download(int status, ResponseDto refusal, String fileName, String contentType, StreamingResponseBody body) {
            this(status, refusal, fileName, contentType, body, null, null);
        }

        private Download(int status, ResponseDto refusal, String fileName, String contentType, StreamingResponseBody body, Long tenantId,
            Long jobQueueId) {
            this.status = status;
            this.refusal = refusal;
            this.fileName = fileName;
            this.contentType = contentType;
            this.body = body;
            this.tenantId = tenantId;
            this.jobQueueId = jobQueueId;
        }

        /** The same answer, about this run of this workspace. */
        Download of(JobQueue run) {
            return new Download(this.status, this.refusal, this.fileName, this.contentType, this.body, run.getTenantId(), run.getJobQueueId());
        }

        static Download refused(int status, String message) {
            return new Download(status, new ResponseDto(ERROR, message), null, null, null);
        }

        static Download file(String fileName, String format, StreamingResponseBody body) {
            return new Download(200, null, fileName, FileFormats.contentType(format), body);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JobQueueRepository runs;
    private final SourceJobRepository jobs;
    private final StepStore steps;
    private final ModelChoiceStore aiSteps;
    private final DatasetStore datasets;
    private final RunReviews reviews;

    public StepTimelineService(JobQueueRepository runs, SourceJobRepository jobs, StepStore steps, ModelChoiceStore aiSteps,
                               DatasetStore datasets, RunReviews reviews) {
        this.runs = runs;
        this.jobs = jobs;
        this.steps = steps;
        this.aiSteps = aiSteps;
        this.datasets = datasets;
        this.reviews = reviews;
    }

    /** UX review 2026-10-12 (R2): who besides a run's owner reads it -- its reviewers; absent in hand-built tests. */
    private RunReaders readers;

    @Autowired(required = false)
    public void useReaders(RunReaders readers) {
        this.readers = readers;
    }

    /** MIG-345: retrying a failed run (from its failed step, or from the start), and the attempts someone asked for. */
    private RunRetries retries;
    private RunAttemptStore attempts;
    private UserNameResolver names;

    @Autowired(required = false)
    public void useRetries(RunRetries retries, RunAttemptStore attempts) {
        this.retries = retries;
        this.attempts = attempts;
    }

    /** MIG-346: the Approval steps' requests, shown on their steps. */
    private RunApprovalStore approvals;

    /** UX review 2026-10-12 (run page): each step's name, from the definition the run followed; absent in hand-built tests. */
    private PipelineDefinitionStore definitions;

    @Autowired(required = false)
    public void useDefinitions(PipelineDefinitionStore definitions) {
        this.definitions = definitions;
    }

    @Autowired(required = false)
    public void useApprovals(RunApprovalStore approvals) {
        this.approvals = approvals;
    }

    @Autowired(required = false)
    public void useNames(UserNameResolver names) {
        this.names = names;
    }

    /** The timeline of one attempt of a run: the latest when none is named. */
    @Transactional(readOnly = true)
    public ResponseDto timeline(Long jobQueueId, Integer attempt) {
        Optional<RunOwnership.Owned> owned = this.readable(jobQueueId);
        if (!owned.isPresent()) {
            return new ResponseDto(ERROR, RUN_NOT_FOUND);
        }
        Optional<JobQueue> run = Optional.of(owned.get().run);
        List<StepStore.StepRow> rows = this.steps.stepsOfRun(run.get().getJobQueueId());
        List<RunAiStep> ai = this.aiSteps.stepsOfRun(run.get().getJobQueueId());
        TreeSet<Integer> attempts = new TreeSet<>();
        rows.forEach(row -> attempts.add(row.attempt));
        ai.forEach(step -> attempts.add(step.attempt == null ? 1 : step.attempt));
        attempts.add(Math.max(1, run.get().getAttempt()));
        int shown = attempt != null ? attempt : attempts.last();
        if (!attempts.contains(shown)) {
            return new ResponseDto(ERROR, String.format("This run has no attempt %d; it has %s.", shown, attempts));
        }

        Map<String, Object> timeline = new LinkedHashMap<>();
        timeline.put("jobQueueId", run.get().getJobQueueId());
        timeline.put("jobId", run.get().getJobId());
        timeline.put("runStatus", run.get().getJobStatus());
        timeline.put("attempt", shown);
        timeline.put("attempts", new ArrayList<>(attempts));
        List<StepStore.StepRow> ofAttempt = rows.stream().filter(row -> row.attempt == shown).collect(Collectors.toList());
        boolean legacy = rows.isEmpty();
        timeline.put("legacy", legacy);
        if (legacy) {
            timeline.put("steps", Collections.singletonList(this.legacyStep(run.get(), shown)));
        } else {
            Map<Long, List<StepStore.DatasetRow>> datasets = this.steps.datasetsOfRun(run.get().getJobQueueId()).stream()
                .collect(Collectors.groupingBy(dataset -> dataset.stepExecutionId));
            // MIG-345: a reused step shows the attempt whose output it is, and that output's datasets.
            Map<Long, Integer> attemptOf = new HashMap<>();
            rows.forEach(row -> attemptOf.put(row.stepExecutionId, row.attempt));
            Long definitionId = ofAttempt.isEmpty() ? null : ofAttempt.get(0).pipelineDefinitionId;
            timeline.put("pipelineDefinitionId", definitionId);
            Map<String, String> names = this.stepNames(definitionId);
            // MIG-346: an Approval step's request (a reused step shows the one that approved it).
            Map<Long, RunApprovalStore.Approval> approvalsOf = this.approvals == null ? Collections.emptyMap()
                : this.approvals.ofSteps(ofAttempt.stream().filter(row -> "approval".equals(row.taskCode))
                    .map(row -> row.reusedFrom != null ? row.reusedFrom : row.stepExecutionId).collect(Collectors.toList()));
            boolean[] waiting = {false};
            timeline.put("steps", ofAttempt.stream().map(row -> {
                long datasetsOf = row.reusedFrom != null ? row.reusedFrom : row.stepExecutionId;
                Map<String, Object> step = step(row, datasets.getOrDefault(datasetsOf, Collections.emptyList()));
                step.put("name", names.get(row.stepKey));
                step.put("reusedFromAttempt", row.reusedFrom == null ? null : attemptOf.get(row.reusedFrom));
                RunApprovalStore.Approval approval = approvalsOf.get(datasetsOf);
                if (approval != null) {
                    step.put("approval", approvalView(approval));
                    waiting[0] |= approval.pending() && row.reusedFrom == null;
                }
                return step;
            }).collect(Collectors.toList()));
            timeline.put("waitingForApproval", waiting[0]);
        }
        timeline.put("attemptDetails", this.attemptDetails(run.get().getJobQueueId()));
        timeline.put("aiSteps", ai.stream().filter(step -> (step.attempt == null ? 1 : step.attempt) == shown).collect(Collectors.toList()));
        this.putReview(timeline, owned.get());
        return new ResponseDto(SUCCESS, String.format("%d step(s) in attempt %d.", ((List<?>) timeline.get("steps")).size(), shown),
            timeline);
    }

    /** One step's own log, in order. */
    @Transactional(readOnly = true)
    public ResponseDto log(Long stepExecutionId) {
        Optional<StepStore.StepRow> step = stepExecutionId == null ? Optional.empty() : this.steps.stepById(stepExecutionId);
        if (!step.isPresent() || !this.owned(step.get().jobQueueId).isPresent()) {
            return new ResponseDto(ERROR, STEP_NOT_FOUND);
        }
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("stepExecutionId", step.get().stepExecutionId);
        log.put("jobQueueId", step.get().jobQueueId);
        log.put("stepKey", step.get().stepKey);
        log.put("attempt", step.get().attempt);
        log.put("lines", this.steps.logOf(step.get().stepExecutionId));
        return new ResponseDto(SUCCESS, String.format("%d line(s).", ((List<?>) log.get("lines")).size()), log);
    }

    /**
     * A run dataset as a file (Wave 4): a step's output as CSV, JSON or JSON Lines (CSV when no format is named), or a
     * file Save File kept, as it was written (or converted, when another format is named). The caller's when its run
     * is (the timeline's rule); anything else, another workspace's included, is not found (404). Past its expiresAt it
     * is gone (410) -- after the sweep too, for a kept file, which the run's manifest still names.
     */
    @Transactional(readOnly = true)
    public Download download(Long runDatasetId, String format) {
        String wanted = format == null || format.trim().isEmpty() ? null : format.trim().toLowerCase(Locale.ROOT);
        if (wanted != null && !FileFormats.WRITABLE.contains(wanted) && !"pdf".equals(wanted)) {
            return Download.refused(400, String.format("A dataset downloads as csv, json or jsonl; got '%s'.", format));
        }
        Optional<StepStore.DatasetFile> found = runDatasetId == null ? Optional.empty() : this.steps.datasetById(runDatasetId);
        if (!found.isPresent()) {
            Optional<StepStore.OutputRow> swept = runDatasetId == null ? Optional.empty() : this.steps.outputOfDataset(runDatasetId);
            Optional<JobQueue> sweptRun = swept.isPresent() ? this.owned(swept.get().jobQueueId) : Optional.empty();
            if (sweptRun.isPresent()) {
                return Download.refused(410, expired(swept.get().expiresAt)).of(sweptRun.get());
            }
            return Download.refused(404, DATASET_NOT_FOUND);
        }
        StepStore.DatasetFile dataset = found.get();
        Optional<JobQueue> run = this.owned(dataset.jobQueueId);
        if (!run.isPresent()) {
            return Download.refused(404, DATASET_NOT_FOUND);
        }
        if (dataset.expiresAt != null && !dataset.expiresAt.isAfter(Instant.now())) {
            return Download.refused(410, expired(dataset.expiresAt)).of(run.get());
        }
        try {
            if (dataset.storageKey.contains("/files/")) {
                return this.keptFile(dataset, wanted).of(run.get());
            }
            if ("pdf".equals(wanted)) {
                return Download.refused(400, "A dataset downloads as csv, json or jsonl; only a report is a PDF.").of(run.get());
            }
            // MIG-344: a dataset streams from the store to the download, a batch at a time -- a streamed step's output may be
            // far bigger than memory. Opened here, so a dataset that is gone still answers 410 before anything is sent.
            RowSource rows = this.datasets.open(dataset.storageKey, RowSource.DEFAULT_BATCH);
            String as = wanted == null ? "csv" : wanted;
            String name = String.format("run-%d-attempt-%d-%s-%s.%s", dataset.jobQueueId, dataset.attempt, dataset.stepKey, dataset.name, as);
            return Download.file(name, as, out -> {
                try (RowSource source = rows) {
                    FileFormats.writeTo(source, as, out);
                } catch (IOException | RuntimeException failed) {
                    throw failed;
                } catch (Exception failed) {
                    throw new IOException(failed.getMessage(), failed);
                }
            }).of(run.get());
        } catch (Exception ex) {
            return Download.refused(410, "This dataset's content is no longer available; run the job again to make it anew.").of(run.get());
        }
    }

    /**
     * A run's result manifest (Wave 4): the files its steps wrote, every attempt unless one is named -- a kept file with
     * the runDatasetId that downloads it (sourceJob.json/runDataset) and when it expires, an upload with the bucket alias
     * and key storage-service's browse endpoints download. Never a storage key.
     */
    @Transactional(readOnly = true)
    public ResponseDto outputs(Long jobQueueId, Integer attempt) {
        Optional<RunOwnership.Owned> owned = this.readable(jobQueueId);
        if (!owned.isPresent()) {
            return new ResponseDto(ERROR, RUN_NOT_FOUND);
        }
        Optional<JobQueue> run = Optional.of(owned.get().run);
        Instant now = Instant.now();
        // MIG-345: an attempt's files include those its reused steps made in the attempt whose outputs they are.
        Set<Long> reusedSources = new HashSet<>();
        if (attempt != null) {
            this.steps.stepsOfRun(run.get().getJobQueueId()).stream().filter(row -> row.attempt == attempt && row.reusedFrom != null)
                .forEach(row -> reusedSources.add(row.reusedFrom));
        }
        List<Map<String, Object>> outputs = this.steps.outputsOfRun(run.get().getJobQueueId()).stream()
            .filter(row -> attempt == null || row.attempt == attempt || reusedSources.contains(row.stepExecutionId))
            .map(row -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("runOutputId", row.runOutputId);
                out.put("stepExecutionId", row.stepExecutionId);
                out.put("attempt", row.attempt);
                out.put("stepIndex", row.stepIndex);
                out.put("stepKey", row.stepKey);
                out.put("task", row.taskCode);
                out.put("kind", row.kind);
                out.put("name", row.name);
                out.put("format", row.format);
                out.put("rowCount", row.rowCount);
                out.put("byteCount", row.byteCount);
                out.put("pageCount", row.pageCount);
                out.put("recordedAt", row.recordedAt);
                if (RunOutput.FILE.equals(row.kind)) {
                    out.put("runDatasetId", row.runDatasetId);
                    out.put("expiresAt", row.expiresAt == null ? null : BusinessTime.wallClockOf(row.expiresAt));
                    out.put("expired", row.expiresAt != null && !row.expiresAt.isAfter(now));
                } else {
                    out.put("bucket", row.bucketAlias);
                    out.put("key", row.objectKey);
                }
                return out;
            }).collect(Collectors.toList());
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("jobQueueId", run.get().getJobQueueId());
        manifest.put("jobId", run.get().getJobId());
        manifest.put("attempt", attempt);
        manifest.put("outputs", outputs);
        this.putReview(manifest, owned.get());
        return new ResponseDto(SUCCESS, String.format("%d file(s).", outputs.size()), manifest);
    }

    /**
     * MIG-345: the attempts of the run someone asked for -- resume or restart, from which step, how many steps reused, by
     * whom (a person's name, or the API client) and when -- for the run's history.
     */
    private List<Map<String, Object>> attemptDetails(long jobQueueId) {
        if (this.attempts == null) {
            return Collections.emptyList();
        }
        List<RunAttemptStore.Attempt> asked = this.attempts.ofRun(jobQueueId);
        Map<Long, String> who = Collections.emptyMap();
        List<Long> users = asked.stream().map(a -> a.requestedByUserId).filter(Objects::nonNull).distinct().collect(Collectors.toList());
        if (this.names != null && !users.isEmpty()) {
            try {
                who = this.names.namesFor(users);
            } catch (RuntimeException unnamed) {
                who = Collections.emptyMap();
            }
        }
        List<Map<String, Object>> details = new ArrayList<>();
        for (RunAttemptStore.Attempt one : asked) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("attempt", one.attempt);
            detail.put("previousAttempt", one.previousAttempt);
            detail.put("mode", one.mode);
            detail.put("fromStepIndex", one.fromStepIndex);
            detail.put("fromStepKey", one.fromStepKey);
            detail.put("reusedSteps", one.reusedSteps);
            detail.put("requestedVia", one.requestedVia);
            detail.put("requestedByUserId", one.requestedByUserId);
            detail.put("requestedBy", one.requestedByClientId != null ? "API client " + one.requestedByClientId
                : who.getOrDefault(one.requestedByUserId, one.requestedByUserId == null ? null : "user " + one.requestedByUserId));
            detail.put("requestedAt", one.requestedAt == null ? null : BusinessTime.wallClockOf(one.requestedAt));
            details.add(detail);
        }
        return details;
    }

    /**
     * MIG-345: what retrying this failed run would do, for the console's dialog: the step it would resume from and the steps
     * it would reuse (each with its rows and when its output expires), or why it cannot; and whether the caller may run it
     * again from the start (a workspace administrator's), or why not. The input files are not read here: the retry checks
     * them and says so when one changed.
     */
    @Transactional(readOnly = true)
    public ResponseDto retryPlan(Long jobQueueId) {
        Optional<RunOwnership.Owned> owned = RunOwnership.owned(this.runs, this.jobs, jobQueueId);
        if (!owned.isPresent()) {
            return new ResponseDto(ERROR, RUN_NOT_FOUND);
        }
        if (this.retries == null) {
            return new ResponseDto(ERROR, "Retrying a run is not available here.");
        }
        RunRetries.Plan plan = this.retries.plan(owned.get().run, owned.get().job, false);
        return new ResponseDto(SUCCESS, plan.resumable() ? "Resumable." : plan.refusal, this.planView(plan));
    }

    /** MIG-345: retries the failed run -- {@code from} "start" runs it again from the start, else from its failed step. */
    public ResponseDto retry(Long jobQueueId, String from) {
        return this.retry(jobQueueId, from, null);
    }

    /** MIG-355: the same retry as part of a bulk retry: its audit line names the bulk retry ({@code bulkId}, null for none). */
    public ResponseDto retry(Long jobQueueId, String from, String bulkId) {
        Optional<RunOwnership.Owned> owned = RunOwnership.owned(this.runs, this.jobs, jobQueueId);
        if (!owned.isPresent()) {
            return new ResponseDto(ERROR, RUN_NOT_FOUND);
        }
        if (this.retries == null) {
            return new ResponseDto(ERROR, "Retrying a run is not available here.");
        }
        boolean restart = "start".equals(from);
        RunRetries.Actor actor = RunRetries.Actor.person(TenantContext.getAppUserId(), isAdmin());
        RunRetries.Outcome outcome = this.retries.retry(owned.get().run, owned.get().job, restart, bulkId == null ? actor : actor.inBulk(bulkId));
        Map<String, Object> view = this.planView(outcome.plan);
        view.put("mode", outcome.mode);
        view.put("refusalKind", outcome.refusalKind);
        if (!outcome.queued()) {
            return new ResponseDto(ERROR, outcome.refusal, view);
        }
        return new ResponseDto(SUCCESS, restart
            ? String.format("Run %d queued again from the start as attempt %d.", jobQueueId, outcome.plan.nextAttempt)
            : String.format("Run %d queued to resume from step %d <%s> as attempt %d, reusing %d step(s).", jobQueueId,
                outcome.plan.fromIndex + 1, outcome.plan.fromKey, outcome.plan.nextAttempt, outcome.plan.reused.size()), view);
    }

    /** A workspace administrator (or the platform's): who may run a failed run again from the start in the console. */
    private static boolean isAdmin() {
        return "TENANT_ADMIN".equals(TenantContext.getUserRole()) || TenantContext.isPlatformAdmin();
    }

    private Map<String, Object> planView(RunRetries.Plan plan) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("jobQueueId", plan.jobQueueId);
        view.put("jobId", plan.jobId);
        view.put("attempt", plan.attempt);
        view.put("nextAttempt", plan.nextAttempt);
        view.put("resumable", plan.resumable());
        view.put("refusalKind", plan.refusalKind);
        view.put("refusal", plan.refusal);
        view.put("fromIndex", plan.fromIndex);
        view.put("fromKey", plan.fromKey);
        view.put("fromName", plan.fromName);
        view.put("definitionVersion", plan.definitionVersion);
        view.put("ranVersion", plan.ranVersion);
        view.put("reused", plan.reused.stream().map(reused -> {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("index", reused.index);
            step.put("key", reused.key);
            step.put("name", reused.name);
            step.put("task", reused.task);
            step.put("rowsOut", reused.rowsOut);
            step.put("expiresAt", reused.expiresAt == null ? null : BusinessTime.wallClockOf(reused.expiresAt));
            step.put("inputFiles", reused.inputFiles);
            return step;
        }).collect(Collectors.toList()));
        view.put("inputFiles", plan.inputFiles);
        view.put("inputsChecked", plan.inputsChecked);
        // UX review 2026-10-13: the console says where such a run is retried (the backfill's Retry failed) instead of offering it.
        view.put("backfillId", plan.backfillId);
        boolean admin = isAdmin();
        view.put("restartable", plan.restartable() && admin);
        view.put("restartRefusal", plan.restartable() ? (admin ? null : "Running a failed run again from the start is a workspace "
            + "administrator's.") : plan.restartRefusal);
        return view;
    }

    /** A file Save File kept: its own bytes in its own format, or its rows in the one asked for. */
    private Download keptFile(StepStore.DatasetFile dataset, String wanted) throws Exception {
        String own = this.steps.outputOfDataset(dataset.runDatasetId).map(output -> output.format)
            .orElseGet(() -> Optional.ofNullable(FileFormats.byExtension(dataset.name)).orElse("csv"));
        if (wanted == null || wanted.equals(own)) {
            // MIG-344: streamed from the store; its size is asked first, so a file that is gone still answers 410.
            this.datasets.fileSize(dataset.storageKey);
            return Download.file(dataset.name, own, out -> {
                try (InputStream in = this.datasets.openFile(dataset.storageKey)) {
                    byte[] chunk = new byte[1 << 16];
                    for (int n = in.read(chunk); n >= 0; n = in.read(chunk)) {
                        out.write(chunk, 0, n);
                    }
                } catch (IOException | RuntimeException failed) {
                    throw failed;
                } catch (Exception failed) {
                    throw new IOException(failed.getMessage(), failed);
                }
            });
        }
        byte[] content = this.datasets.readFile(dataset.storageKey);
        // MIG-255: a report is a document, not rows -- it downloads as itself, and rows download as rows.
        if ("pdf".equals(own) || "pdf".equals(wanted)) {
            return Download.refused(400, "pdf".equals(own) ? "A PDF report downloads as the PDF it is."
                : "Only a report is a PDF; this file downloads as csv, json or jsonl.");
        }
        RowCollector collector = new RowCollector("The file", null);
        FileFormats.read(content, own, new FileFormats.ReadOptions(), collector);
        Dataset rows = collector.toDataset(parseColumns(dataset.columns));
        int dot = dataset.name.lastIndexOf('.');
        String name = (dot > 0 ? dataset.name.substring(0, dot) : dataset.name) + "." + wanted;
        return Download.file(name, wanted, out -> FileFormats.writeTo(rows, wanted, out));
    }

    private static String expired(Instant at) {
        return String.format("This dataset expired%s and can no longer be downloaded: a run's datasets are kept for its pipeline's "
            + "datasetRetentionHours. Run the job again to make it anew.", at == null ? ""
            : " at " + BusinessTime.wallClockOf(at).withNano(0).toString().replace('T', ' ') + " (Chicago)");
    }

    @SuppressWarnings("unchecked")
    private static List<String> parseColumns(String json) {
        Object parsed = parseList(json);
        List<String> columns = new ArrayList<>();
        for (Object column : (List<Object>) parsed) {
            columns.add(String.valueOf(column));
        }
        return columns;
    }

    /**
     * MIG-237: where the run's review stands -- reviewStatus (NOT_REQUIRED, PENDING, APPROVED, REJECTED) and the review
     * itself (required parties, decisions, decidedAt, rerunJobQueueId) -- on the timeline and the manifest alike.
     */
    private void putReview(Map<String, Object> read, RunOwnership.Owned owned) {
        Map<String, Object> review = this.reviews.summary(owned.run, owned.job);
        read.put("reviewStatus", review.get("reviewStatus"));
        read.put("review", review);
    }

    /** The run of a READ -- a step's log, a dataset -- when the caller may read it: its owner or one of its reviewers. */
    private Optional<JobQueue> owned(Long jobQueueId) {
        return this.readable(jobQueueId).map(owned -> owned.run);
    }

    /**
     * UX review 2026-10-12 (R2): the run reads -- timeline, manifest, a step's log, a dataset -- are open to the run's
     * reviewers as well as its owner (RunReaders); retrying stays the owner's (RunOwnership.owned).
     */
    private Optional<RunOwnership.Owned> readable(Long jobQueueId) {
        return this.readers != null ? this.readers.readable(jobQueueId) : RunOwnership.owned(this.runs, this.jobs, jobQueueId);
    }

    /** A legacy run as its one step: the run's own status, times and status line; its log is the run's. */
    private Map<String, Object> legacyStep(JobQueue run, int attempt) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("stepExecutionId", null);
        step.put("index", 0);
        step.put("key", PipelineDefinition.LEGACY_KEY);
        step.put("task", PipelineDefinition.LEGACY_TASK);
        step.put("status", run.getJobStatus() == null ? null : run.getJobStatus().name());
        step.put("startedAt", run.getStartTime());
        step.put("endedAt", run.getEndTime());
        step.put("durationMs", run.getStartTime() == null || run.getEndTime() == null ? null
            : Duration.between(BusinessTime.instantOf(run.getStartTime()), BusinessTime.instantOf(run.getEndTime())).toMillis());
        step.put("recordsIn", null);
        step.put("recordsOut", null);
        step.put("tries", attempt);
        step.put("onError", null);
        step.put("statusMessage", run.getJobStatusMessage());
        step.put("error", null);
        step.put("datasets", Collections.emptyList());
        step.put("log", "run");
        return step;
    }

    /** MIG-346: an Approval step's request as the timeline shows it; times are UTC instants. */
    private static Map<String, Object> approvalView(RunApprovalStore.Approval a) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("approvalId", a.runApprovalId);
        out.put("status", a.status);
        out.put("waiting", a.pending());
        out.put("title", a.title);
        Map<String, Object> reviewers = new LinkedHashMap<>();
        reviewers.put("kind", a.reviewersKind);
        reviewers.put("value", a.reviewersValue);
        out.put("reviewers", reviewers);
        out.put("requestedAt", a.requestedAt == null ? null : a.requestedAt.toString());
        out.put("expiresAt", a.expiresAt == null ? null : a.expiresAt.toString());
        out.put("decidedAt", a.decidedAt == null ? null : a.decidedAt.toString());
        out.put("decidedByUserId", a.decidedByUserId);
        out.put("decidedBy", a.pending() ? null : a.decider());
        out.put("comment", a.comment);
        out.put("workflowInstanceId", a.workflowInstanceId);
        return out;
    }

    /**
     * The steps' names (a step's optional {@code name}: "Vision model: DRAFT label") by key, from the definition the run
     * followed -- the run page leads with them and keeps the key as the secondary text. A definition that cannot be read
     * names nothing: the timeline is about what ran, and the keys still say it.
     */
    Map<String, String> stepNames(Long pipelineDefinitionId) {
        if (pipelineDefinitionId == null || this.definitions == null) {
            return Collections.emptyMap();
        }
        try {
            Map<String, String> names = new HashMap<>();
            this.definitions.byId(pipelineDefinitionId).ifPresent(stored -> stored.definition().getSteps().forEach(step -> {
                if (step.getKey() != null && step.getName() != null && !step.getName().trim().isEmpty()) {
                    names.put(step.getKey(), step.getName().trim());
                }
            }));
            return names;
        } catch (RuntimeException ex) {
            return Collections.emptyMap();
        }
    }

    private static Map<String, Object> step(StepStore.StepRow row, List<StepStore.DatasetRow> datasets) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("stepExecutionId", row.stepExecutionId);
        step.put("index", row.stepIndex);
        step.put("key", row.stepKey);
        step.put("task", row.taskCode);
        step.put("status", row.status);
        step.put("startedAt", row.startedAt);
        step.put("endedAt", row.endedAt);
        step.put("durationMs", row.durationMs);
        step.put("recordsIn", row.recordsIn);
        step.put("recordsOut", row.recordsOut);
        step.put("tries", row.tries);
        step.put("onError", row.onError);
        step.put("statusMessage", row.statusMessage);
        step.put("error", parse(row.error));
        step.put("datasets", datasets.stream().map(dataset -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("runDatasetId", dataset.runDatasetId);
            out.put("name", dataset.name);
            out.put("rowCount", dataset.rowCount);
            out.put("columns", parseList(dataset.columns));
            out.put("expiresAt", dataset.expiresAt);
            return out;
        }).collect(Collectors.toList()));
        step.put("log", "step");
        return step;
    }

    private static Object parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return json;
        }
    }

    private static Object parseList(String json) {
        if (json == null) {
            return Collections.emptyList();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<Object>>() {});
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }
}

package process.customer;

import org.barco.platform.api.ApiTimes;
import org.barco.platform.api.Cursors;
import org.barco.platform.api.Problem;
import org.barco.platform.security.ApiScopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.pipeline.PipelineDefinition;
import process.pipeline.RunResults;
import process.pipeline.items.ItemMasks;
import process.pipeline.items.WorkspaceMasks;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.StepStore;
import process.pipeline.review.RunReviews;
import process.security.TenantContext;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The customer API's runs (MIG-334, ADR-025 decision 3; OpenAPI listRuns, getRun, listRunSteps, getRunOutputs). Scope
 * runs:read for each.
 *
 * <ul>
 *   <li>GET /v1/runs: every run of the workspace's pipelines, however it started, newest first, cursor paging, filtered by
 *   pipelineId, status, createdAfter and updatedAfter.</li>
 *   <li>GET /v1/runs/{id}: one run -- status, times in UTC, the reference it was started with, its attempt, its review
 *   status.</li>
 *   <li>GET /v1/runs/{id}/steps: its latest attempt's steps in order; a run without steps is its one "legacy" step.</li>
 *   <li>GET /v1/runs/{id}/outputs: its manifest -- the files its latest attempt made and the files it was given, by file
 *   id (GET /v1/files/{fileId} downloads one), and its review.</li>
 * </ul>
 * Generic: a new step or output kind appears through these resources as it is. A run of another workspace, a deleted
 * run or a run of a deleted pipeline is a 404 -- the same answer as one that does not exist.
 */
@Service
public class CustomerRuns {

    private static final Logger logger = LoggerFactory.getLogger(CustomerRuns.class);
    /** The API's run status words and the run states each covers (RunIntake.statusOf, the other way round). */
    static final Map<String, List<String>> STATUSES = new LinkedHashMap<>();

    static {
        STATUSES.put("queued", Arrays.asList("Queue", "Start"));
        STATUSES.put("running", Collections.singletonList("Running"));
        STATUSES.put("completed", Collections.singletonList("Completed"));
        STATUSES.put("failed", Collections.singletonList("Failed"));
        STATUSES.put("skipped", Arrays.asList("Skip", "Missed"));
        STATUSES.put("interrupted", Collections.singletonList("Interrupt"));
    }

    static final String NO_SUCH_RUN = "No such run.";

    private final CustomerRunStore store;
    private final JobQueueRepository runs;
    private final SourceJobRepository jobs;
    private final RunReviews reviews;
    private final StepStore steps;
    private final PipelineDefinitionStore definitions;
    private final RunFiles files;

    public CustomerRuns(CustomerRunStore store, JobQueueRepository runs, SourceJobRepository jobs, RunReviews reviews, StepStore steps,
        PipelineDefinitionStore definitions, RunFiles files) {
        this.store = store;
        this.runs = runs;
        this.jobs = jobs;
        this.reviews = reviews;
        this.steps = steps;
        this.definitions = definitions;
        this.files = files;
    }

    @Transactional(readOnly = true)
    public CustomerAnswer list(Integer limit, String cursor, String pipelineId, String status, String createdAfter, String updatedAfter) {
        String instance = "/v1/runs";
        if (!TenantContext.hasScope(ApiScopes.RUNS_READ)) {
            return CustomerAnswer.problem(CustomerPipelines.insufficient(ApiScopes.RUNS_READ), instance);
        }
        int size;
        Long before;
        Long jobId = null;
        Instant created;
        Instant updated;
        try {
            size = Cursors.limit(limit);
            before = Cursors.lastId(cursor);
            created = createdAfter == null || createdAfter.isEmpty() ? null : ApiTimes.parse(createdAfter);
            updated = updatedAfter == null || updatedAfter.isEmpty() ? null : ApiTimes.parse(updatedAfter);
        } catch (IllegalArgumentException bad) {
            return CustomerAnswer.problem(Problem.of(400, bad.getMessage()), instance);
        }
        List<String> states = null;
        if (status != null && !status.isEmpty()) {
            states = STATUSES.get(status);
            if (states == null) {
                return CustomerAnswer.problem(Problem.of(400, "status is one of " + String.join(", ", STATUSES.keySet()) + "."), instance);
            }
        }
        long tenantId = TenantContext.getTenantId();
        Map<String, Object> body = new LinkedHashMap<>();
        if (pipelineId != null && !pipelineId.isEmpty()) {
            try {
                jobId = Long.parseLong(pipelineId);
            } catch (NumberFormatException notOurs) {
                // An id this API never gave is a pipeline with no runs, as another workspace's is.
                body.put("data", Collections.emptyList());
                body.put("nextCursor", null);
                return CustomerAnswer.of(200, body, null);
            }
        }
        List<CustomerRunStore.Row> page = this.store.page(tenantId, before, size + 1, jobId, states, created, updated);
        boolean more = page.size() > size;
        List<CustomerRunStore.Row> shown = more ? page.subList(0, size) : page;
        Map<Long, String> review = this.reviewWords(shown);
        List<Map<String, Object>> data = new ArrayList<>();
        for (CustomerRunStore.Row row : shown) {
            data.add(CustomerViews.run(row, review.getOrDefault(row.runId, "not_required")));
        }
        body.put("data", data);
        body.put("nextCursor", more ? Cursors.after(shown.get(size - 1).runId) : null);
        return CustomerAnswer.of(200, body, null);
    }

    @Transactional(readOnly = true)
    public CustomerAnswer get(String runId) {
        String instance = "/v1/runs/" + runId;
        if (!TenantContext.hasScope(ApiScopes.RUNS_READ)) {
            return CustomerAnswer.problem(CustomerPipelines.insufficient(ApiScopes.RUNS_READ), instance);
        }
        Optional<Found> found = this.found(runId);
        if (!found.isPresent()) {
            return CustomerAnswer.problem(Problem.of(404, NO_SUCH_RUN), instance);
        }
        Map<String, Object> summary = this.reviews.summary(found.get().run, found.get().job);
        return CustomerAnswer.of(200, CustomerViews.run(found.get().row, CustomerViews.reviewWordOf(summary.get("reviewStatus"))), null);
    }

    @Transactional(readOnly = true)
    public CustomerAnswer steps(String runId) {
        String instance = "/v1/runs/" + runId + "/steps";
        if (!TenantContext.hasScope(ApiScopes.RUNS_READ)) {
            return CustomerAnswer.problem(CustomerPipelines.insufficient(ApiScopes.RUNS_READ), instance);
        }
        Optional<Found> found = this.found(runId);
        if (!found.isPresent()) {
            return CustomerAnswer.problem(Problem.of(404, NO_SUCH_RUN), instance);
        }
        CustomerRunStore.Row run = found.get().row;
        Steps latest = this.stepsOf(run);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", String.valueOf(run.runId));
        body.put("attempt", latest.attempt);
        body.put("data", latest.data);
        return CustomerAnswer.of(200, body, null);
    }

    /** A run's latest attempt and its steps, as the Step schema. */
    static final class Steps {
        final int attempt;
        final List<Map<String, Object>> data;

        Steps(int attempt, List<Map<String, Object>> data) {
            this.attempt = attempt;
            this.data = data;
        }
    }

    /** The latest attempt's steps in order; a run without steps is its one legacy step (MIG-335's view reads them too). */
    Steps stepsOf(CustomerRunStore.Row run) {
        List<StepStore.StepRow> rows = this.steps.stepsOfRun(run.runId);
        int attempt = run.attempt;
        for (StepStore.StepRow row : rows) {
            attempt = Math.max(attempt, row.attempt);
        }
        List<Map<String, Object>> data = new ArrayList<>();
        if (rows.isEmpty()) {
            data.add(legacyStep(run));
        } else {
            Map<String, String> names = this.stepNames(run.runId);
            // MIG-345: a reused step says which attempt's output it is.
            Map<Long, Integer> attemptOf = new HashMap<>();
            for (StepStore.StepRow row : rows) {
                attemptOf.put(row.stepExecutionId, row.attempt);
            }
            for (StepStore.StepRow row : rows) {
                if (row.attempt == attempt) {
                    data.add(step(row, names.get(row.stepKey), row.reusedFrom == null ? null : attemptOf.get(row.reusedFrom)));
                }
            }
        }
        return new Steps(attempt, data);
    }

    /**
     * The files the run's latest attempt made, as the manifest lists them (the File schema with step, rows and expired); a
     * file recorded before file ids is given its id here, so the caller's transaction must allow a write.
     */
    List<Map<String, Object>> madeFiles(CustomerRunStore.Row run, int attempt) {
        List<Map<String, Object>> listed = new ArrayList<>();
        Instant now = Instant.now();
        // MIG-345: a resumed attempt's files include those its reused steps made in the attempt whose outputs they are.
        Set<Long> reusedSources = new HashSet<>();
        for (StepStore.StepRow row : this.steps.stepsOfRun(run.runId)) {
            if (row.attempt == attempt && row.reusedFrom != null) {
                reusedSources.add(row.reusedFrom);
            }
        }
        for (StepStore.OutputRow output : this.steps.outputsOfRun(run.runId)) {
            if (output.attempt != attempt && !reusedSources.contains(output.stepExecutionId)) {
                continue;
            }
            String fileId = output.fileId != null ? output.fileId : this.steps.fileIdOf(output.runOutputId);
            if (fileId != null) {
                listed.add(CustomerViews.madeFile(output, fileId, now));
            }
        }
        return listed;
    }

    /** MIG-345: the run's review summary, for the retry's answer. */
    Map<String, Object> reviewSummary(Found found) {
        return this.reviews.summary(found.run, found.job);
    }

    /** The run's review as the Review schema (MIG-335's view reads it too). */
    Map<String, Object> reviewOf(Found found) {
        return CustomerViews.review(this.reviews.summary(found.run, found.job));
    }

    /** Not read-only: a made file recorded before MIG-334 is given its file id the first time it is listed. */
    @Transactional
    public CustomerAnswer outputs(String runId) {
        String instance = "/v1/runs/" + runId + "/outputs";
        if (!TenantContext.hasScope(ApiScopes.RUNS_READ)) {
            return CustomerAnswer.problem(CustomerPipelines.insufficient(ApiScopes.RUNS_READ), instance);
        }
        Optional<Found> found = this.found(runId);
        if (!found.isPresent()) {
            return CustomerAnswer.problem(Problem.of(404, NO_SUCH_RUN), instance);
        }
        CustomerRunStore.Row run = found.get().row;
        long tenantId = run.tenantId;
        List<Map<String, Object>> listed = new ArrayList<>();
        Optional<CustomerRunStore.Intake> intake = this.store.intakeOf(tenantId, run.runId);
        if (intake.isPresent() && !intake.get().fileIds.isEmpty()) {
            Map<String, Map<String, Object>> given;
            try {
                given = this.files.of(tenantId, intake.get().fileIds);
            } catch (RuntimeException unreachable) {
                logger.warn("Run {}'s input files could not be read from storage-service for its manifest: {}", run.runId,
                    unreachable.getMessage());
                return CustomerAnswer.problem(Problem.of(503, "The run's files cannot be listed right now. Try again in a moment."), instance);
            }
            for (String id : intake.get().fileIds) {
                if (given.containsKey(id)) {
                    listed.add(CustomerViews.uploadedFile(given.get(id)));
                }
            }
        }
        int attempt = run.attempt;
        for (StepStore.StepRow row : this.steps.stepsOfRun(run.runId)) {
            attempt = Math.max(attempt, row.attempt);
        }
        listed.addAll(this.madeFiles(run, attempt));
        Instant now = Instant.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", String.valueOf(run.runId));
        body.put("pipelineId", String.valueOf(run.jobId));
        body.put("status", RunIntake.statusOf(run.jobStatus));
        body.put("attempt", attempt);
        body.put("generatedAt", ApiTimes.utc(now));
        body.put("files", listed);
        body.put("review", this.reviewOf(found.get()));
        return CustomerAnswer.of(200, body, null);
    }

    /** MIG-424: a finished run's answer, and the workspace's masks it is read with; absent in hand-built tests. */
    private RunResults results;
    private WorkspaceMasks masks;

    @Autowired(required = false)
    public void useResults(RunResults results, WorkspaceMasks masks) {
        this.results = results;
        this.masks = masks;
    }

    /** MIG-424: the most rows a run's result gives. */
    static final int RESULT_ROWS = RunResults.MAX_ROWS;

    /** MIG-426: a run's result as a document (pdf, html, md); absent in hand-built tests (json only). */
    private RunDocuments documents;
    /**
     * MIG-426: the reads a result needs, in a read-only transaction of their own -- never around a document's making, which
     * waits on media-service and writes the file access log. Absent in hand-built tests (read as they come).
     */
    private TransactionTemplate reads;

    @Autowired(required = false)
    public void useDocuments(RunDocuments documents) {
        this.documents = documents;
    }

    @Autowired(required = false)
    public void useTransactions(PlatformTransactionManager transactions) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setReadOnly(true);
        this.reads = template;
    }

    private <T> T inRead(Supplier<T> read) {
        return this.reads == null ? read.get() : this.reads.execute(status -> read.get());
    }

    /**
     * MIG-424: GET /v1/runs/{id}/result -- a completed run's answer: its final output's rows (at most
     * {@value #RESULT_ROWS}, at most 16 KB of JSON), masked as the workspace's data policy masks data no one person reads.
     * runs:read. A run that has not completed is a 409 (run-not-completed); another workspace's run a 404.
     */
    public CustomerAnswer result(String runId) {
        return this.result(runId, null, null, null);
    }

    /**
     * MIG-426: GET /v1/runs/{id}/result in the format the client chose -- {@code format} (json, pdf, html, md) or the Accept
     * header, json when neither says ({@link ResultFormat#choose}; anything else a 400 naming the allowed ones). pdf, html
     * and md are the run's report, or with none its result rows ({@link RunDocuments}); {@code report} names one report
     * (RPT-0093) of a run that made several.
     */
    public CustomerAnswer result(String runId, String format, String accept, String report) {
        String instance = "/v1/runs/" + runId + "/result";
        if (!TenantContext.hasScope(ApiScopes.RUNS_READ)) {
            return CustomerAnswer.problem(CustomerPipelines.insufficient(ApiScopes.RUNS_READ), instance);
        }
        ResultFormat chosen;
        try {
            chosen = ResultFormat.choose(format, accept);
        } catch (IllegalArgumentException unsupported) {
            return CustomerAnswer.problem(Problem.of(400, unsupported.getMessage()).kind("unsupported-format"), instance);
        }
        if (report != null && !report.trim().isEmpty() && !RunDocuments.REPORT_LABEL.matcher(report.trim()).matches()) {
            return CustomerAnswer.problem(Problem.of(400, RunDocuments.REPORT_REFUSAL), instance);
        }
        Optional<Found> found = this.inRead(() -> this.found(runId));
        if (!found.isPresent()) {
            return CustomerAnswer.problem(Problem.of(404, NO_SUCH_RUN), instance);
        }
        String status = RunIntake.statusOf(found.get().row.jobStatus);
        if (!"completed".equals(status)) {
            return CustomerAnswer.problem(Problem.of(409, "The run has not completed (" + status + "), so it has no result yet.")
                .kind("run-not-completed"), instance);
        }
        if (chosen.document()) {
            return this.documentOf(found.get(), chosen, report == null || report.trim().isEmpty() ? null : report.trim(), instance);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", String.valueOf(found.get().row.runId));
        body.put("pipelineId", String.valueOf(found.get().row.jobId));
        body.put("status", status);
        body.put("result", this.inRead(() -> this.resultOf(found.get())));
        return CustomerAnswer.of(200, body, null);
    }

    /**
     * MIG-424: a completed run's answer as the API gives it -- {step, columns, rowCount, rows, truncated, masked, problem}; null
     * for a run that has not completed. Its output past retention, or none kept, is said in {@code problem}.
     */
    Map<String, Object> resultOf(Found found) {
        if (!"completed".equals(RunIntake.statusOf(found.row.jobStatus)) || this.results == null) {
            return null;
        }
        RunResults.Selection all = new RunResults.Selection();
        all.take = "all";
        all.maxRows = RESULT_ROWS;
        Map<String, Object> out;
        try {
            PipelineDefinition definition = this.steps.pinnedDefinition(found.row.runId).flatMap(this.definitions::byId)
                .map(PipelineDefinitionStore.Stored::definition).orElse(null);
            ItemMasks shown = this.masks == null ? ItemMasks.forWorkspace(null, false) : this.masks.of(found.row.tenantId, definition);
            RunResults.Result result = this.results.read(found.row.runId, all, shown);
            out = new LinkedHashMap<>();
            out.put("step", result.step);
            out.put("columns", result.columns);
            out.put("rowCount", result.rowCount);
            out.put("rows", result.rows);
            out.put("truncated", result.truncated);
            out.put("masked", new ArrayList<>(result.masked));
            out.put("problem", result.problem);
        } catch (Exception unreadable) {
            logger.warn("Run {}'s result could not be read for the API: {}", found.row.runId, unreadable.getMessage());
            out = new LinkedHashMap<>();
            out.put("step", null);
            out.put("columns", Collections.emptyList());
            out.put("rowCount", 0);
            out.put("rows", Collections.emptyList());
            out.put("truncated", false);
            out.put("masked", Collections.emptyList());
            out.put("problem", "The run's output could not be read.");
        }
        return out;
    }

    /** MIG-426: a completed run's result as a document, or its problem, about {@code instance}. */
    private CustomerAnswer documentOf(Found found, ResultFormat format, String report, String instance) {
        if (this.documents == null) {
            return CustomerAnswer.problem(Problem.of(503, "The run's result cannot be given as " + format.word + " here."), instance);
        }
        return CustomerAnswer.of(this.documents.document(found.row.tenantId, found.row.runId, format, report,
            () -> this.inRead(() -> this.resultOf(found)), TenantContext.getClientId(), "api"), instance);
    }

    /**
     * MIG-426: run-and-wait's answer in a document format, once the run has ended -- what GET /v1/runs/{id}/result gives in
     * that format (the document, or its problem: run-not-completed for a run that failed); its own read-only transaction.
     */
    public CustomerAnswer resultAs(String runId, ResultFormat format, String report) {
        String instance = "/v1/runs/" + runId + "/result";
        Optional<Found> found = this.inRead(() -> this.found(runId));
        if (!found.isPresent()) {
            return CustomerAnswer.problem(Problem.of(404, NO_SUCH_RUN), instance);
        }
        String status = RunIntake.statusOf(found.get().row.jobStatus);
        if (!"completed".equals(status)) {
            return CustomerAnswer.problem(Problem.of(409, "The run has not completed (" + status + "), so it has no result.")
                .kind("run-not-completed"), instance);
        }
        return this.documentOf(found.get(), format, report, instance);
    }

    /**
     * MIG-426: a run of a workspace's result in each of {@code formats}, for a webhook's delivery (no request: the event's
     * workspace); json is the result as GET /v1/runs/{id}/result gives it, every other format its document or its problem.
     * Empty when the run is not there.
     */
    public Optional<Map<ResultFormat, Object>> resultsFor(long tenantId, long runId, List<ResultFormat> formats, String report, String who,
        String via) {
        Optional<Found> found = this.inRead(() -> this.found(tenantId, runId));
        if (!found.isPresent()) {
            return Optional.empty();
        }
        Map<ResultFormat, Object> out = new LinkedHashMap<>();
        boolean completed = "completed".equals(RunIntake.statusOf(found.get().row.jobStatus));
        Map<String, Object> rows = completed ? this.inRead(() -> this.resultOf(found.get())) : null;
        for (ResultFormat format : formats) {
            if (!completed) {
                out.put(format, RunDocuments.Outcome.refused(Problem.of(409, "The run has not completed, so it has no result.")
                    .kind("run-not-completed")));
            } else if (!format.document()) {
                out.put(format, rows);
            } else if (this.documents == null) {
                out.put(format, RunDocuments.Outcome.refused(Problem.of(503, "The run's result cannot be given as " + format.word + " here.")));
            } else {
                out.put(format, this.documents.document(tenantId, runId, format, report, () -> rows, who, via));
            }
        }
        return Optional.of(out);
    }

    /**
     * MIG-424: the run as GET /v1/runs/{id} answers it, with its result when it has completed; empty when it is not there.
     * For run-and-wait: read in its own read-only transaction each time it is asked.
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> runWithResult(String runId) {
        Optional<Found> found = this.found(runId);
        if (!found.isPresent()) {
            return Optional.empty();
        }
        Map<String, Object> summary = this.reviews.summary(found.get().run, found.get().job);
        Map<String, Object> view = new LinkedHashMap<>(CustomerViews.run(found.get().row, CustomerViews.reviewWordOf(summary.get("reviewStatus"))));
        view.put("result", this.resultOf(found.get()));
        return Optional.of(view);
    }

    /** A run of the caller's workspace, with its entities (for the review), or empty. */
    Optional<Found> found(String runId) {
        long id;
        try {
            id = Long.parseLong(runId);
        } catch (NumberFormatException notOurs) {
            return Optional.empty();
        }
        return this.found(TenantContext.getTenantId(), id);
    }

    /** A run of this workspace, with its entities, or empty -- for a caller with no request (the event relay, MIG-333). */
    Optional<Found> found(long tenantId, long id) {
        Optional<CustomerRunStore.Row> row = this.store.find(tenantId, id);
        if (!row.isPresent()) {
            return Optional.empty();
        }
        Optional<JobQueue> run = this.runs.findById(id).filter(r -> r.getTenantId() != null && r.getTenantId() == tenantId);
        Optional<SourceJob> job = this.jobs.findById(row.get().jobId).filter(j -> j.getTenantId() != null && j.getTenantId() == tenantId);
        if (!run.isPresent() || !job.isPresent()) {
            return Optional.empty();
        }
        return Optional.of(new Found(row.get(), run.get(), job.get()));
    }

    /** A run as the API reads it and as the review reads it. */
    static final class Found {
        final CustomerRunStore.Row row;
        final JobQueue run;
        final SourceJob job;

        Found(CustomerRunStore.Row row, JobQueue run, SourceJob job) {
            this.row = row;
            this.run = run;
            this.job = job;
        }
    }

    /** Each run's review status word, for a page: the run's own review, as GET /v1/runs/{id} answers it. */
    private Map<Long, String> reviewWords(List<CustomerRunStore.Row> rows) {
        Map<Long, String> words = new HashMap<>();
        if (rows.isEmpty()) {
            return words;
        }
        List<Long> runIds = new ArrayList<>();
        List<Long> jobIds = new ArrayList<>();
        for (CustomerRunStore.Row row : rows) {
            runIds.add(row.runId);
            jobIds.add(row.jobId);
        }
        Map<Long, SourceJob> jobsById = new HashMap<>();
        this.jobs.findAllById(jobIds).forEach(job -> jobsById.put(job.getJobId(), job));
        List<JobQueue> page = new ArrayList<>();
        this.runs.findAllById(runIds).forEach(page::add);
        // MIG-326: the whole page's reviews in a few queries, not four a run.
        this.reviews.statuses(page, jobsById).forEach((runId, status) -> words.put(runId, CustomerViews.reviewWordOf(status.name())));
        return words;
    }

    /** The steps' names, from the definition the run follows; empty when it follows none. */
    private Map<String, String> stepNames(long runId) {
        Map<String, String> names = new HashMap<>();
        try {
            Optional<PipelineDefinitionStore.Stored> stored = this.steps.pinnedDefinition(runId).flatMap(this.definitions::byId);
            if (stored.isPresent() && stored.get().definition().getSteps() != null) {
                for (PipelineDefinition.Step step : stored.get().definition().getSteps()) {
                    if (step != null && step.getKey() != null && step.getName() != null) {
                        names.put(step.getKey(), step.getName());
                    }
                }
            }
        } catch (RuntimeException unreadable) {
            logger.warn("Run {}: its definition's step names cannot be read: {}", runId, unreadable.getMessage());
        }
        return names;
    }

    private static Map<String, Object> step(StepStore.StepRow row, String name, Integer reusedFromAttempt) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("key", row.stepKey);
        step.put("name", name);
        step.put("task", row.taskCode);
        step.put("status", CustomerViews.stepStatusOf(row.status));
        step.put("rowsIn", row.recordsIn);
        step.put("rowsOut", row.recordsOut);
        step.put("startedAt", ApiTimes.utc(row.startedAt));
        step.put("endedAt", ApiTimes.utc(row.endedAt));
        step.put("durationMs", row.durationMs);
        step.put("message", CustomerViews.cut(row.statusMessage));
        step.put("reusedFromAttempt", reusedFromAttempt);
        return step;
    }

    /** A run of a pipeline without steps, as its one step: the run's own status, times and status line. */
    private static Map<String, Object> legacyStep(CustomerRunStore.Row run) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("key", PipelineDefinition.LEGACY_KEY);
        step.put("name", null);
        step.put("task", PipelineDefinition.LEGACY_TASK);
        step.put("status", legacyStatusOf(RunIntake.statusOf(run.jobStatus)));
        step.put("rowsIn", null);
        step.put("rowsOut", null);
        step.put("startedAt", ApiTimes.utc(run.startedAt));
        step.put("endedAt", ApiTimes.utc(run.endedAt));
        step.put("durationMs", run.startedAt == null || run.endedAt == null ? null : Duration.between(run.startedAt, run.endedAt).toMillis());
        step.put("message", CustomerViews.cut(run.message));
        step.put("reusedFromAttempt", null);
        return step;
    }

    /** The run's status word as a step's: a queued run's one step is pending. */
    static String legacyStatusOf(String runStatus) {
        return "queued".equals(runStatus) ? "pending" : runStatus;
    }
}

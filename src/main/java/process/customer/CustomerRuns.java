package process.customer;

import org.barco.platform.api.ApiTimes;
import org.barco.platform.api.Cursors;
import org.barco.platform.api.Problem;
import org.barco.platform.security.ApiScopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.pipeline.PipelineDefinition;
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
            for (StepStore.StepRow row : rows) {
                if (row.attempt == attempt) {
                    data.add(step(row, names.get(row.stepKey)));
                }
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", String.valueOf(run.runId));
        body.put("attempt", attempt);
        body.put("data", data);
        return CustomerAnswer.of(200, body, null);
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
        List<StepStore.OutputRow> outputs = this.steps.outputsOfRun(run.runId);
        int attempt = run.attempt;
        for (StepStore.StepRow row : this.steps.stepsOfRun(run.runId)) {
            attempt = Math.max(attempt, row.attempt);
        }
        Instant now = Instant.now();
        for (StepStore.OutputRow output : outputs) {
            if (output.attempt != attempt) {
                continue;
            }
            String fileId = output.fileId != null ? output.fileId : this.steps.fileIdOf(output.runOutputId);
            if (fileId != null) {
                listed.add(CustomerViews.madeFile(output, fileId, now));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", String.valueOf(run.runId));
        body.put("pipelineId", String.valueOf(run.jobId));
        body.put("status", RunIntake.statusOf(run.jobStatus));
        body.put("attempt", attempt);
        body.put("generatedAt", ApiTimes.utc(now));
        body.put("files", listed);
        body.put("review", CustomerViews.review(this.reviews.summary(found.get().run, found.get().job)));
        return CustomerAnswer.of(200, body, null);
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
        for (JobQueue run : this.runs.findAllById(runIds)) {
            SourceJob job = jobsById.get(run.getJobId());
            if (job != null) {
                words.put(run.getJobQueueId(), CustomerViews.reviewWordOf(this.reviews.summary(run, job).get("reviewStatus")));
            }
        }
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

    private static Map<String, Object> step(StepStore.StepRow row, String name) {
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
        return step;
    }

    /** The run's status word as a step's: a queued run's one step is pending. */
    static String legacyStatusOf(String runStatus) {
        return "queued".equals(runStatus) ? "pending" : runStatus;
    }
}

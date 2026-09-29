package process.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.ai.ModelChoiceStore;
import process.ai.RunAiStep;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.security.JobOwnership;
import process.util.BusinessTime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

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

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JobQueueRepository runs;
    private final SourceJobRepository jobs;
    private final StepStore steps;
    private final ModelChoiceStore aiSteps;

    public StepTimelineService(JobQueueRepository runs, SourceJobRepository jobs, StepStore steps, ModelChoiceStore aiSteps) {
        this.runs = runs;
        this.jobs = jobs;
        this.steps = steps;
        this.aiSteps = aiSteps;
    }

    /** The timeline of one attempt of a run: the latest when none is named. */
    @Transactional(readOnly = true)
    public ResponseDto timeline(Long jobQueueId, Integer attempt) {
        Optional<JobQueue> run = this.owned(jobQueueId);
        if (!run.isPresent()) {
            return new ResponseDto(ERROR, RUN_NOT_FOUND);
        }
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
            timeline.put("pipelineDefinitionId", ofAttempt.isEmpty() ? null : ofAttempt.get(0).pipelineDefinitionId);
            timeline.put("steps", ofAttempt.stream().map(row -> step(row, datasets.getOrDefault(row.stepExecutionId,
                Collections.emptyList()))).collect(Collectors.toList()));
        }
        timeline.put("aiSteps", ai.stream().filter(step -> (step.attempt == null ? 1 : step.attempt) == shown).collect(Collectors.toList()));
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

    private Optional<JobQueue> owned(Long jobQueueId) {
        if (jobQueueId == null) {
            return Optional.empty();
        }
        Optional<JobQueue> run = this.runs.findById(jobQueueId);
        Optional<SourceJob> job = run.flatMap(r -> r.getJobId() == null ? Optional.empty() : this.jobs.findById(r.getJobId()));
        if (!run.isPresent() || !job.isPresent() || !JobOwnership.isVisibleToCaller(job.get())
            || Status.Delete.equals(job.get().getJobStatus()) || !Objects.equals(run.get().getTenantId(), job.get().getTenantId())) {
            return Optional.empty();
        }
        return run;
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

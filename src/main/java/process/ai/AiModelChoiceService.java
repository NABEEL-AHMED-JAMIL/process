package process.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.AiModelChoiceDto;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobDto;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.Pipeline;
import process.model.pojo.PipelineField;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.repository.JobQueueRepository;
import process.model.repository.PipelineRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.service.SourceJobService;
import process.security.JobOwnership;
import process.security.TenantContext;
import process.security.TenantOwnership;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * Core's side of choosing the AI model at run time (Wave 4, MIG-242's Core part). ai-service keeps the models each AI
 * step may run on and decides, at run time, whether a run may have the one it asks for; Core owns the jobs, schedules
 * and pipelines, so it owns who may ask:
 *
 * <ul>
 *   <li>a schedule's setting -- per AI step of the job's pipeline, the model option its runs ask for;</li>
 *   <li>"Run with..." -- the same for one run made by hand, over the schedule's for the steps it names;</li>
 *   <li>a pipeline step's own allowed list -- edited here, by whoever may edit the source task, and kept by ai-service
 *       (Core checks the caller and the step, then asks ai-service's internal endpoint to write it);</li>
 *   <li>what each AI step of a run asked for and ran on (run_ai_step), for the run's manifest.</li>
 * </ul>
 *
 * A job is the caller's when they may see it (JobOwnership: the workspace, and for a tenant user the jobs that name
 * them); a source task when it is their workspace's. Anything else reads as not found. An option is accepted only when
 * ai-service lists it for that step in the job's own workspace, on an active connection -- so another workspace's
 * option id is refused here, before any run, with the same words as any other option off the list. ai-service checks
 * again when the run asks (a list can change in between) and refuses with 422, which fails the step.
 */
@Service
public class AiModelChoiceService {

    static final String JOB_NOT_FOUND = "SourceJob not found with jobId.";

    static final String TASK_NOT_FOUND = "SourceTask not found with taskDetailId.";

    static final String RUN_NOT_FOUND = "Run not found with jobQueueId.";

    static final String AI_UNREACHABLE = "The AI service could not confirm the models this step may run on, so nothing was changed. "
        + "Try again in a moment.";

    private final Logger logger = LoggerFactory.getLogger(AiModelChoiceService.class);

    private final SourceJobRepository jobs;
    private final SourceTaskRepository tasks;
    private final JobQueueRepository runs;
    private final PipelineRepository pipelines;
    private final AiPort ai;
    private final ModelChoiceStore store;
    private final SourceJobService sourceJobs;

    public AiModelChoiceService(SourceJobRepository jobs, SourceTaskRepository tasks, JobQueueRepository runs, PipelineRepository pipelines,
        AiPort ai, ModelChoiceStore store, SourceJobService sourceJobs) {
        this.jobs = jobs;
        this.tasks = tasks;
        this.runs = runs;
        this.pipelines = pipelines;
        this.ai = ai;
        this.store = store;
        this.sourceJobs = sourceJobs;
    }

    /**
     * The job's AI steps, each with its schedule setting (modelOptionId, absent for the step's default) and the models
     * it may run on -- what the schedule setting and the "Run with..." picker offer. When ai-service cannot be asked,
     * a step carries optionsError instead of options.
     */
    @Transactional(readOnly = true)
    public ResponseDto jobChoices(Long jobId) {
        Optional<SourceJob> job = this.visibleJob(jobId);
        if (!job.isPresent()) {
            return new ResponseDto(ERROR, JOB_NOT_FOUND);
        }
        SourceJob j = job.get();
        Map<String, String> schedule = ModelProfiles.read(this.store.scheduleProfiles(j.getJobId(), j.getTenantId()));
        List<Map<String, Object>> steps = new ArrayList<>();
        for (PipelineField field : this.aiStepsOf(j.getTaskDetail(), j.getTenantId())) {
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("stepKey", field.getTagKey());
            step.put("label", field.getLabel());
            step.put("runIn", "worker".equals(field.getRunIn()) ? RunAiStep.WORKER : RunAiStep.SERVER);
            step.put("promptId", field.getPromptId());
            if (schedule.containsKey(field.getTagKey())) {
                step.put("modelOptionId", schedule.get(field.getTagKey()));
            }
            try {
                step.put("options", this.ai.stepModelOptions(j.getTenantId(), taskIdOf(j), field.getTagKey(), field.getPromptId()));
            } catch (AiPort.AiUnavailableException ex) {
                this.logger.warn("Job {}: the models of step <{}> could not be read: {}", j.getJobId(), field.getTagKey(), ex.getMessage());
                step.put("optionsError", "The AI service could not be reached to list this step's models.");
            }
            steps.add(step);
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("jobId", j.getJobId());
        answer.put("taskDetailId", taskIdOf(j));
        answer.put("steps", steps);
        return new ResponseDto(SUCCESS, String.format("%d AI step(s).", steps.size()), answer);
    }

    /**
     * Sets the schedule's model per step, replacing the whole setting: a step not sent, or sent blank, runs on its
     * default. Nothing is saved unless every choice is one ai-service allows for its step in the job's workspace.
     */
    @Transactional
    public ResponseDto saveSchedule(AiModelChoiceDto dto) {
        Optional<SourceJob> job = dto == null ? Optional.empty() : this.visibleJob(dto.getJobId());
        if (!job.isPresent()) {
            return new ResponseDto(ERROR, JOB_NOT_FOUND);
        }
        Checked checked = this.check(job.get(), dto.getSteps());
        if (checked.refusal != null) {
            return new ResponseDto(ERROR, checked.refusal);
        }
        this.store.saveScheduleProfiles(job.get().getJobId(), job.get().getTenantId(), ModelProfiles.write(checked.byStep),
            TenantContext.getAppUserId());
        return new ResponseDto(SUCCESS, checked.byStep.isEmpty() ? "Every AI step of this job runs on its default model."
            : String.format("The job's schedule runs %d AI step(s) on the chosen model.", checked.byStep.size()), checked.byStep);
    }

    /**
     * "Run with...": runs the job now, as Run now does, with these steps on these models for this run only. The same
     * checks as the schedule setting, then every check Run now makes.
     */
    @Transactional
    public ResponseDto runWith(AiModelChoiceDto dto) throws Exception {
        Optional<SourceJob> job = dto == null ? Optional.empty() : this.visibleJob(dto.getJobId());
        if (!job.isPresent()) {
            return new ResponseDto(ERROR, JOB_NOT_FOUND);
        }
        Checked checked = this.check(job.get(), dto.getSteps());
        if (checked.refusal != null) {
            return new ResponseDto(ERROR, checked.refusal);
        }
        SourceJobDto run = new SourceJobDto();
        run.setJobId(job.get().getJobId());
        return this.sourceJobs.runSourceJob(run, ModelProfiles.write(checked.byStep));
    }

    /** What each AI step of a run asked for and ran on, every attempt: the run's manifest of models and prompt versions. */
    @Transactional(readOnly = true)
    public ResponseDto runSteps(Long jobQueueId) {
        Optional<JobQueue> run = jobQueueId == null ? Optional.empty() : this.runs.findById(jobQueueId);
        Optional<SourceJob> job = run.flatMap(r -> this.jobs.findById(r.getJobId()));
        if (!run.isPresent() || !job.isPresent() || !JobOwnership.isVisibleToCaller(job.get())
            || !Objects.equals(run.get().getTenantId(), job.get().getTenantId())) {
            return new ResponseDto(ERROR, RUN_NOT_FOUND);
        }
        List<RunAiStep> steps = this.store.stepsOfRun(jobQueueId);
        return new ResponseDto(SUCCESS, String.format("%d AI step(s).", steps.size()), steps);
    }

    /** The models a pipeline step may run on -- its own list when it has one, else its prompt's -- for its editor. */
    @Transactional(readOnly = true)
    public ResponseDto stepOptions(Long taskDetailId, String stepKey) {
        Optional<SourceTask> task = this.ownedTask(taskDetailId);
        if (!task.isPresent()) {
            return new ResponseDto(ERROR, TASK_NOT_FOUND);
        }
        Optional<PipelineField> step = this.stepOf(task.get(), stepKey);
        if (!step.isPresent()) {
            return new ResponseDto(ERROR, String.format("This task's pipeline has no AI step <%s>.", stepKey));
        }
        try {
            return new ResponseDto(SUCCESS, "The models this step may run on.", this.ai.stepModelOptions(task.get().getTenantId(),
                task.get().getTaskDetailId(), step.get().getTagKey(), step.get().getPromptId()));
        } catch (AiPort.AiUnavailableException ex) {
            this.logger.warn("Task {}: the models of step <{}> could not be read: {}", taskDetailId, stepKey, ex.getMessage());
            return new ResponseDto(ERROR, "The AI service could not be reached to list this step's models.");
        }
    }

    /**
     * Replaces a pipeline step's own allowed list (empty: back to its prompt's). Core decides who may -- whoever may
     * edit the source task -- and that the step is an AI step of its pipeline; ai-service keeps the list and checks the
     * connections are the workspace's or the platform's, active, each listed once, at most one default.
     */
    @Transactional(readOnly = true)
    public ResponseDto saveStepOptions(AiModelChoiceDto.StepOptions dto) {
        Optional<SourceTask> task = dto == null ? Optional.empty() : this.ownedTask(dto.getTaskDetailId());
        if (!task.isPresent()) {
            return new ResponseDto(ERROR, TASK_NOT_FOUND);
        }
        Optional<PipelineField> step = this.stepOf(task.get(), dto.getStepKey());
        if (!step.isPresent()) {
            return new ResponseDto(ERROR, String.format("This task's pipeline has no AI step <%s>.", dto.getStepKey()));
        }
        List<AiPort.ModelOption> options = dto.getOptions() == null ? Collections.emptyList() : dto.getOptions();
        int defaults = 0;
        for (AiPort.ModelOption o : options) {
            if (o == null || o.connectionId == null) {
                return new ResponseDto(ERROR, "Every allowed model names a model connection.");
            }
            if (Boolean.TRUE.equals(o.isDefault) && ++defaults > 1) {
                return new ResponseDto(ERROR, "Only one allowed model can be the default.");
            }
        }
        try {
            return this.ai.saveStepModelOptions(task.get().getTenantId(), task.get().getTaskDetailId(), step.get().getTagKey(),
                step.get().getPromptId(), options, TenantContext.getAppUserId());
        } catch (AiPort.AiUnavailableException ex) {
            this.logger.warn("Task {}: the models of step <{}> could not be saved: {}", dto.getTaskDetailId(), dto.getStepKey(), ex.getMessage());
            return new ResponseDto(ERROR, "The AI service could not be reached, so the step's models were not changed.");
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    /** The steps' choices, checked: step tag to option id, or why not. */
    private static final class Checked {
        final Map<String, String> byStep = new TreeMap<>();
        String refusal;
    }

    /**
     * Each step named once, an AI step of the job's pipeline, and its option -- when one is named -- an id ai-service
     * lists for that step in the job's workspace, on an active connection. An option of another workspace's is simply
     * not on the list, and is answered in the same words as any other.
     */
    private Checked check(SourceJob job, List<AiModelChoiceDto.StepChoice> choices) {
        Checked checked = new Checked();
        Map<String, PipelineField> steps = new LinkedHashMap<>();
        for (PipelineField field : this.aiStepsOf(job.getTaskDetail(), job.getTenantId())) {
            steps.put(field.getTagKey(), field);
        }
        List<String> seen = new ArrayList<>();
        for (AiModelChoiceDto.StepChoice choice : choices == null ? Collections.<AiModelChoiceDto.StepChoice>emptyList() : choices) {
            String key = choice == null || choice.getStepKey() == null ? null : choice.getStepKey().trim();
            if (key == null || !steps.containsKey(key)) {
                checked.refusal = String.format("This job's pipeline has no AI step <%s>.", key);
                return checked;
            }
            if (seen.contains(key)) {
                checked.refusal = String.format("AI step <%s> is named twice.", key);
                return checked;
            }
            seen.add(key);
            String option = choice.getModelOptionId() == null ? "" : choice.getModelOptionId().trim();
            if (option.isEmpty()) {
                continue;
            }
            String notAllowed = String.format("That model is not one AI step <%s> may run on. Pick one from the step's allowed models.", key);
            if (!ModelProfiles.isOptionId(option)) {
                checked.refusal = notAllowed;
                return checked;
            }
            PipelineField field = steps.get(key);
            List<AiPort.ModelOption> allowed;
            try {
                allowed = this.ai.stepModelOptions(job.getTenantId(), taskIdOf(job), key, field.getPromptId());
            } catch (AiPort.AiUnavailableException ex) {
                this.logger.warn("Job {}: the models of step <{}> could not be read: {}", job.getJobId(), key, ex.getMessage());
                checked.refusal = AI_UNREACHABLE;
                return checked;
            }
            Optional<AiPort.ModelOption> listed = allowed.stream()
                .filter(o -> o.modelOptionId != null && option.equals(String.valueOf(o.modelOptionId))).findFirst();
            if (!listed.isPresent()) {
                this.logger.warn("Job {}: refused model option <{}> for step <{}>: not on the step's list in workspace {}.",
                    job.getJobId(), option, key, job.getTenantId());
                checked.refusal = notAllowed;
                return checked;
            }
            if (!Boolean.TRUE.equals(listed.get().connectionActive)) {
                checked.refusal = String.format("That model's connection is not active, so AI step <%s> cannot run on it. "
                    + "Pick another, or activate the connection.", key);
                return checked;
            }
            checked.byStep.put(key, option);
        }
        return checked;
    }

    private Optional<SourceJob> visibleJob(Long jobId) {
        if (jobId == null) {
            return Optional.empty();
        }
        return this.jobs.findByJobIdAndJobStatus(jobId, Status.Active).filter(JobOwnership::isVisibleToCaller);
    }

    private Optional<SourceTask> ownedTask(Long taskDetailId) {
        if (taskDetailId == null) {
            return Optional.empty();
        }
        return this.tasks.findByTaskDetailIdAndTaskStatus(taskDetailId, Status.Active)
            .filter(t -> TenantOwnership.isOwnedByCaller(t.getTenantId()));
    }

    private Optional<PipelineField> stepOf(SourceTask task, String stepKey) {
        String key = stepKey == null ? null : stepKey.trim();
        return this.aiStepsOf(task, task.getTenantId()).stream().filter(f -> Objects.equals(f.getTagKey(), key)).findFirst();
    }

    /** The AI steps of the task's pipeline, in the task's workspace; none without a pipeline. */
    private List<PipelineField> aiStepsOf(SourceTask task, Long tenantId) {
        if (task == null || task.getPipelineId() == null || task.getPipelineId().trim().isEmpty() || tenantId == null) {
            return Collections.emptyList();
        }
        List<Pipeline> found = this.pipelines.findAllByPipelineIdAndTenantIdAndStatusNot(task.getPipelineId().trim(), tenantId, Status.Delete);
        return found.isEmpty() ? Collections.emptyList() : AiStepService.stepsOf(found.get(0));
    }

    private static Long taskIdOf(SourceJob job) {
        return job.getTaskDetail() == null ? null : job.getTaskDetail().getTaskDetailId();
    }
}

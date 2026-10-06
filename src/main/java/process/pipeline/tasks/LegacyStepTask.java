package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.PipelineDefinition;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.StepTask;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An existing pipeline (a PIPELINE_TASKS entry on the worker) as one step (MIG-230). It is not run by the step engine:
 * a run whose definition is this one step takes today's path unchanged -- the pre-dispatch phase (with the pipeline's
 * AI steps), the dispatch of the task's XML payload to its worker over Kafka, the worker's callbacks through
 * NotifyServiceImpl, retries through BulkAction.scheduleRetry, the run's callback token. The code exists so a
 * definition can say so, and the Task Registry can list the legacy pipelines beside the new tasks.
 *
 * It is the only step of its definition: a legacy step cannot be chained, because what it does after the hand-off is
 * the worker's, and the worker reports the run's end, not a step's.
 */
@Component
public class LegacyStepTask implements StepTask {

    /**
     * The registry's Legacy entry (MIG-231): not overridable, never unavailable -- every existing pipeline stays
     * runnable. Its retry and timeout are the job's and the worker's, not the engine's.
     */
    static final TaskSpec SPEC = TaskSpec.builder(PipelineDefinition.LEGACY_TASK, "Legacy pipeline", TaskKind.LEGACY)
        .description("An existing pipeline, run by its worker exactly as before (the task's XML payload over Kafka).")
        .input(TaskSpec.rows("The job's task payload, handed to the worker as it is today."))
        .output(null)
        .config(JsonSchema.object()
            .property("pipelineId", JsonSchema.string().title("Pipeline").format("pipeline")
                .description("The existing pipeline (its pipelineId on the worker) this step is.")))
        .backing(TaskSpec.WORKER)
        .overridable(false)
        .aiToolName("run_legacy_pipeline")
        .build();

    @Override
    public TaskSpec spec() {
        return SPEC;
    }

    @Override
    public String code() {
        return PipelineDefinition.LEGACY_TASK;
    }

    @Override
    public String description() {
        return "An existing pipeline, run by its worker exactly as before (the task's XML payload over Kafka).";
    }

    @Override
    public boolean runsInEngine() {
        return false;
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        Configs.onlyKnownKeys(config, problems, "pipelineId");
        Object pipelineId = config.get("pipelineId");
        if (pipelineId != null && !(pipelineId instanceof String)) {
            problems.add(new DefinitionProblem("pipelineId", "the pipeline id is text"));
        }
        return problems;
    }

    @Override
    public StepResult run(StepContext context) {
        throw new IllegalStateException("A legacy step is run by its worker, never by the step engine.");
    }
}

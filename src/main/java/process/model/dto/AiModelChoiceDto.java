package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import process.ai.AiPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What the console sends to aiModelChoice.json (MIG-242's Core part): a job's model per AI step -- its schedule's
 * setting, or one run's "Run with..." -- and a pipeline step's own allowed list.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiModelChoiceDto {

    /** One AI step and the ai-service model option it runs on; a blank option is the step's default. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StepChoice {

        private String stepKey;

        private String modelOptionId;

        public StepChoice() {}

        public StepChoice(String stepKey, String modelOptionId) {
            this.stepKey = stepKey;
            this.modelOptionId = modelOptionId;
        }

        public String getStepKey() { return stepKey; }

        public void setStepKey(String stepKey) { this.stepKey = stepKey; }

        public String getModelOptionId() { return modelOptionId; }

        public void setModelOptionId(String modelOptionId) { this.modelOptionId = modelOptionId; }
    }

    /** A pipeline step's own allowed list: the source task, the step (its tag) and the models, at most one default. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StepOptions {

        private Long taskDetailId;

        private String stepKey;

        private List<AiPort.ModelOption> options = new ArrayList<>();

        public Long getTaskDetailId() { return taskDetailId; }

        public void setTaskDetailId(Long taskDetailId) { this.taskDetailId = taskDetailId; }

        public String getStepKey() { return stepKey; }

        public void setStepKey(String stepKey) { this.stepKey = stepKey; }

        public List<AiPort.ModelOption> getOptions() { return options; }

        public void setOptions(List<AiPort.ModelOption> options) { this.options = options; }
    }

    private Long jobId;

    private List<StepChoice> steps = new ArrayList<>();

    /** MIG-347: "Run with..."'s parameter values by name (checked against the pipeline's parameters); null for the defaults. */
    private Map<String, Object> parameters;

    public Map<String, Object> getParameters() { return parameters; }

    public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }

    public Long getJobId() { return jobId; }

    public void setJobId(Long jobId) { this.jobId = jobId; }

    public List<StepChoice> getSteps() { return steps; }

    public void setSteps(List<StepChoice> steps) { this.steps = steps; }
}

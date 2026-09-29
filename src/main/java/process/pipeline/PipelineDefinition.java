package process.pipeline;

import java.util.Arrays;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A pipeline as ordered steps (MIG-230): where its input comes from, the steps that run on it in order, and the
 * settings that apply to all of them. Stored as JSON (pipeline_definition.definition); YAML is a view of the same
 * definition ({@link DefinitionCodec}), never a second source of truth.
 *
 * <pre>
 * version: 1
 * source: {type: task}                 # none (default) | task: the job's task payload, one row of its tags
 * steps:
 *   - key: read                        # unique, [a-z][a-z0-9_]*; what step_execution and run_dataset name
 *     task: sample                     # a registered step task (StepTasks)
 *     config: {rows: [...]}            # the task's own settings, checked by the task
 *     retry: {maxAttempts: 2, delaySeconds: 5}
 *     timeoutSeconds: 60
 *     onError: fail                    # fail | continue | skip_rest (OnError)
 *   - key: shape
 *     task: select
 *     input: read                      # an earlier step's output; default: the latest output before this step
 * settings: {datasetRetentionHours: 24, defaultTimeoutSeconds: 600, defaultOnError: fail}
 * </pre>
 *
 * Every existing pipeline is, without a stored definition, {@link #legacy}: one {@code legacy} step that runs today's
 * path unchanged -- the task's XML payload, the dispatch to its worker, the worker's callbacks.
 *
 * Parsing is lenient about values and strict about names: an unknown field is a parse problem, a bad value is a
 * validation problem ({@link DefinitionValidator}), so a person sees every mistake at once, each at its path.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"version", "source", "steps", "settings"})
public class PipelineDefinition {

    public static final int CURRENT_VERSION = 1;
    /** The task code of the one step every pipeline without a stored definition is. */
    public static final String LEGACY_TASK = "legacy";
    public static final String LEGACY_KEY = "legacy";

    private Integer version;
    private Source source;
    private List<Step> steps = new ArrayList<>();
    private Settings settings;

    /** The definition a pipeline without a stored one has: its whole existing path, as one step. */
    public static PipelineDefinition legacy(String pipelineId) {
        PipelineDefinition definition = new PipelineDefinition();
        definition.setVersion(CURRENT_VERSION);
        definition.setSource(Source.of(Source.TASK));
        Step step = new Step();
        step.setKey(LEGACY_KEY);
        step.setName("Legacy pipeline" + (pipelineId == null || pipelineId.trim().isEmpty() ? "" : " " + pipelineId.trim()));
        step.setTask(LEGACY_TASK);
        if (pipelineId != null && !pipelineId.trim().isEmpty()) {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("pipelineId", pipelineId.trim());
            step.setConfig(config);
        }
        definition.getSteps().add(step);
        return definition;
    }

    /** Whether this definition is the legacy wrap: exactly one {@code legacy} step, so the run takes today's path. */
    @JsonIgnore
    public boolean isLegacy() {
        return this.steps != null && this.steps.size() == 1 && LEGACY_TASK.equals(this.steps.get(0).getTask());
    }

    @JsonIgnore
    public Settings effectiveSettings() {
        return this.settings == null ? new Settings() : this.settings;
    }

    @JsonIgnore
    public String effectiveSourceType() {
        return this.source == null || this.source.getType() == null ? Source.NONE : this.source.getType();
    }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }

    public List<Step> getSteps() { return steps; }
    public void setSteps(List<Step> steps) { this.steps = steps == null ? new ArrayList<>() : steps; }

    public Settings getSettings() { return settings; }
    public void setSettings(Settings settings) { this.settings = settings; }

    /** Where the first step's input comes from. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"type", "config"})
    public static class Source {

        /** No input: the first step makes its own (a read step). */
        public static final String NONE = "none";
        /** The job's task payload (its XML), read with the same parser the AI steps use: one row, one column per tag. */
        public static final String TASK = "task";
        public static final List<String> TYPES = Collections.unmodifiableList(Arrays.asList(NONE, TASK));

        private String type;
        private Map<String, Object> config;

        public static Source of(String type) {
            Source source = new Source();
            source.setType(type);
            return source;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public Map<String, Object> getConfig() { return config; }
        public void setConfig(Map<String, Object> config) { this.config = config; }
    }

    /** One step: which task runs, on what, with which settings, and what happens when it fails. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"key", "name", "task", "input", "config", "retry", "timeoutSeconds", "onError"})
    public static class Step {

        private String key;
        private String name;
        private String task;
        private String input;
        private Map<String, Object> config;
        private Retry retry;
        private Integer timeoutSeconds;
        private String onError;

        @JsonIgnore
        public Map<String, Object> effectiveConfig() {
            return this.config == null ? Collections.emptyMap() : this.config;
        }

        @JsonIgnore
        public int effectiveMaxAttempts() {
            return this.retry == null || this.retry.getMaxAttempts() == null ? 1 : this.retry.getMaxAttempts();
        }

        @JsonIgnore
        public int effectiveDelaySeconds() {
            return this.retry == null || this.retry.getDelaySeconds() == null ? 0 : this.retry.getDelaySeconds();
        }

        @JsonIgnore
        public int effectiveTimeoutSeconds(Settings settings) {
            if (this.timeoutSeconds != null) {
                return this.timeoutSeconds;
            }
            return settings == null ? Settings.DEFAULT_TIMEOUT_SECONDS : settings.effectiveTimeoutSeconds();
        }

        @JsonIgnore
        public OnError effectiveOnError(Settings settings) {
            if (this.onError != null) {
                return OnError.of(this.onError).orElse(OnError.FAIL);
            }
            return settings == null ? OnError.FAIL : settings.effectiveOnError();
        }

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getTask() { return task; }
        public void setTask(String task) { this.task = task; }

        public String getInput() { return input; }
        public void setInput(String input) { this.input = input; }

        public Map<String, Object> getConfig() { return config; }
        public void setConfig(Map<String, Object> config) { this.config = config; }

        public Retry getRetry() { return retry; }
        public void setRetry(Retry retry) { this.retry = retry; }

        public Integer getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

        public String getOnError() { return onError; }
        public void setOnError(String onError) { this.onError = onError; }
    }

    /** How often a step is tried within one attempt of its run, and how long it waits between tries. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"maxAttempts", "delaySeconds"})
    public static class Retry {

        private Integer maxAttempts;
        private Integer delaySeconds;

        public static Retry of(int maxAttempts, int delaySeconds) {
            Retry retry = new Retry();
            retry.setMaxAttempts(maxAttempts);
            retry.setDelaySeconds(delaySeconds);
            return retry;
        }

        public Integer getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(Integer maxAttempts) { this.maxAttempts = maxAttempts; }

        public Integer getDelaySeconds() { return delaySeconds; }
        public void setDelaySeconds(Integer delaySeconds) { this.delaySeconds = delaySeconds; }
    }

    /** What applies to every step that does not say otherwise, and how long a run's datasets are kept. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"datasetRetentionHours", "defaultTimeoutSeconds", "defaultOnError"})
    public static class Settings {

        public static final int DEFAULT_RETENTION_HOURS = 24;
        public static final int DEFAULT_TIMEOUT_SECONDS = 600;

        private Integer datasetRetentionHours;
        private Integer defaultTimeoutSeconds;
        private String defaultOnError;

        @JsonIgnore
        public int effectiveRetentionHours() {
            return this.datasetRetentionHours == null ? DEFAULT_RETENTION_HOURS : this.datasetRetentionHours;
        }

        @JsonIgnore
        public int effectiveTimeoutSeconds() {
            return this.defaultTimeoutSeconds == null ? DEFAULT_TIMEOUT_SECONDS : this.defaultTimeoutSeconds;
        }

        @JsonIgnore
        public OnError effectiveOnError() {
            return this.defaultOnError == null ? OnError.FAIL : OnError.of(this.defaultOnError).orElse(OnError.FAIL);
        }

        public Integer getDatasetRetentionHours() { return datasetRetentionHours; }
        public void setDatasetRetentionHours(Integer datasetRetentionHours) { this.datasetRetentionHours = datasetRetentionHours; }

        public Integer getDefaultTimeoutSeconds() { return defaultTimeoutSeconds; }
        public void setDefaultTimeoutSeconds(Integer defaultTimeoutSeconds) { this.defaultTimeoutSeconds = defaultTimeoutSeconds; }

        public String getDefaultOnError() { return defaultOnError; }
        public void setDefaultOnError(String defaultOnError) { this.defaultOnError = defaultOnError; }
    }
}

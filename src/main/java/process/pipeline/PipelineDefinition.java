package process.pipeline;

import java.util.Arrays;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import process.model.enums.ReviewParty;

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
 * settings: {datasetRetentionHours: 24, defaultTimeoutSeconds: 600, defaultOnError: fail,
 *            review: {required: [internal, customer],     # MIG-237: who must approve a run's results; none by default
 *                     reviewers: {kind: group, value: "1063"}}}  # MIG-361: whose Task inbox the internal review goes to
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

        /** Its own retry.maxAttempts, else its task's default (MIG-231's registry entry). */
        @JsonIgnore
        public int effectiveMaxAttempts(int taskDefault) {
            return this.retry == null || this.retry.getMaxAttempts() == null ? Math.max(1, taskDefault) : this.retry.getMaxAttempts();
        }

        /** Its own retry.delaySeconds, else its task's default. */
        @JsonIgnore
        public int effectiveDelaySeconds(int taskDefault) {
            return this.retry == null || this.retry.getDelaySeconds() == null ? Math.max(0, taskDefault) : this.retry.getDelaySeconds();
        }

        /**
         * Its own timeoutSeconds, else the pipeline's settings.defaultTimeoutSeconds when set, else its task's default,
         * else {@value Settings#DEFAULT_TIMEOUT_SECONDS}: what a person wrote wins over what the task suggests.
         */
        @JsonIgnore
        public int effectiveTimeoutSeconds(Settings settings, Integer taskDefault) {
            if (this.timeoutSeconds != null) {
                return this.timeoutSeconds;
            }
            if (settings != null && settings.getDefaultTimeoutSeconds() != null) {
                return settings.getDefaultTimeoutSeconds();
            }
            return taskDefault != null ? taskDefault : Settings.DEFAULT_TIMEOUT_SECONDS;
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
    @JsonPropertyOrder({"datasetRetentionHours", "defaultTimeoutSeconds", "defaultOnError", "review", "sensitivity", "inputContract"})
    public static class Settings {

        public static final int DEFAULT_RETENTION_HOURS = 24;
        public static final int DEFAULT_TIMEOUT_SECONDS = 600;
        public static final String INTERNAL = "internal";
        /** MIG-243: the levels a pipeline's data may be, as the data policies know them. */
        public static final List<String> SENSITIVITIES = Collections.unmodifiableList(Arrays.asList("public", INTERNAL, "sensitive"));

        private Integer datasetRetentionHours;
        private Integer defaultTimeoutSeconds;
        private String defaultOnError;
        private Review review;

        /**
         * MIG-237: the parties that must approve a run's results, INTERNAL before CUSTOMER; none without a review
         * setting. Words the validator refuses are left out, so a run is never held for a party nobody can be.
         */
        @JsonIgnore
        public Set<ReviewParty> requiredReviews() {
            Set<ReviewParty> required = EnumSet.noneOf(ReviewParty.class);
            if (this.review != null && this.review.getRequired() != null) {
                for (String word : this.review.getRequired()) {
                    Review.partyOf(word).ifPresent(required::add);
                }
            }
            return required;
        }

        /** MIG-243: the sensitivity of the pipeline's data -- public, internal or sensitive; not said is internal. */
        private String sensitivity;

        /**
         * MIG-332: the data contract (MIG-233) a run's record must meet when the customer API starts it
         * (POST /v1/pipelines/{id}/runs): checked before anything is stored, and refused with each field's path. Absent: the
         * contract of the pipeline's first validate step, if it has one; else none.
         */
        private ContractRef inputContract;

        public ContractRef getInputContract() { return inputContract; }

        public void setInputContract(ContractRef inputContract) { this.inputContract = inputContract; }

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

        /** MIG-243: the level the data policy's retention is read for: the one said, or internal. */
        @JsonIgnore
        public String effectiveSensitivity() {
            return this.sensitivity == null ? INTERNAL : this.sensitivity;
        }

        public String getSensitivity() { return sensitivity; }

        public void setSensitivity(String sensitivity) { this.sensitivity = sensitivity; }

        public String getDefaultOnError() { return defaultOnError; }

        public void setDefaultOnError(String defaultOnError) { this.defaultOnError = defaultOnError; }

        public Review getReview() { return review; }

        public void setReview(Review review) { this.review = review; }
    }

    /** MIG-332: a data contract named by its id or by its name, optionally pinned to a version. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"contractId", "contractName", "version"})
    public static class ContractRef {

        private Long contractId;
        private String contractName;
        private Integer version;

        public static ContractRef of(Long contractId, String contractName, Integer version) {
            ContractRef ref = new ContractRef();
            ref.setContractId(contractId);
            ref.setContractName(contractName);
            ref.setVersion(version);
            return ref;
        }

        public Long getContractId() { return contractId; }

        public void setContractId(Long contractId) { this.contractId = contractId; }

        public String getContractName() { return contractName; }

        public void setContractName(String contractName) { this.contractName = contractName; }

        public Integer getVersion() { return version; }

        public void setVersion(Integer version) { this.version = version; }
    }

    /**
     * MIG-237: who reviews a run's results -- {@code required: [internal]}, {@code [customer]}, {@code [internal,
     * customer]} or {@code []}. Absent means [] (nobody: the run's review status is NOT_REQUIRED). A run that requires
     * a review starts PENDING and is approved only by every party named here.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"required", "reviewers"})
    public static class Review {

        /** The words a party is written as, in the order they are listed. */
        public static final List<String> PARTIES = Collections.unmodifiableList(Arrays.asList("internal", "customer"));

        private List<String> required;
        /**
         * MIG-361: who decides the internal review in the Task inbox -- a role (TENANT_ADMIN, TENANT_USER) or a group (an
         * access profile, by id). Absent: the workspace's administrators, as before.
         */
        private Reviewers reviewers;

        public static Review of(List<String> required) {
            Review review = new Review();
            review.setRequired(required == null ? null : new ArrayList<>(required));
            return review;
        }

        /** The party a word names: exactly "internal" or "customer". */
        public static Optional<ReviewParty> partyOf(String word) {
            if ("internal".equals(word)) {
                return Optional.of(ReviewParty.INTERNAL);
            }
            return "customer".equals(word) ? Optional.of(ReviewParty.CUSTOMER) : Optional.empty();
        }

        /** The word a party is written as. */
        public static String wordOf(ReviewParty party) {
            return party.name().toLowerCase(Locale.ROOT);
        }

        public List<String> getRequired() { return required; }

        public void setRequired(List<String> required) { this.required = required; }

        public Reviewers getReviewers() { return reviewers; }

        public void setReviewers(Reviewers reviewers) { this.reviewers = reviewers; }

        /** The reviewers in effect: the ones set, else the workspace's administrators. */
        @JsonIgnore
        public Reviewers effectiveReviewers() {
            return this.reviewers != null ? this.reviewers : Reviewers.of(Reviewers.ROLE, Reviewers.ADMINS);
        }
    }

    /**
     * MIG-361: whose Task inbox a run's internal review goes to -- {@code {kind: role, value: TENANT_USER}} or {@code {kind:
     * group, value: "1063"}} (an access profile of the workspace, by id). A workspace's viewers are neither: a role names
     * its administrators or users, and a group only its own people.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"kind", "value"})
    public static class Reviewers {

        public static final String ROLE = "role";
        public static final String GROUP = "group";
        public static final String ADMINS = "TENANT_ADMIN";
        public static final List<String> ROLES = Collections.unmodifiableList(Arrays.asList(ADMINS, "TENANT_USER"));

        private String kind;
        private String value;

        public static Reviewers of(String kind, String value) {
            Reviewers reviewers = new Reviewers();
            reviewers.setKind(kind);
            reviewers.setValue(value);
            return reviewers;
        }

        public String getKind() { return kind; }

        public void setKind(String kind) { this.kind = kind; }

        public String getValue() { return value; }

        public void setValue(String value) { this.value = value; }
    }
}

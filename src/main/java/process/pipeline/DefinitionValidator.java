package process.pipeline;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.pipeline.registry.ConfigSchemaValidator;
import process.pipeline.registry.TaskRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Whether a pipeline definition can be saved and run (MIG-230), as every problem at its path -- never the first one
 * alone. The rules:
 *
 * <ul>
 *   <li>{@code version} is 1.</li>
 *   <li>{@code source.type} is none or task.</li>
 *   <li>1 to {@value #MAX_STEPS} steps; each has a unique {@code key} of lower case letters, digits and '_' (it names
 *       the step's rows and datasets), a {@code task} that is registered ({@link StepTasks}) and usable here (MIG-231:
 *       available, enabled in the workspace, and within the caller's role -- {@link TaskRegistry#refusal}), and a
 *       config that holds to its task's config schema ({@link ConfigSchemaValidator}) and then to the task's own
 *       checks.</li>
 *   <li>A {@code legacy} step is the only step: it is the whole of an existing pipeline, run by its worker.</li>
 *   <li>{@code input} names an earlier step.</li>
 *   <li>{@code retry.maxAttempts} 1 to 10, {@code retry.delaySeconds} 0 to 3600, {@code timeoutSeconds} 1 to 86400,
 *       {@code onError} fail, continue or skip_rest.</li>
 *   <li>{@code settings}: datasetRetentionHours 1 to 720, defaultTimeoutSeconds 1 to 86400, defaultOnError as onError;
 *       review.required (MIG-237) each of internal and customer at most once; sensitivity public, internal or sensitive
 *       (MIG-243); inputContract (MIG-332) a contract by id or by name, not both.</li>
 * </ul>
 */
@Component
public class DefinitionValidator {

    public static final int MAX_STEPS = 50;
    public static final int MAX_TRIES = 10;
    public static final int MAX_DELAY_SECONDS = 3600;
    public static final int MAX_TIMEOUT_SECONDS = 86400;
    public static final int MAX_RETENTION_HOURS = 720;

    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,63}$");

    private final StepTasks tasks;
    private final TaskRegistry registry;

    /** Every task at its default: no workspace's switches (tests, and a definition read outside any workspace). */
    public DefinitionValidator(StepTasks tasks) {
        this(TaskRegistry.defaults(tasks));
    }

    @Autowired
    public DefinitionValidator(TaskRegistry registry) {
        this.registry = registry;
        this.tasks = registry.tasks();
    }

    /** The definition's problems with every task at its default and no role to check. */
    public List<DefinitionProblem> problems(PipelineDefinition definition) {
        return this.problems(definition, null, null);
    }

    /**
     * The definition's problems in this workspace (its task switches) for a caller of this role; {@code role} null
     * checks no role -- the step engine, running what an admin saved.
     */
    public List<DefinitionProblem> problems(PipelineDefinition definition, Long tenantId, String role) {
        Map<String, Boolean> switches = definition == null ? Collections.<String, Boolean>emptyMap() : this.registry.overridesOf(tenantId);
        List<DefinitionProblem> problems = new ArrayList<>();
        if (definition == null) {
            problems.add(new DefinitionProblem("$", "the definition is empty"));
            return problems;
        }
        if (definition.getVersion() == null || definition.getVersion() != PipelineDefinition.CURRENT_VERSION) {
            problems.add(new DefinitionProblem("version", "must be " + PipelineDefinition.CURRENT_VERSION));
        }
        this.source(definition.getSource(), problems);
        this.settings(definition.getSettings(), problems);
        List<PipelineDefinition.Step> steps = definition.getSteps();
        if (steps == null || steps.isEmpty()) {
            problems.add(new DefinitionProblem("steps", "a pipeline has at least one step"));
            return problems;
        }
        if (steps.size() > MAX_STEPS) {
            problems.add(new DefinitionProblem("steps", String.format("at most %d steps; found %d", MAX_STEPS, steps.size())));
        }
        Set<String> earlier = new HashSet<>();
        for (int i = 0; i < steps.size(); i++) {
            this.step(steps.get(i), "steps[" + i + "]", steps.size(), earlier, switches, role, problems);
            if (steps.get(i) != null && steps.get(i).getKey() != null) {
                earlier.add(steps.get(i).getKey());
            }
        }
        return problems;
    }

    /** The definition, or every problem with it. */
    public PipelineDefinition require(PipelineDefinition definition) throws DefinitionException {
        return this.require(definition, null, null);
    }

    /** The definition, or every problem with it in this workspace for this role. */
    public PipelineDefinition require(PipelineDefinition definition, Long tenantId, String role) throws DefinitionException {
        List<DefinitionProblem> problems = this.problems(definition, tenantId, role);
        if (!problems.isEmpty()) {
            throw new DefinitionException(problems);
        }
        return definition;
    }

    private void source(PipelineDefinition.Source source, List<DefinitionProblem> problems) {
        if (source == null) {
            return;
        }
        if (source.getType() == null || !PipelineDefinition.Source.TYPES.contains(source.getType())) {
            problems.add(new DefinitionProblem("source.type", "one of " + PipelineDefinition.Source.TYPES));
        }
        if (source.getConfig() != null && !source.getConfig().isEmpty()) {
            problems.add(new DefinitionProblem("source.config", String.format("a '%s' source takes no settings", source.getType())));
        }
    }

    private void settings(PipelineDefinition.Settings settings, List<DefinitionProblem> problems) {
        if (settings == null) {
            return;
        }
        range(settings.getDatasetRetentionHours(), 1, MAX_RETENTION_HOURS, "settings.datasetRetentionHours", problems);
        range(settings.getDefaultTimeoutSeconds(), 1, MAX_TIMEOUT_SECONDS, "settings.defaultTimeoutSeconds", problems);
        if (settings.getSensitivity() != null && !PipelineDefinition.Settings.SENSITIVITIES.contains(settings.getSensitivity())) {
            problems.add(new DefinitionProblem("settings.sensitivity", "one of " + PipelineDefinition.Settings.SENSITIVITIES));
        }
        if (settings.getDefaultOnError() != null && !OnError.of(settings.getDefaultOnError()).isPresent()) {
            problems.add(new DefinitionProblem("settings.defaultOnError", "one of " + OnError.words()));
        }
        PipelineDefinition.ContractRef input = settings.getInputContract();
        if (input != null) {
            boolean byId = input.getContractId() != null;
            boolean byName = input.getContractName() != null && !input.getContractName().trim().isEmpty();
            if (byId == byName) {
                problems.add(new DefinitionProblem("settings.inputContract", "name the contract by its id or by its name, not both"));
            }
            if (byId && input.getContractId() < 1) {
                problems.add(new DefinitionProblem("settings.inputContract.contractId", "a contract id is at least 1"));
            }
            if (input.getVersion() != null && input.getVersion() < 1) {
                problems.add(new DefinitionProblem("settings.inputContract.version", "a version is at least 1"));
            }
        }
        if (settings.getReview() != null && settings.getReview().getRequired() != null) {
            List<String> required = settings.getReview().getRequired();
            Set<String> named = new HashSet<>();
            for (int i = 0; i < required.size(); i++) {
                String word = required.get(i);
                String at = "settings.review.required[" + i + "]";
                if (!PipelineDefinition.Review.partyOf(word).isPresent()) {
                    problems.add(new DefinitionProblem(at, "one of " + PipelineDefinition.Review.PARTIES));
                } else if (!named.add(word)) {
                    problems.add(new DefinitionProblem(at, String.format("'%s' is already required", word)));
                }
            }
        }
    }

    private void step(PipelineDefinition.Step step, String at, int stepCount, Set<String> earlier, Map<String, Boolean> switches,
        String role, List<DefinitionProblem> problems) {
        if (step == null) {
            problems.add(new DefinitionProblem(at, "a step is an object"));
            return;
        }
        if (step.getKey() == null || !KEY.matcher(step.getKey()).matches()) {
            problems.add(new DefinitionProblem(at + ".key", "lower case letters, digits and '_', starting with a letter, at most 64"));
        } else if (earlier.contains(step.getKey())) {
            problems.add(new DefinitionProblem(at + ".key", String.format("'%s' is already the key of an earlier step", step.getKey())));
        }
        if (step.getName() != null && step.getName().length() > 255) {
            problems.add(new DefinitionProblem(at + ".name", "at most 255 characters"));
        }
        Optional<StepTask> task = this.tasks.find(step.getTask());
        if (step.getTask() == null || step.getTask().trim().isEmpty()) {
            problems.add(new DefinitionProblem(at + ".task", "which task this step runs is required"));
        } else if (!task.isPresent()) {
            problems.add(new DefinitionProblem(at + ".task", String.format("no task '%s' is registered", step.getTask())));
        } else {
            if (!task.get().runsInEngine() && stepCount > 1) {
                problems.add(new DefinitionProblem(at + ".task", String.format(
                    "a '%s' step is the whole of an existing pipeline and must be the only step", step.getTask())));
            }
            Optional<String> refused = this.registry.refusal(task.get(), switches, role);
            if (refused.isPresent()) {
                problems.add(new DefinitionProblem(at + ".task", refused.get()));
            }
            Map<String, Object> config = step.effectiveConfig();
            List<DefinitionProblem> configProblems = ConfigSchemaValidator.problems(task.get().spec().configSchema(), config, earlier);
            if (configProblems.isEmpty()) {
                configProblems = task.get().check(config);
            }
            for (DefinitionProblem problem : configProblems) {
                problems.add(new DefinitionProblem(at + ".config" + ("$".equals(problem.getPath()) ? ""
                    : (problem.getPath().startsWith("[") ? "" : ".") + problem.getPath()), problem.getMessage()));
            }
        }
        if (step.getInput() != null && !earlier.contains(step.getInput())) {
            problems.add(new DefinitionProblem(at + ".input", String.format("'%s' is not an earlier step", step.getInput())));
        }
        if (step.getRetry() != null) {
            range(step.getRetry().getMaxAttempts(), 1, MAX_TRIES, at + ".retry.maxAttempts", problems);
            range(step.getRetry().getDelaySeconds(), 0, MAX_DELAY_SECONDS, at + ".retry.delaySeconds", problems);
        }
        range(step.getTimeoutSeconds(), 1, MAX_TIMEOUT_SECONDS, at + ".timeoutSeconds", problems);
        if (step.getOnError() != null && !OnError.of(step.getOnError()).isPresent()) {
            problems.add(new DefinitionProblem(at + ".onError", "one of " + OnError.words()));
        }
    }

    private static void range(Integer value, int min, int max, String at, List<DefinitionProblem> problems) {
        if (value != null && (value < min || value > max)) {
            problems.add(new DefinitionProblem(at, String.format("between %d and %d", min, max)));
        }
    }
}

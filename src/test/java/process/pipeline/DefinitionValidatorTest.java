package process.pipeline;

import process.pipeline.tasks.SampleStepTask;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;
import static process.pipeline.Definitions.sample;
import static process.pipeline.Definitions.step;

/** MIG-230: what a definition must satisfy before it is saved or run -- every problem at once, each at its path. */
class DefinitionValidatorTest {

    private final DefinitionValidator validator = new DefinitionValidator(Definitions.builtInTasks());

    private PipelineDefinition valid() {
        PipelineDefinition definition = Definitions.of(sample("read", row("id", 1, "name", "Ada")),
            step("shape", "select", config("columns", config("id", "patient_id"))));
        definition.setSource(PipelineDefinition.Source.of("task"));
        definition.getSteps().get(0).setRetry(PipelineDefinition.Retry.of(3, 10));
        definition.getSteps().get(0).setTimeoutSeconds(60);
        definition.getSteps().get(1).setOnError("skip_rest");
        definition.getSteps().get(1).setInput("read");
        PipelineDefinition.Settings settings = new PipelineDefinition.Settings();
        settings.setDatasetRetentionHours(48);
        settings.setDefaultOnError("continue");
        definition.setSettings(settings);
        return definition;
    }

    @Test
    void aWellFormedDefinitionHasNoProblems() throws Exception {
        assertThat(this.validator.problems(this.valid())).isEmpty();
        assertThat(this.validator.require(this.valid())).isNotNull();
    }

    /** MIG-243: a pipeline's sensitivity is one of the data policies' three levels, or not said (internal). */
    @Test
    void theSensitivityIsPublicInternalOrSensitive() {
        for (String level : new String[] {"public", "internal", "sensitive"}) {
            PipelineDefinition definition = this.valid();
            definition.getSettings().setSensitivity(level);
            assertThat(this.validator.problems(definition)).as(level).isEmpty();
            assertThat(definition.getSettings().effectiveSensitivity()).isEqualTo(level);
        }
        PipelineDefinition definition = this.valid();
        assertThat(definition.getSettings().effectiveSensitivity()).isEqualTo("internal");
        definition.getSettings().setSensitivity("PHI");
        assertThat(this.validator.problems(definition)).containsExactly(
            new DefinitionProblem("settings.sensitivity", "one of [public, internal, sensitive]"));
    }

    @Test
    void everyExistingPipelinesLegacyWrapValidates() {
        assertThat(this.validator.problems(PipelineDefinition.legacy("F768927"))).isEmpty();
        assertThat(this.validator.problems(PipelineDefinition.legacy(null))).isEmpty();
    }

    @Test
    void everyProblemIsReportedAtOnceEachAtItsPath() {
        PipelineDefinition definition = this.valid();
        definition.setVersion(2);
        definition.setSource(PipelineDefinition.Source.of("bucket"));
        definition.getSettings().setDatasetRetentionHours(0);
        definition.getSettings().setDefaultTimeoutSeconds(86401);
        definition.getSettings().setDefaultOnError("ignore");
        PipelineDefinition.Step first = definition.getSteps().get(0);
        first.setRetry(PipelineDefinition.Retry.of(11, -1));
        first.setTimeoutSeconds(0);
        first.setOnError("retry");
        PipelineDefinition.Step second = definition.getSteps().get(1);
        second.setKey("Shape-2");
        second.setInput("later");
        definition.getSteps().add(step("read", "nosuch"));
        definition.getSteps().add(step(null, " "));

        assertThat(this.validator.problems(definition)).containsExactly(
            new DefinitionProblem("version", "must be 1"),
            new DefinitionProblem("source.type", "one of [none, task]"),
            new DefinitionProblem("settings.datasetRetentionHours", "between 1 and 720"),
            new DefinitionProblem("settings.defaultTimeoutSeconds", "between 1 and 86400"),
            new DefinitionProblem("settings.defaultOnError", "one of [fail, continue, skip_rest]"),
            new DefinitionProblem("steps[0].retry.maxAttempts", "between 1 and 10"),
            new DefinitionProblem("steps[0].retry.delaySeconds", "between 0 and 3600"),
            new DefinitionProblem("steps[0].timeoutSeconds", "between 1 and 86400"),
            new DefinitionProblem("steps[0].onError", "one of [fail, continue, skip_rest]"),
            new DefinitionProblem("steps[1].key", "lower case letters, digits and '_', starting with a letter, at most 64"),
            new DefinitionProblem("steps[1].input", "'later' is not an earlier step"),
            new DefinitionProblem("steps[2].key", "'read' is already the key of an earlier step"),
            new DefinitionProblem("steps[2].task", "no task 'nosuch' is registered"),
            new DefinitionProblem("steps[3].key", "lower case letters, digits and '_', starting with a letter, at most 64"),
            new DefinitionProblem("steps[3].task", "which task this step runs is required"));
    }

    @Test
    void aStepsInputIsAnEarlierStepNeverItselfOrALaterOne() {
        PipelineDefinition definition = Definitions.of(sample("a", row("x", 1)), sample("b", row("x", 2)));
        definition.getSteps().get(0).setInput("b");
        definition.getSteps().get(1).setInput("b");
        assertThat(this.validator.problems(definition)).containsExactly(
            new DefinitionProblem("steps[0].input", "'b' is not an earlier step"),
            new DefinitionProblem("steps[1].input", "'b' is not an earlier step"));
    }

    @Test
    void aLegacyStepIsTheWholePipelineAndCannotBeChained() {
        PipelineDefinition definition = PipelineDefinition.legacy("F768927");
        definition.getSteps().add(sample("after", row("x", 1)));
        assertThat(this.validator.problems(definition)).containsExactly(new DefinitionProblem("steps[0].task",
            "a 'legacy' step is the whole of an existing pipeline and must be the only step"));
    }

    @Test
    void aPipelineHasOneToFiftySteps() {
        assertThat(this.validator.problems(Definitions.of())).containsExactly(
            new DefinitionProblem("steps", "a pipeline has at least one step"));
        List<PipelineDefinition.Step> many = new ArrayList<>();
        for (int i = 0; i <= DefinitionValidator.MAX_STEPS; i++) {
            many.add(sample("s" + i, row("x", i)));
        }
        PipelineDefinition definition = Definitions.of();
        definition.setSteps(many);
        assertThat(this.validator.problems(definition)).containsExactly(new DefinitionProblem("steps", "at most 50 steps; found 51"));
    }

    @Test
    void eachTasksOwnConfigIsCheckedAtTheStepsConfigPath() {
        PipelineDefinition definition = Definitions.of(
            step("read", "sample", config("rows", Arrays.asList(row("x", 1), "not a row", row("y", Arrays.asList(1))), "extra", 1)),
            step("keep", "select", config("columns", Collections.emptyList(), "required", "yes")),
            step("old", "sample"));
        // MIG-231: the task's config schema first, each problem at its path; the task's own checks only once it holds.
        assertThat(this.validator.problems(definition)).containsExactly(
            new DefinitionProblem("steps[0].config.rows[1]", "must be an object"),
            new DefinitionProblem("steps[0].config.rows[2].y", "must be text, a number, true/false or null"),
            new DefinitionProblem("steps[0].config.extra", "unknown setting 'extra'"),
            new DefinitionProblem("steps[1].config.required", "must be true or false"),
            new DefinitionProblem("steps[2].config.rows", "required"));
        PipelineDefinition schemaHolds = Definitions.of(step("keep", "select", config("columns", Collections.emptyList())));
        assertThat(this.validator.problems(schemaHolds)).containsExactly(
            new DefinitionProblem("steps[0].config.columns", "name at least one column"));
    }

    @Test
    void aSourceTakesNoSettingsAndAnEmptyDefinitionIsOneProblem() {
        PipelineDefinition definition = this.valid();
        definition.getSource().setConfig(config("bucket", "x"));
        assertThat(this.validator.problems(definition)).containsExactly(
            new DefinitionProblem("source.config", "a 'task' source takes no settings"));
        assertThat(this.validator.problems(null)).containsExactly(new DefinitionProblem("$", "the definition is empty"));
        assertThatThrownBy(() -> this.validator.require(null)).isInstanceOf(DefinitionException.class);
    }

    @Test
    void theDefaultsAreFailTenMinutesOneTryAndADay() {
        PipelineDefinition.Step bare = step("a", "sample");
        assertThat(bare.effectiveOnError(null)).isEqualTo(OnError.FAIL);
        assertThat(bare.effectiveTimeoutSeconds(null)).isEqualTo(600);
        assertThat(bare.effectiveMaxAttempts()).isEqualTo(1);
        assertThat(bare.effectiveDelaySeconds()).isZero();
        assertThat(new PipelineDefinition.Settings().effectiveRetentionHours()).isEqualTo(24);
        PipelineDefinition.Settings settings = new PipelineDefinition.Settings();
        settings.setDefaultOnError("continue");
        settings.setDefaultTimeoutSeconds(30);
        assertThat(bare.effectiveOnError(settings)).isEqualTo(OnError.CONTINUE);
        assertThat(bare.effectiveTimeoutSeconds(settings)).isEqualTo(30);
        bare.setOnError("skip_rest");
        assertThat(bare.effectiveOnError(settings)).isEqualTo(OnError.SKIP_REST);
    }

    @Test
    void twoTasksCannotShareACodeAndACodeIsLowerCase() {
        assertThatThrownBy(() -> Definitions.builtInTasks(new SampleStepTask()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("share the code sample");
        assertThat(Definitions.builtInTasks().all()).extracting(StepTask::code).containsExactly("legacy", "sample", "select");
    }
}

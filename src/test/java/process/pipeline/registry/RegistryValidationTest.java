package process.pipeline.registry;

import org.junit.jupiter.api.Test;
import process.pipeline.AllTasks;
import process.pipeline.DefinitionProblem;
import process.pipeline.DefinitionValidator;
import process.pipeline.Definitions;
import process.pipeline.PipelineDefinition;
import process.pipeline.StepTasks;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;
import static process.pipeline.Definitions.sample;
import static process.pipeline.Definitions.step;

/**
 * MIG-231: a definition checked in a workspace for a caller -- a disabled, unavailable or above-my-role task is a problem at
 * its step's task; each step's config is checked against its task's config schema at steps[i].config.<path>.
 */
class RegistryValidationTest {

    private static final long TENANT = 41L;

    private final AllTasks all = new AllTasks();
    private final StepTasks tasks = this.all.tasks();
    private final InMemoryTaskOverrideStore switches = new InMemoryTaskOverrideStore();
    private final DefinitionValidator validator = new DefinitionValidator(new TaskRegistry(this.tasks, this.switches));

    @Test
    void aDisabledTaskCannotBeAddedToAPipeline() {
        PipelineDefinition definition = Definitions.of(sample("read", row("id", 1)),
            step("write", "write_database", config("connectionId", 2, "table", "claims")));
        assertThat(this.validator.problems(definition, TENANT, "TENANT_ADMIN")).containsExactly(
            new DefinitionProblem("steps[1].task", "the task 'write_database' is disabled in this workspace"));
        this.switches.set(TENANT, "write_database", true, 7L);
        assertThat(this.validator.problems(definition, TENANT, "TENANT_ADMIN")).isEmpty();
        this.switches.set(TENANT, "sample", false, 7L);
        assertThat(this.validator.problems(definition, TENANT, "TENANT_ADMIN")).containsExactly(
            new DefinitionProblem("steps[0].task", "the task 'sample' is disabled in this workspace"));
        assertThat(this.validator.problems(definition, 42L, "TENANT_ADMIN")).as("another workspace, its own switches").containsExactly(
            new DefinitionProblem("steps[1].task", "the task 'write_database' is disabled in this workspace"));
    }

    @Test
    void anUnavailableTaskAndOneAboveTheCallersRoleAreProblemsToo() {
        this.all.buckets.unavailable = "storage-service does not accept CORE_PIPELINES yet";
        PipelineDefinition definition = Definitions.of(sample("read", row("id", 1)),
            step("up", "upload_bucket", config("bucket", "exports", "key", "a.csv")));
        assertThat(this.validator.problems(definition, TENANT, "TENANT_ADMIN")).containsExactly(new DefinitionProblem("steps[1].task",
            "the task 'upload_bucket' is not available: storage-service does not accept CORE_PIPELINES yet"));
        this.all.buckets.unavailable = null;
        assertThat(this.validator.problems(definition, TENANT, "TENANT_USER")).containsExactly(
            new DefinitionProblem("steps[1].task", "the task 'upload_bucket' needs the tenant administrator role"));
    }

    @Test
    void eachStepsConfigIsCheckedAgainstItsSchemaThenItsTask() {
        PipelineDefinition definition = Definitions.of(
            sample("read", row("id", 1)),
            step("shape", "transform", config("mappings", Arrays.asList(config("target", "a", "op", "explode"), config("op", "copy")))),
            step("keep", "filter", config("conditions", Collections.singletonList(config("column", "a", "operator", "eq")))),
            step("both", "join", config("with", "later", "on", Collections.singletonList(config("left", "a", "right", "b")))),
            step("later", "aggregate", config("aggregations", Collections.singletonList(config("op", "sum", "as", "t")))));
        assertThat(this.validator.problems(definition)).containsExactly(
            new DefinitionProblem("steps[1].config.mappings[0].op", "one of [copy, constant, concat, upper, lower, trim, cast, coalesce, replace]"),
            new DefinitionProblem("steps[1].config.mappings[1].target", "required"),
            new DefinitionProblem("steps[2].config.conditions[0].value", "eq needs a value to compare with"),
            new DefinitionProblem("steps[3].config.with", "'later' is not an earlier step"),
            new DefinitionProblem("steps[4].config.aggregations[0].column", "sum needs its column"));
    }
}

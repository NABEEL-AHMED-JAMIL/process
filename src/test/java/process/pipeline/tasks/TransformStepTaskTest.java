package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/** MIG-231: Transform maps each row to new columns, one operation per target. */
class TransformStepTaskTest {

    private final TransformStepTask task = new TransformStepTask();

    @Test
    void everyOperationOnASmallDataset() throws Exception {
        TaskContext context = TaskContext.of(config("mappings", Arrays.asList(
            config("target", "id", "op", "cast", "source", "id", "to", "integer"),
            config("target", "name", "op", "upper", "source", "name"),
            config("target", "slug", "op", "lower", "source", "name"),
            config("target", "clean", "op", "trim", "source", "note"),
            config("target", "label", "op", "concat", "sources", Arrays.asList("id", "name"), "separator", "-"),
            config("target", "contact", "op", "coalesce", "sources", Arrays.asList("email", "phone"), "value", "none"),
            config("target", "source", "op", "constant", "value", "crm"),
            config("target", "amount", "op", "cast", "source", "amount", "to", "number"),
            config("target", "active", "op", "cast", "source", "active", "to", "boolean"),
            config("target", "code", "op", "replace", "source", "code", "search", "-", "replacement", ""),
            config("target", "copied", "source", "id"))),
            Arrays.asList(
                row("id", "007", "name", "Acme", "note", "  hi ", "email", "", "phone", "555", "amount", "12.50", "active", "yes", "code", "A-1-2"),
                row("id", "8", "name", "beta", "note", null, "email", "b@x", "phone", null, "amount", null, "active", "0", "code", null)));

        Dataset out = this.task.run(context).getOutput();

        assertThat(out.getColumns()).containsExactly("id", "name", "slug", "clean", "label", "contact", "source", "amount", "active", "code", "copied");
        assertThat(out.getRows().get(0)).containsExactly(
            entry("id", 7L), entry("name", "ACME"), entry("slug", "acme"), entry("clean", "hi"), entry("label", "007-Acme"),
            entry("contact", "555"), entry("source", "crm"), entry("amount", 12.5), entry("active", true), entry("code", "A12"),
            entry("copied", "007"));
        assertThat(out.getRows().get(1)).containsEntry("id", 8L).containsEntry("clean", null).containsEntry("contact", "b@x")
            .containsEntry("amount", null).containsEntry("active", false).containsEntry("code", null);
    }

    private static Map.Entry<String, Object> entry(String key, Object value) {
        return new AbstractMap.SimpleEntry<>(key, value);
    }

    @Test
    void keepUnmappedPassesTheInputsColumnsOnWithTheTargets() throws Exception {
        Dataset out = this.task.run(TaskContext.of(config("keepUnmapped", true, "mappings", Collections.singletonList(
            config("target", "total", "op", "cast", "source", "total", "to", "number"))), Collections.singletonList(row("id", 1, "total", "3")))).getOutput();
        assertThat(out.getColumns()).containsExactly("id", "total");
        assertThat(out.getRows()).containsExactly(row("id", 1, "total", 3L));
    }

    @Test
    void aValueThatDoesNotCastFailsAtItsRowWithoutQuotingIt() {
        TaskContext context = TaskContext.of(config("mappings", Collections.singletonList(
            config("target", "n", "op", "cast", "source", "amount", "to", "integer"))),
            Arrays.asList(row("amount", "1"), row("amount", "secret-12.5")));
        assertThatThrownBy(() -> this.task.run(context)).hasMessage("Row 2: amount is not a whole number.");
    }

    @Test
    void eachOperationNeedsItsSettings() {
        assertThat(this.task.check(config("mappings", Arrays.asList(
            config("target", "a", "op", "constant"),
            config("target", "b", "op", "concat"),
            config("target", "c", "op", "cast", "source", "x"),
            config("target", "a", "source", "y"),
            config("target", "d", "op", "replace", "source", "y"))))).containsExactly(
            new DefinitionProblem("mappings[0].value", "a constant needs its value"),
            new DefinitionProblem("mappings[1].sources", "concat needs at least one column"),
            new DefinitionProblem("mappings[2].to", "cast needs what to cast to"),
            new DefinitionProblem("mappings[3].target", "'a' is already made by an earlier mapping"),
            new DefinitionProblem("mappings[4].search", "replace needs what to replace"));
    }
}

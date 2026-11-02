package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.DefinitionProblem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/** MIG-231: Filter keeps the rows that meet all (or any) of its conditions. */
class FilterStepTaskTest {

    private final FilterStepTask task = new FilterStepTask();
    private final List<Map<String, Object>> rows = Arrays.asList(
        row("id", 1L, "amount", "9", "state", "TX", "email", "a@acme.com"),
        row("id", 2L, "amount", "10", "state", "tx", "email", null),
        row("id", 3L, "amount", 250.5, "state", "CA", "email", ""),
        row("id", 4L, "amount", null, "state", "NY", "email", "d@beta.io"));

    private List<Object> kept(Object... config) throws Exception {
        TaskContext context = TaskContext.of(config(config), this.rows);
        List<Object> ids = new ArrayList<>();

        for (Map<String, Object> row : this.task.run(context).getOutput().getRows()) {
            ids.add(row.get("id"));
        }
        return ids;
    }

    @Test
    void numbersCompareByValueTextAsText() throws Exception {
        assertThat(this.kept("conditions", Collections.singletonList(config("column", "amount", "operator", "gte", "value", 10))))
            .containsExactly(2L, 3L);
        assertThat(this.kept("conditions", Collections.singletonList(config("column", "state", "operator", "eq", "value", "TX"))))
            .containsExactly(1L);
        assertThat(this.kept("conditions", Collections.singletonList(config("column", "state", "operator", "eq", "value", "TX", "ignoreCase", true))))
            .containsExactly(1L, 2L);
    }

    @Test
    void allOrAnyOfSeveralConditions() throws Exception {
        List<Object> both = this.kept("conditions", Arrays.asList(
            config("column", "state", "operator", "in", "values", Arrays.asList("TX", "CA")),
            config("column", "email", "operator", "not_empty")));
        assertThat(both).containsExactly(1L);
        List<Object> either = this.kept("match", "any", "conditions", Arrays.asList(
            config("column", "email", "operator", "ends_with", "value", ".io"),
            config("column", "amount", "operator", "lt", "value", "10")));
        assertThat(either).containsExactly(1L, 4L);
        assertThat(this.kept("conditions", Collections.singletonList(config("column", "email", "operator", "is_empty"))))
            .containsExactly(2L, 3L);
        assertThat(this.kept("conditions", Collections.singletonList(config("column", "amount", "operator", "is_null"))))
            .containsExactly(4L);
        assertThat(this.kept("conditions", Collections.singletonList(config("column", "email", "operator", "contains", "value", "ACME",
            "ignoreCase", true)))).containsExactly(1L);
    }

    /**
     * AI eval A-filter-021 (2026-11-02): "hba1c below 8" kept all 100 rows instead of 34 -- the rows with no hba1c, an empty
     * CSV cell, passed, because "" sorted before every number as text. A comparison with an empty side never holds.
     */
    @Test
    void anEmptyValueIsNeitherBelowNorAboveANumber() throws Exception {
        List<Map<String, Object>> labs = Arrays.asList(row("id", 1L, "hba1c", "7.2"), row("id", 2L, "hba1c", ""),
            row("id", 3L, "hba1c", "9.1"), row("id", 4L, "hba1c", null), row("id", 5L, "hba1c", "  "), row("id", 6L, "hba1c", 6.4));
        for (String[] c : new String[][] {{"lt", "8", "1,6"}, {"lte", "7.2", "1,6"}, {"gt", "8", "3"}, {"gte", "6.4", "1,3,6"}}) {
            assertThat(this.keptOf(labs, "conditions", Collections.singletonList(config("column", "hba1c", "operator", c[0], "value", c[1]))))
                .as(c[0] + " " + c[1]).containsExactly(Arrays.stream(c[2].split(",")).map(Long::valueOf).toArray());
        }
        // Empty is said explicitly, and eq/ne keep their rule (null eq null; anything ne null).
        assertThat(this.keptOf(labs, "conditions", Collections.singletonList(config("column", "hba1c", "operator", "is_empty"))))
            .containsExactly(2L, 4L);
        assertThat(this.keptOf(labs, "conditions", Collections.singletonList(config("column", "hba1c", "operator", "not_empty"))))
            .containsExactly(1L, 3L, 5L, 6L);
        assertThat(this.keptOf(labs, "conditions", Collections.singletonList(config("column", "hba1c", "operator", "ne", "value", "7.2"))))
            .containsExactly(2L, 3L, 4L, 5L, 6L);
        // An empty value to compare with matches nothing either.
        assertThat(FilterStepTask.meets("7", config("operator", "lt", "value", ""))).isFalse();
    }

    private List<Object> keptOf(List<Map<String, Object>> input, Object... config) throws Exception {
        List<Object> ids = new ArrayList<>();
        for (Map<String, Object> row : this.task.run(TaskContext.of(config(config), input)).getOutput().getRows()) {
            ids.add(row.get("id"));
        }
        return ids;
    }

    @Test
    void anOperatorNeedsWhatItComparesWith() {
        assertThat(this.task.check(config("conditions", Arrays.asList(config("column", "a", "operator", "eq"),
            config("column", "a", "operator", "in"), config("column", "a", "operator", "not_null"))))).containsExactly(
            new DefinitionProblem("conditions[0].value", "eq needs a value to compare with"),
            new DefinitionProblem("conditions[1].values", "in needs its list of values"));
    }
}

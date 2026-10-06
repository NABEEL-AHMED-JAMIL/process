package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/** MIG-231: Aggregate makes one row per group with each aggregation as a column. */
class AggregateStepTaskTest {

    private final AggregateStepTask task = new AggregateStepTask();

    @Test
    void everyOperationPerGroup() throws Exception {
        TaskContext context = TaskContext.of(config("groupBy", Collections.singletonList("state"), "aggregations", Arrays.asList(
            config("op", "count", "as", "orders"),
            config("op", "count_distinct", "column", "customer", "as", "customers"),
            config("op", "sum", "column", "amount", "as", "total"),
            config("op", "avg", "column", "amount", "as", "average"),
            config("op", "min", "column", "amount", "as", "smallest"),
            config("op", "max", "column", "day", "as", "latest"),
            config("op", "first", "column", "customer", "as", "first_customer"),
            config("op", "last", "column", "customer", "as", "last_customer"))),
            Arrays.asList(
                row("state", "TX", "customer", "a", "amount", "10", "day", "2026-09-01"),
                row("state", "CA", "customer", "b", "amount", 5L, "day", "2026-09-03"),
                row("state", "TX", "customer", "a", "amount", 2.5, "day", "2026-09-02"),
                row("state", "TX", "customer", "c", "amount", null, "day", "2026-09-05")));

        Dataset out = this.task.run(context).getOutput();

        assertThat(out.getColumns()).containsExactly("state", "orders", "customers", "total", "average", "smallest", "latest", "first_customer",
            "last_customer");
        assertThat(out.getRows()).containsExactly(
            row("state", "TX", "orders", 3L, "customers", 2L, "total", 12.5, "average", 6.25, "smallest", 2.5, "latest", "2026-09-05",
                "first_customer", "a", "last_customer", "c"),
            row("state", "CA", "orders", 1L, "customers", 1L, "total", 5L, "average", 5L, "smallest", 5L, "latest", "2026-09-03",
                "first_customer", "b", "last_customer", "b"));
    }

    @Test
    void noGroupByIsOneRowEvenForNoInput() throws Exception {
        Dataset out = this.task.run(TaskContext.of(config("aggregations", Arrays.asList(config("op", "count", "as", "n"),
            config("op", "sum", "column", "x", "as", "total"))), Collections.emptyList())).getOutput();
        assertThat(out.getRows()).containsExactly(row("n", 0L, "total", 0L));
    }

    @Test
    void aSumOfTextFailsAtItsRowAndEachAggregationNeedsItsColumn() {
        assertThatThrownBy(() -> this.task.run(TaskContext.of(config("aggregations", Collections.singletonList(
            config("op", "sum", "column", "x", "as", "t"))), Arrays.asList(row("x", 1L), row("x", "abc")))))
            .hasMessage("Row 2: x is not a number (sum).");
        assertThat(this.task.check(config("groupBy", Collections.singletonList("n"), "aggregations", Arrays.asList(
            config("op", "sum", "as", "t"), config("op", "count", "as", "n"))))).containsExactly(
            new DefinitionProblem("aggregations[0].column", "sum needs its column"),
            new DefinitionProblem("aggregations[1].as", "'n' is already a column of the output"));
    }
}

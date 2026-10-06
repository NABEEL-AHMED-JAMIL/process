package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/** MIG-231: Join meets the input with an earlier step's output on equal keys. */
class JoinStepTaskTest {

    private final JoinStepTask task = new JoinStepTask();

    private TaskContext context(String type) {
        TaskContext context = TaskContext.of(config("with", "customers", "type", type, "on",
            Collections.singletonList(config("left", "customer_id", "right", "id"))),
            Arrays.asList(row("order", "A1", "customer_id", "1", "name", "order one"), row("order", "A2", "customer_id", 2L, "name", "two"),
                row("order", "A3", "customer_id", null, "name", "none")));
        context.outputs.put("customers", Dataset.of(Arrays.asList(row("id", 1L, "name", "Acme", "tier", "gold"),
            row("id", 1.0, "name", "Acme again", "tier", "silver"), row("id", 9L, "name", "Zed", "tier", "none"))));
        return context;
    }

    @Test
    void innerKeepsThePairsTextAndNumberKeysMeetClashesArePrefixed() throws Exception {
        Dataset out = this.task.run(this.context("inner")).getOutput();
        assertThat(out.getColumns()).containsExactly("order", "customer_id", "name", "right_name", "tier");
        assertThat(out.getRows()).containsExactly(
            row("order", "A1", "customer_id", "1", "name", "order one", "right_name", "Acme", "tier", "gold"),
            row("order", "A1", "customer_id", "1", "name", "order one", "right_name", "Acme again", "tier", "silver"));
    }

    @Test
    void leftKeepsEveryInputRowWithEmptyRightColumns() throws Exception {
        TaskContext context = this.context("left");
        Dataset out = this.task.run(context).getOutput();
        assertThat(out.getRows()).hasSize(4);
        assertThat(out.getRows().get(2)).containsEntry("order", "A2").containsEntry("right_name", null).containsEntry("tier", null);
        assertThat(context.lines).anyMatch(line -> line.contains("2 input row(s) matched nothing"));
    }

    @Test
    void theOtherSideMustBeAnEarlierStepsOutput() {
        TaskContext context = this.context("inner");
        context.outputs.clear();
        assertThatThrownBy(() -> this.task.run(context)).hasMessage("no output customers");
    }
}

package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;
import process.pipeline.backing.Fakes;

import java.util.Arrays;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/** MIG-231: Enrich calls a saved request per row (one call per distinct variables) and adds fields of its answer. */
class EnrichStepTaskTest {

    private final Fakes.Api api = new Fakes.Api();
    private final EnrichStepTask task = new EnrichStepTask(this.api);

    private TaskContext context(Object... more) {
        Object[] settings = new Object[6 + more.length];
        System.arraycopy(new Object[] {"requestId", 7, "variables", config("customer", "{{customer_id}}"), "fields",
            Arrays.asList(config("path", "data.score", "target", "score"), config("path", "data.tier", "target", "tier"))}, 0, settings, 0, 6);
        System.arraycopy(more, 0, settings, 6, more.length);
        return TaskContext.of(config(settings), Arrays.asList(row("customer_id", "1", "order", "A1"), row("customer_id", "2", "order", "A2"),
            row("customer_id", "1", "order", "A3")));
    }

    @Test
    void rowsWithTheSameVariablesShareOneCall() throws Exception {
        this.api.answer = variables -> "{\"data\":{\"score\":" + ("1".equals(variables.get("customer")) ? 90 : 40) + ",\"tier\":\"t"
            + variables.get("customer") + "\"}}";
        Dataset out = this.task.run(this.context()).getOutput();
        assertThat(this.api.calls).hasSize(2);
        assertThat(out.getColumns()).containsExactly("customer_id", "order", "score", "tier");
        assertThat(out.getRows()).containsExactly(row("customer_id", "1", "order", "A1", "score", 90L, "tier", "t1"),
            row("customer_id", "2", "order", "A2", "score", 40L, "tier", "t2"), row("customer_id", "1", "order", "A3", "score", 90L, "tier", "t1"));
    }

    @Test
    void aFailedCallFailsSkipsOrEmptiesPerOnError() throws Exception {
        this.api.outcome = "FAILED";
        assertThatThrownBy(() -> this.task.run(this.context())).hasMessageContaining("The API request failed");
        assertThat(this.task.run(this.context("onError", "skip")).getOutput().size()).isZero();
        Dataset kept = this.task.run(this.context("onError", "null")).getOutput();
        assertThat(kept.getRows()).hasSize(3).allMatch(row -> row.get("score") == null);
    }

    @Test
    void moreCallsThanMaxCallsFailsBeforeAnyIsMade() {
        assertThatThrownBy(() -> this.task.run(this.context("maxCalls", 1))).hasMessage("The input needs 2 calls; this step makes at most 1 (maxCalls).");
        assertThat(this.api.calls).isEmpty();
    }
}

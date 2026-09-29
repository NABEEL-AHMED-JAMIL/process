package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;
import process.pipeline.backing.Fakes;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/** MIG-231: Read API takes a saved request's answer as rows, through integration-service's runner. */
class ReadApiStepTaskTest {

    private final Fakes.Api api = new Fakes.Api();
    private final ReadApiStepTask task = new ReadApiStepTask(this.api);

    @Test
    void theRowsAtThePathBecomeRowsNestedValuesAsJsonText() throws Exception {
        this.api.answer = variables -> "{\"data\":{\"items\":[{\"id\":1,\"name\":\"Acme\",\"tags\":[\"x\"]},{\"id\":2,\"name\":\"Beta\"}]}}";
        TaskContext context = TaskContext.of(config("requestId", 12, "environmentId", 3, "variables", config("since", "{{date}}", "run", "{{run}}"),
            "rowsPath", "data.items"), Collections.emptyList());

        Dataset out = this.task.run(context).getOutput();

        assertThat(out.getColumns()).containsExactly("id", "name", "tags");
        assertThat(out.getRows()).containsExactly(row("id", 1L, "name", "Acme", "tags", "[\"x\"]"), row("id", 2L, "name", "Beta"));
        assertThat(this.api.calls).hasSize(1);
        assertThat(this.api.calls.get(0).tenantId).as("the run's workspace, never the config's").isEqualTo(TaskContext.TENANT);
        assertThat(this.api.calls.get(0).requestId).isEqualTo(12L);
        assertThat(this.api.calls.get(0).environmentId).isEqualTo(3L);
        assertThat(this.api.calls.get(0).variables).containsEntry("run", "88001").doesNotContainValue("{{date}}");
    }

    @Test
    void anObjectAnswerIsOneRowAndMaxRowsStopsEarly() throws Exception {
        this.api.answer = variables -> "{\"total\":3}";
        assertThat(this.task.run(TaskContext.of(config("requestId", 1), Collections.emptyList())).getOutput().getRows())
            .containsExactly(row("total", 3L));
        this.api.answer = variables -> "[{\"n\":1},{\"n\":2},{\"n\":3}]";
        assertThat(this.task.run(TaskContext.of(config("requestId", 1, "maxRows", 2), Collections.emptyList())).getOutput().size()).isEqualTo(2);
    }

    @Test
    void aFailedRunFailsTheStepInTheRunnersWordsAndUnavailableIsTheRunners() {
        this.api.outcome = "FAILED";
        assertThatThrownBy(() -> this.task.run(TaskContext.of(config("requestId", 1), Collections.emptyList())))
            .hasMessage("The API request failed (HTTP 500): upstream said no.");
        this.api.unavailable = "not deployed";
        assertThat(this.task.unavailable()).contains("not deployed");
    }
}

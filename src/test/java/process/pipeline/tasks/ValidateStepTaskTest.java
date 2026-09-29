package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.backing.Fakes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/** MIG-231: Validate checks rows against a data contract (integration-service): fail, drop or flag. */
class ValidateStepTaskTest {

    private final Fakes.Contracts contracts = new Fakes.Contracts();
    private final ValidateStepTask task = new ValidateStepTask(this.contracts);
    private final List<Map<String, Object>> rows = Arrays.asList(row("id", 1L, "amount", "10"), row("id", 2L, "amount", "x"),
        row("id", 3L, "amount", "30"));

    ValidateStepTaskTest() {
        this.contracts.holds = row -> !"x".equals(row.get("amount"));
    }

    @Test
    void failIsTheDefaultAndNamesTheFirstRowThatDoesNotHold() {
        assertThatThrownBy(() -> this.task.run(TaskContext.of(config("contractId", 4), this.rows)))
            .hasMessage("1 of 3 row(s) do not hold to claims v3; row 2: /amount: must be a number");
        assertThat(this.contracts.calls.get(0).contractId).isEqualTo(4L);
        assertThat(this.contracts.calls.get(0).tenantId).isEqualTo(TaskContext.TENANT);
    }

    @Test
    void dropPassesTheValidRowsOn() throws Exception {
        TaskContext context = TaskContext.of(config("contractName", "claims", "onInvalid", "drop"), this.rows);
        assertThat(this.task.run(context).getOutput().getRows()).extracting(row -> row.get("id")).containsExactly(1L, 3L);
        assertThat(context.lines).anyMatch(line -> line.startsWith("WARN 1 row(s) dropped"));
    }

    @Test
    void flagKeepsEveryRowWithItsVerdict() throws Exception {
        Dataset out = this.task.run(TaskContext.of(config("contractId", 4, "onInvalid", "flag"), this.rows)).getOutput();
        assertThat(out.getColumns()).containsExactly("id", "amount", "_valid", "_errors");
        assertThat(out.getRows().get(1)).containsEntry("_valid", false).containsEntry("_errors", "/amount: must be a number");
        assertThat(out.getRows().get(0)).containsEntry("_valid", true).containsEntry("_errors", null);
    }

    @Test
    void rowsGoInBatchesAndTheContractIsNamedOneWay() throws Exception {
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            many.add(row("id", (long) i));
        }
        assertThat(this.task.run(TaskContext.of(config("contractId", 4), many)).getOutput().size()).isEqualTo(501);
        assertThat(this.contracts.calls).extracting(call -> call.rows.size()).containsExactly(500, 1);
        assertThat(this.task.check(config("contractId", 4, "contractName", "claims"))).containsExactly(
            new DefinitionProblem("contractId", "name the contract by its id or by its name, not both"));
        assertThat(this.task.check(config())).hasSize(1);
    }
}

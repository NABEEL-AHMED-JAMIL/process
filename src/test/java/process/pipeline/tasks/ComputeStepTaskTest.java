package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/**
 * MIG-255: Compute adds columns worked out from each row by formula, in order, so a later formula reads an earlier one's
 * column -- the wound pipeline's area, change against the last visit, and healing trend are computed here, in code, not
 * by the model. A formula that does not parse is a definition problem at its place.
 */
class ComputeStepTaskTest {

    private final ComputeStepTask task = new ComputeStepTask();

    private static final Object FORMULAS = Arrays.asList(
        config("target", "area_cm2", "expression", "round(length_cm * width_cm * 0.785, 1)"),
        config("target", "change_pct", "expression", "if(prev_area > 0, round((area_cm2 - prev_area) / prev_area * 100, 1), null)"),
        config("target", "trend", "expression",
            "if(is_null(prev_area), 'baseline', if(is_null(change_pct), 'unknown', "
                + "if(change_pct <= -10, 'improving', if(change_pct >= 10, 'worsening', 'stable'))))"));

    @Test
    void formulasRunInOrderAndReadEachOthersColumns() throws Exception {
        TaskContext context = TaskContext.of(config("formulas", FORMULAS), Arrays.asList(
            row("case_id", "C1", "length_cm", "4", "width_cm", "3", "prev_area", "12.5"),
            row("case_id", "C2", "length_cm", 2, "width_cm", 2, "prev_area", ""),
            row("case_id", "C3", "length_cm", null, "width_cm", 2, "prev_area", "4")));

        Dataset out = this.task.run(context).getOutput();

        assertThat(out.getColumns()).containsExactly("case_id", "length_cm", "width_cm", "prev_area", "area_cm2", "change_pct", "trend");
        assertThat(out.getRows().get(0)).containsEntry("area_cm2", 9.4).containsEntry("change_pct", -24.8).containsEntry("trend", "improving");
        assertThat(out.getRows().get(1)).containsEntry("area_cm2", 3.1).containsEntry("change_pct", null).containsEntry("trend", "baseline");
        assertThat(out.getRows().get(2)).containsEntry("area_cm2", null).containsEntry("trend", "unknown");
    }

    @Test
    void aFormulaThatCannotBeWorkedOutFailsTheStepOrLeavesItsColumnEmpty() throws Exception {
        Object formulas = Collections.singletonList(config("target", "twice", "expression", "a * 2"));
        TaskContext bad = TaskContext.of(config("formulas", formulas), Arrays.asList(row("a", "x")));
        assertThatThrownBy(() -> this.task.run(bad)).hasMessage("Row 1, twice: 'x' in a is not a number.");

        Object lenient = Collections.singletonList(config("target", "twice", "expression", "a * 2", "nullOnError", true));
        Dataset out = this.task.run(TaskContext.of(config("formulas", lenient), Arrays.asList(row("a", "x"), row("a", 2)))).getOutput();
        assertThat(out.getRows().get(0)).containsEntry("twice", null);
        assertThat(out.getRows().get(1)).containsEntry("twice", 4.0);
    }

    @Test
    void aFormulaThatDoesNotParseIsADefinitionProblemAtItsPlace() {
        Object formulas = Arrays.asList(config("target", "ok", "expression", "1 + 1"), config("target", "bad", "expression", "round(a,"),
            config("target", "ok", "expression", "2"));

        assertThat(this.task.check(config("formulas", formulas))).extracting(p -> p.getPath() + ": " + p.getMessage()).containsExactly(
            "formulas[1].expression: Expected a value at 9 in: round(a,",
            "formulas[2].target: 'ok' is already made by an earlier formula");
    }
}

package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;

/**
 * MIG-346: Append stacks earlier steps' rows by column name -- every column (missing ones empty) or only the common
 * ones, optionally saying which step a row came from -- converting a value of another kind to its column's kind and
 * failing, with the row, step and column, when it cannot. It streams: one batch of one input at a time.
 */
class AppendStepTaskTest {

    @TempDir
    Path dir;

    private final AppendStepTask task = new AppendStepTask();

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private TaskContext context(Map<String, Object> config) {
        TaskContext context = TaskContext.of(config, Collections.singletonList(row("ignored", 1)));
        context.outputs.put("men", Dataset.of(Arrays.asList(row("measure", "bmi", "mean", 27.9, "n", 100L),
            row("measure", "age", "mean", 51.2, "n", 100L))));
        context.outputs.put("women", Dataset.of(Arrays.asList(row("measure", "bmi", "n", "98", "mean", "26.4", "note", "two missing"))));
        context.outputs.put("total", Dataset.of(Collections.singletonList(row("measure", "bmi", "mean", 27.1))));
        return context;
    }

    @Test
    void everyColumnByNameInTheOrderFirstSeenAMissingOneEmpty() throws Exception {
        StepResult result = this.task.run(this.context(config("steps", Arrays.asList("men", "women", "total"), "sourceColumn", "group")));
        Dataset out = result.getOutput();
        assertThat(out.getColumns()).containsExactly("measure", "mean", "n", "note", "group");
        assertThat(out.getRows()).containsExactly(
            row("measure", "bmi", "mean", 27.9, "n", 100L, "note", null, "group", "men"),
            row("measure", "age", "mean", 51.2, "n", 100L, "note", null, "group", "men"),
            // The women's numbers came as text: converted to the kind the men's set.
            row("measure", "bmi", "mean", 26.4, "n", 98L, "note", "two missing", "group", "women"),
            row("measure", "bmi", "mean", 27.1, "n", null, "note", null, "group", "total"));
    }

    @Test
    void onlyTheCommonColumnsWhenAsked() throws Exception {
        StepResult result = this.task.run(this.context(config("steps", Arrays.asList("men", "women", "total"), "columns", "common")));
        assertThat(result.getOutput().getColumns()).containsExactly("measure", "mean");
        assertThat(result.getOutput().size()).isEqualTo(4);
    }

    /**
     * ZT-S9 (showcase job 11, 2026-10-27): "common" with an input that found nothing -- no rows, and so no columns (a read of
     * files since the last run) -- keeps the other steps' common columns; it took every column away, and the next step failed.
     * An empty input that does know its columns still counts.
     */
    @Test
    void anEmptyInputThatKnowsNoColumnsTakesNoColumnsAwayFromCommon() throws Exception {
        TaskContext context = this.context(config("steps", Arrays.asList("men", "nothing_new", "total"), "columns", "common"));
        context.outputs.put("nothing_new", new Dataset(Collections.emptyList(), Collections.emptyList()));
        Dataset out = this.task.run(context).getOutput();
        assertThat(out.getColumns()).containsExactly("measure", "mean");
        assertThat(out.size()).isEqualTo(3);

        TaskContext known = this.context(config("steps", Arrays.asList("men", "empty_known"), "columns", "common"));
        known.outputs.put("empty_known", new Dataset(Arrays.asList("measure"), Collections.emptyList()));
        assertThat(this.task.run(known).getOutput().getColumns()).containsExactly("measure");
    }

    @Test
    void aValueThatCannotBeConvertedFailsNamingTheRowStepAndColumn() {
        TaskContext context = this.context(config("steps", Arrays.asList("men", "odd")));
        context.outputs.put("odd", Dataset.of(Arrays.asList(row("measure", "bmi", "mean", 30), row("measure", "bp", "mean", "n/a"))));
        assertThatThrownBy(() -> this.task.run(context)).hasMessage("Row 2 of <odd>, column mean: 'n/a' is not a number (its values are "
            + "numbers in <men>). Name the column's kind under types (text keeps every value), or turn off Convert mismatched values.");
    }

    @Test
    void namedTypesConvertEveryStepAndCoerceOffKeepsValuesAsTheyAre() throws Exception {
        TaskContext typed = this.context(config("steps", Arrays.asList("men", "women"), "types", config("mean", "text", "n", "number")));
        assertThat(this.task.run(typed).getOutput().getRows()).extracting(r -> r.get("mean")).containsExactly("27.9", "51.2", "26.4");
        TaskContext raw = this.context(config("steps", Arrays.asList("men", "women"), "coerce", false));
        assertThat(this.task.run(raw).getOutput().getRows()).extracting(r -> r.get("mean")).containsExactly(27.9, 51.2, "26.4");
        TaskContext odd = this.context(config("steps", Arrays.asList("men", "odd"), "types", config("mean", "text")));
        odd.outputs.put("odd", Dataset.of(Collections.singletonList(row("measure", "bp", "mean", "n/a", "flag", "yes"))));
        assertThat(this.task.run(odd).getOutput().getRows().get(2).get("mean")).isEqualTo("n/a");
        TaskContext flags = this.context(config("steps", Arrays.asList("odd", "more"), "types", config("flag", "boolean")));
        flags.outputs.put("odd", Dataset.of(Collections.singletonList(row("flag", "yes"))));
        flags.outputs.put("more", Dataset.of(Arrays.asList(row("flag", 0), row("flag", "FALSE"))));
        assertThat(this.task.run(flags).getOutput().getRows()).extracting(r -> r.get("flag")).containsExactly(true, false, false);
    }

    @Test
    void settingsAreChecked() {
        assertThat(this.task.check(config("steps", Arrays.asList("a", "b", "a"))))
            .containsExactly(new DefinitionProblem("steps[2]", "'a' is already named"));
        assertThatThrownBy(() -> this.task.run(this.context(config("steps", Arrays.asList("men", "women"), "sourceColumn", "measure"))))
            .hasMessage("The source column 'measure' is already a column of the steps; name another.");
    }

    @Test
    void itStreamsOneBatchAtATimeOverBigInputs() throws Exception {
        List<Map<String, Object>> a = new ArrayList<>();
        List<Map<String, Object>> b = new ArrayList<>();
        for (int i = 0; i < 60_000; i++) {
            a.add(row("id", (long) i, "v", "x"));
            b.add(row("id", String.valueOf(60_000 + i), "w", i));
        }
        StreamTry stream = new StreamTry(config("steps", Arrays.asList("a", "b")), Dataset.EMPTY, 8L << 20, this.dir, 1024);
        stream.outputs.put("a", Dataset.of(a));
        stream.outputs.put("b", Dataset.of(b));
        stream.keep = false;
        long[] last = {-1};
        stream.check = r -> {
            assertThat((Long) r.get("id")).isEqualTo(last[0] + 1);
            last[0] = (Long) r.get("id");
        };
        StepResult result = this.task.stream(stream);
        assertThat(result.getRecordsOut()).isEqualTo(120_000L);
        assertThat(stream.declared).containsExactly("id", "v", "w");
        assertThat(stream.lines).contains("INFO Stacked 120,000 row(s) from 2 steps (<a> 60,000, <b> 60,000), 3 column(s).",
            "INFO 60,000 value(s) converted to their column's kind.");
    }
}

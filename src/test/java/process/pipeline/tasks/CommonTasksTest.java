package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepResult;
import process.pipeline.StreamingStepTask;

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
 * Owner #16 (2026-10-19): the common tasks added to the Task Registry -- Remove duplicates, Split column, Pivot, Unpivot and
 * Fill missing values -- each streamed as the engine runs it (StreamTry: batches in, a sink out, a memory budget), each
 * checking its config before a run, and the two that hold state failing past the run's memory budget, saying so.
 */
class CommonTasksTest {

    @TempDir
    Path dir;

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private StreamTry run(StreamingStepTask task, Map<String, Object> config, List<Map<String, Object>> rows) throws Exception {
        return this.run(task, config, rows, 128L << 20);
    }

    private StreamTry run(StreamingStepTask task, Map<String, Object> config, List<Map<String, Object>> rows, long budget) throws Exception {
        assertThat(task.check(config)).as("config problems").isEmpty();
        StreamTry stream = new StreamTry(config, Dataset.of(rows), budget, this.dir, 2);
        StepResult result = task.stream(stream);
        assertThat(result.getRecordsOut()).isEqualTo((long) stream.rows.size());
        assertThat(stream.memory.used()).as("everything held is given back").isZero();
        return stream;
    }

    // ---------------------------------------------------------------------------------------------------------- dedupe

    private final List<Map<String, Object>> visits = Arrays.asList(
        row("patient", "P1", "ward", "A", "visit", 1), row("patient", "p1 ", "ward", "A", "visit", 2),
        row("patient", "P2", "ward", "B", "visit", 3), row("patient", "P1", "ward", "A", "visit", 4),
        row("patient", 10, "ward", "C", "visit", 5), row("patient", "10", "ward", "C", "visit", 6));

    @Test
    void dedupeKeepsTheFirstOrTheLastRowOfEachKeyInInputOrder() throws Exception {
        StreamTry first = this.run(new DedupeStepTask(), config("by", Collections.singletonList("patient")), this.visits);
        assertThat(first.rows).extracting(r -> r.get("visit")).containsExactly(1, 2, 3, 5);
        assertThat(first.lines).containsExactly("INFO 6 row(s) in, 4 kept, 2 duplicate(s) removed (key: patient; kept the first of each).");

        StreamTry last = this.run(new DedupeStepTask(), config("by", Collections.singletonList("patient"), "keep", "last"), this.visits);
        assertThat(last.rows).as("10 and \"10\" are one key").extracting(r -> r.get("visit")).containsExactly(2, 3, 4, 6);

        StreamTry folded = this.run(new DedupeStepTask(), config("by", Arrays.asList("patient", "ward"), "ignoreCase", true), this.visits);
        assertThat(folded.rows).extracting(r -> r.get("visit")).containsExactly(1, 3, 5);
        assertThat(folded.declared).containsExactly("patient", "ward", "visit");
    }

    @Test
    void dedupeWithNoKeyComparesWholeRowsAndFailsPastTheBudget() throws Exception {
        List<Map<String, Object>> rows = Arrays.asList(row("a", 1, "b", "x"), row("a", 1, "b", "x"), row("a", 1, "b", "y"));
        assertThat(this.run(new DedupeStepTask(), config(), rows).rows).hasSize(2);

        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 12_000; i++) {
            many.add(row("id", i));
        }
        // The smallest budget a run has is 1 MB: 10,922 fingerprints.
        StreamTry tight = new StreamTry(config("by", Collections.singletonList("id")), Dataset.of(many), 1, this.dir, 1000);
        assertThatThrownBy(() -> new DedupeStepTask().stream(tight)).hasMessageContaining("10,922 keys passed the run's memory budget (1.0 MB)");
        assertThat(tight.memory.used()).isZero();

        assertThat(new DedupeStepTask().check(config("by", Arrays.asList("a", "a"), "other", 1)))
            .extracting(DefinitionProblem::toString).containsExactlyInAnyOrder("other: unknown setting 'other'", "by[1]: 'a' is named twice");
        StreamTry missing = new StreamTry(config("by", Collections.singletonList("nope")), Dataset.of(rows), 1L << 20, this.dir, 10);
        assertThatThrownBy(() -> new DedupeStepTask().stream(missing)).hasMessage("The input has no column nope.");
    }

    // ---------------------------------------------------------------------------------------------------- split_column

    @Test
    void splitColumnIntoNamedColumnsKeepsTheRestInTheLastAndLeavesMissingPartsEmpty() throws Exception {
        List<Map<String, Object>> rows = Arrays.asList(row("id", 1, "name", "Doe, Jane, Q"), row("id", 2, "name", "Smith"), row("id", 3, "name", null));
        StreamTry split = this.run(new SplitColumnStepTask(), config("column", "name", "into", Arrays.asList("last", "first")), rows);
        assertThat(split.declared).containsExactly("id", "last", "first");
        assertThat(split.rows).containsExactly(row("id", 1, "last", "Doe", "first", "Jane, Q"), row("id", 2, "last", "Smith", "first", null),
            row("id", 3, "last", null, "first", null));

        StreamTry kept = this.run(new SplitColumnStepTask(), config("column", "name", "separator", ", ", "into", Arrays.asList("a", "b", "c"),
            "keepOriginal", true), rows.subList(0, 1));
        assertThat(kept.rows).containsExactly(row("id", 1, "name", "Doe, Jane, Q", "a", "Doe", "b", "Jane", "c", "Q"));
        assertThat(new SplitColumnStepTask().check(config("column", "name"))).extracting(DefinitionProblem::toString)
            .containsExactly("into: name the columns the parts go into");
    }

    @Test
    void splitColumnIntoRowsMakesARowPerPartAndKeepsARowWithNothingToSplit() throws Exception {
        List<Map<String, Object>> rows = Arrays.asList(row("id", 1, "codes", "E11|I10||J45"), row("id", 2, "codes", ""));
        StreamTry split = this.run(new SplitColumnStepTask(), config("column", "codes", "separator", "|", "mode", "rows",
            "into", Collections.singletonList("code")), rows);
        assertThat(split.declared).containsExactly("id", "code");
        assertThat(split.rows).containsExactly(row("id", 1, "code", "E11"), row("id", 1, "code", "I10"), row("id", 1, "code", "J45"),
            row("id", 2, "code", null));
        assertThat(split.lines).containsExactly("INFO 2 row(s) in, 4 out: 'codes' split at \"|\", a row per part.");
    }

    // ----------------------------------------------------------------------------------------------------------- pivot

    private final List<Map<String, Object>> labs = Arrays.asList(
        row("patient", "P1", "test", "HbA1c", "result", "7.1"), row("patient", "P1", "test", "LDL", "result", 130),
        row("patient", "P2", "test", "LDL", "result", 99), row("patient", "P1", "test", "HbA1c", "result", 6.9),
        row("patient", "P2", "test", "", "result", 1));

    @Test
    void pivotMakesARowPerGroupAndAColumnPerNameInTheOrderMet() throws Exception {
        StreamTry first = this.run(new PivotStepTask(), config("groupBy", Collections.singletonList("patient"), "column", "test", "value", "result"),
            this.labs);
        assertThat(first.declared).containsExactly("patient", "HbA1c", "LDL");
        assertThat(first.rows).containsExactly(row("patient", "P1", "HbA1c", "7.1", "LDL", 130), row("patient", "P2", "HbA1c", null, "LDL", 99));

        StreamTry avg = this.run(new PivotStepTask(), config("groupBy", Collections.singletonList("patient"), "column", "test", "value", "result",
            "op", "avg", "columns", Collections.singletonList("HbA1c")), this.labs);
        assertThat(avg.rows).containsExactly(row("patient", "P1", "HbA1c", 7L));

        StreamTry count = this.run(new PivotStepTask(), config("groupBy", Collections.singletonList("patient"), "column", "test", "op", "count"),
            this.labs);
        assertThat(count.rows).containsExactly(row("patient", "P1", "HbA1c", 2L, "LDL", 1L), row("patient", "P2", "HbA1c", 0L, "LDL", 1L));
    }

    @Test
    void pivotRefusesWhatItCannotDo() throws Exception {
        assertThat(new PivotStepTask().check(config("groupBy", Collections.singletonList("test"), "column", "test", "op", "sum")))
            .extracting(DefinitionProblem::toString)
            .containsExactlyInAnyOrder("value: sum needs the column the values come from", "column: 'test' is also a Row per column");
        StreamTry text = new StreamTry(config("groupBy", Collections.singletonList("patient"), "column", "test", "value", "patient", "op", "sum"),
            Dataset.of(this.labs), 1L << 20, this.dir, 10);
        assertThatThrownBy(() -> new PivotStepTask().stream(text)).hasMessage("Row 1: the value for 'HbA1c' is not a number (sum).");
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            many.add(row("patient", "P" + i, "test", "LDL", "result", i));
        }
        StreamTry tight = new StreamTry(config("groupBy", Collections.singletonList("patient"), "column", "test", "value", "result"),
            Dataset.of(many), 1, this.dir, 1000);
        assertThatThrownBy(() -> new PivotStepTask().stream(tight)).hasMessageContaining("passed the run's memory budget");
        assertThat(tight.memory.used()).isZero();
    }

    // --------------------------------------------------------------------------------------------------------- unpivot

    @Test
    void unpivotMakesARowPerNamedColumnAndLeavesEmptyValuesOutUnlessAsked() throws Exception {
        List<Map<String, Object>> rows = Arrays.asList(row("ward", "A", "jan", 10, "feb", null, "mar", 12));
        StreamTry long1 = this.run(new UnpivotStepTask(), config("columns", Arrays.asList("jan", "feb", "mar"), "nameAs", "month",
            "valueAs", "admissions"), rows);
        assertThat(long1.declared).containsExactly("ward", "month", "admissions");
        assertThat(long1.rows).containsExactly(row("ward", "A", "month", "jan", "admissions", 10), row("ward", "A", "month", "mar", "admissions", 12));
        StreamTry kept = this.run(new UnpivotStepTask(), config("columns", Arrays.asList("jan", "feb"), "keepEmpty", true), rows);
        assertThat(kept.rows).containsExactly(row("ward", "A", "mar", 12, "name", "jan", "value", 10), row("ward", "A", "mar", 12, "name", "feb", "value", null));
        StreamTry clash = new StreamTry(config("columns", Collections.singletonList("jan"), "nameAs", "ward"), Dataset.of(rows), 1L << 20, this.dir, 10);
        assertThatThrownBy(() -> new UnpivotStepTask().stream(clash)).hasMessage("The input already has a column 'ward'; name the new columns differently.");
        assertThat(new UnpivotStepTask().check(config("columns", Collections.singletonList("jan"), "nameAs", "x", "valueAs", "x")))
            .extracting(DefinitionProblem::toString).containsExactly("valueAs: the name and value columns need different names");
    }

    // ---------------------------------------------------------------------------------------------------- fill_missing

    @Test
    void fillMissingFillsWithAValueOrTheLastValueAbove() throws Exception {
        List<Map<String, Object>> rows = Arrays.asList(row("ward", "A", "score", null), row("ward", null, "score", 3),
            row("ward", "  ", "score", ""), row("ward", "B", "score", 5));
        StreamTry filled = this.run(new FillMissingStepTask(), config("fills", Arrays.asList(config("column", "ward", "with", "previous"),
            config("column", "score", "value", 0))), rows);
        assertThat(filled.rows).containsExactly(row("ward", "A", "score", 0), row("ward", "A", "score", 3), row("ward", "A", "score", 0),
            row("ward", "B", "score", 5));
        assertThat(filled.lines).containsExactly("INFO 4 row(s); cells filled: ward 2, score 2.");
        assertThat(new FillMissingStepTask().check(config("fills", Arrays.asList(config("column", "a"), config("column", "a", "with", "previous")))))
            .extracting(DefinitionProblem::toString)
            .containsExactlyInAnyOrder("fills[0].value: what an empty cell gets is required", "fills[1].column: 'a' is filled twice");
    }
}

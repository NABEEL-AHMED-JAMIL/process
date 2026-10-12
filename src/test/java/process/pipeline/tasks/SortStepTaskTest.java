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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;

/**
 * MIG-365: Sort orders rows by columns -- numbers as numbers (CSV text too) before text, empty values last unless asked
 * first in either direction, ties in input order -- in memory when they fit the run's budget, and past it by sorted runs
 * on disk merged back (more than one pass for more than 64 runs), always with the same answer and no file left behind.
 */
class SortStepTaskTest {

    @TempDir
    Path dir;

    private final SortStepTask task = new SortStepTask();

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private static Map<String, Object> by(String column, String direction, String empty) {
        Map<String, Object> by = new LinkedHashMap<>();
        by.put("column", column);
        if (direction != null) {
            by.put("direction", direction);
        }
        if (empty != null) {
            by.put("empty", empty);
        }
        return by;
    }

    private List<Object> sorted(List<Map<String, Object>> rows, String column, Map<String, Object> config) throws Exception {
        TaskContext context = TaskContext.of(config, rows);
        StepResult result = this.task.run(context);
        List<Object> values = new ArrayList<>();
        result.getOutput().getRows().forEach(r -> values.add(r.get(column)));
        return values;
    }

    @Test
    void numbersSortAsNumbersBeforeTextAndEmptyValuesGoLast() throws Exception {
        List<Map<String, Object>> rows = Arrays.asList(row("v", "10"), row("v", "9"), row("v", "beta"), row("v", null), row("v", 2.5),
            row("v", ""), row("v", "Alpha"), row("v", -3L), row("v", "1e2"), row("v", true));
        assertThat(this.sorted(rows, "v", config("by", Collections.singletonList(by("v", null, null)))))
            .containsExactly(-3L, 2.5, "9", "10", "1e2", "Alpha", "beta", true, null, "");
        assertThat(this.sorted(rows, "v", config("by", Collections.singletonList(by("v", "desc", null)))))
            .containsExactly(true, "beta", "Alpha", "1e2", "10", "9", 2.5, -3L, null, "");
        assertThat(this.sorted(rows, "v", config("by", Collections.singletonList(by("v", "desc", "first")))).subList(0, 2))
            .containsExactly(null, "");
    }

    @Test
    void severalColumnsAndTiesKeepTheirInputOrder() throws Exception {
        List<Map<String, Object>> rows = Arrays.asList(row("ward", "B", "age", "70", "id", 1), row("ward", "A", "age", "61", "id", 2),
            row("ward", "B", "age", "70", "id", 3), row("ward", "A", "age", "80", "id", 4), row("ward", "B", "age", "45", "id", 5));
        assertThat(this.sorted(rows, "id", config("by", Arrays.asList(by("ward", null, null), by("age", "desc", null)))))
            .containsExactly(4, 2, 1, 3, 5);
        assertThat(TaskContext.of(config("by", Collections.singletonList(by("ward", null, null))), rows).input.getColumns())
            .containsExactly("ward", "age", "id");
    }

    @Test
    void isoDatesSortInDateOrderAndTheColumnsAreKept() throws Exception {
        List<Map<String, Object>> rows = Arrays.asList(row("d", "2026-10-12", "x", 1), row("d", "2025-01-01", "x", 2),
            row("d", "2026-02-28T10:00", "x", 3));
        StepResult result = this.task.run(TaskContext.of(config("by", Collections.singletonList(by("d", null, null))), rows));
        assertThat(result.getOutput().getColumns()).containsExactly("d", "x");
        assertThat(result.getOutput().getRows()).extracting(r -> r.get("x")).containsExactly(2, 3, 1);
    }

    @Test
    void aColumnTheRowsDoNotHaveIsRefusedWithTheOnesTheyHave() {
        assertThatThrownBy(() -> this.task.run(TaskContext.of(config("by", Collections.singletonList(by("nope", null, null))),
            Collections.singletonList(row("a", 1, "b", 2))))).hasMessage("The input has no column nope; its columns are: a, b.");
        assertThat(this.task.check(config("by", Arrays.asList(by("a", null, null), by("a", "desc", null)))))
            .containsExactly(new DefinitionProblem("by[1].column", "'a' is already in the order"));
    }

    /** Random rows: a number column with duplicates and gaps, a text column, a padding column to make rows heavy. */
    private static Dataset random(int rows, long seed) {
        Random random = new Random(seed);
        char[] pad = new char[200];
        Arrays.fill(pad, 'x');
        List<Map<String, Object>> out = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("n", random.nextInt(20) == 0 ? "" : String.valueOf(random.nextInt(5000)));
            row.put("t", "k" + random.nextInt(300));
            row.put("seq", (long) i);
            row.put("pad", new String(pad));
            out.add(row);
        }
        return Dataset.of(out);
    }

    /** What a stable in-memory sort by n desc (empty last), t asc gives: the seq column in order. */
    private static List<Object> expected(Dataset data) {
        List<Map<String, Object>> rows = new ArrayList<>(data.getRows());
        Comparator<Map<String, Object>> byN = Comparator.comparing(r -> {
            String n = (String) r.get("n");
            return n.isEmpty() ? Integer.MIN_VALUE : Integer.parseInt(n);
        });
        rows.sort(byN.reversed().thenComparing(r -> (String) r.get("t")));
        List<Object> seq = new ArrayList<>();
        rows.forEach(r -> seq.add(r.get("seq")));
        return seq;
    }

    private Map<String, Object> order() {
        return config("by", Arrays.asList(by("n", "desc", null), by("t", null, null)));
    }

    @Test
    void pastTheBudgetItSpillsSortedRunsAndMergesThemToTheSameAnswer() throws Exception {
        Dataset data = random(20_000, 7);
        StreamTry fits = new StreamTry(this.order(), data, 512L << 20, this.dir.resolve("a"), 1024);
        this.task.stream(fits);
        assertThat(fits.lines.get(0)).startsWith("INFO Sorted 20,000 row(s) by n desc, t, in memory.");

        StreamTry tight = new StreamTry(this.order(), data, 1L << 20, this.dir.resolve("b"), 500);
        StepResult result = this.task.stream(tight);
        assertThat(result.getRecordsOut()).isEqualTo(20_000L);
        assertThat(tight.lines.get(0)).contains("so ").contains("sorted run(s) went to disk").contains("merged in 1 pass(es)");
        // A row read back from a run file holds a small number as the JSON store would (an Integer): compared as numbers.
        List<Object> seq = new ArrayList<>();
        tight.rows.forEach(r -> seq.add(((Number) r.get("seq")).longValue()));
        assertThat(seq).isEqualTo(expected(data));
        assertThat(seq).isEqualTo(fits.rows.stream().map(r -> ((Number) r.get("seq")).longValue()).collect(Collectors.toList()));
        assertThat(tight.declared).containsExactly("n", "t", "seq", "pad");
        assertThat(tight.scratchFiles()).as("no run file is left").isZero();
        assertThat(tight.memory.used()).as("everything given back").isZero();
        assertThat(tight.memory.spilled()).isGreaterThan(0);
    }

    @Test
    void moreRunsThanOneMergeReadsAreMergedInPasses() throws Exception {
        Dataset data = random(130_000, 11);
        StreamTry tight = new StreamTry(this.order(), data, 1L << 20, this.dir.resolve("c"), 500);
        tight.keep = false;
        List<Object> seq = new ArrayList<>();
        tight.check = r -> seq.add(((Number) r.get("seq")).longValue());
        this.task.stream(tight);
        assertThat(tight.lines.get(0)).containsPattern("so (6[5-9]|[7-9][0-9]|1[0-9][0-9]) sorted run\\(s\\)").contains("merged in 2 pass(es)");
        assertThat(seq).isEqualTo(expected(data));
        assertThat(tight.scratchFiles()).isZero();
        assertThat(tight.memory.used()).isZero();
    }
}

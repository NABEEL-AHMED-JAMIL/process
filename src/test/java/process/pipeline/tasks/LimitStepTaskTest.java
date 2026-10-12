package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import process.pipeline.Dataset;
import process.pipeline.StepResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static process.pipeline.Definitions.config;

/**
 * MIG-365: Limit keeps the first N rows and stops reading its input there; with an order it keeps the top N, holding
 * only those (a bounded heap), or -- when even those pass the run's budget -- sorts on disk and stops the merge at them.
 */
class LimitStepTaskTest {

    @TempDir
    Path dir;

    private final LimitStepTask task = new LimitStepTask();

    private static Dataset numbered(int rows) {
        List<Map<String, Object>> out = new ArrayList<>(rows);
        Random random = new Random(3);
        for (int i = 0; i < rows; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", (long) i);
            row.put("score", String.valueOf(random.nextInt(1000)));
            row.put("note", "row " + i + " ".repeat(100));
            out.add(row);
        }
        return Dataset.of(out);
    }

    private static Map<String, Object> by(String column, String direction) {
        Map<String, Object> by = new LinkedHashMap<>();
        by.put("column", column);
        by.put("direction", direction);
        return by;
    }

    private static List<Object> seq(List<Map<String, Object>> rows) {
        // A row read back from a run file holds a small number as the JSON store would (an Integer): compared as numbers.
        return rows.stream().map(r -> (Object) ((Number) r.get("seq")).longValue()).collect(Collectors.toList());
    }

    @Test
    void theFirstRowsAreKeptAndTheRestOfTheInputIsNotRead() throws Exception {
        Dataset data = numbered(100_000);
        StreamTry stream = new StreamTry(config("rows", 10), data, 128L << 20, this.dir, 1024);
        StepResult result = this.task.stream(stream);
        assertThat(result.getRecordsOut()).isEqualTo(10L);
        assertThat(seq(stream.rows)).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        assertThat(stream.batchesRead).as("one batch of 1,024 read, not 98").isEqualTo(1);
        assertThat(stream.declared).containsExactly("seq", "score", "note");
        assertThat(stream.lines).containsExactly("INFO Kept 10 row(s) of 100,000; read 10 row(s), the other 99,990 were not read.");
    }

    @Test
    void anOffsetSkipsRowsFirstAndAShortInputGivesWhatItHas() throws Exception {
        StreamTry page = new StreamTry(config("rows", 3, "offset", 2048), numbered(5000), 128L << 20, this.dir, 1024);
        this.task.stream(page);
        assertThat(seq(page.rows)).containsExactly(2048L, 2049L, 2050L);
        assertThat(page.batchesRead).isEqualTo(3);
        StreamTry shortOne = new StreamTry(config("rows", 50), numbered(7), 128L << 20, this.dir, 1024);
        this.task.stream(shortOne);
        assertThat(shortOne.rows).hasSize(7);
        assertThat(shortOne.lines.get(0)).isEqualTo("INFO Kept 7 row(s) of 7; read 7 row(s).");
    }

    @Test
    void theTopRowsByAnOrderHoldOnlyThoseAndTiesKeepInputOrder() throws Exception {
        Dataset data = numbered(30_000);
        StreamTry top = new StreamTry(config("rows", 5, "by", Collections.singletonList(by("score", "desc"))), data, 128L << 20, this.dir, 1024);
        this.task.stream(top);
        List<Map<String, Object>> expected = new ArrayList<>(data.getRows());
        expected.sort((a, b) -> Integer.compare(Integer.parseInt((String) b.get("score")), Integer.parseInt((String) a.get("score"))));
        assertThat(seq(top.rows)).isEqualTo(seq(expected.subList(0, 5)));
        assertThat(top.lines.get(0)).isEqualTo("INFO Kept the top 5 row(s) of 30,000 by score desc, holding at most 5 at once.");
        assertThat(top.memory.used()).isZero();

        StreamTry paged = new StreamTry(config("rows", 4, "offset", 3, "by", Collections.singletonList(by("score", "asc"))), data,
            128L << 20, this.dir, 1024);
        this.task.stream(paged);
        List<Map<String, Object>> ascending = new ArrayList<>(data.getRows());
        ascending.sort((a, b) -> Integer.compare(Integer.parseInt((String) a.get("score")), Integer.parseInt((String) b.get("score"))));
        assertThat(seq(paged.rows)).isEqualTo(seq(ascending.subList(3, 7)));
    }

    @Test
    void topRowsTooManyForTheBudgetAreFoundBySortingOnDisk() throws Exception {
        Dataset data = numbered(40_000);
        StreamTry tight = new StreamTry(config("rows", 15_000, "by", Arrays.asList(by("score", "desc"), by("seq", "asc"))), data,
            1L << 20, this.dir, 500);
        StepResult result = this.task.stream(tight);
        List<Map<String, Object>> expected = new ArrayList<>(data.getRows());
        expected.sort((a, b) -> Integer.compare(Integer.parseInt((String) b.get("score")), Integer.parseInt((String) a.get("score"))));
        assertThat(result.getRecordsOut()).isEqualTo(15_000L);
        assertThat(seq(tight.rows)).isEqualTo(seq(expected.subList(0, 15_000)));
        assertThat(tight.lines.get(0)).contains("15,000 rows would not fit the run's memory budget (1.0 MB), so the input was sorted on disk");
        assertThat(tight.scratchFiles()).isZero();
        assertThat(tight.memory.used()).isZero();
    }
}

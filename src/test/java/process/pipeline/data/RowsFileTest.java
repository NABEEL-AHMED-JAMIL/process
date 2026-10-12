package process.pipeline.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import process.pipeline.Dataset;
import process.pipeline.FileDatasetStore;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MIG-344: Core's row file gives back exactly what the JSON dataset store gives back. */
class RowsFileTest {

    @TempDir
    Path dir;

    private static Map<String, Object> row(Object... keyValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            row.put((String) keyValues[i], keyValues[i + 1]);
        }
        return row;
    }

    /** Rows of every kind of value a step makes, keys missing and out of order, through both stores. */
    @Test
    void readsBackWhatTheJsonStoreReadsBack() throws Exception {
        Map<String, Object> nested = row("a", 1L, "b", Arrays.asList(1.5, "x", null, row("deep", true)));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 70_000; i++) {
            big.append((char) ('a' + i % 26));
        }
        List<Map<String, Object>> rows = Arrays.asList(
            row("id", 1, "name", "Ada", "amount", "10.50", "ok", true),
            row("name", "Bø 🦀 ünïcode", "id", 2L, "ok", false, "extra", null),
            row("id", 3_000_000_000L, "amount", new BigDecimal("1.50"), "f", 0.1f, "s", (short) 7),
            row("id", -4, "d", 12.25, "nan", Double.NaN, "inf", Double.NEGATIVE_INFINITY, "bi", new BigInteger("123456789012345678901234567890")),
            row("nested", nested, "list", Collections.singletonList("only"), "empty", "", "big", big.toString()),
            row(),
            row("id", Long.MIN_VALUE, "small", 0L, "neg", -1L, "dbl", -0.0));
        Dataset dataset = new Dataset(Arrays.asList("id", "name", "amount", "ok"), rows);

        FileDatasetStore store = new FileDatasetStore(this.dir.toString());
        store.write("datasets/1/1/s/output.json", dataset);
        store.write("datasets/1/1/s/output.rows", dataset);
        Dataset fromJson = store.read("datasets/1/1/s/output.json");
        Dataset fromRows = store.read("datasets/1/1/s/output.rows");

        assertThat(fromRows.getColumns()).isEqualTo(fromJson.getColumns()).containsExactly("id", "name", "amount", "ok");
        assertThat(fromRows.size()).isEqualTo(fromJson.size());
        for (int i = 0; i < fromJson.size(); i++) {
            Map<String, Object> expected = fromJson.getRows().get(i);
            Map<String, Object> actual = fromRows.getRows().get(i);
            assertThat(new ArrayList<>(actual.keySet())).as("row %d's keys, in order", i).isEqualTo(new ArrayList<>(expected.keySet()));
            for (String key : expected.keySet()) {
                Object want = expected.get(key);
                Object got = actual.get(key);
                assertThat(got).as("row %d, %s", i, key).isEqualTo(want);
                assertThat(got == null ? null : got.getClass()).as("row %d, %s's type", i, key).isEqualTo(want == null ? null : want.getClass());
            }
        }
        // And the types are the ones Jackson reads: a small Long is an Integer, a BigDecimal a Double, NaN text.
        assertThat(fromRows.getRows().get(1).get("id")).isEqualTo(2);
        assertThat(fromRows.getRows().get(2).get("amount")).isEqualTo(1.5);
        assertThat(fromRows.getRows().get(3).get("nan")).isEqualTo("NaN");
        assertThat(fromRows.getRows().get(1)).containsKey("extra").doesNotContainKey("amount");
    }

    @Test
    void randomRowsMatchTheJsonStoreToo() throws Exception {
        Random random = new Random(344);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            int width = random.nextInt(8);
            for (int c = 0; c < width; c++) {
                String key = "c" + random.nextInt(12);
                switch (random.nextInt(7)) {
                    case 0:
                        row.put(key, null);
                        break;
                    case 1:
                        row.put(key, Long.toString(random.nextLong(), 36));
                        break;
                    case 2:
                        row.put(key, random.nextInt());
                        break;
                    case 3:
                        row.put(key, random.nextLong());
                        break;
                    case 4:
                        row.put(key, random.nextDouble() * 1e6 - 5e5);
                        break;
                    case 5:
                        row.put(key, random.nextBoolean());
                        break;
                    default:
                        row.put(key, new BigDecimal(random.nextInt(100000)).movePointLeft(random.nextInt(4)));
                        break;
                }
            }
            rows.add(row);
        }
        Dataset dataset = Dataset.of(rows);
        FileDatasetStore store = new FileDatasetStore(this.dir.toString());
        store.write("datasets/2/1/s/output.json", dataset);
        store.write("datasets/2/1/s/output.rows", dataset);
        Dataset fromJson = store.read("datasets/2/1/s/output.json");
        Dataset fromRows = store.read("datasets/2/1/s/output.rows");
        assertThat(fromRows.getColumns()).isEqualTo(fromJson.getColumns());
        assertThat(fromRows.getRows()).isEqualTo(fromJson.getRows());
        for (int i = 0; i < rows.size(); i++) {
            assertThat(new ArrayList<>(fromRows.getRows().get(i).keySet())).isEqualTo(new ArrayList<>(fromJson.getRows().get(i).keySet()));
        }
    }

    @Test
    void readsInBatchesAndKnowsItsSizeAndColumnsFirst() throws Exception {
        Path file = this.dir.resolve("a.rows");
        RowsFile.Writer writer = RowsFile.create(file);
        for (int i = 0; i < 2_500; i++) {
            writer.add(row("n", i, "half", i % 2 == 0 ? "even" : null));
        }
        writer.add(row("late", "x"));
        assertThat(writer.commit()).isPositive();
        try (RowsFile.Reader reader = RowsFile.open(file, 1000)) {
            assertThat(reader.size()).isEqualTo(2_501);
            assertThat(reader.columns()).containsExactly("n", "half", "late");
            assertThat(reader.next()).hasSize(1000);
            assertThat(reader.next()).hasSize(1000);
            List<Map<String, Object>> last = reader.next();
            assertThat(last).hasSize(501);
            assertThat(last.get(500)).containsExactly(Map.entry("late", "x"));
            assertThat(reader.next()).isNull();
            assertThat(reader.next()).isNull();
        }
    }

    @Test
    void anEmptyDatasetKeepsItsDeclaredColumns() throws Exception {
        Path file = this.dir.resolve("empty.rows");
        RowsFile.Writer writer = RowsFile.create(file);
        writer.declare(Arrays.asList("a", "b"));
        writer.commit();
        try (RowsFile.Reader reader = RowsFile.open(file, 10)) {
            assertThat(reader.size()).isZero();
            assertThat(reader.columns()).containsExactly("a", "b");
            assertThat(reader.next()).isNull();
        }
    }

    @Test
    void anAbortedWriteLeavesNothingAndACommittedOneNoPartialFile() throws Exception {
        Path file = this.dir.resolve("x").resolve("gone.rows");
        RowsFile.Writer writer = RowsFile.create(file);
        writer.add(row("a", 1));
        writer.abort();
        assertThat(file).doesNotExist();
        try (Stream<Path> left = Files.list(file.getParent())) {
            assertThat(left).isEmpty();
        }
        RowsFile.Writer kept = RowsFile.create(file);
        kept.add(row("a", 1));
        kept.commit();
        kept.close();
        try (Stream<Path> left = Files.list(file.getParent())) {
            assertThat(left).containsExactly(file);
        }
    }

    @Test
    void aFileThatIsNotOneIsRefused() throws Exception {
        Path file = this.dir.resolve("bad.rows");
        Files.write(file, "id,name\n1,Ada\n".getBytes());
        assertThatThrownBy(() -> RowsFile.open(file, 10)).hasMessageContaining("Not a dataset file");
    }
}

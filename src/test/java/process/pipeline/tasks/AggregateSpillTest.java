package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import process.pipeline.Dataset;
import process.pipeline.FileDatasetStore;
import process.pipeline.RunOutput;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.data.RowSink;
import process.pipeline.data.RowSource;
import process.pipeline.data.RunMemory;

import java.io.IOException;
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
import static process.pipeline.Definitions.config;

/**
 * MIG-344: an aggregate whose groups pass the run's memory budget spills to disk and still gives exactly the in-memory
 * answer -- the same groups in the same order with the same values, and the same error for the first bad row.
 */
class AggregateSpillTest {

    @TempDir
    Path dir;

    private final AggregateStepTask task = new AggregateStepTask();

    /** A streaming try over rows in memory, with a budget of its own and a real scratch directory. */
    final class Tight implements StreamContext {
        final Map<String, Object> config;
        final Dataset input;
        final RunMemory memory;
        final RowSink.Collecting sink = RowSink.collecting("The output");
        final List<String> lines = new ArrayList<>();
        int opened;
        Path scratch;

        Tight(Map<String, Object> config, Dataset input, long budgetBytes) {
            this.config = config;
            this.input = input;
            this.memory = new RunMemory(budgetBytes);
        }

        @Override
        public RowSource openInput() {
            this.opened++;
            return RowSource.of(this.input, 100);
        }

        @Override public RowSink output() { return this.sink; }

        @Override
        public Path scratch() throws IOException {
            if (this.scratch == null) {
                this.scratch = Files.createDirectories(dir.resolve("scratch"));
            }
            return this.scratch;
        }

        @Override public RunMemory memory() { return this.memory; }

        @Override public long maxFileBytes() { return Long.MAX_VALUE; }

        @Override public long maxRows() { return Long.MAX_VALUE; }

        @Override public KeptFile keepFile(String fileName, List<String> columns, long rows, FileWriter writer) { throw new UnsupportedOperationException(); }

        @Override public long tenantId() { return 1; }

        @Override public long jobQueueId() { return 2; }

        @Override public int attempt() { return 1; }

        @Override public String stepKey() { return "totals"; }

        @Override public int tryNumber() { return 1; }

        @Override public Map<String, Object> config() { return this.config; }

        @Override public Dataset input() { return this.input; }

        @Override public void keepFile(String fileName, byte[] content, long rows, List<String> columns) { throw new UnsupportedOperationException(); }

        @Override public void recordOutput(RunOutput output) { }

        @Override public void log(String message) { this.lines.add("INFO " + message); }

        @Override public void warn(String message) { this.lines.add("WARN " + message); }
    }

    private static Dataset orders(int rows, int customers, long seed) {
        Random random = new Random(seed);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("customer", "C-" + random.nextInt(customers));
            row.put("region", Arrays.asList("north", "south", "east", "west").get(random.nextInt(4)));
            row.put("amount", random.nextInt(10) == 0 ? "" : String.valueOf(random.nextInt(100000) / 100.0));
            row.put("score", random.nextInt(5) == 0 ? null : (Object) random.nextInt(100));
            row.put("tag", "t" + random.nextInt(30));
            out.add(row);
        }
        return Dataset.of(out);
    }

    private static Map<String, Object> aggregations(String... groupBy) {
        return config("groupBy", Arrays.asList(groupBy), "aggregations", Arrays.asList(
            config("op", "count", "as", "n"),
            config("op", "count_distinct", "column", "tag", "as", "tags"),
            config("op", "sum", "column", "amount", "as", "total"),
            config("op", "avg", "column", "amount", "as", "mean"),
            config("op", "min", "column", "score", "as", "low"),
            config("op", "max", "column", "score", "as", "high"),
            config("op", "first", "column", "amount", "as", "first_amount"),
            config("op", "last", "column", "tag", "as", "last_tag"),
            config("op", "list", "column", "region", "as", "regions")));
    }

    /** Rows as the dataset store gives them back (a spilled aggregate's values went through a row file). */
    private Dataset stored(Dataset dataset, String name) throws Exception {
        FileDatasetStore store = new FileDatasetStore(this.dir.resolve("store").toString());
        String key = "datasets/9/1/" + name + "/output.rows";
        store.write(key, dataset);
        return store.read(key);
    }

    @Test
    void manyGroupsSpillAndComeOutAsInMemory() throws Exception {
        Dataset input = orders(20_000, 6_000, 1);
        Map<String, Object> config = aggregations("customer", "region");
        Dataset inMemory = this.task.run(TaskContext.of(config, input.getRows())).getOutput();

        Tight tight = new Tight(config, input, 1L << 20);
        StepResult result = this.task.stream(tight);
        Dataset spilled = tight.sink.toDataset();

        assertThat(result.isStreamed()).isTrue();
        assertThat(tight.opened).as("it read its input again to spill it").isEqualTo(2);
        assertThat(tight.lines).anyMatch(line -> line.contains("grouping in") && line.contains("partitions on disk"));
        assertThat(inMemory.size()).isGreaterThan(10_000);
        assertThat(spilled.getColumns()).isEqualTo(inMemory.getColumns());
        assertThat(this.stored(spilled, "a").getRows()).isEqualTo(this.stored(inMemory, "b").getRows());
        assertThat(tight.memory.used()).as("every reservation given back").isZero();
        try (Stream<Path> left = Files.list(tight.scratch)) {
            assertThat(left).as("partition files removed").isEmpty();
        }
    }

    @Test
    void aListThatPassesTheBudgetSpillsAndKeepsItsFirstSeenOrderAndItsMoreCount() throws Exception {
        // 30 groups, each listing hundreds of customers: the lists' values, not the groups, pass the budget.
        Dataset input = orders(20_000, 6_000, 4);
        Map<String, Object> config = config("groupBy", Collections.singletonList("tag"), "aggregations", Arrays.asList(
            config("op", "list", "column", "customer", "as", "customers"),
            config("op", "list", "column", "region", "as", "regions"),
            config("op", "count_distinct", "column", "customer", "as", "n")));
        Dataset inMemory = this.task.run(TaskContext.of(config, input.getRows())).getOutput();

        Tight tight = new Tight(config, input, 1L << 20);
        this.task.stream(tight);
        Dataset spilled = tight.sink.toDataset();

        assertThat(tight.opened).as("it spilled").isEqualTo(2);
        assertThat(inMemory.size()).isEqualTo(30);
        assertThat((String) inMemory.getRows().get(0).get("customers")).matches("C-\\d+(, C-\\d+){49} \\(\\+\\d+ more\\)");
        assertThat(this.stored(spilled, "l1").getRows()).isEqualTo(this.stored(inMemory, "l2").getRows());
        assertThat(tight.memory.used()).isZero();
        try (Stream<Path> left = Files.list(tight.scratch)) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void aGroupingWithoutKeysAndOneThatFitsDoNotSpill() throws Exception {
        Dataset input = orders(3_000, 50, 2);
        for (Map<String, Object> config : Arrays.asList(aggregations(), aggregations("region"))) {
            Tight tight = new Tight(config, input, 64L << 20);
            this.task.stream(tight);
            assertThat(tight.opened).isEqualTo(1);
            assertThat(this.stored(tight.sink.toDataset(), "c").getRows())
                .isEqualTo(this.stored(this.task.run(TaskContext.of(config, input.getRows())).getOutput(), "d").getRows());
        }
    }

    @Test
    void theFirstBadRowIsNamedWhetherOrNotItSpilled() throws Exception {
        Dataset good = orders(20_000, 6_000, 3);
        List<Map<String, Object>> rows = new ArrayList<>(good.getRows());
        // Two bad rows, in different groups: the earlier one is the one named.
        Map<String, Object> early = new LinkedHashMap<>(rows.get(15_000));
        early.put("amount", "not a number");
        rows.set(15_000, early);
        Map<String, Object> late = new LinkedHashMap<>(rows.get(18_000));
        late.put("amount", "nope");
        rows.set(18_000, late);
        Dataset input = Dataset.of(rows);
        Map<String, Object> config = aggregations("customer");
        assertThatThrownBy(() -> this.task.run(TaskContext.of(config, input.getRows())))
            .hasMessage("Row 15001: amount is not a number (sum).");
        assertThatThrownBy(() -> this.task.stream(new Tight(config, input, 1L << 20)))
            .hasMessage("Row 15001: amount is not a number (sum).");
    }

    @Test
    void oneGroupTooBigForTheBudgetSaysSo() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 60_000; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("all", "same");
            row.put("tag", "distinct-value-" + i);
            rows.add(row);
        }
        Map<String, Object> config = config("groupBy", Collections.singletonList("all"), "aggregations",
            Collections.singletonList(config("op", "count_distinct", "column", "tag", "as", "tags")));
        assertThatThrownBy(() -> this.task.stream(new Tight(config, new Dataset(Arrays.asList("all", "tag"), rows), 1L << 20)))
            .hasMessageContaining("need more than the run's memory budget");
    }
}

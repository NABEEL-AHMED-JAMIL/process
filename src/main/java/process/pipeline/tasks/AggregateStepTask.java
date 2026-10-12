package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.data.Limits;
import process.pipeline.data.RowSink;
import process.pipeline.data.RowSource;
import process.pipeline.data.RowsFile;
import process.pipeline.data.RunMemory;
import process.pipeline.data.Values;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Aggregate (MIG-231): one row per group of equal {@code groupBy} values (in the order groups first appear; one row
 * for all when none), with each aggregation as a column: count (rows), count_distinct, sum, avg, min, max, first,
 * last. sum and avg read numbers (numeric text too) and skip nulls; a value that is not a number fails the step at its
 * row. min and max compare numbers by value, else text. Pure Core, at most {@value Limits#MAX_ROWS} groups in memory.
 *
 * MIG-344: streamed, and spilled when its groups pass the run's memory budget ({@link RunMemory}). It first groups in
 * memory, asking the budget for every group and every distinct value it keeps. When the budget says no, it drops
 * what it has and starts again from its input -- a file -- in three passes: (1) each row, cut to the columns the
 * grouping reads and numbered, goes to one of N partition files by its group key's hash; (2) each partition is grouped
 * in memory (a partition still too big is partitioned again, up to three levels); (3) the partitions' groups are merged
 * by the row each group first appeared at. So the output is exactly the in-memory one -- the same groups, values and
 * order, and the same error for the first row that fails -- with only one partition's groups in memory at a time.
 */
@Component
public class AggregateStepTask extends RegisteredTask implements StreamingStepTask {

    /** The partition files' row number and the partition results' first row: names no column can have. */
    static final String ROW = "\u0000row";
    static final String FIRST = "\u0000first";

    /** How many partitions a spill cuts its input into, at least and at most. */
    static final int MIN_PARTITIONS = 8;
    static final int MAX_PARTITIONS = 256;

    /** How deep a partition that is still too big is partitioned again. */
    static final int MAX_DEPTH = 3;

    /** Groups reserve their memory from the budget in steps of this many bytes. */
    static final long RESERVE_STEP = 256 * 1024;

    static final List<String> OPS = Arrays.asList("count", "count_distinct", "sum", "avg", "min", "max", "first", "last", "list");

    /** list: at most this many distinct values are named; the rest are counted ("+3 more"). */
    static final int LIST_MAX = 50;

    static final TaskSpec SPEC = TaskSpec.builder("aggregate", "Aggregate", TaskKind.PROCESS)
        .description("Groups rows by columns and computes count, count_distinct, sum, avg, min, max, first, last or list"
            + " (the group's distinct values as one text, in the order first seen, joined by \", \").")
        .input(TaskSpec.rows("Any rows."))
        .output(TaskSpec.rows("One row per group: the group's columns, then each aggregation."))
        .config(JsonSchema.object()
            .property("groupBy", JsonSchema.array(JsonSchema.string().minLength(1).maxLength(128).format("column")).maxItems(20)
                .title("Group by").description("Empty: one row for all the input."))
            .required("aggregations", JsonSchema.array(JsonSchema.object()
                .required("op", JsonSchema.string().enumOf(OPS.toArray(new String[0])).title("Compute"))
                .property("column", JsonSchema.string().maxLength(128).title("Of column").format("column")
                    .description("Every operation but count needs one."))
                .required("as", JsonSchema.string().minLength(1).maxLength(128).title("As column")))
                .minItems(1).maxItems(50).title("Aggregations")))
        .aiToolName("aggregate_rows")
        .build();

    public AggregateStepTask() {
        super(SPEC);
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        Set<String> names = new HashSet<>(Configs.texts(config, "groupBy"));
        List<Map<String, Object>> aggregations = Configs.objects(config, "aggregations");
        for (int i = 0; i < aggregations.size(); i++) {
            Map<String, Object> aggregation = aggregations.get(i);
            if (!"count".equals(aggregation.get("op")) && Configs.text(aggregation, "column", null) == null) {
                problems.add(new DefinitionProblem("aggregations[" + i + "].column", aggregation.get("op") + " needs its column"));
            }
            String as = Configs.text(aggregation, "as", "");
            if (!names.add(as)) {
                problems.add(new DefinitionProblem("aggregations[" + i + "].as", String.format("'%s' is already a column of the output", as)));
            }
        }
        return problems;
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        List<String> groupBy = Configs.texts(context.config(), "groupBy");
        List<Map<String, Object>> aggregations = Configs.objects(context.config(), "aggregations");
        Set<String> columns = new LinkedHashSet<>(groupBy);
        for (Map<String, Object> aggregation : aggregations) {
            columns.add(Configs.text(aggregation, "as", ""));
        }
        RowSink out = context.output();
        out.declare(columns);
        RunMemory memory = context.memory();
        Grouping grouping = new Grouping(groupBy, aggregations, memory, context.inMemory());
        long rows = 0;
        boolean overflow = false;
        try (RowSource input = context.openInput()) {
            rows = input.size();
            long index = 0;
            reading:
            for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Aggregate was stopped.");
                }
                for (Map<String, Object> row : batch) {
                    if (!grouping.add(row, ++index)) {
                        overflow = true;
                        break reading;
                    }
                }
            }
        }
        if (!overflow) {
            if (groupBy.isEmpty() && grouping.groups.isEmpty()) {
                grouping.groups.put(new ArrayList<>(), new Group(new LinkedHashMap<>(), groupBy, aggregations.size(), 0));
            }
            for (Group group : grouping.groups.values()) {
                out.add(group.result(aggregations));
            }
            grouping.release();
            context.log(String.format("%d row(s) in %d group(s).", rows, out.size()));
            return StepResult.streamed(out.size());
        }
        long held = grouping.reserved;
        long seen = grouping.index;
        grouping.release();
        return this.spill(context, groupBy, aggregations, rows, held, seen);
    }

    /** The three passes when the groups do not fit (see the class comment). */
    private StepResult spill(StreamContext context, List<String> groupBy, List<Map<String, Object>> aggregations, long rows, long held,
                             long seen) throws Exception {
        RunMemory memory = context.memory();
        // Enough partitions that each should take about a quarter of the budget, judged by what the rows seen so far took.
        double whole = held * ((double) Math.max(rows, seen) / Math.max(1, seen));
        int parts = (int) Math.max(MIN_PARTITIONS, Math.min(MAX_PARTITIONS, Math.ceil(whole * 4 / memory.budget())));
        Set<String> needed = new LinkedHashSet<>(groupBy);
        for (Map<String, Object> aggregation : aggregations) {
            String column = Configs.text(aggregation, "column", null);
            if (column != null) {
                needed.add(column);
            }
        }
        Path dir = context.scratch();
        Path[] partitions;
        try (RowSource input = context.openInput()) {
            partitions = this.partition(input, dir, "p", parts, 0, groupBy, needed, memory, true);
        }
        context.log(String.format("The groups passed the run's memory budget (%s) after %,d of %,d row(s): grouping in %d partitions on disk "
            + "(%s spilled).", RunMemory.megabytes(memory.budget()), seen, rows, parts, RunMemory.megabytes(memory.spilled())));
        List<Path> results = new ArrayList<>();
        Failure failure = null;
        for (int p = 0; p < partitions.length; p++) {
            Path result = dir.resolve("r" + p + ".rows");
            Failure failed = this.groupPartition(partitions[p], result, groupBy, aggregations, memory, 0, "p" + p);
            Files.deleteIfExists(partitions[p]);
            if (failed != null) {
                failure = failure == null || failed.index < failure.index ? failed : failure;
            } else {
                results.add(result);
            }
        }
        if (failure != null) {
            throw failure.error;
        }
        long groups = merge(results, context.output());
        context.log(String.format("%d row(s) in %d group(s).", rows, groups));
        return StepResult.streamed(context.output().size());
    }

    /** Cuts rows into {@code parts} files by their group key's hash (with {@code seed}), each row cut to {@code needed} and numbered. */
    private Path[] partition(RowSource input, Path dir, String prefix, int parts, int seed, List<String> groupBy, Set<String> needed,
                             RunMemory memory, boolean number) throws Exception {
        RowsFile.Writer[] writers = new RowsFile.Writer[parts];
        Path[] files = new Path[parts];
        try {
            for (int p = 0; p < parts; p++) {
                files[p] = dir.resolve(prefix + "-" + p + ".rows");
                writers[p] = RowsFile.create(files[p]);
            }
            long index = 0;
            for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Aggregate was stopped.");
                }
                for (Map<String, Object> row : batch) {
                    index++;
                    Map<String, Object> slim = new LinkedHashMap<>(needed.size() * 2);
                    for (String column : needed) {
                        if (row.containsKey(column)) {
                            slim.put(column, row.get(column));
                        }
                    }
                    slim.put(ROW, number ? (Object) index : row.get(ROW));
                    writers[bucket(keyOf(row, groupBy), seed, parts)].add(slim);
                }
            }
            long bytes = 0;
            for (RowsFile.Writer writer : writers) {
                bytes += writer.commit();
            }
            memory.spilled(bytes);
            return files;
        } finally {
            for (RowsFile.Writer writer : writers) {
                if (writer != null) {
                    writer.abort();
                }
            }
        }
    }

    /**
     * One partition's groups, written to {@code result} with the row each first appeared at; a partition too big for
     * the budget is partitioned again. Returns the first row that failed, or null.
     */
    private Failure groupPartition(Path partition, Path result, List<String> groupBy, List<Map<String, Object>> aggregations, RunMemory memory,
                                   int depth, String name) throws Exception {
        Grouping grouping = new Grouping(groupBy, aggregations, memory, false);
        boolean overflow = false;
        try (RowSource rows = RowsFile.open(partition, RowSource.DEFAULT_BATCH)) {
            reading:
            for (List<Map<String, Object>> batch = rows.next(); batch != null; batch = rows.next()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Aggregate was stopped.");
                }
                for (Map<String, Object> row : batch) {
                    long index = ((Number) row.get(ROW)).longValue();
                    try {
                        if (!grouping.add(row, index)) {
                            overflow = true;
                            break reading;
                        }
                    } catch (IllegalArgumentException | IllegalStateException failed) {
                        // Rows are in their input order: the first that fails here is this partition's first.
                        grouping.release();
                        return new Failure(index, failed);
                    }
                }
            }
        }
        if (!overflow) {
            try (RowsFile.Writer out = RowsFile.create(result)) {
                for (Group group : grouping.groups.values()) {
                    Map<String, Object> row = group.result(aggregations);
                    row.put(FIRST, group.first);
                    out.add(row);
                }
                out.commit();
            } finally {
                grouping.release();
            }
            return null;
        }
        grouping.release();
        if (depth + 1 >= MAX_DEPTH) {
            throw new IllegalStateException(String.format("A few groups alone need more than the run's memory budget (%s), even split %d "
                + "ways: raise process.pipeline.run-memory-mb, or group by more columns.", RunMemory.megabytes(memory.budget()),
                MIN_PARTITIONS * MAX_DEPTH));
        }
        // Again, with another hash: the partition's rows keep their numbers.
        Path dir = partition.getParent();
        Path[] smaller;
        try (RowSource rows = RowsFile.open(partition, RowSource.DEFAULT_BATCH)) {
            Set<String> all = new LinkedHashSet<>(rows.columns());
            all.remove(ROW);
            smaller = this.partition(rows, dir, name + "-" + (depth + 1), MIN_PARTITIONS, depth + 1, groupBy, all, memory, false);
        }
        List<Path> results = new ArrayList<>();
        Failure failure = null;
        for (int p = 0; p < smaller.length; p++) {
            Path sub = dir.resolve(name + "-" + (depth + 1) + "-r" + p + ".rows");
            Failure failed = this.groupPartition(smaller[p], sub, groupBy, aggregations, memory, depth + 1, name + "-" + (depth + 1) + "-" + p);
            Files.deleteIfExists(smaller[p]);
            if (failed != null) {
                failure = failure == null || failed.index < failure.index ? failed : failure;
            } else {
                results.add(sub);
            }
        }
        if (failure != null) {
            return failure;
        }
        try (RowsFile.Writer out = RowsFile.create(result)) {
            mergeKeepingFirst(results, out);
            out.commit();
        }
        return null;
    }

    /** Partition results, merged by the row each group first appeared at: the in-memory order. Returns the groups written. */
    private static long merge(List<Path> results, RowSink sink) throws Exception {
        return mergeInto(results, row -> {
            row.remove(FIRST);
            sink.add(row);
        });
    }

    private static void mergeKeepingFirst(List<Path> results, RowSink sink) throws Exception {
        mergeInto(results, sink::add);
    }

    private interface RowConsumer {
        void accept(Map<String, Object> row) throws Exception;
    }

    private static long mergeInto(List<Path> results, RowConsumer consumer) throws Exception {
        List<Cursor> cursors = new ArrayList<>();
        PriorityQueue<Cursor> queue = new PriorityQueue<>((a, b) -> Long.compare(a.first(), b.first()));
        long count = 0;
        try {
            for (Path result : results) {
                Cursor cursor = new Cursor(RowsFile.open(result, 256));
                cursors.add(cursor);
                if (cursor.advance()) {
                    queue.add(cursor);
                }
            }
            while (!queue.isEmpty()) {
                Cursor cursor = queue.poll();
                consumer.accept(cursor.row);
                count++;
                if (cursor.advance()) {
                    queue.add(cursor);
                }
            }
        } finally {
            for (Cursor cursor : cursors) {
                cursor.source.close();
            }
            for (Path result : results) {
                Files.deleteIfExists(result);
            }
        }
        return count;
    }

    /** One partition result being merged: its current row. */
    private static final class Cursor {
        final RowSource source;
        List<Map<String, Object>> batch;
        int at;
        Map<String, Object> row;

        Cursor(RowSource source) {
            this.source = source;
        }

        boolean advance() throws Exception {
            if (this.batch == null || this.at >= this.batch.size()) {
                this.batch = this.source.next();
                this.at = 0;
                if (this.batch == null) {
                    this.row = null;
                    return false;
                }
            }
            this.row = this.batch.get(this.at++);
            return true;
        }

        long first() {
            return ((Number) this.row.get(FIRST)).longValue();
        }
    }

    /** The first row that failed in a partition, and how. */
    private static final class Failure {
        final long index;
        final RuntimeException error;

        Failure(long index, RuntimeException error) {
            this.index = index;
            this.error = error;
        }
    }

    /** A row's group key, as the in-memory path builds it. */
    static List<String> keyOf(Map<String, Object> row, List<String> groupBy) {
        List<String> key = new ArrayList<>(groupBy.size());
        for (String column : groupBy) {
            String part = Values.key(row.get(column));
            key.add(part == null ? "null" : part);
        }
        return key;
    }

    /** The partition a key goes to, for a seed: its hash, mixed (MurmurHash3's finaliser) so equal keys meet and others spread. */
    static int bucket(List<String> key, int seed, int parts) {
        int h = key.hashCode() ^ (seed * 0x9E3779B9);
        h ^= h >>> 16;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return Math.floorMod(h, parts);
    }

    /** Groups in memory, each one's bytes asked of the budget; {@link #add} says false when the budget says no. */
    private static final class Grouping {
        final Map<List<String>, Group> groups = new LinkedHashMap<>();
        private final List<String> groupBy;
        private final List<Map<String, Object>> aggregations;
        private final RunMemory memory;
        private final boolean inMemory;
        long reserved;
        private long pending;
        long index;

        Grouping(List<String> groupBy, List<Map<String, Object>> aggregations, RunMemory memory, boolean inMemory) {
            this.groupBy = groupBy;
            this.aggregations = aggregations;
            this.memory = memory;
            this.inMemory = inMemory;
        }

        boolean add(Map<String, Object> row, long index) {
            this.index = index;
            List<String> key = keyOf(row, this.groupBy);
            Group group = this.groups.get(key);
            if (group == null) {
                if (this.inMemory && this.groups.size() >= Limits.MAX_ROWS) {
                    throw new IllegalStateException(String.format("More than %,d groups, the most a step holds.", Limits.MAX_ROWS));
                }
                group = new Group(row, this.groupBy, this.aggregations.size(), index);
                this.groups.put(key, group);
                this.pending += Group.size(key, this.aggregations.size());
            }
            this.pending += group.add(row, this.aggregations, index);
            if (this.pending >= RESERVE_STEP) {
                if (!this.memory.tryReserve(this.pending)) {
                    return false;
                }
                this.reserved += this.pending;
                this.pending = 0;
            }
            return true;
        }

        void release() {
            this.memory.release(this.reserved);
            this.reserved = 0;
            this.pending = 0;
        }
    }

    /** One group's running values: one slot per aggregation. */
    private static final class Group {
        private final Map<String, Object> keys = new LinkedHashMap<>();
        private final long[] counts;
        private final BigDecimal[] sums;
        private final Object[] picks;
        private final List<LinkedHashSet<String>> lists = new ArrayList<>();
        private final List<Set<String>> distinct = new ArrayList<>();
        private long rows;
        /** The row this group first appeared at (MIG-344: the order a spilled aggregate merges its partitions in). */
        final long first;

        Group(Map<String, Object> first, List<String> groupBy, int slots, long firstRow) {
            this.first = firstRow;
            for (String column : groupBy) {
                this.keys.put(column, first.get(column));
            }
            this.counts = new long[slots];
            this.sums = new BigDecimal[slots];
            this.picks = new Object[slots];
            for (int i = 0; i < slots; i++) {
                this.distinct.add(null);
                this.lists.add(null);
            }
        }

        /** A new group's heap size, roughly (MIG-344's budget). */
        static long size(List<String> key, int slots) {
            long bytes = 160 + 56L * slots;
            for (String part : key) {
                bytes += 48 + part.length() * 2L;
            }
            return bytes;
        }

        /** Adds a row; returns about how many bytes the group grew by (a new distinct or listed value). */
        long add(Map<String, Object> row, List<Map<String, Object>> aggregations, long index) {
            long grew = 0;
            this.rows++;
            for (int i = 0; i < aggregations.size(); i++) {
                String op = Configs.text(aggregations.get(i), "op", "count");
                String column = Configs.text(aggregations.get(i), "column", "");
                Object value = row.get(column);
                switch (op) {
                    case "count":
                        break;
                    case "count_distinct":
                        if (value != null) {
                            if (this.distinct.get(i) == null) {
                                this.distinct.set(i, new HashSet<>());
                            }
                            String distinctKey = Values.key(value);
                            if (this.distinct.get(i).add(distinctKey)) {
                                grew += 56 + distinctKey.length() * 2L;
                            }
                        }
                        break;
                    case "sum":
                    case "avg":
                        if (value != null && !(value instanceof String && ((String) value).trim().isEmpty())) {
                            BigDecimal number = Values.number(value);
                            if (number == null) {
                                throw new IllegalArgumentException(String.format("Row %d: %s is not a number (%s).", index, column, op));
                            }
                            this.sums[i] = this.sums[i] == null ? number : this.sums[i].add(number);
                            this.counts[i]++;
                        }
                        break;
                    case "min":
                        if (value != null && (this.picks[i] == null || Values.compare(value, this.picks[i]) < 0)) {
                            this.picks[i] = value;
                        }
                        break;
                    case "max":
                        if (value != null && (this.picks[i] == null || Values.compare(value, this.picks[i]) > 0)) {
                            this.picks[i] = value;
                        }
                        break;
                    case "list":
                        if (value != null && !(value instanceof String && ((String) value).trim().isEmpty())) {
                            if (this.lists.get(i) == null) {
                                this.lists.set(i, new LinkedHashSet<>());
                            }
                            String listed = Values.text(value).trim();
                            if (this.lists.get(i).add(listed)) {
                                grew += 56 + listed.length() * 2L;
                            }
                        }
                        break;
                    case "first":
                        if (this.counts[i]++ == 0) {
                            this.picks[i] = value;
                        }
                        break;
                    case "last":
                    default:
                        this.picks[i] = value;
                        break;
                }
            }
            return grew;
        }

        Map<String, Object> result(List<Map<String, Object>> aggregations) {
            Map<String, Object> out = new LinkedHashMap<>(this.keys);
            for (int i = 0; i < aggregations.size(); i++) {
                String op = Configs.text(aggregations.get(i), "op", "count");
                Object value;
                switch (op) {
                    case "count":
                        value = this.rows;
                        break;
                    case "count_distinct":
                        value = (long) (this.distinct.get(i) == null ? 0 : this.distinct.get(i).size());
                        break;
                    case "sum":
                        value = Values.plain(this.sums[i] == null ? BigDecimal.ZERO : this.sums[i]);
                        break;
                    case "list":
                        value = listed(this.lists.get(i));
                        break;
                    case "avg":
                        value = this.counts[i] == 0 ? null : Values.plain(this.sums[i].divide(BigDecimal.valueOf(this.counts[i]), MathContext.DECIMAL64));
                        break;
                    default:
                        value = this.picks[i];
                }
                out.put(Configs.text(aggregations.get(i), "as", ""), value);
            }
            return out;
        }

        private static String listed(Set<String> values) {
            if (values == null || values.isEmpty()) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            int shown = 0;
            for (String value : values) {
                if (shown == LIST_MAX) {
                    break;
                }
                text.append(shown++ == 0 ? "" : ", ").append(value);
            }
            if (values.size() > LIST_MAX) {
                text.append(" (+").append(values.size() - LIST_MAX).append(" more)");
            }
            return text.toString();
        }
    }
}

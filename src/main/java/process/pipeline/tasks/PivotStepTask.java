package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.data.RowSink;
import process.pipeline.data.RowSource;
import process.pipeline.data.RunMemory;
import process.pipeline.data.Values;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.ItemRole;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pivot (owner #16, 2026-10-19): long rows to wide ones -- one row per group, one column per value of a column (a lab
 * result's test name), each holding what the value column had for it (first, last, sum, count, min, max or avg). The new
 * columns are the ones named, or the values met, in the order met (at most {@value #MAX_COLUMNS}). A whole-table step: it
 * holds one row per group, charged to the run's memory budget, and fails past it saying so.
 */
@Component
public class PivotStepTask extends RegisteredTask implements StreamingStepTask {

    static final int MAX_COLUMNS = 200;
    static final List<String> OPS = Arrays.asList("first", "last", "sum", "count", "min", "max", "avg");

    static final TaskSpec SPEC = TaskSpec.builder("pivot", "Pivot (rows to columns)", TaskKind.PROCESS)
        .description("Turns rows into columns: one row per group, one column per value of a column, filled from a value column.")
        .input(TaskSpec.rows("Long rows: a group, a name column and a value column."))
        .output(TaskSpec.rows("One row per group: the group's columns, then one column per name."))
        .config(JsonSchema.object()
            .property("groupBy", JsonSchema.array(JsonSchema.string().minLength(1).maxLength(128).format("column")).maxItems(20)
                .title("Row per").description("The columns that make one output row (a patient id, a visit). Empty: one row for all."))
            .required("column", JsonSchema.string().minLength(1).maxLength(128).title("Names from").format("column")
                .description("Its values become the new columns' names."))
            .property("value", JsonSchema.string().maxLength(128).title("Values from").format("column")
                .description("What fills them. Every operation but count needs one."))
            .property("op", JsonSchema.string().enumOf(OPS.toArray(new String[0])).title("When a group has several").defaultValue("first")
                .description("first, last, sum, count, min, max or avg of the values."))
            .property("columns", JsonSchema.array(JsonSchema.string().minLength(1).maxLength(128)).maxItems(MAX_COLUMNS).title("Only these")
                .description("The new columns to make, in this order; other names are left out. Empty: every name met, in order met.")))
        .backing(TaskSpec.CORE)
        .timeoutSeconds(1800)
        .aiToolName("pivot_rows")
        .items(ItemRole.SHARED).build();

    public PivotStepTask() {
        super(SPEC);
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        Configs.onlyKnownKeys(config, problems, "groupBy", "column", "value", "op", "columns");
        String op = Configs.text(config, "op", "first");
        if (!"count".equals(op) && Configs.text(config, "value", "").isEmpty()) {
            problems.add(new DefinitionProblem("value", String.format("%s needs the column the values come from", op)));
        }
        List<String> groupBy = Configs.texts(config, "groupBy");
        String column = Configs.text(config, "column", "");
        if (groupBy.contains(column)) {
            problems.add(new DefinitionProblem("column", String.format("'%s' is also a Row per column", column)));
        }
        Set<String> seen = new HashSet<>();
        List<String> named = Configs.texts(config, "columns");
        for (int i = 0; i < named.size(); i++) {
            if (!seen.add(named.get(i))) {
                problems.add(new DefinitionProblem("columns[" + i + "]", String.format("'%s' is named twice", named.get(i))));
            } else if (groupBy.contains(named.get(i))) {
                problems.add(new DefinitionProblem("columns[" + i + "]", String.format("'%s' is a Row per column", named.get(i))));
            }
        }
        return problems;
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        Map<String, Object> config = context.config();
        List<String> groupBy = Configs.texts(config, "groupBy");
        String column = Configs.text(config, "column", "");
        String valueColumn = Configs.text(config, "value", "");
        String op = Configs.text(config, "op", "first");
        List<String> only = Configs.texts(config, "columns");
        List<String> names = new ArrayList<>(only);
        Set<String> wanted = new HashSet<>(only);
        Map<List<String>, Group> groups = new LinkedHashMap<>();
        RunMemory memory = context.memory();
        long held = 0;
        long rows = 0;
        try {
            try (RowSource input = context.openInput()) {
                List<String> needed = new ArrayList<>(groupBy);
                needed.add(column);
                if (!valueColumn.isEmpty()) {
                    needed.add(valueColumn);
                }
                DedupeStepTask.requireColumns(needed, input.columns());
                for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Pivot was stopped.");
                    }
                    for (Map<String, Object> row : batch) {
                        rows++;
                        Object raw = row.get(column);
                        String name = raw == null ? "" : Values.text(raw).trim();
                        if (name.isEmpty()) {
                            continue;
                        }
                        if (only.isEmpty() && !names.contains(name)) {
                            if (groupBy.contains(name)) {
                                throw new IllegalArgumentException(String.format("Row %,d: '%s' would be a new column, but it is a Row per column.",
                                    rows, name));
                            }
                            if (names.size() >= MAX_COLUMNS) {
                                throw new IllegalArgumentException(String.format("'%s' has more than %d different values: a pivot makes at most %d "
                                    + "columns. Name the ones you want under Only these.", column, MAX_COLUMNS, MAX_COLUMNS));
                            }
                            names.add(name);
                        } else if (!only.isEmpty() && !wanted.contains(name)) {
                            continue;
                        }
                        List<String> key = AggregateStepTask.keyOf(row, groupBy);
                        Group group = groups.get(key);
                        if (group == null) {
                            Map<String, Object> keys = new LinkedHashMap<>();
                            groupBy.forEach(c -> keys.put(c, row.get(c)));
                            group = new Group(keys);
                            groups.put(key, group);
                            long size = 96 + RunMemory.estimate(keys);
                            held = charge(memory, held, size, groups.size());
                        }
                        if (group.add(name, valueColumn.isEmpty() ? null : row.get(valueColumn), op, rows)) {
                            held = charge(memory, held, 64, groups.size());
                        }
                    }
                }
            }
            RowSink out = context.output();
            List<String> columns = new ArrayList<>(groupBy);
            columns.addAll(names);
            out.declare(columns);
            for (Group group : groups.values()) {
                Map<String, Object> row = new LinkedHashMap<>(group.keys);
                for (String name : names) {
                    row.put(name, group.result(name, op));
                }
                out.add(row);
            }
            context.log(String.format("%,d row(s) in, %,d group(s) out, %d new column(s) from '%s' (%s of %s).", rows, groups.size(),
                names.size(), column, op, valueColumn.isEmpty() ? "rows" : valueColumn));
            return StepResult.streamed(out.size());
        } finally {
            memory.release(held);
        }
    }

    private static long charge(RunMemory memory, long held, long bytes, int groups) {
        if (!memory.tryReserve(bytes)) {
            throw new IllegalStateException(String.format("Pivot holds one row per group, and %,d groups passed the run's memory budget (%s). "
                + "Pivot fewer groups (filter first) or ask for a larger budget.", groups, RunMemory.megabytes(memory.budget())));
        }
        return held + bytes;
    }

    /** One output row being made: its group's values and, per new column, what the values so far add up to. */
    private static final class Group {
        final Map<String, Object> keys;
        final Map<String, Object> picks = new LinkedHashMap<>();
        final Map<String, BigDecimal> sums = new LinkedHashMap<>();
        final Map<String, Long> counts = new LinkedHashMap<>();

        Group(Map<String, Object> keys) {
            this.keys = keys;
        }

        /** Adds a value under a name; true when the name is new to the group (one more cell held). */
        boolean add(String name, Object value, String op, long row) {
            boolean fresh = !this.counts.containsKey(name);
            long seen = this.counts.getOrDefault(name, 0L);
            boolean empty = value == null || (value instanceof String && ((String) value).trim().isEmpty());
            switch (op) {
                case "count":
                    this.counts.put(name, seen + 1);
                    return fresh;
                case "sum":
                case "avg":
                    if (!empty) {
                        BigDecimal number = Values.number(value);
                        if (number == null) {
                            throw new IllegalArgumentException(String.format("Row %,d: the value for '%s' is not a number (%s).", row, name, op));
                        }
                        this.sums.merge(name, number, BigDecimal::add);
                        this.counts.put(name, seen + 1);
                    } else {
                        this.counts.putIfAbsent(name, 0L);
                    }
                    return fresh;
                case "min":
                case "max":
                    if (!empty) {
                        Object now = this.picks.get(name);
                        int c = now == null ? 0 : Values.compare(value, now);
                        if (now == null || ("min".equals(op) ? c < 0 : c > 0)) {
                            this.picks.put(name, value);
                        }
                    }
                    this.counts.put(name, seen + 1);
                    return fresh;
                case "last":
                    this.picks.put(name, value);
                    this.counts.put(name, seen + 1);
                    return fresh;
                case "first":
                default:
                    if (seen == 0) {
                        this.picks.put(name, value);
                    }
                    this.counts.put(name, seen + 1);
                    return fresh;
            }
        }

        Object result(String name, String op) {
            if (!this.counts.containsKey(name)) {
                return "count".equals(op) ? (Object) 0L : null;
            }
            switch (op) {
                case "count":
                    return this.counts.get(name);
                case "sum":
                    return Values.plain(this.sums.getOrDefault(name, BigDecimal.ZERO));
                case "avg":
                    long n = this.counts.get(name);
                    return n == 0 ? null : Values.plain(this.sums.get(name).divide(BigDecimal.valueOf(n), MathContext.DECIMAL64));
                default:
                    return this.picks.get(name);
            }
        }
    }
}

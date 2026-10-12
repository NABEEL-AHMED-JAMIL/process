package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.data.RowSink;
import process.pipeline.data.RowSource;
import process.pipeline.data.Values;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Append (MIG-346): stacks the outputs of two or more earlier steps, one after the other, matching columns by name -- a
 * union. The output's columns are every column any of them has, in the order first seen (or, with {@code columns:
 * common}, only those all of them have); a row from a step without a column has it empty. {@code sourceColumn} adds a
 * column saying which step each row came from.
 *
 * <b>Types.</b> A column holds one kind of value across the steps: the kind its first value has (number, text or
 * true/false), or the kind {@code types} names for it. A value of another kind from a later step is converted -- a
 * number to text, numeric text to a number, "true"/"false" (or 1/0) to true/false -- and one that cannot be fails the
 * step, naming the row, the step and the column, and how to fix it. Values of the step that set a column's kind are kept
 * as they are unless {@code types} names it. {@code coerce: false} keeps every value as it is.
 *
 * Streamed: one batch of one input at a time, however big the inputs.
 */
@Component
public class AppendStepTask extends RegisteredTask implements StreamingStepTask {

    static final int MAX_INPUTS = 20;

    static final TaskSpec SPEC = TaskSpec.builder("append", "Append (union)", TaskKind.PROCESS)
        .description("Stacks the rows of two or more earlier steps, matching columns by name; a missing column is empty.")
        .input(TaskSpec.rows("Not read: the steps named in Steps are."))
        .output(TaskSpec.rows("Every named step's rows, in the order named; columns by name."))
        .config(JsonSchema.object()
            .required("steps", JsonSchema.array(JsonSchema.string().minLength(1).format("step")).minItems(2).maxItems(MAX_INPUTS)
                .title("Steps to stack").description("Earlier steps whose rows are stacked, in this order (one per line)."))
            .property("columns", JsonSchema.string().enumOf("all", "common").title("Columns").defaultValue("all")
                .description("all: every column any step has (missing ones are empty); common: only the columns every step has."))
            .property("sourceColumn", JsonSchema.string().pattern("^[A-Za-z_][A-Za-z0-9_ .-]{0,63}$").title("Source column")
                .description("A column to add that holds the key of the step each row came from; none when empty."))
            .property("types", JsonSchema.map(JsonSchema.string().enumOf("number", "text", "boolean")).title("Column types")
                .description("A column's kind, to convert every step's values to; otherwise its first value's kind."))
            .property("coerce", JsonSchema.bool().title("Convert mismatched values").defaultValue(true)
                .description("Convert a value of another kind to the column's kind (failing when it cannot); off keeps every value "
                    + "as it is.")))
        .backing(TaskSpec.CORE)
        .aiToolName("append_rows")
        .build();

    public AppendStepTask() {
        super(SPEC);
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        List<String> steps = Configs.texts(config, "steps");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < steps.size(); i++) {
            if (!seen.add(steps.get(i))) {
                problems.add(new DefinitionProblem("steps[" + i + "]", String.format("'%s' is already named", steps.get(i))));
            }
        }
        return problems;
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        Map<String, Object> config = context.config();
        List<String> steps = Configs.texts(config, "steps");
        boolean common = "common".equals(Configs.text(config, "columns", "all"));
        String sourceColumn = Configs.text(config, "sourceColumn", null);
        boolean coerce = Configs.bool(config, "coerce", true);
        Map<String, String> types = Configs.textMap(config, "types");

        // Columns first, from each input's own list (known before a row is read).
        Set<String> columns = new LinkedHashSet<>();
        List<Long> sizes = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            try (RowSource input = context.openOutputOf(steps.get(i))) {
                sizes.add(input.size());
                if (i == 0 || !common) {
                    columns.addAll(input.columns());
                } else {
                    columns.retainAll(new HashSet<>(input.columns()));
                }
            }
        }
        if (sourceColumn != null && columns.contains(sourceColumn)) {
            throw new IllegalArgumentException(String.format("The source column '%s' is already a column of the steps; name another.",
                sourceColumn));
        }
        for (String typed : types.keySet()) {
            if (!columns.contains(typed)) {
                context.warn(String.format("types names '%s', which none of the steps has%s.", typed, common ? " in common" : ""));
            }
        }
        List<String> declared = new ArrayList<>(columns);
        if (sourceColumn != null) {
            declared.add(sourceColumn);
        }
        RowSink out = context.output();
        out.declare(declared);
        // Each column's kind: named, or set by the first value; and which input set it.
        Map<String, String> kind = new HashMap<>();
        Map<String, Integer> setBy = new HashMap<>();
        types.forEach((column, word) -> kind.put(column, word.toLowerCase(Locale.ROOT)));
        long converted = 0;
        for (int i = 0; i < steps.size(); i++) {
            String step = steps.get(i);
            long r = 0;
            try (RowSource input = context.openOutputOf(step)) {
                for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Append was stopped.");
                    }
                    for (Map<String, Object> row : batch) {
                        r++;
                        Map<String, Object> stacked = new LinkedHashMap<>(declared.size() * 4 / 3 + 1);
                        for (String column : columns) {
                            Object value = row.get(column);
                            if (value != null && coerce) {
                                String want = kind.get(column);
                                if (want == null) {
                                    want = kindOf(value);
                                    if (want != null) {
                                        kind.put(column, want);
                                        setBy.put(column, i);
                                    }
                                } else if (!types.containsKey(column) && Integer.valueOf(i).equals(setBy.get(column))) {
                                    want = null;
                                }
                                if (want != null && !want.equals(kindOf(value))) {
                                    Object to = convert(value, want);
                                    if (to == CANNOT) {
                                        throw new IllegalArgumentException(String.format("Row %,d of <%s>, column %s: '%s' is not %s (%s). Name the "
                                            + "column's kind under types (text keeps every value), or turn off Convert mismatched values.",
                                            r, step, column, Values.text(value), article(want), types.containsKey(column)
                                                ? "types says " + want : String.format("its values are %s in <%s>", plural(want),
                                                steps.get(setBy.get(column)))));
                                    }
                                    if (to != value) {
                                        converted++;
                                    }
                                    value = to;
                                }
                            }
                            stacked.put(column, value);
                        }
                        if (sourceColumn != null) {
                            stacked.put(sourceColumn, step);
                        }
                        out.add(stacked);
                    }
                }
            }
        }
        StringBuilder line = new StringBuilder(String.format("Stacked %,d row(s) from %d steps (", out.size(), steps.size()));
        for (int i = 0; i < steps.size(); i++) {
            line.append(i == 0 ? "" : ", ").append(String.format("<%s> %,d", steps.get(i), sizes.get(i)));
        }
        line.append(String.format("), %d column(s)%s.", declared.size(), common ? " in common" : ""));
        context.log(line.toString());
        if (converted > 0) {
            context.log(String.format("%,d value(s) converted to their column's kind.", converted));
        }
        return StepResult.streamed(out.size());
    }

    private static final Object CANNOT = new Object();

    /** number, text, boolean -- or null for a value with no kind to set (blank text, a nested value). */
    static String kindOf(Object value) {
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof String) {
            return ((String) value).trim().isEmpty() ? null : "text";
        }
        return null;
    }

    /** The value as {@code kind}; {@link #CANNOT} when it is not one. Blank text is empty in any kind. */
    static Object convert(Object value, String kind) {
        if (value instanceof String && ((String) value).trim().isEmpty()) {
            return "text".equals(kind) ? value : null;
        }
        switch (kind) {
            case "text":
                return value instanceof String ? value : Values.text(value);
            case "number": {
                if (value instanceof Boolean) {
                    return CANNOT;
                }
                BigDecimal number = Values.number(value);
                return number == null ? CANNOT : Values.plain(number);
            }
            case "boolean": {
                if (value instanceof Boolean) {
                    return value;
                }
                String text = Values.text(value).trim().toLowerCase(Locale.ROOT);
                if ("true".equals(text) || "1".equals(text) || "yes".equals(text)) {
                    return Boolean.TRUE;
                }
                if ("false".equals(text) || "0".equals(text) || "no".equals(text)) {
                    return Boolean.FALSE;
                }
                return CANNOT;
            }
            default:
                return value;
        }
    }

    private static String article(String kind) {
        return "number".equals(kind) ? "a number" : "boolean".equals(kind) ? "true or false" : "text";
    }

    private static String plural(String kind) {
        return "number".equals(kind) ? "numbers" : "boolean".equals(kind) ? "true/false" : "text";
    }
}

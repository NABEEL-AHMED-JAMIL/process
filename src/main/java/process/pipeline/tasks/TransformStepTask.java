package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.data.Limits;
import process.pipeline.data.Values;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Transform (MIG-231): a mapping -- each target column made from the input row by one operation, in the order
 * written:
 *
 * <ul>
 *   <li>{@code copy} source; {@code constant} value; {@code concat} sources with a separator (nulls skipped);</li>
 *   <li>{@code upper}, {@code lower}, {@code trim} source (text);</li>
 *   <li>{@code cast} source {@code to} text, integer, number or boolean -- a value that is not one fails the step at
 *       its row (never quoting the value), unless {@code nullOnError};</li>
 *   <li>{@code coalesce}: the first source that is not null or empty, else value;</li>
 *   <li>{@code replace}: source with every {@code search} replaced by {@code replacement} (plain text, not a regex).</li>
 * </ul>
 *
 * {@code keepUnmapped} false (the default): the output is the targets only; true: the input's columns, then the
 * targets (a target that is an input column replaces it in place). Pure Core, no service.
 */
@Component
public class TransformStepTask extends RegisteredTask {

    static final List<String> OPS = Arrays.asList("copy", "constant", "concat", "upper", "lower", "trim", "cast", "coalesce",
        "replace");

    static final TaskSpec SPEC = TaskSpec.builder("transform", "Transform", TaskKind.PROCESS)
        .description("Maps each row to new columns: copy, constant, concat, upper/lower/trim, cast, coalesce, replace.")
        .input(TaskSpec.rows("Any rows."))
        .output(TaskSpec.rows("The mapped columns (and the input's, with keepUnmapped)."))
        .config(JsonSchema.object()
            .required("mappings", JsonSchema.array(JsonSchema.object()
                .required("target", JsonSchema.string().minLength(1).maxLength(128).title("Column").description("The column it makes."))
                .property("op", JsonSchema.string().enumOf(OPS.toArray(new String[0])).title("Operation").defaultValue("copy"))
                .property("source", JsonSchema.string().maxLength(128).title("From column").format("column"))
                .property("sources", JsonSchema.array(JsonSchema.string().maxLength(128).format("column")).title("From columns")
                    .description("concat and coalesce: the columns, in order."))
                .property("value", JsonSchema.scalar().title("Value").description("constant: the value; coalesce: the fallback."))
                .property("separator", JsonSchema.string().maxLength(32).title("Separator").defaultValue("").description("concat: between values."))
                .property("to", JsonSchema.string().enumOf("text", "integer", "number", "boolean").title("Cast to"))
                .property("nullOnError", JsonSchema.bool().title("Empty when it cannot cast").defaultValue(false))
                .property("search", JsonSchema.string().minLength(1).maxLength(256).title("Replace"))
                .property("replacement", JsonSchema.string().maxLength(256).title("With").defaultValue("")))
                .minItems(1).maxItems(Limits.MAX_COLUMNS).title("Mappings"))
            .property("keepUnmapped", JsonSchema.bool().title("Keep the other columns").defaultValue(false)
                .description("Pass the input's columns on too; otherwise only the mapped ones.")))
        .aiToolName("transform_rows")
        .build();

    public TransformStepTask() {
        super(SPEC);
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        Set<String> targets = new HashSet<>();
        List<Map<String, Object>> mappings = Configs.objects(config, "mappings");
        for (int i = 0; i < mappings.size(); i++) {
            Map<String, Object> mapping = mappings.get(i);
            String at = "mappings[" + i + "]";
            String op = Configs.text(mapping, "op", "copy");
            String target = Configs.text(mapping, "target", "");
            if (!targets.add(target)) {
                problems.add(new DefinitionProblem(at + ".target", String.format("'%s' is already made by an earlier mapping", target)));
            }
            switch (op) {
                case "constant":
                    if (!mapping.containsKey("value")) {
                        problems.add(new DefinitionProblem(at + ".value", "a constant needs its value"));
                    }
                    break;
                case "concat":
                case "coalesce":
                    if (Configs.texts(mapping, "sources").isEmpty()) {
                        problems.add(new DefinitionProblem(at + ".sources", String.format("%s needs at least one column", op)));
                    }
                    break;
                default:
                    if (Configs.text(mapping, "source", null) == null) {
                        problems.add(new DefinitionProblem(at + ".source", String.format("%s needs its column", op)));
                    }
                    if ("cast".equals(op) && mapping.get("to") == null) {
                        problems.add(new DefinitionProblem(at + ".to", "cast needs what to cast to"));
                    }
                    if ("replace".equals(op) && mapping.get("search") == null) {
                        problems.add(new DefinitionProblem(at + ".search", "replace needs what to replace"));
                    }
            }
        }
        return problems;
    }

    @Override
    public StepResult run(StepContext context) throws Exception {
        List<Map<String, Object>> mappings = Configs.objects(context.config(), "mappings");
        boolean keep = Configs.bool(context.config(), "keepUnmapped", false);
        Dataset input = context.input();
        Set<String> columns = new LinkedHashSet<>();
        if (keep) {
            columns.addAll(input.getColumns());
        }
        for (Map<String, Object> mapping : mappings) {
            columns.add(Configs.text(mapping, "target", ""));
        }
        Limits.requireShape(input.size(), columns.size(), "The transformed rows");
        Set<String> missing = new LinkedHashSet<>();
        for (Map<String, Object> mapping : mappings) {
            for (String source : sourcesOf(mapping)) {
                if (!input.getColumns().contains(source)) {
                    missing.add(source);
                }
            }
        }
        if (!missing.isEmpty() && !input.isEmpty()) {
            context.warn("The input has no column " + String.join(", ", missing) + "; read as empty.");
        }
        List<Map<String, Object>> rows = new ArrayList<>(input.size());
        int index = 0;
        for (Map<String, Object> row : input.getRows()) {
            index++;
            Map<String, Object> out = keep ? new LinkedHashMap<>(row) : new LinkedHashMap<>();
            for (Map<String, Object> mapping : mappings) {
                out.put(Configs.text(mapping, "target", ""), apply(mapping, row, index));
            }
            rows.add(out);
        }
        context.log(String.format("%d row(s) mapped to %d column(s).", rows.size(), columns.size()));
        return StepResult.of(new Dataset(new ArrayList<>(columns), rows));
    }

    private static List<String> sourcesOf(Map<String, Object> mapping) {
        List<String> sources = new ArrayList<>(Configs.texts(mapping, "sources"));
        String source = Configs.text(mapping, "source", null);
        if (source != null) {
            sources.add(source);
        }
        return sources;
    }

    static Object apply(Map<String, Object> mapping, Map<String, Object> row, int index) {
        String op = Configs.text(mapping, "op", "copy");
        Object value = row.get(Configs.text(mapping, "source", ""));
        switch (op) {
            case "constant":
                return mapping.get("value");
            case "concat": {
                String separator = mapping.get("separator") == null ? "" : mapping.get("separator").toString();
                List<String> parts = new ArrayList<>();
                for (String source : Configs.texts(mapping, "sources")) {
                    String text = Values.text(row.get(source));
                    if (text != null) {
                        parts.add(text);
                    }
                }
                return String.join(separator, parts);
            }
            case "coalesce":
                for (String source : Configs.texts(mapping, "sources")) {
                    Object candidate = row.get(source);
                    if (candidate != null && !(candidate instanceof String && ((String) candidate).isEmpty())) {
                        return candidate;
                    }
                }
                return mapping.get("value");
            case "upper":
                return value == null ? null : Values.text(value).toUpperCase(Locale.ROOT);
            case "lower":
                return value == null ? null : Values.text(value).toLowerCase(Locale.ROOT);
            case "trim":
                return value == null ? null : Values.text(value).trim();
            case "replace": {
                String replacement = mapping.get("replacement") == null ? "" : mapping.get("replacement").toString();
                return value == null ? null : Values.text(value).replace(mapping.get("search").toString(), replacement);
            }
            case "cast":
                return cast(value, Configs.text(mapping, "to", "text"), Configs.bool(mapping, "nullOnError", false), index,
                    Configs.text(mapping, "source", ""));
            case "copy":
            default:
                return value;
        }
    }

    private static Object cast(Object value, String to, boolean nullOnError, int index, String source) {
        if (value == null || (value instanceof String && ((String) value).trim().isEmpty() && !"text".equals(to))) {
            return null;
        }
        switch (to) {
            case "text":
                return Values.text(value);
            case "integer": {
                BigDecimal number = Values.number(value);
                if (number != null) {
                    try {
                        return number.stripTrailingZeros().longValueExact();
                    } catch (ArithmeticException notWhole) {
                        number = null;
                    }
                }
                return failed(value, "a whole number", nullOnError, index, source);
            }
            case "number": {
                BigDecimal number = Values.number(value);
                return number != null ? Values.plain(number) : failed(value, "a number", nullOnError, index, source);
            }
            case "boolean": {
                if (value instanceof Boolean) {
                    return value;
                }
                String text = Values.text(value).trim().toLowerCase(Locale.ROOT);
                if (text.equals("true") || text.equals("yes") || text.equals("y") || text.equals("1")) {
                    return true;
                }
                if (text.equals("false") || text.equals("no") || text.equals("n") || text.equals("0")) {
                    return false;
                }
                return failed(value, "true or false", nullOnError, index, source);
            }
            default:
                return value;
        }
    }

    private static Object failed(Object value, String what, boolean nullOnError, int index, String source) {
        if (nullOnError) {
            return null;
        }
        // The value itself stays out of the log: a row may be anyone's data.
        throw new IllegalArgumentException(String.format("Row %d: %s is not %s.", index, source, what));
    }
}

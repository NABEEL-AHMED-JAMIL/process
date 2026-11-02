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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Filter (MIG-231): keeps the rows that meet all (or any) of its conditions. A condition compares a column with a
 * value: eq, ne, gt, gte, lt, lte (numbers by value when both sides are numbers, else text), contains, starts_with,
 * ends_with (text; {@code ignoreCase} optional), in / not_in a list of values, is_null / not_null, is_empty /
 * not_empty (null or ""). gt, gte, lt and lte never hold for an empty value (null or blank): an empty cell is not below
 * 8 (2026-11-02). No regular expressions: nothing a person types can make a step run away. Pure Core.
 * MIG-344: streamed, a row at a time.
 */
@Component
public class FilterStepTask extends RegisteredTask implements StreamingStepTask {

    static final List<String> OPERATORS = Arrays.asList("eq", "ne", "gt", "gte", "lt", "lte", "contains", "starts_with", "ends_with",
        "in", "not_in", "is_null", "not_null", "is_empty", "not_empty");
    private static final List<String> UNARY = Arrays.asList("is_null", "not_null", "is_empty", "not_empty");
    private static final List<String> LISTED = Arrays.asList("in", "not_in");
    /** The comparisons that order: none of them holds when either side is empty (null or blank text). */
    private static final List<String> ORDERED = Arrays.asList("gt", "gte", "lt", "lte");

    static final TaskSpec SPEC = TaskSpec.builder("filter", "Filter", TaskKind.PROCESS)
        .description("Keeps the rows that meet all (or any) of its conditions.")
        .input(TaskSpec.rows("Any rows."))
        .output(TaskSpec.rows("The rows that meet the conditions, unchanged."))
        .config(JsonSchema.object()
            .property("match", JsonSchema.string().enumOf("all", "any").title("Keep a row that meets").defaultValue("all"))
            .required("conditions", JsonSchema.array(conditionSchema()).minItems(1).maxItems(50).title("Conditions")))
        .aiToolName("filter_rows")
        .build();

    public FilterStepTask() {
        super(SPEC);
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        return conditionProblems(Configs.objects(config, "conditions"), "");
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        boolean any = "any".equals(Configs.text(context.config(), "match", "all"));
        List<Map<String, Object>> conditions = Configs.objects(context.config(), "conditions");
        RowSink out = context.output();
        long total;
        try (RowSource input = context.openInput()) {
            total = input.size();
            out.declare(input.columns());
            for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Filter was stopped.");
                }
                for (Map<String, Object> row : batch) {
                    if (matches(row, conditions, any)) {
                        out.add(row);
                    }
                }
            }
        }
        context.log(String.format("%d of %d row(s) kept.", out.size(), total));
        return StepResult.streamed(out.size());
    }

    /** Whether a row meets all (or, {@code any}, one) of the conditions: Filter's rule, and a Route branch's (MIG-450). */
    static boolean matches(Map<String, Object> row, List<Map<String, Object>> conditions, boolean any) {
        for (Map<String, Object> condition : conditions) {
            boolean met = meets(row.get(Configs.text(condition, "column", "")), condition);
            if (any && met) {
                return true;
            }
            if (!any && !met) {
                return false;
            }
        }
        return !any;
    }

    /** Filter's problems with a list of conditions (a value or a list where the operator needs one), each at its path. */
    static List<DefinitionProblem> conditionProblems(List<Map<String, Object>> conditions, String prefix) {
        List<DefinitionProblem> problems = new ArrayList<>();
        for (int i = 0; i < conditions.size(); i++) {
            String operator = Configs.text(conditions.get(i), "operator", "");
            String at = prefix + "conditions[" + i + "]";
            if (LISTED.contains(operator) && !(conditions.get(i).get("values") instanceof List)) {
                problems.add(new DefinitionProblem(at + ".values", operator + " needs its list of values"));
            } else if (!UNARY.contains(operator) && !LISTED.contains(operator) && !conditions.get(i).containsKey("value")) {
                problems.add(new DefinitionProblem(at + ".value", operator + " needs a value to compare with"));
            }
        }
        return problems;
    }

    /** One condition's schema (column, operator, value or values, ignoreCase): Filter's, and a Route branch's. */
    static JsonSchema conditionSchema() {
        return JsonSchema.object()
            .required("column", JsonSchema.string().minLength(1).maxLength(128).title("Column").format("column"))
            .required("operator", JsonSchema.string().enumOf(OPERATORS.toArray(new String[0])).title("Is"))
            .property("value", JsonSchema.scalar().title("Value"))
            .property("values", JsonSchema.array(JsonSchema.scalar()).maxItems(1000).title("Values").description("in / not_in."))
            .property("ignoreCase", JsonSchema.bool().title("Ignore case").defaultValue(false));
    }

    static boolean meets(Object actual, Map<String, Object> condition) {
        String operator = Configs.text(condition, "operator", "");
        Object value = condition.get("value");
        boolean ignoreCase = Configs.bool(condition, "ignoreCase", false);
        switch (operator) {
            case "is_null":
                return actual == null;
            case "not_null":
                return actual != null;
            case "is_empty":
                return actual == null || Values.text(actual).isEmpty();
            case "not_empty":
                return actual != null && !Values.text(actual).isEmpty();
            case "in":
            case "not_in": {
                boolean found = false;
                Object values = condition.get("values");
                if (values instanceof List && actual != null) {
                    for (Object candidate : (List<?>) values) {
                        if (equal(actual, candidate, ignoreCase)) {
                            found = true;
                            break;
                        }
                    }
                }
                return "in".equals(operator) == found;
            }
            default:
                break;
        }
        if (actual == null || value == null) {
            // Only eq/ne say anything about null: null eq null, and anything ne null.
            return "eq".equals(operator) ? actual == value : "ne".equals(operator) && actual != value;
        }
        // AI eval A-filter-021 (2026-11-02): an empty value is not less (or more) than anything. A CSV's empty cell is "",
        // and "" sorted before every number as text, so "hba1c below 8" kept every row with no hba1c. A comparison with
        // an empty side is false, as in a Compute formula (Expression) and a form's rule; is_empty / not_empty say it.
        if (ORDERED.contains(operator) && (blank(actual) || blank(value))) {
            return false;
        }
        String left = ignoreCase ? Values.text(actual).toLowerCase(Locale.ROOT) : Values.text(actual);
        String right = ignoreCase ? Values.text(value).toLowerCase(Locale.ROOT) : Values.text(value);
        switch (operator) {
            case "eq":
                return equal(actual, value, ignoreCase);
            case "ne":
                return !equal(actual, value, ignoreCase);
            case "gt":
                return Values.compare(actual, value) > 0;
            case "gte":
                return Values.compare(actual, value) >= 0;
            case "lt":
                return Values.compare(actual, value) < 0;
            case "lte":
                return Values.compare(actual, value) <= 0;
            case "contains":
                return left.contains(right);
            case "starts_with":
                return left.startsWith(right);
            case "ends_with":
                return left.endsWith(right);
            default:
                return false;
        }
    }

    /** Text with nothing in it but spaces: what an empty CSV cell or form field reads as. */
    private static boolean blank(Object value) {
        return value instanceof CharSequence && value.toString().trim().isEmpty();
    }

    private static boolean equal(Object actual, Object value, boolean ignoreCase) {
        if (actual == null || value == null) {
            return actual == value;
        }
        if (ignoreCase && Values.number(actual) == null) {
            return Values.text(actual).equalsIgnoreCase(Values.text(value));
        }
        return Values.same(actual, value);
    }
}

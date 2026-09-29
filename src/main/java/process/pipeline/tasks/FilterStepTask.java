package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
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
 * not_empty (null or ""). No regular expressions: nothing a person types can make a step run away. Pure Core.
 */
@Component
public class FilterStepTask extends RegisteredTask {

    static final List<String> OPERATORS = Arrays.asList("eq", "ne", "gt", "gte", "lt", "lte", "contains", "starts_with", "ends_with",
        "in", "not_in", "is_null", "not_null", "is_empty", "not_empty");
    private static final List<String> UNARY = Arrays.asList("is_null", "not_null", "is_empty", "not_empty");
    private static final List<String> LISTED = Arrays.asList("in", "not_in");

    static final TaskSpec SPEC = TaskSpec.builder("filter", "Filter", TaskKind.PROCESS)
        .description("Keeps the rows that meet all (or any) of its conditions.")
        .input(TaskSpec.rows("Any rows."))
        .output(TaskSpec.rows("The rows that meet the conditions, unchanged."))
        .config(JsonSchema.object()
            .property("match", JsonSchema.string().enumOf("all", "any").title("Keep a row that meets").defaultValue("all"))
            .required("conditions", JsonSchema.array(JsonSchema.object()
                .required("column", JsonSchema.string().minLength(1).maxLength(128).title("Column").format("column"))
                .required("operator", JsonSchema.string().enumOf(OPERATORS.toArray(new String[0])).title("Is"))
                .property("value", JsonSchema.scalar().title("Value"))
                .property("values", JsonSchema.array(JsonSchema.scalar()).maxItems(1000).title("Values").description("in / not_in."))
                .property("ignoreCase", JsonSchema.bool().title("Ignore case").defaultValue(false)))
                .minItems(1).maxItems(50).title("Conditions")))
        .aiToolName("filter_rows")
        .build();

    public FilterStepTask() {
        super(SPEC);
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        List<Map<String, Object>> conditions = Configs.objects(config, "conditions");
        for (int i = 0; i < conditions.size(); i++) {
            String operator = Configs.text(conditions.get(i), "operator", "");
            String at = "conditions[" + i + "]";
            if (LISTED.contains(operator) && !(conditions.get(i).get("values") instanceof List)) {
                problems.add(new DefinitionProblem(at + ".values", operator + " needs its list of values"));
            } else if (!UNARY.contains(operator) && !LISTED.contains(operator) && !conditions.get(i).containsKey("value")) {
                problems.add(new DefinitionProblem(at + ".value", operator + " needs a value to compare with"));
            }
        }
        return problems;
    }

    @Override
    public StepResult run(StepContext context) {
        boolean any = "any".equals(Configs.text(context.config(), "match", "all"));
        List<Map<String, Object>> conditions = Configs.objects(context.config(), "conditions");
        Dataset input = context.input();
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> row : input.getRows()) {
            boolean keep = !any;
            for (Map<String, Object> condition : conditions) {
                boolean met = meets(row.get(Configs.text(condition, "column", "")), condition);
                if (any && met) {
                    keep = true;
                    break;
                }
                if (!any && !met) {
                    keep = false;
                    break;
                }
            }
            if (keep) {
                kept.add(row);
            }
        }
        context.log(String.format("%d of %d row(s) kept.", kept.size(), input.size()));
        return StepResult.of(new Dataset(input.getColumns(), kept));
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

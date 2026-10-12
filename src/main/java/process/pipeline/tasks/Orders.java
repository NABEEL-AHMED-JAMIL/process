package process.pipeline.tasks;

import process.pipeline.DefinitionProblem;
import process.pipeline.data.RowOrder;
import process.pipeline.registry.JsonSchema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The order setting Sort and Limit share (MIG-365): columns, each ascending or descending, empty values first or last. */
final class Orders {

    private Orders() {
    }

    static JsonSchema schema(String title, String description) {
        return JsonSchema.array(JsonSchema.object()
                .required("column", JsonSchema.string().minLength(1).maxLength(255).title("Column").format("column"))
                .property("direction", JsonSchema.string().enumOf("asc", "desc").title("Direction").defaultValue("asc")
                    .description("asc: smallest first (A to Z, oldest date first); desc: largest first."))
                .property("empty", JsonSchema.string().enumOf("last", "first").title("Empty values").defaultValue("last")
                    .description("Where rows with no value in this column go, in either direction.")))
            .minItems(1).maxItems(10).title(title).description(description);
    }

    static List<DefinitionProblem> check(Map<String, Object> config, String key) {
        List<DefinitionProblem> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> by = Configs.objects(config, key);
        for (int i = 0; i < by.size(); i++) {
            String column = Configs.text(by.get(i), "column", "");
            if (!seen.add(column)) {
                problems.add(new DefinitionProblem(key + "[" + i + "].column", String.format("'%s' is already in the order", column)));
            }
        }
        return problems;
    }

    /** The order the setting names; null when it names none. */
    static RowOrder of(Map<String, Object> config, String key) {
        List<Map<String, Object>> by = Configs.objects(config, key);
        if (by.isEmpty()) {
            return null;
        }
        List<RowOrder.By> columns = new ArrayList<>();
        for (Map<String, Object> one : by) {
            columns.add(new RowOrder.By(Configs.text(one, "column", ""), "desc".equals(Configs.text(one, "direction", "asc")),
                "first".equals(Configs.text(one, "empty", "last"))));
        }
        return new RowOrder(columns);
    }

    /** The order's columns the input does not have, refused with the ones it has. */
    static void requireColumns(RowOrder order, List<String> has, String what) {
        List<String> missing = new ArrayList<>();
        for (String column : order.columns()) {
            if (!has.contains(column)) {
                missing.add(column);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(String.format("%s has no column %s; its columns are: %s.", what, String.join(", ", missing),
                String.join(", ", has.subList(0, Math.min(40, has.size()))) + (has.size() > 40 ? String.format(" (and %d more)", has.size() - 40) : "")));
        }
    }

    /** "a desc, b" for the log. */
    static String describe(RowOrder order) {
        StringBuilder text = new StringBuilder();
        for (RowOrder.By by : order.by()) {
            text.append(text.length() == 0 ? "" : ", ").append(by.column).append(by.descending ? " desc" : "")
                .append(by.emptyFirst ? " (empty first)" : "");
        }
        return text.toString();
    }
}

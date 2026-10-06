package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.Dataset;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.data.Limits;
import process.pipeline.data.Values;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Join (MIG-231): the step's input (left) with an earlier step's output (right, {@code with}) on equal keys -- 1, 1.0
 * and "1" are one key; null meets nothing. {@code inner} keeps the pairs; {@code left} keeps every left row, with the
 * right's columns empty when nothing matched. The right's key columns are dropped (they equal the left's); a right
 * column whose name the left has is prefixed ({@code prefix}, default "right_"). A hash join on the right side, both
 * within the step's bounds, and the output too: a key that multiplies rows past them fails the step. Pure Core.
 */
@Component
public class JoinStepTask extends RegisteredTask {

    static final TaskSpec SPEC = TaskSpec.builder("join", "Join", TaskKind.PROCESS)
        .description("Joins the rows with an earlier step's output on equal keys (inner or left).")
        .input(TaskSpec.rows("The left rows."))
        .output(TaskSpec.rows("Left columns, then the right's (prefixed on a clash, keys dropped)."))
        .config(JsonSchema.object()
            .required("with", JsonSchema.string().minLength(1).title("Join with").format("step")
                .description("The earlier step whose output is the right side."))
            .required("on", JsonSchema.array(JsonSchema.object()
                .required("left", JsonSchema.string().minLength(1).maxLength(128).title("Left column").format("column"))
                .required("right", JsonSchema.string().minLength(1).maxLength(128).title("Right column")))
                .minItems(1).maxItems(10).title("On"))
            .property("type", JsonSchema.string().enumOf("inner", "left").title("Keep").defaultValue("inner")
                .description("inner: matched rows only; left: every row of the input."))
            .property("prefix", JsonSchema.string().maxLength(32).title("Prefix for clashing columns").defaultValue("right_")))
        .aiToolName("join_datasets")
        .build();

    public JoinStepTask() {
        super(SPEC);
    }

    @Override
    public StepResult run(StepContext context) throws Exception {
        Map<String, Object> config = context.config();
        String with = Configs.text(config, "with", null);
        boolean left = "left".equals(Configs.text(config, "type", "inner"));
        String prefix = config.get("prefix") == null ? "right_" : config.get("prefix").toString();
        List<String[]> on = new ArrayList<>();
        for (Map<String, Object> pair : Configs.objects(config, "on")) {
            on.add(new String[] {Configs.text(pair, "left", ""), Configs.text(pair, "right", "")});
        }
        Dataset input = context.input();
        Dataset right = context.dataset(with);
        Set<String> rightKeys = new LinkedHashSet<>();
        for (String[] pair : on) {
            rightKeys.add(pair[1]);
        }
        Map<String, String> rename = new LinkedHashMap<>();
        for (String column : right.getColumns()) {
            if (!rightKeys.contains(column)) {
                rename.put(column, input.getColumns().contains(column) ? prefix + column : column);
            }
        }
        Set<String> columns = new LinkedHashSet<>(input.getColumns());
        columns.addAll(rename.values());
        Map<String, List<Map<String, Object>>> index = new HashMap<>();
        for (Map<String, Object> row : right.getRows()) {
            String key = key(row, on, 1);
            if (key != null) {
                index.computeIfAbsent(key, absent -> new ArrayList<>()).add(row);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int unmatched = 0;
        for (Map<String, Object> row : input.getRows()) {
            String key = key(row, on, 0);
            List<Map<String, Object>> matches = key == null ? null : index.get(key);
            if (matches == null || matches.isEmpty()) {
                unmatched++;
                if (left) {
                    Map<String, Object> out = new LinkedHashMap<>(row);
                    rename.values().forEach(column -> out.putIfAbsent(column, null));
                    rows.add(out);
                }
                continue;
            }
            for (Map<String, Object> match : matches) {
                Map<String, Object> out = new LinkedHashMap<>(row);
                rename.forEach((from, to) -> out.put(to, match.get(from)));
                rows.add(out);
                if (rows.size() > Limits.MAX_ROWS) {
                    throw new IllegalStateException(String.format("The join makes more than %,d rows, the most a step holds; "
                        + "its keys match too many rows.", Limits.MAX_ROWS));
                }
            }
        }
        Limits.requireShape(rows.size(), columns.size(), "The joined rows");
        context.log(String.format("%d row(s) joined with <%s> (%d row(s)); %d input row(s) matched nothing.", rows.size(), with, right.size(),
            unmatched));
        return StepResult.of(new Dataset(new ArrayList<>(columns), rows));
    }

    /** The row's key over the pairs' side (0 left, 1 right); null when any part is null. */
    private static String key(Map<String, Object> row, List<String[]> on, int side) {
        StringBuilder key = new StringBuilder();
        for (String[] pair : on) {
            String part = Values.key(row.get(pair[side]));
            if (part == null) {
                return null;
            }
            key.append(part.length()).append(':').append(part).append('|');
        }
        return key.toString();
    }
}

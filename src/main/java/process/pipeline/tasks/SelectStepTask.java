package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.data.RowSink;
import process.pipeline.data.RowSource;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps some columns of its input, optionally renamed (MIG-230): {@code config.columns} is a list of names to keep, or
 * an object {@code {from: to}} to keep and rename, in that order. {@code required: true} fails the step when a column
 * is missing from the input; otherwise a missing column is null and the step's log says so once.
 *
 * The smallest shaping step, so a multi-step pipeline can be built before the Task Registry's Transform (MIG-231)
 * exists; MIG-231 may absorb it. MIG-344: streamed, a row at a time.
 */
@Component
public class SelectStepTask implements StreamingStepTask {

    static final TaskSpec SPEC = TaskSpec.builder("select", "Select columns", TaskKind.PROCESS)
        .description("Keeps the columns it names, in that order, optionally renamed.")
        .input(TaskSpec.rows("Any rows."))
        .output(TaskSpec.rows("The named columns only, in that order, renamed where asked."))
        .config(JsonSchema.object()
            .required("columns", JsonSchema.anyOf("array", "object").title("Columns")
                .description("A list of column names to keep, or an object {from: to} to keep and rename."))
            .property("required", JsonSchema.bool().title("Fail on a missing column").defaultValue(false)
                .description("Fail the step when the input has no such column; otherwise the column is empty.")))
        .aiToolName("select_columns")
        .build();

    @Override
    public TaskSpec spec() {
        return SPEC;
    }

    @Override
    public String code() {
        return "select";
    }

    @Override
    public String description() {
        return "Keeps the columns it names, in that order, optionally renamed.";
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        Configs.onlyKnownKeys(config, problems, "columns", "required");
        Object columns = config.get("columns");
        if (columns instanceof List) {
            List<?> names = (List<?>) columns;
            if (names.isEmpty()) {
                problems.add(new DefinitionProblem("columns", "name at least one column"));
            }
            for (int i = 0; i < names.size(); i++) {
                if (!(names.get(i) instanceof String) || ((String) names.get(i)).trim().isEmpty()) {
                    problems.add(new DefinitionProblem("columns[" + i + "]", "a column name is text"));
                }
            }
        } else if (columns instanceof Map) {
            Map<?, ?> renames = (Map<?, ?>) columns;
            if (renames.isEmpty()) {
                problems.add(new DefinitionProblem("columns", "name at least one column"));
            }
            for (Map.Entry<?, ?> rename : renames.entrySet()) {
                if (!(rename.getValue() instanceof String) || ((String) rename.getValue()).trim().isEmpty()) {
                    problems.add(new DefinitionProblem("columns." + rename.getKey(), "the new name is text"));
                }
            }
        } else {
            problems.add(new DefinitionProblem("columns", "a list of column names, or an object {from: to}"));
        }
        Object required = config.get("required");
        if (required != null && !(required instanceof Boolean)) {
            problems.add(new DefinitionProblem("required", "true or false"));
        }
        return problems;
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        Map<String, String> picks = picks(context.config().get("columns"));
        boolean required = Boolean.TRUE.equals(context.config().get("required"));
        RowSink out = context.output();
        try (RowSource input = context.openInput()) {
            List<String> missing = new ArrayList<>();
            for (String from : picks.keySet()) {
                if (!input.columns().contains(from)) {
                    missing.add(from);
                }
            }
            if (!missing.isEmpty()) {
                if (required) {
                    throw new IllegalArgumentException("The input has no column " + String.join(", ", missing) + ".");
                }
                context.warn("The input has no column " + String.join(", ", missing) + "; left empty.");
            }
            out.declare(new ArrayList<>(picks.values()));
            for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Select was stopped.");
                }
                for (Map<String, Object> row : batch) {
                    Map<String, Object> picked = new LinkedHashMap<>();
                    picks.forEach((from, to) -> picked.put(to, row.get(from)));
                    out.add(picked);
                }
            }
        }
        context.log(String.format("%d row(s), %d column(s) kept.", out.size(), picks.size()));
        return StepResult.streamed(out.size());
    }

    private static Map<String, String> picks(Object columns) {
        Map<String, String> picks = new LinkedHashMap<>();
        if (columns instanceof List) {
            for (Object name : (List<?>) columns) {
                picks.put(String.valueOf(name).trim(), String.valueOf(name).trim());
            }
        } else if (columns instanceof Map) {
            ((Map<?, ?>) columns).forEach((from, to) -> picks.put(String.valueOf(from), String.valueOf(to).trim()));
        }
        return picks;
    }
}

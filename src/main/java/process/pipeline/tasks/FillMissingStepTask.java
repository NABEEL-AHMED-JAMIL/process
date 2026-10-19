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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fill missing values (owner #16, 2026-10-19): an empty cell of a named column gets a fixed value ("Unknown", 0) or the
 * column's last value above it (a ward written once for a block of rows). Streamed, a row at a time; the previous value
 * is the input's order (sort first when it matters).
 */
@Component
public class FillMissingStepTask extends RegisteredTask implements StreamingStepTask {

    static final TaskSpec SPEC = TaskSpec.builder("fill_missing", "Fill missing values", TaskKind.PROCESS)
        .description("Fills empty cells of the columns you name: with a value, or with the last value above them.")
        .input(TaskSpec.rows("Any rows."))
        .output(TaskSpec.rows("The same rows and columns, the named columns' gaps filled."))
        .config(JsonSchema.object()
            .required("fills", JsonSchema.array(JsonSchema.object()
                .required("column", JsonSchema.string().minLength(1).maxLength(128).title("Column").format("column"))
                .property("with", JsonSchema.string().enumOf("value", "previous").title("Fill with").defaultValue("value")
                    .description("value: the value below; previous: the column's last value above (none above stays empty)."))
                .property("value", JsonSchema.scalar().title("Value").description("value: what an empty cell gets.")))
                .minItems(1).maxItems(100).title("Columns to fill"))
            .property("blankIsEmpty", JsonSchema.bool().title("Blank text is empty").defaultValue(true)
                .description("Treat text of only spaces as empty, as well as a cell with nothing in it.")))
        .backing(TaskSpec.CORE)
        .aiToolName("fill_missing_values")
        .build();

    public FillMissingStepTask() {
        super(SPEC);
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        Configs.onlyKnownKeys(config, problems, "fills", "blankIsEmpty");
        List<Map<String, Object>> fills = Configs.objects(config, "fills");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < fills.size(); i++) {
            Map<String, Object> fill = fills.get(i);
            String at = "fills[" + i + "]";
            Configs.onlyKnownKeys(fill, problems, "column", "with", "value");
            String column = Configs.text(fill, "column", "");
            if (!column.isEmpty() && !seen.add(column)) {
                problems.add(new DefinitionProblem(at + ".column", String.format("'%s' is filled twice", column)));
            }
            if ("value".equals(Configs.text(fill, "with", "value")) && !fill.containsKey("value")) {
                problems.add(new DefinitionProblem(at + ".value", "what an empty cell gets is required"));
            }
            if (fill.containsKey("value") && !Configs.isScalar(fill.get("value"))) {
                problems.add(new DefinitionProblem(at + ".value", "text, a number or true/false"));
            }
        }
        return problems;
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        List<Map<String, Object>> fills = Configs.objects(context.config(), "fills");
        boolean blank = Configs.bool(context.config(), "blankIsEmpty", true);
        List<String> columns = new ArrayList<>();
        fills.forEach(f -> columns.add(Configs.text(f, "column", "")));
        Map<String, Object> previous = new HashMap<>();
        Map<String, Long> filled = new LinkedHashMap<>();
        columns.forEach(c -> filled.put(c, 0L));
        RowSink out = context.output();
        try (RowSource input = context.openInput()) {
            DedupeStepTask.requireColumns(columns, input.columns());
            out.declare(input.columns());
            for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Fill missing values was stopped.");
                }
                for (Map<String, Object> row : batch) {
                    Map<String, Object> next = new LinkedHashMap<>(row);
                    for (Map<String, Object> fill : fills) {
                        String column = Configs.text(fill, "column", "");
                        Object value = row.get(column);
                        boolean empty = value == null || (value instanceof String && (blank ? ((String) value).trim().isEmpty() : ((String) value).isEmpty()));
                        if (!empty) {
                            previous.put(column, value);
                            continue;
                        }
                        Object with = "previous".equals(Configs.text(fill, "with", "value")) ? previous.get(column) : fill.get("value");
                        if (with != null) {
                            next.put(column, with);
                            filled.merge(column, 1L, Long::sum);
                        }
                    }
                    out.add(next);
                }
            }
        }
        List<String> said = new ArrayList<>();
        filled.forEach((c, n) -> said.add(String.format("%s %,d", c, n)));
        context.log(String.format("%,d row(s); cells filled: %s.", out.size(), String.join(", ", said)));
        return StepResult.streamed(out.size());
    }
}

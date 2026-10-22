package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.data.RowSink;
import process.pipeline.data.RowSource;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.ItemRole;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Unpivot (owner #16, 2026-10-19): wide rows to long ones -- each of the named columns becomes a row of its own, holding
 * the column's name and its value, with the other columns repeated (a month per column to a row per month). Streamed, a
 * row at a time.
 */
@Component
public class UnpivotStepTask extends RegisteredTask implements StreamingStepTask {

    static final int MAX_COLUMNS = 200;

    static final TaskSpec SPEC = TaskSpec.builder("unpivot", "Unpivot (columns to rows)", TaskKind.PROCESS)
        .description("Turns columns into rows: each named column becomes a row with its name and value, the other columns repeated.")
        .input(TaskSpec.rows("Wide rows."))
        .output(TaskSpec.rows("The other columns, then the name column and the value column; a row per named column."))
        .config(JsonSchema.object()
            .required("columns", JsonSchema.array(JsonSchema.string().minLength(1).maxLength(128).format("column")).minItems(1)
                .maxItems(MAX_COLUMNS).title("Columns to turn into rows").description("Each becomes a row of its own."))
            .property("nameAs", JsonSchema.string().minLength(1).maxLength(128).title("Name column").defaultValue("name")
                .description("The new column that holds which column a row came from."))
            .property("valueAs", JsonSchema.string().minLength(1).maxLength(128).title("Value column").defaultValue("value")
                .description("The new column that holds its value."))
            .property("keepEmpty", JsonSchema.bool().title("Keep empty values").defaultValue(false)
                .description("Also make a row for an empty value; otherwise it is left out.")))
        .backing(TaskSpec.CORE)
        .aiToolName("unpivot_columns")
        .items(ItemRole.SHARED).build();

    public UnpivotStepTask() {
        super(SPEC);
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        Configs.onlyKnownKeys(config, problems, "columns", "nameAs", "valueAs", "keepEmpty");
        List<String> columns = Configs.texts(config, "columns");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < columns.size(); i++) {
            if (!seen.add(columns.get(i))) {
                problems.add(new DefinitionProblem("columns[" + i + "]", String.format("'%s' is named twice", columns.get(i))));
            }
        }
        String nameAs = Configs.text(config, "nameAs", "name");
        String valueAs = Configs.text(config, "valueAs", "value");
        if (nameAs.equals(valueAs)) {
            problems.add(new DefinitionProblem("valueAs", "the name and value columns need different names"));
        }
        return problems;
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        Map<String, Object> config = context.config();
        List<String> turned = Configs.texts(config, "columns");
        String nameAs = Configs.text(config, "nameAs", "name");
        String valueAs = Configs.text(config, "valueAs", "value");
        boolean keepEmpty = Configs.bool(config, "keepEmpty", false);
        RowSink out = context.output();
        long in = 0;
        try (RowSource input = context.openInput()) {
            DedupeStepTask.requireColumns(turned, input.columns());
            List<String> kept = new ArrayList<>();
            for (String c : input.columns()) {
                if (!turned.contains(c)) {
                    kept.add(c);
                }
            }
            if (kept.contains(nameAs) || kept.contains(valueAs)) {
                throw new IllegalArgumentException(String.format("The input already has a column '%s'; name the new columns differently.",
                    kept.contains(nameAs) ? nameAs : valueAs));
            }
            List<String> columns = new ArrayList<>(kept);
            columns.add(nameAs);
            columns.add(valueAs);
            out.declare(columns);
            for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Unpivot was stopped.");
                }
                for (Map<String, Object> row : batch) {
                    in++;
                    for (String c : turned) {
                        Object value = row.get(c);
                        if (!keepEmpty && (value == null || (value instanceof String && ((String) value).trim().isEmpty()))) {
                            continue;
                        }
                        Map<String, Object> next = new LinkedHashMap<>();
                        kept.forEach(k -> next.put(k, row.get(k)));
                        next.put(nameAs, c);
                        next.put(valueAs, value);
                        out.add(next);
                    }
                }
            }
        }
        context.log(String.format("%,d row(s) in, %,d out: %d column(s) turned into rows (%s, %s).", in, out.size(), turned.size(), nameAs, valueAs));
        return StepResult.streamed(out.size());
    }
}

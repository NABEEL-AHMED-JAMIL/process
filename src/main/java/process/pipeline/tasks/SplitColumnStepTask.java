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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Split column (owner #16, 2026-10-19): one text column cut at a separator -- into named columns (a "last, first" name, a
 * "2026-10-19" date into year, month, day), or into one row per part (a list of codes in one cell). Streamed, a row at a
 * time. Parts beyond the named columns stay together in the last one; missing parts are empty.
 */
@Component
public class SplitColumnStepTask extends RegisteredTask implements StreamingStepTask {

    static final int MAX_INTO = 50;
    /** One cell's parts, in rows mode: a cell with more is refused rather than multiplying the table without bound. */
    static final int MAX_PARTS = 1000;

    static final TaskSpec SPEC = TaskSpec.builder("split_column", "Split column", TaskKind.PROCESS)
        .description("Cuts a text column at a separator: into named columns, or into one row per part.")
        .input(TaskSpec.rows("Rows with a text column to split."))
        .output(TaskSpec.rows("columns: the input with the new columns; rows: one row per part."))
        .config(JsonSchema.object()
            .required("column", JsonSchema.string().minLength(1).maxLength(128).title("Column").format("column")
                .description("The column to split."))
            .property("separator", JsonSchema.string().minLength(1).maxLength(32).title("Separator").defaultValue(",")
                .description("The text between parts, taken literally (a comma, \" - \", a pipe)."))
            .property("mode", JsonSchema.string().enumOf("columns", "rows").title("Into").defaultValue("columns")
                .description("columns: each part into a column of its own; rows: one row per part, the other columns repeated."))
            .property("into", JsonSchema.array(JsonSchema.string().minLength(1).maxLength(128)).maxItems(MAX_INTO).title("New columns")
                .description("columns: the parts' names, in order (needed). rows: one name for the part (empty: the column itself)."))
            .property("trim", JsonSchema.bool().title("Trim spaces").defaultValue(true)
                .description("Remove the spaces around each part."))
            .property("keepOriginal", JsonSchema.bool().title("Keep the column").defaultValue(false)
                .description("columns: keep the column split as well as its parts."))
            .property("skipEmpty", JsonSchema.bool().title("Skip empty parts").defaultValue(true)
                .description("rows: no row for an empty part (\"a,,b\" is two rows); a row with nothing to split keeps one empty row.")))
        .backing(TaskSpec.CORE)
        .aiToolName("split_column")
        .build();

    public SplitColumnStepTask() {
        super(SPEC);
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        Configs.onlyKnownKeys(config, problems, "column", "separator", "mode", "into", "trim", "keepOriginal", "skipEmpty");
        boolean rows = "rows".equals(Configs.text(config, "mode", "columns"));
        List<String> into = Configs.texts(config, "into");
        if (!rows && into.isEmpty()) {
            problems.add(new DefinitionProblem("into", "name the columns the parts go into"));
        }
        if (rows && into.size() > 1) {
            problems.add(new DefinitionProblem("into", "rows: at most one name, the column each part goes into"));
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < into.size(); i++) {
            if (!seen.add(into.get(i))) {
                problems.add(new DefinitionProblem("into[" + i + "]", String.format("'%s' is named twice", into.get(i))));
            }
        }
        String column = Configs.text(config, "column", "");
        if (!rows && Configs.bool(config, "keepOriginal", false) && into.contains(column)) {
            problems.add(new DefinitionProblem("into", String.format("'%s' is the column split and is kept; name its parts differently", column)));
        }
        return problems;
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        Map<String, Object> config = context.config();
        String column = Configs.text(config, "column", "");
        String separator = config.get("separator") == null ? "," : config.get("separator").toString();
        boolean rowsMode = "rows".equals(Configs.text(config, "mode", "columns"));
        List<String> into = Configs.texts(config, "into");
        boolean trim = Configs.bool(config, "trim", true);
        boolean keepOriginal = Configs.bool(config, "keepOriginal", false);
        boolean skipEmpty = Configs.bool(config, "skipEmpty", true);
        Pattern cut = Pattern.compile(Pattern.quote(separator));
        RowSink out = context.output();
        long in = 0;
        try (RowSource input = context.openInput()) {
            if (!input.columns().contains(column)) {
                throw new IllegalArgumentException("The input has no column " + column + ".");
            }
            String target = rowsMode && !into.isEmpty() ? into.get(0) : column;
            List<String> columns = new ArrayList<>();
            for (String c : input.columns()) {
                if (rowsMode) {
                    columns.add(c.equals(column) ? target : c);
                } else if (!c.equals(column) || keepOriginal) {
                    if (!into.contains(c)) {
                        columns.add(c);
                    }
                }
                if (!rowsMode && c.equals(column)) {
                    into.forEach(name -> { if (!columns.contains(name)) columns.add(name); });
                }
            }
            out.declare(columns);
            for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Split column was stopped.");
                }
                for (Map<String, Object> row : batch) {
                    in++;
                    Object value = row.get(column);
                    String text = value == null ? "" : value.toString();
                    if (rowsMode) {
                        String[] parts = text.isEmpty() ? new String[0] : cut.split(text, -1);
                        if (parts.length > MAX_PARTS) {
                            throw new IllegalArgumentException(String.format("Row %,d: '%s' holds %,d parts; a cell is split into at most %,d rows.",
                                in, column, parts.length, MAX_PARTS));
                        }
                        int made = 0;
                        for (String part : parts) {
                            String p = trim ? part.trim() : part;
                            if (skipEmpty && p.isEmpty()) {
                                continue;
                            }
                            out.add(rowWith(row, column, target, p));
                            made++;
                        }
                        if (made == 0) {
                            out.add(rowWith(row, column, target, null));
                        }
                    } else {
                        String[] parts = text.isEmpty() ? new String[0] : cut.split(text, into.size());
                        Map<String, Object> next = new LinkedHashMap<>();
                        for (String c : columns) {
                            next.put(c, row.get(c));
                        }
                        for (int i = 0; i < into.size(); i++) {
                            String p = i < parts.length ? (trim ? parts[i].trim() : parts[i]) : null;
                            next.put(into.get(i), p == null || p.isEmpty() ? null : p);
                        }
                        out.add(next);
                    }
                }
            }
        }
        context.log(rowsMode ? String.format("%,d row(s) in, %,d out: '%s' split at \"%s\", a row per part.", in, out.size(), column, separator)
            : String.format("%,d row(s): '%s' split at \"%s\" into %s.", in, column, separator, String.join(", ", into)));
        return StepResult.streamed(out.size());
    }

    private static Map<String, Object> rowWith(Map<String, Object> row, String column, String target, Object value) {
        Map<String, Object> next = new LinkedHashMap<>();
        for (Map.Entry<String, Object> cell : row.entrySet()) {
            if (cell.getKey().equals(column)) {
                next.put(target, value);
            } else {
                next.put(cell.getKey(), cell.getValue());
            }
        }
        if (!next.containsKey(target)) {
            next.put(target, value);
        }
        return next;
    }
}

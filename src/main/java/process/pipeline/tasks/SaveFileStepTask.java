package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.RunOutput;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.data.FileFormats;
import process.pipeline.data.Limits;
import process.pipeline.data.RowSource;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import process.pipeline.data.Values;

/**
 * Save File (MIG-231): the step's input as a file -- CSV, JSON or JSON Lines -- kept with the run beside its datasets
 * (a run_dataset named by the file) for as long as the run's datasets are kept (settings.datasetRetentionHours). The
 * rows pass on unchanged: the next step reads what this one read. Pure Core; Parquet is read, not written, here.
 *
 * MIG-344: streamed -- the rows go straight from the input dataset to the file, its sha256 worked out on the way.
 *
 * <p>The name takes the run's placeholders ({{date}}, {{run}}). With "One file per row" each row is a file of its own --
 * a JSON file is the row's object -- named from the row's columns: {{file_stem}}-insight.json; a row's values are made
 * safe (FileRows), a name taken twice gets -2, -3, and at most {@code maxFiles} rows are written (more fail the step
 * before any file). The columns in {@code jsonColumns} that hold JSON text are written as the lists and objects they hold.</p>
 */
@Component
public class SaveFileStepTask extends RegisteredTask implements StreamingStepTask {

    static final String FILE_NAME = "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$";
    /** A name, or a template of one: placeholders allowed, filled and made a plain name when the step runs. */
    static final String FILE_NAME_TEMPLATE = "^[A-Za-z0-9{][A-Za-z0-9._{} -]{0,199}$";
    static final int MAX_FILES = Limits.MAX_OBJECTS;

    static final TaskSpec SPEC = TaskSpec.builder("save_file", "Save File", TaskKind.OUTPUT)
        .description("Keeps the rows as a CSV, JSON or JSON Lines file with the run -- or each row as a file of its own.")
        .input(TaskSpec.rows("The rows to save."))
        .output(TaskSpec.rows("The input, unchanged."))
        .config(JsonSchema.object()
            .required("fileName", JsonSchema.string().pattern(FILE_NAME_TEMPLATE).format("template").title("File name")
                .description("letters, digits, '.', '-' and '_', at most 128; the run's placeholders ({{date}}) and, one file per "
                    + "row, the row's columns ({{file_stem}}-insight.json) are filled in"))
            .property("format", JsonSchema.string().enumOf("csv", "json", "jsonl").title("Format").defaultValue("csv"))
            .property("perRow", JsonSchema.bool().title("One file per row").defaultValue(false)
                .description("Each row is a file of its own (a JSON file is the row's object), named from its columns."))
            .property("maxFiles", JsonSchema.integer().minimum(1).maximum(MAX_FILES).title("At most files").defaultValue(100)
                .description("One file per row: an input with more rows fails before any file is written."))
            .property("jsonColumns", JsonSchema.array(JsonSchema.string().minLength(1).maxLength(255).format("column"))
                .maxItems(Limits.MAX_COLUMNS).title("JSON columns")
                .description("Columns holding JSON text (an AI answer's lists): a JSON file holds them as lists and objects.")))
        .aiToolName("save_file")
        .build();

    public SaveFileStepTask() {
        super(SPEC);
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        String name = Configs.text(config, "fileName", "");
        if (!Configs.bool(config, "perRow", false) && !name.contains("{{") && !name.matches(FILE_NAME)) {
            problems.add(new DefinitionProblem("fileName", "letters, digits, '.', '-' and '_', at most 128"));
        }
        return problems;
    }

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        String template = Configs.text(context.config(), "fileName", null);
        String format = Configs.text(context.config(), "format", "csv");
        List<String> json = Configs.texts(context.config(), "jsonColumns");
        try (RowSource input = context.openInput()) {
            long rows = input.size();
            Map<String, Object> runValues = Templates.ofRun(context, (int) Math.min(Integer.MAX_VALUE, rows));
            if (Configs.bool(context.config(), "perRow", false)) {
                return this.perRow(context, input, template, format, json, runValues);
            }
            String fileName = named(Templates.fill(template, runValues));
            StreamContext.KeptFile kept = context.keepFile(fileName, input.columns(), rows, out -> {
                if (json.isEmpty() || "csv".equals(format)) {
                    FileFormats.writeTo(input, format, out);
                } else {
                    FileFormats.writeTo(new StructuredRows(input, json), format, out);
                }
            });
            context.recordOutput(RunOutput.file(fileName, format, rows, kept.bytes).withSha256(kept.sha256));
            context.log(String.format("Saved %d row(s) as %s (%s, %,d bytes).", rows, fileName, format, kept.bytes));
            return StepResult.nothing(rows);
        }
    }

    private StepResult perRow(StreamContext context, RowSource input, String template, String format, List<String> json,
        Map<String, Object> runValues) throws Exception {
        long rows = input.size();
        int maxFiles = Configs.integer(context.config(), "maxFiles", 100);
        if (rows > maxFiles) {
            throw new IllegalStateException(String.format("The input has %,d rows; this step writes at most %d files (maxFiles).", rows, maxFiles));
        }
        List<String> columns = input.columns();
        Set<String> taken = new HashSet<>();
        int number = 0;
        long bytes = 0;
        for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
            for (Map<String, Object> row : batch) {
                number++;
                String fileName = FileRows.unique(named(FileRows.fillForRow(template, runValues, row, number)), taken);
                StreamContext.KeptFile kept = context.keepFile(fileName, columns, 1, out -> FileFormats.writeRow(columns, row, format, json, out));
                context.recordOutput(RunOutput.file(fileName, format, 1, kept.bytes).withSha256(kept.sha256));
                bytes += kept.bytes;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Save File was stopped.");
            }
        }
        context.log(String.format("Saved %d row(s) as %d %s file(s) (%,d bytes).", rows, number, format, bytes));
        return StepResult.nothing(rows);
    }

    /** The filled name as a plain one; an unfilled placeholder is a mistake to say, not a name to keep. */
    static String named(String filled) {
        if (filled == null || filled.contains("{{")) {
            throw new IllegalArgumentException(String.format("'%s' names a placeholder this run has no value for.", filled));
        }
        return FileRows.plainName(filled);
    }

    /** The input with its JSON columns' text as lists and objects, for a JSON file that shows them as such. */
    private static final class StructuredRows implements RowSource {
        private final RowSource rows;
        private final List<String> json;

        StructuredRows(RowSource rows, List<String> json) {
            this.rows = rows;
            this.json = json;
        }

        @Override
        public List<String> columns() {
            return this.rows.columns();
        }

        @Override
        public long size() {
            return this.rows.size();
        }

        @Override
        public List<Map<String, Object>> next() throws Exception {
            List<Map<String, Object>> batch = this.rows.next();
            if (batch == null) {
                return null;
            }
            List<Map<String, Object>> out = new ArrayList<>(batch.size());
            for (Map<String, Object> row : batch) {
                Map<String, Object> copy = new LinkedHashMap<>(row);
                for (String column : this.json) {
                    if (copy.containsKey(column)) {
                        copy.put(column, Values.structured(copy.get(column)));
                    }
                }
                out.add(copy);
            }
            return out;
        }

        @Override
        public void close() throws Exception {
            this.rows.close();
        }
    }
}

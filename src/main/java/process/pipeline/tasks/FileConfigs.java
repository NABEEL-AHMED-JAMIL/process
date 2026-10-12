package process.pipeline.tasks;

import process.pipeline.data.FileFormats;
import process.pipeline.data.Limits;
import process.pipeline.registry.JsonSchema;

import java.util.Map;
import java.util.List;
import process.pipeline.DefinitionProblem;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;

/** The settings Read S3 and Read CSV/JSON/Parquet share: how a file's rows are read. */
final class FileConfigs {

    static final String BUCKET_DESCRIPTION = "One of the workspace's own storage connections, by its alias.";

    private FileConfigs() {
    }

    static JsonSchema bucket(JsonSchema schema) {
        return schema.required("bucket", JsonSchema.string().minLength(1).maxLength(255).title("Bucket").format("bucket")
            .description(BUCKET_DESCRIPTION));
    }

    static JsonSchema parsing(JsonSchema schema) {
        return schema
            .property("delimiter", JsonSchema.string().minLength(1).maxLength(1).title("CSV delimiter").defaultValue(",")
                .description("The character between a CSV's cells."))
            .property("header", JsonSchema.bool().title("CSV has a header row").defaultValue(true)
                .description("The first row names the columns; otherwise they are c1, c2, ..."))
            .property("rowsPath", JsonSchema.string().maxLength(255).title("JSON rows at")
                .description("Where the rows are in a JSON file, as a dot path (data.items); empty for the whole file."))
            .property("maxRows", JsonSchema.integer().minimum(1).maximum(Limits.MAX_ROWS).title("At most rows")
                .description("Stop after this many rows."))
            .property("columns", JsonSchema.array(JsonSchema.string().minLength(1).maxLength(255).format("column")).maxItems(Limits.MAX_COLUMNS)
                .title("Only these columns")
                .description("Read only these columns, in this order; every column when empty. A wide file's other cells are never "
                    + "loaded. A CSV without a header row names them c1, c2, ..."));
    }

    /** MIG-365: a column named twice in {@code columns} is a problem (the row would hold it once). */
    static List<DefinitionProblem> checkColumns(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> columns = Configs.texts(config, "columns");
        for (int i = 0; i < columns.size(); i++) {
            if (!seen.add(columns.get(i))) {
                problems.add(new DefinitionProblem("columns[" + i + "]", String.format("'%s' is named twice", columns.get(i))));
            }
        }
        return problems;
    }

    static FileFormats.ReadOptions options(Map<String, Object> config) {
        FileFormats.ReadOptions options = new FileFormats.ReadOptions();
        options.delimiter = Configs.text(config, "delimiter", ",").charAt(0);
        options.header = Configs.bool(config, "header", true);
        options.rowsPath = Configs.text(config, "rowsPath", null);
        List<String> columns = Configs.texts(config, "columns");
        options.columns = columns.isEmpty() ? null : columns;
        return options;
    }

    /** The format to read a key as: the setting, else its extension; an exception when neither says. */
    static String formatOf(String setting, String key) {
        if (setting != null && !"auto".equals(setting)) {
            return setting;
        }
        String format = FileFormats.byExtension(key);
        if (format == null) {
            throw new IllegalArgumentException(String.format("%s has no .csv, .json, .jsonl or .parquet extension; set the format.", key));
        }
        return format;
    }
}

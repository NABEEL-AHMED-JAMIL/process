package process.pipeline.tasks;

import process.pipeline.data.FileFormats;
import process.pipeline.data.Limits;
import process.pipeline.registry.JsonSchema;

import java.util.Map;

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
                .description("Stop after this many rows."));
    }

    static FileFormats.ReadOptions options(Map<String, Object> config) {
        FileFormats.ReadOptions options = new FileFormats.ReadOptions();
        options.delimiter = Configs.text(config, "delimiter", ",").charAt(0);
        options.header = Configs.bool(config, "header", true);
        options.rowsPath = Configs.text(config, "rowsPath", null);
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

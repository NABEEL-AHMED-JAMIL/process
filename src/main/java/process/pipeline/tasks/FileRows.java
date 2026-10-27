package process.pipeline.tasks;

import process.pipeline.StepContext;
import process.pipeline.data.Values;
import process.pipeline.registry.JsonSchema;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which file each row names, for the steps that take one file a row (transcribe_audio, convert_file, split_pages; and the
 * images of measure_image, measure_mask and ai_prompt), and the names a step writes one file a row under (save_file,
 * upload_bucket with "One file per row"). MIG-426: a form's file field feeds them as it is ({@link #refOf}).
 *
 * <p>A file is a key in one of the run's workspace's own storage connections, by its alias -- as read_s3, ai_prompt's
 * images and the inbox name files: the step's {@code bucket}, else the row's {@code bucket} column, else the run's own
 * (the inbox the run was started from). The key is the row's {@code keyColumn}, else its {@code key} column (read_s3's
 * listing), else -- for a run started for files -- one row per file the run took, with its {@code bucket} and {@code key}.
 * Every row also gets {@code file_name} (the key's last part) and {@code file_stem} (that name without its extension), so a
 * later step can name its own file after it: {@code insights/{{file_stem}}.json}.</p>
 *
 * <p>A row's values in a file name are made safe first ({@link #segment}): a value cannot add a folder, climb out of
 * one, or carry anything but letters, digits, '.', '-', '_' and spaces. The template's own text and the run's placeholders
 * are written as given.</p>
 */
final class FileRows {

    static final String BUCKET = "bucket";
    static final String KEY = "key";
    static final String FILE_NAME = "file_name";
    static final String FILE_STEM = "file_stem";
    /** The most characters one row value adds to a name. */
    static final int MAX_SEGMENT = 120;

    private FileRows() {
    }

    /** The two settings that say where each row's file is. */
    static JsonSchema addressing(JsonSchema schema, String what) {
        return schema
            .property("bucket", JsonSchema.string().maxLength(255).format("bucket").title("Bucket")
                .description("The workspace's storage connection the " + what + " are in, by its alias. Empty: the row's bucket "
                    + "column, else the run's own (the inbox it was started from)."))
            .property("keyColumn", JsonSchema.string().maxLength(255).format("column").title("File key column")
                .description("The column holding each row's file key, or a form's file field as it is (read where the form stored it). "
                    + "Empty: the key column (Read S3's list), else the files the run was started for (an inbox), one row each."));
    }

    /** One row's file: where it is, and the row it came from (with bucket, key, file_name and file_stem). */
    static final class Target {
        final String bucket;
        final String key;
        final Map<String, Object> row;
        /** MIG-426: why the row names no file, in words, when its value said more than nothing (a form's empty file answer). */
        final String problem;
        /** MIG-426: how many more files the row's value names past the one read. */
        final int more;

        Target(String bucket, String key, Map<String, Object> row) {
            this(bucket, key, row, null, 0);
        }

        Target(String bucket, String key, Map<String, Object> row, String problem, int more) {
            this.bucket = bucket;
            this.key = key;
            this.row = row;
            this.problem = problem;
            this.more = more;
        }

        /** Why this row names no file: its own problem, or that it names none. */
        String missing() {
            return this.problem != null ? this.problem : "The row names no file.";
        }

        String fileName() {
            return nameOf(this.key);
        }

        /** The key's extension, lower case, without the dot; empty when it has none. */
        String extension() {
            String name = this.fileName();
            int dot = name.lastIndexOf('.');
            return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
    }

    /** The column each row's key is read from: the setting, else "key" when the input has one, else null (the run's files). */
    static String keyColumn(Map<String, Object> config, List<String> inputColumns) {
        String column = Configs.text(config, "keyColumn", null);
        if (column != null) {
            if (!inputColumns.contains(column)) {
                throw new IllegalArgumentException(String.format("The input has no column %s to read the file keys from.", column));
            }
            return column;
        }
        return inputColumns.contains(KEY) ? KEY : null;
    }

    /** The run's own files, one row each (bucket, key, file_name, file_stem); an exception when the run took none. */
    static List<Target> runFiles(StepContext context, Map<String, Object> config) {
        List<String> keys = context.inputKeys();
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("No file to read: name the column that holds each row's file key, or start the run "
                + "for files (an inbox trigger).");
        }
        String bucket = Configs.text(config, "bucket", context.inputBucket());
        if (bucket == null) {
            throw new IllegalArgumentException("Name the bucket the files are in.");
        }
        List<Target> out = new ArrayList<>(keys.size());
        for (String key : keys) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(BUCKET, bucket);
            row.put(KEY, key);
            out.add(new Target(bucket, key, withNames(row, key)));
        }
        return out;
    }

    /**
     * The input row's file; an exception (the row's failure) when it names none. MIG-426: the column may be a form's file field
     * as it is -- its upload, or the list of them, or either as JSON text ({@link #refOf}) -- read from where the form stored it
     * and named as it was uploaded.
     */
    static Target of(StepContext context, Map<String, Object> config, String keyColumn, Map<String, Object> row) {
        Ref ref = refOf(row.get(keyColumn), keyColumn);
        String key = ref.key;
        String bucket = ref.bucket != null ? ref.bucket : Configs.text(config, "bucket", null);
        if (bucket == null) {
            Object own = row.get(BUCKET);
            bucket = own == null || own.toString().trim().isEmpty() ? context.inputBucket() : own.toString().trim();
        }
        Map<String, Object> named = new LinkedHashMap<>(row);
        if (ref.name != null) {
            withNames(named, ref.name);
        }
        named = withNames(named, key);
        if (key.isEmpty()) {
            return new Target(bucket, "", named, ref.problem, ref.more);
        }
        return new Target(bucket, key, named, null, ref.more);
    }

    /**
     * MIG-426: a warning on the run when rows' values name several files (a form's field with three photos) -- a step that
     * takes one file a row reads each row's first.
     */
    static void warnSeveral(StepContext context, String column, List<Target> targets) {
        int rows = 0;
        for (Target target : targets) {
            rows += target.more > 0 ? 1 : 0;
        }
        warnSeveral(context, column, rows);
    }

    /** {@link #warnSeveral(StepContext, String, List)} for rows read whole: how many of them name more than one file. */
    static void warnSeveralIn(StepContext context, String column, List<Map<String, Object>> rows) {
        int several = 0;
        for (Map<String, Object> row : rows) {
            several += refOf(row.get(column), column).more > 0 ? 1 : 0;
        }
        warnSeveral(context, column, several);
    }

    private static void warnSeveral(StepContext context, String column, int rows) {
        if (rows > 0) {
            context.warn(String.format("%,d row(s) hold more than one file in %s; this step reads each row's first (one file a row).", rows,
                column));
        }
    }

    /**
     * MIG-426: the file a row's value names. A key as text; or a form's file or signature field as the submission holds it
     * -- an upload {key, bucket, name, ...}, a list of them, or either written as JSON text -- whose first upload it is, in the
     * bucket the form stored it in (the workspace's own, by its alias) and with the name it was uploaded as. An answer with no
     * upload, or one naming no key, is no file and says so ({@link Ref#problem}); a step reading one file a row reads the
     * first of several ({@link Ref#more} counts the rest).
     */
    static Ref refOf(Object value, String column) {
        Object read = value;
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (!(text.startsWith("[") || text.startsWith("{"))) {
                return new Ref(text, null, null, null, 0);
            }
            try {
                read = Values.JSON.readValue(text, Object.class);
            } catch (IOException notJson) {
                return new Ref(text, null, null, null, 0);
            }
        }
        if (read == null) {
            return new Ref("", null, null, null, 0);
        }
        String what = column == null || column.isEmpty() ? "value" : column;
        int more = 0;
        if (read instanceof List) {
            List<?> list = (List<?>) read;
            if (list.isEmpty()) {
                return new Ref("", null, null, String.format("The row's %s holds no file.", what), 0);
            }
            more = list.size() - 1;
            read = list.get(0);
            if (!(read instanceof Map)) {
                return read == null ? new Ref("", null, null, String.format("The row's %s holds no file.", what), more)
                    : new Ref(read.toString().trim(), null, null, null, more);
            }
        }
        if (!(read instanceof Map)) {
            return new Ref(read.toString().trim(), null, null, null, 0);
        }
        Map<?, ?> upload = (Map<?, ?>) read;
        String key = textOf(upload.get(KEY));
        if (key == null) {
            return new Ref("", null, null, String.format("The row's %s names no stored file (no key).", what), more);
        }
        return new Ref(key, textOf(upload.get(BUCKET)), textOf(upload.get("name")), null, more);
    }

    private static String textOf(Object value) {
        String text = value == null ? null : value.toString().trim();
        return text == null || text.isEmpty() ? null : text;
    }

    /** MIG-426: a file a row's value names -- its key ("" for none), its bucket and name when the value says, and why none. */
    static final class Ref {
        final String key;
        /** The bucket the value names (a form's upload); null when it names none and the step's own is used. */
        final String bucket;
        /** The name it was uploaded as; null when the value names none. */
        final String name;
        /** Why the value names no file, in words; null when it names one, or is simply empty. */
        final String problem;
        /** How many more files the value names past the first. */
        final int more;

        Ref(String key, String bucket, String name, String problem, int more) {
            this.key = key;
            this.bucket = bucket;
            this.name = name;
            this.problem = problem;
            this.more = more;
        }

        /** The key, or an exception naming why there is none: the value's own problem, else {@code none}. */
        String keyOr(String none) {
            if (this.key.isEmpty()) {
                throw new IllegalStateException(this.problem != null ? this.problem : none);
            }
            return this.key;
        }
    }

    /** The row with file_name and file_stem of its key, unless it has them already. */
    private static Map<String, Object> withNames(Map<String, Object> row, String key) {
        String name = nameOf(key);
        int dot = name.lastIndexOf('.');
        row.putIfAbsent(FILE_NAME, name);
        row.putIfAbsent(FILE_STEM, dot <= 0 ? name : name.substring(0, dot));
        return row;
    }

    static String nameOf(String key) {
        String k = key == null ? "" : key;
        return k.substring(k.lastIndexOf('/') + 1);
    }

    /**
     * A row's value as a safe part of a name: letters, digits, '.', '-', '_' and spaces kept, anything else (a '/', a
     * '\', a control character) an underscore, no ".." and no leading '.', at most {@value #MAX_SEGMENT} characters;
     * "_" when nothing is left.
     */
    static String segment(Object value) {
        String text = value == null ? "" : String.valueOf(Values.text(value) == null ? "" : Values.text(value));
        StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_SEGMENT));
        for (int i = 0; i < text.length() && out.length() < MAX_SEGMENT; i++) {
            char c = text.charAt(i);
            boolean safe = c < 128 && (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' || c == ' ');
            out.append(safe ? c : '_');
        }
        String clean = out.toString().replace("..", "_").replaceAll("^[. ]+", "").trim();
        return clean.isEmpty() ? "_" : clean;
    }

    /**
     * A name for one row's file: the run's placeholders as given, the row's columns (and {{row}}, its number from 1) made
     * safe by {@link #segment}. The row's columns win over a run placeholder of the same name, as a prompt's do.
     */
    static String fillForRow(String template, Map<String, Object> runValues, Map<String, Object> row, int number) {
        Map<String, Object> values = new LinkedHashMap<>(runValues);
        for (Map.Entry<String, Object> cell : row.entrySet()) {
            values.put(cell.getKey(), segment(cell.getValue()));
        }
        values.put("row", number);
        return Templates.fill(template, values);
    }

    /** A plain file name kept with the run: letters, digits, '.', '-', '_'; at most 128; never empty or hidden. */
    static String plainName(String name) {
        String clean = (name == null ? "" : name).replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("^[._-]+", "");
        if (clean.length() > 128) {
            int dot = clean.lastIndexOf('.');
            String ext = dot > 0 && clean.length() - dot <= 10 ? clean.substring(dot) : "";
            clean = clean.substring(0, 128 - ext.length()) + ext;
        }
        return clean.isEmpty() ? "file" : clean;
    }

    /** {@code name} again with -2, -3 ... before its extension until it is not one already {@code taken}. */
    static String unique(String name, Set<String> taken) {
        if (taken.add(name)) {
            return name;
        }
        int slash = name.lastIndexOf('/');
        int dot = name.lastIndexOf('.');
        boolean ext = dot > slash + 1;
        for (int n = 2; ; n++) {
            String next = ext ? name.substring(0, dot) + "-" + n + name.substring(dot) : name + "-" + n;
            if (taken.add(next)) {
                return next;
            }
        }
    }

    /** An object key a step writes: no leading '/', no '..', no unfilled placeholder, at most 1024 characters. */
    static String checkedKey(String key) {
        if (key == null || key.isEmpty() || key.startsWith("/") || key.contains("..") || key.contains("{{") || key.length() > 1024) {
            throw new IllegalArgumentException(String.format("'%s' is not an object key a step writes: no leading '/', no '..', "
                + "no unfilled placeholder, at most 1,024 characters.", key));
        }
        return key;
    }
}

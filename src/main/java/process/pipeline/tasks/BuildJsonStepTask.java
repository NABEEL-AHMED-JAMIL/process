package process.pipeline.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import process.pipeline.DefinitionProblem;
import process.pipeline.RunOutput;
import process.pipeline.StepResult;
import process.pipeline.StreamContext;
import process.pipeline.StreamingStepTask;
import process.pipeline.data.Expression;
import process.pipeline.data.Limits;
import process.pipeline.data.RowSink;
import process.pipeline.data.RowSource;
import process.pipeline.data.Values;
import process.pipeline.registry.ItemRole;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Build JSON (MIG-451, owner 2026-11-02 "filter and map to new result"): turns rows into the exact JSON a next API or a
 * client app expects. Each key of the shape is a dot path ("patient.id" nests an object) filled from a column, a fixed
 * value, a Compute formula, a list of a column's values, or a list of objects -- one per row -- made of its own keys.
 *
 * <ul>
 *   <li>{@code mode}: one document per row, one per group of rows (Group by columns), or one for the whole input. A
 *       group's (or the input's) column, value and formula keys are read from its first row; its lists from every row.</li>
 *   <li>{@code output}: the document in a column (an object, for Call API's body, Save file or the run's result), the
 *       document's own keys as the columns (the run's result as the shape), or a JSON / JSON Lines file kept with the run
 *       (the rows go on as with a column).</li>
 *   <li>Types are kept: numbers stay numbers, true/false booleans, empty is null; a key's type can make it text, a number,
 *       a boolean or a date (ISO: 2026-11-02, 2026-11-02T14:30:00). Auto keeps the value, and a JSON text (an AI answer's
 *       list) becomes the list or object it holds.</li>
 *   <li>A key with no value is null (or left out with "Leave out empty keys"), and the step says which keys were empty in
 *       how many documents. A document bigger than Largest document fails the step, saying which and by how much.</li>
 * </ul>
 * Streamed: one document per row is written as it is made; a group's documents are held until the input ends.
 */
@Component
public class BuildJsonStepTask extends RegisteredTask implements StreamingStepTask {

    static final String CODE = "build_json";
    static final String KEY_PATTERN = "^[^.]{1,64}(\\.[^.]{1,64}){0,7}$";
    static final String COLUMN_PATTERN = "^[A-Za-z_][A-Za-z0-9_]{0,63}$";
    static final int DEFAULT_MAX_KB = 1024;
    static final int MAX_KB = 8 * 1024;
    /** What the documents of a group or the whole input may hold together, while they are built. */
    static final long MAX_HELD_BYTES = 64L * 1024 * 1024;
    static final List<String> FROM = Arrays.asList("column", "value", "expression", "list", "rows");
    static final List<String> TYPES = Arrays.asList("auto", "text", "number", "boolean", "date");
    private static final Pattern FILE_NAME = Pattern.compile(SaveFileStepTask.FILE_NAME_TEMPLATE);

    static final TaskSpec SPEC = TaskSpec.builder(CODE, "Build JSON", TaskKind.PROCESS)
        .description("Shapes rows into nested JSON for an API or a client app: keys from columns, fixed values or formulas, "
            + "nested objects, and lists grouped from many rows -- one document per row, per group, or for the whole run.")
        .input(TaskSpec.rows("The rows to shape."))
        .output(TaskSpec.rows("The documents: in a column (with the row's or the group's columns), as columns of their own, or "
            + "kept as a JSON or JSON Lines file."))
        .config(JsonSchema.object()
            .property("mode", JsonSchema.string().enumOf("row", "group", "all").title("Make").defaultValue("row")
                .description("row: one document per row; group: one per group of rows (Group by); all: one document for the whole "
                    + "input. A group's keys come from its first row, its lists from every row."))
            .property("groupBy", JsonSchema.array(JsonSchema.string().minLength(1).maxLength(128).format("column"))
                .maxItems(10).title("Group by").description("group: the columns whose values make a group, e.g. patient_id."))
            .required("fields", JsonSchema.array(field(true)).minItems(1).maxItems(200).title("Keys")
                .description("The document's keys in order; a dot in a key nests an object: patient.id, patient.name."))
            .property("output", JsonSchema.string().enumOf("column", "rows", "file").title("Write it").defaultValue("column")
                .description("column: the document in one column (for Call API's body); rows: the document's own keys as the "
                    + "columns (the run's result as the shape); file: a JSON or JSON Lines file kept with the run."))
            .property("column", JsonSchema.string().pattern(COLUMN_PATTERN).title("Column").defaultValue("json")
                .description("column and file: the column the document is written to."))
            .property("fileName", JsonSchema.string().pattern(SaveFileStepTask.FILE_NAME_TEMPLATE).format("template").title("File name")
                .description("file: letters, digits, '.', '-' and '_'; the run's placeholders ({{date}}, {{run}}) are filled in."))
            .property("format", JsonSchema.string().enumOf("json", "jsonl").title("File format").defaultValue("json")
                .description("file: json is one document (all) or a list of them; jsonl one document per line."))
            .property("skipEmpty", JsonSchema.bool().title("Leave out empty keys").defaultValue(false)
                .description("A key with no value is left out of the document instead of being null."))
            .property("maxKb", JsonSchema.integer().minimum(1).maximum(MAX_KB).title("Largest document (KB)").defaultValue(DEFAULT_MAX_KB)
                .description("A bigger document fails the step, saying which.")))
        .backing(TaskSpec.CORE)
        .aiToolName("build_json")
        .items(ItemRole.SHARED).itemsWhen("mode", "group", "all")
        .build();

    /** One key of the shape; a list of rows' own keys never hold another list of rows. */
    private static JsonSchema field(boolean top) {
        JsonSchema field = JsonSchema.object()
            .required("key", JsonSchema.string().pattern(KEY_PATTERN).title("Key")
                .description("The key in the document; a dot nests: patient.id."))
            .property("from", JsonSchema.string().enumOf(top ? FROM.toArray(new String[0]) : new String[] {"column", "value", "expression"})
                .title("From").defaultValue("column")
                .description(top ? "column: a column's value; value: a fixed value; expression: a formula on the row (Compute's); list: "
                    + "a column's values over the rows; rows: a list of objects, one per row, of its own keys."
                    : "column: a column's value; value: a fixed value; expression: a formula on the row (Compute's)."))
            .property("column", JsonSchema.string().minLength(1).maxLength(128).format("column").title("Column"))
            .property("value", JsonSchema.scalar().title("Value"))
            .property("expression", JsonSchema.string().minLength(1).maxLength(1000).format("expression").title("Formula")
                .description("e.g. concat(first_name, ' ', last_name), or if(acuity >= 8, 'urgent', 'routine')."))
            .property("type", JsonSchema.string().enumOf(TYPES.toArray(new String[0])).title("Type").defaultValue("auto")
                .description("auto keeps the value (a JSON text becomes its list or object); text, number, boolean, or date (ISO)."));
        if (top) {
            field.property("distinct", JsonSchema.bool().title("Leave out repeats").defaultValue(false)
                .description("list and rows: a value (or an object) already in the list is not added again."))
                .property("fields", JsonSchema.array(field(false)).maxItems(100).title("Keys of each item")
                    .description("rows: each row's object, its keys as the document's."));
        }
        return field;
    }

    public BuildJsonStepTask() {
        super(SPEC);
    }

    // ------------------------------------------------------------------------------------------------------------- check

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        String mode = Configs.text(config, "mode", "row");
        if ("group".equals(mode) && Configs.texts(config, "groupBy").isEmpty()) {
            problems.add(new DefinitionProblem("groupBy", "One document per group needs the columns that make a group"));
        }
        String output = Configs.text(config, "output", "column");
        if ("file".equals(output)) {
            String name = Configs.text(config, "fileName", "");
            if (name.isEmpty()) {
                problems.add(new DefinitionProblem("fileName", "A file needs its name, e.g. referrals-{{date}}.json"));
            } else if (!FILE_NAME.matcher(name).matches()) {
                problems.add(new DefinitionProblem("fileName", "letters, digits, '.', '-' and '_', at most 128"));
            }
        }
        checkFields(Configs.objects(config, "fields"), "fields", true, problems);
        return problems;
    }

    private static void checkFields(List<Map<String, Object>> fields, String at, boolean top, List<DefinitionProblem> problems) {
        Map<String, Boolean> paths = new LinkedHashMap<>();
        for (int i = 0; i < fields.size(); i++) {
            Map<String, Object> field = fields.get(i);
            String where = at + "[" + i + "]";
            String key = Configs.text(field, "key", "");
            String clash = clash(paths, key);
            if (clash != null) {
                problems.add(new DefinitionProblem(where + ".key", clash));
            }
            String from = Configs.text(field, "from", "column");
            boolean needsColumn = "column".equals(from) || "list".equals(from);
            if (needsColumn && Configs.text(field, "column", "").isEmpty()) {
                problems.add(new DefinitionProblem(where + ".column", String.format("'%s' takes a column's value: name the column", key)));
            }
            if ("value".equals(from) && !field.containsKey("value")) {
                problems.add(new DefinitionProblem(where + ".value", String.format("'%s' is a fixed value: give it (null for none)", key)));
            }
            if ("expression".equals(from)) {
                try {
                    Expression.parse(Configs.text(field, "expression", ""));
                } catch (IllegalArgumentException bad) {
                    problems.add(new DefinitionProblem(where + ".expression", bad.getMessage()));
                }
            }
            if ("rows".equals(from)) {
                List<Map<String, Object>> inner = Configs.objects(field, "fields");
                if (inner.isEmpty()) {
                    problems.add(new DefinitionProblem(where + ".fields", String.format("'%s' is a list of objects: give each object's keys", key)));
                }
                checkFields(inner, where + ".fields", false, problems);
            } else if (!top && ("list".equals(from) || "rows".equals(from))) {
                problems.add(new DefinitionProblem(where + ".from", "an item of a list holds columns, values and formulas, not another list"));
            }
        }
    }

    /** Why a key cannot go beside the ones before it (named twice, or a value where another key nests); null when it can. */
    static String clash(Map<String, Boolean> paths, String key) {
        if (key.isEmpty()) {
            return null;
        }
        if (Boolean.TRUE.equals(paths.get(key))) {
            return String.format("'%s' is named twice", key);
        }
        String[] parts = key.split("\\.", -1);
        StringBuilder prefix = new StringBuilder();
        for (int p = 0; p < parts.length - 1; p++) {
            prefix.append(p == 0 ? "" : ".").append(parts[p]);
            if (Boolean.TRUE.equals(paths.get(prefix.toString()))) {
                return String.format("'%s' is a value, so '%s' cannot nest in it", prefix, key);
            }
        }
        for (String other : paths.keySet()) {
            if (other.startsWith(key + ".")) {
                return String.format("'%s' already holds '%s', so it cannot be a value too", key, other);
            }
        }
        paths.put(key, Boolean.TRUE);
        for (int p = 0, n = 0; p < parts.length - 1; p++) {
            n += parts[p].length() + (p == 0 ? 0 : 1);
            paths.putIfAbsent(key.substring(0, n), Boolean.FALSE);
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------------------- run

    @Override
    public StepResult stream(StreamContext context) throws Exception {
        Shape shape = Shape.of(context.config());
        String output = Configs.text(context.config(), "output", "column");
        String column = Configs.text(context.config(), "column", "json");
        String mode = shape.mode;
        Documents documents = new Documents(shape);
        RowSink out = context.output();
        long rows = 0;
        List<String> inputColumns;
        try (RowSource input = context.openInput()) {
            inputColumns = new ArrayList<>(input.columns());
            if ("group".equals(mode)) {
                for (String by : shape.groupBy) {
                    if (!inputColumns.contains(by)) {
                        throw new IllegalStateException(String.format("The rows have no column '%s' to group by.", by));
                    }
                }
            }
            List<String> columns = this.columns(output, mode, column, inputColumns, shape);
            Limits.requireShape(0, columns.size(), "The documents' rows");
            out.declare(columns);
            for (List<Map<String, Object>> batch = input.next(); batch != null; batch = input.next()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Build JSON was stopped.");
                }
                for (Map<String, Object> row : batch) {
                    rows++;
                    if ("row".equals(mode)) {
                        Document doc = new Document(shape, row, "row " + rows);
                        doc.add(row, rows, documents.empty, null);
                        Map<String, Object> made = documents.finish(doc);
                        if (!"file".equals(output)) {
                            out.add(this.rowOf(output, column, row, made));
                        } else {
                            documents.keep(row, made);
                            out.add(this.rowOf("column", column, row, made));
                        }
                    } else {
                        documents.add(row, rows);
                    }
                }
            }
        }
        if (!"row".equals(mode)) {
            if ("all".equals(mode) && documents.held.isEmpty()) {
                // One document for the run, though there were no rows: its keys are empty and its lists are [].
                documents.held.put("", new Document(shape, new LinkedHashMap<>(), "the input"));
            }
            for (Document doc : documents.held.values()) {
                Map<String, Object> made = documents.finish(doc);
                Map<String, Object> keys = "group".equals(mode) ? doc.groupValues() : new LinkedHashMap<>();
                documents.keep(keys, made);
                out.add(this.rowOf("file".equals(output) ? "column" : output, column, keys, made));
            }
        }
        if ("file".equals(output)) {
            this.writeFile(context, documents, mode);
        }
        String note = documents.count == 1 ? "1 document" : String.format("%,d documents", documents.count);
        context.log(String.format("%s from %,d row(s) (%s).", capitalised(note), rows, modeWords(mode)));
        documents.warnEmpty(context);
        return StepResult.streamed(out.size()).withNote(note);
    }

    private List<String> columns(String output, String mode, String column, List<String> inputColumns, Shape shape) {
        if ("rows".equals(output)) {
            List<String> top = new ArrayList<>();
            for (Spec spec : shape.fields) {
                String first = spec.path[0];
                if (!top.contains(first)) {
                    top.add(first);
                }
            }
            return top;
        }
        List<String> columns = "row".equals(mode) ? new ArrayList<>(inputColumns)
            : "group".equals(mode) ? new ArrayList<>(shape.groupBy) : new ArrayList<>();
        columns.remove(column);
        columns.add(column);
        return columns;
    }

    private Map<String, Object> rowOf(String output, String column, Map<String, Object> beside, Map<String, Object> made) {
        if ("rows".equals(output)) {
            return made;
        }
        Map<String, Object> row = new LinkedHashMap<>(beside);
        row.remove(column);
        row.put(column, made);
        return row;
    }

    private void writeFile(StreamContext context, Documents documents, String mode) throws Exception {
        String format = Configs.text(context.config(), "format", "json");
        String template = Configs.text(context.config(), "fileName", "documents.json");
        Map<String, Object> runValues = Templates.ofRun(context, (int) Math.min(Integer.MAX_VALUE, documents.count));
        String fileName = SaveFileStepTask.named(Templates.fill(template, runValues));
        boolean single = "all".equals(mode) && "json".equals(format);
        StreamContext.KeptFile kept = context.keepFile(fileName, documents.fileColumns(), documents.count, (OutputStream to) -> {
            if (single) {
                to.write(Values.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(documents.kept.get(0)));
                to.write('\n');
            } else if ("json".equals(format)) {
                to.write(Values.JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(documents.kept));
                to.write('\n');
            } else {
                for (Map<String, Object> doc : documents.kept) {
                    to.write(Values.JSON.writeValueAsBytes(doc));
                    to.write('\n');
                }
            }
        });
        context.recordOutput(RunOutput.file(fileName, format, documents.count, kept.bytes).withSha256(kept.sha256));
        context.log(String.format("Saved %,d document(s) as %s (%s, %,d bytes).", documents.count, fileName, format, kept.bytes));
    }

    private static String modeWords(String mode) {
        return "group".equals(mode) ? "one per group" : "all".equals(mode) ? "one for the input" : "one per row";
    }

    private static String capitalised(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // ------------------------------------------------------------------------------------------------------------- shape

    /** The shape as the step reads it: its keys parsed once. */
    static final class Shape {
        final String mode;
        final List<String> groupBy;
        final List<Spec> fields;
        final boolean skipEmpty;
        final long maxBytes;

        private Shape(String mode, List<String> groupBy, List<Spec> fields, boolean skipEmpty, long maxBytes) {
            this.mode = mode;
            this.groupBy = groupBy;
            this.fields = fields;
            this.skipEmpty = skipEmpty;
            this.maxBytes = maxBytes;
        }

        static Shape of(Map<String, Object> config) {
            int kb = Math.max(1, Math.min(MAX_KB, Configs.integer(config, "maxKb", DEFAULT_MAX_KB)));
            return new Shape(Configs.text(config, "mode", "row"), Configs.texts(config, "groupBy"), Spec.all(Configs.objects(config, "fields")),
                Configs.bool(config, "skipEmpty", false), kb * 1024L);
        }
    }

    /** One key: where it goes, and what fills it. */
    static final class Spec {
        final String key;
        final String[] path;
        final String from;
        final String column;
        final Object value;
        final Expression expression;
        final String type;
        final boolean distinct;
        final List<Spec> fields;

        private Spec(Map<String, Object> field) {
            this.key = Configs.text(field, "key", "");
            this.path = this.key.split("\\.", -1);
            this.from = Configs.text(field, "from", "column");
            this.column = Configs.text(field, "column", null);
            this.value = field.get("value");
            this.expression = "expression".equals(this.from) ? Expression.parse(Configs.text(field, "expression", "")) : null;
            this.type = Configs.text(field, "type", "auto");
            this.distinct = Configs.bool(field, "distinct", false);
            this.fields = "rows".equals(this.from) ? all(Configs.objects(field, "fields")) : new ArrayList<>();
        }

        static List<Spec> all(List<Map<String, Object>> fields) {
            List<Spec> specs = new ArrayList<>();
            for (Map<String, Object> field : fields) {
                specs.add(new Spec(field));
            }
            return specs;
        }

        boolean isList() {
            return "list".equals(this.from) || "rows".equals(this.from);
        }

        /** This key's value on a row: its column's, its fixed value, or its formula's, as its type says. */
        Object valueOn(Map<String, Object> row, long rowNumber, Empty empty) {
            Object raw;
            if ("value".equals(this.from)) {
                raw = this.value;
            } else if ("expression".equals(this.from)) {
                try {
                    raw = this.expression.evaluate(row);
                } catch (IllegalArgumentException cannot) {
                    throw new IllegalStateException(String.format("Row %d, %s: %s", rowNumber, this.key, cannot.getMessage()));
                }
            } else {
                raw = row.get(this.column);
            }
            return typed(raw, this.type, this.key, empty);
        }
    }

    /** What a key's type makes of a value: null for empty, and a value that is not of the type null too (counted). */
    static Object typed(Object raw, String type, String key, Empty empty) {
        if (raw == null || (raw instanceof String && ((String) raw).trim().isEmpty() && !"text".equals(type))) {
            return null;
        }
        switch (type) {
            case "text":
                return raw instanceof Map || raw instanceof Collection || raw instanceof JsonNode ? json(raw) : Values.text(raw);
            case "number": {
                BigDecimal number = Values.number(raw);
                if (number == null) {
                    empty.notOfType(key, "a number");
                }
                return number == null ? null : Values.plain(number);
            }
            case "boolean": {
                Boolean bool = bool(raw);
                if (bool == null) {
                    empty.notOfType(key, "true or false");
                }
                return bool;
            }
            case "date": {
                String date = isoDate(raw);
                if (date == null) {
                    empty.notOfType(key, "a date");
                }
                return date;
            }
            default:
                return plain(raw);
        }
    }

    /** Auto: a value as it is, a JSON text of a list or an object as that list or object (maps and lists, not nodes). */
    static Object plain(Object raw) {
        Object structured = raw instanceof JsonNode ? raw : Values.structured(raw);
        if (structured instanceof JsonNode) {
            return Values.JSON.convertValue(structured, Object.class);
        }
        return raw;
    }

    private static String json(Object value) {
        try {
            return Values.JSON.writeValueAsString(value);
        } catch (Exception unwritable) {
            return String.valueOf(value);
        }
    }

    static Boolean bool(Object raw) {
        if (raw instanceof Boolean) {
            return (Boolean) raw;
        }
        BigDecimal number = raw instanceof Number ? Values.number(raw) : null;
        if (number != null) {
            return number.signum() == 0 ? Boolean.FALSE : number.compareTo(BigDecimal.ONE) == 0 ? Boolean.TRUE : null;
        }
        switch (String.valueOf(raw).trim().toLowerCase(Locale.ROOT)) {
            case "true": case "yes": case "y": case "1":
                return Boolean.TRUE;
            case "false": case "no": case "n": case "0":
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    private static final DateTimeFormatter US_DATE = DateTimeFormatter.ofPattern("M/d/yyyy");

    /** A date as ISO: 2026-11-02, a time 2026-11-02T14:30:00 (an offset kept); also 11/2/2026. Null when it is no date. */
    static String isoDate(Object raw) {
        String text = String.valueOf(raw).trim();
        try {
            if (text.length() == 10 && text.charAt(4) == '-') {
                return LocalDate.parse(text).toString();
            }
            if (text.length() > 10 && text.charAt(4) == '-') {
                String t = text.replace(' ', 'T');
                if (t.endsWith("Z") || t.matches(".*[+-]\\d\\d:?\\d\\d$")) {
                    return OffsetDateTime.parse(t.endsWith("Z") ? t : t.replaceAll("([+-]\\d\\d)(\\d\\d)$", "$1:$2"))
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                }
                return LocalDateTime.parse(t).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
            if (text.matches("\\d{1,2}/\\d{1,2}/\\d{4}")) {
                return LocalDate.parse(text, US_DATE).toString();
            }
        } catch (DateTimeParseException notADate) {
            return null;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------------------- build

    /** How many documents each key was empty in, and values that were not of their key's type. */
    static final class Empty {
        final Map<String, Long> empty = new LinkedHashMap<>();
        final Map<String, String> wrongType = new LinkedHashMap<>();
        final Map<String, Long> wrongCount = new LinkedHashMap<>();

        void notOfType(String key, String what) {
            this.wrongType.putIfAbsent(key, what);
            this.wrongCount.merge(key, 1L, Long::sum);
        }
    }

    /** One document as it is built: its keys from its first row, its lists from every row. */
    static final class Document {
        final Shape shape;
        final Map<String, Object> first;
        final String label;
        final Map<String, Object> values = new LinkedHashMap<>();
        final Map<String, List<Object>> lists = new LinkedHashMap<>();
        final Map<String, Set<String>> seen = new LinkedHashMap<>();
        long bytes = 2;
        boolean started;

        Document(Shape shape, Map<String, Object> first, String label) {
            this.shape = shape;
            this.first = first;
            this.label = label;
            for (Spec spec : shape.fields) {
                if (spec.isList()) {
                    this.lists.put(spec.key, new ArrayList<>());
                }
            }
        }

        Map<String, Object> groupValues() {
            Map<String, Object> keys = new LinkedHashMap<>();
            for (String by : this.shape.groupBy) {
                keys.put(by, this.first.get(by));
            }
            return keys;
        }

        /** One more row of the document: its first fills the keys, every one the lists. */
        void add(Map<String, Object> row, long rowNumber, Empty empty, Documents all) {
            if (!this.started) {
                this.started = true;
                for (Spec spec : this.shape.fields) {
                    if (!spec.isList()) {
                        Object v = spec.valueOn(row, rowNumber, empty);
                        this.values.put(spec.key, v);
                        this.grow(spec.key, v, all);
                    }
                }
            }
            for (Spec spec : this.shape.fields) {
                if ("list".equals(spec.from)) {
                    Object v = spec.valueOn(row, rowNumber, empty);
                    if (v instanceof List) {
                        for (Object each : (List<?>) v) {
                            this.append(spec, each, all);
                        }
                    } else if (v != null) {
                        this.append(spec, v, all);
                    }
                } else if ("rows".equals(spec.from)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    for (Spec inner : spec.fields) {
                        Object v = inner.valueOn(row, rowNumber, empty);
                        if (v == null && this.shape.skipEmpty) {
                            continue;
                        }
                        put(item, inner.path, v);
                    }
                    this.append(spec, item, all);
                }
            }
        }

        private void append(Spec spec, Object value, Documents all) {
            if (spec.distinct && !this.seen.computeIfAbsent(spec.key, k -> new HashSet<>()).add(json(value))) {
                return;
            }
            this.lists.get(spec.key).add(value);
            this.grow(spec.key, value, all);
        }

        private void grow(String key, Object value, Documents all) {
            long n = json(value).getBytes(StandardCharsets.UTF_8).length + key.length() + 4L;
            this.bytes += n;
            if (all != null) {
                all.grew(this, n);
            }
            if (this.bytes > this.shape.maxBytes) {
                throw tooBig(this.label, this.bytes, this.shape.maxBytes);
            }
        }

        /** The document, its keys in the shape's order, nested by their dots. */
        Map<String, Object> make(Empty empty) {
            Map<String, Object> doc = new LinkedHashMap<>();
            for (Spec spec : this.shape.fields) {
                Object v = spec.isList() ? this.lists.get(spec.key) : this.values.get(spec.key);
                if (v == null) {
                    empty.empty.merge(spec.key, 1L, Long::sum);
                    if (this.shape.skipEmpty) {
                        continue;
                    }
                }
                put(doc, spec.path, v);
            }
            return doc;
        }
    }

    static IllegalStateException tooBig(String label, long bytes, long max) {
        return new IllegalStateException(String.format("The document for %s is %s; a document is at most %s (Largest document). "
            + "Leave out keys, make its lists smaller, or raise Largest document.", label, size(bytes), size(max)));
    }

    static String size(long bytes) {
        return bytes >= 1024 * 1024 ? String.format("%.1f MB", bytes / 1048576.0) : String.format("%,d KB", Math.max(1, (bytes + 1023) / 1024));
    }

    /** Puts a value at a dot path, making the objects on the way. */
    @SuppressWarnings("unchecked")
    static void put(Map<String, Object> doc, String[] path, Object value) {
        Map<String, Object> at = doc;
        for (int p = 0; p < path.length - 1; p++) {
            Object next = at.get(path[p]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                at.put(path[p], next);
            }
            at = (Map<String, Object>) next;
        }
        at.put(path[path.length - 1], value);
    }

    /** The run's documents: a group's held until the input ends, every one counted and its empty keys said. */
    static final class Documents {
        final Shape shape;
        final Empty empty = new Empty();
        final Map<String, Document> held = new LinkedHashMap<>();
        final List<Map<String, Object>> kept = new ArrayList<>();
        final Set<String> keptColumns = new LinkedHashSet<>();
        long count;
        long heldBytes;
        long keptBytes;

        Documents(Shape shape) {
            this.shape = shape;
        }

        void add(Map<String, Object> row, long rowNumber) {
            String key = "";
            String label = "the input";
            if ("group".equals(this.shape.mode)) {
                StringBuilder k = new StringBuilder();
                StringBuilder l = new StringBuilder();
                for (String by : this.shape.groupBy) {
                    k.append(Values.key(row.get(by))).append('\u0000');
                    l.append(l.length() == 0 ? "" : ", ").append(by).append(' ').append(Values.text(row.get(by)));
                }
                key = k.toString();
                label = l.toString();
            }
            Document doc = this.held.get(key);
            if (doc == null) {
                long most = Math.min(Limits.MAX_ROWS, Limits.MAX_STREAM_ROWS);
                if (this.held.size() >= most) {
                    throw new IllegalStateException(String.format("More than %,d groups, the most a step holds.", most));
                }
                doc = new Document(this.shape, row, label);
                this.held.put(key, doc);
            }
            doc.add(row, rowNumber, this.empty, this);
        }

        void grew(Document doc, long n) {
            this.heldBytes += n;
            if (this.heldBytes > MAX_HELD_BYTES) {
                throw new IllegalStateException(String.format("The documents come to more than %s while they are built; make fewer "
                    + "groups or smaller lists.", size(MAX_HELD_BYTES)));
            }
        }

        Map<String, Object> finish(Document doc) {
            Map<String, Object> made = doc.make(this.empty);
            long bytes = json(made).getBytes(StandardCharsets.UTF_8).length;
            if (bytes > this.shape.maxBytes) {
                throw tooBig(doc.label, bytes, this.shape.maxBytes);
            }
            this.count++;
            return made;
        }

        /** A document for the file: kept until it is written, within what a step holds. */
        void keep(Map<String, Object> beside, Map<String, Object> made) {
            this.keptBytes += json(made).length();
            if (this.keptBytes > MAX_HELD_BYTES) {
                throw new IllegalStateException(String.format("The file's documents come to more than %s; make one per group, or "
                    + "write a column and save it with Save file.", size(MAX_HELD_BYTES)));
            }
            this.kept.add(made);
            this.keptColumns.addAll(made.keySet());
        }

        List<String> fileColumns() {
            return new ArrayList<>(this.keptColumns);
        }

        void warnEmpty(StreamContext context) {
            if (this.count == 0) {
                return;
            }
            List<String> said = new ArrayList<>();
            Map<String, Long> inOrder = new LinkedHashMap<>();
            for (Spec spec : this.shape.fields) {
                if (this.empty.empty.containsKey(spec.key)) {
                    inOrder.put(spec.key, this.empty.empty.get(spec.key));
                }
            }
            inOrder.forEach((key, n) -> said.add(String.format("%s in %s", key, n == this.count && n > 1 ? "every document"
                : n == 1 ? "1 document" : String.format("%,d documents", n))));
            if (!said.isEmpty()) {
                context.warn(String.format("No value for %s%s.", String.join("; ", said.subList(0, Math.min(10, said.size()))),
                    said.size() > 10 ? String.format("; and %d more key(s)", said.size() - 10) : ""));
            }
            this.empty.wrongType.forEach((key, what) -> context.warn(String.format("%s: %s value(s) were not %s and are empty.", key,
                String.format("%,d", this.empty.wrongCount.get(key)), what)));
        }
    }
}

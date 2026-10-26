package process.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import process.pipeline.data.RowSource;
import process.pipeline.data.Values;
import process.pipeline.items.ItemMasks;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MIG-424: a finished run's answer, for whoever started it to use -- a workflow's run_pipeline step (saved on the request's
 * subject for its later conditions), a pipeline's Run pipeline step (taken as rows), the customer API's run-and-wait.
 *
 * <ul>
 *   <li><b>From</b>: the run's final output -- the last step of its latest attempt that kept one -- or a named step's
 *   ({@code from: "<stepKey>"}). A step reused by a resume answers with the step whose output it reused.</li>
 *   <li><b>Fields</b>: chosen columns ({@code path} a column, or a column holding JSON and a dot path into it:
 *   {@code summary.risk}), each under its {@code as}; none chosen, every column (at most {@value #MAX_FIELDS}). A column of
 *   JSON text is given as that JSON.</li>
 *   <li><b>Take</b>: {@code first} (the first row's fields), {@code all} (up to maxRows rows), {@code count}, or an aggregate
 *   of each field over the rows ({@code sum}, {@code min}, {@code max}, {@code avg}; at most {@value #MAX_SCAN} rows read).</li>
 *   <li><b>Capped and masked</b>: the rows are masked as the reader's masks say before anything is taken from them, and the
 *   answer is at most {@value #MAX_JSON_CHARS} characters of JSON -- rows beyond it are dropped, a value too big alone is
 *   left out -- and says so ({@code truncated}).</li>
 * </ul>
 * Nothing here checks who may read the run: the caller has (the run is its workspace's, row security holds).
 */
@Component
public class RunResults {

    public static final String FINAL = "final";
    public static final List<String> TAKES = Collections.unmodifiableList(Arrays.asList("first", "all", "count", "sum", "min", "max", "avg"));
    public static final int MAX_FIELDS = 50;
    public static final int DEFAULT_ROWS = 20;
    public static final int MAX_ROWS = 100;
    public static final int MAX_SCAN = 10_000;
    public static final int MAX_JSON_CHARS = 16_384;
    /** A JSON text value bigger than this is kept as text, not read into JSON. */
    static final int MAX_JSON_VALUE_CHARS = 8_192;
    public static final Pattern NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");
    public static final Pattern PATH = Pattern.compile("^[A-Za-z_][A-Za-z0-9_ -]{0,127}(\\.[A-Za-z0-9_-]{1,64}){0,5}$");

    private static final ObjectMapper JSON = Values.JSON;

    private final StepStore steps;
    private final DatasetStore datasets;

    public RunResults(StepStore steps, DatasetStore datasets) {
        this.steps = steps;
        this.datasets = datasets;
    }

    /** One chosen field: where it is read and the name it is given. */
    public static final class Field {
        public final String path;
        public final String as;

        public Field(String path, String as) {
            this.path = path;
            this.as = as == null || as.trim().isEmpty() ? defaultName(path) : as.trim();
        }

        /** The column itself, before any dot into its JSON. */
        String column(Set<String> columns) {
            if (columns.contains(this.path)) {
                return this.path;
            }
            int dot = this.path.indexOf('.');
            return dot < 0 ? this.path : this.path.substring(0, dot);
        }
    }

    /** What to take from a run. */
    public static final class Selection {
        public String from = FINAL;
        public String take = "first";
        public List<Field> fields = new ArrayList<>();
        public int maxRows = DEFAULT_ROWS;

        /** The selection a caller sent ({from, take, fields: ["risk", {path, as}], maxRows}), or the problems with it. */
        public static Selection of(JsonNode node, List<String> problems) {
            Selection s = new Selection();
            if (node == null || node.isNull() || node.isMissingNode()) {
                return s;
            }
            if (!node.isObject()) {
                problems.add("results: an object {from, take, fields, maxRows}");
                return s;
            }
            if (node.hasNonNull("from")) {
                String from = node.path("from").asText("").trim();
                if (from.isEmpty() || from.length() > 128) {
                    problems.add("results.from: final, or a step's key");
                } else {
                    s.from = from;
                }
            }
            if (node.hasNonNull("take")) {
                String take = node.path("take").asText("").trim().toLowerCase(Locale.ROOT);
                if (!TAKES.contains(take)) {
                    problems.add("results.take: one of " + String.join(", ", TAKES));
                } else {
                    s.take = take;
                }
            }
            if (node.hasNonNull("maxRows")) {
                JsonNode max = node.get("maxRows");
                if (!max.canConvertToInt() || max.asInt() < 1 || max.asInt() > MAX_ROWS) {
                    problems.add("results.maxRows: 1 to " + MAX_ROWS);
                } else {
                    s.maxRows = max.asInt();
                }
            }
            JsonNode fields = node.path("fields");
            if (!fields.isMissingNode() && !fields.isNull()) {
                if (!fields.isArray() || fields.size() > MAX_FIELDS) {
                    problems.add("results.fields: a list of at most " + MAX_FIELDS + " fields");
                } else {
                    Set<String> names = new LinkedHashSet<>();
                    for (int i = 0; i < fields.size(); i++) {
                        JsonNode f = fields.get(i);
                        String path = f.isTextual() ? f.asText().trim() : f.path("path").asText("").trim();
                        String as = f.isObject() && f.hasNonNull("as") ? f.path("as").asText("").trim() : null;
                        if (!PATH.matcher(path).matches()) {
                            problems.add("results.fields[" + i + "]: a column, or a column and a dot path into its JSON (summary.risk)");
                            continue;
                        }
                        Field field = new Field(path, as);
                        if (!NAME.matcher(field.as).matches()) {
                            problems.add("results.fields[" + i + "].as: letters, digits and _, starting with a letter");
                        } else if (!names.add(field.as)) {
                            problems.add("results.fields[" + i + "].as: " + field.as + " is named twice");
                        } else {
                            s.fields.add(field);
                        }
                    }
                }
            }
            return s;
        }

        /** The selection as JSON, as {@link #of} reads it. */
        public Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("from", this.from);
            out.put("take", this.take);
            List<Object> list = new ArrayList<>();
            for (Field f : this.fields) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("path", f.path);
                one.put("as", f.as);
                list.add(one);
            }
            out.put("fields", list);
            out.put("maxRows", this.maxRows);
            return out;
        }
    }

    /** What a run answered: its values (take first or an aggregate), its rows (take all), and how they were cut. */
    public static final class Result {
        /** Why there is no answer (the step kept no output, its output is past its retention); null when there is one. */
        public String problem;
        public String step;
        public List<String> columns = new ArrayList<>();
        public long rowCount;
        /** take first, count or an aggregate: field -> value. */
        public Map<String, Object> values = new LinkedHashMap<>();
        /** take all: the rows' fields. */
        public List<Map<String, Object>> rows = new ArrayList<>();
        public boolean truncated;
        public Set<String> masked = new LinkedHashSet<>();

        /** The answer as JSON-ready values: {step, rowCount, values | rows, truncated, masked, problem}. */
        public Map<String, Object> toMap(String take) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("step", this.step);
            out.put("rowCount", this.rowCount);
            if ("all".equals(take)) {
                out.put("rows", this.rows);
            } else {
                out.put("values", this.values);
            }
            out.put("truncated", this.truncated);
            out.put("masked", new ArrayList<>(this.masked));
            if (this.problem != null) {
                out.put("problem", this.problem);
            }
            return out;
        }
    }

    /**
     * The run's answer as {@code selection} asks, its rows masked by {@code masks} (null: none -- the run's own workspace
     * reading on, as a pipeline step does).
     */
    public Result read(long jobQueueId, Selection selection, ItemMasks masks) throws Exception {
        Result result = new Result();
        Optional<StepStore.StepRow> chosen = this.stepOf(jobQueueId, selection.from, result);
        if (!chosen.isPresent()) {
            return result;
        }
        StepStore.StepRow step = chosen.get();
        result.step = step.stepKey;
        long source = step.reusedFrom != null ? step.reusedFrom : step.stepExecutionId;
        StepStore.KeptOutput kept = this.steps.keptOutputsOf(Collections.singletonList(source)).get(source);
        if (kept == null) {
            result.problem = "The step " + step.stepKey + " kept no output of its own.";
            return result;
        }
        if (kept.expiredAt(Instant.now())) {
            result.problem = "The run's output is past its retention.";
            return result;
        }
        int wanted = "all".equals(selection.take) ? selection.maxRows : "first".equals(selection.take) ? 1 : MAX_SCAN;
        List<Map<String, Object>> rows = new ArrayList<>();
        try (RowSource rowSource = this.datasets.open(kept.storageKey, Math.min(RowSource.DEFAULT_BATCH, Math.max(1, wanted)))) {
            result.columns = new ArrayList<>(rowSource.columns());
            result.rowCount = rowSource.size();
            List<Map<String, Object>> batch;
            while (rows.size() < wanted && (batch = rowSource.next()) != null) {
                for (Map<String, Object> row : batch) {
                    if (rows.size() >= wanted) {
                        break;
                    }
                    rows.add(row);
                }
            }
        }
        // take all: more rows than it gives; an aggregate: more rows than it reads. first and count are whole as they are.
        result.truncated = !"first".equals(selection.take) && !"count".equals(selection.take) && result.rowCount > rows.size();
        if (masks != null && masks.any()) {
            rows = masks.rows(rows, result.columns, result.masked);
        }
        List<Field> fields = selection.fields.isEmpty() ? everyColumn(result.columns) : selection.fields;
        Set<String> columns = new LinkedHashSet<>(result.columns);
        switch (selection.take) {
            case "all":
                for (Map<String, Object> row : rows) {
                    result.rows.add(fieldsOf(row, fields, columns));
                }
                break;
            case "count":
                result.values.put("count", result.rowCount);
                break;
            case "first":
                Map<String, Object> first = rows.isEmpty() ? null : fieldsOf(rows.get(0), fields, columns);
                for (Field f : fields) {
                    result.values.put(f.as, first == null ? null : first.get(f.as));
                }
                break;
            default:
                for (Field f : fields) {
                    result.values.put(f.as, aggregate(selection.take, rows, f, columns));
                }
                break;
        }
        cap(result, selection.take);
        return result;
    }

    /**
     * A finished run's rows as a pipeline takes them (a Run pipeline step): the chosen step's output, its fields as chosen (all
     * its columns when none are), at most {@code maxRows} rows -- never masked (the rows stay in the workspace's own pipeline)
     * and never cut to a size but the step's row limit. An empty dataset when the run kept no output; {@code problem} on
     * the result says why.
     */
    public Dataset rows(long jobQueueId, Selection selection, int maxRows, Result about) throws Exception {
        Optional<StepStore.StepRow> chosen = this.stepOf(jobQueueId, selection.from, about);
        if (!chosen.isPresent()) {
            return Dataset.EMPTY;
        }
        StepStore.StepRow step = chosen.get();
        about.step = step.stepKey;
        long source = step.reusedFrom != null ? step.reusedFrom : step.stepExecutionId;
        StepStore.KeptOutput kept = this.steps.keptOutputsOf(Collections.singletonList(source)).get(source);
        if (kept == null || kept.expiredAt(Instant.now())) {
            about.problem = kept == null ? "The step " + step.stepKey + " kept no output of its own." : "The run's output is past its retention.";
            return Dataset.EMPTY;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> columns;
        try (RowSource rowSource = this.datasets.open(kept.storageKey, RowSource.DEFAULT_BATCH)) {
            columns = new ArrayList<>(rowSource.columns());
            about.rowCount = rowSource.size();
            List<Map<String, Object>> batch;
            while (rows.size() < maxRows && (batch = rowSource.next()) != null) {
                for (Map<String, Object> row : batch) {
                    if (rows.size() >= maxRows) {
                        break;
                    }
                    rows.add(row);
                }
            }
        }
        about.columns = columns;
        about.truncated = about.rowCount > rows.size();
        if (selection.fields.isEmpty()) {
            return new Dataset(columns, rows);
        }
        Set<String> present = new LinkedHashSet<>(columns);
        List<String> named = new ArrayList<>();
        for (Field f : selection.fields) {
            named.add(f.as);
        }
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> picked = new LinkedHashMap<>();
            for (Field f : selection.fields) {
                Object value = valueOf(row, f, present);
                picked.put(f.as, value instanceof Map || value instanceof List ? JSON.writeValueAsString(value) : value);
            }
            out.add(picked);
        }
        return new Dataset(named, out);
    }

    /** The step whose output is read: the named one of the latest attempt, or the last one that kept an output. */
    private Optional<StepStore.StepRow> stepOf(long jobQueueId, String from, Result result) {
        List<StepStore.StepRow> all = this.steps.stepsOfRun(jobQueueId);
        int attempt = 0;
        for (StepStore.StepRow row : all) {
            attempt = Math.max(attempt, row.attempt);
        }
        List<StepStore.StepRow> latest = new ArrayList<>();
        for (StepStore.StepRow row : all) {
            if (row.attempt == attempt) {
                latest.add(row);
            }
        }
        latest.sort((a, b) -> Integer.compare(a.stepIndex, b.stepIndex));
        if (latest.isEmpty()) {
            result.problem = "The run has no steps of its own to answer with.";
            return Optional.empty();
        }
        if (!FINAL.equals(from)) {
            for (StepStore.StepRow row : latest) {
                if (from.equals(row.stepKey)) {
                    if (!"Completed".equalsIgnoreCase(row.status)) {
                        result.problem = "The step " + from + " did not complete (" + row.status + ").";
                        return Optional.empty();
                    }
                    return Optional.of(row);
                }
            }
            result.problem = "The run has no step " + from + ".";
            return Optional.empty();
        }
        List<Long> ids = new ArrayList<>();
        for (StepStore.StepRow row : latest) {
            ids.add(row.reusedFrom != null ? row.reusedFrom : row.stepExecutionId);
        }
        Map<Long, StepStore.KeptOutput> kept = this.steps.keptOutputsOf(ids);
        for (int i = latest.size() - 1; i >= 0; i--) {
            StepStore.StepRow row = latest.get(i);
            long source = row.reusedFrom != null ? row.reusedFrom : row.stepExecutionId;
            if ("Completed".equalsIgnoreCase(row.status) && kept.containsKey(source)) {
                return Optional.of(row);
            }
        }
        result.problem = "No step of the run kept an output.";
        return Optional.empty();
    }

    private static List<Field> everyColumn(List<String> columns) {
        List<Field> out = new ArrayList<>();
        for (String column : columns) {
            if (out.size() >= MAX_FIELDS) {
                break;
            }
            out.add(new Field(column, NAME.matcher(column).matches() ? column : defaultName(column)));
        }
        return out;
    }

    /** A field's name when none is given: its path's last part, as letters, digits and _. */
    static String defaultName(String path) {
        String last = path == null ? "" : path.substring(path.lastIndexOf('.') + 1);
        String name = last.replaceAll("[^A-Za-z0-9_]", "_");
        if (name.isEmpty() || !Character.isLetter(name.charAt(0)) && name.charAt(0) != '_') {
            name = "f_" + name;
        }
        return name.length() > 64 ? name.substring(0, 64) : name;
    }

    /** One row's chosen fields, a JSON text cell read as JSON. */
    static Map<String, Object> fieldsOf(Map<String, Object> row, List<Field> fields, Set<String> columns) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field f : fields) {
            out.put(f.as, valueOf(row, f, columns));
        }
        return out;
    }

    static Object valueOf(Map<String, Object> row, Field field, Set<String> columns) {
        String column = field.column(columns);
        Object cell = row.get(column);
        Object value = structured(cell);
        if (column.equals(field.path)) {
            return value;
        }
        JsonNode node = JSON.valueToTree(value);
        JsonNode found = Values.at(node, field.path.substring(column.length() + 1));
        return found == null || found.isMissingNode() || found.isNull() ? null : found.isValueNode() ? Values.scalar(found)
            : JSON.convertValue(found, Object.class);
    }

    /** JSON text of an object or a list as that object or list; anything else as it is. */
    static Object structured(Object cell) {
        if (!(cell instanceof String)) {
            return cell;
        }
        String text = ((String) cell).trim();
        if (text.length() > MAX_JSON_VALUE_CHARS || !(text.startsWith("{") && text.endsWith("}") || text.startsWith("[") && text.endsWith("]"))) {
            return cell;
        }
        try {
            return JSON.readValue(text, Object.class);
        } catch (IOException notJson) {
            return cell;
        }
    }

    /** sum, min, max or avg of a field over the rows: numbers only; a non-number is left out; null when there is none. */
    static Object aggregate(String take, List<Map<String, Object>> rows, Field field, Set<String> columns) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal min = null;
        BigDecimal max = null;
        long count = 0;
        for (Map<String, Object> row : rows) {
            BigDecimal n = number(valueOf(row, field, columns));
            if (n == null) {
                continue;
            }
            count++;
            total = total.add(n);
            min = min == null || n.compareTo(min) < 0 ? n : min;
            max = max == null || n.compareTo(max) > 0 ? n : max;
        }
        if (count == 0) {
            return null;
        }
        BigDecimal answer;
        switch (take) {
            case "sum":
                answer = total;
                break;
            case "min":
                answer = min;
                break;
            case "max":
                answer = max;
                break;
            default:
                answer = total.divide(BigDecimal.valueOf(count), MathContext.DECIMAL64);
                break;
        }
        answer = answer.stripTrailingZeros();
        if (answer.scale() <= 0 && answer.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) < 0) {
            return answer.longValueExact();
        }
        return answer.doubleValue();
    }

    private static BigDecimal number(Object value) {
        if (value instanceof Number) {
            try {
                return new BigDecimal(value.toString());
            } catch (NumberFormatException odd) {
                return null;
            }
        }
        if (value instanceof String) {
            try {
                return new BigDecimal(((String) value).trim());
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        return null;
    }

    /** The answer held to {@value #MAX_JSON_CHARS} characters of JSON: rows dropped from the end, a value too big alone left out. */
    static void cap(Result result, String take) {
        if ("all".equals(take)) {
            while (!result.rows.isEmpty() && size(result.rows) > MAX_JSON_CHARS) {
                result.rows.remove(result.rows.size() - 1);
                result.truncated = true;
            }
            return;
        }
        if (size(result.values) <= MAX_JSON_CHARS) {
            return;
        }
        for (Map.Entry<String, Object> entry : result.values.entrySet()) {
            if (size(entry.getValue()) > MAX_JSON_CHARS / 4) {
                entry.setValue(null);
                result.truncated = true;
            }
        }
        while (size(result.values) > MAX_JSON_CHARS && !result.values.isEmpty()) {
            String last = null;
            for (String key : result.values.keySet()) {
                last = key;
            }
            result.values.remove(last);
            result.truncated = true;
        }
    }

    static int size(Object value) {
        try {
            return JSON.writeValueAsString(value).length();
        } catch (JsonProcessingException unwritable) {
            return Integer.MAX_VALUE;
        }
    }
}

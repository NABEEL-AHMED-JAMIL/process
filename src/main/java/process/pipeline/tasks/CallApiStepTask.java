package process.pipeline.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.forms.StorageFormInbox;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.backing.ApiRunner;
import process.pipeline.backing.BucketStore;
import process.pipeline.data.FileFormats;
import process.pipeline.data.Limits;
import process.pipeline.data.Values;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.ItemRole;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;
import process.storage.remote.StorageServiceClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Call API (owner #25, 2026-10-19): sends to a target API -- POST or PUT, or any method a saved request has -- through
 * integration-service's runner, once for the step or once per row, and keeps the answer on the rows so the pipeline can
 * branch on it.
 *
 * The target is never a URL the step names: it is one of the workspace's saved API requests (API collections, registered
 * by its administrators), with its environment's secrets, which only integration-service opens. The runner calls through
 * its address guard (public addresses only, unless integration's own allow-list names a host), honours the request's
 * timeout and its retry settings (a POST is retried on a 5xx only when the request says so), masks every secret in what
 * it answers and records each call in its call log. Nothing is written to any database: this is HTTP to an API.
 *
 * Each output row is the input row (or, {@code once}, the first row or an empty one) plus {@code <prefix>status} (the HTTP
 * status, empty when no answer came), {@code <prefix>ok} (a 2xx, and every {@code okWhen} condition on the answer met),
 * {@code <prefix>error} (why not, in words) and the {@code fields} read from the answer. Branching is the pipeline's own:
 * steps that read this one ({@code input: <this step>}) with a Filter on {@code api_ok} or {@code api_status} -- the
 * accepted rows one way, the refused ones another. {@code onError: fail} (the default) fails the step on the first call
 * that is not ok; {@code continue} keeps every row with its outcome for the branches to take.
 *
 * Every try of a run sends the same {@code {{call_key}}} for a row (run, step and row, never the attempt), so a target that
 * takes an idempotency key can tell a retried run's call from a new one. The step's own retry is off by default: a send
 * is not repeated unless the step or the request asks for it.
 *
 * MIG-419 (owner 2026-10-21): what the target wants decides the shape. {@code {{row}}} is a row as one JSON object (the
 * chosen columns, renamed, a dot in a name nesting); {@code batch} sends Rows per call rows a call as one JSON array,
 * {@code {{rows_json}}} (wrapped by the variable's own text: {"items": {{rows_json}}}), with {@code {{batch}}},
 * {@code {{batches}}} and {@code {{batch_rows}}} and one call key per batch; a batch's per-row results are mapped back by
 * position (Results at). A variable that sends {{row}} or {{rows_json}} goes to integration-service as JSON, written into
 * a JSON body as JSON. A call's variables hold at most 8 MB, and a bigger batch fails before it is sent, saying so. A file
 * an earlier step made (an upload, or one kept with the run, put in the workspace's file drop first) fills
 * {@code {{file_bucket}}} and {@code {{file_key}}} for the request's file or multipart body. Saved steps in each and once
 * run as before; a batch is one metered call.
 */
@Component
public class CallApiStepTask extends RegisteredTask {

    static final String DEFAULT_PREFIX = "api_";
    static final int DEFAULT_MAX_CALLS = 100;
    /** MIG-419: rows a batch call sends by default, and at most. */
    static final int DEFAULT_BATCH_SIZE = 50;
    static final int MAX_BATCH_SIZE = 1000;
    /** MIG-419: the most a call's variables may hold, under integration's 10 MB request cap. */
    static final long MAX_CALL_BYTES = 8L * 1024 * 1024;
    /** What a chosen file fills (MIG-419), sent as the request's variables too. */
    private static final List<String> FILE_VALUES = Arrays.asList("file_bucket", "file_key", "file_name", "file_format");
    private static final Pattern JSON_PLACEHOLDER = Pattern.compile("\\{\\{\\s*(row|rows_json)\\s*}}");
    static final int LOGGED_BODY_CHARS = 300;
    private static final List<String> UNARY = Arrays.asList("is_null", "not_null", "is_empty", "not_empty");

    static final TaskSpec SPEC = TaskSpec.builder("call_api", "Call API", TaskKind.OUTPUT)
        .description("Sends to a saved API request (POST, PUT) once, per row (a row as one JSON object) or in batches (rows as one "
            + "JSON array), with a file an earlier step made if asked, and keeps the status and fields of each answer to branch on.")
        .input(TaskSpec.rows("The rows to send; their columns fill the request's variables."))
        .output(TaskSpec.rows("The input rows (or one row, once), with the answer's status, ok, error and fields."))
        .config(ApiConfigs.request(JsonSchema.object())
            .property("mode", JsonSchema.string().enumOf("each", "batch", "once").title("Send").defaultValue("each")
                .description("each: one call per row ({{row}} is the row as one JSON object); batch: one call per Rows per call rows "
                    + "({{rows_json}} is them as one JSON array, {{batch}} of {{batches}}); once: one call for the step, filled from "
                    + "the run and the first row."))
            .property("batchSize", JsonSchema.integer().minimum(1).maximum(MAX_BATCH_SIZE).title("Rows per call")
                .defaultValue(DEFAULT_BATCH_SIZE).description("batch: how many rows each call sends; the last call sends the rest."))
            .property("columns", JsonSchema.array(JsonSchema.object()
                .required("column", JsonSchema.string().minLength(1).maxLength(128).format("column").title("Column"))
                .property("as", JsonSchema.string().maxLength(255).title("As")
                    .description("The name in the JSON; a dot nests: patient.id.")))
                .maxItems(200).title("Columns sent")
                .description("What {{row}} and {{rows_json}} hold for each row: these columns, renamed with As. None: every column."))
            .property("resultsPath", JsonSchema.string().maxLength(255).title("Results at")
                .description("batch: a dot path to the answer's list of per-row results (results, data.items); each row's Fields "
                    + "are read from its item, by position. Empty: from the whole answer, for every row of the call."))
            .property("file", JsonSchema.object()
                .required("step", JsonSchema.string().minLength(1).maxLength(128).format("step").title("From step")
                    .description("An earlier step of this run that made a file (Save file, Generate report, Convert file)."))
                .property("name", JsonSchema.string().maxLength(255).title("File name")
                    .description("Which of its files, when it made several; empty for its first."))
                .property("bucket", JsonSchema.string().maxLength(128).title("Put in").format("bucket")
                    .description("Where a file kept with the run is put for the call (a storage connection of the workspace); "
                        + "empty for the workspace's file drop."))
                .title("A file from an earlier step")
                .description("Fills {{file_bucket}}, {{file_key}}, {{file_name}} and {{file_format}} for the request's file or "
                    + "multipart body: the request gets them as its variables, and the step's own variables may use them too."))
            .property("variables", JsonSchema.map(JsonSchema.string().maxLength(2000).format("template")).title("Variables")
                .description("Request variables by name, filled from the row and the run: {{patient_id}}, {{row}}, {{rows_json}}, "
                    + "{{run}}, {{call_key}}. A variable holding {{row}} or {{rows_json}} is sent as JSON."))
            .property("fields", JsonSchema.array(JsonSchema.object()
                .required("path", JsonSchema.string().minLength(1).maxLength(255).title("From the answer at")
                    .description("A dot path into the answer's body: id, result.status, errors[0].message."))
                .required("target", JsonSchema.string().minLength(1).maxLength(128).title("As column")))
                .maxItems(50).title("Fields"))
            .property("okWhen", JsonSchema.array(JsonSchema.object()
                .required("path", JsonSchema.string().minLength(1).maxLength(255).title("The answer at"))
                .required("operator", JsonSchema.string().enumOf(FilterStepTask.OPERATORS.toArray(new String[0])).title("Is"))
                .property("value", JsonSchema.scalar().title("Value"))
                .property("values", JsonSchema.array(JsonSchema.scalar()).maxItems(100).title("Values").description("in / not_in."))
                .property("ignoreCase", JsonSchema.bool().title("Ignore case").defaultValue(false)))
                .maxItems(20).title("Ok only when")
                .description("Conditions on the answer's body, beyond a 2xx status (status is accepted); all must hold."))
            .property("onError", JsonSchema.string().enumOf("fail", "continue").title("When a call is not ok").defaultValue("fail")
                .description("fail the step, or continue with the row's status and error for later steps to branch on."))
            .property("prefix", JsonSchema.string().pattern("^[a-z][a-z0-9_]{0,30}$").title("Column prefix").defaultValue(DEFAULT_PREFIX)
                .description("The answer's columns are <prefix>status, <prefix>ok and <prefix>error."))
            .property(AtOnce.SETTING, AtOnce.setting("each and batch: calls"))
            .property("maxCalls", JsonSchema.integer().minimum(1).maximum(Limits.MAX_CALLS).title("At most calls")
                .defaultValue(DEFAULT_MAX_CALLS).description("each: an input needing more calls fails before any is made.")))
        .backing(TaskSpec.INTEGRATION)
        .requiredRole(TaskSpec.ROLE_ADMIN)
        .retry(1, 0)
        .timeoutSeconds(1800)
        .items(ItemRole.EACH).itemsWhen("mode", "each", "batch")
        .build();

    private final ApiRunner runner;
    /** MIG-419: puts a file kept with the run where the request's file body can read it; and the workspace's file drop. */
    private FileDrop drop;
    private LongFunction<String> fileDropOf;

    public CallApiStepTask(ApiRunner runner) {
        super(SPEC);
        this.runner = runner;
    }

    /** Where a kept file goes: (workspace, bucket, key, bytes, size, content type). */
    @FunctionalInterface
    interface FileDrop {
        void put(long tenantId, String bucket, String key, InputStream content, long size, String contentType) throws Exception;
    }

    void useStorage(FileDrop drop, LongFunction<String> fileDropOf) {
        this.drop = drop;
        this.fileDropOf = fileDropOf;
    }

    @Autowired(required = false)
    void useStorage(BucketStore buckets, StorageServiceClient storage) {
        this.useStorage(buckets::upload, tenant -> StorageFormInbox.of(storage.inboxOf(tenant)).alias);
    }

    @Override
    public Optional<String> unavailable() {
        return this.runner.unavailable();
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        List<Map<String, Object>> conditions = Configs.objects(config, "okWhen");
        for (int i = 0; i < conditions.size(); i++) {
            String operator = Configs.text(conditions.get(i), "operator", "");
            boolean listed = "in".equals(operator) || "not_in".equals(operator);
            boolean unary = UNARY.contains(operator);
            if (listed && !(conditions.get(i).get("values") instanceof List)) {
                problems.add(new DefinitionProblem("okWhen[" + i + "].values", operator + " needs its list of values"));
            } else if (!listed && !unary && !conditions.get(i).containsKey("value")) {
                problems.add(new DefinitionProblem("okWhen[" + i + "].value", operator + " needs a value to compare with"));
            }
        }
        String prefix = Configs.text(config, "prefix", DEFAULT_PREFIX);
        Set<String> targets = new LinkedHashSet<>();
        List<Map<String, Object>> fields = Configs.objects(config, "fields");
        for (int i = 0; i < fields.size(); i++) {
            String target = Configs.text(fields.get(i), "target", "");
            if (!targets.add(target)) {
                problems.add(new DefinitionProblem("fields[" + i + "].target", "the column " + target + " is named twice"));
            }
            if (target.equals(prefix + "status") || target.equals(prefix + "ok") || target.equals(prefix + "error")) {
                problems.add(new DefinitionProblem("fields[" + i + "].target", target + " is the answer's own column; name it otherwise"));
            }
        }
        return problems;
    }

    @Override
    public StepResult run(StepContext context) throws Exception {
        Map<String, Object> config = context.config();
        String mode = Configs.text(config, "mode", "each");
        boolean once = "once".equals(mode);
        boolean batch = "batch".equals(mode);
        boolean failFast = !"continue".equals(Configs.text(config, "onError", "fail"));
        String prefix = Configs.text(config, "prefix", DEFAULT_PREFIX);
        int maxCalls = Configs.integer(config, "maxCalls", DEFAULT_MAX_CALLS);
        int batchSize = Math.max(1, Math.min(MAX_BATCH_SIZE, Configs.integer(config, "batchSize", DEFAULT_BATCH_SIZE)));
        Map<String, String> templates = Configs.textMap(config, "variables");
        List<Map<String, Object>> fields = Configs.objects(config, "fields");
        List<Map<String, Object>> okWhen = Configs.objects(config, "okWhen");
        List<Map<String, Object>> sent = Configs.objects(config, "columns");
        String resultsPath = Configs.text(config, "resultsPath", "");

        List<Map<String, Object>> sources;
        List<String> inputColumns;
        if (once) {
            Map<String, Object> first = context.firstInputRow();
            sources = Collections.singletonList(first == null ? new LinkedHashMap<>() : first);
            inputColumns = first == null ? new ArrayList<>() : new ArrayList<>(first.keySet());
        } else {
            Dataset input = context.input();
            int calls = batch ? (input.size() + batchSize - 1) / batchSize : input.size();
            if (calls > maxCalls) {
                throw new IllegalStateException(batch
                    ? String.format("The input has %,d rows: %,d calls of %,d rows; Call API makes at most %,d calls (At most calls). "
                        + "Raise Rows per call (at most %,d), or put a filter, a limit or an aggregate before it.", input.size(), calls,
                        batchSize, maxCalls, MAX_BATCH_SIZE)
                    : String.format("The input has %,d rows; Call API makes at most %,d calls (At most calls). "
                        + "Put a filter, a limit or an aggregate before it, send them in batches, or call once.", input.size(), maxCalls));
            }
            sources = input.getRows();
            inputColumns = input.getColumns();
        }
        // ZT-S5: no rows to send is no call -- nothing to check the columns against (an empty read has only the other side's).
        for (Map<String, Object> column : once || !sources.isEmpty() ? sent : Collections.<Map<String, Object>>emptyList()) {
            String name = Configs.text(column, "column", "");
            if (!inputColumns.isEmpty() && !inputColumns.contains(name)) {
                throw new IllegalStateException(String.format("Columns sent names %s, which the rows have not (they have %s).", name,
                    String.join(", ", inputColumns)));
            }
        }

        Map<String, Object> runValues = Templates.ofRun(context, sources.size());
        runValues.putAll(this.fileValues(context, config));
        Set<String> columns = new LinkedHashSet<>(inputColumns);
        columns.add(prefix + "status");
        columns.add(prefix + "ok");
        columns.add(prefix + "error");
        fields.forEach(field -> columns.add(Configs.text(field, "target", "")));
        Limits.requireShape(sources.size(), columns.size(), "The rows Call API sends");

        int total = once ? 1 : batch ? (sources.size() + batchSize - 1) / batchSize : sources.size();
        context.log(String.format("Request %s, %s; variables: %s.", config.get("requestId"), once ? "one call"
                : batch ? String.format("%d call(s) of up to %d rows", total, batchSize) : sources.size() + " call(s)",
            templates.isEmpty() ? "none" : String.join(", ", templates.keySet())));
        List<Map<String, Object>> rows = new ArrayList<>(sources.size());
        Map<Integer, Integer> byStatus = new LinkedHashMap<>();
        int notOk = 0;
        int atOnce = once ? 1 : AtOnce.of(config);
        if (atOnce > 1) {
            context.log(String.format("Calls at once: %d (the workspace's calls together at most %d).", atOnce, AtOnce.workspaceCap()));
        }
        // MIG-420: each row's item key, worked out here on the step's thread (the calls may run on others), and each call's time.
        String[] tags = new String[sources.size()];
        for (int r = 0; r < tags.length; r++) {
            tags[r] = once ? String.valueOf(r + 1) : context.rowTag(sources.get(r), r);
        }
        long[] took = new long[total];
        // MIG-418: the calls, Calls at once at a time; each built when it is made, never every batch at once.
        List<AtOnce.Outcome<Sent>> outcomes = AtOnce.run(context.tenantId(), total, atOnce, i -> {
            long began = System.nanoTime();
            Sent made;
            try {
                made = this.send(context, config, templates, runValues, sources, sent, batch, once, batchSize, total, i, atOnce > 1, tags);
            } finally {
                took[i] = (System.nanoTime() - began) / 1_000_000L;
            }
            // A blocked call, or one not ok when the step fails on it, stops the calls after it, as one after another always did.
            if ("BLOCKED".equals(made.result.outcome) || failFast && this.problem(made.result, okWhen) != null) {
                throw new Stop(made);
            }
            return made;
        });
        for (int i = 0; i < total; i++) {
            AtOnce.Outcome<Sent> outcome = outcomes.get(i);
            if (outcome.error != null && !(outcome.error instanceof Stop)) {
                throw outcome.error;
            }
            if (!outcome.made) {
                break;
            }
            Sent call = outcome.error instanceof Stop ? ((Stop) outcome.error).made : outcome.value;
            ApiRunner.ApiRunResult result = call.result;
            String said = this.problem(result, okWhen);
            if (!once) {
                // MIG-420: each of the call's rows' items, on the step's thread, in row order: its time, and its failure in the
                // call's words (a blocked call, or one not ok) -- before a call that fails the step throws.
                for (Map<String, Object> row : call.part) {
                    context.itemTook(row, took[i]);
                    if ("BLOCKED".equals(result.outcome)) {
                        context.itemFailed(row, "The API request was blocked" + (result.message == null ? "." : ": " + result.message));
                    } else if (said != null) {
                        context.itemFailed(row, said);
                    }
                }
            }
            if ("BLOCKED".equals(result.outcome)) {
                // The address guard (or a run variable reaching for the address) refused it: never a branch to take.
                throw new IllegalStateException("The API request was blocked" + (result.message == null ? "." : ": " + result.message));
            }
            String error = this.problem(result, okWhen);
            boolean ok = error == null;
            if (result.statusCode != null) {
                byStatus.merge(result.statusCode, 1, Integer::sum);
            }
            List<Map<String, Object>> part = call.part;
            context.log(String.format("%s %d of %d: %s%s.", once ? "Call" : batch ? "Call (" + part.size() + " rows)" : "Row", i + 1, total,
                result.statusCode == null ? "no answer" : "HTTP " + result.statusCode,
                bodySample(result.body).isEmpty() ? "" : ", " + bodySample(result.body)));
            if (!ok) {
                notOk++;
                if (failFast) {
                    throw new IllegalStateException(String.format("Call %d of %d was not ok: %s (When a call is not ok: fail; "
                        + "set it to continue to branch on %sok instead).", i + 1, total, error, prefix));
                }
            }
            JsonNode results = batch && !resultsPath.isEmpty() && result.body != null ? Values.at(result.body, resultsPath) : null;
            for (int j = 0; j < part.size(); j++) {
                Map<String, Object> out = new LinkedHashMap<>(part.get(j));
                out.put(prefix + "status", result.statusCode);
                out.put(prefix + "ok", ok);
                out.put(prefix + "error", error);
                JsonNode from = results == null ? result.body : results.isArray() && j < results.size() ? results.get(j) : null;
                for (Map<String, Object> field : fields) {
                    JsonNode found = from == null ? null : Values.at(from, Configs.text(field, "path", ""));
                    out.put(Configs.text(field, "target", ""), found == null || found.isMissingNode() ? null : Values.scalar(found));
                }
                rows.add(out);
            }
        }
        context.log(String.format("%d call(s) to request %s: %s; %d not ok.", total, config.get("requestId"),
            byStatus.isEmpty() ? "no answers" : byStatus.toString().replace("=", " x").replace("{", "HTTP ").replace("}", ""), notOk));
        if (notOk > 0) {
            context.warn(String.format("%d call(s) were not ok; their rows carry %serror.", notOk, prefix));
        }
        return StepResult.of(new Dataset(new ArrayList<>(columns), rows));
    }

    /** A call that ends the step (blocked, or not ok under fail): the calls after it are not made. */
    private static final class Stop extends Exception {
        final Sent made;

        Stop(Sent made) {
            super("stop", null, false, false);
            this.made = made;
        }
    }

    /** One call made: the rows it was for, and the answer. */
    private static final class Sent {
        final List<Map<String, Object>> part;
        final ApiRunner.ApiRunResult result;

        Sent(List<Map<String, Object>> part, ApiRunner.ApiRunResult result) {
            this.part = part;
            this.result = result;
        }
    }

    /**
     * Call {@code i}: its rows, its variables filled (a variable holding {{row}} or {{rows_json}} sent as JSON), its size
     * checked, and the request run. With Calls at once above 1, a busy answer (429, 503) is {@link AtOnce.Busy}, made again.
     */
    private Sent send(StepContext context, Map<String, Object> config, Map<String, String> templates, Map<String, Object> runValues,
        List<Map<String, Object>> sources, List<Map<String, Object>> sent, boolean batch, boolean once, int batchSize, int total, int i,
        boolean backOff, String[] tags) throws Exception {
        List<Map<String, Object>> part = batch ? sources.subList(i * batchSize, Math.min(sources.size(), (i + 1) * batchSize))
            : Collections.singletonList(sources.get(i));
        Map<String, Object> values = new HashMap<>(runValues);
        if (batch) {
            List<Object> array = new ArrayList<>(part.size());
            part.forEach(source -> array.add(rowObject(source, sent)));
            values.put("rows_json", Values.JSON.writeValueAsString(array));
            values.put("batch", i + 1);
            values.put("batches", total);
            values.put("batch_rows", part.size());
            values.put("call_key", batchKeyOf(context, tags, i * batchSize, part.size(), i));
        } else {
            Map<String, Object> source = part.get(0);
            values.putAll(source);
            // {{row}}: the row as one object -- unless the rows have a column of that name, which fills it as before.
            values.putIfAbsent("row", Values.JSON.writeValueAsString(rowObject(source, sent)));
            values.put("call_key", callKeyOf(context, tags[i], i));
        }
        Map<String, String> variables = new LinkedHashMap<>();
        List<String> json = new ArrayList<>();
        long size = 0;
        for (Map.Entry<String, String> template : templates.entrySet()) {
            String filled = Templates.fill(template.getValue(), values);
            variables.put(template.getKey(), filled);
            size += filled == null ? 0 : filled.length();
            if (sendsJson(template.getValue(), values) && isJson(filled)) {
                json.add(template.getKey());
            }
        }
        // A request whose own body names {{file_bucket}}... gets the file without the step repeating it as a variable.
        for (String name : FILE_VALUES) {
            Object value = runValues.get(name);
            if (value != null) {
                variables.putIfAbsent(name, String.valueOf(value));
            }
        }
        if (size > MAX_CALL_BYTES) {
            throw new IllegalStateException(String.format("Call %d of %d would send %.1f MB (%,d rows); a call's body is at most "
                + "%d MB. Make Rows per call smaller (it is %,d), or send fewer columns.", i + 1, total, size / 1048576.0, part.size(),
                MAX_CALL_BYTES / 1048576, batch ? batchSize : 1));
        }
        ApiRunner.ApiCall call = ApiConfigs.call(context, config, variables);
        call.jsonVariables = json.isEmpty() ? null : json;
        ApiRunner.ApiRunResult result = this.runner.run(call);
        if (backOff && result.statusCode != null && (result.statusCode == 429 || result.statusCode == 503)) {
            throw new AtOnce.Busy(String.format("Call %d of %d: HTTP %d", i + 1, total, result.statusCode), 0);
        }
        return new Sent(part, result);
    }

    /** Whether a variable's template sends a row or rows as JSON: it names {{row}} or {{rows_json}}, and they are this step's. */
    static boolean sendsJson(String template, Map<String, Object> values) {
        Matcher m = JSON_PLACEHOLDER.matcher(template == null ? "" : template);
        while (m.find()) {
            Object value = values.get(m.group(1));
            if (value instanceof String && (((String) value).startsWith("[") || ((String) value).startsWith("{"))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJson(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        try {
            Values.JSON.readTree(text);
            return true;
        } catch (IOException notJson) {
            return false;
        }
    }

    /**
     * MIG-419: a row as one JSON object -- the chosen columns (every one when none is chosen), named by As, a dot in As
     * nesting (patient.id); numbers, true/false and empty stay what they are, anything else is its text.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> rowObject(Map<String, Object> row, List<Map<String, Object>> chosen) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String[]> pairs = new ArrayList<>();
        if (chosen.isEmpty()) {
            row.keySet().forEach(name -> pairs.add(new String[] {name, name}));
        } else {
            for (Map<String, Object> column : chosen) {
                String name = Configs.text(column, "column", "");
                String as = Configs.text(column, "as", "");
                pairs.add(new String[] {name, as.isEmpty() ? name : as});
            }
        }
        for (String[] pair : pairs) {
            Object value = row.get(pair[0]);
            Object written = value == null || value instanceof Number || value instanceof Boolean || value instanceof String
                || value instanceof Map || value instanceof List ? value : Values.text(value);
            String[] path = pair[1].split("\\.");
            Map<String, Object> at = out;
            for (int k = 0; k < path.length - 1; k++) {
                Object next = at.get(path[k]);
                if (!(next instanceof Map)) {
                    next = new LinkedHashMap<String, Object>();
                    at.put(path[k], next);
                }
                at = (Map<String, Object>) next;
            }
            at.put(path[path.length - 1], written);
        }
        return out;
    }

    /**
     * MIG-419: {{file_bucket}}, {{file_key}}, {{file_name}} and {{file_format}} for a file an earlier step of this run made: an
     * upload where it is; a file kept with the run put first in the workspace's file drop (or the bucket the step names),
     * under intake/api/call-api/<run>/<step>/, so the request's file body can read it.
     */
    private Map<String, Object> fileValues(StepContext context, Map<String, Object> config) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> file = config.get("file") instanceof Map ? (Map<String, Object>) config.get("file") : null;
        if (file == null) {
            return Collections.emptyMap();
        }
        String step = Configs.text(file, "step", "");
        String wanted = Configs.text(file, "name", "");
        List<StepContext.RunFile> files = context.runFiles(step);
        StepContext.RunFile chosen = files.stream().filter(f -> wanted.isEmpty() || wanted.equals(f.name)).findFirst().orElse(null);
        if (chosen == null) {
            throw new IllegalStateException(files.isEmpty()
                ? String.format("A file from an earlier step: step <%s> made no file in this run.", step)
                : String.format("A file from an earlier step: step <%s> made no file named %s (it made %s).", step, wanted,
                    files.stream().map(f -> f.name).collect(Collectors.joining(", "))));
        }
        String bucket = chosen.bucket;
        String key = chosen.key;
        if (chosen.kept()) {
            if (this.drop == null || this.fileDropOf == null) {
                throw new IllegalStateException("A file kept with the run cannot be sent from here: no storage is reachable.");
            }
            bucket = Configs.text(file, "bucket", "");
            if (bucket.isEmpty()) {
                bucket = this.fileDropOf.apply(context.tenantId());
            }
            if (bucket == null || bucket.isEmpty()) {
                throw new IllegalStateException(String.format("%s is kept with the run; Call API puts it in the workspace's file drop to "
                    + "send it, and the workspace has none. Choose one under Documents › Intake › File drop, or name a bucket in Put in.",
                    chosen.name));
            }
            key = String.format("intake/api/call-api/%d/%s/%s", context.jobQueueId(), context.stepKey(), chosen.name);
            try (InputStream content = chosen.open()) {
                this.drop.put(context.tenantId(), bucket, key, content, chosen.bytes == null ? -1 : chosen.bytes,
                    FileFormats.contentType(chosen.format));
            }
            context.log(String.format("%s (kept with the run by step <%s>) put in %s/%s to send.", chosen.name, step, bucket, key));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("file_bucket", bucket);
        values.put("file_key", key);
        values.put("file_name", chosen.name);
        values.put("file_format", chosen.format);
        return values;
    }

    /** Why the answer is not ok, in words; null when it is (a 2xx, and every okWhen condition met). */
    String problem(ApiRunner.ApiRunResult result, List<Map<String, Object>> okWhen) {
        if (result.statusCode == null) {
            return result.message == null ? "no answer" : result.message;
        }
        if (result.statusCode < 200 || result.statusCode > 299) {
            return "HTTP " + result.statusCode + (result.message == null ? "" : ": " + result.message);
        }
        if (!result.ok() && result.message != null) {
            // A 2xx the runner still failed (an assertion of the request, an answer over its cap).
            return result.message;
        }
        for (Map<String, Object> condition : okWhen) {
            String path = Configs.text(condition, "path", "");
            JsonNode found = result.body == null ? null : Values.at(result.body, path);
            Object actual = found == null || found.isMissingNode() ? null : Values.scalar(found);
            if (!FilterStepTask.meets(actual, condition)) {
                return String.format("the answer's %s is %s, not %s %s", path, actual == null ? "empty" : actual,
                    Configs.text(condition, "operator", ""), condition.containsKey("values") ? condition.get("values")
                        : condition.getOrDefault("value", ""));
            }
        }
        return null;
    }

    /** The first characters of the (already masked) answer, for the run's log. */
    static String bodySample(JsonNode body) {
        if (body == null || body.isNull() || body.isMissingNode()) {
            return "";
        }
        String text = body.isTextual() ? body.asText() : body.toString();
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() <= LOGGED_BODY_CHARS ? text : text.substring(0, LOGGED_BODY_CHARS) + "…";
    }

    /**
     * MIG-420: a row's {{call_key}} -- by its number, as callKey always was, or in an item run by its item's token
     * (StepContext.rowTag, worked out on the step's thread), so a retry of some items, which reads only their rows, sends each
     * item the key it had and never another item's.
     */
    static String callKeyOf(StepContext context, String tag, int index) {
        if (tag == null || tag.equals(String.valueOf(index + 1))) {
            return callKey(context.tenantId(), context.jobQueueId(), context.stepKey(), index);
        }
        return UUID.nameUUIDFromBytes(String.format("call-api:%d:%d:%s:%s", context.tenantId(), context.jobQueueId(), context.stepKey(), tag)
            .getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** MIG-420: {@link #callKeyOf} for a row (its tag asked for here, on the caller's thread). */
    static String callKeyOf(StepContext context, Map<String, Object> row, int index) {
        return callKeyOf(context, context.rowTag(row, index), index);
    }

    /**
     * MIG-420: a batch's {{call_key}} -- by its number, as batchKey, or in an item run by its rows' item tokens, so a retry
     * whose batches hold other rows never sends the key an earlier batch of other items had.
     */
    static String batchKeyOf(StepContext context, String[] tags, int from, int size, int batch) {
        StringBuilder items = new StringBuilder();
        for (int r = from; r < from + size; r++) {
            if (tags[r] == null || tags[r].equals(String.valueOf(r + 1))) {
                return batchKey(context.tenantId(), context.jobQueueId(), context.stepKey(), batch);
            }
            items.append(tags[r]).append(',');
        }
        return UUID.nameUUIDFromBytes(String.format("call-api:%d:%d:%s:batch:%s", context.tenantId(), context.jobQueueId(), context.stepKey(),
            items).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** MIG-419: one per run, step and batch -- the same on every try of the run. */
    static String batchKey(long tenantId, long jobQueueId, String stepKey, int batch) {
        return UUID.nameUUIDFromBytes(String.format("call-api:%d:%d:%s:batch:%d", tenantId, jobQueueId, stepKey, batch)
            .getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** One per run, step and row -- the same on every try of the run, so a target can tell a resend from a new call. */
    static String callKey(long tenantId, long jobQueueId, String stepKey, int row) {
        return UUID.nameUUIDFromBytes(String.format("call-api:%d:%d:%s:%d", tenantId, jobQueueId, stepKey, row)
            .getBytes(StandardCharsets.UTF_8)).toString();
    }
}

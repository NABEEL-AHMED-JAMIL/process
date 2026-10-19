package process.pipeline.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.backing.ApiRunner;
import process.pipeline.data.Limits;
import process.pipeline.data.Values;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

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
 */
@Component
public class CallApiStepTask extends RegisteredTask {

    static final String DEFAULT_PREFIX = "api_";
    static final int DEFAULT_MAX_CALLS = 100;
    static final int LOGGED_BODY_CHARS = 300;
    private static final List<String> UNARY = Arrays.asList("is_null", "not_null", "is_empty", "not_empty");

    static final TaskSpec SPEC = TaskSpec.builder("call_api", "Call API", TaskKind.OUTPUT)
        .description("Sends to a saved API request (POST, PUT) once or per row, and keeps the status and fields of each answer to "
            + "branch on.")
        .input(TaskSpec.rows("The rows to send; their columns fill the request's variables."))
        .output(TaskSpec.rows("The input rows (or one row, once), with the answer's status, ok, error and fields."))
        .config(ApiConfigs.request(JsonSchema.object())
            .property("mode", JsonSchema.string().enumOf("each", "once").title("Call").defaultValue("each")
                .description("each: one call per row (at most At most calls); once: one call for the step, filled from the run and "
                    + "the first row."))
            .property("variables", JsonSchema.map(JsonSchema.string().maxLength(2000).format("template")).title("Variables")
                .description("Request variables by name, filled from the row and the run: {{patient_id}}, {{run}}, {{call_key}}."))
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
            .property("maxCalls", JsonSchema.integer().minimum(1).maximum(Limits.MAX_CALLS).title("At most calls")
                .defaultValue(DEFAULT_MAX_CALLS).description("each: an input needing more calls fails before any is made.")))
        .backing(TaskSpec.INTEGRATION)
        .requiredRole(TaskSpec.ROLE_ADMIN)
        .retry(1, 0)
        .timeoutSeconds(1800)
        .build();

    private final ApiRunner runner;

    public CallApiStepTask(ApiRunner runner) {
        super(SPEC);
        this.runner = runner;
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
        boolean once = "once".equals(Configs.text(config, "mode", "each"));
        boolean failFast = !"continue".equals(Configs.text(config, "onError", "fail"));
        String prefix = Configs.text(config, "prefix", DEFAULT_PREFIX);
        int maxCalls = Configs.integer(config, "maxCalls", DEFAULT_MAX_CALLS);
        Map<String, String> templates = Configs.textMap(config, "variables");
        List<Map<String, Object>> fields = Configs.objects(config, "fields");
        List<Map<String, Object>> okWhen = Configs.objects(config, "okWhen");

        List<Map<String, Object>> sources;
        List<String> inputColumns;
        if (once) {
            Map<String, Object> first = context.firstInputRow();
            sources = Collections.singletonList(first == null ? new LinkedHashMap<>() : first);
            inputColumns = first == null ? new ArrayList<>() : new ArrayList<>(first.keySet());
        } else {
            Dataset input = context.input();
            if (input.size() > maxCalls) {
                throw new IllegalStateException(String.format("The input has %,d rows; Call API makes at most %,d calls (At most calls). "
                    + "Put a filter, a limit or an aggregate before it, or call once.", input.size(), maxCalls));
            }
            sources = input.getRows();
            inputColumns = input.getColumns();
        }

        Map<String, Object> runValues = Templates.ofRun(context, sources.size());
        Set<String> columns = new LinkedHashSet<>(inputColumns);
        columns.add(prefix + "status");
        columns.add(prefix + "ok");
        columns.add(prefix + "error");
        fields.forEach(field -> columns.add(Configs.text(field, "target", "")));
        Limits.requireShape(sources.size(), columns.size(), "The rows Call API sends");

        context.log(String.format("Request %s, %s; variables: %s.", config.get("requestId"), once ? "one call" : sources.size() + " call(s)",
            templates.isEmpty() ? "none" : String.join(", ", templates.keySet())));
        List<Map<String, Object>> rows = new ArrayList<>(sources.size());
        Map<Integer, Integer> byStatus = new LinkedHashMap<>();
        int notOk = 0;
        for (int i = 0; i < sources.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Call API was stopped.");
            }
            Map<String, Object> source = sources.get(i);
            Map<String, Object> values = new HashMap<>(runValues);
            values.putAll(source);
            values.put("call_key", callKey(context.tenantId(), context.jobQueueId(), context.stepKey(), i));
            Map<String, String> variables = new LinkedHashMap<>();
            templates.forEach((name, template) -> variables.put(name, Templates.fill(template, values)));

            ApiRunner.ApiRunResult result = this.runner.run(ApiConfigs.call(context, config, variables));
            if ("BLOCKED".equals(result.outcome)) {
                // The address guard (or a run variable reaching for the address) refused it: never a branch to take.
                throw new IllegalStateException("The API request was blocked" + (result.message == null ? "." : ": " + result.message));
            }
            String error = this.problem(result, okWhen);
            boolean ok = error == null;
            if (result.statusCode != null) {
                byStatus.merge(result.statusCode, 1, Integer::sum);
            }
            context.log(String.format("%s %d of %d: %s%s.", once ? "Call" : "Row", i + 1, sources.size(),
                result.statusCode == null ? "no answer" : "HTTP " + result.statusCode,
                bodySample(result.body).isEmpty() ? "" : ", " + bodySample(result.body)));
            if (!ok) {
                notOk++;
                if (failFast) {
                    throw new IllegalStateException(String.format("Call %d of %d was not ok: %s (When a call is not ok: fail; "
                        + "set it to continue to branch on %sok instead).", i + 1, sources.size(), error, prefix));
                }
            }
            Map<String, Object> out = new LinkedHashMap<>(source);
            out.put(prefix + "status", result.statusCode);
            out.put(prefix + "ok", ok);
            out.put(prefix + "error", error);
            for (Map<String, Object> field : fields) {
                JsonNode found = result.body == null ? null : Values.at(result.body, Configs.text(field, "path", ""));
                out.put(Configs.text(field, "target", ""), found == null || found.isMissingNode() ? null : Values.scalar(found));
            }
            rows.add(out);
        }
        context.log(String.format("%d call(s) to request %s: %s; %d not ok.", sources.size(), config.get("requestId"),
            byStatus.isEmpty() ? "no answers" : byStatus.toString().replace("=", " x").replace("{", "HTTP ").replace("}", ""), notOk));
        if (notOk > 0) {
            context.warn(String.format("%d call(s) were not ok; their rows carry %serror.", notOk, prefix));
        }
        return StepResult.of(new Dataset(new ArrayList<>(columns), rows));
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

    /** One per run, step and row -- the same on every try of the run, so a target can tell a resend from a new call. */
    static String callKey(long tenantId, long jobQueueId, String stepKey, int row) {
        return UUID.nameUUIDFromBytes(String.format("call-api:%d:%d:%s:%d", tenantId, jobQueueId, stepKey, row)
            .getBytes(StandardCharsets.UTF_8)).toString();
    }
}

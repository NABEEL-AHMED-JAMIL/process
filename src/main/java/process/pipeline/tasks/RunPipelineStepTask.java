package process.pipeline.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.PipelineCalls;
import process.pipeline.RunResults;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.data.Limits;
import process.pipeline.data.Values;
import process.pipeline.registry.ItemRole;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Run pipeline (MIG-424, owner 2026-10-26): calls another pipeline of this workspace -- by its schedule (job) -- with
 * parameters, waits for its run, and takes its answer as rows. The called run is a normal run (on its pipeline's runs,
 * logged, billed as any run) that names the run that called it; PipelineCalls checks the pipeline is the workspace's own and
 * active, its parameters as Run with... checks them, and the call chain: at most {@value PipelineCalls#MAX_DEPTH} deep, never
 * a pipeline already in it.
 *
 * <ul>
 *   <li>{@code once} (the default): one call, its parameters filled from the run ({{param.*}}, {{run.date}}) and the first
 *   input row; the output is the called run's rows -- its final output, or a named step's ({@code from}), the chosen fields
 *   or every column, at most {@code maxRows}.</li>
 *   <li>{@code each}: one call per input row, its parameters filled from the row ({{mrn}}); each output row is the input row
 *   with the first row of its answer's fields beside it, and {@code <prefix>run_id}, {@code <prefix>status},
 *   {@code <prefix>ok} and {@code <prefix>error}. {@code onError: fail} (the default) fails the step on the first call that
 *   does not complete; {@code continue} keeps every row with its outcome for later steps to branch on.</li>
 * </ul>
 * The calls go one after another: each waits for its run on this step's thread.
 */
@Component
public class RunPipelineStepTask extends RegisteredTask {

    public static final String CODE = "run_pipeline";
    static final String DEFAULT_PREFIX = "called_";
    static final int DEFAULT_MAX_CALLS = 20;
    static final int MAX_CALLS = 100;
    static final int DEFAULT_MAX_ROWS = 10_000;
    private static final Pattern PARAMETER = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");

    static final TaskSpec SPEC = TaskSpec.builder(CODE, "Run pipeline", TaskKind.PROCESS)
        .description("Calls another pipeline of this workspace with parameters -- once, or once per row -- waits for its run and "
            + "takes its answer as rows (its final output, or a named step's).")
        .input(TaskSpec.rows("Each: one call per row, its columns fill the parameters. Once: the first row fills them."))
        .output(TaskSpec.rows("Once: the called run's rows. Each: the input rows, each with its call's first answer row, run id, "
            + "status, ok and error."))
        .config(JsonSchema.object()
            .required("jobId", JsonSchema.integer().minimum(1).format("schedule").title("Pipeline")
                .description("The pipeline (its schedule) to call: one of this workspace's, active, running Core's steps."))
            .property("mode", JsonSchema.string().enumOf("once", "each").title("Call").defaultValue("once")
                .description("once: one call for the step; each: one call per input row."))
            .property("parameters", JsonSchema.map(JsonSchema.string().maxLength(500).format("template")).title("Parameters")
                .description("The called pipeline's parameters by name, filled from the row and the run: {{mrn}}, {{param.region}}, "
                    + "{{run.date}}. Checked against what it declares, as Run with... checks them."))
            .property("from", JsonSchema.string().maxLength(128).title("Answer from").defaultValue(RunResults.FINAL)
                .description("final: the called run's last output; or the key of one of its steps."))
            .property("fields", JsonSchema.array(JsonSchema.object()
                .required("path", JsonSchema.string().minLength(1).maxLength(255).title("Column")
                    .description("A column of the answer, or a column holding JSON and a dot path into it (summary.risk)."))
                .property("as", JsonSchema.string().pattern("^[A-Za-z_][A-Za-z0-9_]{0,63}$").title("As")))
                .maxItems(RunResults.MAX_FIELDS).title("Fields")
                .description("The answer's fields to keep; none: every column."))
            .property("maxRows", JsonSchema.integer().minimum(1).maximum(Limits.MAX_ROWS).title("At most rows").defaultValue(DEFAULT_MAX_ROWS)
                .description("once: the most rows of the answer taken; the step warns when there were more."))
            .property("onError", JsonSchema.string().enumOf("fail", "continue").title("When a call does not complete").defaultValue("fail")
                .description("fail the step, or (each) continue with the row's status and error for later steps to branch on."))
            .property("prefix", JsonSchema.string().pattern("^[a-z][a-z0-9_]{0,30}$").title("Column prefix").defaultValue(DEFAULT_PREFIX)
                .description("each: the call's columns are <prefix>run_id, <prefix>status, <prefix>ok and <prefix>error."))
            .property("maxCalls", JsonSchema.integer().minimum(1).maximum(MAX_CALLS).title("At most calls").defaultValue(DEFAULT_MAX_CALLS)
                .description("each: an input needing more calls fails before any is made.")))
        .backing(TaskSpec.CORE)
        // A run it starts is billed as a run: an administrator's step, as Call API's is.
        .requiredRole(TaskSpec.ROLE_ADMIN)
        .retry(1, 0)
        .timeoutSeconds(3600)
        .items(ItemRole.EACH).itemsWhen("mode", "each")
        // Not "run_pipeline": the assistant's own tool of that name runs a pipeline now.
        .aiToolName("call_pipeline")
        .build();

    private final PipelineCalls calls;

    public RunPipelineStepTask(PipelineCalls calls) {
        super(SPEC);
        this.calls = calls;
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        for (String name : Configs.textMap(config, "parameters").keySet()) {
            if (!PARAMETER.matcher(name).matches()) {
                problems.add(new DefinitionProblem("parameters." + name, "a parameter's name: lower case letters, digits and '_'"));
            }
        }
        List<String> selection = new ArrayList<>();
        RunResults.Selection.of(selectionNode(config), selection);
        for (String problem : selection) {
            problems.add(new DefinitionProblem("fields", problem.replaceFirst("^results\\.", "")));
        }
        String prefix = Configs.text(config, "prefix", DEFAULT_PREFIX);
        Set<String> own = ownColumns(prefix);
        List<Map<String, Object>> fields = Configs.objects(config, "fields");
        for (int i = 0; i < fields.size(); i++) {
            String as = Configs.text(fields.get(i), "as", null);
            String name = as != null ? as : Configs.text(fields.get(i), "path", "");
            if ("each".equals(Configs.text(config, "mode", "once")) && own.contains(name)) {
                problems.add(new DefinitionProblem("fields[" + i + "].as", name + " is the call's own column; name it otherwise"));
            }
        }
        return problems;
    }

    @Override
    public StepResult run(StepContext context) throws Exception {
        Map<String, Object> config = context.config();
        long jobId = Configs.longValue(config, "jobId") == null ? 0 : Configs.longValue(config, "jobId");
        boolean each = "each".equals(Configs.text(config, "mode", "once"));
        boolean failFast = !"continue".equals(Configs.text(config, "onError", "fail"));
        String prefix = Configs.text(config, "prefix", DEFAULT_PREFIX);
        int maxCalls = Math.min(MAX_CALLS, Configs.integer(config, "maxCalls", DEFAULT_MAX_CALLS));
        int maxRows = Math.min(Limits.MAX_ROWS, Configs.integer(config, "maxRows", DEFAULT_MAX_ROWS));
        Map<String, String> templates = Configs.textMap(config, "parameters");
        List<String> problems = new ArrayList<>();
        RunResults.Selection selection = RunResults.Selection.of(selectionNode(config), problems);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join("; ", problems) + ".");
        }
        Map<String, Object> runValues = Templates.ofRun(context, null);
        if (!each) {
            Map<String, Object> first = context.firstInputRow();
            Map<String, Object> values = new LinkedHashMap<>(runValues);
            if (first != null) {
                values.putAll(first);
            }
            Map<String, Object> given = fill(templates, values);
            context.log(String.format(Locale.ROOT, "Calling pipeline %d once; parameters: %s.", jobId, describe(given)));
            PipelineCalls.Called called = this.calls.call(context.tenantId(), context.jobQueueId(), jobId, given, selection, maxRows);
            context.log(String.format(Locale.ROOT, "Run %d of pipeline %d: %s%s.", called.runId, jobId, called.status,
                called.message == null ? "" : " -- " + called.message));
            if (!called.ok()) {
                throw new IllegalStateException(String.format("The called run %d of pipeline %d did not complete (%s)%s", called.runId, jobId,
                    called.status, called.message == null ? "." : ": " + called.message));
            }
            if (called.about.problem != null) {
                context.warn("The called run answered no rows: " + called.about.problem);
            } else if (called.about.truncated) {
                context.warn(String.format(Locale.ROOT, "The called run answered %,d rows; the first %,d are taken (At most rows).",
                    called.about.rowCount, maxRows));
            }
            context.log(String.format(Locale.ROOT, "%,d row(s) from step %s of run %d.", called.rows.size(), called.about.step, called.runId));
            return StepResult.of(called.rows);
        }

        Dataset input = context.input();
        if (input.size() > maxCalls) {
            throw new IllegalStateException(String.format(Locale.ROOT, "The input has %,d rows; Run pipeline makes at most %,d calls (At most "
                + "calls). Put a filter, a limit or an aggregate before it, or call once.", input.size(), maxCalls));
        }
        Set<String> columns = new LinkedHashSet<>(input.getColumns());
        Set<String> own = ownColumns(prefix);
        columns.addAll(own);
        List<Map<String, Object>> rows = new ArrayList<>(input.size());
        int notOk = 0;
        for (int i = 0; i < input.size(); i++) {
            Map<String, Object> row = input.getRows().get(i);
            Map<String, Object> values = new LinkedHashMap<>(runValues);
            values.putAll(row);
            Map<String, Object> out = new LinkedHashMap<>(row);
            long began = System.nanoTime();
            String error = null;
            PipelineCalls.Called called = null;
            try {
                called = this.calls.call(context.tenantId(), context.jobQueueId(), jobId, fill(templates, values), selection, 1);
                if (!called.ok()) {
                    error = String.format("run %d %s%s", called.runId, called.status, called.message == null ? "" : ": " + called.message);
                }
            } catch (PipelineCalls.Refused refused) {
                error = refused.getMessage();
            }
            // MIG-420: the row's item, on the step's thread, in row order: its time, and its failure in the call's words.
            context.itemTook(row, (System.nanoTime() - began) / 1_000_000L);
            if (error != null) {
                context.itemFailed(row, "Run pipeline: " + error);
            }
            context.log(String.format(Locale.ROOT, "Row %d of %d: %s.", i + 1, input.size(), error == null
                ? "run " + called.runId + " completed" : error));
            if (error != null) {
                notOk++;
                if (failFast) {
                    throw new IllegalStateException(String.format(Locale.ROOT, "Call %d of %d did not complete: %s (When a call does not "
                        + "complete: fail; set it to continue to branch on %sok instead).", i + 1, input.size(), error, prefix));
                }
            }
            Map<String, Object> answer = called == null || called.rows.isEmpty() ? Collections.<String, Object>emptyMap()
                : called.rows.getRows().get(0);
            if (called != null) {
                for (String column : called.rows.getColumns()) {
                    if (!own.contains(column) && !row.containsKey(column)) {
                        columns.add(column);
                    }
                }
            }
            for (Map.Entry<String, Object> cell : answer.entrySet()) {
                if (!own.contains(cell.getKey()) && !row.containsKey(cell.getKey())) {
                    out.put(cell.getKey(), cell.getValue());
                }
            }
            out.put(prefix + "run_id", called == null ? null : called.runId);
            out.put(prefix + "status", called == null ? null : called.status);
            out.put(prefix + "ok", error == null);
            out.put(prefix + "error", error);
            rows.add(out);
        }
        if (notOk > 0) {
            context.warn(String.format(Locale.ROOT, "%d call(s) did not complete; their rows carry %serror.", notOk, prefix));
        }
        Limits.requireShape(rows.size(), columns.size(), "The rows Run pipeline answers");
        return StepResult.of(new Dataset(new ArrayList<>(columns), rows));
    }

    /** The call's own columns, each with the prefix. */
    static Set<String> ownColumns(String prefix) {
        Set<String> own = new LinkedHashSet<>();
        own.add(prefix + "run_id");
        own.add(prefix + "status");
        own.add(prefix + "ok");
        own.add(prefix + "error");
        return own;
    }

    /** The step's from and fields as a result selection reads them. */
    static JsonNode selectionNode(Map<String, Object> config) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("from", Configs.text(config, "from", RunResults.FINAL));
        node.put("take", "all");
        node.put("fields", Configs.objects(config, "fields"));
        return Values.JSON.valueToTree(node);
    }

    /** Each parameter's template filled; an empty one is left out (the pipeline's default applies). */
    static Map<String, Object> fill(Map<String, String> templates, Map<String, Object> values) {
        Map<String, Object> given = new LinkedHashMap<>();
        for (Map.Entry<String, String> template : templates.entrySet()) {
            String filled = Templates.fill(template.getValue(), values);
            if (filled != null && !filled.isEmpty()) {
                given.put(template.getKey(), filled);
            }
        }
        return given;
    }

    private static String describe(Map<String, Object> given) {
        return given.isEmpty() ? "its defaults" : String.join(", ", given.keySet());
    }
}

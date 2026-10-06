package process.pipeline.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import process.pipeline.Dataset;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.backing.ApiRunner;
import process.pipeline.data.Limits;
import process.pipeline.data.Values;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Enrich (MIG-231): for each input row, runs a saved API request through integration-service's runner with variables
 * filled from the row ({{column}}, and the run's placeholders), and adds fields of the answer as columns. Rows that
 * would send the same variables share one call. At most {@code maxCalls} calls (default 100, at most
 * {@value Limits#MAX_CALLS}): an input that needs more fails before any call is made. A call that fails: {@code fail}
 * (the default) fails the step, {@code skip} drops the row, {@code null} keeps it with the fields empty.
 */
@Component
public class EnrichStepTask extends RegisteredTask {

    static final TaskSpec SPEC = TaskSpec.builder("enrich", "Enrich", TaskKind.PROCESS)
        .description("Calls a saved API request per row (integration-service's runner) and adds fields of its answer as columns.")
        .input(TaskSpec.rows("The rows to enrich; their columns fill the request's variables."))
        .output(TaskSpec.rows("The input's columns, then each field taken from the answer."))
        .config(ApiConfigs.request(JsonSchema.object())
            .property("variables", JsonSchema.map(JsonSchema.string().format("template")).title("Variables")
                .description("Request variables by name, filled from the row: {{customer_id}}."))
            .required("fields", JsonSchema.array(JsonSchema.object()
                .required("path", JsonSchema.string().minLength(1).maxLength(255).title("From the answer at")
                    .description("A dot path into the answer's body: data.score."))
                .required("target", JsonSchema.string().minLength(1).maxLength(128).title("As column")))
                .minItems(1).maxItems(50).title("Fields"))
            .property("onError", JsonSchema.string().enumOf("fail", "skip", "null").title("When a call fails").defaultValue("fail")
                .description("fail the step, skip the row, or keep it with the fields empty."))
            .property("maxCalls", JsonSchema.integer().minimum(1).maximum(Limits.MAX_CALLS).title("At most calls").defaultValue(100)))
        .backing(TaskSpec.INTEGRATION)
        .timeoutSeconds(1800)
        .aiToolName("enrich_rows")
        .build();

    private final ApiRunner runner;

    public EnrichStepTask(ApiRunner runner) {
        super(SPEC);
        this.runner = runner;
    }

    @Override
    public Optional<String> unavailable() {
        return this.runner.unavailable();
    }

    @Override
    public StepResult run(StepContext context) throws Exception {
        Map<String, Object> config = context.config();
        Map<String, String> templates = Configs.textMap(config, "variables");
        List<Map<String, Object>> fields = Configs.objects(config, "fields");
        String onError = Configs.text(config, "onError", "fail");
        int maxCalls = Configs.integer(config, "maxCalls", 100);
        Dataset input = context.input();
        Map<String, Object> runValues = Templates.ofRun(context, input.size());

        List<Map<String, String>> perRow = new ArrayList<>(input.size());
        Set<Map<String, String>> distinct = new LinkedHashSet<>();
        for (Map<String, Object> row : input.getRows()) {
            Map<String, Object> values = new HashMap<>(runValues);
            values.putAll(row);
            Map<String, String> variables = new LinkedHashMap<>();
            templates.forEach((name, template) -> variables.put(name, Templates.fill(template, values)));
            perRow.add(variables);
            distinct.add(variables);
        }
        if (distinct.size() > maxCalls) {
            throw new IllegalStateException(String.format("The input needs %d calls; this step makes at most %d (maxCalls).", distinct.size(), maxCalls));
        }

        Map<Map<String, String>, Object> answers = new HashMap<>();
        int failedCalls = 0;
        for (Map<String, String> variables : distinct) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Enrich was stopped.");
            }
            Object answer;
            try {
                ApiRunner.ApiRunResult result = this.runner.run(ApiConfigs.call(context, config, variables));
                if (!result.ok()) {
                    throw ApiConfigs.failed(result);
                }
                answer = result.body;
            } catch (InterruptedException stopped) {
                throw stopped;
            } catch (Exception failed) {
                if ("fail".equals(onError)) {
                    throw failed;
                }
                failedCalls++;
                answer = failed;
            }
            answers.put(variables, answer);
        }

        Set<String> columns = new LinkedHashSet<>(input.getColumns());
        for (Map<String, Object> field : fields) {
            columns.add(Configs.text(field, "target", ""));
        }
        Limits.requireShape(input.size(), columns.size(), "The enriched rows");
        List<Map<String, Object>> rows = new ArrayList<>(input.size());
        int skipped = 0;
        for (int i = 0; i < input.size(); i++) {
            Object answer = answers.get(perRow.get(i));
            if (answer instanceof Exception && "skip".equals(onError)) {
                skipped++;
                continue;
            }
            Map<String, Object> out = new LinkedHashMap<>(input.getRows().get(i));
            for (Map<String, Object> field : fields) {
                Object value = answer instanceof JsonNode ? Values.scalar(Values.at((JsonNode) answer, Configs.text(field, "path", ""))) : null;
                out.put(Configs.text(field, "target", ""), value);
            }
            rows.add(out);
        }
        context.log(String.format("%d call(s) for %d row(s); %d row(s) out.", distinct.size(), input.size(), rows.size()));
        if (failedCalls > 0) {
            context.warn(String.format("%d call(s) failed: %s.", failedCalls, "skip".equals(onError)
                ? skipped + " row(s) skipped" : "their fields are empty"));
        }
        return StepResult.of(new Dataset(new ArrayList<>(columns), rows));
    }
}

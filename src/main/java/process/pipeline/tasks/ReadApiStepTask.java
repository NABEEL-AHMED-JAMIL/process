package process.pipeline.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.backing.ApiRunner;
import process.pipeline.data.FileFormats;
import process.pipeline.data.Limits;
import process.pipeline.data.RowCollector;
import process.pipeline.data.Values;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Read API (MIG-231): runs one of the workspace's saved API requests through integration-service's runner and takes
 * its answer as rows -- the items of every page when the request pages, else the body; at {@code rowsPath} when the
 * rows are inside it. An object is one row, an array one row per element; nested values are kept as JSON text.
 */
@Component
public class ReadApiStepTask extends RegisteredTask {

    static final TaskSpec SPEC = TaskSpec.builder("read_api", "Read API", TaskKind.READ)
        .description("Runs a saved API request (integration-service's runner) and takes its answer as rows.")
        .output(TaskSpec.rows("One row per element of the answer's rows (or the whole answer as one row)."))
        .config(ApiConfigs.request(JsonSchema.object())
            .property("variables", JsonSchema.map(JsonSchema.string()).title("Variables")
                .description("Request variables by name; {{run}}, {{date}} and the other run placeholders are filled in."))
            .property("rowsPath", JsonSchema.string().maxLength(255).title("Rows at")
                .description("Where the rows are in the answer, as a dot path (data.items); empty for the pages' items, else the body."))
            .property("maxRows", JsonSchema.integer().minimum(1).maximum(Limits.MAX_ROWS).title("At most rows")
                .description("Stop after this many rows.")))
        .backing(TaskSpec.INTEGRATION)
        .retry(3, 10)
        .timeoutSeconds(300)
        .aiToolName("read_api")
        .build();

    private final ApiRunner runner;

    public ReadApiStepTask(ApiRunner runner) {
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
        Map<String, Object> placeholders = Templates.ofRun(context, null);
        Map<String, String> variables = new LinkedHashMap<>();
        Configs.textMap(config, "variables").forEach((name, value) -> variables.put(name, Templates.fill(value, placeholders)));
        ApiRunner.ApiRunResult result = this.runner.run(ApiConfigs.call(context, config, variables));
        if (!result.ok()) {
            throw ApiConfigs.failed(result);
        }
        String rowsPath = Configs.text(config, "rowsPath", null);
        JsonNode rows = rowsPath != null ? Values.at(result.body, rowsPath)
            : result.items != null && result.items.isArray() ? result.items : result.body;
        if (rows == null || rows.isMissingNode()) {
            throw new IllegalStateException(rowsPath == null ? "The API answered no body." : "The API's answer has nothing at " + rowsPath + ".");
        }
        RowCollector collector = new RowCollector("The API's answer", Configs.integer(config, "maxRows", null));
        FileFormats.each(rows, collector);
        context.log(String.format("%d row(s) from request %s (HTTP %s)%s.", collector.size(), config.get("requestId"),
            result.statusCode == null ? "-" : result.statusCode.toString(), result.truncated ? "; the runner cut the answer short" : ""));
        if (result.truncated) {
            context.warn("integration-service truncated the answer; some rows may be missing.");
        }
        return StepResult.of(collector.toDataset());
    }
}

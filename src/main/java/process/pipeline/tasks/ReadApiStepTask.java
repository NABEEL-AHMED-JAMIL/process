package process.pipeline.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Read API (MIG-231): runs one of the workspace's saved API requests through integration-service's runner and takes
 * its answer as rows -- the items of every page when the request pages, else the body; at {@code rowsPath} when the
 * rows are inside it ({@code entry[].resource} fans out over a list). An object is one row, an array one row per
 * element; nested values are kept as JSON text, unless {@code fields} names the columns: each a dot path into the row's
 * element ({@code code.coding[0].code}, {@code valueQuantity.value}), so a nested answer (a FHIR resource) becomes flat
 * columns without any knowledge of its shape in code.
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
                .description("Where the rows are in the answer, as a dot path (data.items; entry[].resource takes the resource of"
                    + " every element); empty for the pages' items, else the body."))
            .property("fields", JsonSchema.array(JsonSchema.object()
                .required("path", JsonSchema.string().minLength(1).maxLength(255).title("From the row at")
                    .description("A dot path into each row's element: code.coding[0].code, valueQuantity.value, subject.reference."
                        + " One column's path may fan out (reaction[].name): one row per value, the other columns repeated."))
                .required("target", JsonSchema.string().minLength(1).maxLength(128).title("As column")))
                .maxItems(100).title("Columns")
                .description("When set, each row is these columns only, read from nested values; otherwise the element's own"
                    + " fields, nested values as JSON text."))
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
        JsonNode rows = rowsPath != null ? Values.all(result.body, rowsPath)
            : result.items != null && result.items.isArray() ? result.items : result.body;
        if (rows == null || rows.isMissingNode()) {
            throw new IllegalStateException(rowsPath == null ? "The API answered no body." : "The API's answer has nothing at " + rowsPath + ".");
        }
        rows = picked(rows, Configs.objects(config, "fields"));
        RowCollector collector = new RowCollector("The API's answer", Configs.integer(config, "maxRows", null));
        FileFormats.each(rows, collector);
        context.log(String.format("%d row(s) from request %s (HTTP %s)%s.", collector.size(), config.get("requestId"),
            result.statusCode == null ? "-" : result.statusCode.toString(), result.truncated ? "; the runner cut the answer short" : ""));
        if (result.truncated) {
            context.warn("integration-service truncated the answer; some rows may be missing.");
        }
        return StepResult.of(collector.toDataset());
    }

    /**
     * The rows as the named columns only, each read at its dot path in the row's element; the rows as they are without
     * any. One field's path may fan out ({@code patient.reaction[].name}): the element is then one row per value found,
     * the other columns repeated on each (one row with that column empty when it finds none).
     */
    static JsonNode picked(JsonNode rows, List<Map<String, Object>> fields) {
        if (fields.isEmpty() || rows == null || rows.isNull()) {
            return rows;
        }
        String fanOut = null;
        for (Map<String, Object> field : fields) {
            String path = Configs.text(field, "path", "");
            if (path.contains("[]") || path.contains("[*]")) {
                if (fanOut != null) {
                    throw new IllegalArgumentException("Only one column's path may fan out with []: " + fanOut + " and " + path + ".");
                }
                fanOut = path;
            }
        }
        ArrayNode out = Values.JSON.createArrayNode();
        for (JsonNode element : rows.isArray() ? rows : Values.JSON.createArrayNode().add(rows)) {
            JsonNode many = fanOut == null ? null : Values.all(element, fanOut);
            int copies = many == null || !many.isArray() || many.size() == 0 ? 1 : many.size();
            for (int i = 0; i < copies; i++) {
                ObjectNode row = out.addObject();
                for (Map<String, Object> field : fields) {
                    String path = Configs.text(field, "path", "");
                    JsonNode value = path.equals(fanOut) ? (many != null && many.isArray() && many.size() > i ? many.get(i) : null)
                        : Values.at(element, path);
                    row.set(Configs.text(field, "target", ""), value == null || value.isMissingNode() ? NullNode.getInstance() : value);
                }
            }
        }
        return out;
    }
}

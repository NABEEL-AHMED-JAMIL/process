package process.pipeline.tasks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import process.ai.AiPort;
import process.pipeline.Dataset;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.backing.BucketStore;
import process.pipeline.data.Limits;
import process.pipeline.data.Values;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI prompt (MIG-245): runs a saved prompt once per input row through ai-service, with the prompt's variables filled from
 * the row ({{column}}, and the run's placeholders). With an image column, each row's image is read from the workspace's
 * bucket and sent with the prompt to a vision model (gemma3:4b, llava:7b...). The answer lands in a column, and fields of
 * a JSON answer in columns of their own. The model and its key stay in ai-service: Core sends the prompt id, never a key.
 *
 * Each row is its own run in ai-service (step#row), so a retried run reuses the answers it already has. At most
 * {@code maxCalls} rows (default 50, at most {@value Limits#MAX_CALLS}); an input with more fails before any call. A row
 * whose call fails, or whose image cannot be read: {@code fail} (the default) fails the step, {@code skip} drops the row,
 * {@code null} keeps it with the answer empty. Images are capped at {@value #MAX_IMAGE_BYTES} bytes each.
 *
 * The model: the prompt's, unless the run asks for another (MIG-242's "Run with a different AI model...", else the
 * schedule's setting) -- sent as the model profile with the job's source task, exactly as the old pipeline's AI steps do.
 */
@Component
public class AiPromptStepTask extends RegisteredTask {

    static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;

    static final String DEFAULT_OUTPUT = "ai_output";

    private static final Map<String, String> IMAGE_TYPES = new LinkedHashMap<>();

    static {
        IMAGE_TYPES.put("png", "image/png");
        IMAGE_TYPES.put("jpg", "image/jpeg");
        IMAGE_TYPES.put("jpeg", "image/jpeg");
        IMAGE_TYPES.put("webp", "image/webp");
        IMAGE_TYPES.put("gif", "image/gif");
    }

    static final TaskSpec SPEC = TaskSpec.builder("ai_prompt", "AI prompt", TaskKind.PROCESS)
        .description("Runs a saved AI prompt per row (ai-service), optionally with the row's image, and adds the answer as columns.")
        .input(TaskSpec.rows("The rows to run the prompt on; their columns fill the prompt's variables."))
        .output(TaskSpec.rows("The input's columns, then the answer and each field taken from a JSON answer."))
        .config(JsonSchema.object()
            .required("promptId", JsonSchema.integer().minimum(1).format("prompt").title("Prompt")
                .description("A saved prompt in this workspace (AI > Prompts). Its model connection runs it."))
            .property("values", JsonSchema.map(JsonSchema.string().format("template")).title("Variables")
                .description("The prompt's variables by name, filled from the row: {{patient_id}}."))
            .property("image", JsonSchema.object()
                .required("bucket", JsonSchema.string().minLength(1).format("bucket").title("Bucket"))
                .required("keyColumn", JsonSchema.string().minLength(1).format("column").title("Image key column")
                    .description("The column holding each row's image key in that bucket (png, jpg, webp or gif)."))
                .title("Image").description("Send each row's image to a vision model with the prompt."))
            .property("outputColumn", JsonSchema.string().minLength(1).maxLength(128).title("Answer column").defaultValue(DEFAULT_OUTPUT))
            .property("fields", JsonSchema.array(JsonSchema.object()
                .required("path", JsonSchema.string().minLength(1).maxLength(255).title("From the answer at")
                    .description("A dot path into a JSON answer: stage, or wound.length_cm."))
                .required("target", JsonSchema.string().minLength(1).maxLength(128).title("As column")))
                .maxItems(50).title("Fields"))
            .property("onError", JsonSchema.string().enumOf("fail", "skip", "null").title("When a row fails").defaultValue("fail")
                .description("fail the step, skip the row, or keep it with the answer empty."))
            .property("maxCalls", JsonSchema.integer().minimum(1).maximum(Limits.MAX_CALLS).title("At most rows").defaultValue(50)))
        .backing(TaskSpec.AI)
        .timeoutSeconds(3600)
        .aiToolName("ai_prompt_rows")
        .build();

    private final AiPort ai;
    private final BucketStore buckets;
    private final ObjectMapper json = new ObjectMapper();

    public AiPromptStepTask(AiPort ai, BucketStore buckets) {
        super(SPEC);
        this.ai = ai;
        this.buckets = buckets;
    }

    @Override
    public StepResult run(StepContext context) throws Exception {
        Map<String, Object> config = context.config();
        Long promptId = Configs.longValue(config, "promptId");
        Map<String, String> templates = Configs.textMap(config, "values");
        @SuppressWarnings("unchecked")
        Map<String, Object> image = config.get("image") instanceof Map ? (Map<String, Object>) config.get("image") : null;
        String outputColumn = Configs.text(config, "outputColumn", DEFAULT_OUTPUT);
        List<Map<String, Object>> fields = Configs.objects(config, "fields");
        String onError = Configs.text(config, "onError", "fail");
        int maxCalls = Configs.integer(config, "maxCalls", 50);
        Dataset input = context.input();
        if (input.size() > maxCalls) {
            throw new IllegalStateException(String.format("The input needs %d calls; this step makes at most %d (maxCalls).", input.size(), maxCalls));
        }
        if (image != null) {
            String why = this.buckets.unavailable().orElse(null);
            if (why != null) {
                throw new IllegalStateException("Images cannot be read: " + why);
            }
        }
        Map<String, Object> runValues = Templates.ofRun(context, input.size());
        if (context.modelProfile() != null) {
            // "Run with a different AI model..." or the schedule's setting (MIG-242): ai-service checks the option is one this
            // step may run on, in this workspace, and refuses the row (422) otherwise -- which onError then handles.
            context.log(String.format("Asked to run on model option %s (from the %s).", context.modelProfile(), context.modelProfileSource()));
        }

        Set<String> columns = new LinkedHashSet<>(input.getColumns());
        columns.add(outputColumn);
        for (Map<String, Object> field : fields) {
            columns.add(Configs.text(field, "target", ""));
        }
        Limits.requireShape(input.size(), columns.size(), "The answered rows");

        List<Map<String, Object>> rows = new ArrayList<>(input.size());
        int failed = 0;
        int skipped = 0;
        String model = null;
        for (int i = 0; i < input.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("The AI step was stopped.");
            }
            Map<String, Object> row = input.getRows().get(i);
            String answer;
            try {
                Map<String, Object> values = new HashMap<>(runValues);
                values.putAll(row);
                Map<String, String> variables = new LinkedHashMap<>();
                templates.forEach((name, template) -> variables.put(name, Templates.fill(template, values)));
                List<AiPort.Image> images = image == null ? null : Collections.singletonList(this.imageOf(context, image, row));
                AiPort.StepResult result = this.ai.runRowStep(context.tenantId(), context.jobQueueId(), context.stepKey(),
                    String.valueOf(i + 1), promptId, variables, images, context.modelProfile(), context.sourceTaskId());
                if (!result.ok()) {
                    throw new IllegalStateException("Row " + (i + 1) + ": " + (result.error == null ? "the AI step failed." : result.error));
                }
                answer = result.output;
                model = result.model == null ? model : result.model;
            } catch (InterruptedException stopped) {
                throw stopped;
            } catch (Exception rowFailed) {
                if ("fail".equals(onError)) {
                    throw rowFailed;
                }
                failed++;
                if ("skip".equals(onError)) {
                    skipped++;
                    continue;
                }
                answer = null;
            }
            Map<String, Object> out = new LinkedHashMap<>(row);
            out.put(outputColumn, answer);
            JsonNode parsed = fields.isEmpty() || answer == null ? null : this.parsed(answer);
            for (Map<String, Object> field : fields) {
                out.put(Configs.text(field, "target", ""), parsed == null ? null : Values.scalar(Values.at(parsed, Configs.text(field, "path", ""))));
            }
            rows.add(out);
        }
        context.log(String.format("%d row(s) answered%s; %d row(s) out.", input.size() - failed, model == null ? "" : " on " + model, rows.size()));
        if (failed > 0) {
            context.warn(String.format("%d row(s) failed: %s.", failed, "skip".equals(onError) ? skipped + " row(s) skipped" : "their answer is empty"));
        }
        return StepResult.of(new Dataset(new ArrayList<>(columns), rows));
    }

    /** The row's image from the bucket, as the model reads it; a row without one is an error for the row. */
    private AiPort.Image imageOf(StepContext context, Map<String, Object> image, Map<String, Object> row) throws Exception {
        Object key = row.get(Configs.text(image, "keyColumn", ""));
        String objectKey = key == null ? "" : key.toString().trim();
        if (objectKey.isEmpty()) {
            throw new IllegalStateException("The row has no image key.");
        }
        int dot = objectKey.lastIndexOf('.');
        String type = dot < 0 ? null : IMAGE_TYPES.get(objectKey.substring(dot + 1).toLowerCase(Locale.ROOT));
        if (type == null) {
            throw new IllegalStateException("\"" + objectKey + "\" is not an image (png, jpg, webp or gif).");
        }
        byte[] bytes = this.buckets.read(context.tenantId(), Configs.text(image, "bucket", ""), objectKey, MAX_IMAGE_BYTES);
        return new AiPort.Image(type, Base64.getEncoder().encodeToString(bytes));
    }

    /** A JSON answer (a model may still wrap it in a code fence); null when it is not JSON. */
    private JsonNode parsed(String answer) {
        String text = answer.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
        }
        try {
            JsonNode node = this.json.readTree(text);
            return node != null && node.isContainerNode() ? node : null;
        } catch (Exception notJson) {
            return null;
        }
    }
}

package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.backing.BucketStore;
import process.pipeline.data.Limits;
import process.pipeline.image.WoundMeasure;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Measure image (MIG-255): measures the wound in each row's photo, read from the workspace's bucket, against the ruler
 * in the photo (see {@link WoundMeasure}). Owner decision 2026-09-29: a wound's size is measured in code, never guessed
 * by a model -- a local vision model read the synthetic photos 40-68% too large -- so the AI step describes the wound
 * and this step sizes it. A photo without a ruler is measured as nothing: scale_found false and no sizes (MIG-221).
 *
 * At most {@code maxRows} rows (default 50, at most {@value Limits#MAX_CALLS}); an input with more fails before any
 * read. A photo that cannot be read or is not a png or jpg: {@code null} (the default) keeps the row with empty sizes
 * and the reason in the note, {@code skip} drops it, {@code fail} fails the step. Photos are capped at
 * {@value AiPromptStepTask#MAX_IMAGE_BYTES} bytes each.
 */
@Component
public class MeasureImageStepTask extends RegisteredTask {

    private static final Set<String> IMAGE_TYPES = new HashSet<>(Arrays.asList("png", "jpg", "jpeg"));

    /** Each output's config key and its default column, in output order. */
    private static final String[][] OUTPUTS = {
        {"scaleColumn", "scale_found"}, {"pxPerCmColumn", "px_per_cm"}, {"lengthColumn", "length_cm"},
        {"widthColumn", "width_cm"}, {"areaColumn", "area_cm2"}, {"noteColumn", "measure_note"}};

    static final TaskSpec SPEC = TaskSpec.builder("measure_image", "Measure image", TaskKind.PROCESS)
        .description("Measures the wound in each row's photo against the ruler in it: length, width and area in cm, in code.")
        .input(TaskSpec.rows("The rows whose photos to measure; a column holds each photo's key in the bucket."))
        .output(TaskSpec.rows("The input's columns, then whether a ruler was found, its scale, the wound's length, width and area, and a note."))
        .config(JsonSchema.object()
            .required("image", JsonSchema.object()
                .required("bucket", JsonSchema.string().minLength(1).format("bucket").title("Bucket"))
                .required("keyColumn", JsonSchema.string().minLength(1).format("column").title("Image key column")
                    .description("The column holding each row's photo key in that bucket (png or jpg)."))
                .title("Image").description("Each row's photo, with a ruler (1 cm ticks) beside the wound."))
            .property("scaleColumn", JsonSchema.string().minLength(1).maxLength(128).title("Ruler found column").defaultValue("scale_found"))
            .property("pxPerCmColumn", JsonSchema.string().minLength(1).maxLength(128).title("Scale column (px/cm)").defaultValue("px_per_cm"))
            .property("lengthColumn", JsonSchema.string().minLength(1).maxLength(128).title("Length column (cm)").defaultValue("length_cm"))
            .property("widthColumn", JsonSchema.string().minLength(1).maxLength(128).title("Width column (cm)").defaultValue("width_cm"))
            .property("areaColumn", JsonSchema.string().minLength(1).maxLength(128).title("Area column (cm2)").defaultValue("area_cm2"))
            .property("noteColumn", JsonSchema.string().minLength(1).maxLength(128).title("Note column").defaultValue("measure_note"))
            .property("onError", JsonSchema.string().enumOf("fail", "skip", "null").title("When a photo cannot be read").defaultValue("null")
                .description("fail the step, skip the row, or keep it with the sizes empty and the reason in the note."))
            .property("maxRows", JsonSchema.integer().minimum(1).maximum(Limits.MAX_CALLS).title("At most rows").defaultValue(50)))
        .backing(TaskSpec.CORE)
        .aiToolName("measure_wound_images")
        .build();

    private final BucketStore buckets;

    public MeasureImageStepTask(BucketStore buckets) {
        super(SPEC);
        this.buckets = buckets;
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        List<DefinitionProblem> problems = new ArrayList<>();
        Set<String> columns = new HashSet<>();
        for (String[] output : OUTPUTS) {
            String column = Configs.text(config, output[0], output[1]);
            if (!columns.add(column)) {
                problems.add(new DefinitionProblem(output[0], String.format("'%s' is already an output column", column)));
            }
        }
        return problems;
    }

    @Override
    public StepResult run(StepContext context) throws Exception {
        Map<String, Object> config = context.config();
        @SuppressWarnings("unchecked")
        Map<String, Object> image = config.get("image") instanceof Map ? (Map<String, Object>) config.get("image") : new LinkedHashMap<>();
        String onError = Configs.text(config, "onError", "null");
        int maxRows = Configs.integer(config, "maxRows", 50);
        String[] names = new String[OUTPUTS.length];
        for (int k = 0; k < OUTPUTS.length; k++) {
            names[k] = Configs.text(config, OUTPUTS[k][0], OUTPUTS[k][1]);
        }
        Dataset input = context.input();
        if (input.size() > maxRows) {
            throw new IllegalStateException(String.format("The input has %d rows; this step measures at most %d (maxRows).", input.size(), maxRows));
        }
        String why = this.buckets.unavailable().orElse(null);
        if (why != null) {
            throw new IllegalStateException("Images cannot be read: " + why);
        }
        Set<String> columns = new LinkedHashSet<>(input.getColumns());
        columns.addAll(Arrays.asList(names));
        Limits.requireShape(input.size(), columns.size(), "The measured rows");

        List<Map<String, Object>> rows = new ArrayList<>(input.size());
        int measured = 0;
        int noScale = 0;
        int failed = 0;
        for (int i = 0; i < input.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("The measure step was stopped.");
            }
            Map<String, Object> row = input.getRows().get(i);
            Map<String, Object> out = new LinkedHashMap<>(row);
            try {
                WoundMeasure.Measurement m = WoundMeasure.measure(this.photo(context, image, row));
                Object[] values = {m.scaleFound, m.pxPerCm, m.lengthCm, m.widthCm, m.areaCm2, m.note};
                for (int k = 0; k < names.length; k++) {
                    out.put(names[k], values[k]);
                }
                measured += m.lengthCm == null ? 0 : 1;
                noScale += m.scaleFound ? 0 : 1;
            } catch (Exception unreadable) {
                if ("fail".equals(onError)) {
                    throw new IllegalStateException("Row " + (i + 1) + ": " + unreadable.getMessage(), unreadable);
                }
                failed++;
                if ("skip".equals(onError)) {
                    continue;
                }
                for (int k = 0; k < names.length - 1; k++) {
                    out.put(names[k], null);
                }
                out.put(names[names.length - 1], "not measured: " + unreadable.getMessage());
            }
            rows.add(out);
        }
        context.log(String.format("%d photo(s) measured, %d without a ruler; %d row(s) out.", measured, noScale, rows.size()));
        if (failed > 0) {
            context.warn(String.format("%d photo(s) could not be read: %s.", failed, "skip".equals(onError) ? "their rows skipped" : "their sizes are empty"));
        }
        return StepResult.of(new Dataset(new ArrayList<>(columns), rows));
    }

    /** The row's photo from the bucket; a row without a png or jpg key is an error for the row. */
    private byte[] photo(StepContext context, Map<String, Object> image, Map<String, Object> row) throws Exception {
        Object key = row.get(Configs.text(image, "keyColumn", ""));
        String objectKey = key == null ? "" : key.toString().trim();
        if (objectKey.isEmpty()) {
            throw new IllegalStateException("The row has no image key.");
        }
        int dot = objectKey.lastIndexOf('.');
        if (dot < 0 || !IMAGE_TYPES.contains(objectKey.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("\"" + objectKey + "\" is not a png or jpg image.");
        }
        return this.buckets.read(context.tenantId(), Configs.text(image, "bucket", ""), objectKey, AiPromptStepTask.MAX_IMAGE_BYTES);
    }
}

package process.pipeline.tasks;

import org.springframework.stereotype.Component;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.StepContext;
import process.pipeline.StepResult;
import process.pipeline.backing.BucketStore;
import process.pipeline.data.Limits;
import process.pipeline.image.MaskMeasure;
import process.pipeline.registry.JsonSchema;
import process.pipeline.registry.TaskKind;
import process.pipeline.registry.TaskSpec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Measure mask (2026-10-19): measures the region each row's segmentation mask marks, in code and in pixels -- the
 * marked area, its share of the image, its bounding box, and how many separate regions there are (see
 * {@link MaskMeasure}). A mask is an image the size of its photo, the marked pixels light on a dark background, as public
 * imaging datasets publish an expert's outline of a lesion or an organ.
 *
 * <p>Pixels only: a mask has no scale, so the step never answers a size in cm or mm (a photo with a ruler is
 * {@code measure_image}'s). As there, a size is measured in code and never guessed by a model; an AI step may describe the
 * photo while this step sizes what the mask marks. An empty mask is a measurement too: 0 px, no box, 0 regions.
 *
 * <p>The columns are {@link #COLUMNS}, each with {@code prefix} before it when one is set (two masks per row, say
 * {@code lesion_} and {@code organ_}). At most {@code maxRows} rows (default 50, at most {@value Limits#MAX_CALLS}); a mask
 * that cannot be read or is not a png or jpg: {@code null} (the default) keeps the row with empty sizes and the reason in
 * the note, {@code skip} drops it, {@code fail} fails the step. Masks are capped at
 * {@value AiPromptStepTask#MAX_IMAGE_BYTES} bytes and 12,000,000 pixels each.
 */
@Component
public class MeasureMaskStepTask extends RegisteredTask {

    public static final String CODE = "measure_mask";

    /** The output columns, in order (each after the prefix). */
    public static final List<String> COLUMNS = Collections.unmodifiableList(Arrays.asList("mask_width", "mask_height", "area_px",
        "area_pct", "box_x", "box_y", "box_width", "box_height", "regions", "largest_region_px", "measure_note"));

    private static final Set<String> IMAGE_TYPES = new HashSet<>(Arrays.asList("png", "jpg", "jpeg"));

    static final TaskSpec SPEC = TaskSpec.builder(CODE, "Measure mask", TaskKind.PROCESS)
        .description("Measures the region each row's segmentation mask marks, in pixels and in code: area, share of the image, "
            + "bounding box and region count. A mask has no scale, so no cm or mm.")
        .input(TaskSpec.rows("The rows whose masks to measure; a column holds each mask's key in the bucket."))
        .output(TaskSpec.rows("The input's columns, then the mask's width and height, the marked area (px and % of the image), its "
            + "bounding box, how many regions and the largest one's area, and a note."))
        .config(JsonSchema.object()
            .required("mask", JsonSchema.object()
                .required("bucket", JsonSchema.string().minLength(1).format("bucket").title("Bucket"))
                .required("keyColumn", JsonSchema.string().minLength(1).format("column").title("Mask key column")
                    .description("The column holding each row's mask key in that bucket (png or jpg)."))
                .title("Mask").description("Each row's mask: the photo's size, the marked pixels light on a dark background."))
            .property("threshold", JsonSchema.integer().minimum(1).maximum(255).title("Marked from brightness").defaultValue(128)
                .description("A pixel at least this bright (0-255, the mean of red, green and blue) is marked."))
            .property("prefix", JsonSchema.string().maxLength(64).pattern("^[A-Za-z_][A-Za-z0-9_]*$|^$").title("Column prefix")
                .description("Put before every output column (lesion_ makes lesion_area_px); empty for none."))
            .property("onError", JsonSchema.string().enumOf("fail", "skip", "null").title("When a mask cannot be read").defaultValue("null")
                .description("fail the step, skip the row, or keep it with the sizes empty and the reason in the note."))
            .property("maxRows", JsonSchema.integer().minimum(1).maximum(Limits.MAX_CALLS).title("At most rows").defaultValue(50)))
        .backing(TaskSpec.CORE)
        .aiToolName("measure_masks")
        .build();

    private final BucketStore buckets;

    public MeasureMaskStepTask(BucketStore buckets) {
        super(SPEC);
        this.buckets = buckets;
    }

    @Override
    public List<DefinitionProblem> check(Map<String, Object> config) {
        String prefix = Configs.text(config, "prefix", "");
        if (prefix.length() + "largest_region_px".length() > 128) {
            return Collections.singletonList(new DefinitionProblem("prefix", "too long: a column name is at most 128 characters"));
        }
        return Collections.emptyList();
    }

    @Override
    public StepResult run(StepContext context) throws Exception {
        Map<String, Object> config = context.config();
        @SuppressWarnings("unchecked")
        Map<String, Object> mask = config.get("mask") instanceof Map ? (Map<String, Object>) config.get("mask") : new LinkedHashMap<>();
        String onError = Configs.text(config, "onError", "null");
        int maxRows = Configs.integer(config, "maxRows", 50);
        int threshold = Configs.integer(config, "threshold", 128);
        String prefix = Configs.text(config, "prefix", "");
        List<String> names = new ArrayList<>(COLUMNS.size());
        for (String column : COLUMNS) {
            names.add(prefix + column);
        }
        Dataset input = context.input();
        if (input.size() > maxRows) {
            throw new IllegalStateException(String.format("The input has %d rows; this step measures at most %d (maxRows).", input.size(), maxRows));
        }
        String why = this.buckets.unavailable().orElse(null);
        if (why != null) {
            throw new IllegalStateException("Masks cannot be read: " + why);
        }
        Set<String> columns = new LinkedHashSet<>(input.getColumns());
        columns.addAll(names);
        Limits.requireShape(input.size(), columns.size(), "The measured rows");

        List<Map<String, Object>> rows = new ArrayList<>(input.size());
        int marked = 0;
        int empty = 0;
        int failed = 0;
        for (int i = 0; i < input.size(); i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("The measure step was stopped.");
            }
            Map<String, Object> row = input.getRows().get(i);
            Map<String, Object> out = new LinkedHashMap<>(row);
            try {
                MaskMeasure.Measurement m = MaskMeasure.measure(this.mask(context, mask, row), threshold);
                Object[] values = {m.width, m.height, m.areaPx, m.areaPct, m.boxX, m.boxY, m.boxWidth, m.boxHeight, m.regions,
                    m.largestRegionPx, m.note};
                for (int k = 0; k < names.size(); k++) {
                    out.put(names.get(k), values[k]);
                }
                if (m.areaPx > 0) {
                    marked++;
                } else {
                    empty++;
                }
            } catch (Exception unreadable) {
                if ("fail".equals(onError)) {
                    throw new IllegalStateException("Row " + (i + 1) + ": " + unreadable.getMessage(), unreadable);
                }
                failed++;
                if ("skip".equals(onError)) {
                    continue;
                }
                for (int k = 0; k < names.size() - 1; k++) {
                    out.put(names.get(k), null);
                }
                out.put(names.get(names.size() - 1), "not measured: " + unreadable.getMessage());
            }
            rows.add(out);
        }
        context.log(String.format(Locale.ROOT, "%d mask(s) measured with a marked region, %d with nothing marked; %d row(s) out.",
            marked, empty, rows.size()));
        if (failed > 0) {
            context.warn(String.format(Locale.ROOT, "%d mask(s) could not be read: %s.", failed,
                "skip".equals(onError) ? "their rows skipped" : "their sizes are empty"));
        }
        return StepResult.of(new Dataset(new ArrayList<>(columns), rows));
    }

    /** The row's mask from the bucket; a row without a png or jpg key is an error for the row. */
    private byte[] mask(StepContext context, Map<String, Object> mask, Map<String, Object> row) throws Exception {
        Object key = row.get(Configs.text(mask, "keyColumn", ""));
        String objectKey = key == null ? "" : key.toString().trim();
        if (objectKey.isEmpty()) {
            throw new IllegalStateException("The row has no mask key.");
        }
        int dot = objectKey.lastIndexOf('.');
        if (dot < 0 || !IMAGE_TYPES.contains(objectKey.substring(dot + 1).toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("\"" + objectKey + "\" is not a png or jpg image.");
        }
        return this.buckets.read(context.tenantId(), Configs.text(mask, "bucket", ""), objectKey, AiPromptStepTask.MAX_IMAGE_BYTES);
    }
}

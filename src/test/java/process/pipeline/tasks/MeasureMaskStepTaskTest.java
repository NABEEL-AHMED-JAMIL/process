package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;
import process.pipeline.backing.Fakes;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/**
 * 2026-10-19: Measure mask reads each row's segmentation mask from the bucket and measures what it marks in pixels, in
 * code: area, share of the image, bounding box and region count. A mask has no scale, so nothing is in cm. An empty mask
 * is 0 px with no box; a mask that cannot be read keeps its row with a note unless the step says otherwise.
 */
class MeasureMaskStepTaskTest {

    private final Fakes.Buckets buckets = new Fakes.Buckets();
    private final MeasureMaskStepTask task = new MeasureMaskStepTask(this.buckets);

    /** A width x height mask, black, with white rectangles {x, y, w, h}; type is a BufferedImage type. */
    static byte[] mask(int width, int height, int type, int[]... boxes) throws IOException {
        BufferedImage image = new BufferedImage(width, height, type);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        for (int[] b : boxes) {
            g.fillRect(b[0], b[1], b[2], b[3]);
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static Map<String, Object> maskConfig() {
        return config("bucket", "scans", "keyColumn", "mask_key");
    }

    @Test
    void eachRowsMaskIsMeasuredInPixels() throws Exception {
        this.buckets.objects.put("scans/m/one.png", mask(200, 100, BufferedImage.TYPE_BYTE_GRAY, new int[] {20, 10, 40, 30}));
        this.buckets.objects.put("scans/m/two.png", mask(200, 100, BufferedImage.TYPE_BYTE_BINARY, new int[] {0, 0, 10, 10},
            new int[] {150, 80, 50, 20}));
        TaskContext context = TaskContext.of(config("mask", maskConfig()),
            Arrays.asList(row("image_id", "a", "mask_key", "m/one.png"), row("image_id", "b", "mask_key", "m/two.png")));

        Dataset out = this.task.run(context).getOutput();

        assertThat(out.getColumns()).containsExactly("image_id", "mask_key", "mask_width", "mask_height", "area_px", "area_pct", "box_x",
            "box_y", "box_width", "box_height", "regions", "largest_region_px", "measure_note");
        Map<String, Object> one = out.getRows().get(0);
        assertThat(one).containsEntry("mask_width", 200).containsEntry("mask_height", 100).containsEntry("area_px", 1200L)
            .containsEntry("area_pct", 6.0).containsEntry("box_x", 20).containsEntry("box_y", 10).containsEntry("box_width", 40)
            .containsEntry("box_height", 30).containsEntry("regions", 1).containsEntry("largest_region_px", 1200L);
        assertThat((String) one.get("measure_note")).contains("1,200 px marked").contains("no scale");
        Map<String, Object> two = out.getRows().get(1);
        assertThat(two).containsEntry("area_px", 1100L).containsEntry("area_pct", 5.5).containsEntry("box_x", 0).containsEntry("box_y", 0)
            .containsEntry("box_width", 200).containsEntry("box_height", 100).containsEntry("regions", 2).containsEntry("largest_region_px", 1000L);
        assertThat(this.buckets.lastTenant).isEqualTo(TaskContext.TENANT);
    }

    @Test
    void anEmptyMaskIsZeroPixelsWithNoBox() throws Exception {
        this.buckets.objects.put("scans/empty.png", mask(64, 48, BufferedImage.TYPE_INT_RGB));
        Dataset out = this.task.run(TaskContext.of(config("mask", maskConfig()), Collections.singletonList(row("mask_key", "empty.png"))))
            .getOutput();

        assertThat(out.getRows().get(0)).containsEntry("area_px", 0L).containsEntry("area_pct", 0.0).containsEntry("box_x", null)
            .containsEntry("box_width", null).containsEntry("regions", 0).containsEntry("measure_note", "mask 64 x 48 px: nothing marked");
    }

    @Test
    void aPrefixNamesEveryColumnAndTheThresholdDecidesWhatIsMarked() throws Exception {
        BufferedImage grey = new BufferedImage(10, 10, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = grey.createGraphics();
        g.setColor(new Color(100, 100, 100));
        g.fillRect(0, 0, 5, 10);
        g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(grey, "png", png);
        this.buckets.objects.put("scans/grey.png", png.toByteArray());

        Dataset high = this.task.run(TaskContext.of(config("mask", maskConfig(), "prefix", "lesion_"),
            Collections.singletonList(row("mask_key", "grey.png")))).getOutput();
        Dataset low = this.task.run(TaskContext.of(config("mask", maskConfig(), "threshold", 90),
            Collections.singletonList(row("mask_key", "grey.png")))).getOutput();

        assertThat(high.getColumns()).contains("lesion_area_px", "lesion_box_x", "lesion_measure_note").doesNotContain("area_px");
        assertThat(high.getRows().get(0)).containsEntry("lesion_area_px", 0L);
        assertThat(low.getRows().get(0)).containsEntry("area_px", 50L).containsEntry("area_pct", 50.0);
    }

    @Test
    void anUnreadableMaskKeepsItsRowWithANoteOrSkipsOrFails() throws Exception {
        this.buckets.objects.put("scans/bad.png", "not an image".getBytes(StandardCharsets.UTF_8));
        TaskContext keep = TaskContext.of(config("mask", maskConfig()),
            Arrays.asList(row("mask_key", "bad.png"), row("mask_key", "notes.txt"), row("mask_key", "")));

        Dataset kept = this.task.run(keep).getOutput();
        assertThat(kept.getRows()).hasSize(3);
        assertThat(kept.getRows().get(0)).containsEntry("area_px", null)
            .containsEntry("measure_note", "not measured: The file is not a png or jpg image.");
        assertThat((String) kept.getRows().get(1).get("measure_note")).contains("is not a png or jpg image");
        assertThat((String) kept.getRows().get(2).get("measure_note")).isEqualTo("not measured: The row has no mask key.");

        Dataset skipped = this.task.run(TaskContext.of(config("mask", maskConfig(), "onError", "skip"),
            Collections.singletonList(row("mask_key", "bad.png")))).getOutput();
        assertThat(skipped.getRows()).isEmpty();
        assertThatThrownBy(() -> this.task.run(TaskContext.of(config("mask", maskConfig(), "onError", "fail"),
            Collections.singletonList(row("mask_key", "bad.png"))))).hasMessageContaining("Row 1");
    }

    @Test
    void moreRowsThanMaxRowsFailBeforeAnyRead() {
        TaskContext context = TaskContext.of(config("mask", maskConfig(), "maxRows", 1),
            Arrays.asList(row("mask_key", "a.png"), row("mask_key", "b.png")));
        assertThatThrownBy(() -> this.task.run(context)).hasMessageContaining("at most 1 (maxRows)");
    }

    @Test
    void aMaskTooLargeIsRefusedBeforeItsPixelsAreRead() throws Exception {
        // A png compresses a black field to almost nothing: the size, not the bytes, is the limit.
        this.buckets.objects.put("scans/huge.png", mask(4000, 3001, BufferedImage.TYPE_BYTE_BINARY));
        Dataset out = this.task.run(TaskContext.of(config("mask", maskConfig()), Collections.singletonList(row("mask_key", "huge.png"))))
            .getOutput();
        assertThat((String) out.getRows().get(0).get("measure_note")).contains("4000 x 3001 pixels; at most 12,000,000");
    }
}

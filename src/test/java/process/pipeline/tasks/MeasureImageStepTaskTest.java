package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.backing.Fakes;
import process.pipeline.image.SyntheticWounds;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static process.pipeline.Definitions.config;
import static process.pipeline.Definitions.row;

/**
 * MIG-255: Measure image reads each row's photo from the workspace's bucket and measures the wound in code against the
 * ruler in the photo -- the sizes a model guessed were 40-68% too large. No ruler, no sizes; a photo that cannot be read
 * keeps its row with empty sizes and a note unless the step says otherwise.
 */
class MeasureImageStepTaskTest {

    private final Fakes.Buckets buckets = new Fakes.Buckets();
    private final MeasureImageStepTask task = new MeasureImageStepTask(this.buckets);

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = MeasureImageStepTaskTest.class.getResourceAsStream("/e2e/wound/" + name)) {
            byte[] bytes = new byte[in.available()];
            int read = 0;
            while (read < bytes.length) {
                read += in.read(bytes, read, bytes.length - read);
            }
            return bytes;
        }
    }

    private static Map<String, Object> image() {
        return config("bucket", "wounds", "keyColumn", "image_key");
    }

    @Test
    void eachRowsPhotoIsMeasuredIntoSizeColumns() throws Exception {
        this.buckets.objects.put("wounds/in/WC-0001.png", resource("WC-0001.png"));
        this.buckets.objects.put("wounds/in/WC-0003.png", resource("WC-0003.png"));
        TaskContext context = TaskContext.of(config("image", image()),
            Arrays.asList(row("case_id", "WC-0001", "image_key", "in/WC-0001.png"), row("case_id", "WC-0003", "image_key", "in/WC-0003.png")));

        Dataset out = this.task.run(context).getOutput();

        assertThat(out.getColumns()).containsExactly("case_id", "image_key", "scale_found", "px_per_cm", "length_cm", "width_cm",
            "area_cm2", "measure_note");
        Map<String, Object> measured = out.getRows().get(0);
        assertThat(measured).containsEntry("scale_found", true);
        assertThat((Double) measured.get("px_per_cm")).isCloseTo(40.0, within(1.2));
        assertThat((Double) measured.get("length_cm")).isCloseTo(4.0, within(0.4));
        assertThat((Double) measured.get("width_cm")).isCloseTo(2.67, within(0.27));
        assertThat((Double) measured.get("area_cm2")).isCloseTo(8.38, within(1.26));
        assertThat((String) measured.get("measure_note")).startsWith("ruler ");
        Map<String, Object> noRuler = out.getRows().get(1);
        assertThat(noRuler).containsEntry("scale_found", false).containsEntry("length_cm", null).containsEntry("width_cm", null)
            .containsEntry("area_cm2", null).containsEntry("px_per_cm", null).containsEntry("measure_note", "no ruler found: not measured");
        assertThat(this.buckets.lastTenant).isEqualTo(TaskContext.TENANT);
    }

    @Test
    void theOutputColumnsCanBeRenamed() throws Exception {
        SyntheticWounds photo = SyntheticWounds.photo(800, 600, 30).ellipse(5.0, 3.0, 500, 220, 35).ruler(40, 500, 10).noise(12);
        this.buckets.objects.put("wounds/p1.jpg", photo.png());
        TaskContext context = TaskContext.of(config("image", image(), "lengthColumn", "wound_length", "widthColumn", "wound_width",
            "areaColumn", "wound_area", "scaleColumn", "has_ruler", "pxPerCmColumn", "scale", "noteColumn", "how"),
            Arrays.asList(row("image_key", "p1.jpg")));

        Dataset out = this.task.run(context).getOutput();

        assertThat(out.getColumns()).containsExactly("image_key", "has_ruler", "scale", "wound_length", "wound_width", "wound_area", "how");
        assertThat((Double) out.getRows().get(0).get("wound_length")).isCloseTo(5.0, within(0.5));
        assertThat((Double) out.getRows().get(0).get("wound_width")).isCloseTo(3.0, within(0.3));
    }

    /** By default a photo that cannot be measured keeps its row, empty, with the reason in the note. */
    @Test
    void aPhotoThatCannotBeReadKeepsItsRowEmptyByDefault() throws Exception {
        this.buckets.objects.put("wounds/broken.png", "not a picture".getBytes(StandardCharsets.UTF_8));
        List<Map<String, Object>> rows = Arrays.asList(row("image_key", "broken.png"), row("image_key", "notes.pdf"),
            row("image_key", ""), row("image_key", "missing.jpg"));

        Dataset out = this.task.run(TaskContext.of(config("image", image()), rows)).getOutput();

        assertThat(out.getRows()).hasSize(4);
        assertThat(out.getRows()).allSatisfy(r -> assertThat(r).containsEntry("scale_found", null).containsEntry("length_cm", null));
        assertThat(out.getRows().get(0).get("measure_note")).isEqualTo("not measured: The file is not a png or jpg image.");
        assertThat(out.getRows().get(1).get("measure_note")).isEqualTo("not measured: \"notes.pdf\" is not a png or jpg image.");
        assertThat(out.getRows().get(2).get("measure_note")).isEqualTo("not measured: The row has no image key.");
        assertThat(out.getRows().get(3).get("measure_note")).isEqualTo("not measured: No object at wounds/missing.jpg.");
    }

    @Test
    void aPhotoThatCannotBeReadFailsTheStepOrSkipsTheRowWhenAsked() throws Exception {
        this.buckets.objects.put("wounds/in/WC-0002.png", resource("WC-0002.png"));
        List<Map<String, Object>> rows = Arrays.asList(row("image_key", "in/WC-0002.png"), row("image_key", "missing.jpg"));

        assertThatThrownBy(() -> this.task.run(TaskContext.of(config("image", image(), "onError", "fail"), rows)))
            .hasMessage("Row 2: No object at wounds/missing.jpg.");
        Dataset skipped = this.task.run(TaskContext.of(config("image", image(), "onError", "skip"), rows)).getOutput();
        assertThat(skipped.getRows()).hasSize(1);
        assertThat((Double) skipped.getRows().get(0).get("length_cm")).isCloseTo(2.5, within(0.25));
    }

    @Test
    void tooManyRowsFailBeforeAnyRead() {
        TaskContext context = TaskContext.of(config("image", image(), "maxRows", 1),
            Arrays.asList(row("image_key", "a.png"), row("image_key", "b.png")));

        assertThatThrownBy(() -> this.task.run(context)).hasMessage("The input has 2 rows; this step measures at most 1 (maxRows).");
        assertThat(this.buckets.lastTenant).isZero();
    }

    @Test
    void anUnavailableStoreFailsTheStep() {
        this.buckets.unavailable = "storage-service is not configured";

        assertThatThrownBy(() -> this.task.run(TaskContext.of(config("image", image()), Arrays.asList(row("image_key", "a.png")))))
            .hasMessage("Images cannot be read: storage-service is not configured");
    }

    @Test
    void twoOutputsCannotShareAColumn() {
        List<DefinitionProblem> problems = this.task.check(config("image", image(), "lengthColumn", "size", "widthColumn", "size"));

        assertThat(problems).containsExactly(new DefinitionProblem("widthColumn", "'size' is already an output column"));
    }
}

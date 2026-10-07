package process.pipeline.tasks;

import org.junit.jupiter.api.Test;
import process.pipeline.Dataset;
import process.pipeline.DefinitionProblem;
import process.pipeline.PipelineDefinition;
import process.pipeline.backing.Fakes;
import process.pipeline.image.SyntheticPhotos;

import java.awt.Color;
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
import static process.pipeline.Definitions.step;

/**
 * MIG-255: Measure image reads each row's photo from the workspace's bucket and measures the object in code against the
 * ruler in the photo -- the sizes a model guessed were 40-68% too large. No ruler, no sizes; a photo that cannot be read
 * keeps its row with empty sizes and a note unless the step says otherwise. 2026-10-06: the target says what the object
 * is (contrast, the default for a new step, or red_region, read also under its former word red_on_skin); a step saved without one measures red_region, as every
 * step did before -- the e2e wound tests below run without one on purpose.
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
        SyntheticPhotos photo = SyntheticPhotos.photo(800, 600, 30).ellipse(5.0, 3.0, 500, 220, 35).ruler(40, 500, 10).noise(12);
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
    void aContrastStepMeasuresAnyObject() throws Exception {
        SyntheticPhotos photo = SyntheticPhotos.photo(800, 600, 30).on(new Color(128, 128, 128)).object(new Color(45, 75, 170))
            .ellipse(6.0, 3.5, 450, 230, 40).ruler(40, 500, 10).noise(12);
        this.buckets.objects.put("wounds/blue.png", photo.png());
        TaskContext context = TaskContext.of(config("image", image(), "target", "contrast"), Arrays.asList(row("image_key", "blue.png")));

        Map<String, Object> out = this.task.run(context).getOutput().getRows().get(0);

        assertThat((Double) out.get("length_cm")).isCloseTo(6.0, within(0.6));
        assertThat((Double) out.get("width_cm")).isCloseTo(3.5, within(0.35));
        assertThat((String) out.get("measure_note")).matches("ruler \\d+ ticks, 30\\.\\d px/cm; region [\\d,]+ px");
        assertThat(context.lines).anyMatch(line -> line.contains("1 photo(s) measured (contrast)"));
    }

    /** A step saved before targets existed has none: it measures red_region, exactly as it did (the ring left out). */
    @Test
    void aStepWithoutATargetMeasuresRedOnSkinAsBefore() throws Exception {
        SyntheticPhotos photo = SyntheticPhotos.photo(640, 480, 40).ellipse(4.0, 2.67, 320, 220, 0).periwound().ruler(60, 400, 8).noise(12);
        this.buckets.objects.put("wounds/w.png", photo.png());
        List<Map<String, Object>> rows = Arrays.asList(row("image_key", "w.png"));

        Map<String, Object> none = this.task.run(TaskContext.of(config("image", image()), rows)).getOutput().getRows().get(0);
        Map<String, Object> red = this.task.run(TaskContext.of(config("image", image(), "target", "red_on_skin"), rows)).getOutput().getRows().get(0);
        Map<String, Object> contrast = this.task.run(TaskContext.of(config("image", image(), "target", "contrast"), rows)).getOutput().getRows().get(0);

        assertThat(none).isEqualTo(red);
        assertThat((Double) none.get("length_cm")).isCloseTo(4.0, within(0.4));
        assertThat((Double) contrast.get("length_cm")).as("contrast takes the ring too").isGreaterThan((Double) none.get("length_cm") + 0.3);
    }

    @Test
    void theTargetIsAChoiceWhoseDefaultIsContrast() {
        Map<String, Object> target = property("target");

        assertThat(target).containsEntry("default", "contrast").containsEntry("title", "What to measure");
        assertThat(target.get("enum")).isEqualTo(Arrays.asList("contrast", "red_region"));
        assertThat(this.task.spec().aiToolName()).isEqualTo("measure_images");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> property(String name) {
        Map<String, Object> schema = this.task.spec().configSchema();
        return (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get(name);
    }

    /** A save writes the target: red_region for a step the previous version had without one, contrast for a new step. */
    @Test
    void aSavePinsEachMeasureStepsTarget() {
        PipelineDefinition.Step old = step("size", "measure_image", config("image", image()));
        PipelineDefinition.Step renamed = step("size", "measure_image", config("image", image()));
        PipelineDefinition.Step added = step("size_2", "measure_image", config("image", image()));
        PipelineDefinition.Step chosen = step("size_3", "measure_image", config("image", image(), "target", "red_region"));
        PipelineDefinition.Step other = step("keep", "filter", config("where", "x > 1"));

        int pinned = MeasureImageStepTask.pinTargets(Arrays.asList(renamed, added, chosen, other), Arrays.asList(old));

        assertThat(pinned).isEqualTo(2);
        assertThat(renamed.getConfig()).containsEntry("target", "red_region");
        assertThat(added.getConfig()).containsEntry("target", "contrast");
        assertThat(chosen.getConfig()).containsEntry("target", "red_region");
        assertThat(other.getConfig()).doesNotContainKey("target");
        assertThat(old.getConfig()).doesNotContainKey("target");
    }

    @Test
    void aFirstSaveGivesEveryMeasureStepContrastUnlessItChose() {
        PipelineDefinition.Step bare = step("size", "measure_image", null);
        PipelineDefinition.Step targeted = step("size", "measure_image", config("image", image(), "target", "red_on_skin"));
        PipelineDefinition.Step changed = step("size", "measure_image", config("image", image(), "target", "contrast"));

        MeasureImageStepTask.pinTargets(Arrays.asList(bare), null);
        MeasureImageStepTask.pinTargets(Arrays.asList(changed), Arrays.asList(targeted));

        assertThat(bare.getConfig()).containsEntry("target", "contrast");
        assertThat(changed.getConfig()).as("a target the person changed is theirs").containsEntry("target", "contrast");
    }

    @Test
    void twoOutputsCannotShareAColumn() {
        List<DefinitionProblem> problems = this.task.check(config("image", image(), "lengthColumn", "size", "widthColumn", "size"));

        assertThat(problems).containsExactly(new DefinitionProblem("widthColumn", "'size' is already an output column"));
    }
}

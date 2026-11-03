package process.pipeline.image;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Image review (owner-approved 2026-11-03) measures with RulerMeasure itself -- the measure_image step's code -- and reads
 * the geometry it now carries; "Adjust outline" re-measures a drawn region with MaskMeasure (the measure_mask step's code).
 * The photos are the platform's synthetic demo images ("SYNTHETIC TEST IMAGE - not a patient"); the three visits of one
 * wound were drawn at 9.24, 6.5 and 4.1 cm2, with a 10 cm ruler of 70.2 px per cm.
 */
class ImageReviewMeasureTest {

    private static byte[] photo(String name) throws IOException {
        try (InputStream in = ImageReviewMeasureTest.class.getResourceAsStream("/image-review/" + name)) {
            assertThat(in).as(name).isNotNull();
            return in.readAllBytes();
        }
    }

    @Test
    void threeVisitsOfOneWoundMeasureAgainstTheirRulers() throws IOException {
        double[] expected = {9.3, 6.5, 4.1};
        for (int visit = 1; visit <= 3; visit++) {
            RulerMeasure.Measurement m = RulerMeasure.measure(photo("wound-SV-000117-v" + visit + ".jpg"), RulerMeasure.Target.RED_REGION);
            assertThat(m.scaleFound).as("visit %d has a ruler", visit).isTrue();
            assertThat(m.pxPerCm).isCloseTo(70.2, within(1.5));
            assertThat(m.areaCm2).as("visit %d area", visit).isCloseTo(expected[visit - 1], within(0.35));
            RulerMeasure.Geometry g = m.geometry;
            assertThat(g.width).isEqualTo(1000);
            assertThat(g.height).isEqualTo(760);
            assertThat(g.rulerBox).hasSize(4);
            assertThat(g.rulerTicks).isGreaterThanOrEqualTo(4);
            assertThat(g.outline.length).isEven().isBetween(40, 2 * RulerMeasure.MAX_OUTLINE_POINTS);
            // The outline goes round the wound, near the photo's middle, and stays above the ruler.
            for (int i = 0; i < g.outline.length; i += 2) {
                assertThat(g.outline[i]).isBetween(250, 750);
                assertThat(g.outline[i + 1]).isBetween(150, g.rulerBox[1]);
            }
            double length = Math.hypot(g.lengthLine[2] - g.lengthLine[0], g.lengthLine[3] - g.lengthLine[1]) + 1;
            double width = Math.hypot(g.widthLine[2] - g.widthLine[0], g.widthLine[3] - g.widthLine[1]) + 1;
            assertThat(length / m.pxPerCm).as("the length line is the length").isCloseTo(m.lengthCm, within(0.15));
            assertThat(width / m.pxPerCm).as("the width line is the width").isCloseTo(m.widthCm, within(0.15));
            assertThat(g.areaPx).isPositive();
        }
    }

    @Test
    void aPhotoWithoutARulerHasNoSizeButSaysHowDarkItIs() throws IOException {
        RulerMeasure.Measurement m = RulerMeasure.measure(photo("wound-noruler.jpg"), RulerMeasure.Target.RED_REGION);
        assertThat(m.scaleFound).isFalse();
        assertThat(m.lengthCm).isNull();
        assertThat(m.widthCm).isNull();
        assertThat(m.areaCm2).isNull();
        assertThat(m.note).isEqualTo("no ruler found: not measured");
        assertThat(m.geometry.rulerBox).isNull();
        assertThat(m.geometry.meanLuminance).isGreaterThan(80);
        assertThat(m.geometry.outline).as("the outline is kept, in pixels only").isNotEmpty();
        assertThat(m.geometry.areaPx).isPositive();

        RulerMeasure.Measurement dark = RulerMeasure.measure(photo("wound-photo-dark.jpg"), RulerMeasure.Target.RED_REGION);
        assertThat(dark.scaleFound).isFalse();
        assertThat(dark.geometry.meanLuminance).as("too dark to read").isLessThan(40);
    }

    @Test
    void theStepStillReadsTheSameSizes() throws IOException {
        // The pipeline step's call (bytes in, sizes out) and the tool's call are the same method: one code path.
        byte[] bytes = photo("wound-SV-000117-v3.jpg");
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        RulerMeasure.Measurement a = RulerMeasure.measure(bytes, RulerMeasure.Target.CONTRAST);
        RulerMeasure.Measurement b = RulerMeasure.measure(image, RulerMeasure.Target.CONTRAST);
        assertThat(a.areaCm2).isEqualTo(b.areaCm2);
        assertThat(a.note).isEqualTo(b.note);
    }

    @Test
    void aDrawnOutlineIsMeasuredInCmOnlyWithTheRulersScale() {
        // A 4 x 2 cm rectangle at 50 px/cm, drawn over a 1000 x 760 photo.
        double[] box = {100, 100, 300, 100, 300, 200, 100, 200};
        RulerMeasure.Measurement withRuler = RulerMeasure.drawn(1000, 760, box, 50.0);
        assertThat(withRuler.scaleFound).isTrue();
        assertThat(withRuler.lengthCm).isCloseTo(4.0, within(0.1));
        assertThat(withRuler.widthCm).isCloseTo(2.0, within(0.1));
        assertThat(withRuler.areaCm2).isCloseTo(8.0, within(0.2));
        assertThat(withRuler.geometry.areaPx).isBetween(19_500L, 20_500L);

        RulerMeasure.Measurement noRuler = RulerMeasure.drawn(1000, 760, box, null);
        assertThat(noRuler.scaleFound).isFalse();
        assertThat(noRuler.areaCm2).as("no ruler, no size: pixels only").isNull();
        assertThat(noRuler.lengthCm).isNull();
        assertThat(noRuler.geometry.areaPx).isEqualTo(withRuler.geometry.areaPx);
        assertThat(noRuler.geometry.lengthPx).isCloseTo(200, within(2.0));
        assertThat(noRuler.note).contains("pixels only");
    }

    @Test
    void aLargePhotosDrawnOutlineIsMeasuredAtItsOwnScale() {
        // 6000 x 4000: rastered at a quarter, the sizes still in the photo's pixels.
        double[] box = {1000, 1000, 3000, 1000, 3000, 2000, 1000, 2000};
        RulerMeasure.Measurement m = RulerMeasure.drawn(6000, 4000, box, 100.0);
        assertThat(m.lengthCm).isCloseTo(20.0, within(0.2));
        assertThat(m.widthCm).isCloseTo(10.0, within(0.2));
        assertThat(m.areaCm2).isCloseTo(200.0, within(3.0));
    }

    @Test
    void aDrawnOutlineMustBeAShape() {
        assertThatThrownBy(() -> RulerMeasure.drawn(1000, 760, new double[] {1, 2, 3, 4}, 50.0)).hasMessageContaining("at least 3 points");
        assertThatThrownBy(() -> RulerMeasure.drawn(1000, 760, new double[] {1, 2, 3, 4, 5, 6}, -1.0)).hasMessageContaining("positive");
        assertThatThrownBy(() -> RulerMeasure.drawn(0, 760, new double[] {1, 2, 3, 4, 5, 6}, null)).hasMessageContaining("size");
    }
}

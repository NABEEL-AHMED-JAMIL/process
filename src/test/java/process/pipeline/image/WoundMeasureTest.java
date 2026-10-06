package process.pipeline.image;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * MIG-255 (owner decision 2026-09-29): a wound's size is measured in code from the photo, never guessed by a model -- a
 * local vision model read these same photos 40-68% too large. The scale comes from a ruler's 1 cm ticks; with no ruler,
 * nothing is measured. The truth is the red wound bed; a pink periwound ring around it is not wound.
 */
class WoundMeasureTest {

    /** Length and width within 10% of the truth, area within 15%. */
    private static void assertMeasures(WoundMeasure.Measurement m, double length, double width, double area, double pxPerCm) {
        assertThat(m.scaleFound).as(m.note).isTrue();
        assertThat(m.pxPerCm).as(m.note).isCloseTo(pxPerCm, within(pxPerCm * 0.03));
        assertThat(m.lengthCm).as("length; " + m.note).isCloseTo(length, within(length * 0.10));
        assertThat(m.widthCm).as("width; " + m.note).isCloseTo(width, within(width * 0.10));
        assertThat(m.areaCm2).as("area; " + m.note).isCloseTo(area, within(area * 0.15));
    }

    private static WoundMeasure.Measurement file(String name) throws IOException {
        try (InputStream in = WoundMeasureTest.class.getResourceAsStream("/e2e/wound/" + name)) {
            byte[] bytes = new byte[in.available()];
            int read = 0;
            while (read < bytes.length) {
                read += in.read(bytes, read, bytes.length - read);
            }
            return WoundMeasure.measure(bytes);
        }
    }

    /** The e2e photos: an ellipse bed w x 2w/3 cm (w = 4.0 and 2.5) at 40 px/cm, and one without a ruler. */
    @Test
    void theE2ePhotosMeasureToTheirDrawnSizes() throws IOException {
        assertMeasures(file("WC-0001.png"), 4.0, 2.67, Math.PI * 4.0 * 2.667 / 4, 40);
        assertMeasures(file("WC-0002.png"), 2.5, 1.67, Math.PI * 2.5 * 1.667 / 4, 40);

        WoundMeasure.Measurement none = file("WC-0003.png");
        assertThat(none.scaleFound).isFalse();
        assertThat(none.pxPerCm).isNull();
        assertThat(none.lengthCm).isNull();
        assertThat(none.widthCm).isNull();
        assertThat(none.areaCm2).isNull();
        assertThat(none.note).startsWith("no ruler found");
    }

    @Test
    void theNoteSaysWhatWasMeasured() throws IOException {
        WoundMeasure.Measurement m = file("WC-0001.png");

        assertThat(m.note).matches("ruler \\d+ ticks, 40\\.\\d px/cm; wound [\\d,]+ px");
    }

    @Test
    void aCentredWoundWithAPeriwoundRingAt40PxPerCmMeasuresTheBedOnly() {
        SyntheticWounds photo = SyntheticWounds.photo(640, 480, 40).ellipse(4.0, 2.67, 320, 220, 0).periwound()
            .ruler(60, 400, 8).noise(12);

        assertMeasures(WoundMeasure.measure(photo.image()), photo.truthLength(), photo.truthWidth(), photo.truthArea(), 40);
    }

    @Test
    void aTurnedOffCentreWoundAt30PxPerCmOnAMillimetreRuler() {
        SyntheticWounds photo = SyntheticWounds.photo(800, 600, 30).ellipse(5.0, 3.0, 520, 200, 35).periwound()
            .ruler(40, 500, 10).millimetres().noise(12);

        assertMeasures(WoundMeasure.measure(photo.image()), photo.truthLength(), photo.truthWidth(), photo.truthArea(), 30);
    }

    @Test
    void aTurnedRectangularWoundAt55PxPerCmBesideAVerticalRuler() {
        SyntheticWounds photo = SyntheticWounds.photo(800, 700, 55).rectangle(3.0, 1.5, 450, 300, -20)
            .ruler(30, 60, 10).vertical().noise(8);

        assertMeasures(WoundMeasure.measure(photo.image()), photo.truthLength(), photo.truthWidth(), photo.truthArea(), 55);
    }

    @Test
    void aSmallWoundNearTheCornerAt55PxPerCm() {
        SyntheticWounds photo = SyntheticWounds.photo(900, 700, 55).ellipse(1.6, 0.9, 150, 140, 70).periwound()
            .ruler(300, 560, 8).millimetres().noise(12);

        assertMeasures(WoundMeasure.measure(photo.image()), photo.truthLength(), photo.truthWidth(), photo.truthArea(), 55);
    }

    @Test
    void aJpegWithCompressionArtefactsMeasuresTheSame() throws IOException {
        SyntheticWounds photo = SyntheticWounds.photo(800, 600, 30).ellipse(5.0, 3.0, 520, 200, 35).periwound()
            .ruler(40, 500, 10).millimetres().noise(12);

        assertMeasures(WoundMeasure.measure(photo.jpeg(0.5f)), photo.truthLength(), photo.truthWidth(), photo.truthArea(), 30);
    }

    /** A camera-sized photo is halved until it fits; the sizes stay, and the scale is in the photo's own pixels. */
    @Test
    void aLargePhotoIsHalvedToMeasureAndKeepsItsScale() {
        SyntheticWounds photo = SyntheticWounds.photo(5000, 3600, 160).ellipse(4.0, 2.5, 2600, 1500, 25).periwound()
            .ruler(300, 3000, 12).millimetres();

        assertMeasures(WoundMeasure.measure(photo.image()), photo.truthLength(), photo.truthWidth(), photo.truthArea(), 160);
    }

    @Test
    void withoutARulerNothingIsMeasured() {
        WoundMeasure.Measurement m = WoundMeasure.measure(SyntheticWounds.photo(640, 480, 40).ellipse(4.0, 2.67, 320, 220, 10)
            .periwound().noise(12).image());

        assertThat(m.scaleFound).isFalse();
        assertThat(m.pxPerCm).isNull();
        assertThat(m.lengthCm).isNull();
        assertThat(m.widthCm).isNull();
        assertThat(m.areaCm2).isNull();
        assertThat(m.note).isEqualTo("no ruler found: not measured");
    }

    /** Two centimetres of ruler are three ticks: too few to trust a scale. */
    @Test
    void aRulerWithTooFewTicksIsNoScale() {
        WoundMeasure.Measurement m = WoundMeasure.measure(SyntheticWounds.photo(640, 480, 40).ellipse(4.0, 2.67, 320, 220, 0)
            .ruler(60, 400, 2).image());

        assertThat(m.scaleFound).isFalse();
        assertThat(m.lengthCm).isNull();
    }

    @Test
    void aRulerOnPlainSkinFindsTheScaleButNoWound() {
        WoundMeasure.Measurement m = WoundMeasure.measure(SyntheticWounds.photo(640, 480, 40).ruler(60, 400, 8).noise(12).image());

        assertThat(m.scaleFound).isTrue();
        assertThat(m.pxPerCm).isCloseTo(40.0, within(1.2));
        assertThat(m.lengthCm).isNull();
        assertThat(m.widthCm).isNull();
        assertThat(m.areaCm2).isNull();
        assertThat(m.note).endsWith("no wound region found");
    }

    @Test
    void bytesThatAreNotAnImageAreAnError() {
        assertThatThrownBy(() -> WoundMeasure.measure("not a picture".getBytes("UTF-8")))
            .isInstanceOf(IOException.class).hasMessage("The file is not a png or jpg image.");
    }
}

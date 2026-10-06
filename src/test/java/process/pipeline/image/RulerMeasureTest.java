package process.pipeline.image;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * MIG-255 (owner decision 2026-09-29): an object's size is measured in code from the photo, never guessed by a model --
 * a local vision model read the wound photos 40-68% too large. The scale comes from a ruler's 1 cm ticks; with no ruler,
 * nothing is measured. red_on_skin (the wound photos): the truth is the red wound bed; a pink periwound ring around it is
 * not wound. contrast (2026-10-06): any object whose colour differs markedly from the background.
 */
class RulerMeasureTest {

    private static final RulerMeasure.Target RED = RulerMeasure.Target.RED_ON_SKIN;
    private static final RulerMeasure.Target CONTRAST = RulerMeasure.Target.CONTRAST;

    /** Length and width within 10% of the truth, area within 15%. */
    private static void assertMeasures(RulerMeasure.Measurement m, double length, double width, double area, double pxPerCm) {
        assertThat(m.scaleFound).as(m.note).isTrue();
        assertThat(m.pxPerCm).as(m.note).isCloseTo(pxPerCm, within(pxPerCm * 0.03));
        assertThat(m.lengthCm).as("length; " + m.note).isCloseTo(length, within(length * 0.10));
        assertThat(m.widthCm).as("width; " + m.note).isCloseTo(width, within(width * 0.10));
        assertThat(m.areaCm2).as("area; " + m.note).isCloseTo(area, within(area * 0.15));
    }

    private static RulerMeasure.Measurement file(String name) throws IOException {
        try (InputStream in = RulerMeasureTest.class.getResourceAsStream("/e2e/wound/" + name)) {
            byte[] bytes = new byte[in.available()];
            int read = 0;
            while (read < bytes.length) {
                read += in.read(bytes, read, bytes.length - read);
            }
            return RulerMeasure.measure(bytes, RED);
        }
    }

    /** The e2e photos: an ellipse bed w x 2w/3 cm (w = 4.0 and 2.5) at 40 px/cm, and one without a ruler. */
    @Test
    void theE2ePhotosMeasureToTheirDrawnSizes() throws IOException {
        assertMeasures(file("WC-0001.png"), 4.0, 2.67, Math.PI * 4.0 * 2.667 / 4, 40);
        assertMeasures(file("WC-0002.png"), 2.5, 1.67, Math.PI * 2.5 * 1.667 / 4, 40);

        RulerMeasure.Measurement none = file("WC-0003.png");
        assertThat(none.scaleFound).isFalse();
        assertThat(none.pxPerCm).isNull();
        assertThat(none.lengthCm).isNull();
        assertThat(none.widthCm).isNull();
        assertThat(none.areaCm2).isNull();
        assertThat(none.note).startsWith("no ruler found");
    }

    @Test
    void theNoteSaysWhatWasMeasured() throws IOException {
        RulerMeasure.Measurement m = file("WC-0001.png");

        assertThat(m.note).matches("ruler \\d+ ticks, 40\\.\\d px/cm; region [\\d,]+ px");
    }

    @Test
    void aCentredWoundWithAPeriwoundRingAt40PxPerCmMeasuresTheBedOnly() {
        SyntheticPhotos photo = SyntheticPhotos.photo(640, 480, 40).ellipse(4.0, 2.67, 320, 220, 0).periwound()
            .ruler(60, 400, 8).noise(12);

        assertMeasures(RulerMeasure.measure(photo.image(), RED), photo.truthLength(), photo.truthWidth(), photo.truthArea(), 40);
    }

    @Test
    void aTurnedOffCentreWoundAt30PxPerCmOnAMillimetreRuler() {
        SyntheticPhotos photo = SyntheticPhotos.photo(800, 600, 30).ellipse(5.0, 3.0, 520, 200, 35).periwound()
            .ruler(40, 500, 10).millimetres().noise(12);

        assertMeasures(RulerMeasure.measure(photo.image(), RED), photo.truthLength(), photo.truthWidth(), photo.truthArea(), 30);
    }

    @Test
    void aTurnedRectangularWoundAt55PxPerCmBesideAVerticalRuler() {
        SyntheticPhotos photo = SyntheticPhotos.photo(800, 700, 55).rectangle(3.0, 1.5, 450, 300, -20)
            .ruler(30, 60, 10).vertical().noise(8);

        assertMeasures(RulerMeasure.measure(photo.image(), RED), photo.truthLength(), photo.truthWidth(), photo.truthArea(), 55);
    }

    @Test
    void aSmallWoundNearTheCornerAt55PxPerCm() {
        SyntheticPhotos photo = SyntheticPhotos.photo(900, 700, 55).ellipse(1.6, 0.9, 150, 140, 70).periwound()
            .ruler(300, 560, 8).millimetres().noise(12);

        assertMeasures(RulerMeasure.measure(photo.image(), RED), photo.truthLength(), photo.truthWidth(), photo.truthArea(), 55);
    }

    @Test
    void aJpegWithCompressionArtefactsMeasuresTheSame() throws IOException {
        SyntheticPhotos photo = SyntheticPhotos.photo(800, 600, 30).ellipse(5.0, 3.0, 520, 200, 35).periwound()
            .ruler(40, 500, 10).millimetres().noise(12);

        assertMeasures(RulerMeasure.measure(photo.jpeg(0.5f), RED), photo.truthLength(), photo.truthWidth(), photo.truthArea(), 30);
    }

    /** A camera-sized photo is halved until it fits; the sizes stay, and the scale is in the photo's own pixels. */
    @Test
    void aLargePhotoIsHalvedToMeasureAndKeepsItsScale() {
        SyntheticPhotos photo = SyntheticPhotos.photo(5000, 3600, 160).ellipse(4.0, 2.5, 2600, 1500, 25).periwound()
            .ruler(300, 3000, 12).millimetres();

        assertMeasures(RulerMeasure.measure(photo.image(), RED), photo.truthLength(), photo.truthWidth(), photo.truthArea(), 160);
    }

    @Test
    void withoutARulerNothingIsMeasured() {
        RulerMeasure.Measurement m = RulerMeasure.measure(SyntheticPhotos.photo(640, 480, 40).ellipse(4.0, 2.67, 320, 220, 10)
            .periwound().noise(12).image(), RED);

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
        RulerMeasure.Measurement m = RulerMeasure.measure(SyntheticPhotos.photo(640, 480, 40).ellipse(4.0, 2.67, 320, 220, 0)
            .ruler(60, 400, 2).image(), RED);

        assertThat(m.scaleFound).isFalse();
        assertThat(m.lengthCm).isNull();
    }

    @Test
    void aRulerOnPlainSkinFindsTheScaleButNoRegion() {
        RulerMeasure.Measurement m = RulerMeasure.measure(SyntheticPhotos.photo(640, 480, 40).ruler(60, 400, 8).noise(12).image(), RED);

        assertThat(m.scaleFound).isTrue();
        assertThat(m.pxPerCm).isCloseTo(40.0, within(1.2));
        assertThat(m.lengthCm).isNull();
        assertThat(m.widthCm).isNull();
        assertThat(m.areaCm2).isNull();
        assertThat(m.note).endsWith("no region found");
    }

    @Test
    void contrastMeasuresADarkObjectOnALightBackground() {
        SyntheticPhotos photo = SyntheticPhotos.photo(800, 600, 40).on(new Color(190, 188, 182)).object(new Color(35, 35, 40))
            .rectangle(5.0, 2.0, 430, 230, 15).ruler(40, 500, 10).millimetres().noise(10);

        assertMeasures(RulerMeasure.measure(photo.image(), CONTRAST), photo.truthLength(), photo.truthWidth(), photo.truthArea(), 40);
    }

    @Test
    void contrastMeasuresABlueObjectOnGrey() throws IOException {
        SyntheticPhotos photo = SyntheticPhotos.photo(800, 600, 30).on(new Color(128, 128, 128)).object(new Color(45, 75, 170))
            .ellipse(6.0, 3.5, 450, 230, 40).ruler(40, 500, 10).noise(12);

        assertMeasures(RulerMeasure.measure(photo.jpeg(0.7f), CONTRAST), photo.truthLength(), photo.truthWidth(), photo.truthArea(), 30);
    }

    @Test
    void contrastMeasuresALightObjectOnADarkBackground() {
        SyntheticPhotos photo = SyntheticPhotos.photo(800, 700, 55).on(new Color(40, 45, 55)).object(new Color(230, 222, 200))
            .ellipse(3.0, 2.0, 480, 300, 70).ruler(30, 60, 10).vertical().noise(8);

        assertMeasures(RulerMeasure.measure(photo.image(), CONTRAST), photo.truthLength(), photo.truthWidth(), photo.truthArea(), 55);
    }

    /** contrast takes the wound's pink ring as part of the object; red_on_skin is the rule that leaves it out. */
    @Test
    void contrastMeasuresAWoundWithItsRingAndRedOnSkinWithout() {
        SyntheticPhotos photo = SyntheticPhotos.photo(640, 480, 40).ellipse(4.0, 2.67, 320, 220, 0).periwound()
            .ruler(60, 400, 8).noise(12);

        RulerMeasure.Measurement red = RulerMeasure.measure(photo.image(), RED);
        RulerMeasure.Measurement contrast = RulerMeasure.measure(photo.image(), CONTRAST);

        assertMeasures(red, 4.0, 2.67, photo.truthArea(), 40);
        assertThat(contrast.lengthCm).as(contrast.note).isCloseTo(4.5, within(0.3));
        assertThat(contrast.widthCm).as(contrast.note).isCloseTo(3.17, within(0.3));
    }

    @Test
    void contrastWithoutARulerMeasuresNothing() {
        RulerMeasure.Measurement m = RulerMeasure.measure(SyntheticPhotos.photo(640, 480, 40).on(new Color(128, 128, 128))
            .object(new Color(45, 75, 170)).ellipse(4.0, 2.67, 320, 220, 10).noise(12).image(), CONTRAST);

        assertThat(m.scaleFound).isFalse();
        assertThat(m.pxPerCm).isNull();
        assertThat(m.lengthCm).isNull();
        assertThat(m.areaCm2).isNull();
        assertThat(m.note).isEqualTo("no ruler found: not measured");
    }

    @Test
    void contrastWithARulerOnAPlainBackgroundFindsTheScaleButNoRegion() {
        for (Color background : new Color[] {new Color(128, 128, 128), new Color(40, 45, 55), SyntheticPhotos.SKIN}) {
            RulerMeasure.Measurement m = RulerMeasure.measure(SyntheticPhotos.photo(640, 480, 40).on(background)
                .ruler(60, 400, 8).millimetres().noise(14).image(), CONTRAST);

            assertThat(m.scaleFound).as(background.toString()).isTrue();
            assertThat(m.pxPerCm).isCloseTo(40.0, within(1.2));
            assertThat(m.lengthCm).as(m.note).isNull();
            assertThat(m.areaCm2).isNull();
            assertThat(m.note).endsWith("no region found");
        }
    }

    /** Looking again for a whiter ruler is contrast's alone: red_on_skin keeps its first rule exactly, as saved steps measured. */
    @Test
    void onLightGreyPaperOnlyContrastLooksAgainForAWhiterRuler() {
        SyntheticPhotos photo = SyntheticPhotos.photo(800, 600, 40).on(new Color(194, 194, 190)).object(new Color(35, 35, 40))
            .ellipse(4.0, 2.5, 430, 230, 30).ruler(40, 500, 10).noise(14);

        assertThat(RulerMeasure.measure(photo.image(), RED).scaleFound).isFalse();
        assertMeasures(RulerMeasure.measure(photo.image(), CONTRAST), photo.truthLength(), photo.truthWidth(), photo.truthArea(), 40);
    }

    @Test
    void aTargetIsNamedByItsConfigWord() {
        assertThat(RulerMeasure.Target.of("contrast")).contains(CONTRAST);
        assertThat(RulerMeasure.Target.of("red_on_skin")).contains(RED);
        assertThat(RulerMeasure.Target.of("wound")).isEmpty();
        assertThat(RulerMeasure.Target.of(null)).isEmpty();
    }

    @Test
    void bytesThatAreNotAnImageAreAnError() {
        assertThatThrownBy(() -> RulerMeasure.measure("not a picture".getBytes("UTF-8"), RED))
            .isInstanceOf(IOException.class).hasMessage("The file is not a png or jpg image.");
    }
}

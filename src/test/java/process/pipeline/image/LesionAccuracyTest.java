package process.pipeline.image;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Image review's Skin lesion type (2026-11-03) measures with {@code contrast}: a darker region on skin, against the paper ruler.
 * Before the type is offered, its accuracy on synthetic lesions: 60 photos from one seed -- skin tones light to dark, brown to
 * near-black lesions 0.4 to 3 cm, ellipses and rectangles turned 0-180 degrees, 30-70 px/cm, noise 0-10, a third as JPEG -- each
 * with the platform's dark "SYNTHETIC TEST IMAGE" banner along its top edge, as the demo images have. The report is
 * target/lesion-accuracy.md (mean, 95th percentile, worst).
 *
 * <p>Not covered: real skin (hair, freckles, shadows), raised or multi-coloured lesions, a ruler off the skin.
 */
class LesionAccuracyTest {

    static final long SEED = 454L;
    static final int PHOTOS = 60;

    @Test
    void contrastMeasuresSyntheticLesionsBesideABanner() throws IOException {
        Random random = new Random(SEED);
        Color[] skins = {new Color(238, 205, 180), new Color(224, 182, 150), new Color(196, 150, 112), new Color(150, 104, 74)};
        List<Double> lengthErr = new ArrayList<>();
        List<Double> widthErr = new ArrayList<>();
        List<Double> areaErr = new ArrayList<>();
        List<Double> largeAreaErr = new ArrayList<>();
        StringBuilder rows = new StringBuilder("| Photo | Truth L × W, area | Measured | Area error |\n|---|---|---|---|\n");
        int missed = 0;
        for (int k = 0; k < PHOTOS; k++) {
            double scale = 30 + random.nextDouble() * 40;
            Color skin = skins[random.nextInt(skins.length)];
            int darkness = 25 + random.nextInt(60);
            Color lesion = new Color(Math.max(0, skin.getRed() / 3 + darkness), Math.max(0, skin.getGreen() / 4 + darkness / 2), Math.max(0, skin.getBlue() / 4 + darkness / 3));
            double length = 0.4 + random.nextDouble() * 2.6;
            double width = length * (0.5 + random.nextDouble() * 0.5);
            SyntheticPhotos photo = SyntheticPhotos.photo(1000, 760, scale).on(skin).object(lesion);
            double angle = random.nextDouble() * 180;
            photo = random.nextBoolean() ? photo.ellipse(length, width, 500, 330, angle) : photo.rectangle(length, width, 500, 330, angle);
            photo = photo.ruler(60, 600, 10).noise(random.nextInt(11));
            BufferedImage image = banner(photo.image());
            byte[] bytes = k % 3 == 0 ? jpeg(image) : png(image);
            RulerMeasure.Measurement m = RulerMeasure.measure(bytes, RulerMeasure.Target.CONTRAST);
            if (m.areaCm2 == null) {
                missed++;
                rows.append(String.format(Locale.ROOT, "| %d | %.1f × %.1f, %.2f | not measured: %s | - |%n", k, photo.truthLength(), photo.truthWidth(),
                    photo.truthArea(), m.note));
                continue;
            }
            double ae = Math.abs(m.areaCm2 - photo.truthArea()) / photo.truthArea();
            lengthErr.add(Math.abs(m.lengthCm - photo.truthLength()) / photo.truthLength());
            widthErr.add(Math.abs(m.widthCm - photo.truthWidth()) / photo.truthWidth());
            areaErr.add(ae);
            if (photo.truthArea() >= 0.3) {
                largeAreaErr.add(ae);
            }
            rows.append(String.format(Locale.ROOT, "| %d | %.1f × %.1f, %.2f | %.1f × %.1f, %.2f | %.1f%% |%n", k, photo.truthLength(), photo.truthWidth(),
                photo.truthArea(), m.lengthCm, m.widthCm, m.areaCm2, ae * 100));
        }
        String report = String.format(Locale.ROOT, "# Skin lesion accuracy (contrast, synthetic)%n%n%d photos, %d measured, %d not measured.%n%n"
                + "| Measure | Mean error | 95th percentile | Worst |%n|---|---|---|---|%n%s%s%s%n%s", PHOTOS, PHOTOS - missed, missed,
            line("Length", lengthErr), line("Width", widthErr), line("Area", areaErr), rows);
        Path out = Paths.get("target", "lesion-accuracy.md");
        Files.createDirectories(out.getParent());
        Files.write(out, report.getBytes(StandardCharsets.UTF_8));
        // Measured 2026-11-03: 58 of 60 found (two low-contrast lesions on darker skin give no size, never a guess); area mean 4.1%,
        // 95th percentile 19.2%, worst 31.7% -- the large ones all under 0.25 cm2, where the 0.1 cm2 rounding dominates; from 0.3 cm2
        // up (52 photos) mean 2.4%, 95th percentile 7.8%, worst 9.9%. The bounds hold those figures.
        assertThat(missed).as("synthetic lesions not found (no size, never estimated)").isLessThanOrEqualTo(2);
        assertThat(mean(areaErr)).as("mean area error").isLessThan(0.06);
        assertThat(percentile(areaErr, 0.95)).as("95th percentile area error").isLessThan(0.22);
        assertThat(percentile(largeAreaErr, 0.95)).as("95th percentile, lesions of 0.3 cm2 and up").isLessThan(0.10);
    }

    /** The demo images' dark banner along the top edge, which contrast must leave out. */
    static BufferedImage banner(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(28, 28, 30));
        g.fillRect(0, 0, image.getWidth(), 42);
        g.setColor(Color.WHITE);
        g.drawString("SYNTHETIC TEST IMAGE - not a patient", 16, 26);
        g.dispose();
        return image;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(image, 0, 0, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(rgb, "jpg", out);
        return ImageIO.read(new ByteArrayInputStream(out.toByteArray())) == null ? png(image) : out.toByteArray();
    }

    private static String line(String name, List<Double> errors) {
        return String.format(Locale.ROOT, "| %s | %.1f%% | %.1f%% | %.1f%% |%n", name, mean(errors) * 100, percentile(errors, 0.95) * 100,
            errors.isEmpty() ? 0 : Collections.max(errors) * 100);
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double percentile(List<Double> values, double p) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1));
    }
}

package process.pipeline.image;

import org.junit.jupiter.api.Test;

import java.awt.Color;
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
 * The accuracy figure for the in-code measurement (RulerMeasure) in its default contrast mode (2026-10-06), by the
 * method of WoundAccuracyTest: 60 synthetic photos from one seed, so every run measures the same set.
 *
 * <p><b>Variation:</b> three kinds of photo in turn -- a dark object on a light background (grey or buff paper), a
 * blue object on grey, a light object on a dark background -- each colour moved a little per photo; scales 25 to 60
 * px/cm; objects 1 to 7 cm long, ellipses and rectangles, turned 0 to 180 degrees; horizontal and vertical rulers, cm and
 * mm ticks; noise 0 to 14; a third saved as JPEG at quality 0.5 to 0.9.
 *
 * <p><b>Truth:</b> the generator's drawn sizes. <b>Error:</b> |measured - truth| / truth, per measure. The report
 * (target/contrast-accuracy.md) lists every photo, the mean, the 95th percentile and the worst.
 *
 * <p><b>Not covered:</b> uneven light, shadows and patterned backgrounds (contrast takes the largest region that differs
 * from the border, which a strong shadow can be), a white background (it hides the ruler's white face), tilted or inch
 * rulers.
 */
class ContrastAccuracyTest {

    static final long SEED = 1006L;
    static final int PHOTOS = 60;

    private static final String[] KINDS = {"dark on light", "blue on grey", "light on dark"};

    private static Color jitter(Random random, int r, int g, int b, int by) {
        return new Color(clamp(r + random.nextInt(2 * by + 1) - by), clamp(g + random.nextInt(2 * by + 1) - by),
            clamp(b + random.nextInt(2 * by + 1) - by));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    @Test
    void theContrastMeasurementIsWithinItsPublishedError() throws IOException {
        Random random = new Random(SEED);
        List<Double> lengthErr = new ArrayList<>();
        List<Double> widthErr = new ArrayList<>();
        List<Double> areaErr = new ArrayList<>();
        StringBuilder rows = new StringBuilder();
        int unmeasured = 0;
        for (int i = 0; i < PHOTOS; i++) {
            int kind = i % KINDS.length;
            Color background;
            Color object;
            if (kind == 0) {
                background = random.nextBoolean() ? jitter(random, 185, 185, 180, 10) : jitter(random, 215, 200, 160, 6);
                object = jitter(random, 40, 38, 42, 15);
            } else if (kind == 1) {
                int grey = 105 + random.nextInt(45);
                background = new Color(grey, grey, grey + random.nextInt(8));
                object = jitter(random, 45, 75, 175, 15);
            } else {
                background = jitter(random, 45, 48, 58, 15);
                object = jitter(random, 228, 220, 200, 10);
            }
            double pxPerCm = 25 + random.nextInt(36);
            boolean vertical = random.nextInt(4) == 0;
            boolean millimetres = random.nextBoolean();
            boolean rectangle = random.nextInt(3) == 0;
            int noise = random.nextInt(15);
            double maxLength = Math.min(7.0, 380.0 / pxPerCm);
            double length = round1(1.0 + random.nextDouble() * (maxLength - 1.0));
            double width = round1(length * (0.4 + random.nextDouble() * 0.5));
            double degrees = random.nextInt(180);
            int w = 900;
            int h = 700;
            SyntheticPhotos photo = SyntheticPhotos.photo(w, h, pxPerCm).on(background).object(object);
            double cx = vertical ? 520 + random.nextInt(80) : 420 + random.nextInt(80);
            double cy = vertical ? 330 + random.nextInt(40) : 280 + random.nextInt(40);
            photo = rectangle ? photo.rectangle(length, width, cx, cy, degrees) : photo.ellipse(length, width, cx, cy, degrees);
            if (vertical) {
                photo = photo.ruler(30, 40, (int) Math.min(10, Math.floor((h - 80) / pxPerCm))).vertical();
            } else {
                photo = photo.ruler(40, h - 90, (int) Math.min(10, Math.floor((w - 80) / pxPerCm)));
            }
            if (millimetres) {
                photo = photo.millimetres();
            }
            photo = photo.noise(noise);
            boolean jpeg = random.nextInt(3) == 0;
            float quality = 0.5f + random.nextInt(5) / 10f;
            RulerMeasure.Measurement m = jpeg ? RulerMeasure.measure(photo.jpeg(quality), RulerMeasure.Target.CONTRAST)
                : RulerMeasure.measure(photo.image(), RulerMeasure.Target.CONTRAST);
            String name = String.format(Locale.ROOT, "%02d %s, %s %.1fx%.1f cm, %.0f px/cm, %s ruler%s, noise %d%s", i + 1, KINDS[kind],
                rectangle ? "rect" : "ellipse", length, width, pxPerCm, vertical ? "vertical" : "horizontal", millimetres ? " (mm)" : "",
                noise, jpeg ? String.format(Locale.ROOT, ", jpeg %.1f", quality) : "");
            if (!m.scaleFound || m.areaCm2 == null) {
                unmeasured++;
                rows.append(String.format(Locale.ROOT, "| %s | not measured: %s | | |%n", name, m.note));
                continue;
            }
            double le = err(m.lengthCm, photo.truthLength());
            double we = err(m.widthCm, photo.truthWidth());
            double ae = err(m.areaCm2, photo.truthArea());
            lengthErr.add(le);
            widthErr.add(we);
            areaErr.add(ae);
            rows.append(String.format(Locale.ROOT, "| %s | %.2f vs %.2f cm2 | %.1f%% | %.1f%% / %.1f%% |%n", name, m.areaCm2,
                photo.truthArea(), ae * 100, le * 100, we * 100));
        }
        String summary = String.format(Locale.ROOT,
            "| Measure | Mean error | 95th percentile | Worst |%n|---|---|---|---|%n"
                + "| Length | %.1f%% | %.1f%% | %.1f%% |%n| Width | %.1f%% | %.1f%% | %.1f%% |%n| Area | %.1f%% | %.1f%% | %.1f%% |%n",
            mean(lengthErr) * 100, p95(lengthErr) * 100, max(lengthErr) * 100, mean(widthErr) * 100, p95(widthErr) * 100,
            max(widthErr) * 100, mean(areaErr) * 100, p95(areaErr) * 100, max(areaErr) * 100);
        String report = "# Contrast measurement accuracy (synthetic set, seed " + SEED + ")\n\n" + PHOTOS + " photos, "
            + (PHOTOS - unmeasured) + " measured, " + unmeasured + " not measured.\n\n" + summary
            + "\n| Photo | Area measured vs truth | Area error | Length / width error |\n|---|---|---|---|\n" + rows;
        Path out = Paths.get("target", "contrast-accuracy.md");
        Files.createDirectories(out.getParent());
        Files.write(out, report.getBytes(StandardCharsets.UTF_8));

        // Every photo has a ruler and an object, so every one must be measured; the same bounds as red_on_skin's.
        assertThat(unmeasured).as(report).isZero();
        assertThat(mean(areaErr)).as(report).isLessThan(0.05);
        assertThat(p95(areaErr)).as(report).isLessThan(0.12);
        assertThat(p95(lengthErr)).as(report).isLessThan(0.08);
        assertThat(p95(widthErr)).as(report).isLessThan(0.10);
    }

    private static double err(Double measured, double truth) {
        return Math.abs(measured - truth) / truth;
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private static double max(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).max().orElse(Double.NaN);
    }

    private static double p95(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.isEmpty() ? Double.NaN : sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
    }
}

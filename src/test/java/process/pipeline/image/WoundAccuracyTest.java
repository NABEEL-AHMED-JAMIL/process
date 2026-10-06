package process.pipeline.image;

import org.junit.jupiter.api.Test;

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
 * MIG-325's accuracy figure for the in-code measurement (WoundMeasure), with its method. The set is drawn, not
 * photographed: 60 synthetic photos from one seed, so every run measures the same set.
 *
 * <p><b>Variation:</b> scales 25 to 60 px/cm; wounds 1 to 7 cm long, ellipses and rectangles, turned 0 to 180 degrees;
 * with or without a periwound ring; horizontal and vertical rulers, cm and mm ticks; noise 0 to 14; a third saved as
 * JPEG at quality 0.5 to 0.9.
 *
 * <p><b>Truth:</b> the generator's drawn sizes. Length and width are the shape's axes; the area is the drawn bed.
 *
 * <p><b>Error:</b> |measured - truth| / truth, per measure. The report (target/wound-accuracy.md) lists every photo,
 * the mean, the 95th percentile and the worst.
 *
 * <p><b>Not covered</b> (the method's limits, MIG-255): real skin tones and lighting, tilted rulers, inch rulers, colour
 * casts, eschar at the edge. A real figure needs consented clinical photos measured by clinicians.
 */
class WoundAccuracyTest {

    static final long SEED = 325L;
    static final int PHOTOS = 60;

    static final class Case {
        final String name;
        final double length;
        final double width;
        final double area;
        final WoundMeasure.Measurement m;

        Case(String name, SyntheticWounds photo, WoundMeasure.Measurement m) {
            this.name = name;
            this.length = photo.truthLength();
            this.width = photo.truthWidth();
            this.area = photo.truthArea();
            this.m = m;
        }

        boolean measured() {
            return this.m.scaleFound && this.m.areaCm2 != null;
        }
    }

    static List<Case> measureTheSet() throws IOException {
        Random random = new Random(SEED);
        List<Case> cases = new ArrayList<>();
        for (int i = 0; i < PHOTOS; i++) {
            double pxPerCm = 25 + random.nextInt(36);
            boolean vertical = random.nextInt(4) == 0;
            boolean millimetres = random.nextBoolean();
            boolean rectangle = random.nextInt(3) == 0;
            boolean periwound = !rectangle && random.nextBoolean();
            int noise = random.nextInt(15);
            double maxLength = Math.min(7.0, 380.0 / pxPerCm);
            double length = round1(1.0 + random.nextDouble() * (maxLength - 1.0));
            double width = round1(length * (0.4 + random.nextDouble() * 0.5));
            double degrees = random.nextInt(180);
            int w = 900;
            int h = 700;
            SyntheticWounds photo = SyntheticWounds.photo(w, h, pxPerCm);
            double cx = vertical ? 520 + random.nextInt(80) : 420 + random.nextInt(80);
            double cy = vertical ? 330 + random.nextInt(40) : 280 + random.nextInt(40);
            photo = rectangle ? photo.rectangle(length, width, cx, cy, degrees) : photo.ellipse(length, width, cx, cy, degrees);
            if (periwound) {
                photo = photo.periwound();
            }
            if (vertical) {
                photo = photo.ruler(30, 40, (int) Math.min(10, Math.floor((h - 80) / pxPerCm))).vertical();
            } else {
                photo = photo.ruler(40, h - 90, (int) Math.min(10, Math.floor((w - 80) / pxPerCm)));
            }
            if (millimetres) {
                photo = photo.millimetres();
            }
            photo = photo.noise(noise);
            boolean jpeg = i % 3 == 2;
            float quality = 0.5f + random.nextInt(5) / 10f;
            WoundMeasure.Measurement m = jpeg ? WoundMeasure.measure(photo.jpeg(quality)) : WoundMeasure.measure(photo.image());
            String name = String.format(Locale.ROOT, "%02d %s %.1fx%.1f cm, %.0f px/cm, %s ruler%s, %s%s%s", i + 1,
                rectangle ? "rect" : "ellipse", length, width, pxPerCm, vertical ? "vertical" : "horizontal",
                millimetres ? " (mm)" : "", periwound ? "periwound, " : "", "noise " + noise,
                jpeg ? String.format(Locale.ROOT, ", jpeg %.1f", quality) : "");
            cases.add(new Case(name, photo, m));
        }
        return cases;
    }

    @Test
    void theInCodeMeasurementIsWithinItsPublishedError() throws IOException {
        List<Case> cases = measureTheSet();
        List<Double> lengthErr = new ArrayList<>();
        List<Double> widthErr = new ArrayList<>();
        List<Double> areaErr = new ArrayList<>();
        StringBuilder rows = new StringBuilder();
        int unmeasured = 0;
        for (Case c : cases) {
            if (!c.measured()) {
                unmeasured++;
                rows.append(String.format(Locale.ROOT, "| %s | not measured: %s | | |%n", c.name, c.m.note));
                continue;
            }
            double le = err(c.m.lengthCm, c.length);
            double we = err(c.m.widthCm, c.width);
            double ae = err(c.m.areaCm2, c.area);
            lengthErr.add(le);
            widthErr.add(we);
            areaErr.add(ae);
            rows.append(String.format(Locale.ROOT, "| %s | %.2f vs %.2f cm2 | %.1f%% | %.1f%% / %.1f%% |%n", c.name, c.m.areaCm2,
                c.area, ae * 100, le * 100, we * 100));
        }
        String summary = String.format(Locale.ROOT,
            "| Measure | Mean error | 95th percentile | Worst |%n|---|---|---|---|%n"
                + "| Length | %.1f%% | %.1f%% | %.1f%% |%n| Width | %.1f%% | %.1f%% | %.1f%% |%n| Area | %.1f%% | %.1f%% | %.1f%% |%n",
            mean(lengthErr) * 100, p95(lengthErr) * 100, max(lengthErr) * 100, mean(widthErr) * 100, p95(widthErr) * 100,
            max(widthErr) * 100, mean(areaErr) * 100, p95(areaErr) * 100, max(areaErr) * 100);
        String report = "# Wound measurement accuracy (synthetic set, seed " + SEED + ")\n\n" + PHOTOS + " photos, "
            + (PHOTOS - unmeasured) + " measured, " + unmeasured + " not measured.\n\n" + summary
            + "\n| Photo | Area measured vs truth | Area error | Length / width error |\n|---|---|---|---|\n" + rows;
        Path out = Paths.get("target", "wound-accuracy.md");
        Files.createDirectories(out.getParent());
        Files.write(out, report.getBytes(StandardCharsets.UTF_8));

        // Every photo has a ruler, so every one must be measured; the bounds are what the console may promise.
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

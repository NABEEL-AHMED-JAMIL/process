package process.pipeline.image;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Measures the object in a photo against the ruler beside it: its length, width and area in cm (MIG-255, made generic
 * 2026-10-06). Owner decision 2026-09-29: sizes are measured in code, never taken from a vision model -- a local model
 * read the synthetic photos 40-68% too large. Deterministic and conservative: with no ruler it measures nothing (the
 * MIG-221 rule), and it never guesses a size it cannot see.
 *
 * <ol>
 *   <li><b>Scale.</b> The ruler is the largest light, near-neutral strip (at least 2.5 times as long as it is deep, most
 *       of its bounding box light) with dark ticks across it. Along the strip, each position's longest run of dark pixels
 *       across it marks a tick; only the longest ticks (within 75% of the longest) count, so a ruler's millimetre and
 *       half-centimetre ticks and its digits are left out, and those ticks are taken as 1 cm apart. The scale is the
 *       median spacing of neighbouring ticks, trusted only from {@value #MIN_TICKS} ticks whose spacings vary by less
 *       than {@value #MAX_TICK_CV} (coefficient of variation) -- otherwise there is no scale and no size. Light is a
 *       luminance of at least 200; in contrast mode, when no ruler is found, again at 215 and 230, so a ruler on light
 *       grey paper is still told apart. The ruler must stand out as a white strip: on a white background it is not
 *       found.</li>
 *   <li><b>Region</b>, by the {@link Target}. The background is the photo's border (the ruler left out), and the
 *       region is the largest 4-connected one outside the ruler that differs from it:
 *       <ul>
 *         <li>{@link Target#CONTRAST} (any object): a pixel whose colour differs markedly from the border's median
 *             colour, of any hue -- its CIE76 colour difference (Delta E, in Lab) is at least
 *             {@value #MIN_DELTA_E}, or {@value #NOISE_FACTOR} times the border's own median difference when the
 *             background is busier. The threshold is then raised to half the region's median difference, so an edge
 *             pixel counts when it is more object than background.</li>
 *         <li>{@link Target#RED_REGION} (a red region on a lighter background, the step's first rule): a pixel
 *             markedly redder than the border: its (green + blue) / 2 is at most {@value #MAX_BED_RATIO} of its red, and
 *             at least {@value #MIN_RATIO_DROP} below the border's -- a ratio, so shading does not move it. A lighter,
 *             pinker halo around the red region is left out, so the region is measured from red edge to red edge.</li>
 *       </ul>
 *       The holes in the region (a highlight, a different colour inside) are filled in; a region under
 *       {@value #MIN_REGION_CM2} cm2 is none.</li>
 *   <li><b>Size.</b> The region's major axis comes from its second moments. Length is its extent along that axis,
 *       width its extent across it (both in whole pixels, so a turned object measures the same), and area its pixel
 *       count, each over the scale and rounded to 1 decimal.</li>
 * </ol>
 *
 * Limits: an uneven background (a strong shadow, a gradient, a patterned cloth) can be taken for the region in
 * {@code contrast} mode; tilted rulers, inch rulers and colour casts are not handled in either mode.
 *
 * A photo over {@value #MAX_SIDE} px on its long side is halved (averaging) until it fits, which keeps a step's memory
 * bounded; sizes do not change, and the scale is reported in the photo's own pixels.
 */
public final class RulerMeasure {

    static final int MAX_SIDE = 2400;
    static final int MIN_TICKS = 4;
    static final double MAX_TICK_CV = 0.15;
    static final double MAX_BED_RATIO = 0.45;
    static final double MIN_RATIO_DROP = 0.2;
    static final double MIN_DELTA_E = 15;
    static final double NOISE_FACTOR = 3;
    static final double MIN_REGION_CM2 = 0.05;

    private static final double LONG_TICK = 0.75;
    private static final double MIN_ASPECT = 2.5;
    private static final double MIN_FILL = 0.75;
    private static final int MAX_GAP = 6;
    private static final int LIGHT = 200;
    private static final int[] WHITER = {215, 230};

    private RulerMeasure() {
    }

    /** What the region is: how a pixel is told apart from the background. */
    public enum Target {
        /** The largest region whose colour differs markedly from the background, of any hue: the default for a new step. */
        CONTRAST("contrast"),
        /** A red region on a lighter background, by how much redder it is than the background: the step's first rule. */
        RED_REGION("red_region", "red_on_skin");

        private final String code;
        /** The word this target was saved under before it had a generic name; still read, never written. */
        private final String formerCode;

        Target(String code) {
            this(code, null);
        }

        Target(String code, String formerCode) {
            this.code = code;
            this.formerCode = formerCode;
        }

        /** The word a step's config uses. */
        public String code() {
            return this.code;
        }

        /** The target a config word names; empty when it names none. */
        public static Optional<Target> of(String code) {
            for (Target target : values()) {
                if (target.code.equals(code) || (target.formerCode != null && target.formerCode.equals(code))) {
                    return Optional.of(target);
                }
            }
            return Optional.empty();
        }
    }

    /** What was measured: every size is null when there is no scale or no region; the note says why. */
    public static final class Measurement {
        public final boolean scaleFound;
        public final Double pxPerCm;
        public final Double lengthCm;
        public final Double widthCm;
        public final Double areaCm2;
        public final String note;

        Measurement(boolean scaleFound, Double pxPerCm, Double lengthCm, Double widthCm, Double areaCm2, String note) {
            this.scaleFound = scaleFound;
            this.pxPerCm = pxPerCm;
            this.lengthCm = lengthCm;
            this.widthCm = widthCm;
            this.areaCm2 = areaCm2;
            this.note = note;
        }
    }

    /** A png or jpg photo's bytes, measured; bytes that are no image are an IOException. */
    public static Measurement measure(byte[] bytes, Target target) throws IOException {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException unreadable) {
            image = null;
        }
        if (image == null) {
            throw new IOException("The file is not a png or jpg image.");
        }
        return measure(image, target);
    }

    public static Measurement measure(BufferedImage photo, Target target) {
        int shrink = 1;
        BufferedImage image = photo;
        while (Math.max(image.getWidth(), image.getHeight()) > MAX_SIDE) {
            image = half(image);
            shrink *= 2;
        }
        Pixels pixels = new Pixels(image);
        Ruler ruler = Ruler.find(pixels, LIGHT);
        // On a light background a ruler's face can merge with it: contrast looks again for a whiter strip. red_region
        // keeps its first rule exactly, so a saved step measures as it always has.
        for (int k = 0; target == Target.CONTRAST && ruler == null && k < WHITER.length; k++) {
            ruler = Ruler.find(pixels, WHITER[k]);
        }
        if (ruler == null) {
            return new Measurement(false, null, null, null, null, "no ruler found: not measured");
        }
        double scale = ruler.pxPerCm;
        Double reported = round(scale * shrink);
        String found = String.format(Locale.ROOT, "ruler %d ticks, %.1f px/cm", ruler.ticks, scale * shrink);
        Region region = Region.find(pixels, ruler, MIN_REGION_CM2 * scale * scale, target);
        if (region == null) {
            return new Measurement(true, reported, null, null, null, found + "; no region found");
        }
        String note = String.format(Locale.ROOT, "%s; region %,d px%s", found, region.count * (long) shrink * shrink,
            region.touchesEdge ? ", touches the photo's edge (may be larger)" : "");
        return new Measurement(true, reported, round(region.length / scale), round(region.width / scale),
            round(region.count / (scale * scale)), note);
    }

    private static Double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    /** Half the size, each pixel the average of four (bilinear at exactly one half). */
    private static BufferedImage half(BufferedImage image) {
        BufferedImage half = new BufferedImage(Math.max(1, image.getWidth() / 2), Math.max(1, image.getHeight() / 2), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = half.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, half.getWidth(), half.getHeight(), null);
        g.dispose();
        return half;
    }

    /** A photo's pixels as rgb ints, row by row. */
    static final class Pixels {
        final int width;
        final int height;
        final int[] rgb;

        Pixels(BufferedImage image) {
            this.width = image.getWidth();
            this.height = image.getHeight();
            this.rgb = image.getRGB(0, 0, this.width, this.height, null, 0, this.width);
        }

        int red(int i) {
            return (this.rgb[i] >> 16) & 255;
        }

        int green(int i) {
            return (this.rgb[i] >> 8) & 255;
        }

        int blue(int i) {
            return this.rgb[i] & 255;
        }

        double luminance(int i) {
            return 0.299 * this.red(i) + 0.587 * this.green(i) + 0.114 * this.blue(i);
        }
    }

    /** 4-connected regions of a mask: each pixel's label (0 for none) and each label's pixel count and box. */
    static final class Labels {
        final int[] label;
        final List<int[]> boxes = new ArrayList<>();
        final List<Integer> counts = new ArrayList<>();

        Labels(boolean[] mask, int width, int height) {
            this.label = new int[mask.length];
            int[] stack = new int[mask.length];
            for (int start = 0; start < mask.length; start++) {
                if (!mask[start] || this.label[start] != 0) {
                    continue;
                }
                int id = this.counts.size() + 1;
                int[] box = {start % width, start / width, start % width, start / width};
                int count = 0;
                int top = 0;
                stack[top++] = start;
                this.label[start] = id;
                while (top > 0) {
                    int i = stack[--top];
                    int x = i % width;
                    int y = i / width;
                    count++;
                    box[0] = Math.min(box[0], x);
                    box[1] = Math.min(box[1], y);
                    box[2] = Math.max(box[2], x);
                    box[3] = Math.max(box[3], y);
                    int[] next = {x > 0 ? i - 1 : -1, x < width - 1 ? i + 1 : -1, y > 0 ? i - width : -1, y < height - 1 ? i + width : -1};
                    for (int n : next) {
                        if (n >= 0 && mask[n] && this.label[n] == 0) {
                            this.label[n] = id;
                            stack[top++] = n;
                        }
                    }
                }
                this.boxes.add(box);
                this.counts.add(count);
            }
        }

        /** Labels from the largest region down. */
        List<Integer> bySize() {
            List<Integer> ids = new ArrayList<>();
            for (int id = 1; id <= this.counts.size(); id++) {
                ids.add(id);
            }
            ids.sort((a, b) -> Integer.compare(this.counts.get(b - 1), this.counts.get(a - 1)));
            return ids;
        }
    }

    /** The ruler: its box (minX, minY, maxX, maxY), the box to leave out of the region, and its scale. */
    static final class Ruler {
        final int[] box;
        final int[] excluded;
        final int ticks;
        final double pxPerCm;

        private Ruler(int[] box, int[] excluded, int ticks, double pxPerCm) {
            this.box = box;
            this.excluded = excluded;
            this.ticks = ticks;
            this.pxPerCm = pxPerCm;
        }

        boolean excludes(int x, int y) {
            return x >= this.excluded[0] && x <= this.excluded[2] && y >= this.excluded[1] && y <= this.excluded[3];
        }

        /** The first light strip, largest first, whose ticks give a trusted scale; null when none does. */
        static Ruler find(Pixels pixels, int light) {
            Labels strips = new Labels(bridged(light(pixels, light), pixels.width, pixels.height), pixels.width, pixels.height);
            for (int id : strips.bySize()) {
                int[] box = strips.boxes.get(id - 1);
                int count = strips.counts.get(id - 1);
                int across = box[2] - box[0] + 1;
                int down = box[3] - box[1] + 1;
                int length = Math.max(across, down);
                int depth = Math.min(across, down);
                if (count < 500) {
                    break;
                }
                if (depth < 8 || length < MIN_ASPECT * depth || count < MIN_FILL * across * down) {
                    continue;
                }
                Ruler ruler = ticks(pixels, strips, id, box, across >= down);
                if (ruler != null) {
                    return ruler;
                }
            }
            return null;
        }

        /** Near-white (luminance at least {@code least}) and near-neutral: a ruler's face, not the background. */
        private static boolean[] light(Pixels pixels, int least) {
            boolean[] light = new boolean[pixels.rgb.length];
            for (int i = 0; i < light.length; i++) {
                int r = pixels.red(i);
                int g = pixels.green(i);
                int b = pixels.blue(i);
                light[i] = pixels.luminance(i) >= least && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 40;
            }
            return light;
        }

        /** Light with gaps of up to MAX_GAP px across or down filled, so ticks that cross the whole strip do not split it. */
        private static boolean[] bridged(boolean[] light, int width, int height) {
            boolean[] out = light.clone();
            for (int y = 0; y < height; y++) {
                int last = -1;
                for (int x = 0; x < width; x++) {
                    if (light[y * width + x]) {
                        if (last >= 0 && x - last > 1 && x - last <= MAX_GAP + 1) {
                            Arrays.fill(out, y * width + last + 1, y * width + x, true);
                        }
                        last = x;
                    }
                }
            }
            for (int x = 0; x < width; x++) {
                int last = -1;
                for (int y = 0; y < height; y++) {
                    if (light[y * width + x]) {
                        if (last >= 0 && y - last > 1 && y - last <= MAX_GAP + 1) {
                            for (int fill = last + 1; fill < y; fill++) {
                                out[fill * width + x] = true;
                            }
                        }
                        last = y;
                    }
                }
            }
            return out;
        }

        /** The strip's long ticks, each position's longest dark run across it; a ruler when they are evenly spaced. */
        private static Ruler ticks(Pixels pixels, Labels strips, int id, int[] box, boolean horizontal) {
            List<Double> faces = new ArrayList<>();
            for (int y = box[1]; y <= box[3]; y++) {
                for (int x = box[0]; x <= box[2]; x++) {
                    int i = y * pixels.width + x;
                    if (strips.label[i] == id) {
                        faces.add(pixels.luminance(i));
                    }
                }
            }
            Collections.sort(faces);
            double dark = 0.55 * faces.get(faces.size() * 3 / 4);
            int from = horizontal ? box[0] : box[1];
            int to = horizontal ? box[2] : box[3];
            int depth = horizontal ? box[3] - box[1] + 1 : box[2] - box[0] + 1;
            int[] runs = new int[to - from + 1];
            for (int t = from; t <= to; t++) {
                int run = 0;
                int best = 0;
                for (int s = 0; s < depth; s++) {
                    int x = horizontal ? t : box[0] + s;
                    int y = horizontal ? box[1] + s : t;
                    int i = y * pixels.width + x;
                    run = strips.label[i] == id && pixels.luminance(i) < dark ? run + 1 : 0;
                    best = Math.max(best, run);
                }
                runs[t - from] = best;
            }
            int shortest = Math.max(3, (int) Math.ceil(0.2 * depth));
            List<double[]> ticks = new ArrayList<>();
            for (int t = 0; t < runs.length; t++) {
                if (runs[t] < shortest) {
                    continue;
                }
                int end = t;
                int longest = 0;
                while (end < runs.length && runs[end] >= shortest) {
                    longest = Math.max(longest, runs[end]);
                    end++;
                }
                ticks.add(new double[] {from + (t + end - 1) / 2.0, longest});
                t = end;
            }
            double longest = 0;
            for (double[] tick : ticks) {
                longest = Math.max(longest, tick[1]);
            }
            List<Double> centres = new ArrayList<>();
            for (double[] tick : ticks) {
                if (tick[1] >= LONG_TICK * longest) {
                    centres.add(tick[0]);
                }
            }
            if (centres.size() < MIN_TICKS) {
                return null;
            }
            double[] spacings = new double[centres.size() - 1];
            double sum = 0;
            for (int k = 0; k < spacings.length; k++) {
                spacings[k] = centres.get(k + 1) - centres.get(k);
                sum += spacings[k];
            }
            double mean = sum / spacings.length;
            double variance = 0;
            for (double spacing : spacings) {
                variance += (spacing - mean) * (spacing - mean);
            }
            if (mean < 4 || Math.sqrt(variance / spacings.length) / mean >= MAX_TICK_CV) {
                return null;
            }
            double[] sorted = spacings.clone();
            Arrays.sort(sorted);
            double median = sorted.length % 2 == 1 ? sorted[sorted.length / 2] : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
            int margin = Math.max(4, depth / 4);
            int[] excluded = {box[0] - margin, box[1] - margin, box[2] + margin, box[3] + margin};
            return new Ruler(box, excluded, centres.size(), median);
        }
    }

    /** The region: its pixel count (holes filled), extents along and across its major axis, and whether it meets the edge. */
    static final class Region {
        final long count;
        final double length;
        final double width;
        final boolean touchesEdge;

        private Region(long count, double length, double width, boolean touchesEdge) {
            this.count = count;
            this.length = length;
            this.width = width;
            this.touchesEdge = touchesEdge;
        }

        static Region find(Pixels pixels, Ruler ruler, double smallest, Target target) {
            boolean[] mask = target == Target.RED_REGION ? redRegion(pixels, ruler) : contrasting(pixels, ruler, smallest);
            if (mask == null) {
                return null;
            }
            Labels regions = new Labels(mask, pixels.width, pixels.height);
            int id = largest(regions, smallest);
            if (id == 0) {
                return null;
            }
            int[] box = regions.boxes.get(id - 1);
            boolean[] filled = filled(regions.label, id, box, pixels.width);
            return measured(filled, box, pixels.width, pixels.height);
        }

        /** The largest region's label, or 0 when there is none or it is under the smallest size. */
        private static int largest(Labels regions, double smallest) {
            if (regions.counts.isEmpty()) {
                return 0;
            }
            int id = regions.bySize().get(0);
            return regions.counts.get(id - 1) < Math.max(30, smallest) ? 0 : id;
        }

        /** red_region: markedly redder than the border, by (green + blue) / 2 over red. */
        private static boolean[] redRegion(Pixels pixels, Ruler ruler) {
            double background = borderRatio(pixels, ruler);
            double most = Math.min(MAX_BED_RATIO, background - MIN_RATIO_DROP);
            boolean[] red = new boolean[pixels.rgb.length];
            for (int i = 0; i < red.length; i++) {
                int r = pixels.red(i);
                red[i] = r >= 60 && !ruler.excludes(i % pixels.width, i / pixels.width)
                    && (pixels.green(i) + pixels.blue(i)) / (2.0 * r) <= most;
            }
            return red;
        }

        /** The border's (green + blue) / 2 over red: the median over the photo's border, the ruler left out. */
        private static double borderRatio(Pixels pixels, Ruler ruler) {
            List<Double> ratios = new ArrayList<>();
            for (int i : border(pixels, ruler)) {
                ratios.add((pixels.green(i) + pixels.blue(i)) / (2.0 * Math.max(1, pixels.red(i))));
            }
            if (ratios.isEmpty()) {
                return 1;
            }
            Collections.sort(ratios);
            return ratios.get(ratios.size() / 2);
        }

        /**
         * contrast: a colour difference from the border's median colour of at least the noise threshold, then of at
         * least half the largest region's median difference; null when nothing differs enough.
         */
        private static boolean[] contrasting(Pixels pixels, Ruler ruler, double smallest) {
            int[] border = border(pixels, ruler);
            if (border.length == 0) {
                return null;
            }
            double[] lab = new double[3];
            double[][] channels = new double[3][border.length];
            for (int k = 0; k < border.length; k++) {
                Lab.of(pixels.rgb[border[k]], lab);
                channels[0][k] = lab[0];
                channels[1][k] = lab[1];
                channels[2][k] = lab[2];
            }
            double[] background = {median(channels[0]), median(channels[1]), median(channels[2])};
            float[] difference = new float[pixels.rgb.length];
            for (int i = 0; i < difference.length; i++) {
                Lab.of(pixels.rgb[i], lab);
                double dl = lab[0] - background[0];
                double da = lab[1] - background[1];
                double db = lab[2] - background[2];
                difference[i] = (float) Math.sqrt(dl * dl + da * da + db * db);
            }
            double[] around = new double[border.length];
            for (int k = 0; k < border.length; k++) {
                around[k] = difference[border[k]];
            }
            double noise = Math.max(MIN_DELTA_E, NOISE_FACTOR * median(around));
            boolean[] mask = over(difference, noise, pixels, ruler);
            Labels first = new Labels(mask, pixels.width, pixels.height);
            int id = largest(first, smallest);
            if (id == 0) {
                return mask;
            }
            int[] box = first.boxes.get(id - 1);
            double[] inside = new double[first.counts.get(id - 1)];
            int n = 0;
            for (int y = box[1]; y <= box[3]; y++) {
                for (int x = box[0]; x <= box[2]; x++) {
                    int i = y * pixels.width + x;
                    if (first.label[i] == id) {
                        inside[n++] = difference[i];
                    }
                }
            }
            double edge = median(inside) / 2;
            return edge > noise ? over(difference, edge, pixels, ruler) : mask;
        }

        private static boolean[] over(float[] difference, double threshold, Pixels pixels, Ruler ruler) {
            boolean[] mask = new boolean[difference.length];
            for (int i = 0; i < mask.length; i++) {
                mask[i] = difference[i] >= threshold && !ruler.excludes(i % pixels.width, i / pixels.width);
            }
            return mask;
        }

        /** The photo's border, a twentieth of its short side deep (at least 4 px), the ruler left out: the background. */
        private static int[] border(Pixels pixels, Ruler ruler) {
            int frame = Math.max(4, Math.min(pixels.width, pixels.height) / 20);
            int[] out = new int[pixels.rgb.length];
            int n = 0;
            for (int y = 0; y < pixels.height; y++) {
                for (int x = 0; x < pixels.width; x++) {
                    boolean edge = x < frame || y < frame || x >= pixels.width - frame || y >= pixels.height - frame;
                    if (edge && !ruler.excludes(x, y)) {
                        out[n++] = y * pixels.width + x;
                    }
                }
            }
            return Arrays.copyOf(out, n);
        }

        private static double median(double[] values) {
            double[] sorted = values.clone();
            Arrays.sort(sorted);
            return sorted[sorted.length / 2];
        }

        /** The region with its holes filled, over its box (one pixel wider each side): what outside does not reach. */
        private static boolean[] filled(int[] label, int id, int[] box, int width) {
            int w = box[2] - box[0] + 3;
            int h = box[3] - box[1] + 3;
            boolean[] inside = new boolean[w * h];
            for (int y = 1; y < h - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    inside[y * w + x] = label[(box[1] + y - 1) * width + box[0] + x - 1] == id;
                }
            }
            boolean[] outside = new boolean[w * h];
            int[] stack = new int[w * h];
            int top = 0;
            stack[top++] = 0;
            outside[0] = true;
            while (top > 0) {
                int i = stack[--top];
                int x = i % w;
                int y = i / w;
                int[] next = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
                for (int n : next) {
                    if (n >= 0 && !inside[n] && !outside[n]) {
                        outside[n] = true;
                        stack[top++] = n;
                    }
                }
            }
            for (int i = 0; i < inside.length; i++) {
                inside[i] = !outside[i];
            }
            return inside;
        }

        /** Extents along and across the major axis of the second moments, in whole pixels. */
        private static Region measured(boolean[] filled, int[] box, int width, int height) {
            int w = box[2] - box[0] + 3;
            long count = 0;
            double sx = 0;
            double sy = 0;
            for (int i = 0; i < filled.length; i++) {
                if (filled[i]) {
                    count++;
                    sx += i % w;
                    sy += i / w;
                }
            }
            double mx = sx / count;
            double my = sy / count;
            double xx = 0;
            double yy = 0;
            double xy = 0;
            for (int i = 0; i < filled.length; i++) {
                if (filled[i]) {
                    double dx = i % w - mx;
                    double dy = i / w - my;
                    xx += dx * dx;
                    yy += dy * dy;
                    xy += dx * dy;
                }
            }
            double angle = 0.5 * Math.atan2(2 * xy, xx - yy);
            double ux = Math.cos(angle);
            double uy = Math.sin(angle);
            double minU = Double.MAX_VALUE;
            double maxU = -Double.MAX_VALUE;
            double minV = Double.MAX_VALUE;
            double maxV = -Double.MAX_VALUE;
            for (int i = 0; i < filled.length; i++) {
                if (filled[i]) {
                    double dx = i % w - mx;
                    double dy = i / w - my;
                    double u = dx * ux + dy * uy;
                    double v = -dx * uy + dy * ux;
                    minU = Math.min(minU, u);
                    maxU = Math.max(maxU, u);
                    minV = Math.min(minV, v);
                    maxV = Math.max(maxV, v);
                }
            }
            double along = maxU - minU + 1;
            double across = maxV - minV + 1;
            boolean edge = box[0] == 0 || box[1] == 0 || box[2] == width - 1 || box[3] == height - 1;
            return new Region(count, Math.max(along, across), Math.min(along, across), edge);
        }
    }

    /** sRGB to CIE Lab (D65), for colour differences as the eye sees them. */
    static final class Lab {
        private static final double[] LINEAR = new double[256];

        static {
            for (int v = 0; v < 256; v++) {
                double c = v / 255.0;
                LINEAR[v] = c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
            }
        }

        private Lab() {
        }

        static void of(int rgb, double[] out) {
            double r = LINEAR[(rgb >> 16) & 255];
            double g = LINEAR[(rgb >> 8) & 255];
            double b = LINEAR[rgb & 255];
            double fx = f((0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047);
            double fy = f(0.2126 * r + 0.7152 * g + 0.0722 * b);
            double fz = f((0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883);
            out[0] = 116 * fy - 16;
            out[1] = 500 * (fx - fy);
            out[2] = 200 * (fy - fz);
        }

        private static double f(double t) {
            return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116;
        }
    }
}

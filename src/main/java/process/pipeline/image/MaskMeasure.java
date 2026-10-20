package process.pipeline.image;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;

/**
 * Measures the region a segmentation mask marks, in pixels (2026-10-19, for {@code measure_mask}): a mask is an image the
 * same size as the photo it belongs to, its marked pixels light (white) on a dark background -- the way public imaging
 * datasets publish an expert's outline of a lesion, an organ or a wound.
 *
 * <p>A pixel is marked when its brightness (a grey mask's value as stored; else the mean of red, green and blue, times its
 * alpha) is at least {@code threshold} (0-255). Answers the mask's size, the marked area in pixels and as a share of the image, the
 * bounding box of every marked pixel, and how many separate regions (8-connected) there are. A mask carries no scale, so
 * nothing here is in cm or mm: a physical size needs the pixel spacing, which the image's source must give.
 */
public final class MaskMeasure {

    /** The largest mask measured, by pixels (4000 x 3000); a larger one is an IOException, found before it is decoded. */
    static final long MAX_PIXELS = 12_000_000L;

    private MaskMeasure() {
    }

    /** One mask's measurement; the box is null when nothing is marked. */
    public static final class Measurement {
        public final int width;
        public final int height;
        public final long areaPx;
        public final double areaPct;
        public final Integer boxX;
        public final Integer boxY;
        public final Integer boxWidth;
        public final Integer boxHeight;
        public final int regions;
        public final long largestRegionPx;
        public final String note;

        Measurement(int width, int height, long areaPx, Integer boxX, Integer boxY, Integer boxWidth, Integer boxHeight, int regions,
                    long largestRegionPx, String note) {
            this.width = width;
            this.height = height;
            this.areaPx = areaPx;
            this.areaPct = Math.round(areaPx * 10_000.0 / ((long) width * height)) / 100.0;
            this.boxX = boxX;
            this.boxY = boxY;
            this.boxWidth = boxWidth;
            this.boxHeight = boxHeight;
            this.regions = regions;
            this.largestRegionPx = largestRegionPx;
            this.note = note;
        }
    }

    /**
     * A png or jpg mask's bytes, measured; bytes that are no image, or an image of more than {@value #MAX_PIXELS} pixels
     * (its size is read before the pixels are), are an IOException.
     */
    public static Measurement measure(byte[] bytes, int threshold) throws IOException {
        BufferedImage image = null;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers != null && readers.hasNext()) {
                ImageReader reader = readers.next();
                try {
                    reader.setInput(in, true, true);
                    long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                    if (pixels > MAX_PIXELS) {
                        throw new IOException(String.format(Locale.ROOT, "The mask is %d x %d pixels; at most %,d pixels are measured.",
                            reader.getWidth(0), reader.getHeight(0), MAX_PIXELS));
                    }
                    image = reader.read(0);
                } finally {
                    reader.dispose();
                }
            }
        } catch (IOException tooLarge) {
            if (tooLarge.getMessage() != null && tooLarge.getMessage().startsWith("The mask is ")) {
                throw tooLarge;
            }
            image = null;
        } catch (RuntimeException unreadable) {
            image = null;
        }
        if (image == null) {
            throw new IOException("The file is not a png or jpg image.");
        }
        return measure(image, threshold);
    }

    public static Measurement measure(BufferedImage mask, int threshold) {
        int width = mask.getWidth();
        int height = mask.getHeight();
        boolean[] marked = new boolean[width * height];
        long area = 0;
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = -1;
        int maxY = -1;
        boolean alpha = mask.getColorModel().hasAlpha();
        // A grey mask's samples are read as they are stored: getRGB would convert a linear grey to sRGB and brighten it.
        Raster raster = mask.getRaster();
        boolean grey = raster.getNumBands() == 1 && !(mask.getColorModel() instanceof IndexColorModel);
        double greyScale = grey ? 255.0 / ((1 << raster.getSampleModel().getSampleSize(0)) - 1) : 1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double brightness;
                if (grey) {
                    brightness = raster.getSample(x, y, 0) * greyScale;
                } else {
                    int argb = mask.getRGB(x, y);
                    brightness = (((argb >> 16) & 0xff) + ((argb >> 8) & 0xff) + (argb & 0xff)) / 3.0;
                    if (alpha) {
                        brightness *= ((argb >>> 24) & 0xff) / 255.0;
                    }
                }
                if (brightness >= threshold) {
                    marked[y * width + x] = true;
                    area++;
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (area == 0) {
            return new Measurement(width, height, 0, null, null, null, null, 0, 0,
                String.format(Locale.ROOT, "mask %d x %d px: nothing marked", width, height));
        }
        long[] regions = regions(marked, width, height);
        int count = (int) regions[0];
        long largest = regions[1];
        String note = String.format(Locale.ROOT, "mask %d x %d px: %,d px marked (%.2f%%) in %d region%s; pixels only, the mask has no scale",
            width, height, area, area * 100.0 / ((long) width * height), count, count == 1 ? "" : "s");
        return new Measurement(width, height, area, minX, minY, maxX - minX + 1, maxY - minY + 1, count, largest, note);
    }

    /** {count, largest}: the marked pixels' 8-connected regions, flood-filled with an explicit stack (no recursion). */
    private static long[] regions(boolean[] marked, int width, int height) {
        boolean[] seen = new boolean[marked.length];
        int[] stack = new int[1024];
        long count = 0;
        long largest = 0;
        for (int start = 0; start < marked.length; start++) {
            if (!marked[start] || seen[start]) {
                continue;
            }
            count++;
            long size = 0;
            int top = 0;
            stack[top++] = start;
            seen[start] = true;
            while (top > 0) {
                int p = stack[--top];
                size++;
                int px = p % width;
                int py = p / width;
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = py + dy;
                    if (ny < 0 || ny >= height) {
                        continue;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = px + dx;
                        if ((dx == 0 && dy == 0) || nx < 0 || nx >= width) {
                            continue;
                        }
                        int n = ny * width + nx;
                        if (marked[n] && !seen[n]) {
                            seen[n] = true;
                            if (top == stack.length) {
                                stack = Arrays.copyOf(stack, stack.length * 2);
                            }
                            stack[top++] = n;
                        }
                    }
                }
            }
            largest = Math.max(largest, size);
        }
        return new long[] {count, largest};
    }
}

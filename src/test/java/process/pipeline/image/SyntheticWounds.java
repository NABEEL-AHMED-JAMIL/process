package process.pipeline.image;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Random;

/**
 * Wound photos drawn with known sizes (MIG-255), after the generator of the e2e images in e2e/wound: skin with noise,
 * a red wound bed with yellow slough inside and, optionally, a pink periwound ring 0.25 cm wide around it, and a white
 * ruler with black 1 cm ticks. The truth is the red bed's outer edge -- the ring is not wound (see WoundMeasure).
 */
public final class SyntheticWounds {

    static final Color SKIN = new Color(224, 182, 150);
    static final Color PERIWOUND = new Color(196, 110, 110);
    static final Color BED = new Color(170, 40, 50);
    static final Color SLOUGH = new Color(200, 190, 90);
    static final Color RULER = new Color(250, 250, 240);

    static {
        System.setProperty("java.awt.headless", "true");
    }

    private final int width;
    private final int height;
    private final double pxPerCm;
    private Shape wound;
    private double truthLength;
    private double truthWidth;
    private double truthArea;
    private boolean periwound;
    private int rulerX = -1;
    private int rulerY;
    private int rulerCm;
    private boolean rulerVertical;
    private boolean rulerMillimetres;
    private int noise;

    private SyntheticWounds(int width, int height, double pxPerCm) {
        this.width = width;
        this.height = height;
        this.pxPerCm = pxPerCm;
    }

    public static SyntheticWounds photo(int width, int height, double pxPerCm) {
        return new SyntheticWounds(width, height, pxPerCm);
    }

    /** An elliptical wound bed lengthCm x widthCm, centred at (cx, cy) px and turned by degrees. */
    public SyntheticWounds ellipse(double lengthCm, double widthCm, double cx, double cy, double degrees) {
        double a = lengthCm * this.pxPerCm;
        double b = widthCm * this.pxPerCm;
        this.wound = AffineTransform.getRotateInstance(Math.toRadians(degrees), cx, cy)
            .createTransformedShape(new Ellipse2D.Double(cx - a / 2, cy - b / 2, a, b));
        this.truthLength = lengthCm;
        this.truthWidth = widthCm;
        this.truthArea = Math.PI * lengthCm * widthCm / 4;
        return this;
    }

    /** A rectangular wound bed lengthCm x widthCm, centred at (cx, cy) px and turned by degrees. */
    public SyntheticWounds rectangle(double lengthCm, double widthCm, double cx, double cy, double degrees) {
        double a = lengthCm * this.pxPerCm;
        double b = widthCm * this.pxPerCm;
        this.wound = AffineTransform.getRotateInstance(Math.toRadians(degrees), cx, cy)
            .createTransformedShape(new Rectangle2D.Double(cx - a / 2, cy - b / 2, a, b));
        this.truthLength = lengthCm;
        this.truthWidth = widthCm;
        this.truthArea = lengthCm * widthCm;
        return this;
    }

    public SyntheticWounds periwound() {
        this.periwound = true;
        return this;
    }

    /** A horizontal ruler of cm centimetres, its top-left corner at (x, y). */
    public SyntheticWounds ruler(int x, int y, int cm) {
        this.rulerX = x;
        this.rulerY = y;
        this.rulerCm = cm;
        return this;
    }

    public SyntheticWounds vertical() {
        this.rulerVertical = true;
        return this;
    }

    /** Shorter millimetre ticks between the centimetre ones, as most real rulers have. */
    public SyntheticWounds millimetres() {
        this.rulerMillimetres = true;
        return this;
    }

    /** Each channel of each pixel moved by up to +-amount. */
    public SyntheticWounds noise(int amount) {
        this.noise = amount;
        return this;
    }

    public double truthLength() {
        return this.truthLength;
    }

    public double truthWidth() {
        return this.truthWidth;
    }

    public double truthArea() {
        return this.truthArea;
    }

    public BufferedImage image() {
        BufferedImage image = new BufferedImage(this.width, this.height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(SKIN);
        g.fillRect(0, 0, this.width, this.height);
        if (this.wound != null) {
            if (this.periwound) {
                g.setColor(PERIWOUND);
                g.setStroke(new BasicStroke((float) (0.5 * this.pxPerCm)));
                g.draw(this.wound);
                g.fill(this.wound);
            }
            g.setColor(BED);
            g.fill(this.wound);
            Rectangle2D box = this.wound.getBounds2D();
            double r = Math.min(box.getWidth(), box.getHeight()) / 6;
            g.setColor(SLOUGH);
            g.fill(new Ellipse2D.Double(box.getCenterX() - r, box.getCenterY() - r, 2 * r, 2 * r));
        }
        if (this.rulerX >= 0) {
            this.drawRuler(g);
        }
        g.dispose();
        if (this.noise > 0) {
            Random random = new Random(255);
            for (int y = 0; y < this.height; y++) {
                for (int x = 0; x < this.width; x++) {
                    int rgb = image.getRGB(x, y);
                    int red = clamp(((rgb >> 16) & 255) + random.nextInt(2 * this.noise + 1) - this.noise);
                    int green = clamp(((rgb >> 8) & 255) + random.nextInt(2 * this.noise + 1) - this.noise);
                    int blue = clamp((rgb & 255) + random.nextInt(2 * this.noise + 1) - this.noise);
                    image.setRGB(x, y, (red << 16) | (green << 8) | blue);
                }
            }
        }
        return image;
    }

    public byte[] png() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(this.image(), "png", out);
            return out.toByteArray();
        } catch (IOException broken) {
            throw new UncheckedIOException(broken);
        }
    }

    /** As a camera saves it: jpg at the given quality (0-1), with its compression artefacts. */
    public byte[] jpeg(float quality) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(this.image(), null, null), param);
        } catch (IOException broken) {
            throw new UncheckedIOException(broken);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** The ruler is drawn along x and turned a quarter for a vertical one. */
    private void drawRuler(Graphics2D g) {
        int length = (int) Math.round(this.rulerCm * this.pxPerCm);
        int depth = (int) Math.round(this.pxPerCm);
        if (this.rulerVertical) {
            g.translate(this.rulerX + depth, this.rulerY);
            g.rotate(Math.PI / 2);
        } else {
            g.translate(this.rulerX, this.rulerY);
        }
        g.setColor(RULER);
        g.fillRect(0, 0, length, depth);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(2));
        g.drawRect(0, 0, length, depth);
        if (this.rulerMillimetres) {
            for (int mm = 1; mm < this.rulerCm * 10; mm++) {
                if (mm % 10 != 0) {
                    int x = (int) Math.round(mm * this.pxPerCm / 10);
                    g.fillRect(x, 0, 1, depth / (mm % 5 == 0 ? 3 : 5));
                }
            }
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(8, depth / 4)));
        for (int cm = 0; cm <= this.rulerCm; cm++) {
            int x = (int) Math.round(cm * this.pxPerCm);
            g.fillRect(x, 0, 2, depth / 2);
            g.drawString(String.valueOf(cm), x - 2, depth - 3);
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}

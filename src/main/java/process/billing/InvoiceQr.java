package process.billing;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

/**
 * The QR code an invoice carries: its number, so a scan on the printed page or the screen
 * names the bill -- for a payment reference, a filing system, a support ticket. The text is
 * the number itself, nothing a scanner has to unpick.
 */
public final class InvoiceQr {

    /** Pixels square: what the page asks for by default, and the least and most it may ask for. */
    public static final int DEFAULT_SIZE = 160;
    public static final int MIN_SIZE = 64;
    public static final int MAX_SIZE = 1024;

    private InvoiceQr() {}

    /** A requested size held to the bounds; none at all is the default. */
    public static int sizeOf(Integer requested) {
        return requested == null ? DEFAULT_SIZE : Math.max(MIN_SIZE, Math.min(requested, MAX_SIZE));
    }

    /** The modules of a code for the text, with a one-module quiet zone. */
    public static BitMatrix matrix(String text, int size) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 1);
        try {
            return new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
        } catch (WriterException ex) {
            throw new IllegalArgumentException("Cannot encode as a QR code: " + text, ex);
        }
    }

    /** A black-on-white PNG, `size` pixels square. */
    public static byte[] png(String text, int size) throws IOException {
        BitMatrix m = matrix(text, size);
        BufferedImage image = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < m.getHeight(); y++) {
            for (int x = 0; x < m.getWidth(); x++) {
                image.setRGB(x, y, m.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}

package process.billing;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/** The QR code on an invoice reads back as the invoice number -- from the PNG and from the rendered PDF page. */
class InvoiceQrTest {

    @Test
    void thePngDecodesToTheNumber() throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(InvoiceQr.png("INV-2026-09-0004", 200)));
        assertThat(image.getWidth()).isEqualTo(200);
        assertThat(decode(image)).isEqualTo("INV-2026-09-0004");
    }

    @Test
    void theInvoicePageCarriesItTopRight() throws Exception {
        BillingPdf.Doc doc = new BillingPdf.Doc();
        doc.title = "Invoice"; doc.number = "INV-2026-09-0004"; doc.qrText = "INV-2026-09-0004"; doc.billedTo = "MedAxis Care Network Ltd";
        doc.facts = Collections.singletonList(new String[] {"Period", "1 Sep 2026 - 30 Sep 2026"});
        doc.lines = Collections.singletonList(new BillingPdf.Line("Seats", "8", "0.50 user-day", "1.50"));
        doc.totals = Collections.singletonList(new String[] {"Total", "USD 1.50"});
        try (PDDocument pdf = PDDocument.load(BillingPdf.render(doc))) {
            BufferedImage page = new PDFRenderer(pdf).renderImageWithDPI(0, 150);
            BufferedImage corner = page.getSubimage(page.getWidth() / 2, 0, page.getWidth() / 2, page.getHeight() / 4);
            assertThat(decode(corner)).isEqualTo("INV-2026-09-0004");
        }
    }

    private static String decode(BufferedImage image) throws Exception {
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)))).getText();
    }
}

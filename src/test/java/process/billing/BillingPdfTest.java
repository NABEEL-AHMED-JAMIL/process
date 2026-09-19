package process.billing;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** The PDF says what the invoice says, in words a column can hold. */
class BillingPdfTest {

    @Test
    void ratesAndQuantitiesReadAsAPersonWould() {
        assertThat(BillingPdf.rate(new BigDecimal("0.01"), 1073741824, "byte")).isEqualTo("0.01 per GB");
        assertThat(BillingPdf.rate(new BigDecimal("0.05"), 1000, "token")).isEqualTo("0.05 / 1k token");
        assertThat(BillingPdf.rate(new BigDecimal("0.002"), 1, "run")).isEqualTo("0.002 run");
        assertThat(BillingPdf.rate(new BigDecimal("3.02"), 1, "each")).isEqualTo("3.02 each");
        assertThat(BillingPdf.quantity(new BigDecimal("41016604262"), "byte")).isEqualTo("38.2 GB");
        assertThat(BillingPdf.quantity(new BigDecimal("2048"), "byte")).isEqualTo("2 KB");
        assertThat(BillingPdf.quantity(new BigDecimal("140"), "user-day")).isEqualTo("140");
    }

    @Test
    void anInvoiceRendersEveryLineAndTotal() throws Exception {
        BillingPdf.Doc doc = new BillingPdf.Doc();
        doc.title = "Invoice"; doc.number = "INV-2026-09-0007"; doc.billedTo = "MedAxis Care Network Ltd\n14 Harbour Road"; doc.taxId = "GB 123";
        doc.facts = Arrays.asList(new String[] {"Period", "1 Sep 2026 - 30 Sep 2026"}, new String[] {"Due", "18 Oct 2026 (net 30)"});
        doc.lines = Arrays.asList(new BillingPdf.Line("Seats", "140", "0.33 user-day", "46.20"), new BillingPdf.Line("Bytes deleted (data churn)", "38.2 GB", "0.01 per GB", "0.38"));
        doc.totals = Arrays.asList(new String[] {"Subtotal", "USD 46.58"}, new String[] {"VAT 20 %", "USD 9.32"}, new String[] {"Total", "USD 55.90"});
        doc.note = "Deleting data is counted.";

        byte[] pdf = BillingPdf.render(doc);
        try (PDDocument read = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(read);
            assertThat(read.getNumberOfPages()).isEqualTo(1);
            assertThat(text).contains("INV-2026-09-0007").contains("MedAxis Care Network Ltd").contains("Tax ID GB 123")
                .contains("Bytes deleted (data churn)").contains("38.2 GB").contains("0.01 per GB").contains("VAT 20 %").contains("USD 55.90").contains("Deleting data is counted.");
        }
    }
}

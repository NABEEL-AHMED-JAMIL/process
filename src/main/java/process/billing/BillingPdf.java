package process.billing;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.util.List;

/**
 * The billing documents as PDF: an invoice (or credit note), a receipt, a statement.
 *
 * Plain, one column, pdfbox's built-in Helvetica -- a document a person files, not a brochure.
 * Every figure comes from the rows already frozen on the invoice; nothing is computed here.
 */
public final class BillingPdf {

    private static final PDFont REGULAR = PDType1Font.HELVETICA;
    private static final PDFont BOLD = PDType1Font.HELVETICA_BOLD;
    private static final float MARGIN = 50f;
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
    private static final DecimalFormat QTY = new DecimalFormat("#,##0.######");

    private BillingPdf() {}

    /** One line of a document's table. */
    public static class Line {
        public final String description; public final String quantity; public final String rate; public final String amount;
        public Line(String description, String quantity, String rate, String amount) {
            this.description = description; this.quantity = quantity; this.rate = rate; this.amount = amount;
        }
    }

    public static class Doc {
        public String title;            // Invoice · Credit note · Receipt · Statement
        public String number;
        public String issuer = "ETL Console";
        public String issuerLine = "Platform billing";
        public String billedTo;         // legal name + address, newline separated
        public String taxId;
        public List<String[]> facts;    // [label, value] pairs: Period, Issued, Due, Reference…
        public List<Line> lines;
        public List<String[]> totals;   // [label, value] pairs: Subtotal, VAT 20 %, Total, Paid, Balance
        public String note;
        public String currency = "USD";
    }

    public static String money(BigDecimal v, String currency) {
        return (currency == null ? "" : currency + " ") + MONEY.format(v == null ? BigDecimal.ZERO : v);
    }

    public static String quantity(BigDecimal v) { return QTY.format(v == null ? BigDecimal.ZERO : v); }

    /** A quantity in the unit's own terms: bytes as KB/MB/GB, the rest as numbers. */
    public static String quantity(BigDecimal v, String unit) {
        if (v == null) return "0";
        if ("byte".equals(unit)) {
            double b = v.doubleValue();
            if (b < 1024) return QTY.format(v) + " B";
            if (b < 1024 * 1024) return new DecimalFormat("#,##0.#").format(b / 1024) + " KB";
            if (b < 1024L * 1024 * 1024) return new DecimalFormat("#,##0.##").format(b / 1024 / 1024) + " MB";
            return new DecimalFormat("#,##0.##").format(b / 1024 / 1024 / 1024) + " GB";
        }
        if ("GB-hour".equals(unit)) return new DecimalFormat("#,##0.###").format(v) + " GB-h";
        if ("minute".equals(unit)) return new DecimalFormat("#,##0.###").format(v);
        return QTY.format(v);
    }

    /** "0.05 / 1k token", "0.01 per GB", "3.02 each" -- short enough for its column. */
    public static String rate(BigDecimal unitPrice, Integer per, String unit) {
        if (unitPrice == null) return "";
        String price = new DecimalFormat("#,##0.00####").format(unitPrice);
        if ("byte".equals(unit) && per != null && per == 1073741824) return price + " per GB";
        if ("each".equals(unit)) return price + " each";
        String perText = per == null || per <= 1 ? "" : per == 1000 ? " / 1k" : " / " + new DecimalFormat("#,##0").format(per);
        return price + perText + (unit == null ? "" : " " + unit);
    }

    public static byte[] render(Doc doc) throws IOException {
        try (PDDocument pdf = new PDDocument()) {
            Writer w = new Writer(pdf);
            w.text(BOLD, 20, doc.title);
            w.text(REGULAR, 11, doc.number);
            w.rightText(BOLD, 11, doc.issuer, w.top() + 22);
            w.rightText(REGULAR, 9, doc.issuerLine, w.top() + 10);
            w.gap(14);
            w.rule();
            w.gap(10);
            float twoColumnTop = w.y;
            w.text(BOLD, 9, "BILLED TO");
            for (String line : (doc.billedTo == null ? "" : doc.billedTo).split("\n")) w.text(REGULAR, 10, line);
            if (doc.taxId != null && !doc.taxId.isEmpty()) w.text(REGULAR, 9, "Tax ID " + doc.taxId);
            float leftBottom = w.y;
            w.y = twoColumnTop;
            if (doc.facts != null) {
                for (String[] fact : doc.facts) {
                    w.rightPair(fact[0], fact[1]);
                }
            }
            w.y = Math.min(leftBottom, w.y);
            w.gap(14);
            // The table.
            float[] cols = { MARGIN, 300f, 390f, 480f };
            w.text(BOLD, 9, "DESCRIPTION", "QUANTITY", "RATE", "AMOUNT", cols);
            w.gap(4);
            w.rule();
            w.gap(2);
            if (doc.lines != null) {
                for (Line line : doc.lines) {
                    w.ensureRoom(16);
                    w.text(REGULAR, 10, line.description, line.quantity, line.rate, line.amount, cols);
                }
            }
            w.gap(6);
            w.rule();
            w.gap(6);
            if (doc.totals != null) {
                for (int i = 0; i < doc.totals.size(); i++) {
                    String[] total = doc.totals.get(i);
                    boolean last = i == doc.totals.size() - 1;
                    w.ensureRoom(16);
                    w.rightPairAt(last ? BOLD : REGULAR, last ? 12 : 10, total[0], total[1]);
                }
            }
            if (doc.note != null && !doc.note.isEmpty()) {
                w.gap(16);
                w.ensureRoom(30);
                for (String line : wrap(doc.note, 95)) w.text(REGULAR, 9, line);
            }
            w.close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private static String[] wrap(String text, int width) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (current.length() + word.length() + 1 > width) { lines.add(current.toString()); current.setLength(0); }
            if (current.length() > 0) current.append(' ');
            current.append(word);
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines.toArray(new String[0]);
    }

    /** A cursor down one or more pages. */
    private static final class Writer {
        private final PDDocument pdf;
        private PDPage page;
        private PDPageContentStream stream;
        float y;
        private final float width;

        Writer(PDDocument pdf) throws IOException {
            this.pdf = pdf;
            this.width = PDRectangle.A4.getWidth();
            this.newPage();
        }

        private void newPage() throws IOException {
            if (this.stream != null) this.stream.close();
            this.page = new PDPage(PDRectangle.A4);
            this.pdf.addPage(this.page);
            this.stream = new PDPageContentStream(this.pdf, this.page);
            this.y = PDRectangle.A4.getHeight() - MARGIN;
        }

        float top() { return this.y; }
        void gap(float px) { this.y -= px; }
        void ensureRoom(float px) throws IOException { if (this.y - px < MARGIN) this.newPage(); }

        void text(PDFont font, float size, String text) throws IOException {
            this.ensureRoom(size + 4);
            this.y -= size + 2;
            this.draw(font, size, MARGIN, this.y, text);
        }

        void text(PDFont font, float size, String a, String b, String c, String d, float[] cols) throws IOException {
            this.y -= size + 4;
            this.draw(font, size, cols[0], this.y, clip(a, 52));
            this.drawRight(font, size, cols[2] - 8, this.y, b);
            this.drawRight(font, size, cols[3] - 8, this.y, c);
            this.drawRight(font, size, this.width - MARGIN, this.y, d);
        }

        void rightText(PDFont font, float size, String text, float atY) throws IOException {
            this.drawRight(font, size, this.width - MARGIN, atY, text);
        }

        void rightPair(String label, String value) throws IOException {
            this.y -= 13;
            this.drawRight(BOLD, 9, this.width - MARGIN - 130, this.y, label);
            this.drawRight(REGULAR, 10, this.width - MARGIN, this.y, value);
        }

        void rightPairAt(PDFont font, float size, String label, String value) throws IOException {
            this.y -= size + 4;
            this.drawRight(font, size, this.width - MARGIN - 110, this.y, label);
            this.drawRight(font, size, this.width - MARGIN, this.y, value);
        }

        void rule() throws IOException {
            this.stream.setLineWidth(0.6f);
            this.stream.moveTo(MARGIN, this.y);
            this.stream.lineTo(this.width - MARGIN, this.y);
            this.stream.stroke();
        }

        private void draw(PDFont font, float size, float x, float atY, String text) throws IOException {
            this.stream.beginText();
            this.stream.setFont(font, size);
            this.stream.newLineAtOffset(x, atY);
            this.stream.showText(safe(text));
            this.stream.endText();
        }

        private void drawRight(PDFont font, float size, float rightX, float atY, String text) throws IOException {
            String s = safe(text);
            float w = font.getStringWidth(s) / 1000f * size;
            this.draw(font, size, rightX - w, atY, s);
        }

        void close() throws IOException { this.stream.close(); }

        private static String clip(String s, int max) { return s == null ? "" : s.length() > max ? s.substring(0, max - 1) + "…" : s; }

        /** Helvetica's WinAnsi has no room for every glyph; a character it lacks becomes '?'. */
        private static String safe(String s) {
            if (s == null) return "";
            StringBuilder b = new StringBuilder(s.length());
            for (char c : s.toCharArray()) {
                b.append(c == '…' ? "..." : c == '·' ? "-" : c == '—' || c == '–' ? "-" : c < 32 || c > 255 ? '?' : String.valueOf(c));
            }
            return b.toString();
        }
    }
}

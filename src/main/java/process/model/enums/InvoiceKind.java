package process.model.enums;

/**
 * An invoice row is a bill or a credit note against one; both share the table, the numbering
 * and the PDF, and differ in sign.
 *
 * @author Nabeel Ahmed
 * */
public enum InvoiceKind {

    INVOICE("invoice", "INV"),
    CREDIT_NOTE("credit_note", "CN");

    private final String value;
    private final String numberPrefix;

    InvoiceKind(String value, String numberPrefix) { this.value = value; this.numberPrefix = numberPrefix; }

    public String value() { return this.value; }

    /** INV-2026-09-0004, CN-2026-09-0001: the prefix the number starts with. */
    public String numberPrefix() { return this.numberPrefix; }

    public boolean is(String value) { return this.value.equals(value); }
}

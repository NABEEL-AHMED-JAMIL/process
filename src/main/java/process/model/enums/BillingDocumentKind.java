package process.model.enums;

/**
 * What a billing document is, and the prefix of the number it carries where it has one of its
 * own (a slip is filed under its invoice and has none).
 *
 * @author Nabeel Ahmed
 * */
public enum BillingDocumentKind {

    INVOICE("invoice", "INV"),
    CREDIT_NOTE("credit_note", "CN"),
    RECEIPT("receipt", "RCP"),
    STATEMENT("statement", "STM"),
    PAYMENT_SLIP("payment_slip", null);

    private final String value;
    private final String numberPrefix;

    BillingDocumentKind(String value, String numberPrefix) { this.value = value; this.numberPrefix = numberPrefix; }

    public String value() { return this.value; }

    public String numberPrefix() { return this.numberPrefix; }

    public boolean is(String value) { return this.value.equals(value); }
}

package process.model.enums;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * How a payment came in. The four a person can choose when filing a slip, and the one the
 * console writes itself when a credit note is applied to a bill.
 *
 * @author Nabeel Ahmed
 * */
public enum PaymentMethod {

    BANK("bank"),
    CARD("card"),
    CASH("cash"),
    MANUAL("manual"),
    /** Not money: a credit note applied to the invoice it references. */
    CREDIT_NOTE("credit_note");

    private final String value;

    PaymentMethod(String value) { this.value = value; }

    public String value() { return this.value; }

    public boolean is(String stored) { return this.value.equals(stored); }

    /** What a person may name on a slip; a credit note is the console's own. */
    public boolean isChosen() { return this != CREDIT_NOTE; }

    /** The method a request named, or null when it is not one a person may choose. */
    public static PaymentMethod chosen(String value) {
        for (PaymentMethod m : values()) if (m.isChosen() && m.value.equals(value)) return m;
        return null;
    }

    public static String chosenList() {
        return Arrays.stream(values()).filter(PaymentMethod::isChosen).map(PaymentMethod::value).collect(Collectors.joining(", "));
    }
}

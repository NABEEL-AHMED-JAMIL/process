package process.model.enums;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Where a bill is in its life. Stored as the lower-case word the API and the screens use, so
 * the column reads the same as the JSON; {@link #value()} is what is persisted and compared.
 *
 * @author Nabeel Ahmed
 * */
public enum InvoiceStatus {

    /** Being built from the meter; rebuilt on every draft until issued. */
    DRAFT("draft"),
    /** Frozen, with a PDF and a due date; waiting to be paid. */
    ISSUED("issued"),
    PARTIALLY_PAID("partially_paid"),
    PAID("paid"),
    /** Issued, past due, with a balance. */
    OVERDUE("overdue"),
    /** Withdrawn before any payment; nothing is owed. */
    VOID("void");

    private final String value;

    InvoiceStatus(String value) { this.value = value; }

    public String value() { return this.value; }

    /** True when a stored status is this one. */
    public boolean is(String stored) { return this.value.equals(stored); }

    /** Issued and not yet settled: what can still be paid, credited or fall overdue. */
    public boolean isOpen() { return this == ISSUED || this == PARTIALLY_PAID || this == OVERDUE; }

    /** The states a credit note may be raised against: anything issued, paid or not. */
    public boolean isCreditable() { return this.isOpen() || this == PAID; }

    public static InvoiceStatus of(String value) {
        for (InvoiceStatus s : values()) if (s.value.equals(value)) return s;
        throw new IllegalArgumentException("No such invoice status: " + value);
    }

    /** The stored words of several statuses, for a repository's IN query. */
    public static List<String> values(InvoiceStatus... statuses) {
        return Arrays.stream(statuses).map(InvoiceStatus::value).collect(Collectors.toList());
    }
}

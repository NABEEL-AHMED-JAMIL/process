package process.model.enums;

/**
 * A payment a workspace says it made: promised until the platform verifies it, then counted
 * against the balance (with a receipt) or rejected.
 *
 * @author Nabeel Ahmed
 * */
public enum PaymentStatus {

    SUBMITTED("submitted"),
    VERIFIED("verified"),
    REJECTED("rejected");

    private final String value;

    PaymentStatus(String value) { this.value = value; }

    public String value() { return this.value; }

    public boolean is(String value) { return this.value.equals(value); }
}

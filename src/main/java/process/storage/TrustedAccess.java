package process.storage;

/**
 * The principal a trusted Storage call presents (MIG-65): which named caller, and why. It replaces the
 * boolean that used to skip the guard -- over a network this becomes a service identity; in process it
 * is a named, audited argument that only the four callers in TrustedCaller construct
 * (TrustedStorageBoundaryTest holds them to that).
 */
public final class TrustedAccess {

    private final TrustedCaller caller;
    private final String reason;

    private TrustedAccess(TrustedCaller caller, String reason) {
        this.caller = caller;
        this.reason = reason;
    }

    public static TrustedAccess of(TrustedCaller caller, String reason) {
        if (caller == null) {
            throw new IllegalStateException("A trusted storage call names its caller.");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalStateException("A trusted storage call says why.");
        }
        return new TrustedAccess(caller, reason.trim());
    }

    public TrustedCaller getCaller() {
        return this.caller;
    }

    public String getReason() {
        return this.reason;
    }
}

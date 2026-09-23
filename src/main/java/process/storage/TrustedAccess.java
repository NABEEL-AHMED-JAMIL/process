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
    private final Long tenantId;

    private TrustedAccess(TrustedCaller caller, String reason, Long tenantId) {
        this.caller = caller;
        this.reason = reason;
        this.tenantId = tenantId;
    }

    public static TrustedAccess of(TrustedCaller caller, String reason) {
        if (caller == null) {
            throw new IllegalStateException("A trusted storage call names its caller.");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalStateException("A trusted storage call says why.");
        }
        return new TrustedAccess(caller, reason.trim(), null);
    }

    /**
     * The workspace the row this call came from belongs to, so that workspace's own alias resolves
     * (MIG-53: aliases are unique per workspace). Without one, only the platform's connections do.
     */
    public TrustedAccess forTenant(Long rowTenantId) {
        return new TrustedAccess(this.caller, this.reason, rowTenantId);
    }

    public Long getTenantId() {
        return this.tenantId;
    }

    public TrustedCaller getCaller() {
        return this.caller;
    }

    public String getReason() {
        return this.reason;
    }
}

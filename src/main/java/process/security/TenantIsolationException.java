package process.security;

/**
 * The tenant filter could not be turned on, so the read it was guarding must not run (MIG-11).
 *
 * Unchecked on purpose: every caller of TenantFilterHelper.enableIfNeeded is about to read, and
 * none of them has anything better to do than stop. The controllers' catch-all turns it into a 500,
 * which is the point -- a failed request, not a quietly unscoped one.
 *
 * @author Nabeel Ahmed
 */
public class TenantIsolationException extends RuntimeException {

    public TenantIsolationException(String message, Throwable cause) {
        super(message, cause);
    }
}

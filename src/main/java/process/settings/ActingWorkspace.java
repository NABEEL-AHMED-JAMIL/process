package process.settings;

import org.barco.platform.tenancy.TenantScope;
import process.identity.IdentityPort;
import process.security.TenantContext;

import java.sql.Timestamp;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Which workspace a configuration write acts on, and whether the caller may touch a row (MIG-167).
 *
 * A tenant admin acts for their own workspace only; whatever tenantId they send is ignored. A platform admin has no
 * workspace of his own and must name the one he acts for, and it must exist. A row of another workspace answers
 * exactly as a row that does not exist, so a refusal never confirms an id.
 */
final class ActingWorkspace {

    private ActingWorkspace() {
    }

    /** Null when the workspace is decided (passed to onResolved); otherwise the sentence saying why it is not. */
    static String resolve(IdentityPort identity, Long requested, Consumer<Long> onResolved) {
        if (!TenantContext.isPlatformAdmin()) {
            if (TenantContext.getTenantId() == null) {
                return "Your account belongs to no workspace.";
            }
            onResolved.accept(TenantContext.getTenantId());
            return null;
        }
        if (requested == null) {
            return "Say which workspace this is for: a platform administrator acts for one workspace at a time.";
        }
        if (!IdentityPort.live(identity, requested).isPresent()) {
            return String.format("Workspace %d is not a workspace.", requested);
        }
        onResolved.accept(requested);
        return null;
    }

    /**
     * The workspace a list shows: a tenant admin's own; a platform admin's choice, or every one (null). Null means
     * "every workspace" to the callers, so it is a platform admin's answer only: a caller with no workspace of their
     * own gets {@link TenantScope#NO_TENANT_MATCHES}, which no row carries, and an empty list (MIG-166). It used to be
     * handed their null, and with it every workspace's configuration values and home pages.
     */
    static Long forList(Long requested) {
        if (TenantContext.isPlatformAdmin()) {
            return requested;
        }
        Long mine = TenantContext.getTenantId();
        return mine == null ? TenantScope.NO_TENANT_MATCHES : mine;
    }

    static boolean mayTouch(Long rowTenantId) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        return rowTenantId != null && Objects.equals(rowTenantId, TenantContext.getTenantId());
    }

    static String iso(Timestamp at) {
        return at == null ? null : at.toInstant().toString();
    }
}

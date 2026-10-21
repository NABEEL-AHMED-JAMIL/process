package process.security;

import org.barco.platform.security.WorkspaceDataGuard;

/**
 * ZT-A12 follow-up (owner 2026-10-21, "Managed session only") below the edge: WorkspaceDataGuard refuses a platform
 * administrator's own sign-in (PLATFORM_ADMIN, no workspace) before a {@code @WorkspaceData} handler runs; the services
 * behind a run's outputs, a run dataset, the schedule list and the run list ask here too, so the rule holds for a caller
 * that does not come through the HTTP layer.
 *
 * Core's caller is its own TenantContext (the JWT filter fills it, and ManagementMode beside it), so both are asked. A
 * managed-service session is TENANT_ADMIN of its one workspace and passes; a scheduler, a listener or a worker's
 * callback has no caller at all and passes (row security scopes what it reads).
 *
 * @author Nabeel Ahmed
 */
public final class ManagedSessionOnly {

    /** The sentence the console shows: WorkspaceDataGuard's. */
    public static final String REFUSAL = WorkspaceDataGuard.REFUSAL;

    private ManagedSessionOnly() {}

    /** The caller is a platform administrator on their own sign-in. */
    public static boolean isPlatformOwnSignIn() {
        return TenantContext.isPlatformAdmin() || WorkspaceDataGuard.isPlatformOwnSignIn();
    }
}

package process.security;

/**
 * Per-request identity, populated by JwtAuthenticationFilter from the validated JWT's claims
 * and cleared at the end of every request (see JwtAuthenticationFilter's finally block) --
 * ThreadLocal, so it must never be read outside the thread handling the current request (no
 * @Async/manual thread hops without explicitly propagating it).
 * @author Nabeel Ahmed
 */
public final class TenantContext {

    private static final ThreadLocal<Long> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ROLE = new ThreadLocal<>();
    private static final ThreadLocal<Long> APP_USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Long tenantId, String userRole, Long appUserId, String username) {
        TENANT_ID.set(tenantId);
        USER_ROLE.set(userRole);
        APP_USER_ID.set(appUserId);
        USERNAME.set(username);
    }

    /** Null for a PLATFORM_ADMIN request (not scoped to any tenant). */
    public static Long getTenantId() {
        return TENANT_ID.get();
    }

    public static String getUserRole() {
        return USER_ROLE.get();
    }

    public static Long getAppUserId() {
        return APP_USER_ID.get();
    }

    public static String getUsername() {
        return USERNAME.get();
    }

    public static boolean isPlatformAdmin() {
        return "PLATFORM_ADMIN".equals(USER_ROLE.get());
    }

    public static void clear() {
        TENANT_ID.remove();
        USER_ROLE.remove();
        APP_USER_ID.remove();
        USERNAME.remove();
    }

}

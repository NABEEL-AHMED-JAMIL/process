package process.security;

import org.barco.platform.tenancy.TenantScope;
import org.slf4j.MDC;

/**
 * @author Nabeel Ahmed
 * */
public final class TenantContext {

    /** The logging MDC keys the log pattern prints (MIG-43); the correlation id's is CorrelationId.MDC_KEY. */
    public static final String MDC_TENANT = "tenantId";

    public static final String MDC_USER = "userId";

    private static final ThreadLocal<Long> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER_ROLE = new ThreadLocal<>();
    private static final ThreadLocal<Long> APP_USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME = new ThreadLocal<>();
    private static final ThreadLocal<TenantScope> SCOPE = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Long tenantId, String userRole, Long appUserId, String username) {
        TENANT_ID.set(tenantId);
        USER_ROLE.set(userRole);
        APP_USER_ID.set(appUserId);
        USERNAME.set(username);
        SCOPE.remove();
        // On every log line while this caller is set (MIG-43), beside the correlation id.
        putOrRemove(MDC_TENANT, tenantId);
        putOrRemove(MDC_USER, appUserId);
    }

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

    /**
     * Which tenants this request may see (MIG-93), resolved once per request: a platform admin's
     * all-tenants grant is written to the audit log once, not once per row it touches. A caller with no
     * tenant, or a tenant that is not a real id, is scoped to nothing.
     */
    public static TenantScope scope() {
        TenantScope scope = SCOPE.get();
        if (scope == null) {
            scope = TenantScope.of(TENANT_ID.get(), USER_ROLE.get(), APP_USER_ID.get());
            SCOPE.set(scope);
        }
        return scope;
    }

    public static boolean isPlatformAdmin() {
        return "PLATFORM_ADMIN".equals(USER_ROLE.get());
    }

    public static void clear() {
        TENANT_ID.remove();
        USER_ROLE.remove();
        APP_USER_ID.remove();
        USERNAME.remove();
        SCOPE.remove();
        MDC.remove(MDC_TENANT);
        MDC.remove(MDC_USER);
    }

    private static void putOrRemove(String key, Long value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, String.valueOf(value));
        }
    }

}

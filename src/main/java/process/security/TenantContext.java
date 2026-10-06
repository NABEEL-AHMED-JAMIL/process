package process.security;

import org.barco.platform.tenancy.TenantScope;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

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
    /** MIG-332: the customer API's caller, when it is an API client: its public id and scopes. */
    private static final ThreadLocal<String> CLIENT_ID = new ThreadLocal<>();
    private static final ThreadLocal<Set<String>> CLIENT_SCOPES = new ThreadLocal<>();

    /** The role an API client of the customer API holds: platform-commons' TenantContext.API_CLIENT, the same word. */
    public static final String API_CLIENT = "API_CLIENT";

    private TenantContext() {}

    /**
     * The caller as the token names them. A tenant id that is not a real one -- 0, or anything below it; tenant ids
     * start at 1000 -- is stored as no tenant at all (MIG-166). {@link #scope()} already read it that way, but about
     * forty places read {@link #getTenantId()} directly: to file a new row, to pick a list, to compare an owner. Taken
     * at face value a 0 filed a row under a workspace that does not exist, and was the one value some of those paths
     * had once meant as "every workspace". Deciding it here, once, puts every one of them on the no-workspace path,
     * which fails closed.
     */
    public static void set(Long tenantId, String userRole, Long appUserId, String username) {
        Long workspace = tenantId == null || tenantId <= 0 ? null : tenantId;
        TENANT_ID.set(workspace);
        USER_ROLE.set(userRole);
        APP_USER_ID.set(appUserId);
        USERNAME.set(username);
        SCOPE.remove();
        CLIENT_ID.remove();
        CLIENT_SCOPES.remove();
        // On every log line while this caller is set (MIG-43), beside the correlation id.
        putOrRemove(MDC_TENANT, workspace);
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

    /**
     * MIG-332: the caller is this API client, with these scopes; set after {@link #set} by JwtAuthenticationFilter for a
     * customer API request (role {@value #API_CLIENT}, no person).
     */
    public static void setApiClient(String clientId, Set<String> scopes) {
        CLIENT_ID.set(clientId);
        CLIENT_SCOPES.set(scopes == null ? Collections.<String>emptySet() : Collections.unmodifiableSet(new LinkedHashSet<>(scopes)));
    }

    /** The API client's public id, or null for a person or nobody. */
    public static String getClientId() {
        return CLIENT_ID.get();
    }

    /** Whether the caller is an API client of the customer API. */
    public static boolean isApiClient() {
        return CLIENT_ID.get() != null && API_CLIENT.equals(USER_ROLE.get());
    }

    /** Whether the caller is an API client holding this scope. */
    public static boolean hasScope(String scope) {
        Set<String> scopes = CLIENT_SCOPES.get();
        return isApiClient() && scopes != null && scopes.contains(scope);
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
        CLIENT_ID.remove();
        CLIENT_SCOPES.remove();
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

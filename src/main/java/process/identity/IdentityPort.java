package process.identity;

import org.barco.platform.security.CallerIdentity;
import org.barco.platform.tenancy.TenantScope;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything the rest of process asks of Identity & Tenancy, behind one interface (MIG-93).
 *
 * Authentication, the directory of people and workspaces, and page access -- the four things Core,
 * the engine, the notification outbox and the security filters used to reach by reading app_user,
 * tenant and the page-access tables themselves. Identity keeps all six of its tables together behind
 * this port (the app_user / page_access_profile foreign-key cycle stays in one place); nothing outside
 * it names an Identity repository or entity. {@link LocalIdentity} answers from the monolith's own
 * tables (identity.mode=local, the default); {@link HttpIdentity} asks identity-service (identity.mode=
 * remote, MIG-107). Nothing that calls the port changes between them.
 *
 * Remotely, a directory read can fail: it throws {@link Unavailable}, and a caller that must never fail
 * (the notification outbox, the name resolver) says in its own code what it does instead.
 *
 * @author Nabeel Ahmed
 */
public interface IdentityPort {

    /** The code of the workspace seeded at first start, which owns what a platform admin files with no tenant. */
    String DEFAULT_WORKSPACE_CODE = "default";

    /**
     * The caller behind a bearer token: an access token, well signed, unexpired, and not revoked.
     * Empty for anything else -- including when revocations cannot be checked, which fails closed.
     */
    Optional<CallerIdentity> authenticate(String bearerToken);

    /**
     * MIG-332: the API client behind a customer API token (type client): well signed, unexpired, not revoked and not
     * minted under an older client version. Empty for anything else, a person's token included. Only identity-service's
     * own verification (identity.mode=remote) knows client tokens; in-process identity accepts none.
     */
    default Optional<CallerIdentity> authenticateClient(String bearerToken) {
        return Optional.empty();
    }

    /**
     * MIG-335: whether the API client that made a view link may still act -- active, unexpired, its workspace active (a
     * revoke ends its links at once) -- and the origins that may frame the view. Asked of identity-service for every view
     * page and read; never cached. Throws {@link Unavailable} when Identity cannot answer. In-process identity knows no
     * API clients and refuses every link.
     */
    default EmbedClient embedClient(long tenantId, String clientId) {
        return EmbedClient.refused(404);
    }

    /** MIG-335: Identity's answer about a view link's client. */
    final class EmbedClient {
        public final boolean active;
        /** 404 when no such client in the link's workspace, 410 when it may no longer act; 200 when active. */
        public final int status;
        public final List<String> frameAncestors;

        public EmbedClient(boolean active, int status, List<String> frameAncestors) {
            this.active = active;
            this.status = status;
            this.frameAncestors = frameAncestors == null ? Collections.<String>emptyList() : frameAncestors;
        }

        public static EmbedClient refused(int status) {
            return new EmbedClient(false, status, null);
        }
    }

    /** A person, deleted ones included (callers decide what a deleted person means to them). */
    Optional<Person> person(Long appUserId);

    /** People by id, whatever tenant they are in; ids that name nobody are absent. */
    Map<Long, Person> people(Collection<Long> appUserIds);

    /** A workspace, if it exists. */
    Optional<Workspace> workspace(Long tenantId);

    /** A workspace by its code, if one has it. */
    Optional<Workspace> workspaceByCode(String code);

    /**
     * A workspace a write may name (MIG-166): one that exists and Identity has not deleted. With no foreign key
     * onto tenant, this is what stops a platform administrator filing rows under a workspace that is gone.
     * Static, so a test's mock answers it through {@link #workspace} like everything else.
     */
    static Optional<Workspace> live(IdentityPort identity, Long tenantId) {
        if (identity == null || tenantId == null) {
            return Optional.empty();
        }
        return identity.workspace(tenantId).filter(workspace -> !workspace.isDeleted());
    }

    /** Workspaces by id; ids that name none are absent. */
    List<Workspace> workspaces(Collection<Long> tenantIds);

    /**
     * The people who are not deleted, in this scope: every workspace's for AllTenants, one workspace's
     * otherwise, nobody for a caller scoped to nothing. The dashboard's people list (MIG-107): it used to
     * be a join on app_user inside Core's own statistics query.
     */
    List<Person> members(TenantScope scope);

    /** How many people a workspace has who are not deleted: its seats. */
    long seats(Long tenantId);

    /**
     * Whether this caller may use this API path under their page access: the page gate's decision and,
     * when refused, its sentence -- the one PageAccessInterceptor writes.
     */
    PageDecision pageDecision(String userRole, Long appUserId, String servletPath);

    /** Identity could not be asked (identity.mode=remote): unreachable, or it answered with an error. */
    final class Unavailable extends RuntimeException {
        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** What crosses the port about a person: never a password, never a token. */
    final class Person {
        private final Long appUserId;
        private final Long tenantId;
        private final String username;
        private final String fullName;
        private final String userRole;
        private final String status;
        private final String avatarBucket;
        private final String avatarKey;

        public Person(Long appUserId, Long tenantId, String username, String fullName, String userRole, String status) {
            this(appUserId, tenantId, username, fullName, userRole, status, null, null);
        }

        public Person(Long appUserId, Long tenantId, String username, String fullName, String userRole, String status,
            String avatarBucket, String avatarKey) {
            this.appUserId = appUserId;
            this.tenantId = tenantId;
            this.username = username;
            this.fullName = fullName;
            this.userRole = userRole;
            this.status = status;
            this.avatarBucket = avatarBucket;
            this.avatarKey = avatarKey;
        }

        public Long getAppUserId() { return this.appUserId; }

        /** Null for a platform administrator, who belongs to no workspace. */
        public Long getTenantId() { return this.tenantId; }

        public String getUsername() { return this.username; }

        public String getFullName() { return this.fullName; }

        public String getUserRole() { return this.userRole; }

        public String getStatus() { return this.status; }

        /** Where the person's picture is, if they have one: the object key, never the bytes. */
        public String getAvatarBucket() { return this.avatarBucket; }

        public String getAvatarKey() { return this.avatarKey; }

        public boolean isDeleted() { return "Delete".equals(this.status); }

        /** The name a screen shows: the full name where there is one, else the username (never blank). */
        public String getDisplayName() {
            return this.fullName == null || this.fullName.trim().isEmpty() ? this.username : this.fullName.trim();
        }
    }

    /** What crosses the port about a workspace. */
    final class Workspace {
        private final Long tenantId;
        private final String name;
        private final String code;
        private final String status;

        public Workspace(Long tenantId, String name, String code, String status) {
            this.tenantId = tenantId;
            this.name = name;
            this.code = code;
            this.status = status;
        }

        public Long getTenantId() { return this.tenantId; }

        public String getName() { return this.name; }

        public String getCode() { return this.code; }

        public String getStatus() { return this.status; }

        public boolean isDeleted() { return "Delete".equals(this.status); }
    }

    /** The page gate's answer. */
    final class PageDecision {
        private final boolean allowed;
        private final String message;

        public PageDecision(boolean allowed, String message) {
            this.allowed = allowed;
            this.message = message;
        }

        public boolean isAllowed() { return this.allowed; }

        public String getMessage() { return this.message; }
    }
}

package process.identity;

import org.barco.platform.security.CallerIdentity;

import java.util.Collection;
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
 * it names an Identity repository or entity. Today {@link LocalIdentity} answers from the monolith's
 * own tables; when Identity is its own service only the implementation changes.
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

    /** A person, deleted ones included (callers decide what a deleted person means to them). */
    Optional<Person> person(Long appUserId);

    /** People by id, whatever tenant they are in; ids that name nobody are absent. */
    Map<Long, Person> people(Collection<Long> appUserIds);

    /** A workspace, if it exists. */
    Optional<Workspace> workspace(Long tenantId);

    /** A workspace by its code, if one has it. */
    Optional<Workspace> workspaceByCode(String code);

    /** Workspaces by id; ids that name none are absent. */
    List<Workspace> workspaces(Collection<Long> tenantIds);

    /** Every workspace that is not deleted, newest first. */
    List<Workspace> liveWorkspaces();

    /** How many people a workspace has who are not deleted: its seats. */
    long seats(Long tenantId);

    /**
     * Whether this caller may use this API path under their page access: the page gate's decision and,
     * when refused, its sentence -- the one PageAccessInterceptor writes.
     */
    PageDecision pageDecision(String userRole, Long appUserId, String servletPath);

    /** What crosses the port about a person: never a password, never a token. */
    final class Person {
        private final Long appUserId;
        private final Long tenantId;
        private final String username;
        private final String fullName;
        private final String userRole;
        private final String status;

        public Person(Long appUserId, Long tenantId, String username, String fullName, String userRole, String status) {
            this.appUserId = appUserId;
            this.tenantId = tenantId;
            this.username = username;
            this.fullName = fullName;
            this.userRole = userRole;
            this.status = status;
        }

        public Long getAppUserId() { return this.appUserId; }
        /** Null for a platform administrator, who belongs to no workspace. */
        public Long getTenantId() { return this.tenantId; }
        public String getUsername() { return this.username; }
        public String getFullName() { return this.fullName; }
        public String getUserRole() { return this.userRole; }
        public String getStatus() { return this.status; }

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

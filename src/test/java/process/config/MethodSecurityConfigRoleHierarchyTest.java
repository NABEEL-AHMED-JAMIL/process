package process.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hierarchy every @PreAuthorize in the application leans on.
 *
 * Endpoints are annotated with the least role that may reach them -- hasRole('TENANT_USER') on a
 * read, hasRole('TENANT_ADMIN') on a change -- and are correct only because a higher role reaches
 * a lower one through this hierarchy. Were it dropped or mistyped, a platform admin would simply
 * be locked out of the endpoints it owns, while a downward edge added by accident would hand a
 * tenant user everything. Neither shows up in a compile.
 *
 * The config object is used directly rather than a copy of its hierarchy string, so a change to
 * that string is what this test sees.
 *
 * @author Nabeel Ahmed
 */
public class MethodSecurityConfigRoleHierarchyTest {

    private static final String PLATFORM_ADMIN = "ROLE_PLATFORM_ADMIN";
    private static final String TENANT_ADMIN = "ROLE_TENANT_ADMIN";
    private static final String TENANT_USER = "ROLE_TENANT_USER";

    private final RoleHierarchy hierarchy = new MethodSecurityConfig().roleHierarchy();

    /**
     * What hasRole(required) decides for a caller holding held: the expression handler asks the
     * hierarchy what that one authority reaches and looks for the required one in the answer.
     */
    private boolean satisfies(String held, String required) {
        Collection<? extends GrantedAuthority> reachable = this.hierarchy.getReachableGrantedAuthorities(
            Collections.<GrantedAuthority>singletonList(new SimpleGrantedAuthority(held)));
        if (reachable == null) {
            return false;
        }
        for (GrantedAuthority authority : reachable) {
            if (required.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aPlatformAdminReachesBothLowerRoles() {
        assertTrue(this.satisfies(PLATFORM_ADMIN, TENANT_ADMIN),
            "hasRole('TENANT_ADMIN') must admit a platform admin");
        // Two steps down, so this also proves the hierarchy is transitive and not one step only.
        assertTrue(this.satisfies(PLATFORM_ADMIN, TENANT_USER),
            "hasRole('TENANT_USER') must admit a platform admin");
    }

    @Test
    void aTenantAdminReachesATenantUser() {
        assertTrue(this.satisfies(TENANT_ADMIN, TENANT_USER),
            "hasRole('TENANT_USER') must admit a tenant admin");
    }

    @Test
    void nobodyReachesUpward() {
        assertFalse(this.satisfies(TENANT_USER, TENANT_ADMIN),
            "a tenant user must not satisfy hasRole('TENANT_ADMIN')");
        assertFalse(this.satisfies(TENANT_USER, PLATFORM_ADMIN),
            "a tenant user must not satisfy hasRole('PLATFORM_ADMIN')");
        assertFalse(this.satisfies(TENANT_ADMIN, PLATFORM_ADMIN),
            "a tenant admin must not satisfy hasRole('PLATFORM_ADMIN')");
    }

    @Test
    void everyRoleStillSatisfiesItself() {
        // getReachableGrantedAuthorities returns the held authority too; an endpoint annotated
        // with a caller's own role would otherwise refuse them.
        assertTrue(this.satisfies(TENANT_USER, TENANT_USER));
        assertTrue(this.satisfies(TENANT_ADMIN, TENANT_ADMIN));
        assertTrue(this.satisfies(PLATFORM_ADMIN, PLATFORM_ADMIN));
    }

}

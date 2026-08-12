package process.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.method.configuration.GlobalMethodSecurityConfiguration;

/**
 * Turns on @PreAuthorize across the *RestApi controllers (see each controller for its own
 * class/method-level annotations -- role policy is documented there, not here) and wires in a
 * role hierarchy so PLATFORM_ADMIN > TENANT_ADMIN > TENANT_USER: annotating a method with the
 * minimum role that should reach it (e.g. hasRole('TENANT_ADMIN')) automatically also admits
 * every role above it in the hierarchy, instead of every check needing to spell out
 * hasAnyRole('PLATFORM_ADMIN','TENANT_ADMIN').
 * @author Nabeel Ahmed
 */
@Configuration
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class MethodSecurityConfig extends GlobalMethodSecurityConfiguration {

    @Override
    protected MethodSecurityExpressionHandler createExpressionHandler() {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setRoleHierarchy(this.roleHierarchy());
        return handler;
    }

    @Bean
    public RoleHierarchy roleHierarchy() {
        RoleHierarchyImpl hierarchy = new RoleHierarchyImpl();
        hierarchy.setHierarchy("ROLE_PLATFORM_ADMIN > ROLE_TENANT_ADMIN\nROLE_TENANT_ADMIN > ROLE_TENANT_USER");
        return hierarchy;
    }

}

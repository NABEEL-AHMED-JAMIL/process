package process.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * @author Nabeel Ahmed
 * *
 * Spring Security 6.3+ wires the RoleHierarchy bean into @EnableMethodSecurity's expression handler (MIG-389).
 */
@Configuration
@EnableMethodSecurity
public class MethodSecurityConfig {

    @Bean
    public static RoleHierarchy roleHierarchy() {
        return RoleHierarchyImpl.fromHierarchy("ROLE_PLATFORM_ADMIN > ROLE_TENANT_ADMIN\nROLE_TENANT_ADMIN > ROLE_TENANT_USER");
    }
}

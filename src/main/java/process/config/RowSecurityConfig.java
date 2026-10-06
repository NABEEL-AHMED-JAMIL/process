package process.config;

import org.barco.platform.tenancy.RowSecurityCaller;
import org.barco.platform.tenancy.RowSecurityConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import process.security.TenantContext;

/**
 * Postgres row-level security's hooks (MIG-258): platform-commons' RowSecurityConfiguration puts the caller on every
 * connection -- app.tenant_id and app.all_tenants, which every tenant table's policy reads (V181) -- and runs the
 * {@code @AcrossTenants} paths across workspaces. The caller is Core's own TenantContext, not platform-commons': the
 * JWT filter sets this one.
 *
 * What the session works for: the token's workspace; every workspace for a platform administrator or inside an
 * across-tenants grant (each one listed, with its reason, in RowSecurityContractTest); the run's workspace for a
 * worker's callback (RowSecurity.forTenant); and nothing at all otherwise -- a thread no one set sees no tenant rows
 * and may write none.
 *
 * @author Nabeel Ahmed
 */
@Configuration
@Import(RowSecurityConfiguration.class)
public class RowSecurityConfig {

    @Bean
    public RowSecurityCaller rowSecurityCaller() {
        return CORE_CALLER;
    }

    /** Core's TenantContext as row security reads it; a tenant id of 0 or below is already none there. */
    public static final RowSecurityCaller CORE_CALLER = new RowSecurityCaller() {
        @Override
        public Long tenantId() {
            return TenantContext.getTenantId();
        }

        @Override
        public boolean isPlatformAdmin() {
            return TenantContext.isPlatformAdmin();
        }
    };
}

package process.model.enums;

/**
 * PLATFORM_ADMIN manages tenants + platform config, has tenantId=null, and is exempt from
 * tenant-scoped query filtering (see TenantContext/TenantFilterHelper). TENANT_ADMIN
 * manages users/settings within their own tenant. TENANT_USER is a regular user scoped to
 * their tenant's data.
 * @author Nabeel Ahmed
 */
public enum UserRole {
    PLATFORM_ADMIN, TENANT_ADMIN, TENANT_USER
}

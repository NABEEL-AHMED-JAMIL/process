package process.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ownership rule used to live as a private copy in every service, and the copies had drifted
 * apart over the tenant-less rows. Now that they all route through one method, this is where that
 * rule is pinned down -- in particular the two null cases, which are the ones that decide whether
 * a tenant can reach the platform's records or an unscoped context can reach everybody's.
 *
 * Driven through TenantContext because that is what the helper actually reads; no server or
 * minted token is needed to set it.
 *
 * @author Nabeel Ahmed
 * */
class TenantOwnershipTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 2002L;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void aPlatformAdminOwnsEveryTenantsRows() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@example.com");

        assertThat(TenantOwnership.isOwnedByCaller(TENANT_A)).isTrue();
        assertThat(TenantOwnership.isOwnedByCaller(TENANT_B)).isTrue();
    }

    @Test
    void aPlatformAdminOwnsThePlatformOwnedRowsToo() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@example.com");

        assertThat(TenantOwnership.isOwnedByCaller(null)).isTrue();
    }

    @Test
    void aTenantOwnsItsOwnRows() {
        TenantContext.set(TENANT_A, "TENANT_USER", 7L, "user@a.example.com");

        assertThat(TenantOwnership.isOwnedByCaller(TENANT_A)).isTrue();
    }

    @Test
    void aTenantDoesNotOwnAnotherTenantsRows() {
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 7L, "admin@a.example.com");

        assertThat(TenantOwnership.isOwnedByCaller(TENANT_B)).isFalse();
    }

    @Test
    void aTenantDoesNotOwnAPlatformOwnedRow() {
        // The rule the copies disagreed on: a null tenantId is the platform's, not nobody's.
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 7L, "admin@a.example.com");

        assertThat(TenantOwnership.isOwnedByCaller(null)).isFalse();
    }

    @Test
    void aCallerWithNoTenantOwnsNothing() {
        // Objects.equals(null, null) used to hand every platform-owned row to an unscoped caller.
        TenantContext.set(null, "TENANT_USER", 7L, "orphan@example.com");

        assertThat(TenantOwnership.isOwnedByCaller(null)).isFalse();
        assertThat(TenantOwnership.isOwnedByCaller(TENANT_A)).isFalse();
    }

    @Test
    void anEmptyContextOwnsNothing() {
        // No TenantContext at all -- a code path that forgot to set it must not be trusted.
        assertThat(TenantOwnership.isOwnedByCaller(null)).isFalse();
        assertThat(TenantOwnership.isOwnedByCaller(TENANT_A)).isFalse();
    }

    // ---- the shared catalogues -----------------------------------------------------------

    @Test
    void aPlatformOwnedRowIsVisibleToEveryTenant() {
        TenantContext.set(TENANT_A, "TENANT_USER", 7L, "user@a.example.com");

        assertThat(TenantOwnership.isVisibleToCaller(null)).isTrue();
    }

    @Test
    void visibilityStillStopsAtAnotherTenantsRows() {
        TenantContext.set(TENANT_A, "TENANT_USER", 7L, "user@a.example.com");

        assertThat(TenantOwnership.isVisibleToCaller(TENANT_A)).isTrue();
        assertThat(TenantOwnership.isVisibleToCaller(TENANT_B)).isFalse();
    }

    @Test
    void aCallerWithNoTenantSeesOnlyTheSharedRows() {
        TenantContext.set(null, "TENANT_USER", 7L, "orphan@example.com");

        assertThat(TenantOwnership.isVisibleToCaller(null)).isTrue();
        assertThat(TenantOwnership.isVisibleToCaller(TENANT_A)).isFalse();
    }

}

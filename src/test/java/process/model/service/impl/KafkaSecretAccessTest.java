package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.repository.AppUserRepository;
import process.model.service.KafkaSecretService;
import process.security.TenantContext;
import process.util.KafkaSecretPath;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Who may point a Kafka connection profile at a stored certificate.
 *
 * This is the check that stands in for the storage layer's: the Kafka client is built on threads
 * with no principal, so the file itself is read through the trusted path that asks nothing. If
 * this is wrong, nothing downstream will catch it.
 */
class KafkaSecretAccessTest {

    private static final LocalDate DAY = LocalDate.of(2026, 8, 31);
    private static final Long OWNER = 1248L;
    private static final Long OWNER_TENANT = 5L;

    private final AppUserRepository appUserRepository = mock(AppUserRepository.class);
    private final KafkaSecretService service =
        new KafkaSecretServiceImpl(null, this.appUserRepository, null);

    private final String ownersKey = KafkaSecretPath.newUpload(OWNER, "ca.pem", DAY).key();

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private void ownerIsInTenant(Long tenantId) {
        this.ownerIsInTenant(tenantId, UserRole.TENANT_USER);
    }

    private void ownerIsInTenant(Long tenantId, UserRole role) {
        AppUser owner = new AppUser();
        owner.setTenantId(tenantId);
        owner.setUserRole(role);
        when(this.appUserRepository.findById(anyLong())).thenReturn(Optional.of(owner));
    }

    @Test
    void theUploaderMayUseTheirOwnFile() {
        TenantContext.set(OWNER_TENANT, "TENANT_USER", OWNER, "owner@example.com");
        assertThat(this.service.canUseObject(KafkaSecretService.SECRET_BUCKET, this.ownersKey)).isTrue();
    }

    @Test
    void aPlatformAdminMayUseAnybodys() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        assertThat(this.service.canUseObject(KafkaSecretService.SECRET_BUCKET, this.ownersKey)).isTrue();
    }

    /** A tenant admin owns its tenant's connections, so it has to be able to finish one. */
    @Test
    void aTenantAdminMayUseAFileUploadedBySomebodyInItsOwnTenant() {
        this.ownerIsInTenant(OWNER_TENANT);
        TenantContext.set(OWNER_TENANT, "TENANT_ADMIN", 99L, "admin@tenant.example");
        assertThat(this.service.canUseObject(KafkaSecretService.SECRET_BUCKET, this.ownersKey)).isTrue();
    }

    /**
     * A tenant admin's authority covers the tenant users it manages and stops there. A peer's
     * client key is the credential that authenticates them to a broker, so lending it out would
     * let one administrator produce and consume as another.
     */
    @Test
    void aTenantAdminMayNotUseAPeerAdminsFile() {
        this.ownerIsInTenant(OWNER_TENANT, UserRole.TENANT_ADMIN);
        TenantContext.set(OWNER_TENANT, "TENANT_ADMIN", 99L, "admin@tenant.example");
        assertThat(this.service.canUseObject(KafkaSecretService.SECRET_BUCKET, this.ownersKey)).isFalse();
    }

    /** Nor a platform admin's, who is in no tenant at all and so was never reachable anyway. */
    @Test
    void aTenantAdminMayNotUseAPlatformAdminsFile() {
        this.ownerIsInTenant(null, UserRole.PLATFORM_ADMIN);
        TenantContext.set(OWNER_TENANT, "TENANT_ADMIN", 99L, "admin@tenant.example");
        assertThat(this.service.canUseObject(KafkaSecretService.SECRET_BUCKET, this.ownersKey)).isFalse();
    }

    /** Their own uploads are theirs by id, before the tenant rule is ever consulted. */
    @Test
    void aTenantAdminStillUsesItsOwnFile() {
        TenantContext.set(OWNER_TENANT, "TENANT_ADMIN", OWNER, "owner@example.com");
        assertThat(this.service.canUseObject(KafkaSecretService.SECRET_BUCKET, this.ownersKey)).isTrue();
    }

    @Test
    void aTenantAdminMayNotReachIntoAnotherTenant() {
        this.ownerIsInTenant(OWNER_TENANT);
        TenantContext.set(77L, "TENANT_ADMIN", 99L, "admin@other.example");
        assertThat(this.service.canUseObject(KafkaSecretService.SECRET_BUCKET, this.ownersKey)).isFalse();
    }

    /** A plain user gets no tenant-wide reach, only their own uploads. */
    @Test
    void aTenantUserMayNotUseAPeersFileEvenInTheSameTenant() {
        this.ownerIsInTenant(OWNER_TENANT);
        TenantContext.set(OWNER_TENANT, "TENANT_USER", 99L, "someone@tenant.example");
        assertThat(this.service.canUseObject(KafkaSecretService.SECRET_BUCKET, this.ownersKey)).isFalse();
    }

    @Test
    void aLongerUserIdDoesNotSatisfyAShorterOne() {
        String longerId = KafkaSecretPath.newUpload(12480L, "ca.pem", DAY).key();
        TenantContext.set(OWNER_TENANT, "TENANT_USER", OWNER, "owner@example.com");
        assertThat(this.service.canUseObject(KafkaSecretService.SECRET_BUCKET, longerId)).isFalse();
    }

    @Test
    void onlyTheSecretBucketIsEverAccepted() {
        TenantContext.set(OWNER_TENANT, "TENANT_USER", OWNER, "owner@example.com");
        assertThat(this.service.canUseObject("some-tenant-bucket", this.ownersKey)).isFalse();
        assertThat(this.service.canUseObject(null, this.ownersKey)).isFalse();
    }

    @Test
    void aKeyThatIsNotInTheAgreedLayoutIsRefused() {
        TenantContext.set(OWNER_TENANT, "TENANT_USER", OWNER, "owner@example.com");
        assertThat(this.service.canUseObject(KafkaSecretService.SECRET_BUCKET,
            "kafka-secrets/1248/../9999/2026-08-31/ca.pem")).isFalse();
        assertThat(this.service.canUseObject(KafkaSecretService.SECRET_BUCKET,
            "pdf-highlighter/4/contract.pdf")).isFalse();
        assertThat(this.service.canUseObject(KafkaSecretService.SECRET_BUCKET, null)).isFalse();
    }

    /** Nobody signed in at all -- a scheduler thread -- gets nothing through this door. */
    @Test
    void anEmptyContextIsRefused() {
        assertThat(this.service.canUseObject(KafkaSecretService.SECRET_BUCKET, this.ownersKey)).isFalse();
    }

}

package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import process.model.dto.AppUserDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.repository.AppUserRepository;
import process.model.repository.TenantRepository;
import process.model.service.PageAccessService;
import process.notifications.TestNotifications;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.security.TokenRevocations;
import process.storage.TrustedStorageOperations;
import process.util.ProcessUtil;
import process.util.UserNameResolver;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-91: scopedFind's four steps, in order, and what refusalFor says when they refuse.
 *
 * Every write on another person's row -- update, status, password reset -- goes through
 * scopedFind, and resetPassword is the one that hands back a working credential, so it is the probe
 * here. The steps:
 *   1. a missing or deleted row is nothing, for anybody, a platform admin included;
 *   2. a platform admin reaches every other row;
 *   3. anyone else reaches only a row of its own tenant -- a row with no tenant is nobody's;
 *   4. and within the tenant only tenant users, or its own row: not a peer administrator.
 * refusalFor then collapses every refusal to "User not found." except the one that confirms a row
 * the caller can already see on its users screen: a peer administrator of its own tenant.
 *
 * AppUserServiceImplRoleScopeTest covers each rule on its own; these cases sit where two steps
 * disagree, so they fail if the order changes.
 */
@ExtendWith(MockitoExtension.class)
class ScopedFindCharacterisationTest {

    private static final String NOT_FOUND = "User not found.";
    private static final String PEER = "Only a platform administrator can manage another tenant administrator.";
    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 2002L;
    private static final long ME = 9000L;

    @Mock private AppUserRepository appUserRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TestNotifications.MailSink mails;
    @Mock private TestNotifications.NoticeSink notices;
    @Mock private UserNameResolver userNameResolver;
    @Mock private TrustedStorageOperations storage;
    @Mock private PageAccessService pageAccessService;

    private AppUserServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new AppUserServiceImpl(this.appUserRepository, this.tenantRepository, this.passwordEncoder,
            TestNotifications.recording(null, this.notices, this.mails), this.userNameResolver, this.storage,
            this.pageAccessService, mock(TenantFilterHelper.class), mock(TokenRevocations.class));
        lenient().when(this.passwordEncoder.encode(any())).thenReturn("hashed");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void row(long id, Long tenantId, UserRole role, Status status) {
        AppUser user = new AppUser();
        user.setAppUserId(id);
        user.setTenantId(tenantId);
        user.setUsername("u" + id + "@example.com");
        user.setFullName("U" + id);
        user.setUserRole(role);
        user.setStatus(status);
        when(this.appUserRepository.findById(id)).thenReturn(Optional.of(user));
    }

    private ResponseDto reset(long id) throws Exception {
        AppUserDto dto = new AppUserDto();
        dto.setAppUserId(id);
        dto.setPassword("Passw0rd!");
        return this.service.resetPassword(dto);
    }

    private void refused(ResponseDto response, String message) {
        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage()).isEqualTo(message);
        verify(this.appUserRepository, never()).save(any());
    }

    private void tenantAdmin() {
        TenantContext.set(TENANT_A, "TENANT_ADMIN", ME, "me@a.example");
    }

    private void platformAdmin() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@example.com");
    }

    // -- step 1 before step 2 -------------------------------------------------------------

    @Test
    void aDeletedRowIsNotFoundEvenForAPlatformAdmin() throws Exception {
        this.row(50L, TENANT_B, UserRole.TENANT_USER, Status.Delete);
        this.platformAdmin();
        this.refused(this.reset(50L), NOT_FOUND);
    }

    @Test
    void anUnknownIdIsNotFoundForEveryone() throws Exception {
        when(this.appUserRepository.findById(404L)).thenReturn(Optional.empty());
        this.platformAdmin();
        this.refused(this.reset(404L), NOT_FOUND);
        this.tenantAdmin();
        this.refused(this.reset(404L), NOT_FOUND);
    }

    // -- step 2 before steps 3 and 4 ------------------------------------------------------

    @Test
    void aPlatformAdminReachesAnotherTenantsAdminAndAPlatformRow() throws Exception {
        this.row(51L, TENANT_B, UserRole.TENANT_ADMIN, Status.Active);
        this.row(52L, null, UserRole.PLATFORM_ADMIN, Status.Inactive);
        this.platformAdmin();
        assertThat(this.reset(51L).getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(this.reset(52L).getStatus()).isEqualTo(ProcessUtil.SUCCESS);
    }

    // -- step 1 before step 4 -------------------------------------------------------------

    /** A deleted peer is not "a peer": it is nothing, and says so the way nothing does. */
    @Test
    void aDeletedPeerAdminIsNotFoundRatherThanAPeer() throws Exception {
        this.row(53L, TENANT_A, UserRole.TENANT_ADMIN, Status.Delete);
        this.tenantAdmin();
        this.refused(this.reset(53L), NOT_FOUND);
    }

    // -- step 3 before step 4 -------------------------------------------------------------

    /** A platform admin's row carries no tenant; to a tenant admin it does not exist, not "a peer". */
    @Test
    void aPlatformRowIsNotFoundForATenantAdminRatherThanAPeer() throws Exception {
        this.row(54L, null, UserRole.PLATFORM_ADMIN, Status.Active);
        this.tenantAdmin();
        this.refused(this.reset(54L), NOT_FOUND);
    }

    @Test
    void anotherTenantsAdminIsNotFoundRatherThanAPeer() throws Exception {
        this.row(55L, TENANT_B, UserRole.TENANT_ADMIN, Status.Active);
        this.tenantAdmin();
        this.refused(this.reset(55L), NOT_FOUND);
    }

    /** A caller with no tenant of its own matches nobody -- null never equals null here. */
    @Test
    void aTenantlessCallerMatchesNoRowNotEvenATenantlessOne() throws Exception {
        this.row(56L, null, UserRole.TENANT_USER, Status.Active);
        TenantContext.set(null, "TENANT_ADMIN", ME, "me@nowhere.example");
        this.refused(this.reset(56L), NOT_FOUND);
    }

    // -- step 4 ---------------------------------------------------------------------------

    @Test
    void aPeerAdminIsRefusedByNameAndAPeerIsAnyNonTenantUser() throws Exception {
        this.row(57L, TENANT_A, UserRole.TENANT_ADMIN, Status.Active);
        this.row(58L, TENANT_A, UserRole.PLATFORM_ADMIN, Status.Active);
        this.tenantAdmin();
        this.refused(this.reset(57L), PEER);
        // A PLATFORM_ADMIN row carrying a tenant id should not exist; if one did, it is a peer too.
        this.refused(this.reset(58L), PEER);
    }

    @Test
    void aTenantUserOfTheSameTenantIsReachable() throws Exception {
        this.row(59L, TENANT_A, UserRole.TENANT_USER, Status.Inactive);
        this.tenantAdmin();
        assertThat(this.reset(59L).getStatus()).isEqualTo(ProcessUtil.SUCCESS);
    }

    /** The admin's own row is reachable although it is not a tenant user: it may edit itself. */
    @Test
    void theCallersOwnRowIsReachable() throws Exception {
        this.row(ME, TENANT_A, UserRole.TENANT_ADMIN, Status.Active);
        this.tenantAdmin();
        assertThat(this.reset(ME).getStatus()).isEqualTo(ProcessUtil.SUCCESS);
    }

    // -- refusalFor on the other two writes -----------------------------------------------

    @Test
    void updateAndStatusChangeRefuseInTheSameWords() throws Exception {
        this.row(60L, TENANT_A, UserRole.TENANT_ADMIN, Status.Active);
        this.row(61L, TENANT_B, UserRole.TENANT_USER, Status.Active);
        this.tenantAdmin();

        AppUserDto peer = new AppUserDto();
        peer.setAppUserId(60L);
        peer.setFullName("x");
        peer.setStatus(Status.Inactive);
        AppUserDto foreign = new AppUserDto();
        foreign.setAppUserId(61L);
        foreign.setFullName("x");
        foreign.setStatus(Status.Inactive);

        this.refused(this.service.updateUser(peer), PEER);
        this.refused(this.service.changeUserStatus(peer), PEER);
        this.refused(this.service.updateUser(foreign), NOT_FOUND);
        this.refused(this.service.changeUserStatus(foreign), NOT_FOUND);
    }
}

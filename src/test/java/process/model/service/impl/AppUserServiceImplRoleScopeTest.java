package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.emailer.EmailMessagesFactory;
import process.model.dto.AppUserDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.repository.AppUserRepository;
import process.model.repository.TenantRepository;
import process.model.service.StorageBrowserService;
import process.security.TenantContext;
import process.util.ProcessUtil;
import process.util.UserNameResolver;

import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What a tenant admin may do to the people in its own workspace.
 *
 * The tenant boundary was already held; this covers the line inside it. A tenant admin staffs
 * its workspace with tenant users, so creating a second administrator, promoting somebody into
 * one, or resetting a peer administrator's password are all the platform admin's to do -- the
 * last of those being the one that would otherwise hand one admin a working credential for
 * another's account.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class AppUserServiceImplRoleScopeTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 2002L;
    private static final long ACTING_ADMIN_ID = 9000L;
    private static final String PEER_ADMIN_MESSAGE = "Only a Platform Admin can manage another Tenant Admin.";

    @Mock
    private AppUserRepository appUserRepository;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private EmailMessagesFactory emailMessagesFactory;
    @Mock
    private UserNameResolver userNameResolver;
    @Mock
    private StorageBrowserService storageBrowserService;

    private AppUserServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new AppUserServiceImpl(this.appUserRepository, this.tenantRepository,
            this.passwordEncoder, this.emailMessagesFactory, this.userNameResolver,
            this.storageBrowserService);
        lenient().when(this.passwordEncoder.encode(any())).thenReturn("hashed");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void actAsTenantAdmin() {
        TenantContext.set(TENANT_A, "TENANT_ADMIN", ACTING_ADMIN_ID, "admin@example.com");
    }

    private void actAsPlatformAdmin() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "platform@example.com");
    }

    private AppUser userIn(long tenantId, long appUserId, UserRole role) {
        AppUser user = new AppUser();
        user.setAppUserId(appUserId);
        user.setTenantId(tenantId);
        user.setUsername("someone@example.com");
        user.setFullName("Someone");
        user.setUserRole(role);
        user.setStatus(Status.Active);
        return user;
    }

    @Test
    void aTenantAdminCannotCreateASecondTenantAdmin() throws Exception {
        AppUserDto dto = new AppUserDto();
        dto.setUsername("backdoor@example.com");
        dto.setFullName("Back Door");
        dto.setUserRole(UserRole.TENANT_ADMIN);

        this.actAsTenantAdmin();
        ResponseDto response = this.service.addUser(dto);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.appUserRepository, never()).save(any());
    }

    @Test
    void aTenantAdminCannotCreateAPlatformAdmin() throws Exception {
        AppUserDto dto = new AppUserDto();
        dto.setUsername("root@example.com");
        dto.setFullName("Root");
        dto.setUserRole(UserRole.PLATFORM_ADMIN);

        this.actAsTenantAdmin();
        ResponseDto response = this.service.addUser(dto);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.appUserRepository, never()).save(any());
    }

    @Test
    void aTenantAdminCannotPromoteAColleagueToTenantAdmin() throws Exception {
        when(this.appUserRepository.findById(88L))
            .thenReturn(Optional.of(this.userIn(TENANT_A, 88L, UserRole.TENANT_USER)));

        AppUserDto dto = new AppUserDto();
        dto.setAppUserId(88L);
        dto.setFullName("Colleague");
        dto.setUserRole(UserRole.TENANT_ADMIN);

        this.actAsTenantAdmin();
        ResponseDto response = this.service.updateUser(dto);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.appUserRepository, never()).save(any());
    }

    @Test
    void aTenantAdminMayStillEditATenantUserInItsOwnTenant() throws Exception {
        when(this.appUserRepository.findById(88L))
            .thenReturn(Optional.of(this.userIn(TENANT_A, 88L, UserRole.TENANT_USER)));

        AppUserDto dto = new AppUserDto();
        dto.setAppUserId(88L);
        dto.setFullName("Corrected Name");
        dto.setUserRole(UserRole.TENANT_USER);

        this.actAsTenantAdmin();
        ResponseDto response = this.service.updateUser(dto);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        verify(this.appUserRepository).save(any());
    }

    @Test
    void aTenantAdminMayStillCorrectItsOwnRowEvenThoughItCarriesTheAdminRole() throws Exception {
        // The console posts the whole form back, role included. Resubmitting the role the row
        // already has is not a promotion, so this has to keep working.
        when(this.appUserRepository.findById(ACTING_ADMIN_ID))
            .thenReturn(Optional.of(this.userIn(TENANT_A, ACTING_ADMIN_ID, UserRole.TENANT_ADMIN)));

        AppUserDto dto = new AppUserDto();
        dto.setAppUserId(ACTING_ADMIN_ID);
        dto.setFullName("My Corrected Name");
        dto.setUserRole(UserRole.TENANT_ADMIN);

        this.actAsTenantAdmin();
        ResponseDto response = this.service.updateUser(dto);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
    }

    @Test
    void aTenantAdminCannotResetAPeerAdminsPassword() throws Exception {
        when(this.appUserRepository.findById(77L))
            .thenReturn(Optional.of(this.userIn(TENANT_A, 77L, UserRole.TENANT_ADMIN)));

        AppUserDto dto = new AppUserDto();
        dto.setAppUserId(77L);
        dto.setPassword("Passw0rd!");

        this.actAsTenantAdmin();
        ResponseDto response = this.service.resetPassword(dto);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        // The row is on their screen, so the refusal names the rule rather than claiming the
        // account does not exist.
        assertThat(response.getMessage()).isEqualTo(PEER_ADMIN_MESSAGE);
        verify(this.appUserRepository, never()).save(any());
    }

    @Test
    void aTenantAdminCannotDeactivateAPeerAdmin() throws Exception {
        when(this.appUserRepository.findById(77L))
            .thenReturn(Optional.of(this.userIn(TENANT_A, 77L, UserRole.TENANT_ADMIN)));

        AppUserDto dto = new AppUserDto();
        dto.setAppUserId(77L);
        dto.setStatus(Status.Inactive);

        this.actAsTenantAdmin();
        ResponseDto response = this.service.changeUserStatus(dto);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage()).isEqualTo(PEER_ADMIN_MESSAGE);
        verify(this.appUserRepository, never()).save(any());
    }

    @Test
    void aTenantAdminCannotEditAPeerAdmin() throws Exception {
        when(this.appUserRepository.findById(77L))
            .thenReturn(Optional.of(this.userIn(TENANT_A, 77L, UserRole.TENANT_ADMIN)));

        AppUserDto dto = new AppUserDto();
        dto.setAppUserId(77L);
        dto.setFullName("Renamed By A Peer");

        this.actAsTenantAdmin();
        ResponseDto response = this.service.updateUser(dto);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage()).isEqualTo(PEER_ADMIN_MESSAGE);
        verify(this.appUserRepository, never()).save(any());
    }

    /**
     * The case the tenant boundary exists for, isolated from the role rule.
     *
     * A tenant user is exactly what a tenant admin is allowed to manage, so nothing here is
     * refused because of the target's role -- only because the target belongs to a different
     * company. The neighbouring test uses an admin as the target, which the role rule would have
     * refused anyway, and so proves less than it looks.
     */
    @Test
    void aTenantAdminCannotTouchAnOrdinaryUserOfAnotherCompany() throws Exception {
        when(this.appUserRepository.findById(56L))
            .thenReturn(Optional.of(this.userIn(TENANT_B, 56L, UserRole.TENANT_USER)));

        AppUserDto dto = new AppUserDto();
        dto.setAppUserId(56L);
        dto.setPassword("Passw0rd!");

        this.actAsTenantAdmin();
        ResponseDto response = this.service.resetPassword(dto);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage()).isEqualTo("User not found.");
        verify(this.appUserRepository, never()).save(any());
    }

    @Test
    void aRowInAnotherTenantIsStillNothingMoreThanNotFound() throws Exception {
        // The friendlier wording is only for rows the caller can already see listed. Saying it
        // about somebody else's tenant would turn a refusal into a way to enumerate accounts.
        when(this.appUserRepository.findById(55L))
            .thenReturn(Optional.of(this.userIn(TENANT_B, 55L, UserRole.TENANT_ADMIN)));

        AppUserDto dto = new AppUserDto();
        dto.setAppUserId(55L);
        dto.setPassword("Passw0rd!");

        this.actAsTenantAdmin();
        ResponseDto response = this.service.resetPassword(dto);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage()).isEqualTo("User not found.");
        verify(this.appUserRepository, never()).save(any());
    }

    @Test
    void aPlatformAdminMayStillResetATenantAdminsPassword() throws Exception {
        when(this.appUserRepository.findById(77L))
            .thenReturn(Optional.of(this.userIn(TENANT_A, 77L, UserRole.TENANT_ADMIN)));

        AppUserDto dto = new AppUserDto();
        dto.setAppUserId(77L);
        dto.setPassword("Passw0rd!");

        this.actAsPlatformAdmin();
        ResponseDto response = this.service.resetPassword(dto);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
    }

    @Test
    void anAdministratorResetIsHeldToTheSameLengthAsAPersonsOwnChange() throws Exception {
        AppUserDto dto = new AppUserDto();
        dto.setAppUserId(88L);
        dto.setPassword("1");

        this.actAsTenantAdmin();
        ResponseDto response = this.service.resetPassword(dto);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage()).contains("8 characters");
        verify(this.appUserRepository, never()).save(any());
    }

    @Test
    void aResetPasswordIsTemporaryAndHasToBeChanged() throws Exception {
        AppUser target = this.userIn(TENANT_A, 88L, UserRole.TENANT_USER);
        when(this.appUserRepository.findById(88L)).thenReturn(Optional.of(target));

        AppUserDto dto = new AppUserDto();
        dto.setAppUserId(88L);
        dto.setPassword("a-strong-one");

        this.actAsTenantAdmin();
        ResponseDto response = this.service.resetPassword(dto);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        // The admin who typed it knows it, so the account owes a change before it is theirs.
        assertThat(target.isMustChangePassword()).isTrue();
    }

    @Test
    void somebodyElsesPictureCannotBeClaimedAsYourOwn() throws Exception {
        AppUser me = this.userIn(TENANT_A, 7L, UserRole.TENANT_USER);
        when(this.appUserRepository.findById(7L)).thenReturn(Optional.of(me));

        AppUserDto dto = new AppUserDto();
        dto.setAvatarKey("9/profile/avatar.png");

        TenantContext.set(TENANT_A, "TENANT_USER", 7L, "user@example.com");
        ResponseDto response = this.service.updateOwnAvatar(dto);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(me.getAvatarKey()).isNull();
        verify(this.appUserRepository, never()).save(any());
    }

    @Test
    void anAvatarKeyCannotClimbOutOfItsOwnFolder() throws Exception {
        AppUser me = this.userIn(TENANT_A, 7L, UserRole.TENANT_USER);
        when(this.appUserRepository.findById(7L)).thenReturn(Optional.of(me));

        AppUserDto dto = new AppUserDto();
        dto.setAvatarKey("7/profile/../../9/profile/avatar.png");

        TenantContext.set(TENANT_A, "TENANT_USER", 7L, "user@example.com");
        ResponseDto response = this.service.updateOwnAvatar(dto);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.appUserRepository, never()).save(any());
    }

    @Test
    void aPersonMayStillRecordTheirOwnPicture() throws Exception {
        AppUser me = this.userIn(TENANT_A, 7L, UserRole.TENANT_USER);
        when(this.appUserRepository.findById(7L)).thenReturn(Optional.of(me));

        AppUserDto dto = new AppUserDto();
        dto.setAvatarKey("7/profile/avatar.png");

        TenantContext.set(TENANT_A, "TENANT_USER", 7L, "user@example.com");
        ResponseDto response = this.service.updateOwnAvatar(dto);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(me.getAvatarKey()).isEqualTo("7/profile/avatar.png");
    }

    // ---- readAvatar: who may see whose face ------------------------------------------------

    private AppUser userWithPicture(Long appUserId, Long tenantId) {
        AppUser user = new AppUser();
        user.setAppUserId(appUserId);
        user.setTenantId(tenantId);
        user.setStatus(Status.Active);
        user.setAvatarBucket("etl-avatar");
        user.setAvatarKey(appUserId + "/profile/avatar.png");
        return user;
    }

    /** Whoever appears on your users screen is whose picture you may see -- the same scope. */
    @Test
    void aTenantAdminSeesAPictureOfSomebodyInItsOwnTenant() {
        AppUser target = this.userWithPicture(55L, 5L);
        when(this.appUserRepository.findById(55L)).thenReturn(Optional.of(target));
        TenantContext.set(5L, "TENANT_ADMIN", 9L, "admin@tenant.example");

        this.service.readAvatar(55L);

        verify(this.storageBrowserService).readForWorkflow("etl-avatar", "55/profile/avatar.png");
    }

    @Test
    void nobodySeesAPictureFromAnotherTenant() {
        AppUser target = this.userWithPicture(55L, 5L);
        when(this.appUserRepository.findById(55L)).thenReturn(Optional.of(target));
        TenantContext.set(77L, "TENANT_ADMIN", 9L, "admin@other.example");

        assertThat(this.service.readAvatar(55L)).isNull();
        verifyNoInteractions(this.storageBrowserService);
    }

    @Test
    void aPlatformAdminSeesAnybodys() {
        AppUser target = this.userWithPicture(55L, 5L);
        when(this.appUserRepository.findById(55L)).thenReturn(Optional.of(target));
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");

        this.service.readAvatar(55L);

        verify(this.storageBrowserService).readForWorkflow("etl-avatar", "55/profile/avatar.png");
    }

    /** No picture, a deleted row and an unknown id are all the same quiet nothing. */
    @Test
    void anAbsentPictureIsNotAnError() {
        AppUser noPicture = this.userWithPicture(55L, 5L);
        noPicture.setAvatarKey(null);
        when(this.appUserRepository.findById(55L)).thenReturn(Optional.of(noPicture));
        TenantContext.set(5L, "TENANT_USER", 9L, "someone@tenant.example");

        assertThat(this.service.readAvatar(55L)).isNull();
        assertThat(this.service.readAvatar(null)).isNull();
        verifyNoInteractions(this.storageBrowserService);
    }

}

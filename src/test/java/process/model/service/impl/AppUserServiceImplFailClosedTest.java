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
import process.model.service.NotificationCenterService;
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
 * The two edges where a check used to fall open instead of closed.
 *
 * Both are about a row nobody is meant to reach through the ordinary path -- a tenant-less one,
 * and your own -- so neither shows up in the tenant-boundary tests next door. They are kept
 * together because they share a cause: a rule written out by hand rather than asked of the one
 * place that owns it, and a row left reachable for one purpose being usable for another.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class AppUserServiceImplFailClosedTest {

    private static final long TENANT_A = 1001L;
    private static final long ACTING_ADMIN_ID = 9000L;

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
    @Mock
    private NotificationCenterService notificationCenterService;

    private AppUserServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new AppUserServiceImpl(this.appUserRepository, this.tenantRepository,
            this.passwordEncoder, this.emailMessagesFactory, this.userNameResolver,
            this.storageBrowserService, this.notificationCenterService);
        lenient().when(this.passwordEncoder.encode(any())).thenReturn("hashed");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private AppUser userIn(Long tenantId, long appUserId, UserRole role) {
        AppUser user = new AppUser();
        user.setAppUserId(appUserId);
        user.setTenantId(tenantId);
        user.setUsername("someone@example.com");
        user.setFullName("Someone");
        user.setUserRole(role);
        user.setStatus(Status.Active);
        return user;
    }

    private AppUser platformAdminWithPicture(long appUserId) {
        AppUser user = this.userIn(null, appUserId, UserRole.PLATFORM_ADMIN);
        user.setAvatarBucket("etl-avatar");
        user.setAvatarKey(appUserId + "/profile/avatar.png");
        return user;
    }

    /**
     * A platform admin's row carries no tenant, and neither does a token that arrived without a
     * tenant claim. Comparing the two ids for equality made those two nothings match.
     */
    @Test
    void aCallerWithNoTenantOfItsOwnMatchesNobody() {
        when(this.appUserRepository.findById(42L)).thenReturn(Optional.of(this.platformAdminWithPicture(42L)));
        TenantContext.set(null, "TENANT_ADMIN", 9L, "admin@nowhere.example");

        assertThat(this.service.readAvatar(42L)).isNull();
        verifyNoInteractions(this.storageBrowserService);
    }

    /** The same row is invisible to an ordinary tenant, which is where it never appears anyway. */
    @Test
    void aPlatformOwnedRowIsNotReadableByATenant() {
        when(this.appUserRepository.findById(42L)).thenReturn(Optional.of(this.platformAdminWithPicture(42L)));
        TenantContext.set(TENANT_A, "TENANT_USER", 9L, "someone@tenant.example");

        assertThat(this.service.readAvatar(42L)).isNull();
        verifyNoInteractions(this.storageBrowserService);
    }

    @Test
    void aPlatformAdminCannotDropItsOwnRole() throws Exception {
        when(this.appUserRepository.findById(7L))
            .thenReturn(Optional.of(this.userIn(null, 7L, UserRole.PLATFORM_ADMIN)));
        TenantContext.set(null, "PLATFORM_ADMIN", 7L, "platform@example.com");

        AppUserDto dto = new AppUserDto();
        dto.setAppUserId(7L);
        dto.setFullName("Platform Admin");
        dto.setUserRole(UserRole.TENANT_USER);

        ResponseDto response = this.service.updateUser(dto);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage()).isEqualTo("You cannot change your own role.");
        verify(this.appUserRepository, never()).save(any());
    }

    @Test
    void aTenantAdminCannotDropItsOwnRoleEither() throws Exception {
        when(this.appUserRepository.findById(ACTING_ADMIN_ID))
            .thenReturn(Optional.of(this.userIn(TENANT_A, ACTING_ADMIN_ID, UserRole.TENANT_ADMIN)));
        TenantContext.set(TENANT_A, "TENANT_ADMIN", ACTING_ADMIN_ID, "admin@example.com");

        AppUserDto dto = new AppUserDto();
        dto.setAppUserId(ACTING_ADMIN_ID);
        dto.setFullName("My Corrected Name");
        dto.setUserRole(UserRole.TENANT_USER);

        ResponseDto response = this.service.updateUser(dto);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.appUserRepository, never()).save(any());
    }

    /**
     * The console posts the whole form back, role included, so the guard has to distinguish a
     * change of role from an edit that merely carries the role along.
     */
    @Test
    void resubmittingTheRoleYouAlreadyHoldIsStillAnEdit() throws Exception {
        when(this.appUserRepository.findById(7L))
            .thenReturn(Optional.of(this.userIn(null, 7L, UserRole.PLATFORM_ADMIN)));
        TenantContext.set(null, "PLATFORM_ADMIN", 7L, "platform@example.com");

        AppUserDto dto = new AppUserDto();
        dto.setAppUserId(7L);
        dto.setFullName("My Corrected Name");
        dto.setUserRole(UserRole.PLATFORM_ADMIN);

        ResponseDto response = this.service.updateUser(dto);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        verify(this.appUserRepository).save(any());
    }

    /** Somebody else's role is still the platform admin's to change. */
    @Test
    void aPlatformAdminMayStillDemoteSomebodyElse() throws Exception {
        when(this.appUserRepository.findById(88L))
            .thenReturn(Optional.of(this.userIn(TENANT_A, 88L, UserRole.TENANT_ADMIN)));
        TenantContext.set(null, "PLATFORM_ADMIN", 7L, "platform@example.com");

        AppUserDto dto = new AppUserDto();
        dto.setAppUserId(88L);
        dto.setFullName("Someone");
        dto.setUserRole(UserRole.TENANT_USER);

        ResponseDto response = this.service.updateUser(dto);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        verify(this.appUserRepository).save(any());
    }

}

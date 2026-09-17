package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import process.emailer.EmailMessagesFactory;
import process.model.dto.AppUserDto;
import process.model.dto.ResponseDto;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;
import process.model.enums.Status;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.Tenant;
import process.model.repository.AppUserRepository;
import process.model.repository.TenantRepository;
import process.model.service.NotificationCenterService;
import process.model.service.PageAccessService;
import process.model.service.StorageBrowserService;
import process.security.TenantContext;
import process.util.ProcessUtil;
import process.util.UserNameResolver;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who hears about a new account, in the bell rather than by email.
 *
 * The welcome email is covered elsewhere; these are about the notifications written next to it.
 * Three audiences: the new user (a welcome for their first sign-in), the administrator who did it
 * (a record of what they did), and the other administrators of the same workspace.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
public class AppUserServiceImplUserCreatedNotificationTest {

    private static final long TENANT_A = 1001L;
    private static final long ACTING_ADMIN_ID = 9000L;
    private static final long NEW_USER_ID = 5001L;

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
    @Mock
    private PageAccessService pageAccessService;

    private AppUserServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new AppUserServiceImpl(this.appUserRepository, this.tenantRepository,
            this.passwordEncoder, this.emailMessagesFactory, this.userNameResolver,
            this.storageBrowserService, this.notificationCenterService, this.pageAccessService);
        lenient().when(this.passwordEncoder.encode(any())).thenReturn("hashed");
        // The id the database would hand back -- without it the new user has nobody to notify.
        lenient().when(this.appUserRepository.save(any())).thenAnswer(invocation -> {
            AppUser saved = invocation.getArgument(0);
            if (saved.getAppUserId() == null) {
                saved.setAppUserId(NEW_USER_ID);
            }
            return saved;
        });
        Tenant tenant = new Tenant();
        tenant.setTenantId(TENANT_A);
        tenant.setTenantName("Acme");
        lenient().when(this.tenantRepository.findById(TENANT_A)).thenReturn(Optional.of(tenant));
        lenient().when(this.appUserRepository.findByUsernameAndStatusNot(anyString(), eq(Status.Delete)))
            .thenReturn(Optional.empty());
        lenient().when(this.appUserRepository.findByTenantIdAndStatusNotOrderByAppUserIdDesc(TENANT_A, Status.Delete))
            .thenReturn(Collections.emptyList());
        lenient().when(this.appUserRepository.findAll()).thenReturn(Collections.emptyList());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private AppUser userIn(Long tenantId, long appUserId, UserRole role, String fullName) {
        AppUser user = new AppUser();
        user.setAppUserId(appUserId);
        user.setTenantId(tenantId);
        user.setUsername(fullName.toLowerCase().replace(' ', '.') + "@example.com");
        user.setFullName(fullName);
        user.setUserRole(role);
        user.setStatus(Status.Active);
        return user;
    }

    private void actAsTenantAdmin() {
        TenantContext.set(TENANT_A, "TENANT_ADMIN", ACTING_ADMIN_ID, "admin@example.com");
        lenient().when(this.appUserRepository.findById(ACTING_ADMIN_ID))
            .thenReturn(Optional.of(this.userIn(TENANT_A, ACTING_ADMIN_ID, UserRole.TENANT_ADMIN, "Ada Admin")));
    }

    private AppUserDto newTenantUser() {
        AppUserDto dto = new AppUserDto();
        dto.setUsername("new.person@example.com");
        dto.setFullName("New Person");
        dto.setUserRole(UserRole.TENANT_USER);
        dto.setTenantId(TENANT_A);
        return dto;
    }

    @Test
    void theNewUserGetsAWelcomeAndTheActorGetsARecord() throws Exception {
        this.actAsTenantAdmin();
        when(this.emailMessagesFactory.sendUserWelcomeEmail(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn("Sent");

        ResponseDto response = this.service.addUser(this.newTenantUser());

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(this.notificationCenterService).create(eq(TENANT_A), eq(NEW_USER_ID),
            eq(NotificationType.USER_ADDED), eq(NotificationSeverity.SUCCESS),
            title.capture(), message.capture(), eq("/profile"));
        assertThat(title.getValue()).isEqualTo("Welcome to Acme");
        assertThat(message.getValue()).startsWith("Ada Admin set up your account as tenant user.");

        verify(this.notificationCenterService).create(eq(TENANT_A), eq(ACTING_ADMIN_ID),
            eq(NotificationType.USER_ADDED), eq(NotificationSeverity.INFO),
            eq("User created"), message.capture(), eq("/users"));
        assertThat(message.getValue())
            .isEqualTo("You created New Person (new.person@example.com) as tenant user in Acme.");
    }

    /** The one message an administrator must not miss rides on their own copy, as a warning. */
    @Test
    void aFailedWelcomeEmailTurnsTheActorsCopyIntoAWarning() throws Exception {
        this.actAsTenantAdmin();
        when(this.emailMessagesFactory.sendUserWelcomeEmail(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn("Error: smtp down");

        ResponseDto response = this.service.addUser(this.newTenantUser());

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(response.getMessage()).contains("welcome email could not be sent");
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(this.notificationCenterService).create(eq(TENANT_A), eq(ACTING_ADMIN_ID),
            eq(NotificationType.USER_ADDED), eq(NotificationSeverity.WARNING),
            eq("User created, welcome email not sent"), message.capture(), eq("/users"));
        assertThat(message.getValue()).contains("reset their password and pass it on another way");
        // The welcome still goes to the new user: the account exists and they will sign in eventually.
        verify(this.notificationCenterService).create(eq(TENANT_A), eq(NEW_USER_ID),
            eq(NotificationType.USER_ADDED), eq(NotificationSeverity.SUCCESS), any(), any(), eq("/profile"));
    }

    /**
     * A platform admin staffing a tenant is invisible to that tenant's own admins otherwise. The
     * actor and the new user are never told twice, and a deactivated admin has no bell to ring.
     */
    @Test
    void theWorkspacesOtherAdminsHearAboutItOnce() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "platform@example.com");
        when(this.appUserRepository.findById(1L))
            .thenReturn(Optional.of(this.userIn(null, 1L, UserRole.PLATFORM_ADMIN, "Pat Platform")));
        AppUser otherAdmin = this.userIn(TENANT_A, 77L, UserRole.TENANT_ADMIN, "Other Admin");
        AppUser dormantAdmin = this.userIn(TENANT_A, 78L, UserRole.TENANT_ADMIN, "Dormant Admin");
        dormantAdmin.setStatus(Status.Inactive);
        AppUser plainUser = this.userIn(TENANT_A, 79L, UserRole.TENANT_USER, "Plain User");
        when(this.appUserRepository.findByTenantIdAndStatusNotOrderByAppUserIdDesc(TENANT_A, Status.Delete))
            .thenReturn(Arrays.asList(otherAdmin, dormantAdmin, plainUser));
        when(this.emailMessagesFactory.sendUserWelcomeEmail(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn("Sent");

        this.service.addUser(this.newTenantUser());

        verify(this.notificationCenterService).create(eq(TENANT_A), eq(77L),
            eq(NotificationType.USER_ADDED), eq(NotificationSeverity.INFO),
            eq("New user in your workspace"),
            eq("Pat Platform added New Person (new.person@example.com) as tenant user."), eq("/users"));
        verify(this.notificationCenterService, never()).create(any(), eq(78L), any(), any(), any(), any(), any());
        verify(this.notificationCenterService, never()).create(any(), eq(79L), any(), any(), any(), any(), any());
        // Welcome, actor, one peer.
        verify(this.notificationCenterService, times(3)).create(any(), any(), any(), any(), any(), any(), any());
    }

    /** A second platform admin is something every existing one should hear about. */
    @Test
    void aNewPlatformAdminIsAnnouncedToTheOtherPlatformAdmins() throws Exception {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "platform@example.com");
        AppUser actor = this.userIn(null, 1L, UserRole.PLATFORM_ADMIN, "Pat Platform");
        when(this.appUserRepository.findById(1L)).thenReturn(Optional.of(actor));
        AppUser peer = this.userIn(null, 2L, UserRole.PLATFORM_ADMIN, "Pia Platform");
        AppUser tenantAdmin = this.userIn(TENANT_A, 77L, UserRole.TENANT_ADMIN, "Other Admin");
        when(this.appUserRepository.findAll()).thenReturn(Arrays.asList(actor, peer, tenantAdmin));
        when(this.emailMessagesFactory.sendUserWelcomeEmail(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn("Sent");

        AppUserDto dto = this.newTenantUser();
        dto.setUserRole(UserRole.PLATFORM_ADMIN);
        dto.setTenantId(null);
        this.service.addUser(dto);

        verify(this.notificationCenterService).create(eq(null), eq(NEW_USER_ID),
            eq(NotificationType.USER_ADDED), eq(NotificationSeverity.SUCCESS),
            eq("Welcome to ETL Console"), any(), eq("/profile"));
        verify(this.notificationCenterService).create(eq(null), eq(2L),
            eq(NotificationType.USER_ADDED), eq(NotificationSeverity.INFO),
            eq("New platform admin"), any(), eq("/users"));
        verify(this.notificationCenterService, never()).create(any(), eq(77L), any(), any(), any(), any(), any());
        verify(this.notificationCenterService, times(3)).create(any(), any(), any(), any(), any(), any(), any());
    }
}

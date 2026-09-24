package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.dto.AppUserDto;
import process.model.dto.LoginRequestDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.Tenant;
import process.model.pojo.TenantRequest;
import process.model.repository.AppUserRepository;
import process.model.repository.TenantRepository;
import process.model.repository.TenantRequestRepository;
import process.model.service.PageAccessService;
import process.notifications.TestNotifications;
import org.barco.platform.security.LoginAttemptGuard;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.security.TokenRevocations;
import process.storage.TrustedStorageOperations;
import process.util.JwtUtil;
import process.util.UserNameResolver;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * MIG-17 (DEF-024), the D6 attack: taking over an account by registering its name in another case.
 *
 * Login matched names without case and every "is this name free" check matched them exactly, so
 * Alice@x.com could be created beside alice@x.com -- in the same workspace, another workspace, or
 * through a workspace request -- and login then picked one of the two. Every door a new account
 * comes in by now asks isUsernameTaken, which the database answers ignoring case across every
 * tenant; UsernameUniquenessPostgresTest proves the query and the index behind it.
 */
@ExtendWith(MockitoExtension.class)
class UsernameTakeoverTest {

    private static final long TENANT_A = 1001L;

    @Mock private AppUserRepository appUserRepository;
    @Mock private TenantRepository tenantRepository;
    @Mock private TenantRequestRepository tenantRequestRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private PageAccessService pageAccessService;

    @BeforeEach
    void setUp() {
        lenient().when(this.passwordEncoder.encode(any())).thenReturn("hashed");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private AppUserServiceImpl users() {
        return new AppUserServiceImpl(this.appUserRepository, this.tenantRepository, this.passwordEncoder,
            TestNotifications.recording(null, null, mock(TestNotifications.NoticeSink.class), mock(TestNotifications.MailSink.class)),
            mock(UserNameResolver.class), mock(TrustedStorageOperations.class), this.pageAccessService, mock(TenantFilterHelper.class), mock(TokenRevocations.class));
    }

    private TenantRequestServiceImpl requests() {
        TenantRequestServiceImpl service = new TenantRequestServiceImpl(this.tenantRequestRepository, this.tenantRepository,
            this.appUserRepository, this.passwordEncoder, TestNotifications.recording(null, null, null,
                mock(TestNotifications.MailSink.class)));
        ReflectionTestUtils.setField(service, "consoleUrl", "http://localhost:4400");
        return service;
    }

    @Test
    void aTenantAdminCannotCreateANameThatDiffersOnlyByCase() throws Exception {
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 9L, "admin@a.example");
        when(this.tenantRepository.findById(TENANT_A)).thenReturn(Optional.of(new Tenant()));
        when(this.appUserRepository.isUsernameTakenAcrossTenants("Alice@X.com")).thenReturn(true);

        AppUserDto dto = new AppUserDto();
        dto.setUsername("  Alice@X.com ");
        dto.setFullName("Mallory");
        dto.setUserRole(UserRole.TENANT_USER);
        ResponseDto response = this.users().addUser(dto);

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getMessage()).isEqualTo("Username \"Alice@X.com\" is already in use.");
        verify(this.appUserRepository, never()).save(any());
    }

    @Test
    void aWorkspaceRequestForAnExistingNameInAnotherCaseCreatesNothingAndSaysNothing() {
        when(this.tenantRequestRepository.findOpenByEmail("alice@x.com")).thenReturn(Optional.empty());
        when(this.appUserRepository.isUsernameTakenAcrossTenants("alice@x.com")).thenReturn(true);

        TenantRequest submitted = new TenantRequest();
        submitted.setOrganisationName("Mallory Ltd");
        submitted.setContactName("Mallory");
        submitted.setContactEmail("ALICE@x.com");
        ResponseDto response = this.requests().submit(submitted);

        // The same acknowledgement a fresh address gets: the request form must not become a way to
        // ask whether an account exists.
        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        verify(this.tenantRequestRepository, never()).save(any());
    }

    @Test
    void approvingARequestWhoseNameWasTakenInAnotherCaseIsRefused() {
        TenantRequest pending = new TenantRequest();
        pending.setTenantRequestId(5L);
        pending.setOrganisationName("Mallory Ltd");
        pending.setContactName("Mallory");
        pending.setContactEmail("alice@x.com");
        pending.setStatus("Pending");
        when(this.tenantRequestRepository.findById(5L)).thenReturn(Optional.of(pending));
        when(this.tenantRepository.findByTenantCode(anyString())).thenReturn(Optional.empty());
        when(this.appUserRepository.isUsernameTakenAcrossTenants("alice@x.com")).thenReturn(true);

        ResponseDto response = this.requests().approve(5L, "mallory");

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getMessage()).isEqualTo("There is already an account for alice@x.com.");
        verify(this.tenantRepository, never()).save(any());
        verify(this.appUserRepository, never()).save(any());
    }

    /** Login still ignores case -- the behaviour people rely on -- through the finder that can assume one row. */
    @Test
    void loginFindsTheOneAccountWhateverCaseIsTyped() throws Exception {
        AppUser alice = new AppUser();
        alice.setAppUserId(3L);
        alice.setUsername("alice@x.com");
        alice.setPassword("stored");
        alice.setFullName("Alice");
        alice.setUserRole(UserRole.PLATFORM_ADMIN);
        alice.setStatus(Status.Active);
        when(this.appUserRepository.findLiveByUsernameAcrossTenants("ALICE@X.COM")).thenReturn(Optional.of(alice));
        when(this.passwordEncoder.matches("right", "stored")).thenReturn(true);
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.generateAccessToken(alice)).thenReturn("a");
        when(jwt.generateRefreshToken(alice)).thenReturn("r");
        AuthServiceImpl auth = new AuthServiceImpl(this.appUserRepository, this.tenantRepository, this.passwordEncoder,
            jwt, this.pageAccessService, mock(LoginAttemptGuard.class), mock(TokenRevocations.class));

        LoginRequestDto request = new LoginRequestDto();
        request.setUsername("ALICE@X.COM");
        request.setPassword("right");

        assertThat(auth.login(request).getStatus()).isEqualTo(SUCCESS);
    }
}

package process.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import process.model.dto.LoginRequestDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.enums.TenantStatus;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.Tenant;
import process.model.repository.AppUserRepository;
import process.model.repository.TenantRepository;
import process.model.service.PageAccessService;
import process.model.service.impl.AuthServiceImpl;
import process.util.JwtUtil;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.MalformedJwtException;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * MIG-91: the sentences sign-in and refresh answer with, pinned word for word before Identity moves.
 *
 * In Identity the exact message is the security contract. Two refusals that differ by a word are an
 * oracle: whoever can tell them apart can tell which names exist. LoginHardeningTest pins the
 * mechanisms (case, timing, the guard); this pins the wording and the order in which an account's
 * state is disclosed, which is what a rewrite in another service most easily gets wrong.
 */
class LoginRefusalCharacterisationTest {

    private static final String INVALID = "Invalid username or password.";
    private static final String INACTIVE = "This account is inactive. Contact your administrator.";
    private static final String SUSPENDED = "Your organization's access is currently suspended. Contact your administrator.";
    private static final String LOCKED = "Too many sign-in attempts. Try again in 15 minutes.";
    private static final String NO_LONGER_ACTIVE = "Account no longer active -- please log in again.";

    private static final String NAME = "emily@example.com";
    private static final String STORED_HASH = "stored-hash";
    private static final String NOBODYS_HASH = "nobodys-hash";
    private static final long TENANT = 1001L;

    private AppUserRepository appUserRepository;
    private TenantRepository tenantRepository;
    private PasswordEncoder passwordEncoder;
    private JwtUtil jwtUtil;
    private PageAccessService pageAccessService;
    private AtomicLong now;
    private RedisLoginGuards redis;
    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        this.appUserRepository = mock(AppUserRepository.class);
        this.tenantRepository = mock(TenantRepository.class);
        this.passwordEncoder = mock(PasswordEncoder.class);
        this.jwtUtil = mock(JwtUtil.class);
        this.pageAccessService = mock(PageAccessService.class);
        this.now = new AtomicLong(2_000_000L);
        when(this.passwordEncoder.encode(anyString())).thenReturn(NOBODYS_HASH);
        when(this.passwordEncoder.matches("right", STORED_HASH)).thenReturn(true);
        when(this.pageAccessService.effectivePages(any(AppUser.class))).thenReturn(Collections.emptySet());
        if (this.redis != null) this.redis.close();
        this.redis = RedisLoginGuards.open();
        this.authService = new AuthServiceImpl(this.appUserRepository, this.tenantRepository,
            this.passwordEncoder, this.jwtUtil, this.pageAccessService, this.redis.guard(this.now::get));
    }

    @AfterEach
    void tearDown() {
        if (this.redis != null) this.redis.close();
        this.redis = null;
    }

    private AppUser tenantAdmin(Status status) {
        AppUser user = new AppUser();
        user.setAppUserId(7L);
        user.setUsername(NAME);
        user.setPassword(STORED_HASH);
        user.setFullName("Emily");
        user.setUserRole(UserRole.TENANT_ADMIN);
        user.setStatus(status);
        user.setTenantId(TENANT);
        return user;
    }

    private void accountIs(AppUser user) {
        when(this.appUserRepository.findLiveByUsernameIgnoringCase(anyString()))
            .thenReturn(Optional.ofNullable(user));
    }

    private void tenantIs(TenantStatus status) {
        Tenant tenant = new Tenant();
        tenant.setTenantId(TENANT);
        tenant.setStatus(status);
        when(this.tenantRepository.findById(TENANT)).thenReturn(Optional.of(tenant));
    }

    private ResponseDto signIn(String username, String password) throws Exception {
        LoginRequestDto request = new LoginRequestDto();
        request.setUsername(username);
        request.setPassword(password);
        return this.authService.login(request);
    }

    // -- the hash that matches nothing ----------------------------------------------------

    /** Computed once, in the constructor: hashing per request would itself be a timing difference. */
    @Test
    void nobodysHashIsComputedOnceAndReusedForEveryUnknownName() throws Exception {
        verify(this.passwordEncoder, times(1)).encode(anyString());
        this.accountIs(null);

        this.signIn("a@example.com", "one");
        this.signIn("b@example.com", "two");
        this.signIn("c@example.com", "three");

        verify(this.passwordEncoder, times(1)).encode(anyString());
        verify(this.passwordEncoder).matches("one", NOBODYS_HASH);
        verify(this.passwordEncoder).matches("two", NOBODYS_HASH);
        verify(this.passwordEncoder).matches("three", NOBODYS_HASH);
    }

    // -- one sentence before the password is proved ---------------------------------------

    @Test
    void everyRefusalBeforeTheRightPasswordIsTheSameSentence() throws Exception {
        this.accountIs(null);
        assertThat(this.signIn(NAME, "wrong").getMessage()).as("unknown name").isEqualTo(INVALID);

        this.setUp();
        this.accountIs(this.tenantAdmin(Status.Active));
        this.tenantIs(TenantStatus.Active);
        assertThat(this.signIn(NAME, "wrong").getMessage()).as("wrong password").isEqualTo(INVALID);

        this.setUp();
        this.accountIs(this.tenantAdmin(Status.Inactive));
        assertThat(this.signIn(NAME, "wrong").getMessage()).as("inactive, wrong password").isEqualTo(INVALID);

        this.setUp();
        this.accountIs(this.tenantAdmin(Status.Active));
        this.tenantIs(TenantStatus.Suspended);
        ResponseDto suspendedWrong = this.signIn(NAME, "wrong");
        assertThat(suspendedWrong.getStatus()).isEqualTo(ERROR);
        assertThat(suspendedWrong.getMessage()).as("suspended tenant, wrong password").isEqualTo(INVALID);
        // Nothing about the tenant is even read until the password is right.
        verify(this.tenantRepository, never()).findById(any());
    }

    @Test
    void aSuspendedOrMissingTenantIsToldOnlyAfterTheRightPassword() throws Exception {
        this.accountIs(this.tenantAdmin(Status.Active));
        this.tenantIs(TenantStatus.Suspended);
        assertThat(this.signIn(NAME, "right").getMessage()).isEqualTo(SUSPENDED);

        this.setUp();
        this.accountIs(this.tenantAdmin(Status.Active));
        when(this.tenantRepository.findById(TENANT)).thenReturn(Optional.empty());
        // A tenant row that is gone reads exactly like a suspended one.
        assertThat(this.signIn(NAME, "right").getMessage()).isEqualTo(SUSPENDED);
    }

    @Test
    void anInactiveAccountIsToldBeforeItsTenantIsLookedAt() throws Exception {
        this.accountIs(this.tenantAdmin(Status.Inactive));
        this.tenantIs(TenantStatus.Suspended);

        ResponseDto response = this.signIn(NAME, "right");

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getMessage()).isEqualTo(INACTIVE);
        verify(this.tenantRepository, never()).findById(any());
        verify(this.jwtUtil, never()).generateAccessToken(any());
    }

    // -- the lock -----------------------------------------------------------------------

    /** The guard counts names, not accounts, so a locked name says nothing about whether it exists. */
    @Test
    void aLockedNameReadsTheSameWhetherOrNotTheAccountExists() throws Exception {
        this.accountIs(null);
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES; i++) {
            this.signIn("ghost@example.com", "wrong");
        }
        String ghost = this.signIn("ghost@example.com", "wrong").getMessage();

        this.setUp();
        this.accountIs(this.tenantAdmin(Status.Active));
        this.tenantIs(TenantStatus.Active);
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES; i++) {
            this.signIn(NAME, "wrong");
        }
        String real = this.signIn(NAME, "wrong").getMessage();

        assertThat(ghost).isEqualTo(LOCKED);
        assertThat(real).isEqualTo(LOCKED);
    }

    @Test
    void theRightPasswordDoesNotOpenALockedName() throws Exception {
        this.accountIs(this.tenantAdmin(Status.Active));
        this.tenantIs(TenantStatus.Active);
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES; i++) {
            this.signIn(NAME, "wrong");
        }

        ResponseDto response = this.signIn(NAME, "right");

        assertThat(response.getStatus()).isEqualTo(ERROR);
        assertThat(response.getMessage()).isEqualTo(LOCKED);
        verify(this.passwordEncoder, never()).matches("right", STORED_HASH);
    }

    /**
     * Today: a right password on an inactive account neither counts as a failure nor clears the
     * count. The person proved the password, so it is not guessing; and the account did not sign
     * in, so nothing is forgiven.
     */
    @Test
    void aRightPasswordOnAClosedAccountNeitherCountsNorForgives() throws Exception {
        this.accountIs(this.tenantAdmin(Status.Inactive));
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES - 1; i++) {
            this.signIn(NAME, "wrong");
        }
        for (int i = 0; i < 3; i++) {
            assertThat(this.signIn(NAME, "right").getMessage()).isEqualTo(INACTIVE);
        }

        assertThat(this.signIn(NAME, "wrong").getMessage()).as("the fifth wrong password still locks")
            .isEqualTo(INVALID);
        assertThat(this.signIn(NAME, "wrong").getMessage()).isEqualTo(LOCKED);
    }

    // -- refresh ------------------------------------------------------------------------

    /** Gone, inactive, or its tenant suspended: refresh says one thing for all three. */
    @Test
    void refreshRefusesEveryClosedAccountWithOneSentence() throws Exception {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(NAME);
        when(this.jwtUtil.parseClaims("refresh-token")).thenReturn(claims);
        when(this.jwtUtil.isRefreshToken(claims)).thenReturn(true);

        when(this.appUserRepository.findByUsernameAndStatusNot(NAME, Status.Delete)).thenReturn(Optional.empty());
        assertThat(this.authService.refresh("refresh-token").getMessage()).as("gone").isEqualTo(NO_LONGER_ACTIVE);

        when(this.appUserRepository.findByUsernameAndStatusNot(NAME, Status.Delete))
            .thenReturn(Optional.of(this.tenantAdmin(Status.Inactive)));
        assertThat(this.authService.refresh("refresh-token").getMessage()).as("inactive").isEqualTo(NO_LONGER_ACTIVE);

        when(this.appUserRepository.findByUsernameAndStatusNot(NAME, Status.Delete))
            .thenReturn(Optional.of(this.tenantAdmin(Status.Active)));
        this.tenantIs(TenantStatus.Suspended);
        assertThat(this.authService.refresh("refresh-token").getMessage()).as("suspended").isEqualTo(NO_LONGER_ACTIVE);

        this.tenantIs(TenantStatus.Active);
        when(this.jwtUtil.generateAccessToken(any())).thenReturn("fresh-access");
        ResponseDto refreshed = this.authService.refresh("refresh-token");
        assertThat(refreshed.getStatus()).isEqualTo(SUCCESS);
        assertThat(refreshed.getMessage()).isEqualTo("Token refreshed.");
    }

    @Test
    void refreshRefusesAnAccessTokenAndAnUnreadableOne() throws Exception {
        Claims access = mock(Claims.class);
        when(this.jwtUtil.parseClaims("access-token")).thenReturn(access);
        when(this.jwtUtil.isRefreshToken(access)).thenReturn(false);
        when(this.jwtUtil.parseClaims("garbage")).thenThrow(new MalformedJwtException("bad"));

        assertThat(this.authService.refresh("access-token").getMessage()).isEqualTo("Not a refresh token.");
        assertThat(this.authService.refresh("garbage").getMessage())
            .isEqualTo("Refresh token is invalid or expired -- please log in again.");
        assertThat(this.authService.refresh(" ").getMessage()).isEqualTo("Refresh token missing.");
    }
}

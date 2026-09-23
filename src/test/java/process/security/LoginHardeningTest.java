package process.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import process.model.dto.LoginRequestDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.repository.AppUserRepository;
import process.model.repository.TenantRepository;
import process.model.service.PageAccessService;
import process.model.service.impl.AuthServiceImpl;
import process.util.JwtUtil;

import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;

/**
 * What the sign-in tells a caller, and when it stops answering.
 *
 * Three things the browser suite found on the live console: the name was matched exactly, so
 * Emily with a capital in her address could not sign in to her own account; an unknown name
 * answered sooner than a real one, which told a caller which names exist without needing a
 * password for any of them; and nothing counted wrong passwords, so a list of them could be
 * worked through at the speed of the network.
 *
 * Each is fixed in a different place -- the repository matches without case, {@code nobodysHash}
 * makes the unknown path cost what the real one costs, and {@link LoginAttemptGuard} rests a name
 * after a run of failures -- so each is pinned here separately. The guard's clock is handed in,
 * which is why this test lives in the guard's own package.
 *
 * @author Nabeel Ahmed
 */
class LoginHardeningTest {

    private static final String KNOWN_NAME = "emily@example.com";
    private static final String STORED_HASH = "stored-hash";
    private static final String NOBODYS_HASH = "nobodys-hash";

    private AppUserRepository appUserRepository;
    private TenantRepository tenantRepository;
    private PasswordEncoder passwordEncoder;
    private JwtUtil jwtUtil;
    private PageAccessService pageAccessService;
    private AtomicLong now;
    private LoginAttemptGuard loginAttempts;
    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        this.appUserRepository = mock(AppUserRepository.class);
        this.tenantRepository = mock(TenantRepository.class);
        this.passwordEncoder = mock(PasswordEncoder.class);
        this.jwtUtil = mock(JwtUtil.class);
        this.pageAccessService = mock(PageAccessService.class);
        this.now = new AtomicLong(1_000_000L);
        this.loginAttempts = new LoginAttemptGuard(this.now::get);
        // The service hashes a random string in its constructor to get the hash that matches
        // nothing; stubbing encode is what lets the test name it.
        when(this.passwordEncoder.encode(anyString())).thenReturn(NOBODYS_HASH);
        this.authService = new AuthServiceImpl(this.appUserRepository, this.tenantRepository,
            this.passwordEncoder, this.jwtUtil, this.pageAccessService, this.loginAttempts);
    }

    private AppUser activeUser() {
        AppUser user = new AppUser();
        user.setAppUserId(1L);
        user.setUsername(KNOWN_NAME);
        user.setPassword(STORED_HASH);
        user.setFullName("Emily");
        user.setUserRole(UserRole.PLATFORM_ADMIN);
        user.setStatus(Status.Active);
        // Null tenant keeps the success path off the tenant lookup; a platform admin has none.
        user.setTenantId(null);
        return user;
    }

    private void accountExists(AppUser user) {
        when(this.appUserRepository.findFirstByUsernameIgnoreCaseAndStatusNot(anyString(), eq(Status.Delete)))
            .thenReturn(Optional.of(user));
    }

    private void noSuchAccount() {
        when(this.appUserRepository.findFirstByUsernameIgnoreCaseAndStatusNot(anyString(), eq(Status.Delete)))
            .thenReturn(Optional.empty());
    }

    private void readyForSuccess(AppUser user) {
        when(this.passwordEncoder.matches("right", STORED_HASH)).thenReturn(true);
        when(this.jwtUtil.generateAccessToken(user)).thenReturn("access");
        when(this.jwtUtil.generateRefreshToken(user)).thenReturn("refresh");
        when(this.pageAccessService.effectivePages(any(AppUser.class))).thenReturn(Collections.emptySet());
        when(this.pageAccessService.profileNameFor(any())).thenReturn(null);
    }

    private ResponseDto signIn(String username, String password) throws Exception {
        LoginRequestDto request = new LoginRequestDto();
        request.setUsername(username);
        request.setPassword(password);
        return this.authService.login(request);
    }

    // -- the name -------------------------------------------------------------------------

    @Test
    void theNameIsMatchedWithoutCase() throws Exception {
        AppUser user = this.activeUser();
        this.accountExists(user);
        this.readyForSuccess(user);

        ResponseDto response = this.signIn("EMILY@Example.COM", "right");

        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        // The case rule lives in the repository method, not in the service: pin that this is the
        // method asked. Swapping it for findByUsername would reintroduce the original defect
        // while every other assertion here still passed.
        verify(this.appUserRepository).findFirstByUsernameIgnoreCaseAndStatusNot("EMILY@Example.COM", Status.Delete);
    }

    @Test
    void surroundingSpaceIsTrimmedBeforeAnythingElse() throws Exception {
        AppUser user = this.activeUser();
        this.accountExists(user);
        this.readyForSuccess(user);

        this.signIn("  " + KNOWN_NAME + "  ", "right");

        verify(this.appUserRepository).findFirstByUsernameIgnoreCaseAndStatusNot(KNOWN_NAME, Status.Delete);
    }

    // -- what an unknown name costs -------------------------------------------------------

    @Test
    void anUnknownNameStillPaysForAPasswordCheck() throws Exception {
        this.noSuchAccount();

        this.signIn("nobody@example.com", "guess");

        // Without this the unknown path returned as soon as the lookup missed, and answering in
        // a millisecond where a real account took a hundred is itself the answer to "does this
        // name exist".
        verify(this.passwordEncoder).matches("guess", NOBODYS_HASH);
    }

    @Test
    void anUnknownNameAndAWrongPasswordGetTheSameSentence() throws Exception {
        this.noSuchAccount();
        ResponseDto unknownName = this.signIn("nobody@example.com", "guess");

        this.setUp();
        AppUser user = this.activeUser();
        this.accountExists(user);
        when(this.passwordEncoder.matches("guess", STORED_HASH)).thenReturn(false);
        ResponseDto wrongPassword = this.signIn(KNOWN_NAME, "guess");

        assertThat(unknownName.getStatus()).isEqualTo(ERROR);
        assertThat(wrongPassword.getStatus()).isEqualTo(ERROR);
        assertThat(unknownName.getMessage()).isEqualTo(wrongPassword.getMessage());
    }

    @Test
    void theStateOfAnAccountIsToldOnlyToSomebodyWhoKnowsItsPassword() throws Exception {
        AppUser inactive = this.activeUser();
        inactive.setStatus(Status.Inactive);
        this.accountExists(inactive);
        when(this.passwordEncoder.matches("guess", STORED_HASH)).thenReturn(false);

        ResponseDto withoutThePassword = this.signIn(KNOWN_NAME, "guess");

        // "Invalid username or password", not "this account is inactive" -- the second sentence
        // confirms the account exists to somebody who has not proved they own it.
        assertThat(withoutThePassword.getMessage()).isEqualTo("Invalid username or password.");

        when(this.passwordEncoder.matches("right", STORED_HASH)).thenReturn(true);
        ResponseDto withThePassword = this.signIn(KNOWN_NAME, "right");

        assertThat(withThePassword.getStatus()).isEqualTo(ERROR);
        assertThat(withThePassword.getMessage()).isEqualTo("This account is inactive. Contact your administrator.");
    }

    // -- counting wrong passwords ---------------------------------------------------------

    @Test
    void aRunOfWrongPasswordsRestsTheName() throws Exception {
        this.noSuchAccount();

        for (int attempt = 0; attempt < LoginAttemptGuard.MAX_FAILURES; attempt++) {
            ResponseDto refused = this.signIn(KNOWN_NAME, "guess");
            assertThat(refused.getMessage()).isEqualTo("Invalid username or password.");
        }

        ResponseDto locked = this.signIn(KNOWN_NAME, "guess");

        assertThat(locked.getStatus()).isEqualTo(ERROR);
        assertThat(locked.getMessage()).isEqualTo("Too many sign-in attempts. Try again in 15 minutes.");
    }

    @Test
    void aRestingNameIsNotLookedUpAtAll() throws Exception {
        this.noSuchAccount();
        for (int attempt = 0; attempt < LoginAttemptGuard.MAX_FAILURES; attempt++) {
            this.signIn(KNOWN_NAME, "guess");
        }

        this.signIn(KNOWN_NAME, "guess");

        // The lock is checked before the lookup, so a locked name costs no query and no hash.
        verify(this.appUserRepository, times(LoginAttemptGuard.MAX_FAILURES))
            .findFirstByUsernameIgnoreCaseAndStatusNot(anyString(), eq(Status.Delete));
    }

    @Test
    void theWaitIsCountedInWholeMinutesAndReadsAsOneWhenItIsOne() throws Exception {
        this.noSuchAccount();
        for (int attempt = 0; attempt < LoginAttemptGuard.MAX_FAILURES; attempt++) {
            this.signIn(KNOWN_NAME, "guess");
        }

        // Far enough into the lock that under a minute of it is left; the sentence must not say
        // "0 minutes", and must not say "1 minutes".
        this.now.addAndGet(LoginAttemptGuard.LOCK_SECONDS - 30);
        ResponseDto nearlyOver = this.signIn(KNOWN_NAME, "guess");

        assertThat(nearlyOver.getMessage()).isEqualTo("Too many sign-in attempts. Try again in 1 minute.");
    }

    @Test
    void theNameIsFreeAgainOnceTheLockHasPassed() throws Exception {
        AppUser user = this.activeUser();
        this.noSuchAccount();
        for (int attempt = 0; attempt < LoginAttemptGuard.MAX_FAILURES; attempt++) {
            this.signIn(KNOWN_NAME, "guess");
        }
        assertThat(this.signIn(KNOWN_NAME, "guess").getMessage())
            .startsWith("Too many sign-in attempts.");

        this.now.addAndGet(LoginAttemptGuard.LOCK_SECONDS + 1);
        this.accountExists(user);
        this.readyForSuccess(user);
        ResponseDto afterTheWait = this.signIn(KNOWN_NAME, "right");

        assertThat(afterTheWait.getStatus()).isEqualTo(SUCCESS);
    }

    @Test
    void theCountIsForgottenOnceTheRightPasswordArrives() throws Exception {
        AppUser user = this.activeUser();
        this.accountExists(user);
        when(this.passwordEncoder.matches("guess", STORED_HASH)).thenReturn(false);
        for (int attempt = 0; attempt < LoginAttemptGuard.MAX_FAILURES - 1; attempt++) {
            this.signIn(KNOWN_NAME, "guess");
        }

        this.readyForSuccess(user);
        assertThat(this.signIn(KNOWN_NAME, "right").getStatus()).isEqualTo(SUCCESS);

        // Four earlier failures must not combine with a later one to reach the limit: a person
        // who mistypes, succeeds, then mistypes again is not a guesser.
        when(this.passwordEncoder.matches("guess", STORED_HASH)).thenReturn(false);
        ResponseDto afterOneMoreMistake = this.signIn(KNOWN_NAME, "guess");

        assertThat(afterOneMoreMistake.getMessage()).isEqualTo("Invalid username or password.");
    }

    @Test
    void theCountIsKeptPerNameAndWithoutCase() throws Exception {
        this.noSuchAccount();
        for (int attempt = 0; attempt < LoginAttemptGuard.MAX_FAILURES; attempt++) {
            this.signIn(KNOWN_NAME, "guess");
        }

        // The same account reached by a differently-cased name is the same account, so it is
        // the same count -- otherwise the lock is bypassed by holding down shift.
        ResponseDto shouted = this.signIn(KNOWN_NAME.toUpperCase(), "guess");

        assertThat(shouted.getMessage()).startsWith("Too many sign-in attempts.");
    }

    // -- the request itself ---------------------------------------------------------------

    @Test
    void aRequestMissingEitherHalfIsRefusedBeforeAnythingIsLookedUp() throws Exception {
        ResponseDto noPassword = this.signIn(KNOWN_NAME, null);
        ResponseDto noName = this.signIn(null, "right");

        assertThat(noPassword.getStatus()).isEqualTo(ERROR);
        assertThat(noPassword.getMessage()).isEqualTo("Username and password are required.");
        assertThat(noName.getMessage()).isEqualTo("Username and password are required.");
        verify(this.appUserRepository, times(0))
            .findFirstByUsernameIgnoreCaseAndStatusNot(anyString(), any());
    }
}

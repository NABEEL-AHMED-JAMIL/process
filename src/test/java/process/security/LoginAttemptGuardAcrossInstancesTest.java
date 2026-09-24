package process.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T5 (16-testing-strategy section 8.1), MIG-109 and the LoginAttemptGuard half of MIG-64: the
 * brute-force budget is one budget however many instances answer sign-in.
 *
 * Instances A and B are two guards over one Redis, as two process replicas are. The clock is the
 * test's, passed to both, so the window and the lock are exercised to the second without waiting.
 */
class LoginAttemptGuardAcrossInstancesTest {

    private static final String NAME = "emily@example.com";

    private RedisLoginGuards redis;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private LoginAttemptGuard a;
    private LoginAttemptGuard b;

    @BeforeEach
    void setUp() {
        this.redis = RedisLoginGuards.open();
        this.a = this.redis.guard(this.now::get);
        this.b = this.redis.guard(this.now::get);
    }

    @AfterEach
    void tearDown() {
        if (this.redis != null) this.redis.close();
    }

    /** The values themselves are security behaviour; moving the count to Redis must not move them. */
    @Test
    void theThresholdWindowAndLockAreTheSameNumbersAsBefore() {
        assertThat(LoginAttemptGuard.MAX_FAILURES).isEqualTo(5);
        assertThat(LoginAttemptGuard.WINDOW_SECONDS).isEqualTo(15 * 60);
        assertThat(LoginAttemptGuard.LOCK_SECONDS).isEqualTo(15 * 60);
    }

    @Test
    void fiveFailuresSpreadAcrossTwoInstancesLockTheNameOnBoth() {
        this.a.failed(NAME);
        this.b.failed(NAME);
        this.a.failed(NAME);
        this.b.failed(NAME);
        assertThat(this.a.secondsUntilAllowed(NAME)).as("the fourth does not lock").isZero();
        assertThat(this.b.secondsUntilAllowed(NAME)).isZero();

        this.a.failed(NAME);

        assertThat(this.a.secondsUntilAllowed(NAME)).as("the fifth does, on A").isEqualTo(LoginAttemptGuard.LOCK_SECONDS);
        assertThat(this.b.secondsUntilAllowed(NAME)).as("and B sees it at once, not after a TTL")
            .isEqualTo(LoginAttemptGuard.LOCK_SECONDS);
    }

    @Test
    void aSuccessOnOneInstanceForgivesTheCountOnEvery() {
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES - 1; i++) {
            (i % 2 == 0 ? this.a : this.b).failed(NAME);
        }
        this.a.succeeded(NAME);

        this.b.failed(NAME);

        assertThat(this.b.secondsUntilAllowed(NAME)).isZero();
        assertThat(this.a.secondsUntilAllowed(NAME)).isZero();
    }

    @Test
    void theWindowAndTheLockRunOnTheSameClockAsBefore() {
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES - 1; i++) {
            this.a.failed(NAME);
        }
        this.now.addAndGet(LoginAttemptGuard.WINDOW_SECONDS + 1);
        this.b.failed(NAME);
        assertThat(this.b.secondsUntilAllowed(NAME)).as("four old failures and one new are a count of one").isZero();

        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES - 1; i++) {
            this.a.failed(NAME);
        }
        assertThat(this.a.secondsUntilAllowed(NAME)).isEqualTo(LoginAttemptGuard.LOCK_SECONDS);
        this.now.addAndGet(LoginAttemptGuard.LOCK_SECONDS - 30);
        assertThat(this.b.secondsUntilAllowed(NAME)).isEqualTo(30);
        this.now.addAndGet(31);
        assertThat(this.b.secondsUntilAllowed(NAME)).isZero();
    }

    @Test
    void everySpellingOfANameIsOneCountAndNoNameIsStoredInRedis() {
        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES; i++) {
            (i % 2 == 0 ? this.a : this.b).failed(i % 2 == 0 ? "  Emily@Example.com " : NAME.toUpperCase());
        }
        assertThat(this.b.secondsUntilAllowed(NAME)).isPositive();

        Set<String> keys = this.redis.redis().keys(this.redis.prefix() + "*");
        assertThat(keys).hasSize(1);
        assertThat(keys.iterator().next().toLowerCase()).doesNotContain("emily").doesNotContain("example");
    }

    /**
     * The increment and the check are one script: failures landing on both instances at the same
     * moment are all counted. A read-then-write guard loses some of them and never reaches five.
     */
    @Test
    void simultaneousFailuresOnBothInstancesAreAllCounted() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(LoginAttemptGuard.MAX_FAILURES);
        try {
            for (int round = 0; round < 20; round++) {
                String name = "race-" + round + "@example.com";
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> done = new ArrayList<>();
                for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES; i++) {
                    LoginAttemptGuard guard = i % 2 == 0 ? this.a : this.b;
                    done.add(pool.submit(() -> {
                        start.await();
                        guard.failed(name);
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> f : done) f.get(10, TimeUnit.SECONDS);
                assertThat(this.a.secondsUntilAllowed(name)).as("round %d", round).isPositive();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** No Redis, no guess: the guard refuses to answer rather than answering "go ahead". */
    @Test
    void anUnreachableRedisFailsClosed() {
        LettuceConnectionFactory nowhere = new LettuceConnectionFactory("localhost", 1);
        nowhere.afterPropertiesSet();
        try {
            LoginAttemptGuard guard = new LoginAttemptGuard(RedisLoginGuards.template(nowhere), "x:", this.now::get);
            assertThatThrownBy(() -> guard.secondsUntilAllowed(NAME)).isInstanceOf(LoginAttemptGuard.Unavailable.class);
            guard.failed(NAME);
            guard.succeeded(NAME);
        } finally {
            nowhere.destroy();
        }
    }

    // -- through sign-in itself ---------------------------------------------------------

    private AuthServiceImpl signIn(LoginAttemptGuard guard, AppUserRepository users, PasswordEncoder encoder) {
        return new AuthServiceImpl(users, mock(TenantRepository.class), encoder, mock(JwtUtil.class),
            mock(PageAccessService.class), guard, mock(TokenRevocations.class));
    }

    private static ResponseDto attempt(AuthServiceImpl auth, String password) throws Exception {
        LoginRequestDto request = new LoginRequestDto();
        request.setUsername(NAME);
        request.setPassword(password);
        return auth.login(request);
    }

    @Test
    void wrongPasswordsSpreadAcrossTwoInstancesLockSignInOnBoth() throws Exception {
        AppUserRepository users = mock(AppUserRepository.class);
        AppUser emily = new AppUser();
        emily.setAppUserId(1L);
        emily.setUsername(NAME);
        emily.setPassword("stored");
        emily.setUserRole(UserRole.PLATFORM_ADMIN);
        emily.setStatus(Status.Active);
        when(users.findLiveByUsernameIgnoringCase(anyString())).thenReturn(Optional.of(emily));
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("nobody");
        AuthServiceImpl onA = this.signIn(this.a, users, encoder);
        AuthServiceImpl onB = this.signIn(this.b, users, encoder);

        for (int i = 0; i < LoginAttemptGuard.MAX_FAILURES; i++) {
            assertThat(attempt(i % 2 == 0 ? onA : onB, "guess").getMessage()).isEqualTo("Invalid username or password.");
        }

        assertThat(attempt(onA, "guess").getMessage()).isEqualTo("Too many sign-in attempts. Try again in 15 minutes.");
        assertThat(attempt(onB, "guess").getMessage()).isEqualTo("Too many sign-in attempts. Try again in 15 minutes.");
    }

    /** One sentence for every name while the guard is down, and no lookup: nothing to tell names apart by. */
    @Test
    void signInRefusesEveryoneAlikeWhileTheGuardIsDown() throws Exception {
        LettuceConnectionFactory nowhere = new LettuceConnectionFactory("localhost", 1);
        nowhere.afterPropertiesSet();
        try {
            AppUserRepository users = mock(AppUserRepository.class);
            PasswordEncoder encoder = mock(PasswordEncoder.class);
            AuthServiceImpl auth = this.signIn(new LoginAttemptGuard(RedisLoginGuards.template(nowhere), "x:", this.now::get),
                users, encoder);

            ResponseDto refused = attempt(auth, "right");

            assertThat(refused.getStatus()).isEqualTo("ERROR");
            assertThat(refused.getMessage()).isEqualTo("Sign-in is unavailable right now. Try again in a few minutes.");
            verify(users, never()).findLiveByUsernameIgnoringCase(anyString());
            verify(encoder, never()).matches(anyString(), anyString());
        } finally {
            nowhere.destroy();
        }
    }
}

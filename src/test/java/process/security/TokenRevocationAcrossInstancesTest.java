package process.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.dto.AppUserDto;
import process.model.dto.AuthResponseDto;
import process.model.dto.ResponseDto;
import process.model.dto.TenantDto;
import process.model.enums.Status;
import process.model.enums.TenantStatus;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.Tenant;
import process.model.repository.AppUserRepository;
import process.model.repository.TenantRepository;
import process.model.service.PageAccessService;
import process.model.service.impl.AppUserServiceImpl;
import process.model.service.impl.AuthServiceImpl;
import process.model.service.impl.TenantServiceImpl;
import process.notifications.TestNotifications;
import process.storage.TrustedStorageOperations;
import process.util.JwtUtil;
import process.util.UserNameResolver;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-14 (DEF-013): a token stops working the moment it is signed out, or the moment its person's
 * role, tenant or status changes, or their tenant is suspended -- on every instance, not after the
 * thirty minutes (seven days for a refresh token) it used to keep.
 *
 * Instances A and B are two sets of process beans -- JwtAuthenticationFilter, TokenRevocations, the
 * services -- over one Redis and one database, as two replicas are. The database is app_user's
 * token_version held in a map behind a mocked repository; IdentityTokenVersionPostgresTest proves the
 * real SQL. The services, the filter, JwtUtil and Redis are real.
 */
class TokenRevocationAcrossInstancesTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 2002L;

    private RedisLoginGuards redis;
    private JwtUtil jwt;
    private final Map<Long, AppUser> rows = new ConcurrentHashMap<>();
    private final Map<Long, Integer> versions = new ConcurrentHashMap<>();
    private AppUserRepository users;
    private Instance a;
    private Instance b;

    /** One replica's worth of the beans that matter here. */
    private final class Instance {
        final TokenRevocations revocations;
        final JwtAuthenticationFilter filter;
        final AuthServiceImpl auth;
        final AppUserServiceImpl people;
        final TenantServiceImpl tenants;

        Instance(TokenRevocations revocations, boolean singleUseRefresh) {
            this.revocations = revocations;
            this.filter = new JwtAuthenticationFilter(jwt, revocations);
            PasswordEncoder encoder = mock(PasswordEncoder.class);
            lenient().when(encoder.encode(anyString())).thenReturn("hash");
            this.auth = new AuthServiceImpl(users, tenantRepository(), encoder, jwt, mock(PageAccessService.class),
                mock(LoginAttemptGuard.class), revocations);
            ReflectionTestUtils.setField(this.auth, "singleUseRefreshTokens", singleUseRefresh);
            this.people = new AppUserServiceImpl(users, tenantRepository(), encoder,
                TestNotifications.recording(null, null, mock(TestNotifications.NoticeSink.class), mock(TestNotifications.MailSink.class)),
                mock(UserNameResolver.class), mock(TrustedStorageOperations.class), mock(PageAccessService.class),
                mock(TenantFilterHelper.class), revocations);
            this.tenants = new TenantServiceImpl(tenantRepository(), users, null, null, null, null, null,
                mock(UserNameResolver.class), revocations);
        }

        /** Who the filter says is calling with this token, or null for nobody. */
        Long caller(String accessToken) throws Exception {
            SecurityContextHolder.clearContext();
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/sourceJob.json/listSourceJob");
            request.addHeader("Authorization", "Bearer " + accessToken);
            Long[] seen = new Long[1];
            this.filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                seen[0] = SecurityContextHolder.getContext().getAuthentication() == null ? null : TenantContext.getAppUserId();
            });
            SecurityContextHolder.clearContext();
            return seen[0];
        }
    }

    private final Map<Long, Tenant> tenantRows = new ConcurrentHashMap<>();

    private TenantRepository tenantRepository() {
        TenantRepository tenants = mock(TenantRepository.class);
        lenient().when(tenants.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(this.tenantRows.get((Long) i.getArgument(0))));
        return tenants;
    }

    @BeforeEach
    void setUp() {
        this.redis = RedisLoginGuards.open();
        this.jwt = new JwtUtil();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 11 + 5);
        ReflectionTestUtils.setField(this.jwt, "base64Key", Base64.getEncoder().encodeToString(key));
        ReflectionTestUtils.setField(this.jwt, "accessTokenExpiryMinutes", 30L);
        ReflectionTestUtils.setField(this.jwt, "refreshTokenExpiryDays", 7L);

        this.users = mock(AppUserRepository.class);
        lenient().when(this.users.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(this.fresh((Long) i.getArgument(0))));
        lenient().when(this.users.findByUsernameAndStatusNot(anyString(), any())).thenAnswer(i -> this.rows.values().stream()
            .filter(u -> u.getUsername().equals(i.getArgument(0)) && u.getStatus() != Status.Delete).findFirst()
            .map(u -> this.fresh(u.getAppUserId())));
        lenient().when(this.users.findTokenVersion(anyLong())).thenAnswer(i -> this.versions.get((Long) i.getArgument(0)));
        lenient().when(this.users.bumpTokenVersion(anyLong())).thenAnswer(i -> {
            this.versions.merge(i.getArgument(0), 1, Integer::sum);
            return 1;
        });
        lenient().when(this.users.bumpTokenVersionsInTenant(anyLong())).thenAnswer(i -> {
            List<Long> ids = this.idsIn(i.getArgument(0));
            ids.forEach(id -> this.versions.merge(id, 1, Integer::sum));
            return ids.size();
        });
        lenient().when(this.users.findIdsInTenant(anyLong())).thenAnswer(i -> new ArrayList<Number>(this.idsIn(i.getArgument(0))));

        this.tenant(TENANT_A);
        this.tenant(TENANT_B);
        this.a = new Instance(new TokenRevocations(this.redis.redis(), this.users, this.redis.prefix()), false);
        this.b = new Instance(new TokenRevocations(this.redis.redis(), this.users, this.redis.prefix()), false);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (this.redis != null) this.redis.close();
    }

    private void tenant(long id) {
        Tenant tenant = new Tenant();
        tenant.setTenantId(id);
        tenant.setTenantName("T" + id);
        tenant.setStatus(TenantStatus.Active);
        this.tenantRows.put(id, tenant);
    }

    private List<Long> idsIn(Long tenantId) {
        return this.rows.values().stream().filter(u -> tenantId.equals(u.getTenantId())).map(AppUser::getAppUserId)
            .collect(Collectors.toList());
    }

    /** A copy of the row as the database would return it now, token_version included. */
    private AppUser fresh(Long id) {
        AppUser row = this.rows.get(id);
        if (row == null) return null;
        AppUser copy = new AppUser();
        copy.setAppUserId(row.getAppUserId());
        copy.setUsername(row.getUsername());
        copy.setFullName(row.getFullName());
        copy.setTenantId(row.getTenantId());
        copy.setUserRole(row.getUserRole());
        copy.setStatus(row.getStatus());
        copy.setTokenVersion(this.versions.get(id));
        return copy;
    }

    private AppUser person(long id, Long tenantId, UserRole role) {
        AppUser user = new AppUser();
        user.setAppUserId(id);
        user.setUsername("p" + id + "@example.com");
        user.setFullName("P" + id);
        user.setTenantId(tenantId);
        user.setUserRole(role);
        user.setStatus(Status.Active);
        this.rows.put(id, user);
        this.versions.put(id, 0);
        // What save() does to the database: the row takes the service's changes, never the version.
        lenient().when(this.users.save(any(AppUser.class))).thenAnswer(i -> {
            AppUser saved = i.getArgument(0);
            AppUser stored = this.rows.get(saved.getAppUserId());
            stored.setUserRole(saved.getUserRole());
            stored.setTenantId(saved.getTenantId());
            stored.setStatus(saved.getStatus());
            return saved;
        });
        return user;
    }

    private String accessFor(long id) {
        return this.jwt.generateAccessToken(this.fresh(id));
    }

    private String refreshFor(long id) {
        return this.jwt.generateRefreshToken(this.fresh(id));
    }

    private void actAsPlatformAdmin() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@example.com");
    }

    // -- the claims -----------------------------------------------------------------------

    @Test
    void everyTokenHasItsOwnIdAndCarriesItsPersonsVersion() {
        this.person(10L, TENANT_A, UserRole.TENANT_USER);
        this.versions.put(10L, 4);

        Claims one = this.jwt.parseClaims(this.accessFor(10L));
        Claims two = this.jwt.parseClaims(this.accessFor(10L));

        assertThat(one.getId()).isNotBlank().isNotEqualTo(two.getId());
        assertThat(TokenRevocations.mintedUnder(one)).isEqualTo(4);
        assertThat(this.jwt.parseClaims(this.refreshFor(10L)).getId()).isNotBlank();
    }

    // -- sign-out -------------------------------------------------------------------------

    @Test
    void signingOutOnAEndsBothTokensOnB() throws Exception {
        this.person(10L, TENANT_A, UserRole.TENANT_USER);
        String access = this.accessFor(10L);
        String refresh = this.refreshFor(10L);
        assertThat(this.b.caller(access)).isEqualTo(10L);

        assertThat(this.a.auth.logout(access, refresh).getMessage()).isEqualTo("Signed out.");

        assertThat(this.b.caller(access)).as("the access token, on the other instance").isNull();
        assertThat(this.a.caller(access)).isNull();
        assertThat(this.b.auth.refresh(refresh).getMessage()).isEqualTo("Refresh token is invalid or expired -- please log in again.");
    }

    @Test
    void aSignedOutTokenReplayedIsRefusedWhileItsSiblingStillWorks() throws Exception {
        this.person(10L, TENANT_A, UserRole.TENANT_USER);
        String laptop = this.accessFor(10L);
        String phone = this.accessFor(10L);

        this.a.auth.logout(laptop, null);

        for (int replay = 0; replay < 3; replay++) {
            assertThat(this.b.caller(laptop)).isNull();
        }
        assertThat(this.b.caller(phone)).as("signing out one device is not signing out all").isEqualTo(10L);
    }

    @Test
    void signOutAnswersTheSameForAnythingUnreadable() {
        assertThat(this.a.auth.logout("not.a.token", "nor.is.this").getMessage()).isEqualTo("Signed out.");
        assertThat(this.a.auth.logout(null, null).getMessage()).isEqualTo("Signed out.");
    }

    // -- a changed standing ends every token ------------------------------------------------

    @Test
    void aDemotedAdminsTokenStopsAtOnceOnEveryInstance() throws Exception {
        this.person(20L, TENANT_A, UserRole.TENANT_ADMIN);
        String before = this.accessFor(20L);
        String refreshBefore = this.refreshFor(20L);
        assertThat(this.b.caller(before)).isEqualTo(20L);

        this.actAsPlatformAdmin();
        AppUserDto demote = new AppUserDto();
        demote.setAppUserId(20L);
        demote.setFullName("P20");
        demote.setUserRole(UserRole.TENANT_USER);
        assertThat(this.a.people.updateUser(demote).getStatus()).isEqualTo("SUCCESS");
        TenantContext.clear();

        assertThat(this.b.caller(before)).isNull();
        assertThat(this.b.auth.refresh(refreshBefore).getStatus()).isEqualTo("ERROR");
        String after = this.accessFor(20L);
        assertThat(this.b.caller(after)).as("a token minted after the change works").isEqualTo(20L);
    }

    @Test
    void aDeactivatedPersonsTokenStopsAtOnce() throws Exception {
        this.person(30L, TENANT_A, UserRole.TENANT_USER);
        String before = this.accessFor(30L);

        this.actAsPlatformAdmin();
        AppUserDto deactivate = new AppUserDto();
        deactivate.setAppUserId(30L);
        deactivate.setStatus(Status.Inactive);
        this.a.people.changeUserStatus(deactivate);
        TenantContext.clear();

        assertThat(this.b.caller(before)).isNull();
    }

    @Test
    void aMovedPersonLosesTheOldTenantsScopeAtOnce() throws Exception {
        this.person(40L, TENANT_A, UserRole.TENANT_USER);
        String before = this.accessFor(40L);

        this.actAsPlatformAdmin();
        AppUserDto move = new AppUserDto();
        move.setAppUserId(40L);
        move.setFullName("P40");
        move.setUserRole(UserRole.TENANT_USER);
        move.setTenantId(TENANT_B);
        this.a.people.updateUser(move);
        TenantContext.clear();

        assertThat(this.b.caller(before)).isNull();
        assertThat(this.jwt.tenantIdOf(this.jwt.parseClaims(this.accessFor(40L)))).isEqualTo(TENANT_B);
    }

    @Test
    void aSuspendedTenantsPeopleStopAtOnceAndNobodyElseDoes() throws Exception {
        this.person(50L, TENANT_A, UserRole.TENANT_ADMIN);
        this.person(51L, TENANT_A, UserRole.TENANT_USER);
        this.person(60L, TENANT_B, UserRole.TENANT_USER);
        String admin = this.accessFor(50L);
        String user = this.accessFor(51L);
        String elsewhere = this.accessFor(60L);

        this.actAsPlatformAdmin();
        TenantDto suspend = new TenantDto();
        suspend.setTenantId(TENANT_A);
        suspend.setStatus(TenantStatus.Suspended);
        this.a.tenants.changeTenantStatus(suspend);
        TenantContext.clear();

        assertThat(this.b.caller(admin)).isNull();
        assertThat(this.b.caller(user)).isNull();
        assertThat(this.b.caller(elsewhere)).isEqualTo(60L);
    }

    /** An administrator's password reset is how an account is taken back: the old sessions go with it. */
    @Test
    void anAdministratorsPasswordResetEndsThePersonsTokens() throws Exception {
        this.person(70L, TENANT_A, UserRole.TENANT_USER);
        String before = this.accessFor(70L);

        this.actAsPlatformAdmin();
        AppUserDto reset = new AppUserDto();
        reset.setAppUserId(70L);
        reset.setPassword("Passw0rd!");
        this.a.people.resetPassword(reset);
        TenantContext.clear();

        assertThat(this.b.caller(before)).isNull();
    }

    /** Editing a name is not a change of standing: nobody is signed out for it. */
    @Test
    void anEditThatChangesNeitherRoleNorTenantEndsNothing() throws Exception {
        this.person(80L, TENANT_A, UserRole.TENANT_USER);
        String before = this.accessFor(80L);

        this.actAsPlatformAdmin();
        AppUserDto rename = new AppUserDto();
        rename.setAppUserId(80L);
        rename.setFullName("Renamed");
        rename.setUserRole(UserRole.TENANT_USER);
        this.a.people.updateUser(rename);
        TenantContext.clear();

        assertThat(this.b.caller(before)).isEqualTo(80L);
    }

    /**
     * The race the Lua max exists for: B misses the cache, reads the old version from the database, and
     * before it writes that into Redis, A bumps and publishes the new one. B's late write must not put
     * the old number back, or the old token would be good again for as long as the cache lasts.
     */
    @Test
    void aReaderRacingABumpCannotPutTheOldVersionBack() throws Exception {
        this.person(98L, TENANT_A, UserRole.TENANT_USER);
        String before = this.accessFor(98L);
        boolean[] raced = new boolean[1];
        when(this.users.findTokenVersion(98L)).thenAnswer(i -> {
            Integer read = this.versions.get(98L);
            if (!raced[0]) {
                raced[0] = true;
                this.a.revocations.revokeSessionsOf(98L);
            }
            return read;
        });

        assertThat(this.b.caller(before)).as("B read version 0 before the bump landed").isEqualTo(98L);

        assertThat(this.redis.redis().opsForValue().get(this.redis.prefix() + "auth:token-version:98")).isEqualTo("1");
        assertThat(this.b.caller(before)).isNull();
        assertThat(this.a.caller(before)).isNull();
    }

    // -- refresh ------------------------------------------------------------------------

    @Test
    void refreshRotatesTheRefreshTokenWithoutMovingItsExpiry() throws Exception {
        this.person(90L, TENANT_A, UserRole.TENANT_USER);
        // Signed in four days ago: three days of the seven left, which the rotated token must keep.
        String presented = this.jwt.rotateRefreshToken(this.fresh(90L), new Date(System.currentTimeMillis() + 3L * 24 * 60 * 60 * 1000));

        ResponseDto refreshed = this.a.auth.refresh(presented);

        AuthResponseDto data = (AuthResponseDto) refreshed.getData();
        Claims before = this.jwt.parseClaims(presented);
        Claims after = this.jwt.parseClaims(data.getRefreshToken());
        assertThat(after.getId()).isNotEqualTo(before.getId());
        assertThat(after.getExpiration()).isEqualTo(before.getExpiration());
        assertThat(this.b.caller(data.getAccessToken())).isEqualTo(90L);
        // Single use is off by default, until both consoles keep the rotated token: the old one still works.
        assertThat(this.b.auth.refresh(presented).getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void aSingleUseRefreshTokenPresentedTwiceEndsTheWholeFamily() throws Exception {
        Instance strictA = new Instance(new TokenRevocations(this.redis.redis(), this.users, this.redis.prefix()), true);
        Instance strictB = new Instance(new TokenRevocations(this.redis.redis(), this.users, this.redis.prefix()), true);
        this.person(91L, TENANT_A, UserRole.TENANT_USER);
        String stolen = this.refreshFor(91L);

        AuthResponseDto rightful = (AuthResponseDto) strictA.auth.refresh(stolen).getData();
        assertThat(strictB.caller(rightful.getAccessToken())).isEqualTo(91L);

        ResponseDto replay = strictB.auth.refresh(stolen);

        assertThat(replay.getMessage()).isEqualTo("Refresh token is invalid or expired -- please log in again.");
        assertThat(strictA.caller(rightful.getAccessToken())).as("the rightful holder's access token too").isNull();
        assertThat(strictA.auth.refresh(rightful.getRefreshToken()).getStatus()).as("and their new refresh token").isEqualTo("ERROR");
    }

    // -- Redis unavailable, and what the check costs ---------------------------------------

    @Test
    void anUnreachableRedisFailsClosedEverywhere() throws Exception {
        this.person(95L, TENANT_A, UserRole.TENANT_USER);
        String access = this.accessFor(95L);
        LettuceConnectionFactory nowhere = new LettuceConnectionFactory("localhost", 1);
        nowhere.afterPropertiesSet();
        try {
            Instance down = new Instance(new TokenRevocations(RedisLoginGuards.template(nowhere), this.users, "x:"), false);
            assertThat(down.caller(access)).as("no token is vouched for").isNull();
            assertThat(down.auth.refresh(this.refreshFor(95L)).getMessage())
                .isEqualTo("Refresh token is invalid or expired -- please log in again.");
            assertThat(down.auth.logout(access, null).getMessage())
                .isEqualTo("Sign-out could not be recorded. Try again in a few minutes.");
        } finally {
            nowhere.destroy();
        }
    }

    /** One Redis round trip per request once the version is cached; bounded, not a database read each time. */
    @Test
    void theCheckIsOneRoundTripAndStaysFast() {
        this.person(99L, TENANT_A, UserRole.TENANT_USER);
        Claims claims = this.jwt.parseClaims(this.accessFor(99L));
        this.a.revocations.isRevoked(claims);

        List<Long> nanos = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            long start = System.nanoTime();
            assertThat(this.a.revocations.isRevoked(claims)).isFalse();
            nanos.add(System.nanoTime() - start);
        }
        Collections.sort(nanos);
        long p99Millis = nanos.get(989) / 1_000_000;
        assertThat(p99Millis).as("p99 of the revocation check, ms").isLessThan(20);
        // The version came from the database once, then from Redis.
        verify(this.users, times(1)).findTokenVersion(99L);
    }
}

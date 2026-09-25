package process.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import process.identity.TestIdentity;
import process.model.enums.Status;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.repository.AppUserRepository;
import process.util.JwtUtil;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-14 (DEF-013), the read side: a token minted under an older version than its person's is refused on
 * every instance, the moment the newer version is published, and an unreachable Redis vouches for nobody.
 *
 * Writing a revocation -- a sign-out, a bump on a change of role, tenant or status, a suspended tenant -- is
 * identity-service's since MIG-107; process's own writers left with its identity endpoints (MIG-108), and
 * their cases went with them. What stays in process is TokenRevocations.isRevoked, which LocalIdentity asks
 * (identity.mode=local), and the Lua max that keeps a late reader from lowering a published version.
 *
 * Instances A and B are two sets of process beans -- JwtAuthenticationFilter and TokenRevocations -- over one
 * Redis and one database, as two replicas are. The database is app_user's token_version held in a map behind
 * a mocked repository. The filter, JwtUtil and Redis are real.
 */
class TokenRevocationAcrossInstancesTest {

    private static final long TENANT_A = 1001L;

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

        Instance(TokenRevocations revocations) {
            this.revocations = revocations;
            this.filter = new JwtAuthenticationFilter(TestIdentity.authenticating(jwt, revocations));
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
        lenient().when(this.users.findTokenVersion(anyLong())).thenAnswer(i -> this.versions.get((Long) i.getArgument(0)));

        this.a = new Instance(new TokenRevocations(this.redis.redis(), this.users, this.redis.prefix()));
        this.b = new Instance(new TokenRevocations(this.redis.redis(), this.users, this.redis.prefix()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (this.redis != null) this.redis.close();
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
        return user;
    }

    private String accessFor(long id) {
        return this.jwt.generateAccessToken(this.fresh(id));
    }

    private String refreshFor(long id) {
        return this.jwt.generateRefreshToken(this.fresh(id));
    }

    /** What identity-service does on a change of the person's standing: the row's version up, and published. */
    private void bumpedByIdentity(long id) {
        int version = this.versions.merge(id, 1, Integer::sum);
        this.redis.redis().opsForValue().set(this.redis.prefix() + "auth:token-version:" + id, String.valueOf(version));
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

    // -- a published version -----------------------------------------------------------------

    @Test
    void aPublishedBumpStopsTheOldTokenOnEveryInstance() throws Exception {
        this.person(97L, TENANT_A, UserRole.TENANT_USER);
        String before = this.accessFor(97L);
        assertThat(this.a.caller(before)).isEqualTo(97L);
        assertThat(this.b.caller(before)).isEqualTo(97L);

        this.bumpedByIdentity(97L);

        assertThat(this.a.caller(before)).isNull();
        assertThat(this.b.caller(before)).isNull();
        assertThat(this.b.caller(this.accessFor(97L))).as("a token minted under the new version").isEqualTo(97L);
    }

    /**
     * The race the Lua max exists for: B misses the cache, reads the old version from the database, and
     * before it writes that into Redis, Identity bumps and publishes the new one. B's late write must not put
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
                this.bumpedByIdentity(98L);
            }
            return read;
        });

        assertThat(this.b.caller(before)).as("B read version 0 before the bump landed").isEqualTo(98L);

        assertThat(this.redis.redis().opsForValue().get(this.redis.prefix() + "auth:token-version:98")).isEqualTo("1");
        assertThat(this.b.caller(before)).isNull();
        assertThat(this.a.caller(before)).isNull();
    }

    // -- Redis unavailable, and what the check costs ---------------------------------------

    @Test
    void anUnreachableRedisFailsClosedEverywhere() throws Exception {
        this.person(95L, TENANT_A, UserRole.TENANT_USER);
        String access = this.accessFor(95L);
        LettuceConnectionFactory nowhere = new LettuceConnectionFactory("localhost", 1);
        nowhere.afterPropertiesSet();
        try {
            Instance down = new Instance(new TokenRevocations(RedisLoginGuards.template(nowhere), this.users, "x:"));
            assertThat(down.caller(access)).as("no token is vouched for").isNull();
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

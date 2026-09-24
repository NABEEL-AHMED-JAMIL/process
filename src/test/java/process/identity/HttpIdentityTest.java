package process.identity;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.barco.platform.security.CallerIdentity;
import org.barco.platform.security.JwtVerifier;
import org.barco.platform.security.RevocationCheck;
import org.barco.platform.tenancy.TenantScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * MIG-107: IdentityPort once Identity is its own service. A token is verified here -- RS256 by the kid
 * Identity publishes, HS256 only while accepted, then the shared revocations -- so authenticating never
 * waits on Identity. The directory is one POST with the service token, and a failure is Unavailable, never
 * an empty answer that reads as "nobody". The page gate asks only about a tenant user on a gated path,
 * keeps each answer 15 seconds, and refuses when it cannot ask.
 */
class HttpIdentityTest {

    private static final String BASE = "http://identity:9160/api/v1/internal/identity";
    private static final String TOKEN = "s3rvice";
    private static final byte[] HS_KEY = new byte[32];

    static {
        for (int i = 0; i < HS_KEY.length; i++) HS_KEY[i] = (byte) (i * 7 + 3);
    }

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private KeyPair rsa;
    private ValueOperations<String, String> revocations;
    private MockRestServiceServer identity;
    private HttpIdentity port;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        this.rsa = generator.generateKeyPair();
        RedisTemplate<String, String> redis = mock(RedisTemplate.class);
        this.revocations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(this.revocations);
        when(this.revocations.multiGet(anyList())).thenReturn(Arrays.asList(null, null));
        JwtVerifier verifier = JwtVerifier.builder()
            .rs256(kid -> "k1".equals(kid) ? this.rsa.getPublic() : null)
            .hs256(Base64.getEncoder().encodeToString(HS_KEY), true)
            .revocations(new RevocationCheck(redis))
            .build();
        RestTemplate http = new RestTemplate();
        this.identity = MockRestServiceServer.bindTo(http).build();
        this.port = new HttpIdentity(http, "http://identity:9160/", TOKEN, verifier, this.now::get);
    }

    private String rs256(String type, Object tenantId) {
        return Jwts.builder().setHeaderParam("kid", "k1").setId("jti-1").setSubject("olivia@a.example")
            .claim("appUserId", 44).claim("tenantId", tenantId).claim("userRole", "TENANT_USER").claim("type", type)
            .claim("tokenVersion", 2).setExpiration(new Date(System.currentTimeMillis() + 60_000))
            .signWith(this.rsa.getPrivate(), SignatureAlgorithm.RS256).compact();
    }

    // ---- authentication: here, not at Identity --------------------------------------------------

    @Test
    void anRs256AccessTokenAuthenticatesItsPersonWithoutAskingIdentity() {
        CallerIdentity caller = this.port.authenticate(this.rs256("access", 2901)).orElseThrow(AssertionError::new);

        assertThat(caller.getAppUserId()).isEqualTo(44L);
        assertThat(caller.getTenantId()).isEqualTo(2901L);
        assertThat(caller.getUserRole()).isEqualTo("TENANT_USER");
        assertThat(caller.getUsername()).isEqualTo("olivia@a.example");
        assertThat(caller.getTokenVersion()).isEqualTo(2);
        this.identity.verify();
    }

    @Test
    void anHs256TokenStillAuthenticatesWhileItIsAccepted() {
        String token = Jwts.builder().setSubject("olivia@a.example").claim("appUserId", 44).claim("tenantId", 2901)
            .claim("userRole", "TENANT_USER").claim("type", "access").setExpiration(new Date(System.currentTimeMillis() + 60_000))
            .signWith(Keys.hmacShaKeyFor(HS_KEY), SignatureAlgorithm.HS256).compact();

        assertThat(this.port.authenticate(token)).isPresent();
    }

    @Test
    void aRefreshTokenABadOneOrNoneAuthenticatesNobody() {
        assertThat(this.port.authenticate(this.rs256("refresh", 2901))).isEmpty();
        assertThat(this.port.authenticate("not-a-token")).isEmpty();
        assertThat(this.port.authenticate("  ")).isEmpty();
        assertThat(this.port.authenticate(null)).isEmpty();
    }

    @Test
    void aSignedOutOrOutrankedTokenAuthenticatesNobody() {
        when(this.revocations.multiGet(anyList())).thenReturn(Arrays.asList("1", null));
        assertThat(this.port.authenticate(this.rs256("access", 2901))).as("on the denylist").isEmpty();

        when(this.revocations.multiGet(anyList())).thenReturn(Arrays.asList(null, "3"));
        assertThat(this.port.authenticate(this.rs256("access", 2901))).as("minted under an older version").isEmpty();
    }

    /** Revocations that cannot be read refuse the token: authentication fails closed (MIG-14). */
    @Test
    void revocationsThatCannotBeReadRefuseTheToken() {
        when(this.revocations.multiGet(anyList())).thenThrow(new QueryTimeoutException("redis away"));

        assertThat(this.port.authenticate(this.rs256("access", 2901))).isEmpty();
    }

    /** A tenant claim that is not a number is no tenant -- never every tenant (MIG-93). */
    @Test
    void aTenantClaimThatIsNotANumberIsNoTenant() {
        CallerIdentity caller = this.port.authenticate(this.rs256("access", "everything")).orElseThrow(AssertionError::new);

        assertThat(caller.getTenantId()).isNull();
        assertThat(TenantScope.of(caller.getTenantId(), caller.getUserRole(), caller.getAppUserId()).isAllTenants()).isFalse();
    }

    // ---- the directory: one POST, the service token, and failure said out loud ------------------------

    @Test
    void peopleAreAskedForOnceWithTheServiceToken() {
        this.identity.expect(requestTo(BASE + "/people")).andExpect(method(HttpMethod.POST))
            .andExpect(header("X-Internal-Token", TOKEN)).andExpect(content().json("{\"ids\":[7,8]}"))
            .andRespond(withSuccess("[{\"appUserId\":7,\"tenantId\":2901,\"username\":\"d@a.example\",\"fullName\":\"Daniel\","
                + "\"userRole\":\"TENANT_ADMIN\",\"status\":\"Active\"}]", MediaType.APPLICATION_JSON));

        Map<Long, IdentityPort.Person> people = this.port.people(Arrays.asList(8L, 7L, null, 7L));

        assertThat(people).containsOnlyKeys(7L);
        IdentityPort.Person daniel = people.get(7L);
        assertThat(daniel.getTenantId()).isEqualTo(2901L);
        assertThat(daniel.getUserRole()).isEqualTo("TENANT_ADMIN");
        assertThat(daniel.getDisplayName()).isEqualTo("Daniel");
        this.identity.verify();
    }

    @Test
    void noIdsAsksNothing() {
        assertThat(this.port.people(Collections.emptyList())).isEmpty();
        assertThat(this.port.person(null)).isEmpty();
        assertThat(this.port.workspaces(null)).isEmpty();
        assertThat(this.port.members(TenantScope.none())).isEmpty();
        this.identity.verify();
    }

    /** Identity down is Unavailable -- never an empty answer a caller would read as "nobody" or "no such workspace". */
    @Test
    void identityDownIsUnavailableNotNobody() {
        this.identity.expect(requestTo(BASE + "/people")).andRespond(withServerError());
        this.identity.expect(requestTo(BASE + "/workspaces")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        this.identity.expect(requestTo(BASE + "/seats")).andRespond(withServerError());

        assertThatThrownBy(() -> this.port.person(7L)).isInstanceOf(IdentityPort.Unavailable.class);
        assertThatThrownBy(() -> this.port.workspace(2901L)).isInstanceOf(IdentityPort.Unavailable.class);
        assertThatThrownBy(() -> this.port.seats(2901L)).isInstanceOf(IdentityPort.Unavailable.class);
    }

    @Test
    void aWorkspaceCodeNobodyHasIsEmpty() {
        this.identity.expect(requestTo(BASE + "/workspaceByCode")).andExpect(content().json("{\"code\":\"default\"}"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(this.port.workspaceByCode("default")).isEmpty();
    }

    @Test
    void membersAreAskedForByScope() {
        this.identity.expect(requestTo(BASE + "/members")).andExpect(content().json("{\"tenantId\":2901}"))
            .andRespond(withSuccess("[{\"appUserId\":7,\"tenantId\":2901,\"username\":\"d@a.example\",\"avatarBucket\":\"etl-avatar\","
                + "\"avatarKey\":\"2901/7.png\",\"status\":\"Inactive\"}]", MediaType.APPLICATION_JSON));
        this.identity.expect(requestTo(BASE + "/members")).andExpect(content().json("{\"allTenants\":true}"))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<IdentityPort.Person> one = this.port.members(TenantScope.tenant(2901L));
        assertThat(this.port.members(TenantScope.of(null, "PLATFORM_ADMIN", 1L))).isEmpty();

        assertThat(one).hasSize(1);
        assertThat(one.get(0).getAvatarKey()).isEqualTo("2901/7.png");
        assertThat(one.get(0).getStatus()).isEqualTo("Inactive");
        this.identity.verify();
    }

    @Test
    void seatsAreANumber() {
        this.identity.expect(requestTo(BASE + "/seats")).andExpect(content().json("{\"tenantId\":2901}"))
            .andRespond(withSuccess("{\"seats\":12}", MediaType.APPLICATION_JSON));

        assertThat(this.port.seats(2901L)).isEqualTo(12L);
    }

    // ---- the page gate ----------------------------------------------------------------------------

    private static final String GATED = "/sourceJob.json/listSourceJob";

    @Test
    void anAdministratorOrAnUngatedPathIsNeverAskedAbout() {
        assertThat(this.port.pageDecision("TENANT_ADMIN", 44L, GATED).isAllowed()).isTrue();
        assertThat(this.port.pageDecision("PLATFORM_ADMIN", 1L, GATED).isAllowed()).isTrue();
        assertThat(this.port.pageDecision("TENANT_USER", 44L, "/setting.json/fetchSettings").isAllowed()).isTrue();
        this.identity.verify();
    }

    @Test
    void aTenantUsersAnswerIsKeptForTheTtlAndAskedAgainAfter() {
        this.identity.expect(ExpectedCount.twice(), requestTo(BASE + "/pageDecision"))
            .andExpect(content().json("{\"userRole\":\"TENANT_USER\",\"appUserId\":44,\"path\":\"" + GATED + "\"}"))
            .andRespond(withSuccess("{\"allowed\":false,\"message\":\"Source Jobs is not part of your access. Ask your workspace admin.\"}",
                MediaType.APPLICATION_JSON));

        IdentityPort.PageDecision first = this.port.pageDecision("TENANT_USER", 44L, GATED);
        this.now.addAndGet(HttpIdentity.PAGE_TTL_MILLIS - 1);
        IdentityPort.PageDecision cached = this.port.pageDecision("TENANT_USER", 44L, GATED);
        this.now.addAndGet(1);
        IdentityPort.PageDecision asked = this.port.pageDecision("TENANT_USER", 44L, GATED);

        assertThat(first.isAllowed()).isFalse();
        assertThat(first.getMessage()).isEqualTo("Source Jobs is not part of your access. Ask your workspace admin.");
        assertThat(cached.getMessage()).isEqualTo(first.getMessage());
        assertThat(asked.isAllowed()).isFalse();
        this.identity.verify();
    }

    /** Page access that cannot be checked refuses, and the refusal is not remembered. */
    @Test
    void aGateThatCannotBeAskedRefusesAndAsksAgainNextTime() {
        this.identity.expect(requestTo(BASE + "/pageDecision")).andRespond(withServerError());
        this.identity.expect(requestTo(BASE + "/pageDecision"))
            .andRespond(withSuccess("{\"allowed\":true}", MediaType.APPLICATION_JSON));

        IdentityPort.PageDecision down = this.port.pageDecision("TENANT_USER", 44L, GATED);
        IdentityPort.PageDecision back = this.port.pageDecision("TENANT_USER", 44L, GATED);

        assertThat(down.isAllowed()).isFalse();
        assertThat(down.getMessage()).isEqualTo(HttpIdentity.PAGE_GATE_UNAVAILABLE);
        assertThat(back.isAllowed()).isTrue();
        this.identity.verify();
    }

    /** One person's answer is never another's. */
    @Test
    void theAnswerIsThePersons() {
        this.identity.expect(requestTo(BASE + "/pageDecision")).andExpect(content().json("{\"appUserId\":44}"))
            .andRespond(withSuccess("{\"allowed\":true}", MediaType.APPLICATION_JSON));
        this.identity.expect(requestTo(BASE + "/pageDecision")).andExpect(content().json("{\"appUserId\":45}"))
            .andRespond(withSuccess("{\"allowed\":false,\"message\":\"no\"}", MediaType.APPLICATION_JSON));

        assertThat(this.port.pageDecision("TENANT_USER", 44L, GATED).isAllowed()).isTrue();
        assertThat(this.port.pageDecision("TENANT_USER", 45L, GATED).isAllowed()).isFalse();
        this.identity.verify();
    }
}

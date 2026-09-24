package process.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import process.identity.TestIdentity;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.util.JwtUtil;

import javax.servlet.FilterChain;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * MIG-91: what a token says and how the filter turns it into a tenant context.
 *
 * The service map has no row for JwtUtil's claims or for JwtAuthenticationFilter populating
 * TenantContext, so the rules are read from the code and pinned here. platform-commons' JwtVerifier
 * reads the same claim names in every other service; renaming one here breaks them all.
 */
class JwtClaimsCharacterisationTest {

    private static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < KEY.length; i++) KEY[i] = (byte) (i * 7 + 3);
    }

    private JwtUtil jwtUtil;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        this.jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(this.jwtUtil, "base64Key", Base64.getEncoder().encodeToString(KEY));
        ReflectionTestUtils.setField(this.jwtUtil, "accessTokenExpiryMinutes", 30L);
        ReflectionTestUtils.setField(this.jwtUtil, "refreshTokenExpiryDays", 7L);
        this.filter = new JwtAuthenticationFilter(TestIdentity.authenticating(this.jwtUtil, mock(TokenRevocations.class)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private AppUser user(boolean owesPassword) {
        AppUser user = new AppUser();
        user.setAppUserId(1234L);
        user.setTenantId(1001L);
        user.setUsername("olivia@a.example");
        user.setUserRole(UserRole.TENANT_USER);
        user.setMustChangePassword(owesPassword);
        return user;
    }

    private Claims read(String token) {
        return Jwts.parserBuilder().setSigningKey(KEY).build().parseClaimsJws(token).getBody();
    }

    // -- the claims ---------------------------------------------------------------------

    @Test
    void anAccessTokenCarriesExactlyTheseClaims() {
        Claims claims = this.read(this.jwtUtil.generateAccessToken(this.user(false)));

        assertThat(claims.getSubject()).isEqualTo("olivia@a.example");
        assertThat(claims.get("appUserId", Number.class).longValue()).isEqualTo(1234L);
        assertThat(claims.get("tenantId", Number.class).longValue()).isEqualTo(1001L);
        assertThat(claims.get("userRole", String.class)).isEqualTo("TENANT_USER");
        assertThat(claims.get("type", String.class)).isEqualTo("access");
        // No debt, no claim: the flag is absent rather than false.
        assertThat(claims.containsKey("pwd")).isFalse();
        Map<String, Object> names = new TreeMap<>(claims);
        // jti and tokenVersion joined with MIG-14; every name that was here before is unchanged, and
        // platform-commons' JwtVerifier ignores the two it does not read.
        assertThat(names.keySet()).containsExactly("appUserId", "exp", "iat", "jti", "sub", "tenantId", "tokenVersion", "type", "userRole");
        assertThat(claims.get("tokenVersion", Number.class).intValue()).isZero();
        assertThat((claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000).isEqualTo(30 * 60);
    }

    @Test
    void aRefreshTokenIsTheSameClaimsForSevenDays() {
        Claims claims = this.read(this.jwtUtil.generateRefreshToken(this.user(true)));

        assertThat(claims.get("type", String.class)).isEqualTo("refresh");
        assertThat(claims.get("pwd", Boolean.class)).isTrue();
        assertThat((claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000).isEqualTo(7 * 24 * 60 * 60);
    }

    @Test
    void aPlatformAdminsTokenCarriesNoTenant() {
        AppUser admin = this.user(false);
        admin.setTenantId(null);
        admin.setUserRole(UserRole.PLATFORM_ADMIN);

        Claims claims = this.read(this.jwtUtil.generateAccessToken(admin));

        assertThat(claims.containsKey("tenantId")).isFalse();
        assertThat(this.jwtUtil.tenantIdOf(claims)).isNull();
    }

    // -- the filter ---------------------------------------------------------------------

    private static final class Seen {
        Long tenantId;
        String role;
        Long appUserId;
        String username;
        String authorities;
    }

    private MockHttpServletResponse run(String uri, String bearer, AtomicReference<Seen> seen) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        if (bearer != null) {
            request.addHeader("Authorization", "Bearer " + bearer);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            Seen s = new Seen();
            s.tenantId = TenantContext.getTenantId();
            s.role = TenantContext.getUserRole();
            s.appUserId = TenantContext.getAppUserId();
            s.username = TenantContext.getUsername();
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            s.authorities = auth == null ? null : auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.joining(","));
            seen.set(s);
        };
        this.filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void anAccessTokenFillsTheTenantContextForTheRequestAndOnlyForIt() throws Exception {
        AtomicReference<Seen> seen = new AtomicReference<>();

        this.run("/api/v1/sourceJob.json/listSourceJob", this.jwtUtil.generateAccessToken(this.user(false)), seen);

        assertThat(seen.get().tenantId).isEqualTo(1001L);
        assertThat(seen.get().role).isEqualTo("TENANT_USER");
        assertThat(seen.get().appUserId).isEqualTo(1234L);
        assertThat(seen.get().username).isEqualTo("olivia@a.example");
        assertThat(seen.get().authorities).isEqualTo("ROLE_TENANT_USER");
        assertThat(TenantContext.getTenantId()).as("cleared after the chain").isNull();
        assertThat(TenantContext.getAppUserId()).isNull();
    }

    /** A refresh token is a good signature and no session: the request goes on unauthenticated. */
    @Test
    void aRefreshTokenPresentedAsBearerAuthenticatesNobody() throws Exception {
        AtomicReference<Seen> seen = new AtomicReference<>();

        this.run("/api/v1/sourceJob.json/listSourceJob", this.jwtUtil.generateRefreshToken(this.user(false)), seen);

        assertThat(seen.get().authorities).isNull();
        assertThat(seen.get().appUserId).isNull();
    }

    @Test
    void anUnreadableOrForeignTokenAuthenticatesNobodyAndIsNotAnError() throws Exception {
        AtomicReference<Seen> seen = new AtomicReference<>();
        MockHttpServletResponse garbage = this.run("/api/v1/x", "not.a.token", seen);
        assertThat(seen.get().authorities).isNull();
        assertThat(garbage.getStatus()).isEqualTo(200);

        JwtUtil other = new JwtUtil();
        byte[] otherKey = new byte[32];
        ReflectionTestUtils.setField(other, "base64Key", Base64.getEncoder().encodeToString(otherKey));
        ReflectionTestUtils.setField(other, "accessTokenExpiryMinutes", 30L);
        this.run("/api/v1/x", other.generateAccessToken(this.user(false)), seen);
        assertThat(seen.get().authorities).isNull();
    }

    @Test
    void aTokenOwingAPasswordReachesOnlyTheChangeItselfAndIsToldSoInTheEnvelope() throws Exception {
        String token = this.jwtUtil.generateAccessToken(this.user(true));
        AtomicReference<Seen> seen = new AtomicReference<>();

        MockHttpServletResponse refused = this.run("/api/v1/sourceJob.json/listSourceJob", token, seen);

        assertThat(seen.get()).as("the chain never ran").isNull();
        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(refused.getContentAsString())
            .isEqualTo("{\"status\":\"ERROR\",\"message\":\"Change your temporary password before using anything else.\"}");

        for (String open : new String[] {"/api/v1/appUser.json/changeOwnPassword", "/api/v1/appUser.json/me",
                "/api/v1/appUser.json/avatar", "/api/v1/auth.json/refresh"}) {
            seen.set(null);
            assertThat(this.run(open, token, seen).getStatus()).as(open).isEqualTo(200);
            assertThat(seen.get().appUserId).as(open).isEqualTo(1234L);
        }
    }

    /** The chain is still run, and the context still cleared, when nothing was presented at all. */
    @Test
    void noTokenIsAnAnonymousRequest() throws Exception {
        AtomicReference<Seen> seen = new AtomicReference<>();
        MockHttpServletResponse response = this.run("/api/v1/auth.json/login", null, seen);
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.get().authorities).isNull();
    }
}

package process.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.barco.platform.correlation.CorrelationId;
import org.barco.platform.tenancy.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import process.identity.TestIdentity;
import process.util.JwtUtil;

import javax.crypto.spec.SecretKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * MIG-93, end to end through process's own filter: the TenantScope a request resolves to, from the token
 * it carried. A missing or unparseable tenant claim is Scoped(NO_TENANT_MATCHES) -- never AllTenants --
 * and every AllTenants resolution is written to the audit log once per request, with its correlation id.
 */
class TenantScopeResolutionTest {

    private static final byte[] KEY = new byte[32];

    static {
        for (int i = 0; i < KEY.length; i++) KEY[i] = (byte) (i * 5 + 11);
    }

    private JwtAuthenticationFilter filter;
    private ListAppender<ILoggingEvent> audit;

    @BeforeEach
    void setUp() {
        JwtUtil jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "base64Key", Base64.getEncoder().encodeToString(KEY));
        this.filter = new JwtAuthenticationFilter(TestIdentity.authenticating(jwt, mock(TokenRevocations.class)));
        this.audit = new ListAppender<>();
        this.audit.start();
        ((Logger) LoggerFactory.getLogger("org.barco.platform.audit.CrossTenant")).addAppender(this.audit);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger("org.barco.platform.audit.CrossTenant")).detachAppender(this.audit);
        CorrelationId.clear();
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    private static String token(Object tenantId, String role) {
        Date now = new Date();
        return Jwts.builder().setSubject("someone@example.com").claim("appUserId", 44).claim("tenantId", tenantId)
            .claim("userRole", role).claim("type", "access").setIssuedAt(now).setExpiration(new Date(now.getTime() + 60_000))
            .signWith(new SecretKeySpec(KEY, "HmacSHA256"), SignatureAlgorithm.HS256).compact();
    }

    /** The scopes a request resolves to, as often as its handler asks. */
    private List<TenantScope> scopesOf(String token, int asks) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/sourceJob.json/listSourceJob");
        request.addHeader("Authorization", "Bearer " + token);
        List<TenantScope> seen = new ArrayList<>();
        this.filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            for (int i = 0; i < asks; i++) seen.add(TenantContext.scope());
        });
        return seen;
    }

    @Test
    void aMissingTenantClaimIsScopedToNothing() throws Exception {
        assertThat(this.scopesOf(token(null, "TENANT_ADMIN"), 1)).containsExactly(TenantScope.none());
    }

    /** A tenant claim that is not a number reads as no tenant -- nothing -- and never as every tenant. */
    @Test
    void anUnparseableTenantClaimIsNeverAllTenants() throws Exception {
        List<TenantScope> scopes = this.scopesOf(token("1001", "TENANT_ADMIN"), 1);
        assertThat(scopes).allSatisfy(scope -> assertThat(scope.isAllTenants()).isFalse());
        List<TenantScope> nonsense = this.scopesOf(token("everything", "TENANT_ADMIN"), 1);
        assertThat(nonsense).allSatisfy(scope -> assertThat(scope).isEqualTo(TenantScope.none()));
    }

    /** A context set again without being cleared -- a pooled thread, a job context -- resolves afresh. */
    @Test
    void aScopeIsResolvedAgainWheneverTheCallerIsSetAgain() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@example.com");
        assertThat(TenantContext.scope().isAllTenants()).isTrue();

        TenantContext.set(1001L, "TENANT_USER", 44L, "o@a.example");

        assertThat(TenantContext.scope()).isEqualTo(TenantScope.tenant(1001L));
    }

    @Test
    void theOldAllTenantsSentinelZeroIsNothing() throws Exception {
        assertThat(this.scopesOf(token(0, "TENANT_ADMIN"), 1)).containsExactly(TenantScope.none());
    }

    @Test
    void aPlatformAdminsGrantIsAuditedOncePerRequestWithItsCorrelationId() throws Exception {
        CorrelationId.set("req-scope-91");

        List<TenantScope> scopes = this.scopesOf(token(null, "PLATFORM_ADMIN"), 5);

        assertThat(scopes).hasSize(5).allSatisfy(scope -> assertThat(scope.isAllTenants()).isTrue());
        assertThat(this.audit.list).hasSize(1);
        assertThat(this.audit.list.get(0).getFormattedMessage()).contains("req-scope-91").contains("user 44");
    }
}

package process.security;

import org.barco.platform.security.CallerIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import process.identity.IdentityPort;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-332: under /customer (the gateway's /v1) only an API client's token is a caller -- its workspace, role API_CLIENT,
 * no person, its scopes -- and anything else is a 401 problem; a client's token anywhere else is nobody.
 */
class CustomerApiAuthenticationTest {

    private final IdentityPort identity = mock(IdentityPort.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(this.identity);
    private final CallerIdentity client = CallerIdentity.apiClient("cl_acme", 2946L, new LinkedHashSet<>(Arrays.asList("runs:write")),
        "jti", 0, "SELF");
    private final CallerIdentity person = new CallerIdentity(4602L, 2946L, "TENANT_ADMIN", "emily", false);

    static final class Seen extends HttpServlet {
        final AtomicReference<String> seen = new AtomicReference<>();

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) {
            this.seen.set(TenantContext.getTenantId() + " " + TenantContext.getUserRole() + " " + TenantContext.getAppUserId() + " "
                + TenantContext.getClientId() + " " + TenantContext.hasScope("runs:write") + " " + TenantContext.hasScope("runs:read"));
        }
    }

    private MockHttpServletResponse call(String path, String token, Seen seen) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1" + path);
        request.setContextPath("/api/v1");
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        this.filter.doFilter(request, response, new MockFilterChain(seen));
        return response;
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aClientTokenIsTheCallerOfTheCustomerApi() throws Exception {
        when(this.identity.authenticateClient("client-token")).thenReturn(Optional.of(this.client));
        Seen seen = new Seen();

        assertThat(this.call("/customer/pipelines/42/runs", "client-token", seen).getStatus()).isEqualTo(200);
        assertThat(seen.seen.get()).isEqualTo("2946 API_CLIENT null cl_acme true false");
        assertThat(TenantContext.getClientId()).as("nothing left on the thread").isNull();
    }

    @Test
    void anythingElseOnTheCustomerApiIsA401Problem() throws Exception {
        when(this.identity.authenticate("person-token")).thenReturn(Optional.of(this.person));
        when(this.identity.authenticateClient("person-token")).thenReturn(Optional.empty());
        for (String token : new String[] {"person-token", null}) {
            Seen seen = new Seen();
            MockHttpServletResponse response = this.call("/customer/events", token, seen);

            assertThat(seen.seen.get()).isNull();
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentType()).startsWith("application/problem+json");
            assertThat(response.getContentAsString()).contains("\"instance\":\"/v1/events\"");
        }
    }

    @Test
    void aClientTokenElsewhereIsNobody() throws Exception {
        when(this.identity.authenticate("client-token")).thenReturn(Optional.empty());
        when(this.identity.authenticateClient("client-token")).thenReturn(Optional.of(this.client));
        Seen seen = new Seen();

        this.call("/sourceJob.json/runSourceJob", "client-token", seen);

        assertThat(seen.seen.get()).as("anonymous: Spring Security refuses it wherever a login is needed")
            .isEqualTo("null null null null false false");
    }

    @Test
    void aFilesSignedLinkHasNoCallerAndNeedsNoToken() throws Exception {
        Seen seen = new Seen();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/customer/files/01JRESULT00000000000000000/content");
        request.setContextPath("/api/v1");
        request.setQueryString("token=abc.def");
        MockHttpServletResponse response = new MockHttpServletResponse();
        this.filter.doFilter(request, response, new MockFilterChain(seen));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.seen.get()).as("no caller: the link's token is checked by FileLinks").isEqualTo("null null null null false false");
        assertThat(JwtAuthenticationFilter.isSignedLink("GET", "/customer/files/01JRESULT00000000000000000/content")).isTrue();
        assertThat(JwtAuthenticationFilter.isSignedLink("POST", "/customer/files/01JRESULT00000000000000000/content")).isFalse();
        assertThat(JwtAuthenticationFilter.isSignedLink("GET", "/customer/files/01JRESULT00000000000000000")).isFalse();
        assertThat(JwtAuthenticationFilter.isSignedLink("GET", "/customer/files/x/../../runs/content")).isFalse();
        // The file itself (its link and facts) still needs an API client's token.
        assertThat(this.call("/customer/files/01JRESULT00000000000000000/meta", null, new Seen()).getStatus()).isEqualTo(401);
    }
}

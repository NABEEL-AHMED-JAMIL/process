package process.config;

import org.barco.platform.security.CallerIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import process.identity.IdentityPort;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Risk register #6 (2026-10-12): Core's CORS is CorsConfig, through the real SecurityConfig's filter chain -- the configured
 * console origin only, answered ahead of authentication, so a 401 and a 403 carry Access-Control-Allow-Origin and the
 * console can read them (an expired token is refreshed; a refusal shows its message). The controllers carry no annotation.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {CorsPolicyTest.Web.class, SecurityConfig.class, CorsConfig.class})
@TestPropertySource(properties = "process.cors.allowed-origins=http://localhost:4400, http://console.example/")
class CorsPolicyTest {

    private static final String CONSOLE = "http://localhost:4400";

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import(Stubs.class)
    static class Web {
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        IdentityPort identity() {
            IdentityPort identity = Mockito.mock(IdentityPort.class);
            Mockito.when(identity.authenticate(Mockito.anyString())).thenReturn(Optional.empty());
            Mockito.when(identity.authenticate("workspace-user")).thenReturn(Optional.of(
                new CallerIdentity(3L, 7L, "TENANT_USER", "member", false)));
            return identity;
        }
    }

    /** Stand-ins for a console controller, a download, an internal call and the embeddable run view -- none annotated. */
    @RestController
    static class Stubs {
        @GetMapping("/sourceJob.json/fetchAll")
        String list() {
            return "{}";
        }

        @GetMapping("/sourceJob.json/download")
        String download(HttpServletResponse response) {
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"runs.csv\"");
            return "a,b";
        }

        @PostMapping("/internal/tenants/facts")
        String internal() {
            return "{}";
        }

        @GetMapping("/customer/embed/runs/{token}")
        String embed() {
            return "{}";
        }
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        this.mvc = MockMvcBuilders.webAppContextSetup(this.context)
            .addFilters(this.context.getBean("springSecurityFilterChain", Filter.class))
            .build();
    }

    @Test
    void aPreflightFromTheConsoleIsAnsweredWithoutAToken() throws Exception {
        MockHttpServletResponse answer = this.mvc.perform(options("/sourceJob.json/fetchAll").header(HttpHeaders.ORIGIN, CONSOLE)
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")).andReturn().getResponse();
        assertThat(answer.getStatus()).isEqualTo(200);
        assertThat(answer.getHeaders(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).containsExactly(CONSOLE);
        assertThat(answer.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS)).contains("POST");
        assertThat(answer.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull();
    }

    @Test
    void anExpiredTokensRefusalCarriesTheHeaderSoTheConsoleRefreshes() throws Exception {
        MockHttpServletResponse answer = this.mvc.perform(get("/sourceJob.json/fetchAll").header(HttpHeaders.ORIGIN, CONSOLE)
            .header(HttpHeaders.AUTHORIZATION, "Bearer x.y.z")).andReturn().getResponse();
        assertThat(answer.getStatus()).isEqualTo(401);
        assertThat(answer.getHeaders(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).containsExactly(CONSOLE);
    }

    @Test
    void aForbiddenAnswerCarriesTheHeaderToo() throws Exception {
        MockHttpServletResponse answer = this.mvc.perform(get("/actuator/metrics").header(HttpHeaders.ORIGIN, CONSOLE)
            .header(HttpHeaders.AUTHORIZATION, "Bearer workspace-user")).andReturn().getResponse();
        assertThat(answer.getStatus()).isEqualTo(403);
        assertThat(answer.getHeaders(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).containsExactly(CONSOLE);
    }

    @Test
    void anAnsweredCallCarriesExactlyOneHeaderAndExposesTheDownloadsName() throws Exception {
        MockHttpServletResponse answer = this.mvc.perform(get("/sourceJob.json/download").header(HttpHeaders.ORIGIN, "http://console.example")
            .header(HttpHeaders.AUTHORIZATION, "Bearer workspace-user")).andReturn().getResponse();
        assertThat(answer.getStatus()).isEqualTo(200);
        assertThat(answer.getHeaders(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).containsExactly("http://console.example");
        assertThat(answer.getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS)).contains(HttpHeaders.CONTENT_DISPOSITION);
    }

    @Test
    void anotherOriginIsRefusedAndNeverEchoed() throws Exception {
        MockHttpServletResponse answer = this.mvc.perform(get("/sourceJob.json/fetchAll").header(HttpHeaders.ORIGIN, "https://evil.example")
            .header(HttpHeaders.AUTHORIZATION, "Bearer workspace-user")).andReturn().getResponse();
        assertThat(answer.getStatus()).isEqualTo(403);
        assertThat(answer.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
        MockHttpServletResponse preflight = this.mvc.perform(options("/sourceJob.json/fetchAll").header(HttpHeaders.ORIGIN, "https://evil.example")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")).andReturn().getResponse();
        assertThat(preflight.getStatus()).isEqualTo(403);
        assertThat(preflight.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
    }

    @Test
    void serviceToServicePathsAnswerNoCorsAndTheEmbeddedRunViewDoes() throws Exception {
        MockHttpServletResponse internal = this.mvc.perform(post("/internal/tenants/facts").header(HttpHeaders.ORIGIN, CONSOLE))
            .andReturn().getResponse();
        assertThat(internal.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
        assertThat(CorsConfig.covered("/runConfig.json/resolve")).isFalse();
        assertThat(CorsConfig.covered("/customer/runs/5")).isFalse();
        assertThat(CorsConfig.covered("/customer/embed/runs/abc")).isTrue();
        assertThat(CorsConfig.covered("/sourceJob.json/review/bulkDecide")).isTrue();
    }

    @Test
    void noOriginConfiguredLetsNoBrowserIn() {
        assertThat(CorsConfig.console("").getAllowedOrigins()).isEmpty();
        assertThat(CorsConfig.console(null).checkOrigin(CONSOLE)).isNull();
        assertThat(CorsConfig.console(" http://localhost:4400/ ,").getAllowedOrigins()).containsExactly(CONSOLE);
    }
}

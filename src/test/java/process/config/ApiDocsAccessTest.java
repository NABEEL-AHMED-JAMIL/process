package process.config;

import org.barco.platform.security.CallerIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import process.identity.IdentityPort;

import javax.servlet.Filter;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The API documentation is a map of every endpoint, its parameters and its models, so it is for a
 * platform admin only (owner, 2026-09-24): no one signed out and no workspace user may read it.
 * It used to be open to anyone who asked, through the gateway included.
 *
 * The real SecurityConfig in a minimal web context; the documentation endpoints are stubs, so this
 * checks who is let through, not what springfox writes (ApiDocsSettingTest does that).
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {ApiDocsAccessTest.Web.class, SecurityConfig.class})
class ApiDocsAccessTest {

    private static final String[] DOCS = {
        "/v2/api-docs", "/swagger-ui.html", "/swagger-resources", "/swagger-resources/configuration/ui",
        "/swagger-ui/index.html", "/webjars/springfox-swagger-ui/springfox.js"
    };

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class Web {
        @Bean
        IdentityPort identity() {
            IdentityPort identity = Mockito.mock(IdentityPort.class);
            Mockito.when(identity.authenticate(Mockito.anyString())).thenReturn(Optional.empty());
            Mockito.when(identity.authenticate("platform-admin")).thenReturn(Optional.of(
                new CallerIdentity(1L, 1L, "PLATFORM_ADMIN", "root", false)));
            Mockito.when(identity.authenticate("workspace-admin")).thenReturn(Optional.of(
                new CallerIdentity(2L, 7L, "TENANT_ADMIN", "owner", false)));
            Mockito.when(identity.authenticate("workspace-user")).thenReturn(Optional.of(
                new CallerIdentity(3L, 7L, "TENANT_USER", "member", false)));
            return identity;
        }

        @Bean
        Docs docs() {
            return new Docs();
        }
    }

    @RestController
    static class Docs {
        @GetMapping({"/v2/api-docs", "/swagger-ui.html", "/swagger-resources", "/swagger-resources/configuration/ui",
            "/swagger-ui/index.html", "/webjars/springfox-swagger-ui/springfox.js"})
        String docs() {
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
    void noOneSignedOutReadsTheDocs() throws Exception {
        for (String path : DOCS) {
            this.mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void anUnreadableTokenIsTheSameAsNone() throws Exception {
        for (String path : DOCS) {
            this.mvc.perform(get(path).header("Authorization", "Bearer forged")).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void aWorkspaceAdminOrUserIsRefused() throws Exception {
        for (String token : new String[]{"workspace-admin", "workspace-user"}) {
            for (String path : DOCS) {
                this.mvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
            }
        }
    }

    @Test
    void aPlatformAdminReadsThem() throws Exception {
        for (String path : DOCS) {
            this.mvc.perform(get(path).header("Authorization", "Bearer platform-admin")).andExpect(status().isOk());
        }
    }
}

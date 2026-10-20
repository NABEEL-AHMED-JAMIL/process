package process.config;

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

import jakarta.servlet.Filter;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Vulnerabilities review 2026-10-13, C9: SecurityConfig let anyone reach /ws/**. The browser's STOMP endpoint left Core
 * for Notifications (MIG-22; the gateway routes "ws" there), so the rule guarded nothing, and any controller later
 * added under /ws would have been public. The rule is gone: a path there needs a token like any other.
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {NoOpenWebSocketPathTest.Web.class, SecurityConfig.class})
class NoOpenWebSocketPathTest {

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class Web {
        @Bean
        IdentityPort identity() {
            IdentityPort identity = Mockito.mock(IdentityPort.class);
            Mockito.when(identity.authenticate(Mockito.anyString())).thenReturn(Optional.empty());
            return identity;
        }

        @Bean
        Later later() {
            return new Later();
        }
    }

    /** A controller someone might add under /ws one day. */
    @RestController
    static class Later {
        @GetMapping({"/ws/info", "/ws/anything/else"})
        String answer() {
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
    void aPathUnderWsNeedsAToken() throws Exception {
        this.mvc.perform(get("/ws/info")).andExpect(status().isUnauthorized());
        this.mvc.perform(get("/ws/anything/else")).andExpect(status().isUnauthorized());
    }
}

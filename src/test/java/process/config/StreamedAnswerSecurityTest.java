package process.config;

import org.barco.platform.security.CallerIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import process.identity.IdentityPort;

import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A streamed answer (StreamingResponseBody: runDataset, a file's signed link) ends with an ASYNC dispatch. Spring
 * Security 6+ authorizes every dispatch, and JwtAuthenticationFilter (once per request) does not run again for it, so
 * the ASYNC dispatch was refused after the file had been written: the chunked answer was never ended (curl exit 18,
 * api-check "no answer", live after the Boot 4 move, MIG-389). The request itself was authorized; its ASYNC dispatch
 * only finishes it, as on Boot 2.7. A caller without a token is still refused before anything is written.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = {StreamedAnswerSecurityTest.Web.class, SecurityConfig.class})
class StreamedAnswerSecurityTest {

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    static class Web {
        @Bean
        IdentityPort identity() {
            IdentityPort identity = Mockito.mock(IdentityPort.class);
            Mockito.when(identity.authenticate(Mockito.anyString())).thenReturn(Optional.empty());
            Mockito.when(identity.authenticate("workspace-user")).thenReturn(Optional.of(
                new CallerIdentity(3L, 7L, "TENANT_USER", "member", false)));
            return identity;
        }

        @Bean
        Files files() {
            return new Files();
        }
    }

    @RestController
    static class Files {
        @GetMapping("/sourceJob.json/runDataset")
        ResponseEntity<StreamingResponseBody> file() {
            return ResponseEntity.ok().body(out -> out.write("a,b\n1,2\n".getBytes(StandardCharsets.UTF_8)));
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
    void aSignedInCallersStreamedFileIsFinished() throws Exception {
        MvcResult started = this.mvc.perform(get("/sourceJob.json/runDataset").header("Authorization", "Bearer workspace-user"))
            .andExpect(request().asyncStarted())
            .andReturn();
        this.mvc.perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andExpect(content().string("a,b\n1,2\n"));
    }

    @Test
    void noOneSignedOutStartsOne() throws Exception {
        this.mvc.perform(get("/sourceJob.json/runDataset")).andExpect(status().isUnauthorized());
    }
}

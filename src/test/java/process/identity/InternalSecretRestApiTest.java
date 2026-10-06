package process.identity;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Where Notifications redeems a secretRef (MIG-22 part 4). Service to service only: the caller
 * proves itself with the internal token, and the gateway refuses the path from outside.
 */
class InternalSecretRestApiTest {

    private static final String TOKEN = "internal-3f1d9a52-8b1e";

    private final OneTimeSecrets secrets = mock(OneTimeSecrets.class);

    private MockMvc api(String configuredToken) {
        return MockMvcBuilders.standaloneSetup(new InternalSecretRestApi(this.secrets, configuredToken)).build();
    }

    @Test
    void theRightTokenRedeemsTheSecretOnce() throws Exception {
        when(this.secrets.redeem("ref-1")).thenReturn(Optional.of("Tmp-9f2c!"));

        this.api(TOKEN).perform(post("/internal/secretRef/ref-1/redeem").header("X-Internal-Token", TOKEN))
            .andExpect(status().isOk()).andExpect(jsonPath("$.secret").value("Tmp-9f2c!"));
    }

    @Test
    void aSpentOrUnknownReferenceIsNotFound() throws Exception {
        when(this.secrets.redeem("ref-2")).thenReturn(Optional.empty());

        this.api(TOKEN).perform(post("/internal/secretRef/ref-2/redeem").header("X-Internal-Token", TOKEN))
            .andExpect(status().isNotFound());
    }

    /** A wrong or missing token must not even spend the reference. */
    @Test
    void withoutTheTokenNothingIsRedeemed() throws Exception {
        this.api(TOKEN).perform(post("/internal/secretRef/ref-1/redeem")).andExpect(status().isUnauthorized());
        this.api(TOKEN).perform(post("/internal/secretRef/ref-1/redeem").header("X-Internal-Token", "guess"))
            .andExpect(status().isUnauthorized());
        verify(this.secrets, never()).redeem(anyString());
    }

    /** Not configured means closed, not open to anyone who sends an empty header. */
    @Test
    void withNoTokenConfiguredTheEndpointIsShut() throws Exception {
        this.api("").perform(post("/internal/secretRef/ref-1/redeem").header("X-Internal-Token", ""))
            .andExpect(status().isUnauthorized());
        verify(this.secrets, never()).redeem(anyString());
    }
}

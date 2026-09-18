package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.ai.AiEndpointPolicy;
import process.ai.AiProviderGateway;
import process.model.dto.AiAgentRuntimeConfigDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.AiModelConnection;
import process.model.pojo.AiPrompt;
import process.security.TenantContext;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The agent surface the file chat and the job assistant still speak, answered from prompts
 * and connections. Two things it must keep doing: a tool uuid from another workspace reads
 * as not found, and the prompt's own instructions reach the runtime config.
 */
@ExtendWith(MockitoExtension.class)
public class AiAgentAliasTest {

    private static final long MINE = 1000L, THEIRS = 2000L;

    @Mock private AiPromptServiceImpl prompts;
    @Mock private AiModelConnectionServiceImpl connections;
    @Mock private AiProviderGateway gateway;
    @Mock private AiEndpointPolicy endpointPolicy;

    private AiAgentServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new AiAgentServiceImpl(this.prompts, this.connections, this.gateway, this.endpointPolicy);
        TenantContext.set(MINE, "TENANT_ADMIN", 5000L, "user@example.com");
    }

    @AfterEach
    void clear() { TenantContext.clear(); }

    private static AiPrompt prompt(String instructions) {
        AiPrompt p = new AiPrompt();
        p.setPromptId(2000L); p.setPromptUuid("uuid-1"); p.setTenantId(MINE); p.setName("Contract review");
        p.setSystemInstructions(instructions); p.setUserTemplate("{{text}}"); p.setOutputMode("text"); p.setStatus(Status.Active); p.setConnectionId(7L);
        return p;
    }

    private static AiModelConnection connection() {
        AiModelConnection c = new AiModelConnection();
        c.setConnectionId(7L); c.setTenantId(MINE); c.setProvider("OpenAI"); c.setDefaultModel("gpt-4o-mini"); c.setApiKey("enc");
        return c;
    }

    @Test
    void anotherWorkspacesToolUuidReadsAsNotFound() throws Exception {
        // The prompt service's scoped lookup is what refuses; the alias must go through it.
        when(this.prompts.scopedFindByUuid("uuid-theirs")).thenReturn(Optional.empty());
        ResponseDto answer = this.service.fetchToolByUuid("uuid-theirs");
        assertThat(answer.getStatus()).isEqualTo("ERROR");
        assertThat(answer.getMessage()).isEqualTo("Tool not found or not active.");
    }

    @Test
    void thePromptsOwnInstructionsReachTheRuntimeConfig() throws Exception {
        String instructions = "You are a contract-review assistant. Flag any clause imposing automatic renewal.";
        AiModelConnection c = connection();
        when(this.prompts.scopedFind(2000L)).thenReturn(Optional.of(prompt(instructions)));
        when(this.connections.scopedFind(7L)).thenReturn(Optional.of(c));
        when(this.connections.keyOf(c)).thenReturn("sk-plain");

        ResponseDto answer = this.service.resolveRuntimeConfig(2000L);

        assertThat(answer.getStatus()).isEqualTo("SUCCESS");
        AiAgentRuntimeConfigDto config = (AiAgentRuntimeConfigDto) answer.getData();
        assertThat(config.getInstructions()).isEqualTo(instructions);
        assertThat(config.getModel()).isEqualTo("gpt-4o-mini");
        assertThat(config.getProvider()).isEqualTo("OpenAI");
    }

    @Test
    void aNullInstructionsColumnComesThroughAsNullNotAnException() throws Exception {
        AiModelConnection c = connection();
        when(this.prompts.scopedFind(2000L)).thenReturn(Optional.of(prompt(null)));
        when(this.connections.scopedFind(7L)).thenReturn(Optional.of(c));
        when(this.connections.keyOf(c)).thenReturn("sk-plain");
        ResponseDto answer = this.service.resolveRuntimeConfig(2000L);
        assertThat(answer.getStatus()).isEqualTo("SUCCESS");
        assertThat(((AiAgentRuntimeConfigDto) answer.getData()).getInstructions()).isNull();
    }
}

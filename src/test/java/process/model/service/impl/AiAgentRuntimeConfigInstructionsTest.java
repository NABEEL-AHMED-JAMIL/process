package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.dto.AiAgentRuntimeConfigDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.pojo.AiAgent;
import process.model.repository.AiAgentRepository;
import process.model.repository.TenantRepository;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.EncryptionUtil;
import process.util.UserNameResolver;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * resolveRuntimeConfig is the ONLY path that reaches from a stored AiAgent into what a request
 * actually sends to the model. Its DTO used to stop at provider/model/key/endpoint -- built
 * before the agent table grew an instructions column, and never extended when it did -- so
 * every caller of it (FileChatServiceImpl chief among them) had no way to see an agent's own
 * configured instructions no matter what an admin typed into that field. This asserts the one
 * fact that closes it: the field is now on the wire.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class AiAgentRuntimeConfigInstructionsTest {

    @Mock
    private AiAgentRepository aiAgentRepository;

    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private EncryptionUtil encryptionUtil;
    @Mock
    private TenantFilterHelper tenantFilterHelper;
    @Mock
    private UserNameResolver userNameResolver;

    private AiAgentServiceImpl service;

    private AiAgentServiceImpl freshService() {
        AiAgentServiceImpl s = new AiAgentServiceImpl(this.aiAgentRepository, this.tenantRepository, this.encryptionUtil,
            this.tenantFilterHelper, this.userNameResolver);
        lenient().doNothing().when(this.tenantFilterHelper).enableIfNeeded(any());
        return s;
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private AiAgent agentWith(String instructions) {
        AiAgent agent = new AiAgent();
        agent.setAiAgentId(2000L);
        agent.setTenantId(1000L);
        agent.setProvider("OpenAI");
        agent.setModel("gpt-4o-mini");
        agent.setInstructions(instructions);
        agent.setStatus(Status.Active);
        return agent;
    }

    @Test
    void theAgentsOwnInstructionsReachTheRuntimeConfig() throws Exception {
        this.service = this.freshService();
        TenantContext.set(1000L, "TENANT_ADMIN", 5000L, "user");
        String instructions = "You are a contract-review assistant. Flag any clause imposing "
            + "automatic renewal, and always cite the section number.";
        when(this.aiAgentRepository.findById(2000L)).thenReturn(Optional.of(this.agentWith(instructions)));

        ResponseDto response = this.service.resolveRuntimeConfig(2000L);

        assertThat(response.getStatus()).isEqualTo(process.util.ProcessUtil.SUCCESS);
        AiAgentRuntimeConfigDto config = (AiAgentRuntimeConfigDto) response.getData();
        assertThat(config.getInstructions())
            .as("this is the exact defect: the DTO used to carry provider/model/key/endpoint and "
                + "silently drop instructions, so no caller could ever apply what an admin typed "
                + "into the agent's own configuration")
            .isEqualTo(instructions);
    }

    @Test
    void aNullInstructionsColumnComesThroughAsNullNotAnException() throws Exception {
        this.service = this.freshService();
        TenantContext.set(1000L, "TENANT_ADMIN", 5000L, "user");
        when(this.aiAgentRepository.findById(2000L)).thenReturn(Optional.of(this.agentWith(null)));

        ResponseDto response = this.service.resolveRuntimeConfig(2000L);

        assertThat(response.getStatus()).isEqualTo(process.util.ProcessUtil.SUCCESS);
        AiAgentRuntimeConfigDto config = (AiAgentRuntimeConfigDto) response.getData();
        assertThat(config.getInstructions()).isNull();
    }
}

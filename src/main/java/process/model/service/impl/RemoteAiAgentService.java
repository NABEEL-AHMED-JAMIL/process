package process.model.service.impl;

import org.springframework.stereotype.Service;
import process.ai.AiPort;
import process.model.dto.AdHocPromptRequestDto;
import process.model.dto.ResponseDto;
import process.model.service.AiAgentService;

import static process.util.ProcessUtil.ERROR;

/**
 * AiAgentService over the AI service (ADR-020). An agent's key never reaches Core: the runtime
 * configuration comes back without it, and an ad-hoc call names the agent and lets the AI service
 * use the key it holds.
 */
@Service
public class RemoteAiAgentService implements AiAgentService {

    private final AiPort ai;

    public RemoteAiAgentService(AiPort ai) {
        this.ai = ai;
    }

    @Override
    public ResponseDto resolveRuntimeConfig(Long aiAgentId) throws Exception {
        return this.ai.runtimeConfig(aiAgentId);
    }

    @Override
    public ResponseDto processAdHoc(AdHocPromptRequestDto dto) throws Exception {
        if (dto == null || dto.getAiAgentId() == null) {
            return new ResponseDto(ERROR, "An ad-hoc answer needs the agent to ask.");
        }
        return this.ai.adHoc(dto.getAiAgentId(), dto.getInstructions(), dto.getText(), dto.getJsonMode());
    }
}

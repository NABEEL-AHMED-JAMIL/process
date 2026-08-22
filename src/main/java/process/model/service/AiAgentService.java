package process.model.service;

import process.model.dto.AdHocPromptRequestDto;
import process.model.dto.AiAgentDto;
import process.model.dto.ResponseDto;

public interface AiAgentService {

    public ResponseDto addAgent(AiAgentDto aiAgentDto) throws Exception;

    public ResponseDto updateAgent(AiAgentDto aiAgentDto) throws Exception;

    public ResponseDto deleteAgent(Long aiAgentId) throws Exception;

    public ResponseDto fetchAllAgents() throws Exception;

    public ResponseDto fetchAgentByAgentId(Long aiAgentId) throws Exception;

    public ResponseDto fetchToolByUuid(String toolUuid) throws Exception;

    public ResponseDto processAdHoc(AdHocPromptRequestDto adHocPromptRequestDto) throws Exception;

    // Looks up one agent (checking ownership/status like every other agent lookup here) and
    // returns its provider/model/apiEndpoint plus the DECRYPTED api key, for another service
    // (FileChatServiceImpl) to call the provider directly with -- distinct from
    // fetchAgentByAgentId, whose AiAgentDto response only ever reports apiKeyConfigured.
    public ResponseDto resolveRuntimeConfig(Long aiAgentId) throws Exception;

}

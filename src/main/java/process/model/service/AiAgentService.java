package process.model.service;

import process.model.dto.AdHocPromptRequestDto;
import process.model.dto.ResponseDto;

/**
 * What Core asks about an AI agent -- file chat and the job assistant (ADR-020). Agents are the AI
 * service's; this is answered by it through {@link process.ai.AiPort}, as the signed-in caller.
 *
 * @author Nabeel Ahmed
 */
public interface AiAgentService {

    /**
     * An agent's provider, model, endpoint, JSON mode, instructions and file types, after the same
     * ownership and status checks every agent lookup makes. Never its key: the provider key stays in
     * the AI service, which answers the ad-hoc call itself.
     */
    public ResponseDto resolveRuntimeConfig(Long aiAgentId) throws Exception;

    /** One answer from the agent the request names (its aiAgentId); the AI service supplies the key. */
    public ResponseDto processAdHoc(AdHocPromptRequestDto adHocPromptRequestDto) throws Exception;

}

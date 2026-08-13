package process.model.service;

import process.model.dto.AdHocPromptRequestDto;
import process.model.dto.AiAgentDto;
import process.model.dto.ProcessTextRequestDto;
import process.model.dto.ResponseDto;

public interface AiAgentService {

    public ResponseDto addAgent(AiAgentDto aiAgentDto) throws Exception;

    public ResponseDto updateAgent(AiAgentDto aiAgentDto) throws Exception;

    public ResponseDto deleteAgent(Long aiAgentId) throws Exception;

    public ResponseDto fetchAllAgents() throws Exception;

    public ResponseDto fetchAgentByAgentId(Long aiAgentId) throws Exception;

    public ResponseDto fetchToolByUuid(String toolUuid) throws Exception;

    public ResponseDto processText(ProcessTextRequestDto processTextRequestDto) throws Exception;

    public ResponseDto processAdHoc(AdHocPromptRequestDto adHocPromptRequestDto) throws Exception;

}

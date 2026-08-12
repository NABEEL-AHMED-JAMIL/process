package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.AdHocPromptRequestDto;
import process.model.dto.AiAgentDto;
import process.model.dto.ProcessTextRequestDto;
import process.model.dto.ResponseDto;
import process.model.service.AiAgentService;
import process.util.ProcessUtil;

/**
 * Api use to perform crud operation on AI agents, and to run an agent against extracted
 * file text (see ProcessTextRequestDto -- extraction itself happens client-side). Role policy:
 * defining an agent (add/update/delete) is tenant configuration, TENANT_ADMIN+ -- using one
 * (fetch/process*) is relaxed back down to TENANT_USER below.
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/aiAgent.json")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class AiAgentRestApi {

    private Logger logger = LoggerFactory.getLogger(AiAgentRestApi.class);

    private final AiAgentService aiAgentService;

    public AiAgentRestApi(AiAgentService aiAgentService) {
        this.aiAgentService = aiAgentService;
    }

    /**
     * Api use to add a new AI agent
     * @param aiAgentDto
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/addAgent", method = RequestMethod.POST)
    public ResponseEntity<?> addAgent(@RequestBody AiAgentDto aiAgentDto) {
        try {
            return new ResponseEntity<>(this.aiAgentService.addAgent(aiAgentDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addAgent ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to update an existing AI agent
     * @param aiAgentDto
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/updateAgent", method = RequestMethod.PUT)
    public ResponseEntity<?> updateAgent(@RequestBody AiAgentDto aiAgentDto) {
        try {
            return new ResponseEntity<>(this.aiAgentService.updateAgent(aiAgentDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateAgent ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to soft-delete an AI agent
     * @param aiAgentId
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/deleteAgent", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteAgent(@RequestParam Long aiAgentId) {
        try {
            return new ResponseEntity<>(this.aiAgentService.deleteAgent(aiAgentId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteAgent ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to fetch every AI agent
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/fetchAllAgents", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAllAgents() {
        try {
            return new ResponseEntity<>(this.aiAgentService.fetchAllAgents(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllAgents ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to fetch a single AI agent by id
     * @param aiAgentId
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/fetchAgentByAgentId", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAgentByAgentId(@RequestParam Long aiAgentId) {
        try {
            return new ResponseEntity<>(this.aiAgentService.fetchAgentByAgentId(aiAgentId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAgentByAgentId ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to fetch an agent's public tool configuration (provider/model/instructions,
     * never apiKey/apiEndpoint) by its toolUuid -- meant for an external consumer, e.g. a
     * Source Task XML "tool url" resolves here. Only Active agents are resolvable.
     * @param uuid
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/fetchToolByUuid", method = RequestMethod.GET)
    public ResponseEntity<?> fetchToolByUuid(@RequestParam String uuid) {
        try {
            return new ResponseEntity<>(this.aiAgentService.fetchToolByUuid(uuid), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchToolByUuid ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to run an agent against already-extracted file text
     * @param processTextRequestDto
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/processText", method = RequestMethod.POST)
    public ResponseEntity<?> processText(@RequestBody ProcessTextRequestDto processTextRequestDto) {
        try {
            return new ResponseEntity<>(this.aiAgentService.processText(processTextRequestDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while processText ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to run a fully ad-hoc provider+model+prompt+text call, not tied to any saved
     * AiAgent -- nothing here is persisted.
     * @param adHocPromptRequestDto
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/processAdHoc", method = RequestMethod.POST)
    public ResponseEntity<?> processAdHoc(@RequestBody AdHocPromptRequestDto adHocPromptRequestDto) {
        try {
            return new ResponseEntity<>(this.aiAgentService.processAdHoc(adHocPromptRequestDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while processAdHoc ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

}

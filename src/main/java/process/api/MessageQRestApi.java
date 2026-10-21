package process.api;

import org.barco.platform.security.BuilderAction;
import org.barco.platform.security.WorkspaceData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import process.model.dto.MessageQSearchDto;
import process.model.dto.QueueMessageStatusDto;
import process.model.dto.ResponseDto;
import process.model.service.MessageQService;
import process.util.ProcessUtil;
import process.util.RequestRefused;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * @author Nabeel Ahmed
 * */
// ZT-A12 follow-up (owner 2026-10-21, "Managed session only"): a workspace's runs (the Queue's rows, facets and logs)
// and every status change on them are "Work in this workspace"'s only.
@WorkspaceData
@RestController
@RequestMapping(value = "/message.json")
@PreAuthorize("hasRole('TENANT_USER')")
public class MessageQRestApi {

    private Logger logger = LoggerFactory.getLogger(MessageQRestApi.class);

    private final MessageQService messageQService;

    public MessageQRestApi(MessageQService messageQService) {
        this.messageQService = messageQService;
    }

    @RequestMapping(value = "/fetchLogs", method = RequestMethod.POST)
    public ResponseEntity<?> fetchLogs(
        @RequestBody MessageQSearchDto messageQSearch) {
        try {
            return new ResponseEntity<>(this.messageQService.fetchLogs(messageQSearch), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchLogs ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * UX review 2026-10-13 (B3): the Runs explorer's facets (status, pipeline, trigger) and time histogram, for fetchLogs'
     * body. Gated as the Queue (Runs) page: Run analytics holds /message.json/fetchLogs only.
     */
    @RequestMapping(value = "/runFacets", method = RequestMethod.POST)
    public ResponseEntity<?> runFacets(@RequestBody MessageQSearchDto messageQSearch) {
        try {
            return new ResponseEntity<>(this.messageQService.runFacets(messageQSearch), HttpStatus.OK);
        } catch (RequestRefused refused) {
            throw refused;
        } catch (Exception ex) {
            logger.error("An error occurred while runFacets ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @BuilderAction
    @RequestMapping(value = "/failJobLogs", method = RequestMethod.DELETE)
    public ResponseEntity<?> failJobLogs(
        @RequestParam Long jobQId) {
        try {
            return new ResponseEntity<>(this.messageQService.failJobLogs(jobQId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while failJobLogs ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @BuilderAction
    @RequestMapping(value = "/interruptJobLogs", method = RequestMethod.DELETE)
    public ResponseEntity<?> interruptJobLogs(
        @RequestParam Long jobQId) {
        try {
            return new ResponseEntity<>(this.messageQService.interruptJobLogs(jobQId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while interruptJobLogs ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @BuilderAction
    @RequestMapping(value = "/changeJobStatus", method = RequestMethod.PUT)
    public ResponseEntity<?> changeJobStatus(
        @RequestBody QueueMessageStatusDto queueMessageStatus) {
        try {
            return new ResponseEntity<>(this.messageQService.changeJobStatus(queueMessageStatus), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while changeJobStatus ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}

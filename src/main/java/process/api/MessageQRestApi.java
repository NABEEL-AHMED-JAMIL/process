package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import process.engine.BulkAction;
import process.model.dto.MessageQSearchDto;
import process.model.dto.QueueMessageStatusDto;
import process.model.dto.ResponseDto;
import process.model.service.MessageQApiService;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;

/**
 * Api use to perform crud operation on dashboard
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/message.json")
public class MessageQRestApi {

    private Logger logger = LoggerFactory.getLogger(MessageQRestApi.class);

    @Autowired
    private MessageQApiService messageQApiService;
    @Autowired
    private BulkAction bulkAction;

    /**
     * Integration Status :- done
     * Fetch all message queue by create time (default week data)
     * @param messageQSearch
     * @return ResponseEntity
     * */
    @RequestMapping(value = "/fetchLogs", method = RequestMethod.POST)
    public ResponseEntity<?> fetchLogs(@RequestBody MessageQSearchDto messageQSearch) {
        try {
            return new ResponseEntity<>(this.messageQApiService.fetchLogs(messageQSearch), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchLogs ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Only the queue job be failed
     * @param jobQId
     * @return ResponseEntity
     * */
    @RequestMapping(value = "/failJobLogs", method = RequestMethod.DELETE)
    public ResponseEntity<?> failJobLogs(@RequestParam Long jobQId) {
        try {
            return new ResponseEntity<>(this.messageQApiService.failJobLogs(jobQId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while failJobLogs ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Only the queue job be interrupted
     * @param jobQId
     * @return ResponseEntity
     * */
    @RequestMapping(value = "/interruptJobLogs", method = RequestMethod.DELETE)
    public ResponseEntity<?> interruptJobLogs(@RequestParam Long jobQId) {
        try {
            return new ResponseEntity<>(this.messageQApiService.interruptJobLogs(jobQId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while interruptJobLogs ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Api use to change the status of running job
     * @param queueMessageStatus
     * @return ResponseEntity
     * */
    @RequestMapping(value = "/changeJobStatus", method = RequestMethod.PUT)
    public ResponseEntity<?> changeJobStatus(@RequestBody QueueMessageStatusDto queueMessageStatus) {
        try {
            return new ResponseEntity<>(this.messageQApiService.changeJobStatus(queueMessageStatus), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while changeJobStatus ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }
}

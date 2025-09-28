package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.service.NotifyService;
import process.socket.GlobalProperties;
import process.socket.Message;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;

/**
 * Api use to perform crud operation on dashboard
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
public class NotifyResetApi {

    private Logger logger = LoggerFactory.getLogger(NotifyResetApi.class);

    private final NotifyService notifyService;
    private final GlobalProperties globalProperties;

    public NotifyResetApi(NotifyService notifyService,
        GlobalProperties globalProperties) {
        this.notifyService = notifyService;
        this.globalProperties = globalProperties;
    }

    /**
     * Process message for register the session
     * @param message
     * @param headerAccessor
     * */
    @MessageMapping("/register")
    public void register(@Payload Message message,
        SimpMessageHeaderAccessor headerAccessor) throws Exception {
        logger.info("register sessionID" + message);
        this.globalProperties.addTransactionAndSession(message.getSessionId(), message.getTransactionId());
    }

    /**
     * Process message for unregister the session
     * @param message
     * @param headerAccessor
     * */
    @MessageMapping("/unregister")
    public void unregister(@Payload Message message,
        SimpMessageHeaderAccessor headerAccessor) throws Exception {
        logger.info("unregister sessionID" + message);
        this.globalProperties.removeTransactionAndSession(message.getSessionId());
    }

    // send email
    /**
     * Method use to send the email to user
     * @return ResponseEntity
     * */
    @RequestMapping(value = "/sendEmail", method = RequestMethod.POST)
    public ResponseEntity<?> sendEmail(@RequestBody SourceJobQueueDto sourceJobQueueDto) {
        try {
            return new ResponseEntity<>(this.notifyService.sendEmail(sourceJobQueueDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while sendEmail ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Push notification method use to push the notification on ui
     * @param jobId
     * @return ResponseEntity
     * */
    @RequestMapping(value = "/sendJobStatusNotification", method = RequestMethod.GET)
    public ResponseEntity<?> sendJobStatusNotification(@RequestParam Long jobId) {
        try {
            return new ResponseEntity<>(this.notifyService.sendJobStatusNotification(jobId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while sendJobStatusNotification ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }


}

package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.web.bind.annotation.*;
import process.payload.request.NotificationRequest;
import process.payload.response.AppResponse;
import process.socket.GlobalProperties;
import process.socket.service.NotificationService;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;

/**
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/notification.json")
public class NotificationRestApi {

    private Logger logger = LoggerFactory.getLogger(NotificationRestApi.class);

    @Autowired
    private GlobalProperties globalProperties;
    @Autowired
    private NotificationService notificationService;

    /**
     * Process message for register the session
     * @param notificationRequest
     * @param headerAccessor
     * */
    @MessageMapping("/register")
    public void register(@Payload NotificationRequest notificationRequest,
        SimpMessageHeaderAccessor headerAccessor) throws Exception {
        logger.info("register" + notificationRequest);
        this.globalProperties.addTransactionAndSession(notificationRequest.getSessionId(),
            notificationRequest.getTransactionId());
    }

    /**
     * Process message for unregister the session
     * @param notificationRequest
     * @param headerAccessor
     * */
    @MessageMapping("/unregister")
    public void unregister(@Payload NotificationRequest notificationRequest,
        SimpMessageHeaderAccessor headerAccessor) throws Exception {
        logger.info("unregister" + notificationRequest);
        this.globalProperties.removeTransactionAndSession(notificationRequest.getSessionId());
    }

    /**
     * api-status :- done
     * @apiName :- updateNotification
     * @apiNote :- Api use update notification for specific user
     * @param requestPayload
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/updateNotification", method = RequestMethod.POST)
    public ResponseEntity<?> updateNotification(@RequestBody NotificationRequest requestPayload) {
        try {
            return new ResponseEntity<>(this.notificationService.updateNotification(requestPayload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateNotification ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                ExceptionUtil.getRootCauseMessage(ex)), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- fetchAllNotification
     * @apiNote :- Api use to fetch all notification for specific user
     * @param username
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/fetchAllNotification", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAllNotification(@RequestParam String username) {
        try {
            return new ResponseEntity<>(this.notificationService.fetchAllNotification(username), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllNotification ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                ExceptionUtil.getRootCauseMessage(ex)), HttpStatus.BAD_REQUEST);
        }
    }

}
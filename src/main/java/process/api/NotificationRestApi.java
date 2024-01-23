package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;
import process.socket.GlobalProperties;
import process.socket.payload.Message;


/**
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
public class NotificationRestApi {

    private Logger logger = LoggerFactory.getLogger(NotificationRestApi.class);

    @Autowired
    private GlobalProperties globalProperties;

    /**
     * Process message for register the session
     * @param message
     * @param headerAccessor
     * */
    @MessageMapping("/register")
    public void processMessageFromClient(@Payload Message message,
        SimpMessageHeaderAccessor headerAccessor) throws Exception {
        logger.info("sessionID" + message);
        this.globalProperties.addTransactionAndSession(
            message.getSessionId(), message.getTransactionId());
    }

    /**
     * Process message for unregister the session
     * @param message
     * @param headerAccessor
     * */
    @MessageMapping("/unregister")
    public void unregister(@Payload Message message,
         SimpMessageHeaderAccessor headerAccessor) throws Exception {
         logger.info("sessionID" + message);
         this.globalProperties.removeTransactionAndSession(message.getSessionId());
    }

}

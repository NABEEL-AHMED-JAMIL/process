package process.socket.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import process.socket.GlobalProperties;
import process.util.ProcessUtil;

/**
 * @author Nabeel Ahmed
 */
@Component
public class NotificationService {

    private Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private final String REPLAY = "/reply";

    @Autowired
    private GlobalProperties globalProperties;
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * Notify to websocket
     * @param message
     * @param transactionId
     */
    public void sendNotificationToSpecificUser(String message, String transactionId) {
        logger.info("Request sendNotificationToSpecificUser :- " + message);
        if (!ProcessUtil.isNull(this.globalProperties.getSessionId(transactionId))) {
            String sendTo = this.globalProperties.getSessionId(transactionId)+"-"+transactionId;
            this.messagingTemplate.convertAndSendToUser(sendTo, REPLAY, message);
            logger.info("Send To " + sendTo);
        } else {
            logger.info("No session exist with transactionId " + transactionId);
        }
    }

}

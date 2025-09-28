package process.socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * @author Nabeel Ahmed
 */
@Component
public class NotificationService {

    private Logger logger = LoggerFactory.getLogger(NotificationService.class);

    @Value("${socket.session.id}")
    private String sessionId;
    @Value("${socket.transaction.id}")
    private String transactionId;

    private final GlobalProperties globalProperties;
    private final SimpMessagingTemplate messagingTemplate;

    public NotificationService(GlobalProperties globalProperties,
        SimpMessagingTemplate messagingTemplate) {
        this.globalProperties = globalProperties;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Notify to websocket
     * @param message
     */
    public void sendNotificationToSpecificUser(String message) {
        logger.info("Request sendNotificationToSpecificUser :- {}.", message);
        if (this.globalProperties.isSessionActive(sessionId)) {
            String sendTo = sessionId+"-"+transactionId;
            this.messagingTemplate.convertAndSendToUser(sendTo, "/reply", message);
            logger.info("Session exist, Sending To :- {}.", sendTo);
        } else {
            logger.warn("Cannot send, no active WebSocket session for: {}", sessionId);
        }
    }

}

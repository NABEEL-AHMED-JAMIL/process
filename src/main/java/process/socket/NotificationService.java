package process.socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
public class NotificationService {

    private final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private final WebSocketPresenceService presenceService;
    private final SimpMessagingTemplate messagingTemplate;

    public NotificationService(WebSocketPresenceService presenceService,
        SimpMessagingTemplate messagingTemplate) {
        this.presenceService = presenceService;
        this.messagingTemplate = messagingTemplate;
    }

    public void sendNotificationToSpecificUser(String username, String message) {
        if (username == null) {
            this.logger.debug("sendNotificationToSpecificUser called with no username -- nothing to target.");
            return;
        }
        if (!this.presenceService.isOnline(username)) {
            this.logger.debug("Not sending, no active WebSocket session for user: {}", username);
            return;
        }
        this.messagingTemplate.convertAndSendToUser(username, "/queue/reply", message);
        this.logger.info("Sent WebSocket notification to user: {}", username);
    }

}

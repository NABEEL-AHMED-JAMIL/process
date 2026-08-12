package process.socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Pushes a message to exactly one user's active WebSocket session(s) -- targeted by the real
 * authenticated username (StompAuthChannelInterceptor sets it as the STOMP Principal on
 * CONNECT), not the old fixed global sessionId/transactionId pair every browser tab used to
 * share (which made this, despite the method name, actually a broadcast-to-everyone channel).
 * @author Nabeel Ahmed
 */
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

    /**
     * Method use to notify one specific user, iff they currently have a live WebSocket session.
     * Spring's convertAndSendToUser would itself silently no-op for an offline user, but the
     * presence check here is the actual "is anyone listening" logic this app owns (and gives a
     * log line either way, rather than a send that just vanishes).
     * @param username
     * @param message
     * */
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

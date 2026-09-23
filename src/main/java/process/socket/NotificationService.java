package process.socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * @author Nabeel Ahmed
 * */
@Component
public class NotificationService {

    private final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private final WebSocketPresenceService presenceService;
    private final ClusterBroadcast broadcast;

    public NotificationService(WebSocketPresenceService presenceService,
        ClusterBroadcast broadcast) {
        this.presenceService = presenceService;
        this.broadcast = broadcast;
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
        // Presence is shared through Redis, so the session may be on another instance.
        this.broadcast.toUser(username, "/queue/reply", message);
        this.logger.info("Sent WebSocket notification to user: {}", username);
    }

}

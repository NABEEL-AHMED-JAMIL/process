package process.socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import java.security.Principal;

/**
 * Keeps WebSocketPresenceService in sync with the actual STOMP session lifecycle -- Spring
 * fires SessionConnectedEvent once CONNECT succeeds (by which point
 * StompAuthChannelInterceptor has already set the Principal, if the CONNECT frame carried a
 * valid JWT) and SessionDisconnectEvent whenever a session ends for any reason (client
 * disconnect, network drop, browser tab closed). Driving presence off these events instead of a
 * client-sent register/unregister message means it can't desync from a dropped/skipped message.
 * @author Nabeel Ahmed
 */
@Component
public class WebSocketEventListener {

    private final Logger logger = LoggerFactory.getLogger(WebSocketEventListener.class);

    private final WebSocketPresenceService presenceService;

    public WebSocketEventListener(WebSocketPresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @EventListener
    public void handleSessionConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal user = accessor.getUser();
        if (user == null) {
            this.logger.debug("WebSocket session {} connected with no authenticated Principal -- not tracked as online.",
                accessor.getSessionId());
            return;
        }
        this.presenceService.markOnline(user.getName(), accessor.getSessionId());
    }

    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal user = accessor.getUser();
        if (user == null) {
            return;
        }
        this.presenceService.markOffline(user.getName(), accessor.getSessionId());
    }

}

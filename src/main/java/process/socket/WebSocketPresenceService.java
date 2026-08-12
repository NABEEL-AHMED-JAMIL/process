package process.socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;

/**
 * Tracks which usernames currently have at least one live WebSocket session, backed by Redis
 * (one SET per user, keyed by session id, so multiple tabs/devices for the same user all count)
 * instead of the in-memory single-shared-channel map this replaced (see GlobalProperties.java,
 * now removed). Redis-backed so presence survives an app restart and stays correct if this ever
 * runs as more than one instance -- an in-process map wouldn't.
 *
 * Populated by WebSocketEventListener off Spring's own STOMP connect/disconnect events (not a
 * client-sent register/unregister message -- that was fragile: a client that never explicitly
 * unregistered, or whose register message was dropped, silently desynced the old map).
 * @author Nabeel Ahmed
 */
@Service
public class WebSocketPresenceService {

    private final Logger logger = LoggerFactory.getLogger(WebSocketPresenceService.class);

    private static final String KEY_PREFIX = "ws:online:";
    // Safety net only -- SessionDisconnectEvent should always fire and remove the session id
    // directly. This just bounds how long a session id could linger if that event is ever
    // missed (process crash mid-session, etc.), so presence self-heals instead of leaking forever.
    private static final Duration SAFETY_TTL = Duration.ofHours(12);

    private final RedisTemplate<String, String> redisTemplate;

    public WebSocketPresenceService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Method use to record that a username's WebSocket session just connected.
     * @param username
     * @param sessionId
     * */
    public void markOnline(String username, String sessionId) {
        if (username == null || sessionId == null) {
            return;
        }
        String key = KEY_PREFIX + username;
        this.redisTemplate.opsForSet().add(key, sessionId);
        this.redisTemplate.expire(key, SAFETY_TTL);
        this.logger.debug("WebSocket online: {} (session {})", username, sessionId);
    }

    /**
     * Method use to record that a username's WebSocket session just disconnected -- the
     * username may still be online via another session (another tab/device), which is exactly
     * why this is a set-remove rather than a delete.
     * @param username
     * @param sessionId
     * */
    public void markOffline(String username, String sessionId) {
        if (username == null || sessionId == null) {
            return;
        }
        this.redisTemplate.opsForSet().remove(KEY_PREFIX + username, sessionId);
        this.logger.debug("WebSocket offline: {} (session {})", username, sessionId);
    }

    /**
     * Method use to check whether a username has at least one live WebSocket session right now.
     * @param username
     * @return boolean
     * */
    public boolean isOnline(String username) {
        if (username == null) {
            return false;
        }
        Long count = this.redisTemplate.opsForSet().size(KEY_PREFIX + username);
        return count != null && count > 0;
    }

}

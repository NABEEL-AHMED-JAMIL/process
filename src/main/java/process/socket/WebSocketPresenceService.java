package process.socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Duration;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class WebSocketPresenceService {

    private final Logger logger = LoggerFactory.getLogger(WebSocketPresenceService.class);

    private static final String KEY_PREFIX = "ws:online:";

    private static final Duration SAFETY_TTL = Duration.ofHours(12);

    private final RedisTemplate<String, String> redisTemplate;

    public WebSocketPresenceService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void markOnline(String username, String sessionId) {
        if (username == null || sessionId == null) {
            return;
        }
        String key = KEY_PREFIX + username;
        this.redisTemplate.opsForSet().add(key, sessionId);
        this.redisTemplate.expire(key, SAFETY_TTL);
        this.logger.debug("WebSocket online: {} (session {})", username, sessionId);
    }

    public void markOffline(String username, String sessionId) {
        if (username == null || sessionId == null) {
            return;
        }
        this.redisTemplate.opsForSet().remove(KEY_PREFIX + username, sessionId);
        this.logger.debug("WebSocket offline: {} (session {})", username, sessionId);
    }

    public boolean isOnline(String username) {
        if (username == null) {
            return false;
        }
        Long count = this.redisTemplate.opsForSet().size(KEY_PREFIX + username);
        return count != null && count > 0;
    }

}

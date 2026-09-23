package process.notifications;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The old webpack console's /user/queue/reply push, and nothing else (MIG-22 part 5).
 *
 * That console reads a job's whole detail on its owner's private queue, a payload no contract event
 * carries. Its sessions are on notifications-service now, so this hands the push to the service's
 * bridge channel in the bridge's own envelope, and the instance holding the session delivers it.
 *
 * Temporary by decision: deleted with the old console, once the new one has the AI agent and Ollama
 * screens (recorded on MIG-191). It is the one place process still knows the bridge's format.
 *
 * @author Nabeel Ahmed
 */
@Component
public class LegacyConsolePush {

    /** notifications-service's ClusterBroadcast.CHANNEL. */
    static final String CHANNEL = "ws:broadcast";

    private final Logger logger = LoggerFactory.getLogger(LegacyConsolePush.class);
    private final String origin = "process-" + UUID.randomUUID();
    private final RedisTemplate<String, String> redis;

    public LegacyConsolePush(@Qualifier("redisTemplate") RedisTemplate<String, String> redis) {
        this.redis = redis;
    }

    public void toOwner(String username, String jobDetailJson) {
        if (username == null) {
            return;
        }
        JsonObject envelope = new JsonObject();
        envelope.addProperty("origin", this.origin);
        envelope.addProperty("user", username);
        envelope.addProperty("destination", "/queue/reply");
        envelope.addProperty("body", jobDetailJson);
        try {
            this.redis.convertAndSend(CHANNEL, envelope.toString());
        } catch (RuntimeException redisDown) {
            // A push nobody receives must never fail the operation that triggered it.
            this.logger.warn("Could not push to the old console for {}: {}", username, redisDown.getMessage());
        }
    }
}

package process.socket;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Sends to STOMP destinations on every instance, not just this one (MIG-44/97).
 *
 * Each instance keeps its own in-memory STOMP broker, and a browser's subscriptions live on the
 * instance its WebSocket reached. On its own, a second instance would therefore publish job events
 * and notices only to the browsers connected to it, and presence -- already shared through Redis --
 * could say a user is online while the message went nowhere.
 *
 * So every send is delivered here first, then published once on a Redis channel; every other
 * instance delivers it to the browsers connected to it. Delivery here does not wait for Redis, so
 * with one instance and Redis unavailable nothing changes for the browsers on this instance.
 *
 * Why Redis and not a STOMP relay to RabbitMQ: Redis already runs and already holds presence, so
 * presence and delivery share one dependency; a relay would add a broker to provision, secure and
 * monitor for the same fire-and-forget semantics (a relay's /topic queues are not durable either).
 *
 * One event is one message between instances, however many local audiences it has -- the tenant's
 * feed and the platform-admin feed are both filled from it (DEF-061).
 *
 * @author Nabeel Ahmed
 */
@Component
public class ClusterBroadcast implements MessageListener {

    public static final String CHANNEL = "ws:broadcast";

    private final Logger logger = LoggerFactory.getLogger(ClusterBroadcast.class);
    private final Gson gson = new Gson();
    /** Tells this instance's own publishes apart when Redis echoes them back. */
    private final String origin = UUID.randomUUID().toString();

    private final SimpMessagingTemplate local;
    private final RedisTemplate<String, String> redis;

    public ClusterBroadcast(SimpMessagingTemplate local, @Qualifier("redisTemplate") RedisTemplate<String, String> redis) {
        this.local = local;
        this.redis = redis;
    }

    /** One body to several topic destinations, on every instance. */
    public void toTopics(List<String> destinations, String body) {
        for (String destination : destinations) {
            this.local.convertAndSend(destination, body);
        }
        JsonObject envelope = this.envelope(body);
        JsonArray to = new JsonArray();
        destinations.forEach(to::add);
        envelope.add("destinations", to);
        this.publish(envelope);
    }

    /** To every session of one user, whichever instance each session is on. */
    public void toUser(String username, String destination, String body) {
        this.local.convertAndSendToUser(username, destination, body);
        JsonObject envelope = this.envelope(body);
        envelope.addProperty("user", username);
        envelope.addProperty("destination", destination);
        this.publish(envelope);
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            JsonObject envelope = this.gson.fromJson(new String(message.getBody(), StandardCharsets.UTF_8), JsonObject.class);
            if (envelope == null || this.origin.equals(text(envelope, "origin")) || text(envelope, "body") == null) {
                return;
            }
            String body = text(envelope, "body");
            if (envelope.has("destinations")) {
                for (JsonElement destination : envelope.getAsJsonArray("destinations")) {
                    this.local.convertAndSend(destination.getAsString(), body);
                }
            } else if (text(envelope, "user") != null && text(envelope, "destination") != null) {
                this.local.convertAndSendToUser(text(envelope, "user"), text(envelope, "destination"), body);
            }
        } catch (JsonParseException | IllegalStateException | ClassCastException | UnsupportedOperationException unreadable) {
            this.logger.warn("Ignored an unreadable message on {}: {}", CHANNEL, unreadable.getMessage());
        }
    }

    private JsonObject envelope(String body) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("origin", this.origin);
        envelope.addProperty("body", body);
        return envelope;
    }

    private void publish(JsonObject envelope) {
        try {
            this.redis.convertAndSend(CHANNEL, this.gson.toJson(envelope));
        } catch (RuntimeException redisDown) {
            // The browsers on this instance already have it; only other instances miss out.
            this.logger.warn("Could not share a push with the other instances: {}", redisDown.getMessage());
        }
    }

    private static String text(JsonObject envelope, String field) {
        JsonElement value = envelope.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    /** The subscription that feeds another instance's publishes into this one. */
    public static RedisMessageListenerContainer listenerFor(RedisConnectionFactory connection, ClusterBroadcast bridge) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connection);
        container.addMessageListener(bridge, new ChannelTopic(CHANNEL));
        return container;
    }

    @Configuration
    static class Listener {
        @Bean
        RedisMessageListenerContainer clusterBroadcastListener(RedisConnectionFactory connection, ClusterBroadcast bridge) {
            return listenerFor(connection, bridge);
        }
    }
}

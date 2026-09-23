package process.socket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * T2 and T3 of the two-instance suite (MIG-44/97), against a real Redis: two instances, each
 * with its own connection and its own local broker, as two replicas behind the gateway would be.
 *
 * T2 -- a browser connected to A receives a job event published by B.
 * T3 -- presence is shared through Redis, so isOnline can be true for a user whose only session
 *       is on B; a message sent to that user from A must then reach them.
 *
 * Opt-in by nature: it runs when Redis answers on localhost:6379 (docker compose up -d redis)
 * and skips otherwise.
 */
class ClusterBroadcastRedisTest {

    private final List<AutoCloseable> running = new ArrayList<>();

    static boolean redisIsUp() {
        try (Socket socket = new Socket("localhost", 6379)) {
            return true;
        } catch (Exception unreachable) {
            return false;
        }
    }

    private static final class Instance {
        final SimpMessagingTemplate browsers = mock(SimpMessagingTemplate.class);
        ClusterBroadcast bridge;
    }

    private Instance start() throws Exception {
        LettuceConnectionFactory connection = new LettuceConnectionFactory("localhost", 6379);
        connection.afterPropertiesSet();
        RedisTemplate<String, String> redis = new RedisTemplate<>();
        redis.setConnectionFactory(connection);
        redis.setKeySerializer(new StringRedisSerializer());
        redis.setValueSerializer(new StringRedisSerializer());
        redis.afterPropertiesSet();
        Instance instance = new Instance();
        instance.bridge = new ClusterBroadcast(instance.browsers, redis);
        RedisMessageListenerContainer listener = ClusterBroadcast.listenerFor(connection, instance.bridge);
        listener.afterPropertiesSet();
        listener.start();
        this.running.add(() -> { listener.stop(); listener.destroy(); });
        this.running.add(connection::destroy);
        return instance;
    }

    @BeforeEach
    void redis() {
        assumeTrue(redisIsUp(), "Redis is not running on localhost:6379");
    }

    @AfterEach
    void stop() throws Exception {
        for (int i = this.running.size() - 1; i >= 0; i--) this.running.get(i).close();
    }

    @Test
    void t2_aBrowserOnAReceivesAJobEventPublishedByB() throws Exception {
        Instance a = this.start();
        Instance b = this.start();
        Thread.sleep(500); // both listeners subscribed

        b.bridge.toTopics(Arrays.asList("/topic/jobs.2901", "/topic/jobs.all"), "{\"type\":\"job.status\"}");

        verify(a.browsers, timeout(3000)).convertAndSend("/topic/jobs.2901", "{\"type\":\"job.status\"}");
        verify(a.browsers, timeout(3000)).convertAndSend("/topic/jobs.all", "{\"type\":\"job.status\"}");
        // B delivered its own browsers once, not again from its echo.
        Thread.sleep(300);
        verify(b.browsers, times(1)).convertAndSend("/topic/jobs.2901", "{\"type\":\"job.status\"}");
    }

    @Test
    void t3_aUserWhoseSessionIsOnBGetsAMessageSentFromA() throws Exception {
        Instance a = this.start();
        Instance b = this.start();
        Thread.sleep(500);

        a.bridge.toUser("ops@medaxis.example", "/queue/notifications", "{\"unread\":1}");

        verify(b.browsers, timeout(3000)).convertAndSendToUser("ops@medaxis.example", "/queue/notifications", "{\"unread\":1}");
    }
}

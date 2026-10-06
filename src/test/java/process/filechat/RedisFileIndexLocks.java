package process.filechat;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * RedisFileIndexLocks against the Redis on localhost:6379, as RedisLoginGuards does for the
 * sign-in guard: each fixture under a prefix of its own and cleaned up after, so tests never share
 * a lock with each other or with a running console.
 *
 * Every {@link #instance} is one JVM's lock -- its own heartbeat thread -- over the one shared
 * Redis, as two process replicas are. {@link #crash} stops an instance's heartbeat without
 * releasing anything, which is all a killed JVM leaves behind.
 */
public final class RedisFileIndexLocks implements AutoCloseable {

    private final LettuceConnectionFactory connection;
    private final RedisTemplate<String, String> redis;
    private final String prefix = "filechat-test:index-lock:" + UUID.randomUUID() + ":";
    private final List<ScheduledExecutorService> heartbeats = new ArrayList<>();

    private RedisFileIndexLocks(String host, int port) {
        this.connection = new LettuceConnectionFactory(host, port);
        this.connection.afterPropertiesSet();
        this.redis = template(this.connection);
    }

    public static RedisFileIndexLocks open() {
        boolean up;
        try (Socket socket = new Socket("localhost", 6379)) {
            up = true;
        } catch (Exception unreachable) {
            up = false;
        }
        assumeTrue(up, "Redis is not running on localhost:6379");
        return new RedisFileIndexLocks("localhost", 6379);
    }

    public static RedisTemplate<String, String> template(LettuceConnectionFactory connection) {
        RedisTemplate<String, String> redis = new RedisTemplate<>();
        redis.setConnectionFactory(connection);
        redis.setKeySerializer(new StringRedisSerializer());
        redis.setValueSerializer(new StringRedisSerializer());
        redis.afterPropertiesSet();
        return redis;
    }

    /** One replica's lock, with its own heartbeat. */
    public RedisFileIndexLock instance(Duration lease, Duration wait) {
        ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
        this.heartbeats.add(heartbeat);
        return new RedisFileIndexLock(this.redis, this.prefix, lease, wait, heartbeat);
    }

    /** What a killed JVM does to the locks it held: stops renewing them, and releases nothing. */
    public void crash(RedisFileIndexLock instance) {
        instance.heartbeat().shutdownNow();
    }

    public RedisTemplate<String, String> redis() {
        return this.redis;
    }

    public String prefix() {
        return this.prefix;
    }

    /** Every key this fixture's locks have left in Redis. */
    public Set<String> keysLeft() {
        return this.redis.keys(this.prefix + "*");
    }

    @Override
    public void close() {
        for (ScheduledExecutorService heartbeat : this.heartbeats) {
            heartbeat.shutdownNow();
        }
        Set<String> keys = this.keysLeft();
        if (keys != null && !keys.isEmpty()) {
            this.redis.delete(keys);
        }
        this.connection.destroy();
    }
}

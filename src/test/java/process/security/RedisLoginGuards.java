package process.security;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.net.Socket;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * LoginAttemptGuards against the Redis on localhost:6379, each test under a prefix of its own and
 * cleaned up after, so tests never share a count with each other or with a running console.
 *
 * Skips the calling test when Redis is not there, like OneTimeSecretsRedisTest: the guard's rule is
 * one Lua script, and only a real Redis runs it.
 */
public final class RedisLoginGuards implements AutoCloseable {

    private final LettuceConnectionFactory connection;
    private final RedisTemplate<String, String> redis;
    private final String prefix = "identity-prep-test:login-guard:" + UUID.randomUUID() + ":";

    private RedisLoginGuards(String host, int port) {
        this.connection = new LettuceConnectionFactory(host, port);
        this.connection.afterPropertiesSet();
        this.redis = template(this.connection);
    }

    public static RedisLoginGuards open() {
        boolean up;
        try (Socket socket = new Socket("localhost", 6379)) {
            up = true;
        } catch (Exception unreachable) {
            up = false;
        }
        assumeTrue(up, "Redis is not running on localhost:6379");
        return new RedisLoginGuards("localhost", 6379);
    }

    static RedisTemplate<String, String> template(LettuceConnectionFactory connection) {
        RedisTemplate<String, String> redis = new RedisTemplate<>();
        redis.setConnectionFactory(connection);
        redis.setKeySerializer(new StringRedisSerializer());
        redis.setValueSerializer(new StringRedisSerializer());
        redis.setHashKeySerializer(new StringRedisSerializer());
        redis.setHashValueSerializer(new StringRedisSerializer());
        redis.afterPropertiesSet();
        return redis;
    }

    /** One instance's guard. Two from the same fixture share every count, as two replicas do. */
    public LoginAttemptGuard guard(LongSupplier clock) {
        return new LoginAttemptGuard(this.redis, this.prefix, clock);
    }

    public RedisTemplate<String, String> redis() {
        return this.redis;
    }

    public String prefix() {
        return this.prefix;
    }

    @Override
    public void close() {
        Set<String> keys = this.redis.keys(this.prefix + "*");
        if (keys != null && !keys.isEmpty()) {
            this.redis.delete(keys);
        }
        this.connection.destroy();
    }
}

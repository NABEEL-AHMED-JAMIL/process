package process.identity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;
import process.util.EncryptionUtil;

import java.net.Socket;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A temporary password handed to Notifications by reference (MIG-22 part 4): kept encrypted, for a
 * day, and readable exactly once. Against a real Redis, because "exactly once" is the Lua script.
 */
class OneTimeSecretsRedisTest {

    private LettuceConnectionFactory connection;
    private RedisTemplate<String, String> redis;
    private OneTimeSecrets secrets;

    @BeforeEach
    void redis() throws Exception {
        boolean up;
        try (Socket socket = new Socket("localhost", 6379)) {
            up = true;
        } catch (Exception unreachable) {
            up = false;
        }
        assumeTrue(up, "Redis is not running on localhost:6379");
        this.connection = new LettuceConnectionFactory("localhost", 6379);
        this.connection.afterPropertiesSet();
        this.redis = new RedisTemplate<>();
        this.redis.setConnectionFactory(this.connection);
        this.redis.setKeySerializer(new StringRedisSerializer());
        this.redis.setValueSerializer(new StringRedisSerializer());
        this.redis.afterPropertiesSet();
        EncryptionUtil encryption = new EncryptionUtil();
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i * 5 + 1);
        ReflectionTestUtils.setField(encryption, "base64Key", Base64.getEncoder().encodeToString(key));
        this.secrets = new OneTimeSecrets(this.redis, encryption);
    }

    @AfterEach
    void close() {
        if (this.connection != null) this.connection.destroy();
    }

    @Test
    void aSecretIsReadableOnceAndThenGone() {
        String ref = this.secrets.keep("Tmp-9f2c!");

        assertThat(this.secrets.redeem(ref)).contains("Tmp-9f2c!");
        assertThat(this.secrets.redeem(ref)).isEmpty();
    }

    @Test
    void itIsNotStoredInPlainTextAndExpires() {
        String ref = this.secrets.keep("Tmp-9f2c!");
        String key = OneTimeSecrets.PREFIX + ref;

        assertThat(this.redis.opsForValue().get(key)).isNotBlank().doesNotContain("Tmp-9f2c!");
        Long ttl = this.redis.getExpire(key);
        assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(OneTimeSecrets.TTL.getSeconds());
        this.secrets.redeem(ref);
    }

    @Test
    void anUnknownReferenceRedeemsNothing() {
        assertThat(this.secrets.redeem("no-such-ref")).isEmpty();
        assertThat(this.secrets.redeem(null)).isEmpty();
    }

    /** Two redeemers racing (a retried delivery, two instances): exactly one gets it. */
    @Test
    void twoRedeemersAtOnceGetItOnceBetweenThem() throws Exception {
        String ref = this.secrets.keep("Tmp-9f2c!");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        Callable<Optional<String>> redeem = () -> this.secrets.redeem(ref);
        List<Future<Optional<String>>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) results.add(pool.submit(redeem));
        int got = 0;
        for (Future<Optional<String>> result : results) if (result.get().isPresent()) got++;
        pool.shutdown();

        assertThat(got).isEqualTo(1);
    }
}

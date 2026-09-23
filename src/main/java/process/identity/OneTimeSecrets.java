package process.identity;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import process.util.EncryptionUtil;

import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

/**
 * A secret handed to another service by reference and readable exactly once (MIG-22 part 4).
 *
 * A new account's temporary password must reach the welcome mail, which Notifications now sends,
 * and the contract forbids a credential on a topic. So Identity keeps it here -- encrypted, for a
 * day -- and the event carries only a reference that Notifications redeems once through
 * InternalSecretRestApi. Redeeming is one Lua script, GET and DEL together, so two redeemers (a
 * retried delivery, two instances) can never both read it.
 *
 * @author Nabeel Ahmed
 */
@Component
public class OneTimeSecrets {

    static final String PREFIX = "identity:secret:";
    static final Duration TTL = Duration.ofHours(24);

    private static final DefaultRedisScript<String> REDEEM = new DefaultRedisScript<>(
        "local v = redis.call('GET', KEYS[1]) if v then redis.call('DEL', KEYS[1]) end return v", String.class);

    private final RedisTemplate<String, String> redis;
    private final EncryptionUtil encryption;

    public OneTimeSecrets(@Qualifier("redisTemplate") RedisTemplate<String, String> redis, EncryptionUtil encryption) {
        this.redis = redis;
        this.encryption = encryption;
    }

    /** Keeps a secret for one redemption; answers the reference to send instead. */
    public String keep(String secret) {
        String ref = UUID.randomUUID().toString();
        this.redis.opsForValue().set(PREFIX + ref, this.encryption.encrypt(secret), TTL);
        return ref;
    }

    /** The secret, the first time only; empty when spent, expired or never issued. */
    public Optional<String> redeem(String ref) {
        if (ref == null || ref.trim().isEmpty()) {
            return Optional.empty();
        }
        String sealed = this.redis.execute(REDEEM, Collections.singletonList(PREFIX + ref));
        return sealed == null ? Optional.empty() : Optional.of(this.encryption.decrypt(sealed));
    }
}

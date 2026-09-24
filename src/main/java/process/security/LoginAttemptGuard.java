package process.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.function.LongSupplier;

/**
 * Slows a password guesser down: after {@value #MAX_FAILURES} wrong passwords against one name
 * within {@value #WINDOW_SECONDS} seconds, that name rests for {@value #LOCK_SECONDS} seconds.
 *
 * Per name rather than per address, because the console sits behind a proxy on every
 * deployment that matters and the address it sees is the proxy's.
 *
 * In Redis, shared by every instance (MIG-109, MIG-64). It was a map in each JVM, which multiplied
 * the guesser's budget by the number of replicas -- five wrong passwords spread across two
 * instances never locked anything, and no TTL bounds that harm the way it bounds a stale page set.
 * One hash per name holds the same three numbers the map held, and one Lua script reads and writes
 * them, so two instances counting the same name at the same moment cannot both see four. The rule
 * inside the script is the old compute() line for line, with the caller's clock passed in: the
 * threshold, the window and the lock are numerically unchanged, and a restart of process no longer
 * forgets them (a restart of Redis does, which costs an attacker nothing they had not lost to the
 * lock already). The name is hashed into the key, so Redis holds no sign-in names.
 *
 * When Redis cannot be reached the guard does not guess: {@link #secondsUntilAllowed} throws
 * {@link Unavailable}, and sign-in refuses everybody with one sentence until it is back. Failing
 * open here would be unlimited guesses for as long as the outage lasts.
 *
 * Nothing here refuses a right password on the first try: only a run of wrong ones locks.
 *
 * @author Nabeel Ahmed
 */
@Component
public class LoginAttemptGuard {

    public static final int MAX_FAILURES = 5;
    public static final long WINDOW_SECONDS = 15 * 60;
    public static final long LOCK_SECONDS = 15 * 60;

    static final String DEFAULT_PREFIX = "identity:login-guard:";

    /** Long enough to outlive both the window and the lock it records; the numbers inside decide. */
    static final long KEY_TTL_SECONDS = Math.max(WINDOW_SECONDS, LOCK_SECONDS) + 60;

    /**
     * failed(), atomically. KEYS[1] the name's hash; ARGV now, window, lock, max failures, key TTL.
     * A first failure, or one after the window, starts a fresh count with no lock; reaching the
     * threshold locks from now and starts the count again. Returns the name's lockedUntil.
     */
    private static final DefaultRedisScript<Long> FAILED = new DefaultRedisScript<>(
        "local now = tonumber(ARGV[1]) "
            + "local first = tonumber(redis.call('HGET', KEYS[1], 'first') or '-1') "
            + "local failures = tonumber(redis.call('HGET', KEYS[1], 'failures') or '0') "
            + "local lockedUntil = tonumber(redis.call('HGET', KEYS[1], 'lockedUntil') or '0') "
            + "if first < 0 or now - first > tonumber(ARGV[2]) then first = now failures = 0 lockedUntil = 0 end "
            + "failures = failures + 1 "
            + "if failures >= tonumber(ARGV[4]) then lockedUntil = now + tonumber(ARGV[3]) failures = 0 first = now end "
            + "redis.call('HMSET', KEYS[1], 'first', first, 'failures', failures, 'lockedUntil', lockedUntil) "
            + "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[5])) "
            + "return lockedUntil",
        Long.class);

    /** Redis could not be asked, so whether this name may try is unknown: sign-in must refuse. */
    public static final class Unavailable extends RuntimeException {
        Unavailable(Throwable cause) {
            super("The sign-in attempt guard cannot reach Redis.", cause);
        }
    }

    private final Logger logger = LoggerFactory.getLogger(LoginAttemptGuard.class);

    private final RedisTemplate<String, String> redis;
    private final String prefix;
    private final LongSupplier clock;

    @Autowired
    public LoginAttemptGuard(@Qualifier("redisTemplate") RedisTemplate<String, String> redis,
        @Value("${identity.login-guard.key-prefix:" + DEFAULT_PREFIX + "}") String prefix) {
        this(redis, prefix, () -> System.currentTimeMillis() / 1000);
    }

    /** A prefix and a clock of the caller's, so tests share a Redis without sharing names or waiting. */
    public LoginAttemptGuard(RedisTemplate<String, String> redis, String prefix, LongSupplier clock) {
        this.redis = redis;
        this.prefix = prefix;
        this.clock = clock;
    }

    /** The Redis key for a name: trimmed, lower-cased, then hashed, so every spelling is one count. */
    String keyFor(String username) {
        String name = username == null ? "" : username.trim().toLowerCase();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(this.prefix);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every JVM", impossible);
        }
    }

    /**
     * Seconds the name has to wait before another attempt, or 0 when it may try now.
     *
     * @throws Unavailable when Redis cannot be asked -- never a 0 that would let a guess through.
     */
    public long secondsUntilAllowed(String username) {
        Object lockedUntil;
        try {
            lockedUntil = this.redis.opsForHash().get(this.keyFor(username), "lockedUntil");
        } catch (DataAccessException ex) {
            this.logger.error("Refusing sign-in: the attempt guard cannot read Redis: {}", ex.getMessage());
            throw new Unavailable(ex);
        }
        if (lockedUntil == null) {
            return 0;
        }
        long now = this.clock.getAsLong();
        long until = Long.parseLong(lockedUntil.toString());
        return until > now ? until - now : 0;
    }

    /** Counts a wrong password against the name, everywhere at once. */
    public void failed(String username) {
        try {
            this.redis.execute(FAILED, Collections.singletonList(this.keyFor(username)),
                String.valueOf(this.clock.getAsLong()), String.valueOf(WINDOW_SECONDS), String.valueOf(LOCK_SECONDS),
                String.valueOf(MAX_FAILURES), String.valueOf(KEY_TTL_SECONDS));
        } catch (DataAccessException ex) {
            // The attempt it would have counted has already been refused. The next one asks
            // secondsUntilAllowed first, which fails closed while Redis stays away.
            this.logger.error("Could not count a failed sign-in: {}", ex.getMessage());
        }
    }

    /** A right password forgives the name's earlier mistakes, on every instance. */
    public void succeeded(String username) {
        try {
            this.redis.delete(this.keyFor(username));
        } catch (DataAccessException ex) {
            // The person is signed in either way; at worst an old count ends sooner than it would have.
            this.logger.warn("Could not clear the sign-in count after a success: {}", ex.getMessage());
        }
    }
}

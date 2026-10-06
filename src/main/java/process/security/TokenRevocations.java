package process.security;

import process.identity.IdentityInProcess;
import io.jsonwebtoken.Claims;
import org.barco.platform.security.RevocationCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import process.model.repository.AppUserRepository;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Which tokens are no longer good, although their signature and expiry still are (MIG-14, DEF-013).
 *
 * A JWT used to be trusted for its whole life: a demoted admin kept admin rights, a deactivated
 * person kept working, a suspended tenant's people and a moved person's old scope all lasted until
 * expiry -- thirty minutes for an access token, seven days for a refresh token. Two things end a
 * token early now, both in Redis so every instance agrees at once:
 *
 *   - the denylist: one key per token id (jti), written at logout and when a single-use refresh
 *     token is spent, expiring when the token itself would have;
 *   - the token version: every person carries one (app_user.token_version); every token carries
 *     the version it was minted under; a token below the person's current version is refused.
 *     Bumping it ends every token the person holds, whatever instance minted them.
 *
 * The check is one Redis round trip (MGET of both keys). The version is kept per person for
 * {@link #VERSION_TTL} -- longer than any token lives, because the other services read the same key
 * through platform-commons' RevocationCheck and have no database to fall back on: to them a missing
 * key has to mean "no change a live token could predate". process reads the database on a miss and
 * publishes what it read, and a publish never lowers what is there (a Lua max), so a reader racing a
 * bump cannot put the old number back.
 *
 * Only the read side is left in process (identity.mode=local). Writing -- a sign-out's denial, a bump on
 * every change of a person's standing -- is identity-service's since MIG-107; the process code that did it
 * left with the identity endpoints (MIG-108).
 *
 * Redis unreachable fails closed: {@link #isRevoked} throws {@link Unavailable} and the filter
 * treats the token as unusable. That is every signed-in request refused for the length of an
 * outage, chosen over accepting tokens nobody can vouch for.
 *
 * The key names are platform-commons' ({@link RevocationCheck#DENIED}, {@link RevocationCheck#VERSION}):
 * every service built with JwtVerifier.builder().revocations(...) reads what this writes (1.7.0).
 *
 * @author Nabeel Ahmed
 */
@IdentityInProcess
@Component
public class TokenRevocations {

    public static final String CLAIM_TOKEN_VERSION = "tokenVersion";

    static final String DENIED = RevocationCheck.DENIED;
    static final String VERSION = RevocationCheck.VERSION;
    /** Longer than any token lives -- seven days of refresh token, and a day over (see above). */
    static final Duration VERSION_TTL = Duration.ofDays(8);

    /** SET the version only if it is higher than what is there; refresh the TTL either way. */
    private static final DefaultRedisScript<Long> RAISE = new DefaultRedisScript<>(
        "local current = tonumber(redis.call('GET', KEYS[1]) or '-1') "
            + "local offered = tonumber(ARGV[1]) "
            + "if offered > current then redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2]) return offered end "
            + "redis.call('EXPIRE', KEYS[1], ARGV[2]) "
            + "return current",
        Long.class);

    /** Redis could not be asked, so whether a token is still good is unknown: it is not. */
    public static final class Unavailable extends RuntimeException {
        Unavailable(Throwable cause) {
            super("Token revocations cannot reach Redis.", cause);
        }
    }

    private final Logger logger = LoggerFactory.getLogger(TokenRevocations.class);

    private final RedisTemplate<String, String> redis;
    private final AppUserRepository users;
    private final String prefix;

    @Autowired
    public TokenRevocations(@Qualifier("redisTemplate") RedisTemplate<String, String> redis, AppUserRepository users,
        @Value("${identity.token-revocations.key-prefix:}") String prefix) {
        this.redis = redis;
        this.users = users;
        this.prefix = prefix == null ? "" : prefix;
    }

    private String deniedKey(String jti) {
        return this.prefix + DENIED + jti;
    }

    private String versionKey(Long appUserId) {
        return this.prefix + VERSION + appUserId;
    }

    private static Long appUserIdOf(Claims claims) {
        Number id = claims.get("appUserId", Number.class);
        return id == null ? null : id.longValue();
    }

    /** The version a token was minted under; a token from before MIG-14 carries none and reads as 0. */
    public static int mintedUnder(Claims claims) {
        Number version = claims.get(CLAIM_TOKEN_VERSION, Number.class);
        return version == null ? 0 : version.intValue();
    }

    /**
     * Whether a token must be refused: its id is denied, or it was minted under an older version than
     * its person's. A token naming no person, or a person who is gone, is refused too.
     *
     * @throws Unavailable when Redis cannot be asked -- never a false that would let it through.
     */
    public boolean isRevoked(Claims claims) {
        Long appUserId = appUserIdOf(claims);
        if (appUserId == null) {
            return true;
        }
        String jti = claims.getId();
        List<String> found;
        try {
            found = this.redis.opsForValue().multiGet(Arrays.asList(
                jti == null ? this.prefix + DENIED + "-" : this.deniedKey(jti), this.versionKey(appUserId)));
        } catch (DataAccessException ex) {
            this.logger.error("Refusing a token: revocations cannot be read from Redis: {}", ex.getMessage());
            throw new Unavailable(ex);
        }
        if (jti != null && found != null && found.get(0) != null) {
            return true;
        }
        Integer current = found == null || found.get(1) == null ? null : Integer.valueOf(found.get(1));
        if (current == null) {
            current = this.users.findTokenVersion(appUserId);
            if (current == null) {
                return true;
            }
            this.raise(appUserId, current);
        }
        return mintedUnder(claims) < current;
    }

    private void raise(Long appUserId, int version) {
        try {
            this.redis.execute(RAISE, Collections.singletonList(this.versionKey(appUserId)),
                String.valueOf(version), String.valueOf(VERSION_TTL.getSeconds()));
        } catch (DataAccessException ex) {
            throw new Unavailable(ex);
        }
    }
}

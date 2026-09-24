package process.security;

import io.jsonwebtoken.Claims;
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
import java.util.Date;
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
 * The check is one Redis round trip (MGET of both keys). The version is cached per person for
 * {@link #VERSION_TTL} and read from the database on a miss; a bump publishes the new number
 * straight away, and a publish never lowers what is cached (a Lua max), so a reader racing a bump
 * cannot put the old number back. If publishing fails, the TTL is the bound on how long an old
 * token can outlive its bump -- which is why it is a minute, not a day.
 *
 * Redis unreachable fails closed: {@link #isRevoked} throws {@link Unavailable} and the filter
 * treats the token as unusable. That is every signed-in request refused for the length of an
 * outage, chosen over accepting tokens nobody can vouch for.
 *
 * Only process checks today. Every other service verifies tokens with platform-commons' JwtVerifier,
 * which reads neither key; teaching it to (the key names here are the contract) is a follow-up in
 * that repository.
 *
 * @author Nabeel Ahmed
 */
@Component
public class TokenRevocations {

    public static final String CLAIM_TOKEN_VERSION = "tokenVersion";

    static final String DENIED = "auth:denied:";
    static final String VERSION = "auth:token-version:";
    static final Duration VERSION_TTL = Duration.ofSeconds(60);

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

    /** Whether this token's id is on the denylist -- a spent single-use refresh token, or one signed out. */
    public boolean isDenied(Claims claims) {
        if (claims.getId() == null) {
            return false;
        }
        try {
            return this.redis.hasKey(this.deniedKey(claims.getId()));
        } catch (DataAccessException ex) {
            throw new Unavailable(ex);
        }
    }

    /** Denies this one token until it would have expired anyway: logout, a spent refresh token. */
    public void deny(Claims claims) {
        if (claims.getId() == null) {
            return;
        }
        Date expires = claims.getExpiration();
        long seconds = expires == null ? 1 : Math.max(1, (expires.getTime() - System.currentTimeMillis() + 999) / 1000);
        try {
            this.redis.opsForValue().set(this.deniedKey(claims.getId()), "1", Duration.ofSeconds(seconds));
        } catch (DataAccessException ex) {
            throw new Unavailable(ex);
        }
    }

    /** Ends every token the person holds, on every instance, from the next request. */
    public void revokeSessionsOf(Long appUserId) {
        if (appUserId == null) {
            return;
        }
        this.users.bumpTokenVersion(appUserId);
        Integer version = this.users.findTokenVersion(appUserId);
        if (version != null) {
            this.publish(appUserId, version);
        }
    }

    /** The same for everybody in a tenant: a suspended tenant's people stop at their next request. */
    public void revokeSessionsInTenant(Long tenantId) {
        if (tenantId == null) {
            return;
        }
        this.users.bumpTokenVersionsInTenant(tenantId);
        for (Number id : this.users.findIdsInTenant(tenantId)) {
            Integer version = this.users.findTokenVersion(id.longValue());
            if (version != null) {
                this.publish(id.longValue(), version);
            }
        }
    }

    private void publish(Long appUserId, int version) {
        try {
            this.raise(appUserId, version);
        } catch (Unavailable ex) {
            // The database already holds the new version; a cached old one lasts at most VERSION_TTL.
            this.logger.error("Token version {} for user {} is saved but not published; old tokens may be "
                + "accepted for up to {} s", version, appUserId, VERSION_TTL.getSeconds());
        }
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

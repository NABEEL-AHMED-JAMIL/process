package process.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import process.model.pojo.AppUser;
import process.security.TokenRevocations;
import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * @author Nabeel Ahmed
 * */
@Component
public class JwtUtil {

    private static final String CLAIM_TENANT_ID = "tenantId";
    private static final String CLAIM_USER_ROLE = "userRole";
    private static final String CLAIM_APP_USER_ID = "appUserId";
    /** True while the account still owes a password change: the filter refuses everything else. */
    private static final String CLAIM_PASSWORD_DEBT = "pwd";
    private static final String CLAIM_TYPE = "type";
    /** The person's token_version when the token was minted (MIG-14); TokenRevocations compares it. */
    private static final String CLAIM_TOKEN_VERSION = TokenRevocations.CLAIM_TOKEN_VERSION;
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    @Value("${jwt.secret.key:}")
    private String base64Key;

    @Value("${jwt.access-token.expiry-minutes:30}")
    private long accessTokenExpiryMinutes;

    @Value("${jwt.refresh-token.expiry-days:7}")
    private long refreshTokenExpiryDays;

    public String generateAccessToken(AppUser user) {
        return this.buildToken(user, TYPE_ACCESS, this.accessTokenExpiryMinutes * 60 * 1000);
    }

    public String generateRefreshToken(AppUser user) {
        return this.buildToken(user, TYPE_REFRESH, this.refreshTokenExpiryDays * 24 * 60 * 60 * 1000);
    }

    /**
     * The refresh token handed back by a refresh (MIG-14): a new id, the person's current version,
     * and the same expiry as the token it replaces -- rotating must not turn seven days from sign-in
     * into seven days from the last refresh.
     */
    public String rotateRefreshToken(AppUser user, Date expiresAt) {
        return this.buildToken(user, TYPE_REFRESH, new Date(), expiresAt);
    }

    private String buildToken(AppUser user, String type, long expiryMillis) {
        Date now = new Date();
        return this.buildToken(user, type, now, new Date(now.getTime() + expiryMillis));
    }

    private String buildToken(AppUser user, String type, Date now, Date expiresAt) {
        JwtBuilder builder = Jwts.builder()
            // A token of its own, so it can be signed out on its own (MIG-14).
            .setId(UUID.randomUUID().toString())
            .setSubject(user.getUsername())
            .claim(CLAIM_APP_USER_ID, user.getAppUserId())
            .claim(CLAIM_TENANT_ID, user.getTenantId())
            .claim(CLAIM_USER_ROLE, user.getUserRole().name())
            .claim(CLAIM_PASSWORD_DEBT, user.isMustChangePassword() ? Boolean.TRUE : null)
            .claim(CLAIM_TYPE, type)
            .claim(CLAIM_TOKEN_VERSION, user.getTokenVersion() == null ? 0 : user.getTokenVersion())
            .setIssuedAt(now)
            .setExpiration(expiresAt)
            .signWith(this.secretKey(), SignatureAlgorithm.HS256);
        return builder.compact();
    }

    public Claims parseClaims(String token) {
        return Jwts.parserBuilder()
            .setSigningKey(this.secretKey())
            .build()
            .parseClaimsJws(token)
            .getBody();
    }

    public boolean isRefreshToken(Claims claims) {
        return TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class));
    }

    /** Whether the token was issued to an account that had not yet replaced its temporary password. */
    public boolean owesPasswordChange(Claims claims) {
        return Boolean.TRUE.equals(claims.get(CLAIM_PASSWORD_DEBT, Boolean.class));
    }

    public Long tenantIdOf(Claims claims) {
        return claims.get(CLAIM_TENANT_ID, Long.class);
    }

    public String userRoleOf(Claims claims) {
        return claims.get(CLAIM_USER_ROLE, String.class);
    }

    public Long appUserIdOf(Claims claims) {
        Number n = claims.get(CLAIM_APP_USER_ID, Number.class);
        return n == null ? null : n.longValue();
    }

    private SecretKey secretKey() {
        if (this.base64Key == null || this.base64Key.trim().isEmpty()) {
            throw new IllegalStateException("JWT_SECRET_KEY environment variable is not set; cannot sign/validate tokens.");
        }
        byte[] keyBytes = Base64.getDecoder().decode(this.base64Key.trim());
        return Keys.hmacShaKeyFor(keyBytes);
    }

}

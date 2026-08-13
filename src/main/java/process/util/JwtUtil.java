package process.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import process.model.pojo.AppUser;
import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

@Component
public class JwtUtil {

    private static final String CLAIM_TENANT_ID = "tenantId";
    private static final String CLAIM_USER_ROLE = "userRole";
    private static final String CLAIM_APP_USER_ID = "appUserId";
    private static final String CLAIM_TYPE = "type";
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

    private String buildToken(AppUser user, String type, long expiryMillis) {
        Date now = new Date();
        JwtBuilder builder = Jwts.builder()
            .setSubject(user.getUsername())
            .claim(CLAIM_APP_USER_ID, user.getAppUserId())
            .claim(CLAIM_TENANT_ID, user.getTenantId())
            .claim(CLAIM_USER_ROLE, user.getUserRole().name())
            .claim(CLAIM_TYPE, type)
            .setIssuedAt(now)
            .setExpiration(new Date(now.getTime() + expiryMillis))
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

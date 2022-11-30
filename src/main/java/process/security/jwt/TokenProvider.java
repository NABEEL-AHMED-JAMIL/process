package process.security.jwt;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import io.jsonwebtoken.*;
import process.properties.AppProperties;
import process.security.UserPrincipalDetail;
import java.util.Date;

@Service
public class TokenProvider {

    public Logger logger = LogManager.getLogger(TokenProvider.class);

    private AppProperties appProperties;

    public TokenProvider(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * Generate token
     * */
    public String createToken(UserPrincipalDetail userPrincipal) {
        Date nowDate = new Date();
        Date expiryDate = new Date(nowDate.getTime() + this.appProperties.getAuth().getTokenExpirationMsec());
        return Jwts.builder().setSubject(userPrincipal.getAppUser().getEmail())
        .setIssuedAt(new Date()).setExpiration(expiryDate)
        .signWith(SignatureAlgorithm.HS512, this.appProperties.getAuth().getTokenSecret()).compact();
    }

    public String getUserEmailDetailFromToken(String token) {
        Claims claims = Jwts.parser().setSigningKey(this.appProperties
            .getAuth().getTokenSecret()).parseClaimsJws(token).getBody();
        return claims.getSubject();
    }

    public boolean validateToken(String authToken) {
        try {
            Jwts.parser().setSigningKey(this.appProperties.getAuth()
                .getTokenSecret()).parseClaimsJws(authToken);
            return true;
        } catch (SignatureException ex) {
            logger.error("Invalid JWT signature");
        } catch (MalformedJwtException ex) {
            logger.error("Invalid JWT token");
        } catch (ExpiredJwtException ex) {
            logger.error("Expired JWT token");
        } catch (UnsupportedJwtException ex) {
            logger.error("Unsupported JWT token");
        } catch (IllegalArgumentException ex) {
            logger.error("JWT claims string is empty.");
        }
        return false;
    }
}

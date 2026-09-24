package process.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import process.util.JwtUtil;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;

/**
 * @author Nabeel Ahmed
 * */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtUtil jwtUtil;

    private final TokenRevocations revocations;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, TokenRevocations revocations) {
        this.jwtUtil = jwtUtil;
        this.revocations = revocations;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7);
                Claims claims = this.jwtUtil.parseClaims(token);
                // A signed, unexpired token that has been signed out, or minted before its person's
                // standing changed, authenticates nobody (MIG-14): the request goes on anonymous and
                // Spring Security refuses it wherever a login is needed.
                if (!this.jwtUtil.isRefreshToken(claims) && this.stillGood(claims, request)) {
                    // The gate the browser draws is now also drawn here. A one-time password
                    // used to open a full API session: the console kept the person on the
                    // profile page, and nothing kept a script anywhere.
                    if (this.jwtUtil.owesPasswordChange(claims) && !allowedWhileOwingPassword(request.getRequestURI())) {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType("application/json");
                        response.setCharacterEncoding("UTF-8");
                        response.getWriter().write("{\"status\":\"ERROR\",\"message\":\"Change your temporary password before using anything else.\"}");
                        return;
                    }
                    Long tenantId = this.jwtUtil.tenantIdOf(claims);
                    String userRole = this.jwtUtil.userRoleOf(claims);
                    Long appUserId = this.jwtUtil.appUserIdOf(claims);
                    String username = claims.getSubject();
                    TenantContext.set(tenantId, userRole, appUserId, username);
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        username, null, Collections.singletonList(() -> "ROLE_" + userRole));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        } catch (JwtException | IllegalArgumentException ex) {

            this.logger.debug("Rejected token on {}: {}", request.getRequestURI(), ex.getMessage());
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    /** False when the token is revoked, and when nobody can say whether it is (Redis down): fail closed. */
    private boolean stillGood(Claims claims, HttpServletRequest request) {
        try {
            return !this.revocations.isRevoked(claims);
        } catch (TokenRevocations.Unavailable ex) {
            this.logger.warn("Refused a token on {}: revocations cannot be checked", request.getRequestURI());
            return false;
        }
    }

    /**
     * What a person who still owes a password change may reach: the change itself, their own
     * profile (the screen the change is on), and the session endpoints. Nothing that reads or
     * writes the workspace.
     */
    static boolean allowedWhileOwingPassword(String uri) {
        if (uri == null) return false;
        return uri.contains("/auth.json/")
            || uri.endsWith("/appUser.json/changeOwnPassword")
            || uri.endsWith("/appUser.json/me")
            || uri.contains("/appUser.json/avatar");
    }

}

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

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7);
                Claims claims = this.jwtUtil.parseClaims(token);
                if (!this.jwtUtil.isRefreshToken(claims)) {
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

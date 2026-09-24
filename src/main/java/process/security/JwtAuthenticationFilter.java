package process.security;

import org.barco.platform.security.CallerIdentity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import process.identity.IdentityPort;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 * */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final IdentityPort identity;

    /** Who a token belongs to is Identity's to say (MIG-93): this filter asks the port and sets the context. */
    public JwtAuthenticationFilter(IdentityPort identity) {
        this.identity = identity;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            // A refresh token, a signed-out one, one minted before its person's standing changed, or one
            // that cannot be read authenticates nobody (MIG-14): the request goes on anonymous and Spring
            // Security refuses it wherever a login is needed.
            Optional<CallerIdentity> authenticated = this.identity.authenticate(header.substring(7));
            if (authenticated.isPresent()) {
                CallerIdentity caller = authenticated.get();
                // The gate the browser draws is now also drawn here. A one-time password
                // used to open a full API session: the console kept the person on the
                // profile page, and nothing kept a script anywhere.
                if (caller.owesPasswordChange() && !allowedWhileOwingPassword(request.getRequestURI())) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"status\":\"ERROR\",\"message\":\"Change your temporary password before using anything else.\"}");
                    return;
                }
                String userRole = caller.getUserRole();
                TenantContext.set(caller.getTenantId(), userRole, caller.getAppUserId(), caller.getUsername());
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    caller.getUsername(), null, Collections.singletonList(() -> "ROLE_" + userRole));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
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

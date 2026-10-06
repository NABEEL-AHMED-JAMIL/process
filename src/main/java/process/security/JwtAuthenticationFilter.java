package process.security;

import org.barco.platform.api.Problem;
import org.barco.platform.security.BearerAuthFilter;
import org.barco.platform.security.CallerIdentity;
import org.barco.platform.security.ManagementMode;
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
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith(BearerAuthFilter.CUSTOMER_API) && !"OPTIONS".equalsIgnoreCase(request.getMethod())) {
            this.customerApi(request, response, filterChain, header, path);
            return;
        }
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
                // The change itself, and the profile screen it is on, are Identity's (MIG-108): nothing
                // process answers is open to a person who still owes it.
                if (caller.owesPasswordChange()) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write("{\"status\":\"ERROR\",\"message\":\"Change your temporary password before using anything else.\"}");
                    return;
                }
                String userRole = caller.getUserRole();
                TenantContext.set(caller.getTenantId(), userRole, caller.getAppUserId(), caller.getUsername());
                // MIG-244: the workspace's management mode and whether this is our staff (platform-commons' own context;
                // ManagementModeInterceptor enforces @BuilderAction and audits a managed-service session from it).
                ManagementMode.set(caller);
                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    caller.getUsername(), null, Collections.singletonList(() -> "ROLE_" + userRole));
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            ManagementMode.clear();
        }
    }

    /**
     * MIG-332: the customer API (/v1 at the gateway, /customer here). Only an API client's token opens it -- a person's
     * never, and a client's opens nothing else (authenticate refuses type client). Refused as an RFC 9457 problem, as
     * every /v1 answer is. The caller is the client's workspace with role API_CLIENT and no person; its scopes are the
     * token's.
     */
    private void customerApi(HttpServletRequest request, HttpServletResponse response, FilterChain chain, String header, String path)
        throws ServletException, IOException {
        Optional<CallerIdentity> client = header != null && header.startsWith("Bearer ")
            ? this.identity.authenticateClient(header.substring(7)) : Optional.empty();
        if (!client.isPresent()) {
            Problem.of(401, "A valid access token for the API is required.").at(BearerAuthFilter.publicPath(path)).write(response);
            return;
        }
        CallerIdentity caller = client.get();
        TenantContext.set(caller.getTenantId(), TenantContext.API_CLIENT, null, caller.getUsername());
        TenantContext.setApiClient(caller.getClientId(), caller.getScopes());
        ManagementMode.set(caller);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(caller.getUsername(), null,
            Collections.singletonList(() -> "ROLE_" + TenantContext.API_CLIENT)));
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            ManagementMode.clear();
        }
    }
}

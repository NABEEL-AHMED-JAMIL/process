package process.security;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import process.model.dto.ResponseDto;
import process.identity.IdentityPort;
import process.util.ProcessUtil;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * The server-side half of page access: refuses an API call that belongs to a page the caller's
 * profile does not open.
 *
 * The console hides menu entries and guards routes, but that is courtesy -- anything a browser
 * can be told not to show it can also be asked for directly. So every request under a gated
 * API group (PageKey.pagesGating) is checked here for a TENANT_USER, and refused with a 403
 * carrying the same envelope the console already understands. Admins never reach the check:
 * roles gate their pages, and a profile cannot take anything from them.
 *
 * Cost: one lookup per request, of the user row and possibly the profile. PageAccessCache keeps
 * the answer for a few seconds per person, because a busy screen fires several calls a second
 * and none of them wants a round trip to decide something that changes a few times a year.
 *
 * @author Nabeel Ahmed
 */
@Component
public class PageAccessInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(PageAccessInterceptor.class);

    private final IdentityPort identity;

    /** The decision is Identity's page gate, asked through the port (MIG-93). */
    public PageAccessInterceptor(IdentityPort identity) {
        this.identity = identity;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        IdentityPort.PageDecision decision = this.identity.pageDecision(TenantContext.getUserRole(), TenantContext.getAppUserId(),
            request.getServletPath());
        if (decision.isAllowed()) {
            return true;
        }
        logger.info("Refused {} {} for user {}: {}", request.getMethod(), request.getServletPath(),
            TenantContext.getAppUserId(), decision.getMessage());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(new Gson().toJson(new ResponseDto(ProcessUtil.ERROR, decision.getMessage())));
        return false;
    }
}

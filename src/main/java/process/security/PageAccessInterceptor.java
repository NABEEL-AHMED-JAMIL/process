package process.security;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import process.model.dto.ResponseDto;
import process.model.enums.PageKey;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.repository.AppUserRepository;
import process.model.service.PageAccessService;
import process.util.ProcessUtil;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.Set;

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

    private final AppUserRepository appUserRepository;
    private final PageAccessService pageAccessService;
    private final PageAccessCache cache;

    public PageAccessInterceptor(AppUserRepository appUserRepository, PageAccessService pageAccessService,
        PageAccessCache cache) {
        this.appUserRepository = appUserRepository;
        this.pageAccessService = pageAccessService;
        this.cache = cache;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!UserRole.TENANT_USER.name().equals(TenantContext.getUserRole())) {
            return true;
        }
        Set<PageKey> gating = PageKey.pagesGating(request.getServletPath());
        if (gating.isEmpty()) {
            return true;
        }
        Long appUserId = TenantContext.getAppUserId();
        if (appUserId == null) {
            return true;
        }
        Set<PageKey> allowed = this.pagesFor(appUserId);
        for (PageKey page : gating) {
            if (allowed.contains(page)) {
                return true;
            }
        }
        PageKey named = gating.iterator().next();
        logger.info("Refused {} {} for user {}: page '{}' is not in their access profile.",
            request.getMethod(), request.getServletPath(), appUserId, named.getKey());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(new Gson().toJson(new ResponseDto(ProcessUtil.ERROR,
            String.format("%s is not part of your access. Ask your workspace admin.", named.getLabel()))));
        return false;
    }

    private Set<PageKey> pagesFor(Long appUserId) {
        return this.cache.get(appUserId, id -> this.appUserRepository.findById(id)
            .map(this.pageAccessService::effectivePages).orElse(PageKey.all()));
    }
}

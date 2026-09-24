package process.security;

import org.springframework.stereotype.Component;
import process.model.enums.PageKey;
import process.model.enums.UserRole;
import process.model.repository.AppUserRepository;
import process.model.service.PageAccessService;

import java.util.EnumSet;
import java.util.Set;

/**
 * Whether the caller may use an API path, by the pages their access profile opens. One decision,
 * used by PageAccessInterceptor for process's own paths and by /internal/pageAccess/check for the
 * services that left process, so a page gates the same way wherever its API now lives.
 *
 * Only a tenant user is gated; an administrator's page access is decided elsewhere. A tenant user
 * with no user id, or whose row is gone, holds no pages: both used to fail open (MIG-12).
 */
@Component
public class PageGate {

    /** The decision, and the sentence a refused caller reads. */
    public static final class Decision {
        private final boolean allowed;
        private final String message;
        private Decision(boolean allowed, String message) { this.allowed = allowed; this.message = message; }
        public boolean isAllowed() { return this.allowed; }
        public String getMessage() { return this.message; }
    }

    private static final Decision ALLOWED = new Decision(true, null);

    private final AppUserRepository appUserRepository;
    private final PageAccessService pageAccessService;
    private final PageAccessCache cache;

    public PageGate(AppUserRepository appUserRepository, PageAccessService pageAccessService, PageAccessCache cache) {
        this.appUserRepository = appUserRepository;
        this.pageAccessService = pageAccessService;
        this.cache = cache;
    }

    /** The decision for this role and user on this servlet path (without the /api/v1 context). */
    public Decision decide(String userRole, Long appUserId, String servletPath) {
        return this.decide(userRole, appUserId, servletPath, true);
    }

    /**
     * The same decision read straight from the database, for the gateway (MIG-99). The gateway keeps
     * each answer for the 15-second TTL itself; answering it from this instance's cache as well would
     * stack two TTLs, and a revoked page could stay open for up to 30 seconds through the gateway.
     */
    public Decision decideFresh(String userRole, Long appUserId, String servletPath) {
        return this.decide(userRole, appUserId, servletPath, false);
    }

    private Decision decide(String userRole, Long appUserId, String servletPath, boolean cached) {
        if (!UserRole.TENANT_USER.name().equals(userRole)) {
            return ALLOWED;
        }
        Set<PageKey> gating = PageKey.pagesGating(servletPath);
        if (gating.isEmpty()) {
            return ALLOWED;
        }
        Set<PageKey> held = appUserId == null ? EnumSet.noneOf(PageKey.class)
            : cached ? this.cache.get(appUserId, this::resolve) : this.resolve(appUserId);
        for (PageKey page : gating) {
            if (held.contains(page)) {
                return ALLOWED;
            }
        }
        return new Decision(false, String.format("%s is not part of your access. Ask your workspace admin.",
            gating.iterator().next().getLabel()));
    }

    private Set<PageKey> resolve(Long appUserId) {
        return this.appUserRepository.findById(appUserId)
            .map(this.pageAccessService::effectivePages).orElse(EnumSet.noneOf(PageKey.class));
    }
}

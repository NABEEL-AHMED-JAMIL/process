package process.model.service;

import process.model.enums.PageKey;
import process.model.pojo.AppUser;
import java.util.Set;

/**
 * Which console pages a tenant user may open -- the page gate's question while Identity runs in process
 * (identity.mode=local). The profiles and exceptions themselves are edited in identity-service.
 *
 * @author Nabeel Ahmed
 */
public interface PageAccessService {

    /**
     * The pages this person may open. Never empty for an admin; for a tenant user, their
     * profile, else the workspace default, else -- when the workspace has no default -- every
     * page. That last rule is what keeps a workspace that never set profiles up exactly as it
     * was.
     */
    Set<PageKey> effectivePages(AppUser user);
}

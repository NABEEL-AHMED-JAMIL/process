package process.model.service.impl;

import process.identity.IdentityInProcess;
import org.springframework.stereotype.Service;
import process.model.enums.PageKey;
import process.model.enums.Status;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.PageAccessProfile;
import process.model.pojo.UserPageAccess;
import process.model.repository.PageAccessProfileRepository;
import process.model.repository.UserPageAccessRepository;
import process.model.service.PageAccessService;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import static process.util.ProcessUtil.isNull;

/**
 * The pages a person may open, read from Identity's tables for the page gate while Identity runs in
 * process (identity.mode=local). Editing profiles and exceptions is identity-service's since MIG-107;
 * its endpoints left process with MIG-108.
 *
 * @author Nabeel Ahmed
 * */
@IdentityInProcess
@Service
public class PageAccessServiceImpl implements PageAccessService {

    private final PageAccessProfileRepository profileRepository;
    private final UserPageAccessRepository exceptionRepository;

    public PageAccessServiceImpl(PageAccessProfileRepository profileRepository, UserPageAccessRepository exceptionRepository) {
        this.profileRepository = profileRepository;
        this.exceptionRepository = exceptionRepository;
    }

    /**
     * The whole rule, in one place.
     *
     * Admins are never subject to a profile: the tenant admin is the person who writes them, and
     * a platform admin spans every workspace. For everyone else the order is their own profile,
     * then the workspace default, then everything -- and the last step is deliberate. A
     * workspace that has never opened the Access profiles screen has no default, and its people
     * keep exactly what they had before the screen existed. A workspace that HAS set a default
     * but forgot to assign somebody gets the default, which is what "default" means.
     *
     * A profile row that was deactivated underneath a person, or a key the catalogue no longer
     * carries, both degrade the same way: as if not there.
     */
    @Override
    public Set<PageKey> effectivePages(AppUser user) {
        if (user == null) {
            return EnumSet.noneOf(PageKey.class);
        }
        if (user.getUserRole() != UserRole.TENANT_USER) {
            return PageKey.all();
        }
        Optional<PageAccessProfile> own = isNull(user.getPageAccessProfileId()) ? Optional.empty()
            : this.profileRepository.findById(user.getPageAccessProfileId());
        Optional<PageAccessProfile> fallback = isNull(user.getTenantId()) ? Optional.empty()
            : this.profileRepository.findByTenantIdAndDefaultProfileTrueAndStatus(user.getTenantId(), Status.Active);
        return resolve(user, own.orElse(null), fallback.orElse(null),
            this.exceptionRepository.findByIdAppUserId(user.getAppUserId()));
    }

    /**
     * The rule itself, with the rows already in hand.
     *
     * The profile is the baseline: the person's own if it is active and in their workspace (a
     * deactivated row, or one from another workspace, must not grant anything and falls through
     * to the default), else the default, else every page. Then the person's exceptions: each
     * allowed one opens a page the profile withholds, each withheld one closes a page it opens.
     */
    private static Set<PageKey> resolve(AppUser user, PageAccessProfile own, PageAccessProfile fallback,
        Collection<UserPageAccess> exceptions) {
        Set<PageKey> pages = profilePages(user, own, fallback);
        for (UserPageAccess exception : exceptions == null ? Collections.<UserPageAccess>emptyList() : exceptions) {
            PageKey.fromKey(exception.getPageKey()).ifPresent(page -> {
                if (exception.isAllowed()) pages.add(page); else pages.remove(page);
            });
        }
        return pages;
    }

    /** What the profile alone says -- the baseline the exceptions are measured against. */
    private static Set<PageKey> profilePages(AppUser user, PageAccessProfile own, PageAccessProfile fallback) {
        PageAccessProfile chosen = own != null && own.getStatus() == Status.Active
            && own.getTenantId() != null && own.getTenantId().equals(user.getTenantId()) ? own : fallback;
        if (chosen == null) {
            return PageKey.all();
        }
        return toPageKeys(chosen.getPageKeys());
    }

    private static Set<PageKey> toPageKeys(Collection<String> keys) {
        Set<PageKey> pages = EnumSet.noneOf(PageKey.class);
        if (keys == null) {
            return pages;
        }
        for (String key : keys) {
            PageKey.fromKey(key).ifPresent(pages::add);
        }
        return pages;
    }
}

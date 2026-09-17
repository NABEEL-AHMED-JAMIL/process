package process.model.service;

import process.model.dto.PageAccessProfileDto;
import process.model.dto.ResponseDto;
import process.model.enums.PageKey;
import process.model.pojo.AppUser;
import java.util.Set;

/**
 * Access profiles: which console pages a tenant user may open, and the bundles that say so.
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

    /** The catalogue, for the profile editor and the console's own page list. */
    ResponseDto catalogue();

    /** The caller's own effective pages, for a console that wants to re-check after a change. */
    ResponseDto mine() throws Exception;

    ResponseDto listProfiles() throws Exception;

    ResponseDto addProfile(PageAccessProfileDto dto) throws Exception;

    ResponseDto updateProfile(PageAccessProfileDto dto) throws Exception;

    ResponseDto deleteProfile(Long pageAccessProfileId) throws Exception;

    ResponseDto setDefaultProfile(Long pageAccessProfileId) throws Exception;

    /**
     * Checks a profile id somebody wants to put on a user: it has to exist, be active, and belong
     * to the user's workspace. Returns null when it is fine, or the refusal to send back.
     */
    ResponseDto refuseUnusableProfile(Long pageAccessProfileId, Long tenantId);

    /** The profile's name for a user row, or null when the user holds none. */
    String profileNameFor(Long pageAccessProfileId);

    /** A tenant user asking their admins for a page they cannot open. */
    ResponseDto requestAccess(String pageKey) throws Exception;

}

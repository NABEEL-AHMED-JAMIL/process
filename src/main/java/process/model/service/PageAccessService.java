package process.model.service;

import process.model.dto.PageAccessProfileDto;
import process.model.dto.ResponseDto;
import process.model.enums.PageKey;
import process.model.pojo.AppUser;
import java.util.Collection;
import java.util.Map;
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

    /**
     * The profiles of one workspace. A tenant admin's own; a platform admin names it with
     * tenantId, since it has no workspace of its own.
     */
    ResponseDto listProfiles(Long tenantId) throws Exception;

    ResponseDto addProfile(PageAccessProfileDto dto) throws Exception;

    ResponseDto updateProfile(PageAccessProfileDto dto) throws Exception;

    ResponseDto deleteProfile(Long pageAccessProfileId) throws Exception;

    ResponseDto setDefaultProfile(Long pageAccessProfileId) throws Exception;

    /**
     * The workspace's tenant users with the pages each one effectively opens -- the grid's rows.
     * Admins are left out: nothing about them can be changed here.
     */
    ResponseDto listPeople(Long tenantId) throws Exception;

    /**
     * Puts one tenant user on a profile (null: the workspace default), without the rest of the
     * user form. Same rules as updateUser: the person must be in a workspace the caller manages,
     * must be a TENANT_USER, and the profile must belong to that workspace.
     */
    ResponseDto assignProfile(Long appUserId, Long pageAccessProfileId) throws Exception;

    /**
     * Opens or withholds one page for one person, as an exception to their profile. Asking for
     * what the profile already says clears any exception instead, so the table only ever holds
     * real differences. Same reach rules as assignProfile.
     */
    ResponseDto setPageAccess(Long appUserId, String pageKey, boolean allowed) throws Exception;

    /** Drops every exception a person carries, so they read exactly as their profile. */
    ResponseDto clearPageAccess(Long appUserId) throws Exception;

    /**
     * Checks a profile id somebody wants to put on a user: it has to exist, be active, and belong
     * to the user's workspace. Returns null when it is fine, or the refusal to send back.
     */
    ResponseDto refuseUnusableProfile(Long pageAccessProfileId, Long tenantId);

    /** The profile's name for a user row, or null when the user holds none. */
    String profileNameFor(Long pageAccessProfileId);

    /** The same, for a whole list at once: one query, however many rows. */
    Map<Long, String> profileNamesFor(Collection<Long> pageAccessProfileIds);

    /**
     * Tells a tenant user their profile changed, in the one wording the console uses for it.
     * Silent for an admin, for whom nothing changed.
     */
    void notifyProfileChanged(AppUser user);

    /** A tenant user asking their admins for a page they cannot open. */
    ResponseDto requestAccess(String pageKey) throws Exception;

}

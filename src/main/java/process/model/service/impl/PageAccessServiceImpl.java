package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.PageAccessProfileDto;
import process.model.dto.ResponseDto;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;
import process.model.enums.PageKey;
import process.model.enums.Status;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.PageAccessProfile;
import process.model.repository.AppUserRepository;
import process.model.repository.PageAccessProfileRepository;
import process.model.repository.TenantRepository;
import process.model.service.NotificationCenterService;
import process.model.service.PageAccessService;
import process.security.PageAccessCache;
import process.security.TenantContext;
import process.util.UserNameResolver;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;
import static process.util.ProcessUtil.isNull;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class PageAccessServiceImpl implements PageAccessService {

    private final Logger logger = LoggerFactory.getLogger(PageAccessServiceImpl.class);

    private static final String NOT_FOUND = "Access profile not found.";

    private final PageAccessProfileRepository profileRepository;
    private final AppUserRepository appUserRepository;
    private final NotificationCenterService notificationCenterService;
    private final UserNameResolver userNameResolver;
    private final PageAccessCache cache;
    private final TenantRepository tenantRepository;

    public PageAccessServiceImpl(PageAccessProfileRepository profileRepository,
        AppUserRepository appUserRepository, NotificationCenterService notificationCenterService,
        UserNameResolver userNameResolver, PageAccessCache cache, TenantRepository tenantRepository) {
        this.profileRepository = profileRepository;
        this.appUserRepository = appUserRepository;
        this.notificationCenterService = notificationCenterService;
        this.userNameResolver = userNameResolver;
        this.cache = cache;
        this.tenantRepository = tenantRepository;
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
        Optional<PageAccessProfile> profile = Optional.empty();
        if (!isNull(user.getPageAccessProfileId())) {
            profile = this.profileRepository.findById(user.getPageAccessProfileId())
                .filter(p -> p.getStatus() == Status.Active)
                // A profile from another workspace on this row is a data error, and it must not
                // grant anything: fall through to the workspace's own default.
                .filter(p -> p.getTenantId() != null && p.getTenantId().equals(user.getTenantId()));
        }
        if (!profile.isPresent() && !isNull(user.getTenantId())) {
            profile = this.profileRepository.findByTenantIdAndDefaultProfileTrueAndStatus(user.getTenantId(), Status.Active);
        }
        if (!profile.isPresent()) {
            return PageKey.all();
        }
        return toPageKeys(profile.get().getPageKeys());
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

    @Override
    public ResponseDto catalogue() {
        List<Map<String, String>> pages = new ArrayList<>();
        for (PageKey page : PageKey.values()) {
            Map<String, String> row = new HashMap<>();
            row.put("key", page.getKey());
            row.put("label", page.getLabel());
            row.put("section", page.getSection());
            row.put("route", page.getRoute());
            pages.add(row);
        }
        return new ResponseDto(SUCCESS, "Pages fetched.", pages);
    }

    @Override
    public ResponseDto mine() throws Exception {
        Long appUserId = TenantContext.getAppUserId();
        if (isNull(appUserId)) {
            return new ResponseDto(ERROR, "No signed-in user.");
        }
        Optional<AppUser> user = this.appUserRepository.findById(appUserId);
        if (!user.isPresent()) {
            return new ResponseDto(ERROR, "User not found.");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("pageKeys", keysOf(this.effectivePages(user.get())));
        data.put("pageAccessProfileName", this.profileNameFor(user.get().getPageAccessProfileId()));
        return new ResponseDto(SUCCESS, "Pages fetched.", data);
    }

    /**
     * Which workspace a management call is about.
     *
     * A tenant admin's is always its own -- whatever id it sends is ignored, so one workspace's
     * admin cannot reach into another's. A platform admin has none, so it has to say: a missing
     * or unknown id is refused rather than guessed. Returns null and fills `refused` otherwise.
     */
    private Long workspaceFor(Long requested, ResponseDto[] refused) {
        if (!TenantContext.isPlatformAdmin()) {
            Long own = TenantContext.getTenantId();
            if (isNull(own)) {
                refused[0] = new ResponseDto(ERROR, "Access profiles belong to a workspace; sign in to one to manage them.");
            }
            return own;
        }
        if (isNull(requested)) {
            refused[0] = new ResponseDto(ERROR, "Say which workspace: a platform admin has none of its own.");
            return null;
        }
        if (!this.tenantRepository.findById(requested).isPresent()) {
            refused[0] = new ResponseDto(ERROR, String.format("Tenant not found with %d.", requested));
            return null;
        }
        return requested;
    }

    @Override
    public ResponseDto listProfiles(Long requestedTenantId) throws Exception {
        ResponseDto[] refused = new ResponseDto[1];
        Long tenantId = this.workspaceFor(requestedTenantId, refused);
        if (tenantId == null) {
            return refused[0];
        }
        List<PageAccessProfile> profiles = this.profileRepository
            .findByTenantIdAndStatusOrderByProfileNameAsc(tenantId, Status.Active);
        this.userNameResolver.attachNames(profiles);
        List<PageAccessProfileDto> dtos = profiles.stream().map(this::toDto).collect(Collectors.toList());
        return new ResponseDto(SUCCESS, "Access profiles fetched.", dtos);
    }

    @Override
    @Transactional
    public ResponseDto addProfile(PageAccessProfileDto dto) throws Exception {
        ResponseDto[] noWorkspace = new ResponseDto[1];
        Long tenantId = this.workspaceFor(dto.getTenantId(), noWorkspace);
        if (tenantId == null) {
            return noWorkspace[0];
        }
        ResponseDto refused = this.validate(dto, tenantId, null);
        if (refused != null) {
            return refused;
        }
        PageAccessProfile profile = new PageAccessProfile();
        profile.setTenantId(tenantId);
        profile.setProfileName(dto.getProfileName().trim());
        profile.setDescription(trimToNull(dto.getDescription()));
        profile.setPageKeys(normaliseKeys(dto.getPageKeys()));
        profile.setStatus(Status.Active);
        profile.setDateCreated(new Timestamp(System.currentTimeMillis()));
        // The first profile a workspace makes becomes its default, so creating one and forgetting
        // to mark it does not leave every unassigned person on "everything" by accident.
        boolean first = this.profileRepository.countByTenantIdAndStatus(tenantId, Status.Active) == 0;
        boolean wantsDefault = Boolean.TRUE.equals(dto.getDefaultProfile()) || first;
        if (wantsDefault) {
            this.clearDefault(tenantId);
        }
        profile.setDefaultProfile(wantsDefault);
        this.profileRepository.save(profile);
        this.cache.forgetAll();
        logger.info("Access profile '{}' ({}) created for tenant {}{}.", profile.getProfileName(),
            profile.getPageAccessProfileId(), tenantId, wantsDefault ? " as the default" : "");
        return new ResponseDto(SUCCESS, String.format("Access profile \"%s\" created%s.",
            profile.getProfileName(), first ? " and set as the default" : ""), this.toDto(profile));
    }

    @Override
    @Transactional
    public ResponseDto updateProfile(PageAccessProfileDto dto) throws Exception {
        if (isNull(dto.getPageAccessProfileId())) {
            return new ResponseDto(ERROR, "Access profile id missing.");
        }
        Optional<PageAccessProfile> found = this.scopedFind(dto.getPageAccessProfileId());
        if (!found.isPresent()) {
            return new ResponseDto(ERROR, NOT_FOUND);
        }
        PageAccessProfile profile = found.get();
        ResponseDto refused = this.validate(dto, profile.getTenantId(), profile.getPageAccessProfileId());
        if (refused != null) {
            return refused;
        }
        Set<String> before = new LinkedHashSet<>(profile.getPageKeys());
        profile.setProfileName(dto.getProfileName().trim());
        profile.setDescription(trimToNull(dto.getDescription()));
        profile.setPageKeys(normaliseKeys(dto.getPageKeys()));
        if (Boolean.TRUE.equals(dto.getDefaultProfile()) && !profile.isDefaultProfile()) {
            this.clearDefault(profile.getTenantId());
            profile.setDefaultProfile(true);
        }
        profile.setDateUpdated(new Timestamp(System.currentTimeMillis()));
        this.profileRepository.save(profile);
        this.cache.forgetAll();
        if (!before.equals(profile.getPageKeys())) {
            this.tellHolders(profile);
        }
        return new ResponseDto(SUCCESS, String.format("Access profile \"%s\" updated.", profile.getProfileName()),
            this.toDto(profile));
    }

    /**
     * People on a changed profile hear about it: the pages in their menu just moved, and a
     * notification is the difference between "the admin changed something" and "the app broke".
     */
    private void tellHolders(PageAccessProfile profile) {
        List<AppUser> holders = this.appUserRepository
            .findByPageAccessProfileIdAndStatusNot(profile.getPageAccessProfileId(), Status.Delete);
        String pages = PageKey.all().stream()
            .filter(p -> profile.getPageKeys().contains(p.getKey()))
            .map(PageKey::getLabel).collect(Collectors.joining(", "));
        for (AppUser holder : holders) {
            if (holder.getUserRole() != UserRole.TENANT_USER) {
                continue;
            }
            this.notificationCenterService.create(holder.getTenantId(), holder.getAppUserId(),
                NotificationType.PAGE_ACCESS_CHANGED, NotificationSeverity.INFO,
                "Your page access changed",
                String.format("Your \"%s\" profile now opens: %s.", profile.getProfileName(),
                    pages.isEmpty() ? "no pages beyond the dashboard" : pages),
                "/dashboard");
        }
    }

    @Override
    @Transactional
    public ResponseDto deleteProfile(Long pageAccessProfileId) throws Exception {
        if (isNull(pageAccessProfileId)) {
            return new ResponseDto(ERROR, "Access profile id missing.");
        }
        Optional<PageAccessProfile> found = this.scopedFind(pageAccessProfileId);
        if (!found.isPresent()) {
            return new ResponseDto(ERROR, NOT_FOUND);
        }
        PageAccessProfile profile = found.get();
        long holders = this.appUserRepository.countByPageAccessProfileIdAndStatusNot(pageAccessProfileId, Status.Delete);
        if (holders > 0) {
            // Refused rather than cascaded: silently dropping people to the default would change
            // what they can open without anyone having decided that for them.
            return new ResponseDto(ERROR, String.format(
                "\"%s\" is still held by %d %s. Move them to another profile first.",
                profile.getProfileName(), holders, holders == 1 ? "person" : "people"));
        }
        profile.setStatus(Status.Delete);
        profile.setDefaultProfile(false);
        profile.setDateUpdated(new Timestamp(System.currentTimeMillis()));
        this.profileRepository.save(profile);
        this.cache.forgetAll();
        return new ResponseDto(SUCCESS, String.format("Access profile \"%s\" deleted.", profile.getProfileName()));
    }

    @Override
    @Transactional
    public ResponseDto setDefaultProfile(Long pageAccessProfileId) throws Exception {
        if (isNull(pageAccessProfileId)) {
            return new ResponseDto(ERROR, "Access profile id missing.");
        }
        Optional<PageAccessProfile> found = this.scopedFind(pageAccessProfileId);
        if (!found.isPresent()) {
            return new ResponseDto(ERROR, NOT_FOUND);
        }
        PageAccessProfile profile = found.get();
        this.clearDefault(profile.getTenantId());
        profile.setDefaultProfile(true);
        profile.setDateUpdated(new Timestamp(System.currentTimeMillis()));
        this.profileRepository.save(profile);
        this.cache.forgetAll();
        return new ResponseDto(SUCCESS, String.format("\"%s\" is now the default for new and unassigned users.",
            profile.getProfileName()), this.toDto(profile));
    }

    @Override
    public ResponseDto refuseUnusableProfile(Long pageAccessProfileId, Long tenantId) {
        if (isNull(pageAccessProfileId)) {
            return null;
        }
        Optional<PageAccessProfile> profile = this.profileRepository.findById(pageAccessProfileId)
            .filter(p -> p.getStatus() == Status.Active);
        if (!profile.isPresent() || tenantId == null || !tenantId.equals(profile.get().getTenantId())) {
            // One message for "does not exist" and "belongs to another workspace": naming the
            // difference would confirm ids from other tenants.
            return new ResponseDto(ERROR, NOT_FOUND);
        }
        return null;
    }

    @Override
    public String profileNameFor(Long pageAccessProfileId) {
        if (isNull(pageAccessProfileId)) {
            return null;
        }
        return this.profileRepository.findById(pageAccessProfileId)
            .filter(p -> p.getStatus() == Status.Active)
            .map(PageAccessProfile::getProfileName).orElse(null);
    }

    @Override
    public ResponseDto requestAccess(String pageKey) throws Exception {
        Optional<PageKey> page = PageKey.fromKey(pageKey);
        if (!page.isPresent()) {
            return new ResponseDto(ERROR, "Unknown page.");
        }
        Long appUserId = TenantContext.getAppUserId();
        Long tenantId = TenantContext.getTenantId();
        if (isNull(appUserId) || isNull(tenantId)) {
            return new ResponseDto(ERROR, "No signed-in user.");
        }
        Optional<AppUser> me = this.appUserRepository.findById(appUserId);
        if (!me.isPresent()) {
            return new ResponseDto(ERROR, "User not found.");
        }
        if (this.effectivePages(me.get()).contains(page.get())) {
            return new ResponseDto(SUCCESS, String.format("You can already open %s.", page.get().getLabel()));
        }
        List<AppUser> admins = this.appUserRepository
            .findByTenantIdAndStatusNotOrderByAppUserIdDesc(tenantId, Status.Delete).stream()
            .filter(u -> u.getUserRole() == UserRole.TENANT_ADMIN && u.getStatus() == Status.Active)
            .collect(Collectors.toList());
        if (admins.isEmpty()) {
            return new ResponseDto(ERROR, "Your workspace has no administrator to ask.");
        }
        String who = String.format("%s (%s)", me.get().getFullName(), me.get().getUsername());
        for (AppUser admin : admins) {
            this.notificationCenterService.create(tenantId, admin.getAppUserId(),
                NotificationType.PAGE_ACCESS_REQUESTED, NotificationSeverity.INFO,
                "Page access requested",
                String.format("%s asked to open %s. Change their access profile under Users, or the profile itself under Access profiles.",
                    who, page.get().getLabel()),
                "/users");
        }
        return new ResponseDto(SUCCESS, String.format("Asked %s to open %s for you.",
            admins.size() == 1 ? admins.get(0).getFullName() : "your workspace admins", page.get().getLabel()));
    }

    // ---------------------------------------------------------------------------------------

    private ResponseDto validate(PageAccessProfileDto dto, Long tenantId, Long selfId) {
        if (isNull(dto.getProfileName()) || dto.getProfileName().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Profile name missing.");
        }
        if (dto.getProfileName().trim().length() > 100) {
            return new ResponseDto(ERROR, "Profile name is longer than 100 characters.");
        }
        if (!isNull(dto.getDescription()) && dto.getDescription().length() > 500) {
            return new ResponseDto(ERROR, "Description is longer than 500 characters.");
        }
        if (dto.getPageKeys() != null) {
            for (String key : dto.getPageKeys()) {
                if (!PageKey.fromKey(key).isPresent()) {
                    return new ResponseDto(ERROR, String.format("\"%s\" is not a page.", key));
                }
            }
        }
        Optional<PageAccessProfile> sameName = this.profileRepository
            .findByTenantIdAndProfileNameIgnoreCaseAndStatus(tenantId, dto.getProfileName().trim(), Status.Active);
        if (sameName.isPresent() && !sameName.get().getPageAccessProfileId().equals(selfId)) {
            return new ResponseDto(ERROR, String.format("A profile called \"%s\" already exists.", sameName.get().getProfileName()));
        }
        return null;
    }

    /** Active, and the caller's own workspace; a platform admin reaches every workspace's. */
    private Optional<PageAccessProfile> scopedFind(Long id) {
        Optional<PageAccessProfile> found = this.profileRepository.findById(id)
            .filter(p -> p.getStatus() == Status.Active);
        if (TenantContext.isPlatformAdmin()) {
            return found;
        }
        Long tenantId = TenantContext.getTenantId();
        if (isNull(tenantId)) {
            return Optional.empty();
        }
        return found.filter(p -> tenantId.equals(p.getTenantId()));
    }

    private void clearDefault(Long tenantId) {
        this.profileRepository.findByTenantIdAndDefaultProfileTrueAndStatus(tenantId, Status.Active)
            .ifPresent(current -> {
                current.setDefaultProfile(false);
                this.profileRepository.save(current);
                // Two rows flagged at once would trip the partial unique index at commit; the
                // flush makes the clear land before the new default is written.
                this.profileRepository.flush();
            });
    }

    private static Set<String> normaliseKeys(List<String> keys) {
        Set<String> out = new LinkedHashSet<>();
        if (keys == null) {
            return out;
        }
        for (String key : keys) {
            PageKey.fromKey(key).ifPresent(p -> out.add(p.getKey()));
        }
        return out;
    }

    private static List<String> keysOf(Set<PageKey> pages) {
        return PageKey.all().stream().filter(pages::contains).map(PageKey::getKey).collect(Collectors.toList());
    }

    private static String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private PageAccessProfileDto toDto(PageAccessProfile profile) {
        PageAccessProfileDto dto = new PageAccessProfileDto();
        dto.setPageAccessProfileId(profile.getPageAccessProfileId());
        dto.setTenantId(profile.getTenantId());
        dto.setProfileName(profile.getProfileName());
        dto.setDescription(profile.getDescription());
        dto.setDefaultProfile(profile.isDefaultProfile());
        dto.setStatus(profile.getStatus());
        dto.setPageKeys(keysOf(toPageKeys(profile.getPageKeys())));
        List<AppUser> holders = this.appUserRepository
            .findByPageAccessProfileIdAndStatusNot(profile.getPageAccessProfileId(), Status.Delete);
        dto.setUserCount((long) holders.size());
        dto.setUserNames(holders.stream().map(AppUser::getFullName).sorted().collect(Collectors.toList()));
        dto.setDateCreated(profile.getDateCreated());
        dto.setDateUpdated(profile.getDateUpdated());
        dto.setCreatedByName(profile.getCreatedByName());
        dto.setUpdatedByName(profile.getUpdatedByName());
        return dto;
    }
}

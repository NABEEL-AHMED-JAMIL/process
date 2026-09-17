package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.AccessPersonDto;
import process.model.dto.PageAccessProfileDto;
import process.model.dto.ResponseDto;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;
import process.model.enums.PageKey;
import process.model.enums.Status;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.PageAccessProfile;
import process.model.pojo.UserPageAccess;
import process.model.repository.AppUserRepository;
import process.model.repository.PageAccessProfileRepository;
import process.model.repository.TenantRepository;
import process.model.repository.UserPageAccessRepository;
import process.model.service.NotificationCenterService;
import process.model.service.PageAccessService;
import process.security.PageAccessCache;
import process.security.TenantContext;
import process.util.UserNameResolver;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
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
    private final UserPageAccessRepository exceptionRepository;

    public PageAccessServiceImpl(PageAccessProfileRepository profileRepository,
        AppUserRepository appUserRepository, NotificationCenterService notificationCenterService,
        UserNameResolver userNameResolver, PageAccessCache cache, TenantRepository tenantRepository,
        UserPageAccessRepository exceptionRepository) {
        this.profileRepository = profileRepository;
        this.appUserRepository = appUserRepository;
        this.notificationCenterService = notificationCenterService;
        this.userNameResolver = userNameResolver;
        this.cache = cache;
        this.tenantRepository = tenantRepository;
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
     * The rule itself, with the rows already in hand -- what effectivePages and the grid share.
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
        // One read of the workspace's people for every card, rather than one per card. Tenant
        // users only: an admin holds no profile, and counting them would say they were covered.
        List<AppUser> people = this.appUserRepository
            .findByTenantIdAndStatusNotOrderByAppUserIdDesc(tenantId, Status.Delete).stream()
            .filter(u -> u.getUserRole() == UserRole.TENANT_USER)
            .collect(Collectors.toList());
        Map<Long, List<AppUser>> holders = people.stream()
            .filter(u -> !isNull(u.getPageAccessProfileId()))
            .collect(Collectors.groupingBy(AppUser::getPageAccessProfileId));
        // The default covers everyone with no profile of their own too, and its card must say
        // so: "1 person" on a default four more people land on understated it by four.
        List<AppUser> onDefault = people.stream().filter(u -> isNull(u.getPageAccessProfileId())).collect(Collectors.toList());
        List<PageAccessProfileDto> dtos = profiles.stream()
            .map(p -> {
                PageAccessProfileDto dto = this.toDto(p, holders.getOrDefault(p.getPageAccessProfileId(), Collections.emptyList()));
                if (p.isDefaultProfile()) {
                    dto.setDefaultUserCount((long) onDefault.size());
                    dto.setDefaultUserNames(onDefault.stream().map(AppUser::getFullName).sorted().collect(Collectors.toList()));
                }
                return dto;
            })
            .collect(Collectors.toList());
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
    public ResponseDto listPeople(Long requestedTenantId) throws Exception {
        ResponseDto[] refused = new ResponseDto[1];
        Long tenantId = this.workspaceFor(requestedTenantId, refused);
        if (tenantId == null) {
            return refused[0];
        }
        // The workspace's profiles once, then every person resolves against the map: the grid
        // is the screen built to show everyone, so a query per row is the one thing it must not do.
        Map<Long, PageAccessProfile> profiles = this.profileRepository
            .findByTenantIdAndStatusOrderByProfileNameAsc(tenantId, Status.Active).stream()
            .collect(Collectors.toMap(PageAccessProfile::getPageAccessProfileId, p -> p));
        PageAccessProfile fallback = profiles.values().stream().filter(PageAccessProfile::isDefaultProfile).findFirst().orElse(null);
        List<AppUser> people = this.appUserRepository
            .findByTenantIdAndStatusNotOrderByAppUserIdDesc(tenantId, Status.Delete).stream()
            .filter(u -> u.getUserRole() == UserRole.TENANT_USER)
            .sorted(Comparator.comparing(u -> u.getFullName() == null ? "" : u.getFullName().toLowerCase()))
            .collect(Collectors.toList());
        // And everyone's exceptions in one read, likewise.
        Map<Long, List<UserPageAccess>> exceptions = people.isEmpty() ? Collections.emptyMap()
            : this.exceptionRepository.findByIdAppUserIdIn(people.stream().map(AppUser::getAppUserId).collect(Collectors.toList()))
                .stream().collect(Collectors.groupingBy(UserPageAccess::getAppUserId));
        List<AccessPersonDto> rows = people.stream()
            .map(u -> toPersonDto(u, profiles.get(u.getPageAccessProfileId()), fallback,
                exceptions.getOrDefault(u.getAppUserId(), Collections.emptyList())))
            .collect(Collectors.toList());
        return new ResponseDto(SUCCESS, "People fetched.", rows);
    }

    private static AccessPersonDto toPersonDto(AppUser person, PageAccessProfile own, PageAccessProfile fallback,
        Collection<UserPageAccess> exceptions) {
        AccessPersonDto dto = new AccessPersonDto();
        dto.setAppUserId(person.getAppUserId());
        dto.setFullName(person.getFullName());
        dto.setUsername(person.getUsername());
        dto.setPosition(person.getPosition());
        dto.setStatus(person.getStatus());
        dto.setAvatarKey(person.getAvatarKey());
        dto.setPageAccessProfileId(person.getPageAccessProfileId());
        dto.setPageAccessProfileName(own != null && own.getStatus() == Status.Active ? own.getProfileName() : null);
        dto.setPageKeys(keysOf(resolve(person, own, fallback, exceptions)));
        List<String> allowed = new ArrayList<>();
        List<String> withheld = new ArrayList<>();
        for (PageKey page : PageKey.values()) {
            for (UserPageAccess exception : exceptions) {
                if (page.getKey().equals(exception.getPageKey())) {
                    (exception.isAllowed() ? allowed : withheld).add(page.getKey());
                }
            }
        }
        dto.setAllowedExceptions(allowed);
        dto.setWithheldExceptions(withheld);
        return dto;
    }

    @Override
    @Transactional
    public ResponseDto assignProfile(Long appUserId, Long pageAccessProfileId) throws Exception {
        AppUser[] holder = new AppUser[1];
        ResponseDto refused = this.reachablePerson(appUserId, holder);
        if (refused != null) {
            return refused;
        }
        AppUser person = holder[0];
        ResponseDto badProfile = this.refuseUnusableProfile(pageAccessProfileId, person.getTenantId());
        if (badProfile != null) {
            return badProfile;
        }
        if (Objects.equals(person.getPageAccessProfileId(), pageAccessProfileId)) {
            return new ResponseDto(SUCCESS, "No change.", this.personRow(person));
        }
        person.setPageAccessProfileId(pageAccessProfileId);
        this.appUserRepository.save(person);
        this.notifyProfileChanged(person);
        String profileName = this.profileNameFor(pageAccessProfileId);
        return new ResponseDto(SUCCESS, String.format("%s is now on %s.", person.getFullName(),
            profileName == null ? "the workspace default" : "\"" + profileName + "\""), this.personRow(person));
    }

    private AccessPersonDto personRow(AppUser person) {
        PageAccessProfile own = isNull(person.getPageAccessProfileId()) ? null
            : this.profileRepository.findById(person.getPageAccessProfileId()).orElse(null);
        PageAccessProfile fallback = isNull(person.getTenantId()) ? null
            : this.profileRepository.findByTenantIdAndDefaultProfileTrueAndStatus(person.getTenantId(), Status.Active).orElse(null);
        return toPersonDto(person, own, fallback, this.exceptionRepository.findByIdAppUserId(person.getAppUserId()));
    }

    /** The person a management call is about, under the same reach as assignProfile. */
    private ResponseDto reachablePerson(Long appUserId, AppUser[] out) {
        if (isNull(appUserId)) {
            return new ResponseDto(ERROR, "User id missing.");
        }
        Optional<AppUser> found = this.appUserRepository.findById(appUserId)
            .filter(u -> u.getStatus() != Status.Delete);
        if (!found.isPresent()) {
            return new ResponseDto(ERROR, "User not found.");
        }
        AppUser person = found.get();
        if (!TenantContext.isPlatformAdmin()
            && (isNull(person.getTenantId()) || !person.getTenantId().equals(TenantContext.getTenantId()))) {
            return new ResponseDto(ERROR, "User not found.");
        }
        if (person.getUserRole() != UserRole.TENANT_USER) {
            return new ResponseDto(ERROR, "Only a tenant user holds an access profile; admins open every page.");
        }
        out[0] = person;
        return null;
    }

    @Override
    @Transactional
    public ResponseDto setPageAccess(Long appUserId, String pageKey, boolean allowed) throws Exception {
        Optional<PageKey> page = PageKey.fromKey(pageKey);
        if (!page.isPresent()) {
            return new ResponseDto(ERROR, "Unknown page.");
        }
        AppUser[] holder = new AppUser[1];
        ResponseDto refused = this.reachablePerson(appUserId, holder);
        if (refused != null) {
            return refused;
        }
        AppUser person = holder[0];
        PageAccessProfile own = isNull(person.getPageAccessProfileId()) ? null
            : this.profileRepository.findById(person.getPageAccessProfileId()).orElse(null);
        PageAccessProfile fallback = isNull(person.getTenantId()) ? null
            : this.profileRepository.findByTenantIdAndDefaultProfileTrueAndStatus(person.getTenantId(), Status.Active).orElse(null);
        boolean profileSays = profilePages(person, own, fallback).contains(page.get());
        if (profileSays == allowed) {
            // Back to what the profile says: the exception, if any, goes rather than being
            // stored as a difference that makes no difference.
            this.exceptionRepository.deleteOne(person.getAppUserId(), page.get().getKey());
        } else {
            this.exceptionRepository.save(new UserPageAccess(person.getAppUserId(), page.get().getKey(), allowed, TenantContext.getAppUserId()));
        }
        this.exceptionRepository.flush();
        this.cache.forget(person.getAppUserId());
        this.notificationCenterService.create(person.getTenantId(), person.getAppUserId(),
            NotificationType.PAGE_ACCESS_CHANGED, NotificationSeverity.INFO,
            "Your page access changed",
            String.format("%s is now %s for you.", page.get().getLabel(), allowed ? "open" : "withheld"),
            "/dashboard");
        return new ResponseDto(SUCCESS, String.format("%s is now %s for %s%s.", page.get().getLabel(),
            allowed ? "open" : "withheld", person.getFullName(), profileSays == allowed ? " (as their profile says)" : " (an exception to their profile)"),
            this.personRow(person));
    }

    @Override
    @Transactional
    public ResponseDto clearPageAccess(Long appUserId) throws Exception {
        AppUser[] holder = new AppUser[1];
        ResponseDto refused = this.reachablePerson(appUserId, holder);
        if (refused != null) {
            return refused;
        }
        int dropped = this.exceptionRepository.deleteAllFor(appUserId);
        this.exceptionRepository.flush();
        this.cache.forget(appUserId);
        if (dropped > 0) {
            this.notifyProfileChanged(holder[0]);
        }
        return new ResponseDto(SUCCESS, dropped == 0 ? "No exceptions to clear."
            : String.format("%s reads exactly as their profile again (%d %s cleared).", holder[0].getFullName(), dropped, dropped == 1 ? "exception" : "exceptions"),
            this.personRow(holder[0]));
    }

    @Override
    public void notifyProfileChanged(AppUser user) {
        this.cache.forget(user.getAppUserId());
        if (user.getUserRole() != UserRole.TENANT_USER) {
            return;
        }
        String profileName = this.profileNameFor(user.getPageAccessProfileId());
        this.notificationCenterService.create(user.getTenantId(), user.getAppUserId(),
            NotificationType.PAGE_ACCESS_CHANGED, NotificationSeverity.INFO,
            "Your page access changed",
            profileName == null
                ? "You are on your workspace's default access profile now. Your menu shows what it opens."
                : String.format("You are on the \"%s\" access profile now. Your menu shows what it opens.", profileName),
            "/dashboard");
    }

    @Override
    public Map<Long, AccessSummary> accessSummaryFor(Collection<AppUser> users) {
        Map<Long, AccessSummary> out = new HashMap<>();
        if (users == null || users.isEmpty()) {
            return out;
        }
        Set<Long> profileIds = users.stream().map(AppUser::getPageAccessProfileId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, PageAccessProfile> profiles = profileIds.isEmpty() ? Collections.emptyMap()
            : this.profileRepository.findAllById(profileIds).stream()
                .collect(Collectors.toMap(PageAccessProfile::getPageAccessProfileId, p -> p));
        Set<Long> tenantIds = users.stream().map(AppUser::getTenantId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, PageAccessProfile> defaults = new HashMap<>();
        for (Long tenantId : tenantIds) {
            this.profileRepository.findByTenantIdAndDefaultProfileTrueAndStatus(tenantId, Status.Active)
                .ifPresent(p -> defaults.put(tenantId, p));
        }
        List<Long> userIds = users.stream().filter(u -> u.getUserRole() == UserRole.TENANT_USER)
            .map(AppUser::getAppUserId).collect(Collectors.toList());
        Map<Long, List<UserPageAccess>> exceptions = userIds.isEmpty() ? Collections.emptyMap()
            : this.exceptionRepository.findByIdAppUserIdIn(userIds).stream().collect(Collectors.groupingBy(UserPageAccess::getAppUserId));
        for (AppUser user : users) {
            if (user.getUserRole() != UserRole.TENANT_USER) {
                out.put(user.getAppUserId(), new AccessSummary(null, null, PageKey.values().length, 0));
                continue;
            }
            PageAccessProfile own = profiles.get(user.getPageAccessProfileId());
            List<UserPageAccess> mine = exceptions.getOrDefault(user.getAppUserId(), Collections.emptyList());
            Set<PageKey> pages = resolve(user, own, defaults.get(user.getTenantId()), mine);
            String name = own != null && own.getStatus() == Status.Active ? own.getProfileName() : null;
            PageAccessProfile fallback = defaults.get(user.getTenantId());
            out.put(user.getAppUserId(), new AccessSummary(name, fallback == null ? null : fallback.getProfileName(), pages.size(), mine.size()));
        }
        return out;
    }

    @Override
    public Map<Long, String> profileNamesFor(Collection<Long> pageAccessProfileIds) {
        Set<Long> ids = pageAccessProfileIds == null ? Collections.emptySet()
            : pageAccessProfileIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Collections.emptyMap();
        }
        return this.profileRepository.findAllById(ids).stream()
            .filter(p -> p.getStatus() == Status.Active)
            .collect(Collectors.toMap(PageAccessProfile::getPageAccessProfileId, PageAccessProfile::getProfileName));
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
        return this.toDto(profile, this.appUserRepository
            .findByPageAccessProfileIdAndStatusNot(profile.getPageAccessProfileId(), Status.Delete));
    }

    private PageAccessProfileDto toDto(PageAccessProfile profile, List<AppUser> holders) {
        PageAccessProfileDto dto = new PageAccessProfileDto();
        dto.setPageAccessProfileId(profile.getPageAccessProfileId());
        dto.setTenantId(profile.getTenantId());
        dto.setProfileName(profile.getProfileName());
        dto.setDescription(profile.getDescription());
        dto.setDefaultProfile(profile.isDefaultProfile());
        dto.setStatus(profile.getStatus());
        dto.setPageKeys(keysOf(toPageKeys(profile.getPageKeys())));
        dto.setUserCount((long) holders.size());
        dto.setUserNames(holders.stream().map(AppUser::getFullName).sorted().collect(Collectors.toList()));
        dto.setDateCreated(profile.getDateCreated());
        dto.setDateUpdated(profile.getDateUpdated());
        dto.setCreatedByName(profile.getCreatedByName());
        dto.setUpdatedByName(profile.getUpdatedByName());
        return dto;
    }
}

package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.AppUserDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.enums.TenantStatus;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.Tenant;
import process.model.repository.AppUserRepository;
import process.model.repository.TenantRepository;
import process.model.service.AppUserService;
import process.model.service.NotificationCenterService;
import process.model.service.StorageBrowserService;
import process.security.TenantContext;
import process.security.TenantOwnership;
import process.emailer.EmailMessagesFactory;
import process.util.TemporaryPassword;
import process.util.PhoneNumberValidator;
import process.util.UserNameResolver;
import org.springframework.beans.factory.annotation.Value;
import process.config.StoragePropertyDefaults;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.*;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class AppUserServiceImpl implements AppUserService {

    private final Logger logger = LoggerFactory.getLogger(AppUserServiceImpl.class);

    private static final String NOT_FOUND_MESSAGE = "User not found.";
    private static final String PEER_ADMIN_MESSAGE = "Only a Platform Admin can manage another Tenant Admin.";

    private final AppUserRepository appUserRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailMessagesFactory emailMessagesFactory;
    private final UserNameResolver userNameResolver;

    /**
     * Where every profile picture goes, whichever workspace the person belongs to.
     *
     * One bucket rather than each tenant's own: a picture is the person's, not the workspace's,
     * and a tenant created a minute ago has no storage of its own yet -- which used to leave its
     * first user unable to upload at all.
     *
     * Reachable despite the isolation rules by exactly one route: the storage guard lets any
     * caller act on its own <appUserId>/profile/ object in a platform bucket and refuses
     * everything else there. Reading somebody ELSE's picture goes through readAvatar below rather
     * than the object browser, because the browser would -- correctly -- refuse it. The bucket
     * stays out of listBuckets either way, so it is usable without being browsable.
     */
    @Value(StoragePropertyDefaults.AVATAR_BUCKET)
    private String avatarBucket;

    @Value("${app.console.url:http://localhost:4400}")
    private String consoleUrl;

    private final StorageBrowserService storageBrowserService;

    private final NotificationCenterService notificationCenterService;

    public AppUserServiceImpl(AppUserRepository appUserRepository, TenantRepository tenantRepository,
        PasswordEncoder passwordEncoder, EmailMessagesFactory emailMessagesFactory,
        UserNameResolver userNameResolver, StorageBrowserService storageBrowserService,
        NotificationCenterService notificationCenterService) {
        this.notificationCenterService = notificationCenterService;
        this.storageBrowserService = storageBrowserService;
        this.emailMessagesFactory = emailMessagesFactory;
        this.userNameResolver = userNameResolver;
        this.appUserRepository = appUserRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Somebody's profile picture, resolved from their id rather than from a key the browser names.
     *
     * The users screen shows a face per row, and it used to fetch each one straight from the object
     * browser. Once platform buckets stopped being readable by anyone who could guess a key -- which
     * is the whole point, since the ids in those keys run in sequence -- every avatar but the
     * viewer's own stopped loading.
     *
     * So the question moved: the caller names a person, not an object. Whoever appears on their
     * users screen is whose face they may see, which is the same scope listUsers already applies,
     * and the key is read off that row rather than accepted from the request. That makes the
     * trusted read sound here for the same reason it is sound elsewhere -- nothing about the
     * destination came from the caller.
     */
    @Override
    @Transactional(readOnly = true)
    public ObjectContentDto readAvatar(Long appUserId) {
        if (isNull(appUserId)) {
            return null;
        }
        Optional<AppUser> userOpt = this.appUserRepository.findById(appUserId);
        if (!userOpt.isPresent() || userOpt.get().getStatus() == Status.Delete) {
            return null;
        }
        AppUser user = userOpt.get();
        // Asked through the shared rule rather than compared here. Comparing the two ids directly
        // let one case fall open: a caller carrying no tenant of its own matched a platform
        // admin's tenant-less row, because null equals null, and was handed that person's picture.
        // TenantOwnership refuses a tenant-less caller and a platform-owned row alike, which is
        // the same answer scopedFind below already gives.
        if (!TenantOwnership.isOwnedByCaller(user.getTenantId())) {
            return null;
        }
        if (isNull(user.getAvatarKey()) || isNull(user.getAvatarBucket())) {
            return null;
        }
        return this.storageBrowserService.readForWorkflow(user.getAvatarBucket(), user.getAvatarKey());
    }

    @Override
    public ResponseDto listUsers() throws Exception {
        List<AppUser> users = TenantContext.isPlatformAdmin()
            ? this.appUserRepository.findAll().stream()
                .filter(u -> u.getStatus() != Status.Delete)
                .collect(Collectors.toList())
            : this.appUserRepository.findByTenantIdAndStatusNotOrderByAppUserIdDesc(TenantContext.getTenantId(), Status.Delete);
        // One lookup for the whole page rather than one per row.
        this.userNameResolver.attachNames(users);
        List<AppUserDto> dtos = this.mapToDtoList(users);
        return new ResponseDto(SUCCESS, "Users fetched successfully.", dtos);
    }

    @Override
    @Transactional
    public ResponseDto addUser(AppUserDto appUserDto) throws Exception {
        if (isNull(appUserDto.getUsername()) || appUserDto.getUsername().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Username missing.");
        } else if (isNull(appUserDto.getFullName()) || appUserDto.getFullName().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Full name missing.");
        } else if (isNull(appUserDto.getUserRole())) {
            return new ResponseDto(ERROR, "User role missing.");
        }
        // Normalised before anything is written, so what lands in the column is always E.164 --
        // never the spacing and brackets somebody pasted from their contacts.
        PhoneNumberValidator.Result phone = PhoneNumberValidator.normalise(appUserDto.getPhoneNumber());
        if (!phone.isValid()) {
            return new ResponseDto(ERROR, phone.getError());
        }
        boolean isPlatformAdminActor = TenantContext.isPlatformAdmin();
        if (!isPlatformAdminActor && appUserDto.getUserRole() == UserRole.PLATFORM_ADMIN) {
            return new ResponseDto(ERROR, "Only a Platform Admin can create another Platform Admin.");
        }
        // A tenant admin staffs its own workspace with tenant users. A second administrator is a
        // second set of keys to everything the workspace holds, so who gets one is the platform's
        // decision rather than something an admin can grant itself a colleague.
        if (!isPlatformAdminActor && appUserDto.getUserRole() != UserRole.TENANT_USER) {
            return new ResponseDto(ERROR, "Only a Platform Admin can create another Tenant Admin.");
        }
        Long targetTenantId;
        if (appUserDto.getUserRole() == UserRole.PLATFORM_ADMIN) {
            targetTenantId = null;
        } else if (isPlatformAdminActor) {
            if (isNull(appUserDto.getTenantId())) {
                return new ResponseDto(ERROR, "Tenant missing.");
            }
            targetTenantId = appUserDto.getTenantId();
        } else {

            targetTenantId = TenantContext.getTenantId();
        }
        if (!isNull(targetTenantId) && !this.tenantRepository.findById(targetTenantId).isPresent()) {
            return new ResponseDto(ERROR, String.format("Tenant not found with %d.", targetTenantId));
        }
        if (this.appUserRepository.findByUsernameAndStatusNot(appUserDto.getUsername().trim(), Status.Delete).isPresent()) {
            return new ResponseDto(ERROR, String.format("Username \"%s\" is already in use.", appUserDto.getUsername().trim()));
        }
        // Blank means the server picks one. That is the better path -- it is random, it is
        // strong, and nobody but its owner ever reads it -- so the console leaves the field
        // empty by default and only the new user learns the value.
        boolean generated = isNull(appUserDto.getPassword()) || appUserDto.getPassword().trim().isEmpty();
        String temporaryPassword = generated ? TemporaryPassword.generate() : appUserDto.getPassword();

        AppUser user = new AppUser();
        user.setUuid(UUID.randomUUID().toString());
        user.setTenantId(targetTenantId);
        user.setUsername(appUserDto.getUsername().trim());
        user.setPassword(this.passwordEncoder.encode(temporaryPassword));
        user.setFullName(appUserDto.getFullName().trim());
        user.setUserRole(appUserDto.getUserRole());
        user.setPhoneNumber(phone.getValue());
        user.setPosition(trimToNull(appUserDto.getPosition()));
        user.setStatus(Status.Active);
        user.setMustChangePassword(generated);
        user.setDateCreated(new Timestamp(System.currentTimeMillis()));
        this.appUserRepository.save(user);

        String mailResult = notifyNewUser(user, generated ? temporaryPassword : null, targetTenantId);
        if (mailResult != null && mailResult.startsWith("Error")) {
            // The account exists either way. When the password was generated this was the only
            // moment it was readable, so say plainly that it has to be reset rather than
            // reporting a clean success.
            logger.error("User {} was created but the welcome email could not be sent.", user.getAppUserId());
            return new ResponseDto(SUCCESS, generated
                ? String.format("User \"%s\" created, but the welcome email could not be sent. "
                    + "Reset their password and pass it on another way.", user.getUsername())
                : String.format("User \"%s\" created, but the welcome email could not be sent.",
                    user.getUsername()),
                this.mapToDto(user));
        }
        return new ResponseDto(SUCCESS, String.format(
            "User \"%s\" created and emailed how to sign in.", user.getUsername()), this.mapToDto(user));
    }

    /**
     * Tells the new user their account exists.
     *
     * The organisation name is what the recipient recognises, so a tenant's name is looked up
     * rather than printing an id. A platform admin belongs to no tenant, hence the fallback.
     */
    private String notifyNewUser(AppUser user, String temporaryPassword, Long tenantId) {
        String organisation = "ETL Console";
        if (!isNull(tenantId)) {
            organisation = this.tenantRepository.findById(tenantId)
                .map(Tenant::getTenantName).orElse(organisation);
        }
        String createdBy = this.appUserRepository.findById(TenantContext.getAppUserId())
            .map(AppUser::getFullName).orElse("An administrator");
        return this.emailMessagesFactory.sendUserWelcomeEmail(user.getUsername(),
            user.getFullName(), organisation, user.getUsername(), temporaryPassword,
            roleLabel(user.getUserRole()), createdBy, this.consoleUrl + "/login");
    }

    /** The role as the recipient would say it, not as the enum spells it. */
    private static String roleLabel(UserRole role) {
        if (role == UserRole.PLATFORM_ADMIN) {
            return "Platform admin";
        } else if (role == UserRole.TENANT_ADMIN) {
            return "Tenant admin";
        }
        return "Tenant user";
    }

    @Override
    public ResponseDto updateUser(AppUserDto appUserDto) throws Exception {
        if (isNull(appUserDto.getAppUserId())) {
            return new ResponseDto(ERROR, "User id missing.");
        } else if (isNull(appUserDto.getFullName()) || appUserDto.getFullName().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Full name missing.");
        }
        Optional<AppUser> userOpt = this.scopedFind(appUserDto.getAppUserId());
        if (!userOpt.isPresent()) {
            return new ResponseDto(ERROR, this.refusalFor(appUserDto.getAppUserId()));
        }
        AppUser user = userOpt.get();
        // Normalised before anything is written, so what lands in the column is always E.164 --
        // never the spacing and brackets somebody pasted from their contacts.
        PhoneNumberValidator.Result phone = PhoneNumberValidator.normalise(appUserDto.getPhoneNumber());
        if (!phone.isValid()) {
            return new ResponseDto(ERROR, phone.getError());
        }
        boolean isPlatformAdminActor = TenantContext.isPlatformAdmin();
        UserRole effectiveRole = !isNull(appUserDto.getUserRole()) ? appUserDto.getUserRole() : user.getUserRole();
        if (!isNull(appUserDto.getUserRole())) {
            // The counterpart to changeUserStatus refusing to deactivate your own account. Your
            // own row is reachable here so you can correct your name on it, and nothing stopped a
            // different role riding along in the same form -- a platform admin that posts a lower
            // one has no way back, because the screen that grants the role is the one it just
            // lost. Resubmitting the role already held is not a change and stays allowed.
            if (user.getAppUserId().equals(TenantContext.getAppUserId())
                && appUserDto.getUserRole() != user.getUserRole()) {
                return new ResponseDto(ERROR, "You cannot change your own role.");
            }
            if (!isPlatformAdminActor && appUserDto.getUserRole() == UserRole.PLATFORM_ADMIN) {
                return new ResponseDto(ERROR, "Only a Platform Admin can grant the Platform Admin role.");
            }
            // Same rule as addUser: a tenant admin may not hand out its own level. Resubmitting
            // the role the row already carries is not granting anything, and the console sends
            // the whole form back on every edit -- so that stays allowed, or an admin could no
            // longer correct its own name here.
            if (!isPlatformAdminActor && appUserDto.getUserRole() != UserRole.TENANT_USER
                && appUserDto.getUserRole() != user.getUserRole()) {
                return new ResponseDto(ERROR, "Only a Platform Admin can grant the Tenant Admin role.");
            }
        }

        Long effectiveTenantId;
        if (effectiveRole == UserRole.PLATFORM_ADMIN) {
            effectiveTenantId = null;
        } else if (isPlatformAdminActor && !isNull(appUserDto.getTenantId())) {
            effectiveTenantId = appUserDto.getTenantId();
        } else {
            effectiveTenantId = user.getTenantId();
        }

        if (effectiveRole != UserRole.PLATFORM_ADMIN && isNull(effectiveTenantId)) {
            return new ResponseDto(ERROR, "A tenant is required for this role -- assign one before removing Platform Admin.");
        }
        if (!isNull(effectiveTenantId) && !effectiveTenantId.equals(user.getTenantId())
            && !this.tenantRepository.findById(effectiveTenantId).isPresent()) {
            return new ResponseDto(ERROR, String.format("Tenant not found with %d.", effectiveTenantId));
        }
        user.setUserRole(effectiveRole);
        user.setPhoneNumber(phone.getValue());
        user.setTenantId(effectiveTenantId);
        user.setFullName(appUserDto.getFullName().trim());
        user.setPosition(trimToNull(appUserDto.getPosition()));
        this.appUserRepository.save(user);
        return new ResponseDto(SUCCESS, String.format("User \"%s\" updated.", user.getUsername()), this.mapToDto(user));
    }

    @Override
    public ResponseDto changeUserStatus(AppUserDto appUserDto) throws Exception {
        if (isNull(appUserDto.getAppUserId())) {
            return new ResponseDto(ERROR, "User id missing.");
        } else if (isNull(appUserDto.getStatus())) {
            return new ResponseDto(ERROR, "Status missing.");
        }
        Optional<AppUser> userOpt = this.scopedFind(appUserDto.getAppUserId());
        if (!userOpt.isPresent()) {
            return new ResponseDto(ERROR, this.refusalFor(appUserDto.getAppUserId()));
        }
        if (userOpt.get().getAppUserId().equals(TenantContext.getAppUserId()) && appUserDto.getStatus() != Status.Active) {
            return new ResponseDto(ERROR, "You cannot deactivate your own account.");
        }
        AppUser user = userOpt.get();
        user.setStatus(appUserDto.getStatus());
        this.appUserRepository.save(user);
        /*
         * The cached unread counter goes with the account.
         *
         * notif:unread:<id> is a Redis key with no expiry, written whenever a notification is
         * created. Nothing removed it, so every account that was ever deactivated left its key
         * behind for good -- eighteen orphans on this instance already. Dropping it here is
         * safe in both directions: a deactivated user has no badge to corrupt, and unreadCount
         * recounts from the database on a miss, so reactivating the account rebuilds the number
         * rather than showing a stale one.
         */
        if (appUserDto.getStatus() != Status.Active) {
            this.notificationCenterService.clearUnreadCount(user.getAppUserId());
        }
        return new ResponseDto(SUCCESS, String.format("User \"%s\" is now %s.", user.getUsername(), user.getStatus()));
    }

    @Override
    public ResponseDto resetPassword(AppUserDto appUserDto) throws Exception {
        if (isNull(appUserDto.getAppUserId())) {
            return new ResponseDto(ERROR, "User id missing.");
        } else if (isNull(appUserDto.getPassword()) || appUserDto.getPassword().trim().isEmpty()) {
            return new ResponseDto(ERROR, "New password missing.");
        }
        ResponseDto weakPassword = validateNewPassword(appUserDto.getPassword());
        if (weakPassword != null) {
            return weakPassword;
        }
        Optional<AppUser> userOpt = this.scopedFind(appUserDto.getAppUserId());
        if (!userOpt.isPresent()) {
            return new ResponseDto(ERROR, this.refusalFor(appUserDto.getAppUserId()));
        }
        AppUser user = userOpt.get();
        user.setPassword(this.passwordEncoder.encode(appUserDto.getPassword()));
        // The administrator who typed it knows it, so it is a temporary password like the one
        // addUser generates -- the account owes a change before it is theirs alone again.
        user.setMustChangePassword(true);
        this.appUserRepository.save(user);
        return new ResponseDto(SUCCESS, String.format("Password reset for \"%s\".", user.getUsername()));
    }

    /**
     * The one length rule about a new password, wherever it is set.
     *
     * It used to live only in changeOwnPassword, which meant the path a person chose for
     * themselves was held to eight characters and the path an administrator imposed on them was
     * held to nothing at all. Returns null when the password is acceptable.
     */
    private static ResponseDto validateNewPassword(String newPassword) {
        if (isNull(newPassword) || newPassword.length() < 8) {
            return new ResponseDto(ERROR, "Choose a new password of at least 8 characters.");
        }
        return null;
    }

    private Optional<AppUser> scopedFind(Long appUserId) {
        Optional<AppUser> userOpt = this.appUserRepository.findById(appUserId);
        if (!userOpt.isPresent() || userOpt.get().getStatus() == Status.Delete) {
            return Optional.empty();
        }
        if (TenantContext.isPlatformAdmin()) {
            return userOpt;
        }
        Long actorTenantId = TenantContext.getTenantId();
        Long targetTenantId = userOpt.get().getTenantId();
        if (targetTenantId == null || !targetTenantId.equals(actorTenantId)) {
            return Optional.empty();
        }
        // Sharing a tenant is not enough: a tenant admin manages tenant users, not its peers.
        // Without this, resetPassword would hand one administrator a working password for
        // another's account and defeat whatever two-person control the workspace thought it had.
        // Their own row stays reachable so they can still edit themselves from this screen.
        AppUser target = userOpt.get();
        if (target.getUserRole() != UserRole.TENANT_USER
            && !target.getAppUserId().equals(TenantContext.getAppUserId())) {
            return Optional.empty();
        }
        return userOpt;
    }

    /**
     * Why a scoped lookup came back empty, said in words the caller can act on.
     *
     * A peer administrator is on the users screen -- fetchAllUsers returns the whole tenant --
     * so answering "user not found" about a row somebody is looking at reads as a fault in the
     * product rather than as the rule it is. Everything else keeps the flat not-found, which is
     * what stops a caller learning who exists outside its own tenant.
     */
    private String refusalFor(Long appUserId) {
        if (TenantContext.isPlatformAdmin()) {
            return NOT_FOUND_MESSAGE;
        }
        Optional<AppUser> userOpt = this.appUserRepository.findById(appUserId);
        if (!userOpt.isPresent() || userOpt.get().getStatus() == Status.Delete) {
            return NOT_FOUND_MESSAGE;
        }
        Long targetTenantId = userOpt.get().getTenantId();
        if (targetTenantId == null || !targetTenantId.equals(TenantContext.getTenantId())) {
            return NOT_FOUND_MESSAGE;
        }
        return PEER_ADMIN_MESSAGE;
    }

    private List<AppUserDto> mapToDtoList(List<AppUser> users) {
        Set<Long> tenantIds = users.stream().map(AppUser::getTenantId)
            .filter(tenantId -> !isNull(tenantId)).collect(Collectors.toSet());
        Map<Long, Tenant> tenantById = tenantIds.isEmpty() ? Collections.emptyMap()
            : this.tenantRepository.findAllById(tenantIds).stream()
                .collect(Collectors.toMap(Tenant::getTenantId, t -> t));
        return users.stream().map(user -> {
            AppUserDto dto = this.mapToDtoWithoutTenantName(user);
            if (!isNull(user.getTenantId())) {
                this.applyTenantInfo(dto, tenantById.get(user.getTenantId()));
            }
            return dto;
        }).collect(Collectors.toList());
    }

    /**
     * The signed-in user's own record. Reads the id from the token, never from the request,
     * so "me" cannot be pointed at somebody else.
     */
    @Override
    @Transactional(readOnly = true)
    public ResponseDto currentUser() throws Exception {
        Long appUserId = TenantContext.getAppUserId();
        if (isNull(appUserId)) {
            return new ResponseDto(ERROR, "No signed-in user.");
        }
        Optional<AppUser> user = this.appUserRepository.findById(appUserId);
        if (!user.isPresent() || user.get().getStatus() == Status.Delete) {
            return new ResponseDto(ERROR, "User not found.");
        }
        return new ResponseDto(SUCCESS, "Profile found.", this.mapToDto(user.get()));
    }

    /**
     * Lets someone change their own display name. Deliberately narrow: role, status and
     * tenant are not editable here, or a tenant user could promote themselves by posting a
     * fuller payload to their own profile.
     */
    @Override
    @Transactional
    public ResponseDto updateOwnProfile(AppUserDto appUserDto) throws Exception {
        Long appUserId = TenantContext.getAppUserId();
        if (isNull(appUserId)) {
            return new ResponseDto(ERROR, "No signed-in user.");
        }
        if (isNull(appUserDto.getFullName()) || appUserDto.getFullName().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Full name is required.");
        }
        Optional<AppUser> found = this.appUserRepository.findById(appUserId);
        if (!found.isPresent() || found.get().getStatus() == Status.Delete) {
            return new ResponseDto(ERROR, "User not found.");
        }
        // Validated through the same path addUser and updateUser use, so a number set here and
        // one set by an administrator are held identically. Rejecting before anything is written
        // means a bad number does not also lose the name change in the same request.
        PhoneNumberValidator.Result phone = PhoneNumberValidator.normalise(appUserDto.getPhoneNumber());
        if (!phone.isValid()) {
            return new ResponseDto(ERROR, phone.getError());
        }
        AppUser user = found.get();
        user.setFullName(appUserDto.getFullName().trim());
        // Someone's own title is theirs to correct; the permission role is not.
        user.setPosition(trimToNull(appUserDto.getPosition()));
        user.setPhoneNumber(phone.getValue());
        this.appUserRepository.save(user);
        return new ResponseDto(SUCCESS, "Profile updated.", this.mapToDto(user));
    }

    /**
     * Records where the picture was uploaded. The upload itself goes through the storage
     * endpoints, which already enforce what the caller may write; this only stores the
     * pointer. Passing no key clears the picture.
     *
     * The pointer is checked here all the same. The storage rule covers who may *write* an
     * object, and says nothing about which object somebody may claim as theirs -- so without
     * this, naming a neighbour's key would put their face on your account everywhere it renders.
     */
    @Override
    @Transactional
    public ResponseDto updateOwnAvatar(AppUserDto appUserDto) throws Exception {
        Long appUserId = TenantContext.getAppUserId();
        if (isNull(appUserId)) {
            return new ResponseDto(ERROR, "No signed-in user.");
        }
        Optional<AppUser> found = this.appUserRepository.findById(appUserId);
        if (!found.isPresent() || found.get().getStatus() == Status.Delete) {
            return new ResponseDto(ERROR, "User not found.");
        }
        String key = appUserDto.getAvatarKey();
        boolean clearing = isNull(key) || key.trim().isEmpty();
        if (!clearing && !isOwnProfileKey(appUserId, key.trim())) {
            return new ResponseDto(ERROR, "That is not your own picture.");
        }
        // The bucket is the server's decision, not the caller's. It used to be whatever the
        // browser sent, which meant a client could record a picture as living anywhere it could
        // name -- and meant the answer changed with whatever the bucket picker happened to
        // offer. Every picture goes to the configured avatar bucket.
        AppUser user = found.get();
        user.setAvatarBucket(clearing ? null : this.avatarBucket);
        user.setAvatarKey(clearing ? null : key.trim());
        this.appUserRepository.save(user);
        return new ResponseDto(SUCCESS, clearing ? "Picture removed." : "Picture updated.",
            this.mapToDto(user));
    }

    /**
     * Whether a key names an object in the caller's own profile folder.
     *
     * The same shape the storage side allows a tenant user to write -- <appUserId>/profile/ --
     * with the file name required to be a plain one, so a key cannot climb back out of the
     * folder it claims to be in.
     */
    private static boolean isOwnProfileKey(Long appUserId, String key) {
        String prefix = appUserId + "/profile/";
        if (!key.startsWith(prefix)) {
            return false;
        }
        String fileName = key.substring(prefix.length());
        return !fileName.isEmpty() && fileName.indexOf('/') < 0
            && !".".equals(fileName) && !"..".equals(fileName);
    }

    private AppUserDto mapToDto(AppUser user) {
        AppUserDto dto = this.mapToDtoWithoutTenantName(user);
        if (!isNull(user.getTenantId())) {
            this.applyTenantInfo(dto, this.tenantRepository.findById(user.getTenantId()).orElse(null));
        }
        return dto;
    }

    private void applyTenantInfo(AppUserDto dto, Tenant tenant) {
        if (tenant == null) {
            dto.setTenantActive(false);
            return;
        }
        dto.setTenantName(tenant.getTenantName());
        dto.setTenantActive(tenant.getStatus() == TenantStatus.Active);
    }

    private AppUserDto mapToDtoWithoutTenantName(AppUser user) {
        AppUserDto dto = new AppUserDto();
        dto.setAppUserId(user.getAppUserId());
        dto.setUuid(user.getUuid());
        dto.setTenantId(user.getTenantId());
        dto.setUsername(user.getUsername());
        dto.setFullName(user.getFullName());
        dto.setUserRole(user.getUserRole());
        dto.setPosition(user.getPosition());
        dto.setMustChangePassword(user.isMustChangePassword());
        dto.setStatus(user.getStatus());
        dto.setAvatarBucket(user.getAvatarBucket());
        dto.setAvatarKey(user.getAvatarKey());
        dto.setDateCreated(user.getDateCreated());
        dto.setLastLoginAt(user.getLastLoginAt());
        dto.setPhoneNumber(user.getPhoneNumber());
        // Told to every caller so nothing has to hardcode it, and so changing the property moves
        // every future upload without a frontend release.
        dto.setAvatarUploadBucket(this.avatarBucket);
        dto.setCreatedByName(user.getCreatedByName());
        dto.setUpdatedByName(user.getUpdatedByName());
        dto.setCreatedBy(user.getCreatedBy());

        return dto;
    }


    /** A blank title is no title, so it is stored as null rather than an empty string. */
    private static String trimToNull(String value) {
        return (value == null || value.trim().isEmpty()) ? null : value.trim();
    }

    /**
     * Lets someone replace their own password.
     *
     * Separate from updateOwnProfile, which changes what a person is called rather than how they
     * prove who they are. The current password is required even though the caller is already
     * signed in: it is what stops a walk-up at an unlocked screen from taking the account, and
     * an account created with a generated password proves it holds that generated one.
     */
    @Override
    public ResponseDto changeOwnPassword(String currentPassword, String newPassword) throws Exception {
        Long appUserId = TenantContext.getAppUserId();
        if (isNull(appUserId)) {
            return new ResponseDto(ERROR, "No signed-in user.");
        }
        if (isNull(currentPassword) || currentPassword.isEmpty()) {
            return new ResponseDto(ERROR, "Enter your current password.");
        }
        ResponseDto weakPassword = validateNewPassword(newPassword);
        if (weakPassword != null) {
            return weakPassword;
        }
        if (newPassword.equals(currentPassword)) {
            return new ResponseDto(ERROR, "The new password has to differ from the current one.");
        }
        Optional<AppUser> found = this.appUserRepository.findById(appUserId);
        if (!found.isPresent() || found.get().getStatus() == Status.Delete) {
            return new ResponseDto(ERROR, "User not found.");
        }
        AppUser user = found.get();
        if (!this.passwordEncoder.matches(currentPassword, user.getPassword())) {
            // Deliberately vague about which half was wrong.
            return new ResponseDto(ERROR, "That is not your current password.");
        }
        user.setPassword(this.passwordEncoder.encode(newPassword));
        // Whatever it was created with has now been replaced, so the account owes nothing.
        user.setMustChangePassword(false);
        this.appUserRepository.save(user);
        return new ResponseDto(SUCCESS, "Your password has been changed.");
    }
}

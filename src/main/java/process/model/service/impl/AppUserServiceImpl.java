package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.AppUserDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.enums.TenantStatus;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.Tenant;
import process.model.repository.AppUserRepository;
import process.model.repository.TenantRepository;
import process.model.service.AppUserService;
import process.security.TenantContext;
import process.emailer.EmailMessagesFactory;
import process.util.TemporaryPassword;
import process.util.PhoneNumberValidator;
import process.util.UserNameResolver;
import org.springframework.beans.factory.annotation.Value;
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
     * Safe despite the isolation rules because resolveService only refuses a connection owned by
     * a *different* tenant; one with no tenant is reachable by everybody. And it stays out of
     * listBuckets, so the bucket is writable without being browsable.
     */
    @Value("${app.avatar.bucket:etl-avatar}")
    private String avatarBucket;

    @Value("${app.console.url:http://localhost:4400}")
    private String consoleUrl;

    public AppUserServiceImpl(AppUserRepository appUserRepository, TenantRepository tenantRepository,
        PasswordEncoder passwordEncoder, EmailMessagesFactory emailMessagesFactory,
        UserNameResolver userNameResolver) {
        this.emailMessagesFactory = emailMessagesFactory;
        this.userNameResolver = userNameResolver;
        this.appUserRepository = appUserRepository;
        this.tenantRepository = tenantRepository;
        this.passwordEncoder = passwordEncoder;
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
            return new ResponseDto(ERROR, NOT_FOUND_MESSAGE);
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
            if (!isPlatformAdminActor && appUserDto.getUserRole() == UserRole.PLATFORM_ADMIN) {
                return new ResponseDto(ERROR, "Only a Platform Admin can grant the Platform Admin role.");
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
            return new ResponseDto(ERROR, NOT_FOUND_MESSAGE);
        }
        if (userOpt.get().getAppUserId().equals(TenantContext.getAppUserId()) && appUserDto.getStatus() != Status.Active) {
            return new ResponseDto(ERROR, "You cannot deactivate your own account.");
        }
        AppUser user = userOpt.get();
        user.setStatus(appUserDto.getStatus());
        this.appUserRepository.save(user);
        return new ResponseDto(SUCCESS, String.format("User \"%s\" is now %s.", user.getUsername(), user.getStatus()));
    }

    @Override
    public ResponseDto resetPassword(AppUserDto appUserDto) throws Exception {
        if (isNull(appUserDto.getAppUserId())) {
            return new ResponseDto(ERROR, "User id missing.");
        } else if (isNull(appUserDto.getPassword()) || appUserDto.getPassword().trim().isEmpty()) {
            return new ResponseDto(ERROR, "New password missing.");
        }
        Optional<AppUser> userOpt = this.scopedFind(appUserDto.getAppUserId());
        if (!userOpt.isPresent()) {
            return new ResponseDto(ERROR, NOT_FOUND_MESSAGE);
        }
        AppUser user = userOpt.get();
        user.setPassword(this.passwordEncoder.encode(appUserDto.getPassword()));
        this.appUserRepository.save(user);
        return new ResponseDto(SUCCESS, String.format("Password reset for \"%s\".", user.getUsername()));
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
        return userOpt;
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
        if (isNull(newPassword) || newPassword.length() < 8) {
            return new ResponseDto(ERROR, "Choose a new password of at least 8 characters.");
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

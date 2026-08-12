package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.AppUserDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.Tenant;
import process.model.repository.AppUserRepository;
import process.model.repository.TenantRepository;
import process.model.service.AppUserService;
import process.security.TenantContext;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.*;

/**
 * Role policy enforced here (on top of AppUserRestApi's class-level TENANT_ADMIN+ @PreAuthorize,
 * which only gates "some kind of admin", not which tenant):
 *   - PLATFORM_ADMIN sees/manages users across every tenant, and is the only role that can
 *     create another PLATFORM_ADMIN or move a user between tenants.
 *   - TENANT_ADMIN is scoped to their own tenant (TenantContext.getTenantId()) for every
 *     operation -- list, add, update, status, password reset -- and can only assign
 *     TENANT_ADMIN/TENANT_USER, never PLATFORM_ADMIN. Any request naming a user outside their
 *     tenant is rejected with the same "not found" a nonexistent id would get, not a
 *     403/permission-denied -- so a TENANT_ADMIN probing ids can't distinguish "wrong tenant"
 *     from "doesn't exist" and enumerate other tenants' user ids.
 * @author Nabeel Ahmed
 */
@Service
public class AppUserServiceImpl implements AppUserService {

    private final Logger logger = LoggerFactory.getLogger(AppUserServiceImpl.class);

    private static final String NOT_FOUND_MESSAGE = "User not found.";

    private final AppUserRepository appUserRepository;
    private final TenantRepository tenantRepository;
    private final PasswordEncoder passwordEncoder;

    public AppUserServiceImpl(AppUserRepository appUserRepository, TenantRepository tenantRepository,
        PasswordEncoder passwordEncoder) {
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
        List<AppUserDto> dtos = this.mapToDtoList(users);
        return new ResponseDto(SUCCESS, "Users fetched successfully.", dtos);
    }

    @Override
    @Transactional
    public ResponseDto addUser(AppUserDto appUserDto) throws Exception {
        if (isNull(appUserDto.getUsername()) || appUserDto.getUsername().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Username missing.");
        } else if (isNull(appUserDto.getPassword()) || appUserDto.getPassword().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Password missing.");
        } else if (isNull(appUserDto.getFullName()) || appUserDto.getFullName().trim().isEmpty()) {
            return new ResponseDto(ERROR, "Full name missing.");
        } else if (isNull(appUserDto.getUserRole())) {
            return new ResponseDto(ERROR, "User role missing.");
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
            // TENANT_ADMIN -- always their own tenant, regardless of what the request claims.
            targetTenantId = TenantContext.getTenantId();
        }
        if (!isNull(targetTenantId) && !this.tenantRepository.findById(targetTenantId).isPresent()) {
            return new ResponseDto(ERROR, String.format("Tenant not found with %d.", targetTenantId));
        }
        if (this.appUserRepository.findByUsernameAndStatusNot(appUserDto.getUsername().trim(), Status.Delete).isPresent()) {
            return new ResponseDto(ERROR, String.format("Username \"%s\" is already in use.", appUserDto.getUsername().trim()));
        }
        AppUser user = new AppUser();
        user.setUuid(UUID.randomUUID().toString());
        user.setTenantId(targetTenantId);
        user.setUsername(appUserDto.getUsername().trim());
        user.setPassword(this.passwordEncoder.encode(appUserDto.getPassword()));
        user.setFullName(appUserDto.getFullName().trim());
        user.setUserRole(appUserDto.getUserRole());
        user.setStatus(Status.Active);
        user.setDateCreated(new Timestamp(System.currentTimeMillis()));
        this.appUserRepository.save(user);
        return new ResponseDto(SUCCESS, String.format("User \"%s\" created.", user.getUsername()), this.mapToDto(user));
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
        boolean isPlatformAdminActor = TenantContext.isPlatformAdmin();
        UserRole effectiveRole = !isNull(appUserDto.getUserRole()) ? appUserDto.getUserRole() : user.getUserRole();
        if (!isNull(appUserDto.getUserRole())) {
            if (!isPlatformAdminActor && appUserDto.getUserRole() == UserRole.PLATFORM_ADMIN) {
                return new ResponseDto(ERROR, "Only a Platform Admin can grant the Platform Admin role.");
            }
        }
        // Moving a user to a different tenant (or between tenant-bound and platform-wide) is a
        // Platform Admin-only action. PLATFORM_ADMIN is never tenant-bound (mirrors AppUser's
        // own javadoc/addUser's rule below) -- force null regardless of what tenantId was sent.
        Long effectiveTenantId;
        if (effectiveRole == UserRole.PLATFORM_ADMIN) {
            effectiveTenantId = null;
        } else if (isPlatformAdminActor && !isNull(appUserDto.getTenantId())) {
            effectiveTenantId = appUserDto.getTenantId();
        } else {
            effectiveTenantId = user.getTenantId();
        }
        // A non-PLATFORM_ADMIN role with no tenant would leave TenantFilterHelper's tenant
        // filter disabled for them (see its javadoc) -- i.e. unscoped, cross-tenant read access.
        // This can only be reached by demoting a PLATFORM_ADMIN (tenantId==null) to a tenant
        // role without assigning a tenant in the same request -- reject it outright rather than
        // silently create that combination.
        if (effectiveRole != UserRole.PLATFORM_ADMIN && isNull(effectiveTenantId)) {
            return new ResponseDto(ERROR, "A tenant is required for this role -- assign one before removing Platform Admin.");
        }
        if (!isNull(effectiveTenantId) && !effectiveTenantId.equals(user.getTenantId())
            && !this.tenantRepository.findById(effectiveTenantId).isPresent()) {
            return new ResponseDto(ERROR, String.format("Tenant not found with %d.", effectiveTenantId));
        }
        user.setUserRole(effectiveRole);
        user.setTenantId(effectiveTenantId);
        user.setFullName(appUserDto.getFullName().trim());
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

    /**
     * Method use to look up a user by id, scoped to the caller: a Platform Admin can reach any
     * user, a Tenant Admin only one within their own tenant (a PLATFORM_ADMIN target -- tenantId
     * null -- is never reachable by a Tenant Admin either).
     * @param appUserId
     * @return Optional<AppUser>
     * */
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

    /**
     * Method use to map a list of users to Dtos with ONE batch tenant-name lookup for the whole
     * list, instead of mapToDto's own tenantRepository.findById per row -- listUsers() was
     * issuing N extra round-trips (one per user) purely to resolve each user's tenant name.
     * @param users
     * @return List<AppUserDto>
     * */
    private List<AppUserDto> mapToDtoList(List<AppUser> users) {
        java.util.Set<Long> tenantIds = users.stream().map(AppUser::getTenantId)
            .filter(tenantId -> !isNull(tenantId)).collect(Collectors.toSet());
        java.util.Map<Long, Tenant> tenantById = tenantIds.isEmpty() ? java.util.Collections.emptyMap()
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

    /** Single-item convenience overload -- one extra tenant lookup here is fine outside a
     * list/loop context (contrast with mapToDtoList's batch lookup, used by listUsers()). */
    private AppUserDto mapToDto(AppUser user) {
        AppUserDto dto = this.mapToDtoWithoutTenantName(user);
        if (!isNull(user.getTenantId())) {
            this.applyTenantInfo(dto, this.tenantRepository.findById(user.getTenantId()).orElse(null));
        }
        return dto;
    }

    /** Method use to set tenantName/tenantActive on a Dto from an already-resolved Tenant (or
     * null, if the tenant itself was deleted out from under a still-Active app_user row -- see
     * AppUserDto.tenantActive's own javadoc for why this flag exists separately from the user's
     * own status). */
    private void applyTenantInfo(AppUserDto dto, Tenant tenant) {
        if (tenant == null) {
            dto.setTenantActive(false);
            return;
        }
        dto.setTenantName(tenant.getTenantName());
        dto.setTenantActive(tenant.getStatus() == process.model.enums.TenantStatus.Active);
    }

    private AppUserDto mapToDtoWithoutTenantName(AppUser user) {
        AppUserDto dto = new AppUserDto();
        dto.setAppUserId(user.getAppUserId());
        dto.setUuid(user.getUuid());
        dto.setTenantId(user.getTenantId());
        dto.setUsername(user.getUsername());
        dto.setFullName(user.getFullName());
        dto.setUserRole(user.getUserRole());
        dto.setStatus(user.getStatus());
        dto.setDateCreated(user.getDateCreated());
        dto.setLastLoginAt(user.getLastLoginAt());
        // password intentionally never set on read
        return dto;
    }

}

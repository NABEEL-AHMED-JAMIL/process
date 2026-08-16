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
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.*;

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
        dto.setStatus(user.getStatus());
        dto.setDateCreated(user.getDateCreated());
        dto.setLastLoginAt(user.getLastLoginAt());

        return dto;
    }

}

package process.service.imp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import process.emailer.EmailMessagesFactory;
import process.model.enums.ERole;
import process.model.enums.Status;
import process.model.pojo.AppUser;
import process.model.pojo.RefreshToken;
import process.model.pojo.Role;
import process.model.repository.AppUserRepository;
import process.model.repository.RoleRepository;
import process.payload.request.*;
import process.payload.response.*;
import process.security.jwt.JwtUtils;
import process.security.service.RefreshTokenService;
import process.security.service.UserDetailsImpl;
import process.service.AppUserService;
import process.service.LookupDataCacheService;
import process.util.CommonUtil;
import process.util.ProcessUtil;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * @author Nabeel Ahmed
 */
@Service
public class AppUserServiceImpl implements AppUserService {

    private Logger logger = LoggerFactory.getLogger(AppUserServiceImpl.class);

    @Autowired
    private JwtUtils jwtUtils;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private AppUserRepository appUserRepository;
    @Autowired
    private RefreshTokenService refreshTokenService;
    @Autowired
    private AuthenticationManager authenticationManager;
    @Autowired
    private EmailMessagesFactory emailMessagesFactory;
    @Autowired
    private LookupDataCacheService lookupDataCacheService;

    /**
     * Method use to get appUser detail
     * @param username
     * @return AppResponse
     * */
    @Override
    public AppResponse getAppUserProfile(String username) throws Exception {
        logger.info("Request getAppUserProfile :- " + username);
        if (ProcessUtil.isNull(username)) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(username, Status.Active);
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
        }
        AppUser appUserDetail = appUser.get();
        AppUserResponse appUserResponse = new AppUserResponse();
        appUserResponse.setAppUserId(appUserDetail.getAppUserId());
        appUserResponse.setFirstName(appUserDetail.getFirstName());
        appUserResponse.setLastName(appUserDetail.getLastName());
        appUserResponse.setTimeZone(appUserDetail.getTimeZone());
        appUserResponse.setUsername(appUserDetail.getUsername());
        appUserResponse.setEmail(appUserDetail.getEmail());
        appUserResponse.setStatus(appUserDetail.getStatus());
        appUserResponse.setDateCreated(appUserDetail.getDateCreated());
        if (!appUserDetail.getAppUserRoles().isEmpty()) {
            appUserResponse.setRoleResponse(
                appUserDetail.getAppUserRoles().stream().map(role -> {
                    RoleResponse roleResponse = new RoleResponse();
                    roleResponse.setRoleId(role.getRoleId());
                    roleResponse.setRoleName(role.getRoleName());
                    roleResponse.setStatus(role.getStatus());
                    roleResponse.setDateCreated(role.getDateCreated());
                    return roleResponse;
            }).collect(Collectors.toSet()));
        }
        if (!ProcessUtil.isNull(appUserDetail.getParentAppUser())) {
            appUserDetail = appUserDetail.getParentAppUser();
            AppUserResponse parentAppUserResponse = new AppUserResponse();
            parentAppUserResponse.setAppUserId(appUserDetail.getAppUserId());
            parentAppUserResponse.setFirstName(appUserDetail.getFirstName());
            parentAppUserResponse.setLastName(appUserDetail.getLastName());
            parentAppUserResponse.setTimeZone(appUserDetail.getTimeZone());
            parentAppUserResponse.setUsername(appUserDetail.getUsername());
            parentAppUserResponse.setEmail(appUserDetail.getEmail());
            parentAppUserResponse.setStatus(appUserDetail.getStatus());
            parentAppUserResponse.setDateCreated(appUserDetail.getDateCreated());
            appUserResponse.setParentAppUser(parentAppUserResponse);
        }
        return new AppResponse(ProcessUtil.SUCCESS, "User detail.", appUserResponse);
    }


    /**
     * Method use to update the user detail
     * @param updateUserProfileRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse updateAppUserProfile(UpdateUserProfileRequest
        updateUserProfileRequest) throws Exception {
        logger.info("Request updateAppUserProfile :- " + updateUserProfileRequest);
        if (ProcessUtil.isNull(updateUserProfileRequest.getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (ProcessUtil.isNull(updateUserProfileRequest.getFirstName())) {
            return new AppResponse(ProcessUtil.ERROR, "FirstName missing.");
        } else if (ProcessUtil.isNull(updateUserProfileRequest.getLastName())) {
            return new AppResponse(ProcessUtil.ERROR, "LastName missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            updateUserProfileRequest.getUsername(), Status.Active);
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
        }
        appUser.get().setFirstName(updateUserProfileRequest.getFirstName());
        appUser.get().setLastName(updateUserProfileRequest.getLastName());
        this.appUserRepository.save(appUser.get());
        if (this.emailMessagesFactory.sendUpdateAppUserProfile(updateUserProfileRequest)) {
            return new AppResponse(ProcessUtil.SUCCESS,"AppUser Profile Update.", updateUserProfileRequest);
        }
        return new AppResponse(ProcessUtil.ERROR,"Account updated," +
            "Email not send contact with support.", updateUserProfileRequest);
    }

    /**
     * Method use to update the app user password
     * @param updateUserProfileRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse updateAppUserPassword(UpdateUserProfileRequest
        updateUserProfileRequest) throws Exception {
        logger.info("Request updateAppUserPassword :- " + updateUserProfileRequest);
        if (ProcessUtil.isNull(updateUserProfileRequest.getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (ProcessUtil.isNull(updateUserProfileRequest.getOldPassword())) {
            return new AppResponse(ProcessUtil.ERROR, "OldPassword missing.");
        } else if (ProcessUtil.isNull(updateUserProfileRequest.getNewPassword())) {
            return new AppResponse(ProcessUtil.ERROR, "NewPassword missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            updateUserProfileRequest.getUsername(), Status.Active);
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
        }
        if (!this.passwordEncoder.matches(updateUserProfileRequest.getOldPassword(),
            appUser.get().getPassword())) {
            return new AppResponse(ProcessUtil.ERROR, "Old password not match.");
        }
        appUser.get().setPassword(this.passwordEncoder.encode(updateUserProfileRequest.getNewPassword()));
        this.appUserRepository.save(appUser.get());
        if (this.emailMessagesFactory.sendUpdateAppUserPassword(updateUserProfileRequest)) {
            return new AppResponse(ProcessUtil.SUCCESS,"AppUser Profile Update.", updateUserProfileRequest);
        }
        return new AppResponse(ProcessUtil.ERROR,"Account updated, " +
            "Email not send contact with support.", updateUserProfileRequest);
    }

    /**
     * Method use to update the app user timezone
     * @param updateUserProfileRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse updateAppUserTimeZone(UpdateUserProfileRequest
        updateUserProfileRequest) throws Exception {
        logger.info("Request updateAppUserTimeZone :- " + updateUserProfileRequest);
        if (ProcessUtil.isNull(updateUserProfileRequest.getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (ProcessUtil.isNull(updateUserProfileRequest.getTimeZone())) {
            return new AppResponse(ProcessUtil.ERROR, "TimeZone missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            updateUserProfileRequest.getUsername(), Status.Active);
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
        }
        appUser.get().setTimeZone(updateUserProfileRequest.getTimeZone());
        this.appUserRepository.save(appUser.get());
        if (this.emailMessagesFactory.sendUpdateAppUserTimeZone(updateUserProfileRequest)) {
            return new AppResponse(ProcessUtil.SUCCESS,"AppUser Timezone Update.", updateUserProfileRequest);
        }
        return new AppResponse(ProcessUtil.ERROR,"Account updated, " +
            "Email not send contact with support.", updateUserProfileRequest);
    }

    @Override
    public AppResponse closeAppUserAccount(UpdateUserProfileRequest updateUserProfileRequest) throws Exception {
        logger.info("Request closeAppUserAccount :- " + updateUserProfileRequest);
        if (ProcessUtil.isNull(updateUserProfileRequest.getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByUsernameAndStatus(
            updateUserProfileRequest.getUsername(), Status.Active);
        if (!appUser.isPresent()) {
            return new AppResponse(ProcessUtil.ERROR, "AppUser not found.");
        }
        appUser.get().setStatus(Status.Delete);
        /**
         * Will update rest of the code
         * */
        this.appUserRepository.save(appUser.get());
        if (this.emailMessagesFactory.sendCloseAppUserAccount(updateUserProfileRequest)) {
            return new AppResponse(ProcessUtil.SUCCESS,"AppUser close.", updateUserProfileRequest);
        }
        return new AppResponse(ProcessUtil.ERROR,"Account close, " +
            "Email not send contact with support.", updateUserProfileRequest);
    }

    /**
     * Method use for signIn appUser
     * @param loginRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse signInAppUser(LoginRequest loginRequest) throws Exception {
        // spring auth manager will call user detail service
        Authentication authentication = this.authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(loginRequest.getUsername(), loginRequest.getPassword()));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        // get the user detail from authentication
        UserDetailsImpl userDetails = (UserDetailsImpl) authentication.getPrincipal();
        // token generate
        String jwtToken = this.jwtUtils.generateJwtToken(userDetails);
        List<String> roles = userDetails.getAuthorities().stream()
            .map(item -> item.getAuthority()).collect(Collectors.toList());
        // refresh token generate
        RefreshToken refreshToken = this.refreshTokenService.createRefreshToken(userDetails.getAppUserId());
        return new AppResponse(ProcessUtil.SUCCESS, "User successfully authenticate.",
            new AuthResponse(userDetails.getAppUserId(), jwtToken, refreshToken.getToken(),
            userDetails.getUsername(), userDetails.getEmail(), roles));
    }

    /**
     * Method use for signUp appUser as admin
     * @param signUpRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse signupAppUser(SignupRequest signUpRequest) throws Exception {
        logger.info("Request signupAppUser :- " + signUpRequest);
        if (ProcessUtil.isNull(signUpRequest.getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username missing.");
        } else if (ProcessUtil.isNull(signUpRequest.getEmail())) {
            return new AppResponse(ProcessUtil.ERROR, "Email missing.");
        } else if (ProcessUtil.isNull(signUpRequest.getPassword())) {
            return new AppResponse(ProcessUtil.ERROR, "Password missing.");
        } else if (this.appUserRepository.existsByUsername(signUpRequest.getUsername())) {
            return new AppResponse(ProcessUtil.ERROR, "Username is already taken.");
        } else if (this.appUserRepository.existsByEmail(signUpRequest.getEmail())) {
            return new AppResponse(ProcessUtil.ERROR, "Email is already in use.");
        }
        // check if the username and email exist or not
        AppUser adminUser = new AppUser();
        adminUser.setEmail(signUpRequest.getEmail());
        adminUser.setUsername(signUpRequest.getUsername());
        adminUser.setPassword(this.passwordEncoder.encode(signUpRequest.getPassword()));
        // by default active user no need extra action
        adminUser.setStatus(Status.Active);
        // set the parent user which is master admin
        LookupDataResponse lookupDataResponse = this.lookupDataCacheService
            .getParentLookupById(CommonUtil.LookupDetail.SUPER_ADMIN);
        Optional<AppUser> superAdmin = this.appUserRepository.
            findByUsernameAndStatus(lookupDataResponse.getLookupValue(), Status.Active);
        if (superAdmin.isPresent()) {
            adminUser.setParentAppUser(superAdmin.get());
        }
        lookupDataResponse = this.lookupDataCacheService.getChildLookupById(
            CommonUtil.LookupDetail.SCHEDULER_TIMEZONE, CommonUtil.LookupDetail.ASIA_QATAR);
        if (!ProcessUtil.isNull(lookupDataResponse)) {
            adminUser.setTimeZone(lookupDataResponse.getLookupValue());
            signUpRequest.setTimeZone(lookupDataResponse.getLookupValue());
        }
        // register user role default as admin role
        Optional<Role> adminRole = this.roleRepository.findByRoleNameAndStatus(
            ERole.ROLE_ADMIN.name(), Status.Active);
        if (adminRole.isPresent()) {
            Set<Role> roleSet = new HashSet<>();
            roleSet.add(adminRole.get());
            adminUser.setAppUserRoles(roleSet);
        }
        // saving process
        adminUser = this.appUserRepository.save(adminUser);
        signUpRequest.setRole(adminUser.getAppUserRoles().stream()
            .filter(role -> role.getRoleName().equals(ERole.ROLE_ADMIN.name()))
            .findAny().get().getRoleName());
        // send the email to created user (pending)
        this.emailMessagesFactory.sendRegisterUser(signUpRequest);
        return new AppResponse(ProcessUtil.SUCCESS, String.format(
            "User successfully register %s.", adminUser.getUsername()));
    }

    /**
     * Method use support to forgot password
     * @param forgotPasswordRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse forgotPassword(ForgotPasswordRequest forgotPasswordRequest) throws Exception {
        logger.info("Request forgotPassword :- " + forgotPasswordRequest);
        if (ProcessUtil.isNull(forgotPasswordRequest.getEmail())) {
            return new AppResponse(ProcessUtil.ERROR, "Email missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByEmailAndStatus(
            forgotPasswordRequest.getEmail(), Status.Active);
        if (appUser.isPresent()) {
            forgotPasswordRequest.setUsername(appUser.get().getUsername());
            forgotPasswordRequest.setAppUserId(appUser.get().getAppUserId());
            if (this.emailMessagesFactory.sendForgotPassword(forgotPasswordRequest)) {
                return new AppResponse(ProcessUtil.SUCCESS,"Email send successfully");
            }
            return new AppResponse(ProcessUtil.ERROR,"Email not send contact with support.");
        }
        return new AppResponse(ProcessUtil.ERROR, "Account not exist.");
    }

    /**
     * Method use to reset app user password
     * @param passwordResetRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse resetPassword(PasswordResetRequest passwordResetRequest) throws Exception {
        logger.info("Request resetPassword :- " + passwordResetRequest);
        if (ProcessUtil.isNull(passwordResetRequest.getEmail())) {
            return new AppResponse(ProcessUtil.ERROR, "Email missing.");
        } else if (ProcessUtil.isNull(passwordResetRequest.getNewPassword())) {
            return new AppResponse(ProcessUtil.ERROR, "New password missing.");
        }
        Optional<AppUser> appUser = this.appUserRepository.findByEmailAndStatus(
            passwordResetRequest.getEmail(), Status.Active);
        if (appUser.isPresent()) {
            appUser.get().setPassword(this.passwordEncoder.encode(passwordResetRequest.getNewPassword()));
            this.appUserRepository.save(appUser.get());
            if (this.emailMessagesFactory.sendResetPassword(passwordResetRequest)) {
                return new AppResponse(ProcessUtil.SUCCESS,"Email send successfully.");
            }
            return new AppResponse(ProcessUtil.ERROR,"Email not send contact with support.");
        }
        return new AppResponse(ProcessUtil.ERROR, "Account not exist.");
    }

    /**
     * Method generate new token base on refresh token
     * @param tokenRefreshRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse authClamByRefreshToken(TokenRefreshRequest tokenRefreshRequest) throws Exception {
        AtomicReference<String> requestRefreshToken = new AtomicReference<>(tokenRefreshRequest.getRefreshToken());
        return this.refreshTokenService.findByToken(requestRefreshToken.get())
            .map(this.refreshTokenService::verifyExpiration)
            .map(appResponse -> {
                if (appResponse.getStatus().equals(ProcessUtil.SUCCESS)) {
                    RefreshToken refreshToken = (RefreshToken) appResponse.getData();
                    requestRefreshToken.set(this.jwtUtils.generateTokenFromUsername(refreshToken.getAppUser().getUsername()));
                }
                return new AppResponse(appResponse.getStatus(), appResponse.getMessage(), requestRefreshToken);
            }).orElse(new AppResponse(ProcessUtil.ERROR, "Token not found", requestRefreshToken));
    }

    /**
     * Method use to delete the token to log Out the session
     * @param tokenRefreshRequest
     * @return AppResponse
     * */
    @Override
    public AppResponse logoutAppUser(TokenRefreshRequest tokenRefreshRequest) throws Exception {
        return this.refreshTokenService.deleteRefreshToken(tokenRefreshRequest);
    }

}

package process.model.service;

import org.springframework.web.bind.annotation.RequestBody;
import process.payload.request.*;
import process.payload.response.AppResponse;

import java.io.ByteArrayOutputStream;

/**
 * @author Nabeel Ahmed
 */
public interface AppUserService {

    public AppResponse getAppUserProfile(String username) throws Exception;

    public AppResponse updateAppUserProfile(UpdateUserProfileRequest updateUserProfileRequest) throws Exception;

    public AppResponse updateAppUserPassword(UpdateUserProfileRequest updateUserProfileRequest) throws Exception;

    public AppResponse updateAppUserTimeZone(UpdateUserProfileRequest updateUserProfileRequest) throws Exception;

    public AppResponse closeAppUserAccount(UpdateUserProfileRequest updateUserProfileRequest) throws Exception;

    public AppResponse getSubAppUserAccount(String username) throws Exception;

    public AppResponse signInAppUser(LoginRequest loginRequest) throws Exception;

    public AppResponse signupAppUser(SignupRequest signupRequest) throws Exception;

    public AppResponse forgotPassword(ForgotPasswordRequest forgotPasswordRequest) throws Exception;

    public AppResponse resetPassword(PasswordResetRequest passwordResetRequest) throws Exception;

    public AppResponse authClamByRefreshToken(TokenRefreshRequest tokenRefreshRequest)  throws Exception;

    public AppResponse logoutAppUser(TokenRefreshRequest tokenRefreshRequest)  throws Exception;

    public AppResponse addEditAppUserAccount(SignupRequest signupRequest)  throws Exception;

    public ByteArrayOutputStream downloadAppUserTemplateFile() throws Exception;

    public ByteArrayOutputStream downloadAppUser(SignupRequest signupRequest) throws Exception;

    public AppResponse uploadAppUser(FileUploadRequest fileObject) throws Exception;

}

package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.*;
import process.model.dto.LoginRequestDto;
import process.model.dto.ResponseDto;
import process.security.CurrentUser;
import process.security.UserPrincipalDetail;
import process.util.exception.ExceptionUtil;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/auth.json")
public class AuthRestApi {

    private Logger logger = LoggerFactory.getLogger(AuthRestApi.class);


    @RequestMapping(value = "/login", method = RequestMethod.POST)
    public ResponseDto login(@RequestBody LoginRequestDto loginRequest) {
        ResponseDto response;
        try {
            response = this.authService.login(loginRequest);
        } catch (BadCredentialsException ex) {
            logger.info("Error during login " + ExceptionUtil.getRootCause(ex));
            response = new ResponseDto ("ERROR", "Invalid credentials.",
                ExceptionUtil.getRootCauseMessage(ex));
        } catch (Exception ex) {
            logger.info("Error during login " + ExceptionUtil.getRootCause(ex));
            response = new ResponseDto ("ERROR", "Exception in system contact with support.",
                ExceptionUtil.getRootCauseMessage(ex));
        }
        return response;
    }

    @PreAuthorize("hasRole('ADMIN') || hasRole('USER')")
    @RequestMapping(value = "/user/me", method = RequestMethod.GET)
    public ResponseDto getCurrentUser(@CurrentUser UserPrincipalDetail userPrincipalDetail) {
        ResponseDto response;
        try {
            logger.info(String.format("Request for getCurrentUser %s ", userPrincipalDetail));
            response = this.authService.getCurrentUser(userPrincipalDetail);
        } catch (Exception ex) {
            logger.info("Error during login " + ExceptionUtil.getRootCause(ex));
            response = new ResponseDto ("ERROR", "Exception in system contact with support.",
                    ExceptionUtil.getRootCauseMessage(ex));
        }
        return response;
    }

    @PreAuthorize("hasRole('ADMIN') || hasRole('USER')")
    @RequestMapping(value = "/user/profile", method = RequestMethod.GET)
    public ResponseDto getUserProfile(@CurrentUser UserPrincipalDetail userPrincipalDetail) {
        ResponseDto response;
        try {
            response = this.authService.getUserProfile(userPrincipalDetail);
        } catch (Exception ex) {
            logger.info("Error during getUserProfile " + ExceptionUtil.getRootCause(ex));
            response = new ResponseDto ("ERROR", "Exception in system contact with support.",
                    ExceptionUtil.getRootCauseMessage(ex));
        }
        return response;
    }

    @PreAuthorize("hasRole('ADMIN') || hasRole('USER')")
    @RequestMapping(value = "/updateProfile", method = RequestMethod.POST)
    public ResponseDto updateProfile(@RequestBody RealEstateUserDto realEstateAgent) {
        ResponseDto response;
        try {
            logger.info("Request for updateProfile " + realEstateAgent);
            return this.authService.updateProfile(realEstateAgent);
        } catch (Exception ex) {
            logger.info("Error during updateProfile " + ExceptionUtil.getRootCause(ex));
            response = new ResponseDto ("ERROR", "Exception in system contact with support.",
                    ExceptionUtil.getRootCauseMessage(ex));
        }
        return response;
    }

    @RequestMapping(value = "/register", method = RequestMethod.POST)
    public ResponseDto registerRealEstateUser(@RequestBody RealEstateUserDto realEstateAgent) {
        ResponseDto response;
        try {
            logger.info("Request for registerRealEstateUser " + realEstateAgent);
            return this.authService.registerRealEstateUser(realEstateAgent);
        } catch (Exception ex) {
            logger.info("Error during registerRealEstateUser " + ExceptionUtil.getRootCause(ex));
            response = new ResponseDto ("ERROR", "Exception in system contact with support.",
                    ExceptionUtil.getRootCauseMessage(ex));
        }
        return response;
    }

    @RequestMapping(value = "/signupSuccess", method = RequestMethod.GET)
    public ResponseDto signupSuccess(@RequestParam String token, @RequestParam String emailAddress) {
        ResponseDto response;
        try {
            logger.info(String.format("Request for signupSuccess token %s and emailAddress %s ", token, emailAddress));
            return this.authService.signupSuccess(token, emailAddress);
        } catch (Exception ex) {
            logger.info("Error during signupSuccess " + ExceptionUtil.getRootCause(ex));
            response = new ResponseDto ("ERROR", "Exception in system contact with support.",
                    ExceptionUtil.getRootCauseMessage(ex));
        }
        return response;
    }

    @RequestMapping(value = "/forgetPassword", method = RequestMethod.GET)
    public ResponseDto forgetPassword(@RequestParam String emailAddress) {
        ResponseDto response;
        try {
            logger.info("Request for forgetPassword " + emailAddress);
            if (emailAddress == null || !(emailAddress.length() > 0)) {
                response = new ResponseDto("ERROR", "Invalid emailAddress", emailAddress);
            } else if (!RealEstatePlatFormUtil.isValidEmail(emailAddress)) {
                response = new ResponseDto("ERROR", "Invalid emailAddress", emailAddress);
            } else {
                response = this.authService.forgetPasswordRealEstateUser(emailAddress);
            }
        } catch (Exception ex) {
            logger.info("Error during forgetPassword " + ExceptionUtil.getRootCause(ex));
            response = new ResponseDto ("ERROR", "Exception in system contact with support.",
                    ExceptionUtil.getRootCauseMessage(ex));
        }
        return response;
    }

    @RequestMapping(value = "/resetPassword", method = RequestMethod.POST)
    public ResponseDto resetPassword(@RequestBody ResetPasswordRealEstateUserDto resetPasswordRealEstateUserDto) {
        ResponseDto response;
        try {
            logger.info("Request for resetPassword " + resetPasswordRealEstateUserDto);
            response = this.authService.resetPasswordRealEstateUser(resetPasswordRealEstateUserDto);
        } catch (Exception ex) {
            logger.info("Error during resetPassword " + ExceptionUtil.getRootCause(ex));
            response = new ResponseDto ("ERROR", "Exception in system contact with support.",
                    ExceptionUtil.getRootCauseMessage(ex));
        }
        return response;
    }


}

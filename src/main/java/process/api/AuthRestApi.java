package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.*;
import process.payload.request.*;
import process.payload.response.AppResponse;
import process.model.service.AppUserService;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;

/**
 * Api use to perform crud operation
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/auth.json")
public class AuthRestApi {

    private Logger logger = LoggerFactory.getLogger(AuthRestApi.class);

    @Autowired
    private AppUserService appUserService;

    /**
     * api-status :- done
     * @apiName :- signInAppUser
     * @apiNote :- Api use to sign In the appUser
     * @param payload
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/signInAppUser", method = RequestMethod.POST)
    public ResponseEntity<?> signInAppUser(@RequestBody LoginRequest payload) {
        try {
            return new ResponseEntity<>(this.appUserService.signInAppUser(payload), HttpStatus.OK);
        }catch (BadCredentialsException ex) {
            logger.error("An error occurred while signInAppUser ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR, "BadCredentials."), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while signInAppUser ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- signupAppUser
     * @apiNote :- Api use to create the appUser as admin access
     * @param payload
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/signupAppUser", method = RequestMethod.POST)
    public ResponseEntity<?> signupAppUser(@RequestBody SignupRequest payload) {
        try {
            return new ResponseEntity<>(this.appUserService.signupAppUser(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while signupAppUser ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- forgotPassword
     * @apiNote :- Api use support to forgot password
     * @param payload
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/forgotPassword", method = RequestMethod.POST)
    public ResponseEntity<?> forgotPassword(@RequestBody ForgotPasswordRequest payload) {
        try {
            return new ResponseEntity<>(this.appUserService.forgotPassword(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while forgotPassword ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- resetPassword
     * @apiNote :- Api use support to forgot password
     * @param payload
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/resetPassword", method = RequestMethod.POST)
    public ResponseEntity<?> resetPassword(@RequestBody PasswordResetRequest payload) {
        try {
            return new ResponseEntity<>(this.appUserService.resetPassword(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while resetPassword ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- authClamByRefreshToken
     * @apiNote :- Api use to get refreshToken for appUser
     * @param payload
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/authClamByRefreshToken", method = RequestMethod.POST)
    public ResponseEntity<?> authClamByRefreshToken(@RequestBody TokenRefreshRequest payload) {
        try {
            return new ResponseEntity<>(this.appUserService.authClamByRefreshToken(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while authClamByRefreshToken ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- logoutAppUser
     * @apiNote :- Api use to delete refreshToken for appUser
     * @param payload
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/logoutAppUser", method = RequestMethod.POST)
    public ResponseEntity<?> logoutAppUser(@RequestBody TokenRefreshRequest payload) {
        try {
            return new ResponseEntity<>(this.appUserService.logoutAppUser(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteRefreshToken ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

}

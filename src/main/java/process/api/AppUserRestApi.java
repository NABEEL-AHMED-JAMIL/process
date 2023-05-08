package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.payload.request.UpdateUserProfileRequest;
import process.payload.response.AppResponse;
import process.service.AppUserService;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;

/**
 * Api use to perform crud operation
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/appUser.json")
public class AppUserRestApi {

    private Logger logger = LoggerFactory.getLogger(AppUserRestApi.class);

    @Autowired
    private AppUserService appUserService;

    /**
     * api-status :- done
     * @apiName :- tokenVerify
     * @apiNote :- Api use to check token is valid or not
     * its empty call to check the token expiry
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/tokenVerify", method = RequestMethod.GET)
    public ResponseEntity<?> tokenVerify() {
        try {
            return new ResponseEntity<>(new AppResponse(ProcessUtil.SUCCESS, "Token valid."), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while tokenVerify ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR, "Token not valid."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- getAppUserProfile
     * @apiNote :- Api use to sign In the appUser
     * @param username
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/getAppUserProfile", method = RequestMethod.GET)
    public ResponseEntity<?> getAppUserProfile(@RequestParam String username) {
        try {
            return new ResponseEntity<>(this.appUserService.getAppUserProfile(username), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while getAppUserProfile ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- updateAppUserProfile
     * @apiNote :- Api use to update profile
     * @param updateUserProfileRequest
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/updateAppUserProfile", method = RequestMethod.POST)
    public ResponseEntity<?> updateAppUserProfile(@RequestBody UpdateUserProfileRequest updateUserProfileRequest) {
        try {
            return new ResponseEntity<>(this.appUserService.updateAppUserProfile(updateUserProfileRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateAppUserProfile ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- updateAppUserPassword
     * @apiNote :- Api use to update profile password
     * @param updateUserProfileRequest
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/updateAppUserPassword", method = RequestMethod.POST)
    public ResponseEntity<?> updateAppUserPassword(@RequestBody UpdateUserProfileRequest updateUserProfileRequest) {
        try {
            return new ResponseEntity<>(this.appUserService.updateAppUserPassword(updateUserProfileRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateAppUserPassword ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- updateAppUserTimeZone
     * @apiNote :- Api use to update profile password
     * @param updateUserProfileRequest
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/updateAppUserTimeZone", method = RequestMethod.POST)
    public ResponseEntity<?> updateAppUserTimeZone(@RequestBody UpdateUserProfileRequest updateUserProfileRequest) {
        try {
            return new ResponseEntity<>(this.appUserService.updateAppUserTimeZone(updateUserProfileRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateAppUserTimeZone ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- closeAppUserAccount
     * @apiNote :- Api use to update profile password
     * @param updateUserProfileRequest
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/closeAppUserAccount", method = RequestMethod.POST)
    public ResponseEntity<?> closeAppUserAccount(@RequestBody UpdateUserProfileRequest updateUserProfileRequest) {
        try {
            return new ResponseEntity<>(this.appUserService.closeAppUserAccount(updateUserProfileRequest), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while closeAppUserAccount ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- getSubAppUserAccount
     * @apiNote :- Api use to get sub appUser account
     * @param username
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/getSubAppUserAccount", method = RequestMethod.GET)
    public ResponseEntity<?> getSubAppUserAccount(@RequestParam String username) {
        try {
            return new ResponseEntity<>(this.appUserService.getSubAppUserAccount(username), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while getSubAppUserAccount ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

}

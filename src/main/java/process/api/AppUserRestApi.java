package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.payload.request.FileUploadRequest;
import process.model.payload.request.SignupRequest;
import process.model.payload.request.UpdateUserProfileRequest;
import process.model.payload.response.AppResponse;
import process.model.service.AppUserService;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

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
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN') or hasRole('USER')")
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
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN') or hasRole('USER')")
    @RequestMapping(value = "/updateAppUserProfile", method = RequestMethod.POST)
    public ResponseEntity<?> updateAppUserProfile(@RequestBody UpdateUserProfileRequest payload) {
        try {
            return new ResponseEntity<>(this.appUserService.updateAppUserProfile(payload), HttpStatus.OK);
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
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN') or hasRole('USER')")
    @RequestMapping(value = "/updateAppUserPassword", method = RequestMethod.POST)
    public ResponseEntity<?> updateAppUserPassword(@RequestBody UpdateUserProfileRequest payload) {
        try {
            return new ResponseEntity<>(this.appUserService.updateAppUserPassword(payload), HttpStatus.OK);
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
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN') or hasRole('USER')")
    @RequestMapping(value = "/updateAppUserTimeZone", method = RequestMethod.POST)
    public ResponseEntity<?> updateAppUserTimeZone(@RequestBody UpdateUserProfileRequest payload) {
        try {
            return new ResponseEntity<>(this.appUserService.updateAppUserTimeZone(payload), HttpStatus.OK);
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
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/closeAppUserAccount", method = RequestMethod.POST)
    public ResponseEntity<?> closeAppUserAccount(@RequestBody UpdateUserProfileRequest payload) {
        try {
            return new ResponseEntity<>(this.appUserService.closeAppUserAccount(payload), HttpStatus.OK);
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

    /**
     * api-status :- done
     * @apiName :- addEditAppUserAccount
     * @apiNote :- Api use to add/edit the appuser account
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addEditAppUserAccount", method = RequestMethod.POST)
    public ResponseEntity<?> addEditAppUserAccount(@RequestBody SignupRequest payload) {
        try {
            return new ResponseEntity<>(this.appUserService.addEditAppUserAccount(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addEditAppUserAccount ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- downloadAppUserTemplateFile
     * Api use to download lookup template the lookup data
     * @return ResponseEntity<?> downloadAppUserTemplateFile
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/downloadAppUserTemplateFile", method = RequestMethod.GET)
    public ResponseEntity<?> downloadAppUserTemplateFile() {
        try {
            HttpHeaders headers = new HttpHeaders();
            DateFormat dateFormat = new SimpleDateFormat(ProcessUtil.SIMPLE_DATE_PATTERN);
            String fileName = "BatchAppUserDownload-"+dateFormat.format(new Date())+"-"+ UUID.randomUUID() + ".xlsx";
            headers.add(ProcessUtil.CONTENT_DISPOSITION,ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers).body(this.appUserService.downloadAppUserTemplateFile().toByteArray());
        } catch (Exception ex) {
            logger.error("An error occurred while downloadAppUserTemplateFile xlsx file", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Sorry File Not Downland, Contact With Support"), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- downloadLookup
     * Api use to download the lookup data
     * @return ResponseEntity<?> downloadLookup
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/downloadAppUser", method = RequestMethod.POST)
    public ResponseEntity<?> downloadAppUser(@RequestBody SignupRequest payload) {
        try {
            HttpHeaders headers = new HttpHeaders();
            DateFormat dateFormat = new SimpleDateFormat(ProcessUtil.SIMPLE_DATE_PATTERN);
            String fileName = "BatchAppUserDownload-"+dateFormat.format(new Date())+"-"+ UUID.randomUUID() + ".xlsx";
            headers.add(ProcessUtil.CONTENT_DISPOSITION,ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers).body(this.appUserService.downloadAppUser(payload).toByteArray());
        } catch (Exception ex) {
            logger.error("An error occurred while downloadAppUser ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- uploadAppUser
     * Api use to upload the lookup data
     * @return ResponseEntity<?> uploadAppUser
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/uploadAppUser", method = RequestMethod.POST)
    public ResponseEntity<?> uploadAppUser(FileUploadRequest payload) {
        try {
            if (!ProcessUtil.isNull(payload.getFile())) {
                return new ResponseEntity<>(this.appUserService.uploadAppUser(payload), HttpStatus.OK);
            }
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR, "File not found for process."), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.error("An error occurred while uploadAppUser ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR, "Sorry File Not Upload Contact With Support"), HttpStatus.BAD_REQUEST);
        }
    }

}

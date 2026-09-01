package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.core.io.InputStreamResource;
import process.model.dto.ObjectContentDto;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.AppUserDto;
import process.model.dto.ResponseDto;
import process.model.service.AppUserService;
import process.util.ProcessUtil;

/**
 * @author Nabeel Ahmed
 * */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/appUser.json")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class AppUserRestApi {

    private Logger logger = LoggerFactory.getLogger(AppUserRestApi.class);

    private final AppUserService appUserService;

    public AppUserRestApi(AppUserService appUserService) {
        this.appUserService = appUserService;
    }

    @RequestMapping(value = "/listUsers", method = RequestMethod.GET)
    public ResponseEntity<?> listUsers() {
        try {
            return new ResponseEntity<>(this.appUserService.listUsers(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while listUsers.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/addUser", method = RequestMethod.POST)
    public ResponseEntity<?> addUser(@RequestBody AppUserDto appUserDto) {
        try {
            return new ResponseEntity<>(this.appUserService.addUser(appUserDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addUser.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/updateUser", method = RequestMethod.PUT)
    public ResponseEntity<?> updateUser(@RequestBody AppUserDto appUserDto) {
        try {
            return new ResponseEntity<>(this.appUserService.updateUser(appUserDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateUser.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/changeUserStatus", method = RequestMethod.PUT)
    public ResponseEntity<?> changeUserStatus(@RequestBody AppUserDto appUserDto) {
        try {
            return new ResponseEntity<>(this.appUserService.changeUserStatus(appUserDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while changeUserStatus.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/resetPassword", method = RequestMethod.PUT)
    public ResponseEntity<?> resetPassword(@RequestBody AppUserDto appUserDto) {
        try {
            return new ResponseEntity<>(this.appUserService.resetPassword(appUserDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while resetPassword.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }


    /**
     * Profile endpoints are the one part of this controller a plain tenant user reaches --
     * the class requires TENANT_ADMIN, and these three override it down to TENANT_USER
     * because everyone manages their own profile. Each derives the user from the token, so
     * the override widens who can call them without widening what they can touch.
     */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/me", method = RequestMethod.GET)
    public ResponseEntity<?> currentUser() {
        try {
            return new ResponseEntity<>(this.appUserService.currentUser(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while currentUser.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * A user's picture by their id.
     *
     * TENANT_USER because a face beside a name is not privileged information, and everyone's
     * screens show them -- the service decides which people the caller can see, and answers 404 for
     * anyone outside that. Streams the bytes rather than returning a DTO so the browser can bind it
     * straight to an img.
     */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/avatar", method = RequestMethod.GET)
    public ResponseEntity<?> avatar(@RequestParam Long appUserId) {
        try {
            ObjectContentDto content = this.appUserService.readAvatar(appUserId);
            if (content == null) {
                // Not an error: most people have no picture, and the console falls back to initials.
                return new ResponseEntity<>(HttpStatus.NOT_FOUND);
            }
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, content.getContentType() == null
                    ? MediaType.APPLICATION_OCTET_STREAM_VALUE : content.getContentType())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .body(new InputStreamResource(content.getContent()));
        } catch (Exception ex) {
            logger.error("An error occurred while avatar.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/updateOwnProfile", method = RequestMethod.PUT)
    public ResponseEntity<?> updateOwnProfile(@RequestBody AppUserDto appUserDto) {
        try {
            return new ResponseEntity<>(this.appUserService.updateOwnProfile(appUserDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateOwnProfile.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Anyone signed in may change their own password -- including a tenant user, who cannot
     * reach anything else on this controller. Hence the explicit role, which overrides the
     * TENANT_ADMIN the class asks for.
     */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/changeOwnPassword", method = RequestMethod.PUT)
    public ResponseEntity<?> changeOwnPassword(@RequestBody java.util.Map<String, String> body) {
        try {
            String current = body == null ? null : body.get("currentPassword");
            String updated = body == null ? null : body.get("newPassword");
            return new ResponseEntity<>(
                this.appUserService.changeOwnPassword(current, updated), HttpStatus.OK);
        } catch (Exception ex) {
            // No request detail in the log line: this body holds two passwords.
            logger.error("An error occurred while changing a password.");
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/updateOwnAvatar", method = RequestMethod.PUT)
    public ResponseEntity<?> updateOwnAvatar(@RequestBody AppUserDto appUserDto) {
        try {
            return new ResponseEntity<>(this.appUserService.updateOwnAvatar(appUserDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateOwnAvatar.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}

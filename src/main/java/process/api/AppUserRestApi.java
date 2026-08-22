package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.AppUserDto;
import process.model.dto.ResponseDto;
import process.model.service.AppUserService;
import process.util.ProcessUtil;

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

}

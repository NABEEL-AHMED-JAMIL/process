package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.payload.request.AppUserEnvRequest;
import process.payload.response.AppResponse;
import process.service.EnvService;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;

/**
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/appUserEvn.json")
public class EnvRestApi {

    private Logger logger = LoggerFactory.getLogger(EnvRestApi.class);

    @Autowired
    private EnvService envService;

    /**
     * @apiName :- addEnv
     * Api use to add the env data
     * @param payload
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN')")
    @RequestMapping(value = "/addEnv", method = RequestMethod.POST)
    public ResponseEntity<?> addEnv(@RequestBody AppUserEnvRequest payload) {
        try {
            return new ResponseEntity<>(this.envService.addEnv(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addEnv ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- editEnv
     * Api use to edit the env data
     * @param payload
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN')")
    @RequestMapping(value = "/editEnv", method = RequestMethod.POST)
    public ResponseEntity<?> editEnv(@RequestBody AppUserEnvRequest payload) {
        try {
            return new ResponseEntity<>(this.envService.editEnv(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while editEnv ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- deleteEnv
     * Api use to delete the env data
     * @param payload
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN')")
    @RequestMapping(value = "/deleteEnv", method = RequestMethod.POST)
    public ResponseEntity<?> deleteEnv(@RequestBody AppUserEnvRequest payload) {
        try {
            return new ResponseEntity<>(this.envService.deleteEnv(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteEnv ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- fetchAllEvn
     * Api use to fetch all env
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN')")
    @RequestMapping(value = "/fetchAllEvn", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAllEvn() {
        try {
            return new ResponseEntity<>(this.envService.fetchAllEvn(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllEvn ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- fetchEnvById
     * Api use to fetch env by id
     * @param payload
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN')")
    @RequestMapping(value = "/fetchEnvById", method = RequestMethod.POST)
    public ResponseEntity<?> fetchEnvById(@RequestBody AppUserEnvRequest payload) {
        try {
            return new ResponseEntity<>(this.envService.fetchEnvById(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchEnvById ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- fetchUserEnvByEnvKey
     * Api use to fetch env by id
     * @param payload
     * */
    @RequestMapping(value = "/fetchUserEnvByEnvKey", method = RequestMethod.POST)
    public ResponseEntity<?> fetchUserEnvByEnvKey(@RequestBody AppUserEnvRequest payload) {
        try {
            return new ResponseEntity<>(this.envService.fetchUserEnvByEnvKey(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchUserEnvByEnvKey ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- linkEnvWithUser
     * Api use to link env with user
     * @param payload
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN')")
    @RequestMapping(value = "/linkEnvWithUser", method = RequestMethod.POST)
    public ResponseEntity<?> linkEnvWithUser(@RequestBody AppUserEnvRequest payload) {
        try {
            return new ResponseEntity<>(this.envService.linkEnvWithUser(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while linkEnvWithUser ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- fetchAllEnvWithEnvKeyId
     * Api use to fetch the link key with user
     * @param payload
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN')")
    @RequestMapping(value = "/fetchAllEnvWithEnvKeyId", method = RequestMethod.POST)
    public ResponseEntity<?> fetchAllEnvWithEnvKeyId(@RequestBody AppUserEnvRequest payload) {
        try {
            return new ResponseEntity<>(this.envService.fetchAllEnvWithEnvKeyId(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllEnvWithEnvKeyId ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- fetchAllEnvWithAppUserId
     * Api use to fetch the link key with user
     * @param payload
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchAllEnvWithAppUserId", method = RequestMethod.POST)
    public ResponseEntity<?> fetchAllEnvWithAppUserId(@RequestBody AppUserEnvRequest payload) {
        try {
            return new ResponseEntity<>(this.envService.fetchAllEnvWithAppUserId(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllEnvWithAppUserId ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- deleteEnvWithUserId
     * Api use to link env with user
     * @param payload
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN')")
    @RequestMapping(value = "/deleteEnvWithUserId", method = RequestMethod.POST)
    public ResponseEntity<?> deleteEnvWithUserId(@RequestBody AppUserEnvRequest payload) {
        try {
            return new ResponseEntity<>(this.envService.deleteEnvWithUserId(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteEnvWithUserId ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- updateAppUserEnvWithUserId
     * Api use to update env value with user
     * @param payload
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/updateAppUserEnvWithUserId", method = RequestMethod.POST)
    public ResponseEntity<?> updateAppUserEnvWithUserId(@RequestBody AppUserEnvRequest payload) {
        try {
            return new ResponseEntity<>(this.envService.updateAppUserEnvWithUserId(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateAppUserEnvWithUserId ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

}

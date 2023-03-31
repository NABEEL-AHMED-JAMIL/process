package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.payload.request.LookupDataRequest;
import process.payload.response.AppResponse;
import process.service.LookupDataCacheService;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;

/**
 * Api use to perform crud operation
 * Lookup data access for (create,delete,update) only to admin and super admin
 * Fetch access to all the user (admin, super_admin, user)
 * Lookup should be cache data if (admin,super_admin) perform (cud) operation cache should update as well
 * if user is super admin can view all the other admin lookup
 * if user is admin only can view only his lookup
 * @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/lookup.json")
public class LookupDataRestApi {

    private Logger logger = LoggerFactory.getLogger(LookupDataRestApi.class);

    @Autowired
    private LookupDataCacheService lookupDataCacheService;


    /**
     * @apiName :- addLookupData
     * Api use to add the lookup data
     * @param tempLookupData
     * @return ResponseEntity<?> addLookupData
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addLookupData", method = RequestMethod.POST)
    public ResponseEntity<?> addLookupData(@RequestBody LookupDataRequest tempLookupData) {
        try {
            return new ResponseEntity<>(this.lookupDataCacheService.addLookupData(tempLookupData), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addLookupData ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- updateLookupData
     * Api use to update the lookup data
     * @param tempLookupData
     * @return ResponseEntity<?> updateLookupData
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/updateLookupData", method = RequestMethod.PUT)
    public ResponseEntity<?> updateLookupData(@RequestBody LookupDataRequest tempLookupData) {
        try {
            return new ResponseEntity<>(this.lookupDataCacheService.updateLookupData(tempLookupData), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateLookupData ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- fetchAllLookup
     * Api use to fetch the lookup detail
     * @return ResponseEntity<?> fetchAllLookup
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchAllLookup", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAllLookup() {
        try {
            return new ResponseEntity<>(this.lookupDataCacheService.fetchAllLookup(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllLookup ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- fetchSubLookupByParentId
     * Api use to fetch the sub-Lookup by parent lookup id
     * @param parentLookUpId
     * @return ResponseEntity<?> fetchSubLookupByParentId
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSubLookupByParentId", method = RequestMethod.GET)
    public ResponseEntity<?> fetchSubLookupByParentId(@RequestParam Long parentLookUpId) {
        try {
            return new ResponseEntity<>(this.lookupDataCacheService.fetchSubLookupByParentId(parentLookUpId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSubLookupByParentId ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * @apiName :- deleteLookupData
     * Api use to delete the lookup data
     * @param tempLookupData
     * @return ResponseEntity<?> deleteLookupData
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteLookupData", method = RequestMethod.PUT)
    public ResponseEntity<?> deleteLookupData(@RequestBody LookupDataRequest tempLookupData) {
        try {
            return new ResponseEntity<>(this.lookupDataCacheService.deleteLookupData(tempLookupData), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteLookupData ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

}
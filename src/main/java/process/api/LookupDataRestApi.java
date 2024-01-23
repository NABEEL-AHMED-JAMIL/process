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
import process.model.payload.request.LookupDataRequest;
import process.model.payload.response.AppResponse;
import process.model.service.LookupDataCacheService;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

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
     * api-status :- done
     * @apiName :- addLookupData
     * Api use to add the lookup data
     * @param payload
     * @return ResponseEntity<?> addLookupData
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addLookupData", method = RequestMethod.POST)
    public ResponseEntity<?> addLookupData(@RequestBody LookupDataRequest payload) {
        try {
            return new ResponseEntity<>(this.lookupDataCacheService.addLookupData(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addLookupData ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- updateLookupData
     * Api use to update the lookup data
     * @param payload
     * @return ResponseEntity<?> updateLookupData
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/updateLookupData", method = RequestMethod.PUT)
    public ResponseEntity<?> updateLookupData(@RequestBody LookupDataRequest payload) {
        try {
            return new ResponseEntity<>(this.lookupDataCacheService.updateLookupData(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateLookupData ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- fetchAllLookup
     * Api use to fetch the lookup detail
     * @return ResponseEntity<?> fetchAllLookup
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchAllLookup", method = RequestMethod.POST)
    public ResponseEntity<?> fetchAllLookup(@RequestBody LookupDataRequest payload) {
        try {
            return new ResponseEntity<>(this.lookupDataCacheService.fetchAllLookup(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllLookup ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- fetchSubLookupByParentId
     * Api use to fetch the sub-Lookup by parent lookup id
     * @param payload
     * @return ResponseEntity<?> fetchSubLookupByParentId
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSubLookupByParentId", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSubLookupByParentId(@RequestBody LookupDataRequest payload) {
        try {
            return new ResponseEntity<>(this.lookupDataCacheService.fetchSubLookupByParentId(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSubLookupByParentId ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- fetchLookupByLookupType
     * Api use to fetch the sub-Lookup by parent lookup type
     * @param payload
     * @return ResponseEntity<?> fetchLookupByLookupType
     * */
    @RequestMapping(value = "/fetchLookupByLookupType", method = RequestMethod.POST)
    public ResponseEntity<?> fetchLookupByLookupType(@RequestBody LookupDataRequest payload) {
        try {
            return new ResponseEntity<>(this.lookupDataCacheService.fetchLookupByLookupType(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchLookupByLookupType ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- deleteLookupData
     * Api use to delete the lookup data
     * @param payload
     * @return ResponseEntity<?> deleteLookupData
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteLookupData", method = RequestMethod.PUT)
    public ResponseEntity<?> deleteLookupData(@RequestBody LookupDataRequest payload) {
        try {
            return new ResponseEntity<>(this.lookupDataCacheService.deleteLookupData(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteLookupData ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- downloadLookupTemplateFile
     * Api use to download lookup template the lookup data
     * @return ResponseEntity<?> downloadLookupTemplateFile
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/downloadLookupTemplateFile", method = RequestMethod.GET)
    public ResponseEntity<?> downloadLookupTemplateFile() {
        try {
            HttpHeaders headers = new HttpHeaders();
            DateFormat dateFormat = new SimpleDateFormat(ProcessUtil.SIMPLE_DATE_PATTERN);
            String fileName = "BatchLookupDownload-"+dateFormat.format(new Date())+"-"+ UUID.randomUUID() + ".xlsx";
            headers.add(ProcessUtil.CONTENT_DISPOSITION,ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers).body(
                this.lookupDataCacheService.downloadLookupTemplateFile().toByteArray());
        } catch (Exception ex) {
            logger.error("An error occurred while downloadLookupTemplateFile xlsx file",
                ExceptionUtil.getRootCauseMessage(ex));
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
    @RequestMapping(value = "/downloadLookup", method = RequestMethod.POST)
    public ResponseEntity<?> downloadLookup(@RequestBody LookupDataRequest payload) {
        try {
            HttpHeaders headers = new HttpHeaders();
            DateFormat dateFormat = new SimpleDateFormat(ProcessUtil.SIMPLE_DATE_PATTERN);
            String fileName = "BatchLookupDownload-"+dateFormat.format(new Date())+"-"+ UUID.randomUUID() + ".xlsx";
            headers.add(ProcessUtil.CONTENT_DISPOSITION,ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers).body(
                this.lookupDataCacheService.downloadLookup(payload).toByteArray());
        } catch (Exception ex) {
            logger.error("An error occurred while downloadLookup ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- uploadLookup
     * Api use to upload the lookup data
     * @return ResponseEntity<?> uploadLookup
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/uploadLookup", method = RequestMethod.POST)
    public ResponseEntity<?> uploadLookup(FileUploadRequest payload) {
        try {
            if (!ProcessUtil.isNull(payload.getFile())) {
                return new ResponseEntity<>(this.lookupDataCacheService.uploadLookup(payload), HttpStatus.OK);
            }
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR, "File not found for process."), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.error("An error occurred while uploadLookup ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Sorry File Not Upload Contact With Support"), HttpStatus.BAD_REQUEST);
        }
    }

}
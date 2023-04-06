package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.payload.request.FileUploadRequest;
import process.payload.request.LookupDataRequest;
import process.payload.response.AppResponse;
import process.service.LookupDataCacheService;
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
     * api-status :- done
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
     * api-status :- done
     * @apiName :- fetchAllLookup
     * Api use to fetch the lookup detail
     * @return ResponseEntity<?> fetchAllLookup
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchAllLookup", method = RequestMethod.POST)
    public ResponseEntity<?> fetchAllLookup(@RequestBody LookupDataRequest tempLookupData) {
        try {
            return new ResponseEntity<>(this.lookupDataCacheService.fetchAllLookup(tempLookupData), HttpStatus.OK);
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
     * @param lookupDataRequest
     * @return ResponseEntity<?> fetchSubLookupByParentId
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchSubLookupByParentId", method = RequestMethod.POST)
    public ResponseEntity<?> fetchSubLookupByParentId(@RequestBody LookupDataRequest lookupDataRequest) {
        try {
            return new ResponseEntity<>(this.lookupDataCacheService.fetchSubLookupByParentId(lookupDataRequest), HttpStatus.OK);
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
     * @param lookupDataRequest
     * @return ResponseEntity<?> fetchLookupByLookupType
     * */
    @RequestMapping(value = "/fetchLookupByLookupType", method = RequestMethod.POST)
    public ResponseEntity<?> fetchLookupByLookupType(@RequestBody LookupDataRequest lookupDataRequest) {
        try {
            return new ResponseEntity<>(this.lookupDataCacheService.fetchLookupByLookupType(lookupDataRequest), HttpStatus.OK);
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

    /**
     * api-status :- done
     * @apiName :- downloadLookupTemplateFile
     * Api use to download lookup template the lookup data
     * @return ResponseEntity<?> downloadLookupTemplateFile
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/downloadSourceJobTemplateFile", method = RequestMethod.GET)
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
    public ResponseEntity<?> downloadLookup(@RequestBody LookupDataRequest tempLookupData) {
        try {
            HttpHeaders headers = new HttpHeaders();
            DateFormat dateFormat = new SimpleDateFormat(ProcessUtil.SIMPLE_DATE_PATTERN);
            String fileName = "BatchLookupDownload-"+dateFormat.format(new Date())+"-"+ UUID.randomUUID() + ".xlsx";
            headers.add(ProcessUtil.CONTENT_DISPOSITION,ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers).body(
                this.lookupDataCacheService.downloadLookup(tempLookupData).toByteArray());
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
    public ResponseEntity<?> uploadLookup(FileUploadRequest fileObject) {
        try {
            if (fileObject.getFile() != null) {
                return new ResponseEntity<>(this.lookupDataCacheService.uploadLookup(fileObject), HttpStatus.OK);
            }
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "File not found for process."), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.error("An error occurred while uploadLookup ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Sorry File Not Upload Contact With Support"), HttpStatus.BAD_REQUEST);
        }
    }

}
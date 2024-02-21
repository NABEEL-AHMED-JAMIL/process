package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.payload.request.CredentialRequest;
import process.payload.response.AppResponse;
import process.service.CredentialService;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;

/**
 * Api use to perform crud operation
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/credential.json")
public class CredentialRestApi {

    private Logger logger = LoggerFactory.getLogger(CredentialRestApi.class);

    @Autowired
    private CredentialService credentialService;

    /**
     * api-status :- done
     * @apiName :- addCredential
     * Api use to add the credential data
     * @param payload
     * @return ResponseEntity<?> addCredential
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addCredential", method = RequestMethod.POST)
    public ResponseEntity<?> addCredential(@RequestBody CredentialRequest payload) {
        try {
            return new ResponseEntity<>(this.credentialService.addCredential(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addCredential ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- updateCredential
     * Api use to update the credential data
     * @param payload
     * @return ResponseEntity<?> updateCredential
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/updateCredential", method = RequestMethod.PUT)
    public ResponseEntity<?> updateCredential(@RequestBody CredentialRequest payload) {
        try {
            return new ResponseEntity<>(this.credentialService.updateCredential(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateCredential ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- fetchAllCredential
     * Api use to fetch the lookup detail
     * @return ResponseEntity<?> fetchAllCredential
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchAllCredential", method = RequestMethod.POST)
    public ResponseEntity<?> fetchAllCredential(@RequestBody CredentialRequest payload) {
        try {
            return new ResponseEntity<>(this.credentialService.fetchAllCredential(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllCredential ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- fetchCredentialByCredentialId
     * Api use to fetch the Credential by Credential id
     * @param payload
     * @return ResponseEntity<?> fetchCredentialByCredentialId
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/fetchCredentialByCredentialId", method = RequestMethod.POST)
    public ResponseEntity<?> fetchCredentialByCredentialId(@RequestBody CredentialRequest payload) {
        try {
            return new ResponseEntity<>(this.credentialService.fetchCredentialByCredentialId(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchCredentialByCredentialId ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * api-status :- done
     * @apiName :- deleteCredential
     * Api use to delete the lookup data
     * @param payload
     * @return ResponseEntity<?> deleteCredential
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteCredential", method = RequestMethod.PUT)
    public ResponseEntity<?> deleteCredential(@RequestBody CredentialRequest payload) {
        try {
            return new ResponseEntity<>(this.credentialService.deleteCredential(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteCredential ", ExceptionUtil.getRootCause(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }
}

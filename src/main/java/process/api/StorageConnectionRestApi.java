package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.ResponseDto;
import process.model.dto.StorageConnectionDto;
import process.model.service.StorageConnectionService;
import process.util.ProcessUtil;

/**
 * Managing storage connections means handling credentials, so this whole controller is
 * TENANT_ADMIN-only -- ordinary TENANT_USERs still browse the resulting buckets through
 * StorageBrowserRestApi, they just can't see or change how those buckets are connected.
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/storageConnection.json")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class StorageConnectionRestApi {

    private Logger logger = LoggerFactory.getLogger(StorageConnectionRestApi.class);

    private final StorageConnectionService storageConnectionService;

    public StorageConnectionRestApi(StorageConnectionService storageConnectionService) {
        this.storageConnectionService = storageConnectionService;
    }

    @RequestMapping(value = "/addConnection", method = RequestMethod.POST)
    public ResponseEntity<?> addConnection(@RequestBody StorageConnectionDto dto) {
        try {
            return new ResponseEntity<>(this.storageConnectionService.addConnection(dto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addConnection ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/updateConnection", method = RequestMethod.PUT)
    public ResponseEntity<?> updateConnection(@RequestBody StorageConnectionDto dto) {
        try {
            return new ResponseEntity<>(this.storageConnectionService.updateConnection(dto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateConnection ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/deleteConnection", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteConnection(@RequestParam Long storageConnectionId) {
        try {
            return new ResponseEntity<>(this.storageConnectionService.deleteConnection(storageConnectionId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteConnection ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/fetchAllConnections", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAllConnections() {
        try {
            return new ResponseEntity<>(this.storageConnectionService.fetchAllConnections(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllConnections ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/fetchConnectionById", method = RequestMethod.GET)
    public ResponseEntity<?> fetchConnectionById(@RequestParam Long storageConnectionId) {
        try {
            return new ResponseEntity<>(this.storageConnectionService.fetchConnectionById(storageConnectionId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchConnectionById ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/testConnection", method = RequestMethod.POST)
    public ResponseEntity<?> testConnection(@RequestParam Long storageConnectionId) {
        try {
            return new ResponseEntity<>(this.storageConnectionService.testConnection(storageConnectionId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while testConnection ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/discoverBuckets", method = RequestMethod.POST)
    public ResponseEntity<?> discoverBuckets(@RequestBody StorageConnectionDto dto) {
        try {
            return new ResponseEntity<>(this.storageConnectionService.discoverBuckets(dto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while discoverBuckets ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}

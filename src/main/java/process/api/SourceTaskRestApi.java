package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.payload.request.SourceTaskRequest;
import process.payload.response.AppResponse;
import process.service.SourceTaskApiService;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;

/**
 * Api use to perform crud operation on source-task
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/sourceTask.json")
public class SourceTaskRestApi {

    private Logger logger = LoggerFactory.getLogger(SourceTaskRestApi.class);

    @Autowired
    private SourceTaskApiService sourceTaskApiService;

    /**
     * Integration Status :- done
     * Api use to add the source task
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/addSourceTask", method = RequestMethod.POST)
    public ResponseEntity<?> addSourceTask(@RequestBody SourceTaskRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskApiService.addSourceTask(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSourceTask ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Api use to update the source task
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/editSourceTask", method = RequestMethod.PUT)
    public ResponseEntity<?> editSourceTask(@RequestBody SourceTaskRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskApiService.editSourceTask(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while editSourceTask ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Api use to delete the source task in soft
     * @param payload
     * @return ResponseEntity<?>
     * */
    @PreAuthorize("hasRole('MASTER_ADMIN') or hasRole('ADMIN')")
    @RequestMapping(value = "/deleteSourceTask", method = RequestMethod.PUT)
    public ResponseEntity<?> deleteSourceTask(@RequestBody SourceTaskRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskApiService.deleteSourceTask(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSourceTask ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

}
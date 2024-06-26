package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import process.payload.request.SearchTextRequest;
import process.payload.request.SourceTaskRequest;
import process.payload.response.AppResponse;
import process.service.SourceTaskApiService;
import process.util.PagingUtil;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

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
    @RequestMapping(value = "/addSourceTask", method = RequestMethod.POST)
    public ResponseEntity<?> addSourceTask(@RequestBody SourceTaskRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskApiService.addSourceTask(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSourceTask ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR_MESSAGE,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Api use to update the source task
     * @param payload
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/editSourceTask", method = RequestMethod.PUT)
    public ResponseEntity<?> editSourceTask(@RequestBody SourceTaskRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskApiService.editSourceTask(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while editSourceTask ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR_MESSAGE,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Api use to delete the source task in soft
     * @param payload
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/deleteSourceTask", method = RequestMethod.PUT)
    public ResponseEntity<?> deleteSourceTask(@RequestBody SourceTaskRequest payload) {
        try {
            return new ResponseEntity<>(this.sourceTaskApiService.deleteSourceTask(payload), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSourceTask ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Api use to fetch the sourceTask detail with pagination
     * @param appUserId
     * @param page
     * @param limit
     * @param startDate
     * @param endDate
     * @param columnName
     * @param order
     * @param searchTextDto
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/listSourceTask", method = RequestMethod.POST)
    public ResponseEntity<?> listSourceTask(
        @RequestParam(value = "appUserId", required = false) Long appUserId,
        @RequestParam(value = "page", required = false) Long page,
        @RequestParam(value = "limit", required = false) Long limit,
        @RequestParam(value = "startDate", required = false) String startDate,
        @RequestParam(value = "endDate", required = false) String endDate,
        @RequestParam(value = "columnName", required = false) String columnName,
        @RequestParam(value = "order", required = false) String order,
        @RequestBody(required = false) SearchTextRequest searchTextDto) {
        try {
            return new ResponseEntity<>(this.sourceTaskApiService.listSourceTask(appUserId, startDate, endDate, columnName, order,
                PagingUtil.ApplyPaging(columnName, order, page, limit), searchTextDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while listSourceTask ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR_MESSAGE,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Api use to fetch link jobs with source task with pagination
     * @param appUserId
     * @param page
     * @param limit
     * @param startDate
     * @param endDate
     * @param columnName
     * @param order
     * @param searchTextDto
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/fetchAllLinkJobsWithSourceTaskId", method = RequestMethod.POST)
    public ResponseEntity<?> fetchAllLinkJobsWithSourceTaskId(
        @RequestParam(value = "appUserId", required = false) Long appUserId,
        @RequestParam(value = "sourceTaskId") Long sourceTaskId,
        @RequestParam(value = "page", required = false) Long page,
        @RequestParam(value = "limit", required = false) Long limit,
        @RequestParam(value = "startDate", required = false) String startDate,
        @RequestParam(value = "endDate", required = false) String endDate,
        @RequestParam(value = "columnName", required = false) String columnName,
        @RequestParam(value = "order", required = false) String order,
        @RequestBody(required = false) SearchTextRequest searchTextDto) {
        try {
            return new ResponseEntity<>(this.sourceTaskApiService.fetchAllLinkJobsWithSourceTaskId(appUserId, sourceTaskId,
                startDate, endDate, columnName, order, PagingUtil.ApplyPaging(columnName, order, page, limit), searchTextDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllLinkJobsWithSourceTaskId ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Api use to fetch link task with source task type
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/fetchAllLinkSourceTaskWithSourceTaskTypeId", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAllLinkSourceTaskWithSourceTaskTypeId(
            @RequestParam(value = "sourceTaskTypeId", required = false) Long sourceTaskTypeId) {
        try {
            return new ResponseEntity<>(this.sourceTaskApiService.fetchAllLinkSourceTaskWithSourceTaskTypeId(sourceTaskTypeId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllLinkSourceTaskWithSourceTaskTypeId ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR_MESSAGE,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Api use to fetch source task detail with id
     * @param sourceTaskId
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/fetchSourceTaskWithSourceTaskId", method = RequestMethod.GET)
    public ResponseEntity<?> fetchSourceTaskWithSourceTaskId(@RequestParam(value = "sourceTaskId") Long sourceTaskId) {
        try {
            return new ResponseEntity<>(this.sourceTaskApiService.fetchSourceTaskWithSourceTaskId(sourceTaskId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSourceTaskWithSourceTaskId ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Api use to download the list source task
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/downloadListSourceTask", method = RequestMethod.GET)
    public ResponseEntity<?> downloadListSourceTask() {
        try {
            HttpHeaders headers = new HttpHeaders();
            DateFormat dateFormat = new SimpleDateFormat(ProcessUtil.SIMPLE_DATE_PATTERN);
            String fileName = "BatchDownload-"+dateFormat.format(new Date())+"-"+ UUID.randomUUID() + ".xlsx";
            headers.add(ProcessUtil.CONTENT_DISPOSITION,ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers).body(this.sourceTaskApiService.downloadListSourceTask().toByteArray());
        } catch (Exception ex) {
            logger.error("An error occurred while downloadListSourceTask ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new AppResponse(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

}
package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import process.model.dto.ResponseDto;
import process.model.dto.SearchTextDto;
import process.model.dto.TaskDetailDto;
import process.model.service.SourceTaskApiService;
import process.util.PagingUtil;
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
     * Method use to add the source task
     * */
    @RequestMapping(value = "/addSourceTask", method = RequestMethod.POST)
    public ResponseEntity<?> addSourceTask(@RequestBody TaskDetailDto tempTaskDetail) {
        try {
            return new ResponseEntity<>(this.sourceTaskApiService.addSourceTask(tempTaskDetail), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSourceTask ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Method use to update the source task
     * */
    @RequestMapping(value = "/updateSourceTask", method = RequestMethod.PUT)
    public ResponseEntity<?> updateSourceTask(@RequestBody TaskDetailDto tempTaskDetail) {
        try {
            return new ResponseEntity<>(this.sourceTaskApiService.updateSourceTask(tempTaskDetail), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateSourceTask ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Method use to delete the source task in soft
     * */
    @RequestMapping(value = "/deleteSourceTask", method = RequestMethod.PUT)
    public ResponseEntity<?> deleteSourceTask(@RequestBody TaskDetailDto tempTaskDetail) {
        try {
            return new ResponseEntity<>(this.sourceTaskApiService.deleteSourceTask(tempTaskDetail), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateSourceTask ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
        "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Method use to fetch the sourceTask detail with pagination
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
        @RequestBody(required = false) SearchTextDto searchTextDto) {
        try {
            return new ResponseEntity<>(this.sourceTaskApiService.listSourceTask(appUserId, startDate, endDate, columnName, order,
                PagingUtil.ApplyPaging(columnName, order, page, limit), searchTextDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while listSourceTask ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Method uses to get link jobs with source task
     * */
    @RequestMapping(value = "/fetchAllLinkJobsWithSourceTask", method = RequestMethod.POST)
    public ResponseEntity<?> fetchAllLinkJobsWithSourceTask(
        @RequestParam(value = "appUserId", required = false) Long appUserId,
        @RequestParam(value = "taskDetailId") Long taskDetailId,
        @RequestParam(value = "page", required = false) Long page,
        @RequestParam(value = "limit", required = false) Long limit,
        @RequestParam(value = "startDate", required = false) String startDate,
        @RequestParam(value = "endDate", required = false) String endDate,
        @RequestParam(value = "columnName", required = false) String columnName,
        @RequestParam(value = "order", required = false) String order,
        @RequestBody(required = false) SearchTextDto searchTextDto) {
        try {
            return new ResponseEntity<>(this.sourceTaskApiService.fetchAllLinkJobsWithSourceTask(appUserId, taskDetailId,
                startDate, endDate, columnName, order, PagingUtil.ApplyPaging(columnName, order, page, limit),
                searchTextDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllLinkJobsWithSourceTask ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Method uses to get source task detail with id
     * like :- json payload | validation detail |
     * */
    @RequestMapping(value = "/fetchSourceTaskDetailWithSourceTaskId", method = RequestMethod.GET)
    public ResponseEntity<?> fetchSourceTaskDetailWithSourceTaskId(@RequestParam(value = "taskDetailId") Long taskDetailId) {
        try {
            return new ResponseEntity<>(this.sourceTaskApiService.fetchSourceTaskDetailWithSourceTaskId(taskDetailId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllLinkJobsWithSourceTask ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Method uses to download the list source task
     * */
    @RequestMapping(value = "/downloadListSourceTask", method = RequestMethod.POST)
    public ResponseEntity<?> downloadListSourceTask(
            @RequestParam(value = "page", required = false) Long page,
            @RequestParam(value = "limit", required = false) Long limit,
            @RequestParam(value = "appUserId", required = false) Long appUserId,
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate,
            @RequestParam(value = "columnName", required = false) String columnName,
            @RequestParam(value = "order", required = false) String order,
            @RequestBody(required = false) SearchTextDto searchTextDto) {
        try {
            return new ResponseEntity<>(this.sourceTaskApiService.downloadListSourceTask(appUserId, startDate, endDate, columnName, order,
                PagingUtil.ApplyPaging(columnName, order, page, limit), searchTextDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllLinkJobsWithSourceTask ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

}

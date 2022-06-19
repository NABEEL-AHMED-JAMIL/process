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
 * Api use to perform crud operation on sourcetask
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
     * Method use to fetch the sourceTask detail with pagination
     * */
    @RequestMapping(value = "/listSourceTask", method = RequestMethod.POST)
    public ResponseEntity<?> listSourceTask(@RequestParam(value = "page") Long page, @RequestParam(value = "limit") Long limit,
        @RequestParam(value = "startDate") String startDate, @RequestParam(value = "endDate") String endDate,
        @RequestParam(value = "columnName") String columnName, @RequestParam(value = "order") String order,
        @RequestBody SearchTextDto searchTextDto) {
        try {
            return new ResponseEntity<>(this.sourceTaskApiService.listSourceTask(startDate, endDate,
                PagingUtil.ApplyPaging(columnName, order, page, limit), searchTextDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while listSourceTask ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                    "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Method use to get link job with source task
     * */
    @RequestMapping(value = "/fetchAllLinkJobsWithSourceTask", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAllLinkJobsWithSourceTask(@RequestParam Long taskDetailId) {
        try {
            return new ResponseEntity<>(this.sourceTaskApiService.fetchAllLinkJobsWithSourceTask(taskDetailId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllLinkJobsWithSourceTask ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }
}

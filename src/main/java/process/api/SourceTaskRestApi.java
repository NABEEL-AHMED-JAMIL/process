package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import process.model.dto.FileUploadDto;
import process.model.dto.ResponseDto;
import process.model.dto.SearchTextDto;
import process.model.dto.SourceTaskDto;
import process.model.service.SourceTaskService;
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

    private final SourceTaskService sourceTaskService;

    public SourceTaskRestApi(SourceTaskService sourceTaskService) {
        this.sourceTaskService = sourceTaskService;
    }

    /**
     * Api use to add the source task
     * @param sourceTaskDto
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/addSourceTask", method = RequestMethod.POST)
    public ResponseEntity<?> addSourceTask(@RequestBody SourceTaskDto sourceTaskDto) {
        try {
            return new ResponseEntity<>(this.sourceTaskService.addSourceTask(sourceTaskDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSourceTask :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**

     * Api use to update the source task
     * @param sourceTaskDto
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/updateSourceTask", method = RequestMethod.PUT)
    public ResponseEntity<?> updateSourceTask(@RequestBody SourceTaskDto sourceTaskDto) {
        try {
            return new ResponseEntity<>(this.sourceTaskService.updateSourceTask(sourceTaskDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateSourceTask :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**

     * Api use to delete the source task in soft
     * @param sourceTaskDto
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/deleteSourceTask", method = RequestMethod.PUT)
    public ResponseEntity<?> deleteSourceTask(@RequestBody SourceTaskDto sourceTaskDto) {
        try {
            return new ResponseEntity<>(this.sourceTaskService.deleteSourceTask(sourceTaskDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSourceTask :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**

     * Api use to fetch the sourceTask detail with pagination
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
        @RequestParam(value = "page", required = false) Long page,
        @RequestParam(value = "limit", required = false) Long limit,
        @RequestParam(value = "startDate", required = false) String startDate,
        @RequestParam(value = "endDate", required = false) String endDate,
        @RequestParam(value = "columnName", required = false) String columnName,
        @RequestParam(value = "order", required = false) String order,
        @RequestBody(required = false) SearchTextRequest searchTextDto) {
        try {
            return new ResponseEntity<>(this.sourceTaskService.listSourceTask(startDate, endDate, columnName,
                order, PagingUtil.ApplyPaging(columnName, order, page, limit), searchTextDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while listSourceTask :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**

     * Api use to fetch link jobs with source task with pagination
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
        @RequestParam(value = "sourceTaskId") Long sourceTaskId,
        @RequestParam(value = "page", required = false) Long page,
        @RequestParam(value = "limit", required = false) Long limit,
        @RequestParam(value = "startDate", required = false) String startDate,
        @RequestParam(value = "endDate", required = false) String endDate,
        @RequestParam(value = "columnName", required = false) String columnName,
        @RequestParam(value = "order", required = false) String order,
        @RequestBody(required = false) SearchTextRequest searchTextDto) {
        try {
            return new ResponseEntity<>(this.sourceTaskService.fetchAllLinkJobsWithSourceTaskId(sourceTaskId, startDate, endDate,
                columnName, order, PagingUtil.ApplyPaging(columnName, order, page, limit), searchTextDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllLinkJobsWithSourceTaskId :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**

     * Api use to fetch link task with source task type
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/fetchAllLinkSourceTaskWithSourceTaskTypeId", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAllLinkSourceTaskWithSourceTaskTypeId(
            @RequestParam(value = "sourceTaskTypeId", required = false) Long sourceTaskTypeId) {
        try {
            return new ResponseEntity<>(this.sourceTaskService.fetchAllLinkSourceTaskWithSourceTaskTypeId(sourceTaskTypeId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllLinkSourceTaskWithSourceTaskTypeId :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**

     * Api use to fetch source task detail with id
     * @param sourceTaskId
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/fetchSourceTaskWithSourceTaskId", method = RequestMethod.GET)
    public ResponseEntity<?> fetchSourceTaskWithSourceTaskId(@RequestParam(value = "sourceTaskId") Long sourceTaskId) {
        try {
            return new ResponseEntity<>(this.sourceTaskService.fetchSourceTaskWithSourceTaskId(sourceTaskId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSourceTaskWithSourceTaskId :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**

     * Api use to download the list source task
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/downloadListSourceTask", method = RequestMethod.GET)
    public ResponseEntity<?> downloadListSourceTask() {
        try {
            HttpHeaders headers = new HttpHeaders();
            DateFormat dateFormat = new SimpleDateFormat(ProcessUtil.SIMPLE_DATE_PATTERN);
            String fileName = "BatchDownload-"+dateFormat.format(new Date()) + "-" + UUID.randomUUID() + ProcessUtil.XLSX_EXTENSION;
            headers.add(ProcessUtil.CONTENT_DISPOSITION,ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers).body(this.sourceTaskService.downloadListSourceTask().toByteArray());
        } catch (Exception ex) {
            logger.error("An error occurred while downloadListSourceTask :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**

     * Api use to download the template
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/downloadSourceTaskTemplate", method = RequestMethod.GET)
    public ResponseEntity<?> downloadSourceTaskTemplate() {
        try {
            HttpHeaders headers = new HttpHeaders();
            DateFormat dateFormat = new SimpleDateFormat(ProcessUtil.SIMPLE_DATE_PATTERN);
            String fileName = "BatchDownload-"+dateFormat.format(new Date())+ "-" + UUID.randomUUID() + ProcessUtil.XLSX_EXTENSION;
            headers.add(ProcessUtil.CONTENT_DISPOSITION,ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers).body(this.sourceTaskService.downloadSourceTaskTemplate().toByteArray());
        } catch (Exception ex) {
            logger.error("An error occurred while downloadSourceTaskTemplate :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**

     * Api use to upload the source task
     * @param fileUploadDto
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/uploadSourceTask", method = RequestMethod.POST)
    public ResponseEntity<?> uploadSourceTask(FileUploadDto fileUploadDto) {
        try {
            if (fileUploadDto.getFile() != null) {
                return new ResponseEntity<>(this.sourceTaskService.uploadSourceTask(fileUploadDto), HttpStatus.OK);
            }
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, "File not found for process."), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.error("An error occurred while uploadSourceTask :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

}

package process.api;

import org.barco.platform.security.BuilderAction;
import process.util.BusinessTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import process.model.dto.FileUploadDto;
import process.model.dto.ResponseDto;
import process.model.dto.SearchTextDto;
import process.model.dto.SourceTaskDto;
import process.model.service.SourceTaskService;
import process.util.PagingUtil;
import process.util.ProcessUtil;
import java.util.UUID;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * @author Nabeel Ahmed
 * */
@RestController
@CrossOrigin(origins = "*", exposedHeaders = {HttpHeaders.CONTENT_DISPOSITION, HttpHeaders.CONTENT_LENGTH})
@RequestMapping(value = "/sourceTask.json")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class SourceTaskRestApi {

    /** MIG-305: the list and template downloads are workbooks, and say so -- they went out as application/json. */
    static final MediaType XLSX = MediaType.parseMediaType(ProcessUtil.SHEET_NAME);

    private Logger logger = LoggerFactory.getLogger(SourceTaskRestApi.class);

    private final SourceTaskService sourceTaskService;

    public SourceTaskRestApi(SourceTaskService sourceTaskService) {
        this.sourceTaskService = sourceTaskService;
    }

    @BuilderAction
    @RequestMapping(value = "/addSourceTask", method = RequestMethod.POST)
    public ResponseEntity<?> addSourceTask(@RequestBody SourceTaskDto sourceTaskDto) {
        try {
            return new ResponseEntity<>(this.sourceTaskService.addSourceTask(sourceTaskDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSourceTask :- {}.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @BuilderAction
    @RequestMapping(value = "/updateSourceTask", method = RequestMethod.PUT)
    public ResponseEntity<?> updateSourceTask(@RequestBody SourceTaskDto sourceTaskDto) {
        try {
            return new ResponseEntity<>(this.sourceTaskService.updateSourceTask(sourceTaskDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateSourceTask :- {}.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @BuilderAction
    @RequestMapping(value = "/deleteSourceTask", method = RequestMethod.PUT)
    public ResponseEntity<?> deleteSourceTask(@RequestBody SourceTaskDto sourceTaskDto) {
        try {
            return new ResponseEntity<>(this.sourceTaskService.deleteSourceTask(sourceTaskDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSourceTask :- {}.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/listSourceTask", method = RequestMethod.POST)
    public ResponseEntity<?> listSourceTask(
        @RequestParam(value = "page", required = false) Long page,
        @RequestParam(value = "limit", required = false) Long limit,
        @RequestParam(value = "startDate", required = false) String startDate,
        @RequestParam(value = "endDate", required = false) String endDate,
        @RequestParam(value = "columnName", required = false) String columnName,
        @RequestParam(value = "order", required = false) String order,
        @RequestBody(required = false) SearchTextDto searchTextDto) {
        try {
            return new ResponseEntity<>(this.sourceTaskService.listSourceTask(startDate, endDate, columnName,
                order, PagingUtil.applyPaging(columnName, order, page, limit), searchTextDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while listSourceTask :- {}.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/fetchAllLinkJobsWithSourceTaskId", method = RequestMethod.POST)
    public ResponseEntity<?> fetchAllLinkJobsWithSourceTaskId(
        @RequestParam(value = "sourceTaskId") Long sourceTaskId,
        @RequestParam(value = "page", required = false) Long page,
        @RequestParam(value = "limit", required = false) Long limit,
        @RequestParam(value = "startDate", required = false) String startDate,
        @RequestParam(value = "endDate", required = false) String endDate,
        @RequestParam(value = "columnName", required = false) String columnName,
        @RequestParam(value = "order", required = false) String order,
        @RequestBody(required = false) SearchTextDto searchTextDto) {
        try {
            return new ResponseEntity<>(this.sourceTaskService.fetchAllLinkJobsWithSourceTaskId(sourceTaskId, startDate, endDate,
                columnName, order, PagingUtil.applyPaging(columnName, order, page, limit), searchTextDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllLinkJobsWithSourceTaskId :- {}.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/fetchSourceTaskWithSourceTaskId", method = RequestMethod.GET)
    public ResponseEntity<?> fetchSourceTaskWithSourceTaskId(@RequestParam(value = "sourceTaskId") Long sourceTaskId) {
        try {
            return new ResponseEntity<>(this.sourceTaskService.fetchSourceTaskWithSourceTaskId(sourceTaskId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSourceTaskWithSourceTaskId :- {}.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/downloadListSourceTask", method = RequestMethod.GET)
    public ResponseEntity<?> downloadListSourceTask() {
        try {
            HttpHeaders headers = new HttpHeaders();
            String fileName = "BatchDownload-" + BusinessTime.today() + "-" + UUID.randomUUID() + ProcessUtil.XLSX_EXTENSION;
            headers.add(ProcessUtil.CONTENT_DISPOSITION, ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers).contentType(XLSX).body(this.sourceTaskService.downloadListSourceTask().toByteArray());
        } catch (Exception ex) {
            logger.error("An error occurred while downloadListSourceTask :- {}.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/downloadSourceTaskTemplate", method = RequestMethod.GET)
    public ResponseEntity<?> downloadSourceTaskTemplate() {
        try {
            HttpHeaders headers = new HttpHeaders();
            String fileName = "BatchDownload-" + BusinessTime.today() + "-" + UUID.randomUUID() + ProcessUtil.XLSX_EXTENSION;
            headers.add(ProcessUtil.CONTENT_DISPOSITION, ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers).contentType(XLSX).body(this.sourceTaskService.downloadSourceTaskTemplate().toByteArray());
        } catch (Exception ex) {
            logger.error("An error occurred while downloadSourceTaskTemplate :- {}.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @BuilderAction
    @RequestMapping(value = "/uploadSourceTask", method = RequestMethod.POST)
    public ResponseEntity<?> uploadSourceTask(FileUploadDto<?> fileUploadDto) {
        try {
            if (fileUploadDto.getFile() != null) {
                return new ResponseEntity<>(this.sourceTaskService.uploadSourceTask(fileUploadDto), HttpStatus.OK);
            }
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, "File not found for process."), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.error("An error occurred while uploadSourceTask :- {}.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}

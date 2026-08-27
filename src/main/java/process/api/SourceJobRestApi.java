package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.FileUploadDto;
import process.model.dto.ResponseDto;
import process.model.dto.JobAssistantRequestDto;
import process.model.service.impl.JobAssistantServiceImpl;
import process.model.dto.SourceJobDto;
import process.model.service.SourceJobService;
import process.model.service.SourceJobBulkService;
import process.util.ProcessUtil;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

/**
 * @author Nabeel Ahmed
 * */
@RestController
@CrossOrigin(origins = "*", exposedHeaders = {HttpHeaders.CONTENT_DISPOSITION, HttpHeaders.CONTENT_LENGTH})
@RequestMapping(value = "/sourceJob.json")
@PreAuthorize("hasRole('TENANT_USER')")
public class SourceJobRestApi {

    private Logger logger = LoggerFactory.getLogger(SourceJobRestApi.class);

    private final SourceJobService sourceJobService;
    private final SourceJobBulkService sourceJobBulkService;
    private final JobAssistantServiceImpl jobAssistantService;

    public SourceJobRestApi(SourceJobService sourceJobService,
        SourceJobBulkService sourceJobBulkService,
        JobAssistantServiceImpl jobAssistantService) {
        this.sourceJobService = sourceJobService;
        this.sourceJobBulkService = sourceJobBulkService;
        this.jobAssistantService = jobAssistantService;
    }

    @RequestMapping(value = "/addSourceJob", method = RequestMethod.POST)
    public ResponseEntity<?> addSourceJob(
        @RequestBody SourceJobDto tempSourceJob) {
        try {
            return new ResponseEntity<>(this.sourceJobService.addSourceJob(tempSourceJob), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSourceJob.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/updateSourceJob", method = RequestMethod.PUT)
    public ResponseEntity<?> updateSourceJob(
        @RequestBody SourceJobDto tempSourceJob) {
        try {
            return new ResponseEntity<>(this.sourceJobService.updateSourceJob(tempSourceJob), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateSourceJob.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/deleteSourceJob", method = RequestMethod.PUT)
    public ResponseEntity<?> deleteSourceJob(
        @RequestBody SourceJobDto tempSourceJob) {
        try {
            return new ResponseEntity<>(this.sourceJobService.deleteSourceJob(tempSourceJob), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSourceJob.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/toggleSourceJobStatus", method = RequestMethod.PUT)
    public ResponseEntity<?> toggleSourceJobStatus(
        @RequestBody SourceJobDto tempSourceJob) {
        try {
            return new ResponseEntity<>(this.sourceJobService.toggleSourceJobStatus(tempSourceJob), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while toggleSourceJobStatus.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/listSourceJob", method = RequestMethod.GET)
    public ResponseEntity<?> listSourceJob() {
        try {
            return new ResponseEntity<>(this.sourceJobService.listSourceJob(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while listSourceJob.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/fetchSourceJobDetailWithSourceJobId", method = RequestMethod.GET)
    public ResponseEntity<?> fetchSourceJobDetailWithSourceJobId(
        @RequestParam(value = "jobId") Long jobId) {
        try {
            return new ResponseEntity<>(this.sourceJobService.fetchSourceJobDetailWithSourceJobId(jobId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllLinkJobsWithSourceTask.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/fetchSourceJobQueueListWithJobId", method = RequestMethod.GET)
    public ResponseEntity<?> fetchSourceJobQueueListWithJobId(
        @RequestParam(value = "jobId") Long jobId) {
        try {
            return new ResponseEntity<>(this.sourceJobService.fetchSourceJobQueueListWithJobId(jobId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchSourceJobQueueListWithJobId.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/runSourceJob", method = RequestMethod.POST)
    public ResponseEntity<?> runSourceJob(
        @RequestBody SourceJobDto tempSourceJob) {
        try {
            return new ResponseEntity<>(this.sourceJobService.runSourceJob(tempSourceJob), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while runSourceJob.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/skipNextSourceJob", method = RequestMethod.POST)
    public ResponseEntity<?> skipNextSourceJob(
        @RequestBody SourceJobDto tempSourceJob) {
        try {
            return new ResponseEntity<>(this.sourceJobService.skipNextSourceJob(tempSourceJob), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while skipNextSourceJob.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * A question about one job, answered by a configured AI agent. The job's facts are gathered
     * server-side from the id, so a caller cannot supply another job's data as context, and the
     * agent's key never leaves the server.
     */
    @RequestMapping(value = "/askAssistant", method = RequestMethod.POST)
    public ResponseEntity<?> askAssistant(@RequestBody JobAssistantRequestDto requestDto) {
        try {
            return new ResponseEntity<>(this.jobAssistantService.ask(requestDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while askAssistant.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/findSourceJobAuditLog", method = RequestMethod.GET)
    public ResponseEntity<?> findSourceJobAuditLog(
        @RequestParam Long jobQueueId,
        @RequestParam Long jobId) {
        try {
            return new ResponseEntity<>(this.sourceJobService.findSourceJobAuditLog(jobQueueId, jobId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while findSourceJobAuditLog.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/downloadSourceJobTemplateFile", method = RequestMethod.GET)
    public ResponseEntity<?> downloadSourceJobTemplateFile() {
        try {
            HttpHeaders headers = new HttpHeaders();
            DateFormat dateFormat = new SimpleDateFormat(ProcessUtil.SIMPLE_DATE_PATTERN);
            String fileName = "BatchDownload-"+dateFormat.format(new Date())+"-"+ UUID.randomUUID() + ".xlsx";
            headers.add(ProcessUtil.CONTENT_DISPOSITION,ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers).body(this.sourceJobBulkService.downloadSourceJobTemplateFile().toByteArray());
        } catch (Exception ex) {
            logger.error("An error occurred while downloading source job template xlsx file.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, "Sorry, the file could not be downloaded. Please contact support."), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/downloadListSourceJob", method = RequestMethod.GET)
    public ResponseEntity<?> downloadListSourceJob() {
        try {
            HttpHeaders headers = new HttpHeaders();
            DateFormat dateFormat = new SimpleDateFormat(ProcessUtil.SIMPLE_DATE_PATTERN);
            String fileName = "BatchDownload-"+dateFormat.format(new Date())+"-"+ UUID.randomUUID() + ".xlsx";
            headers.add(ProcessUtil.CONTENT_DISPOSITION,ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers).body(this.sourceJobBulkService.downloadListSourceJob().toByteArray());
        } catch (Exception ex) {
            logger.error("An error occurred while downloadListSourceJob.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/uploadSourceJob", method = RequestMethod.POST)
    public ResponseEntity<?> uploadSourceJob(
        FileUploadDto fileObject) {
        try {
            if (fileObject.getFile() != null) {
                return new ResponseEntity<>(this.sourceJobBulkService.uploadSourceJob(fileObject), HttpStatus.OK);
            }
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, "File not found for process."), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.error("An error occurred while uploadSourceJob.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, "Sorry, the file could not be uploaded. Please contact support."), HttpStatus.BAD_REQUEST);
        }
    }

}

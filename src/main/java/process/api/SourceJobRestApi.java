package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import process.model.dto.FileUploadDto;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobDto;
import process.model.service.SourceJobService;
import process.model.service.SourceJobBulkService;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

/**
 * Api use to perform crud operation on source job
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/sourceJob.json")
public class SourceJobRestApi {

    private Logger logger = LoggerFactory.getLogger(SourceJobRestApi.class);

    private final SourceJobService sourceJobService;
    private final SourceJobBulkService sourceJobBulkService;

    public SourceJobRestApi(SourceJobService sourceJobService,
                            SourceJobBulkService sourceJobBulkService) {
        this.sourceJobService = sourceJobService;
        this.sourceJobBulkService = sourceJobBulkService;
    }

    /**
     * Integration Status :- done
     * Api use to add the source job
     * @param tempSourceJob
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/addSourceJob", method = RequestMethod.POST)
    public ResponseEntity<?> addSourceJob(
        @RequestBody SourceJobDto tempSourceJob) {
        try {
            return new ResponseEntity<>(this.sourceJobService.addSourceJob(tempSourceJob), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSourceJob :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Api use to update the source job
     * @param tempSourceJob
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/updateSourceJob", method = RequestMethod.PUT)
    public ResponseEntity<?> updateSourceJob(
        @RequestBody SourceJobDto tempSourceJob) {
        try {
            return new ResponseEntity<>(this.sourceJobService.updateSourceJob(tempSourceJob), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateSourceJob :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Api use to delete the source job in soft
     * @param tempSourceJob
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/deleteSourceJob", method = RequestMethod.PUT)
    public ResponseEntity<?> deleteSourceJob(
        @RequestBody SourceJobDto tempSourceJob) {
        try {
            return new ResponseEntity<>(this.sourceJobService.deleteSourceJob(tempSourceJob), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSourceJob :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- done
     * Api use to fetch the source job list
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/listSourceJob", method = RequestMethod.GET)
    public ResponseEntity<?> listSourceJob() {
        try {
            return new ResponseEntity<>(this.sourceJobService.listSourceJob(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while listSourceJob :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Method uses to get source job detail with id
     * like :- json payload | validation detail |
     * @param jobId
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/fetchSourceJobDetailWithSourceJobId", method = RequestMethod.GET)
    public ResponseEntity<?> fetchSourceJobDetailWithSourceJobId(
        @RequestParam(value = "jobId") Long jobId) {
        try {
            return new ResponseEntity<>(this.sourceJobService.fetchSourceJobDetailWithSourceJobId(jobId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllLinkJobsWithSourceTask :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Method uses to runSourceJob manual
     * @param tempSourceJob
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/runSourceJob", method = RequestMethod.POST)
    public ResponseEntity<?> runSourceJob(
        @RequestBody SourceJobDto tempSourceJob) {
        try {
            return new ResponseEntity<>(this.sourceJobService.runSourceJob(tempSourceJob), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while runSourceJob :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Integration Status :- pending
     * Method uses to skip the sourceJob
     * @param tempSourceJob
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/skipNextSourceJob", method = RequestMethod.POST)
    public ResponseEntity<?> skipNextSourceJob(
        @RequestBody SourceJobDto tempSourceJob) {
        try {
            return new ResponseEntity<>(this.sourceJobService.skipNextSourceJob(tempSourceJob), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while skipNextSourceJob :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Method uses to skip the sourceJob
     * @param jobQueueId
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/findSourceJobAuditLog", method = RequestMethod.GET)
    public ResponseEntity<?> findSourceJobAuditLog(
        @RequestParam Long jobQueueId,
        @RequestParam Long jobId) {
        try {
            return new ResponseEntity<>(this.sourceJobService.findSourceJobAuditLog(jobQueueId, jobId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while findSourceJobAuditLog :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * The method used to download the template file for batch scheduler
     * with the list of timezone and frequency or triggerDetail
     * @return ResponseEntity<?>
     */
    @RequestMapping(value = "/downloadSourceJobTemplateFile", method = RequestMethod.GET)
    public ResponseEntity<?> downloadSourceJobTemplateFile() {
        try {
            HttpHeaders headers = new HttpHeaders();
            DateFormat dateFormat = new SimpleDateFormat(ProcessUtil.SIMPLE_DATE_PATTERN);
            String fileName = "BatchDownload-"+dateFormat.format(new Date())+"-"+ UUID.randomUUID() + ".xlsx";
            headers.add(ProcessUtil.CONTENT_DISPOSITION,ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers).body(this.sourceJobBulkService.downloadSourceJobTemplateFile().toByteArray());
        } catch (Exception ex) {
            logger.error("An error occurred while downloadSourceJobTemplateFile xlsx file :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, "Sorry File Not Downland, Contact With Support"), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * The method used to download the file for batch scheduler
     * @return ResponseEntity<?>
     */
    @RequestMapping(value = "/downloadListSourceJob", method = RequestMethod.GET)
    public ResponseEntity<?> downloadListSourceJob() {
        try {
            HttpHeaders headers = new HttpHeaders();
            DateFormat dateFormat = new SimpleDateFormat(ProcessUtil.SIMPLE_DATE_PATTERN);
            String fileName = "BatchDownload-"+dateFormat.format(new Date())+"-"+ UUID.randomUUID() + ".xlsx";
            headers.add(ProcessUtil.CONTENT_DISPOSITION,ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers).body(this.sourceJobBulkService.downloadListSourceJob().toByteArray());
        } catch (Exception ex) {
            logger.error("An error occurred while downloadListSourceJob :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * The method used to upload the batch file for batch scheduler
     * note :- validation process may-fail the process of upload file
     * and share the detail
     * @param fileObject
     * @return ResponseEntity<?>
     */
    @RequestMapping(value = "/uploadSourceJob", method = RequestMethod.POST)
    public ResponseEntity<?> uploadSourceJob(
        FileUploadDto fileObject) {
        try {
            if (fileObject.getFile() != null) {
                return new ResponseEntity<>(this.sourceJobBulkService.uploadSourceJob(fileObject), HttpStatus.OK);
            }
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, "File not found for process."), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.error("An error occurred while uploadSourceJob :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, "Sorry File Not Upload Contact With Support"), HttpStatus.BAD_REQUEST);
        }
    }

}

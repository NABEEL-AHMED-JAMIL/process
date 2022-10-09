package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import process.model.dto.FileUploadDto;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobDto;
import process.model.service.SourceJobApiService;
import process.model.service.SourceJobBulkApiService;
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

    @Autowired
    private SourceJobApiService sourceJobApiService;
    @Autowired
    private SourceJobBulkApiService sourceJobBulkApiService;

    @RequestMapping(value = "/addSourceJob", method = RequestMethod.POST)
    public ResponseEntity<?> addSourceJob(@RequestBody SourceJobDto tempSourceJob) {
        try {
            return new ResponseEntity<>(this.sourceJobApiService.addSourceJob(tempSourceJob), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addSourceJob ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/updateSourceJob", method = RequestMethod.PUT)
    public ResponseEntity<?> updateSourceJob(@RequestBody SourceJobDto tempSourceJob) {
        try {
            return new ResponseEntity<>(this.sourceJobApiService.updateSourceJob(tempSourceJob), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while uploadSourceJob ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to delete the source job in soft
     * @param tempSourceJob
     * @return ResponseEntity<?> deleteSourceTask
     * */
    @RequestMapping(value = "/deleteSourceJob", method = RequestMethod.PUT)
    public ResponseEntity<?> deleteSourceJob(@RequestBody SourceJobDto tempSourceJob) {
        try {
            return new ResponseEntity<>(this.sourceJobApiService.deleteSourceJob(tempSourceJob), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteSourceJob ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    @RequestMapping(value = "/listSourceJob", method = RequestMethod.POST)
    public ResponseEntity<?> listSourceJob() {
        try {
            return new ResponseEntity<>(this.sourceJobApiService.listSourceJob(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while listSourceJob ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * note: this api call ever 1 mint from the ui by using the timer and update the below detail on the table
     * jobId, jobStatus, Next Flight, Last Run, R-Status
     * */
    @RequestMapping(value = "/fetchRunningJobEvent", method = RequestMethod.POST)
    public ResponseEntity<?> fetchRunningJobEvent(@RequestBody SourceJobDto tempSourceJob) {
        try {
            return new ResponseEntity<>(this.sourceJobApiService.fetchRunningJobEvent(tempSourceJob), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchRunningJobEvent ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Method uses to get source job detail with id
     * like :- json payload | validation detail |
     * */
    @RequestMapping(value = "/fetchSourceJobDetailWithSourceJobId", method = RequestMethod.GET)
    public ResponseEntity<?> fetchSourceJobDetailWithSourceJobId(@RequestParam(value = "jobId") Long jobDetailId) {
        try {
            return new ResponseEntity<>(this.sourceJobApiService.fetchSourceJobDetailWithSourceJobId(jobDetailId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllLinkJobsWithSourceTask ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Method uses to runSourceJob manual
     * @param tempSourceJob
     * */
    @RequestMapping(value = "/runSourceJob", method = RequestMethod.POST)
    public ResponseEntity<?> runSourceJob(@RequestBody SourceJobDto tempSourceJob) {
        try {
            return new ResponseEntity<>(this.sourceJobApiService.runSourceJob(tempSourceJob), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while runSourceJob ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Method uses to skip the sourceJob
     * @param tempSourceJob
     * */
    @RequestMapping(value = "/skipNextSourceJob", method = RequestMethod.POST)
    public ResponseEntity<?> skipNextSourceJob(@RequestBody SourceJobDto tempSourceJob) {
        try {
            return new ResponseEntity<>(this.sourceJobApiService.skipNextSourceJob(tempSourceJob), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while skipNextSourceJob ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * The method used to download the template file for batch scheduler
     * with the list of timezone and frequency or triggerDetail
     * @return ResponseEntity<?> downloadSourceJobTemplateFile
     */
    @RequestMapping(value = "/downloadSourceJobTemplateFile", method = RequestMethod.GET)
    public ResponseEntity<?> downloadSourceJobTemplateFile() {
        try {
            HttpHeaders headers = new HttpHeaders();
            DateFormat dateFormat = new SimpleDateFormat(ProcessUtil.SIMPLE_DATE_PATTERN);
            String fileName = "BatchDownload-"+dateFormat.format(new Date())+"-"+ UUID.randomUUID() + ".xlsx";
            headers.add(ProcessUtil.CONTENT_DISPOSITION,ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers)
                .body(this.sourceJobBulkApiService.downloadSourceJobTemplateFile().toByteArray());
        } catch (Exception ex) {
            logger.error("An error occurred while downloadSourceJobTemplateFile xlsx file",
                ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            "Sorry File Not Downland, Contact With Support"), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * The method used to download the file for batch scheduler
     * @return ResponseEntity<?> downloadListSourceJob
     */
    @RequestMapping(value = "/downloadListSourceJob", method = RequestMethod.GET)
    public ResponseEntity<?> downloadListSourceJob() {
        try {
            HttpHeaders headers = new HttpHeaders();
            DateFormat dateFormat = new SimpleDateFormat(ProcessUtil.SIMPLE_DATE_PATTERN);
            String fileName = "BatchDownload-"+dateFormat.format(new Date())+"-"+ UUID.randomUUID() + ".xlsx";
            headers.add(ProcessUtil.CONTENT_DISPOSITION,ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers)
                .body(this.sourceJobBulkApiService.downloadListSourceJob().toByteArray());
        } catch (Exception ex) {
            logger.error("An error occurred while downloadListSourceJob ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                "Some internal error occurred contact with support."), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * The method used to upload the batch file for batch scheduler
     * note :- validation process may-fail the process of upload file
     * and share the detail
     * @param object
     * @return ResponseEntity<?> uploadSourceJob
     */
    @RequestMapping(value = "/uploadSourceJob", method = RequestMethod.POST)
    public ResponseEntity<?> uploadSourceJob(FileUploadDto object) {
        try {
            if (object.getFile() != null) {
                return new ResponseEntity<>(this.sourceJobBulkApiService.uploadSourceJob(object), HttpStatus.OK);
            }
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                "File not found for process."), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.error("An error occurred while uploadSourceJob ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                "Sorry File Not Upload Contact With Support"), HttpStatus.BAD_REQUEST);
        }
    }

}

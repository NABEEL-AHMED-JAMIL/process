package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import process.model.dto.FileUploadDto;
import process.model.dto.JobStatusChangeDto;
import process.model.dto.ResponseDto;
import process.model.service.SchedulerApiService;
import process.util.exception.ExceptionUtil;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

/**
 * Api use to perform crud operation on scheduler
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/scheduler.json")
public class SchedulerRestApi {

    private Logger logger = LoggerFactory.getLogger(SchedulerRestApi.class);

    @Autowired
    private SchedulerApiService schedulerApiService;

    /**
     * The method used to download the template file for batch scheduler
     * with the list of timezone and frequency or triggerDetail
     * @return ResponseEntity<?> downloadBatchSchedulerTemplateFile
     */
    @RequestMapping(value = "/downloadBatchSchedulerTemplateFile", method = RequestMethod.GET)
    public ResponseEntity<?> downloadBatchSchedulerTemplateFile() {
        logger.info("##### downloadBatchSchedulerTemplateFile Start");
        try {
            HttpHeaders headers = new HttpHeaders();
            DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
            String fileName = "BatchUpload-"+dateFormat.format(new Date())+"-"+ UUID.randomUUID() + ".xlsx";
            headers.add("Content-Disposition", "attachment; filename=" + fileName);
            logger.info("##### downloadBatchSchedulerTemplateFile End");
            return ResponseEntity.ok().headers(headers).body(new InputStreamResource(
                this.schedulerApiService.downloadBatchSchedulerTemplateFile()));
        } catch (Exception ex) {
            logger.error("An error occurred while downloadBatchSchedulerTemplateFile xlsx file", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto("ERROR", "Sorry File Not Downland, Contact With Support"),
                HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * The method used to upload the batch file for batch scheduler
     * note :- validation process may-fail the process of upload file
     * and share the detail
     * @param object
     * @return ResponseEntity<?> uploadBatchSchedulerFile
     */
    @RequestMapping(value = "/uploadBatchSchedulerFile", method = RequestMethod.POST)
    public ResponseEntity<?> uploadBatchSchedulerFile(FileUploadDto object) {
        logger.info("##### uploadBatchSchedulerFile Start");
        try {
            if (object.getFile() != null) {
                logger.info("##### uploadBatchSchedulerFile End");
                return new ResponseEntity<>(this.schedulerApiService.uploadJobFile(object), HttpStatus.OK);
            } else {
                logger.info("##### uploadBatchSchedulerFile End");
                return new ResponseEntity<>(
                    new ResponseDto("ERROR", "File not found for process."), HttpStatus.BAD_REQUEST);
            }
        } catch (Exception ex) {
            logger.error("An error occurred while uploadBatchSchedulerFile ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto("ERROR", "Sorry File Not Upload Contact With Support"),
                HttpStatus.BAD_REQUEST);
        }
    }


    /**
     * This method use to change jobRunningAction
     * @param jobStatusChange
     * @return ResponseEntity<?> stopJob
     */
    @RequestMapping(value = "/jobRunningAction", method = RequestMethod.PUT)
    public ResponseEntity<?> jobRunningAction(@RequestBody JobStatusChangeDto jobStatusChange) {
        logger.info("##### jobRunningAction Start");
        try {
            if (jobStatusChange.getJobId() != null && jobStatusChange.getJobStatus() != null) {
                logger.info("##### jobRunningAction End");
                return new ResponseEntity<>(this.schedulerApiService.jobRunningAction(jobStatusChange), HttpStatus.OK);
            } else {
                logger.info("##### jobRunningAction End");
                return new ResponseEntity<>(
                        new ResponseDto("ERROR", jobStatusChange), HttpStatus.BAD_REQUEST);
            }
        } catch (Exception ex) {
            logger.error("An error occurred while jobRunningAction ", ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto("ERROR", "Sorry File Not Upload Contact With Support"),
                    HttpStatus.BAD_REQUEST);
        }
    }

}

package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import process.model.dto.*;
import process.model.service.SourceJobBulkApiService;
import process.util.ProcessUtil;
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
@RequestMapping(value = "/sourceJob.json")
public class SourceJobBulkRestApi {

    private Logger logger = LoggerFactory.getLogger(SourceJobBulkRestApi.class);

    @Autowired
    private SourceJobBulkApiService sourceJobBulkApiService;

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
     * @return ResponseEntity<?> downloadListSourceJobBatchScheduler
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
            logger.error("An error occurred while uploadSourceJob ",
                ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                "Sorry File Not Upload Contact With Support"), HttpStatus.BAD_REQUEST);
        }
    }

}
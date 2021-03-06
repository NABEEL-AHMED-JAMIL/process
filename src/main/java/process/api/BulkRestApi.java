package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import process.model.dto.*;
import process.model.service.BulkApiService;
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
@RequestMapping(value = "/bulk.json")
public class BulkRestApi {

    private Logger logger = LoggerFactory.getLogger(BulkRestApi.class);

    @Autowired
    private BulkApiService bulkApiService;

    /**
     * The method used to download the template file for batch scheduler
     * with the list of timezone and frequency or triggerDetail
     * @return ResponseEntity<?> downloadBatchSchedulerTemplateFile
     */
    @RequestMapping(value = "/downloadBatchSchedulerTemplateFile", method = RequestMethod.GET)
    public ResponseEntity<?> downloadBatchSchedulerTemplateFile() {
        try {
            HttpHeaders headers = new HttpHeaders();
            DateFormat dateFormat = new SimpleDateFormat(ProcessUtil.SIMPLE_DATE_PATTERN);
            String fileName = "BatchUpload-"+dateFormat.format(new Date())+"-"+ UUID.randomUUID() + ".xlsx";
            headers.add(ProcessUtil.CONTENT_DISPOSITION,ProcessUtil.FILE_NAME_HEADER + fileName);
            return ResponseEntity.ok().headers(headers).body(new InputStreamResource(
                    this.bulkApiService.downloadBatchSchedulerTemplateFile()));
        } catch (Exception ex) {
            logger.error("An error occurred while downloadBatchSchedulerTemplateFile xlsx file",
                ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                "Sorry File Not Downland, Contact With Support"), HttpStatus.BAD_REQUEST);
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
        try {
            if (object.getFile() != null) {
                return new ResponseEntity<>(this.bulkApiService.uploadJobFile(object), HttpStatus.OK);
            }
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                "File not found for process."), HttpStatus.BAD_REQUEST);
        } catch (Exception ex) {
            logger.error("An error occurred while uploadBatchSchedulerFile ",
                ExceptionUtil.getRootCauseMessage(ex));
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                "Sorry File Not Upload Contact With Support"), HttpStatus.BAD_REQUEST);
        }
    }

}
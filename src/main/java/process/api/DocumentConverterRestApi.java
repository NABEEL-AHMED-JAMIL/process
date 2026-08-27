package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import process.model.dto.ResponseDto;
import process.model.service.DocumentConverterService;
import process.util.ProcessUtil;

/**
 * @author Nabeel Ahmed
 * */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/documentConverter.json")
@PreAuthorize("hasRole('TENANT_USER')")
public class DocumentConverterRestApi {

    private Logger logger = LoggerFactory.getLogger(DocumentConverterRestApi.class);

    private final DocumentConverterService documentConverterService;

    public DocumentConverterRestApi(DocumentConverterService documentConverterService) {
        this.documentConverterService = documentConverterService;
    }

    @RequestMapping(value = "/supportedFormats", method = RequestMethod.GET)
    public ResponseEntity<?> supportedFormats() {
        try {
            return new ResponseEntity<>(this.documentConverterService.supportedFormats(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while supportedFormats ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/fetchAllTasks", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAllTasks() {
        try {
            return new ResponseEntity<>(this.documentConverterService.fetchAllTasks(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllTasks ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/fetchTaskById", method = RequestMethod.GET)
    public ResponseEntity<?> fetchTaskById(@RequestParam Long documentConverterTaskId) {
        try {
            return new ResponseEntity<>(this.documentConverterService.fetchTaskById(documentConverterTaskId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchTaskById ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/convert", method = RequestMethod.POST)
    public ResponseEntity<?> convert(
        @RequestParam("file") MultipartFile file,
        @RequestParam String outputFormat,
        @RequestParam(required = false) String bucketName,
        @RequestParam(required = false) String targetFolder,
        @RequestParam(required = false) String taskName,
        @RequestParam(defaultValue = "false") boolean save) {
        try {
            return new ResponseEntity<>(this.documentConverterService.convert(file, outputFormat, bucketName, targetFolder, taskName, save), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while convert ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/deleteTask", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteTask(@RequestParam Long documentConverterTaskId) {
        try {
            return new ResponseEntity<>(this.documentConverterService.deleteTask(documentConverterTaskId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteTask ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}

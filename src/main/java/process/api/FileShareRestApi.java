package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.FileShareRequestDto;
import process.model.dto.ResponseDto;
import process.model.service.FileShareService;
import process.util.ProcessUtil;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/fileShare.json")
@PreAuthorize("hasRole('TENANT_USER')")
public class FileShareRestApi {

    private Logger logger = LoggerFactory.getLogger(FileShareRestApi.class);

    private final FileShareService fileShareService;

    public FileShareRestApi(FileShareService fileShareService) {
        this.fileShareService = fileShareService;
    }

    @RequestMapping(value = "/send", method = RequestMethod.POST)
    public ResponseEntity<?> send(@RequestBody FileShareRequestDto requestDto) {
        try {
            return new ResponseEntity<>(this.fileShareService.emailFile(requestDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while emailing a file/folder ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}

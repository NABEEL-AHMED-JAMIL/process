package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.FileChatExportRequestDto;
import process.model.dto.FileChatMessageRequestDto;
import process.model.dto.FileChatPrepareRequestDto;
import process.model.dto.ResponseDto;
import process.model.service.FileChatService;
import process.util.ProcessUtil;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/fileChat.json")
@PreAuthorize("hasRole('TENANT_USER')")
public class FileChatRestApi {

    private Logger logger = LoggerFactory.getLogger(FileChatRestApi.class);

    private final FileChatService fileChatService;

    public FileChatRestApi(FileChatService fileChatService) {
        this.fileChatService = fileChatService;
    }

    @RequestMapping(value = "/prepareContext", method = RequestMethod.POST)
    public ResponseEntity<?> prepareContext(@RequestBody FileChatPrepareRequestDto requestDto) {
        try {
            return new ResponseEntity<>(
                this.fileChatService.prepareContext(requestDto.getBucket(), requestDto.getKey()), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while prepareContext ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/endSession", method = RequestMethod.POST)
    public ResponseEntity<?> endSession(@RequestBody FileChatPrepareRequestDto requestDto) {
        try {
            return new ResponseEntity<>(
                this.fileChatService.endSession(requestDto.getBucket(), requestDto.getKey()), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while endSession ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/sendMessage", method = RequestMethod.POST)
    public ResponseEntity<?> sendMessage(@RequestBody FileChatMessageRequestDto requestDto) {
        try {
            return new ResponseEntity<>(this.fileChatService.sendMessage(requestDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while sendMessage ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/exportFile", method = RequestMethod.POST)
    public ResponseEntity<?> exportFile(@RequestBody FileChatExportRequestDto requestDto) {
        try {
            return new ResponseEntity<>(this.fileChatService.exportFile(requestDto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while exportFile ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}

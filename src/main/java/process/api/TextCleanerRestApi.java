package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.ResponseDto;
import process.model.dto.TextCleanRequestDto;
import process.util.ProcessUtil;
import process.util.TextCleanerUtil;

/**
 * Api use to clean raw extracted text (PDF/OCR/copy-paste noise) into a tight, plain block --
 * stateless, no persistence. Callable from anywhere: the Content Cleaner UI, a Source Task, a
 * Dynamic Form, or an external caller such as the job-search Python listeners (already required
 * a valid JWT before this change too -- this endpoint was never in SecurityConfig's permitAll
 * list, unlike /changeState and /addLogs). Role policy: self-service tool, TENANT_USER+.
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/textCleaner.json")
@PreAuthorize("hasRole('TENANT_USER')")
public class TextCleanerRestApi {

    private Logger logger = LoggerFactory.getLogger(TextCleanerRestApi.class);

    /**
     * Api use to clean raw text -- request { "text": "..." }, response data holds the cleaned string
     * @param textCleanRequestDto
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/clean", method = RequestMethod.POST)
    public ResponseEntity<?> clean(@RequestBody TextCleanRequestDto textCleanRequestDto) {
        try {
            String cleaned = TextCleanerUtil.clean(
                textCleanRequestDto != null ? textCleanRequestDto.getText() : null);
            return new ResponseEntity<>(
                new ResponseDto(ProcessUtil.SUCCESS, "Text cleaned.", cleaned), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while clean ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

}

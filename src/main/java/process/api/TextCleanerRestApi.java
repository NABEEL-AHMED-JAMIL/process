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
 * @author Nabeel Ahmed
 * */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/textCleaner.json")
@PreAuthorize("hasRole('TENANT_USER')")
public class TextCleanerRestApi {

    private Logger logger = LoggerFactory.getLogger(TextCleanerRestApi.class);

    @RequestMapping(value = "/clean", method = RequestMethod.POST)
    public ResponseEntity<?> clean(@RequestBody TextCleanRequestDto textCleanRequestDto) {
        try {
            String cleaned = TextCleanerUtil.clean(
                textCleanRequestDto != null ? textCleanRequestDto.getText() : null);
            return new ResponseEntity<>(
                new ResponseDto(ProcessUtil.SUCCESS, "Text cleaned.", cleaned), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while clean ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}

package process.media.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import process.media.text.ObjectTextServiceImpl;
import process.model.dto.ResponseDto;
import process.util.ProcessUtil;

/**
 * An object as text -- Media & Documents', moved out of AiPromptRestApi on the same
 * /aiPrompt.json/objectText path so the console did not change (MIG-40).
 *
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/aiPrompt.json")
public class ObjectTextRestApi {

    private final Logger logger = LoggerFactory.getLogger(ObjectTextRestApi.class);
    private final ObjectTextServiceImpl objectText;

    public ObjectTextRestApi(ObjectTextServiceImpl objectText) {
        this.objectText = objectText;
    }

    /**
     * A file in a bucket as the text a variable can hold -- read the way the file chat reads
     * it, whatever the type. Try it fills a variable from it; the storage browser's own check
     * decides whether the caller may see the object at all.
     */
    @PreAuthorize("hasRole('TENANT_USER')")
    @RequestMapping(value = "/objectText", method = RequestMethod.GET)
    public ResponseEntity<?> objectText(@RequestParam String bucket, @RequestParam String key, @RequestParam(required = false) Integer maxChars) {
        try { return new ResponseEntity<>(this.objectText.read(bucket, key, maxChars), HttpStatus.OK); } catch (Exception ex) { return this.failed("objectText", ex); }
    }

    private ResponseEntity<?> failed(String what, Exception ex) {
        this.logger.error("An error occurred while {} prompt", what, ex);
        return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
    }
}

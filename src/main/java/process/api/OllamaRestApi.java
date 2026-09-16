package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.ResponseDto;
import process.model.service.OllamaService;
import process.util.ProcessUtil;

/**
 * @author Nabeel Ahmed
 * */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/ollama.json")
/*
 * Reading the catalogue is a tenant administrator's business; changing it is not.
 *
 * There is ONE Ollama server behind this controller and no tenant dimension to a model at all --
 * every workspace draws on the same catalogue. The class used to carry hasRole('TENANT_ADMIN') for
 * all three endpoints, which meant any tenant administrator could delete a model every other
 * workspace depended on, or pull gigabytes onto shared disk. Deleting the model an agent or a
 * pipeline is configured against breaks that workspace silently and from the outside.
 *
 * So the read stays where it was and the two writes move up. Declared per method rather than on
 * the class, so adding an endpoint here is a decision about who may call it rather than an
 * inheritance nobody re-reads.
 */
public class OllamaRestApi {

    private Logger logger = LoggerFactory.getLogger(OllamaRestApi.class);

    private final OllamaService ollamaService;

    public OllamaRestApi(OllamaService ollamaService) {
        this.ollamaService = ollamaService;
    }

    /** Knowing what is available is harmless, and an agent cannot be configured without it. */
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    @RequestMapping(value = "/listModels", method = RequestMethod.GET)
    public ResponseEntity<?> listModels() {
        try {
            return new ResponseEntity<>(
                new ResponseDto(ProcessUtil.SUCCESS, "Data found.", this.ollamaService.listModels()), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while listModels ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR,
                "Could not reach Ollama: " + ex.getMessage()), HttpStatus.BAD_REQUEST);
        }
    }

    /** Spends shared disk and shared bandwidth, so it is the platform's decision. */
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @RequestMapping(value = "/pullModel", method = RequestMethod.POST)
    public ResponseEntity<?> pullModel(@RequestParam String name) {
        try {
            this.ollamaService.pullModel(name);
            return new ResponseEntity<>(
                new ResponseDto(ProcessUtil.SUCCESS, String.format("Model \"%s\" pulled.", name)), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while pullModel ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR,
                "Could not pull model: " + ex.getMessage()), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Destructive across every workspace at once, which is why it is the narrowest of the three.
     * A model removed here stops an agent or a pipeline in a workspace whose administrator had no
     * part in the decision and no way to see it coming.
     */
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @RequestMapping(value = "/deleteModel", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteModel(@RequestParam String name) {
        try {
            this.ollamaService.deleteModel(name);
            return new ResponseEntity<>(
                new ResponseDto(ProcessUtil.SUCCESS, String.format("Model \"%s\" deleted.", name)), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteModel ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR,
                "Could not delete model: " + ex.getMessage()), HttpStatus.BAD_REQUEST);
        }
    }

}

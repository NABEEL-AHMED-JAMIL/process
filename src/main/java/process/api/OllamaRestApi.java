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
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class OllamaRestApi {

    private Logger logger = LoggerFactory.getLogger(OllamaRestApi.class);

    private final OllamaService ollamaService;

    public OllamaRestApi(OllamaService ollamaService) {
        this.ollamaService = ollamaService;
    }

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

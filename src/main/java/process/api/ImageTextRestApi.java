package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import process.model.dto.ResponseDto;
import process.model.service.ImageTextService;
import process.util.ProcessUtil;

/**
 * Api use to run an uploaded image (optionally cropped to a user-marked region) through the
 * ad-hoc OCR pipeline (proxies job-search's standalone Audio Extract Service, /extract/image).
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/imageText.json")
public class ImageTextRestApi {

    private Logger logger = LoggerFactory.getLogger(ImageTextRestApi.class);

    private final ImageTextService imageTextService;

    public ImageTextRestApi(ImageTextService imageTextService) {
        this.imageTextService = imageTextService;
    }

    /**
     * Api use to extract text (optionally just a marked region) from an uploaded image
     * @param file
     * @param x
     * @param y
     * @param width
     * @param height
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/extractFromImage", method = RequestMethod.POST)
    public ResponseEntity<?> extractFromImage(
        @RequestParam("file") MultipartFile file,
        @RequestParam(required = false) Integer x,
        @RequestParam(required = false) Integer y,
        @RequestParam(required = false) Integer width,
        @RequestParam(required = false) Integer height) {
        try {
            return new ResponseEntity<>(this.imageTextService.extractFromImage(file, x, y, width, height), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while extractFromImage ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()), HttpStatus.BAD_REQUEST);
        }
    }

}

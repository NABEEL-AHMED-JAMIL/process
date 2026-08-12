package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import process.model.dto.AudioExtractBucketRequestDto;
import process.model.dto.ResponseDto;
import process.model.dto.YoutubeExtractRequestDto;
import process.model.service.AudioTranscriptService;
import process.util.ProcessUtil;

/**
 * Api use to run an audio file (upload or an existing bucket object) through the ad-hoc
 * transcript-extraction pipeline (proxies job-search's standalone Audio Extract Service).
 * Role policy: self-service AI tool, TENANT_USER+.
 * @author Nabeel Ahmed
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/audioTranscript.json")
@PreAuthorize("hasRole('TENANT_USER')")
public class AudioTranscriptRestApi {

    private Logger logger = LoggerFactory.getLogger(AudioTranscriptRestApi.class);

    private final AudioTranscriptService audioTranscriptService;

    public AudioTranscriptRestApi(AudioTranscriptService audioTranscriptService) {
        this.audioTranscriptService = audioTranscriptService;
    }

    /**
     * Api use to extract a transcript from an uploaded audio file
     * @param file
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/extractFromUpload", method = RequestMethod.POST)
    public ResponseEntity<?> extractFromUpload(
        @RequestParam("file") MultipartFile file,
        @RequestParam(required = false, defaultValue = "false") boolean timestamps) {
        try {
            return new ResponseEntity<>(this.audioTranscriptService.extractFromUpload(file, timestamps), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while extractFromUpload ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to extract a transcript from an audio file already sitting in a bucket
     * @param request
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/extractFromBucket", method = RequestMethod.POST)
    public ResponseEntity<?> extractFromBucket(@RequestBody AudioExtractBucketRequestDto request) {
        try {
            return new ResponseEntity<>(this.audioTranscriptService.extractFromBucket(request), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while extractFromBucket ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to extract a transcript from an uploaded video file (audio track only)
     * @param file
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/extractFromVideoUpload", method = RequestMethod.POST)
    public ResponseEntity<?> extractFromVideoUpload(
        @RequestParam("file") MultipartFile file,
        @RequestParam(required = false, defaultValue = "false") boolean timestamps) {
        try {
            return new ResponseEntity<>(this.audioTranscriptService.extractFromVideoUpload(file, timestamps), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while extractFromVideoUpload ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to extract a transcript from a YouTube link
     * @param request
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/extractFromYoutube", method = RequestMethod.POST)
    public ResponseEntity<?> extractFromYoutube(@RequestBody YoutubeExtractRequestDto request) {
        try {
            return new ResponseEntity<>(this.audioTranscriptService.extractFromYoutube(request), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while extractFromYoutube ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()), HttpStatus.BAD_REQUEST);
        }
    }

}

package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import process.model.dto.KafkaConnectionProfileDto;
import process.model.dto.ResponseDto;
import process.model.service.KafkaConnectionProfileService;
import process.util.ProcessUtil;

/**
 * @author Nabeel Ahmed
 * */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/kafkaConnectionProfile.json")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class KafkaConnectionProfileRestApi {

    private Logger logger = LoggerFactory.getLogger(KafkaConnectionProfileRestApi.class);

    private final KafkaConnectionProfileService kafkaConnectionProfileService;

    public KafkaConnectionProfileRestApi(KafkaConnectionProfileService kafkaConnectionProfileService) {
        this.kafkaConnectionProfileService = kafkaConnectionProfileService;
    }

    @RequestMapping(value = "/addProfile", method = RequestMethod.POST)
    public ResponseEntity<?> addProfile(@RequestBody KafkaConnectionProfileDto dto) {
        try {
            return new ResponseEntity<>(this.kafkaConnectionProfileService.addProfile(dto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addProfile ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/updateProfile", method = RequestMethod.PUT)
    public ResponseEntity<?> updateProfile(@RequestBody KafkaConnectionProfileDto dto) {
        try {
            return new ResponseEntity<>(this.kafkaConnectionProfileService.updateProfile(dto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateProfile ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/deleteProfile", method = RequestMethod.PUT)
    public ResponseEntity<?> deleteProfile(@RequestParam Long kafkaConnectionProfileId) {
        try {
            return new ResponseEntity<>(this.kafkaConnectionProfileService.deleteProfile(kafkaConnectionProfileId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteProfile ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/fetchAllProfiles", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAllProfiles() {
        try {
            return new ResponseEntity<>(this.kafkaConnectionProfileService.fetchAllProfiles(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllProfiles ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/setAsDefault", method = RequestMethod.POST)
    public ResponseEntity<?> setAsDefault(@RequestParam Long kafkaConnectionProfileId) {
        try {
            return new ResponseEntity<>(this.kafkaConnectionProfileService.setAsDefault(kafkaConnectionProfileId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while setAsDefault ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/clearDefault", method = RequestMethod.POST)
    public ResponseEntity<?> clearDefault() {
        try {
            return new ResponseEntity<>(this.kafkaConnectionProfileService.clearDefault(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while clearDefault ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/testConnection", method = RequestMethod.POST)
    public ResponseEntity<?> testConnection(@RequestBody KafkaConnectionProfileDto dto) {
        try {
            return new ResponseEntity<>(this.kafkaConnectionProfileService.testConnection(dto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while testConnection ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/testTopic", method = RequestMethod.GET)
    public ResponseEntity<?> testTopic(@RequestParam String topicName) {
        try {
            return new ResponseEntity<>(this.kafkaConnectionProfileService.testTopicConnection(topicName), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while testTopic ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}

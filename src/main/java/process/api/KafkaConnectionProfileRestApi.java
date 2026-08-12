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
 * Api use to perform crud operation on user-configured Kafka connection profiles (local or
 * remote clusters), and to set-default/test them. Role policy: infra config, TENANT_ADMIN+ --
 * scoped to the caller's own tenant's profiles (plus platform-wide/shared ones) at the service
 * layer, see KafkaConnectionProfileServiceImpl.scopedFind.
 * @author Nabeel Ahmed
 */
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

    /**
     * Api use to add a new Kafka connection profile
     * @param dto
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/addProfile", method = RequestMethod.POST)
    public ResponseEntity<?> addProfile(@RequestBody KafkaConnectionProfileDto dto) {
        try {
            return new ResponseEntity<>(this.kafkaConnectionProfileService.addProfile(dto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while addProfile ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to update an existing Kafka connection profile
     * @param dto
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/updateProfile", method = RequestMethod.PUT)
    public ResponseEntity<?> updateProfile(@RequestBody KafkaConnectionProfileDto dto) {
        try {
            return new ResponseEntity<>(this.kafkaConnectionProfileService.updateProfile(dto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while updateProfile ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to soft-delete a Kafka connection profile
     * @param kafkaConnectionProfileId
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/deleteProfile", method = RequestMethod.PUT)
    public ResponseEntity<?> deleteProfile(@RequestParam Long kafkaConnectionProfileId) {
        try {
            return new ResponseEntity<>(this.kafkaConnectionProfileService.deleteProfile(kafkaConnectionProfileId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while deleteProfile ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to fetch every non-deleted Kafka connection profile
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/fetchAllProfiles", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAllProfiles() {
        try {
            return new ResponseEntity<>(this.kafkaConnectionProfileService.fetchAllProfiles(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while fetchAllProfiles ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to make the given profile the caller's tenant (or the platform's) default Kafka
     * connection, clearing that flag on every other profile in the same scope
     * @param kafkaConnectionProfileId
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/setAsDefault", method = RequestMethod.POST)
    public ResponseEntity<?> setAsDefault(@RequestParam Long kafkaConnectionProfileId) {
        try {
            return new ResponseEntity<>(this.kafkaConnectionProfileService.setAsDefault(kafkaConnectionProfileId), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while setAsDefault ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to clear the caller's tenant (or the platform's) default Kafka connection,
     * falling back the rest of KafkaConnectionResolver's chain
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/clearDefault", method = RequestMethod.POST)
    public ResponseEntity<?> clearDefault() {
        try {
            return new ResponseEntity<>(this.kafkaConnectionProfileService.clearDefault(), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while clearDefault ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to test connectivity for a profile (saved by id, or an in-flight unsaved one)
     * without making it the active connection
     * @param dto
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/testConnection", method = RequestMethod.POST)
    public ResponseEntity<?> testConnection(@RequestBody KafkaConnectionProfileDto dto) {
        try {
            return new ResponseEntity<>(this.kafkaConnectionProfileService.testConnection(dto), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while testConnection ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Api use to test that a specific topic (e.g. a Source TaskType's queueTopicPartition
     * topic) is reachable on whichever Kafka cluster is currently effective
     * @param topicName
     * @return ResponseEntity<?>
     * */
    @RequestMapping(value = "/testTopic", method = RequestMethod.GET)
    public ResponseEntity<?> testTopic(@RequestParam String topicName) {
        try {
            return new ResponseEntity<>(this.kafkaConnectionProfileService.testTopicConnection(topicName), HttpStatus.OK);
        } catch (Exception ex) {
            logger.error("An error occurred while testTopic ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500), HttpStatus.BAD_REQUEST);
        }
    }

}

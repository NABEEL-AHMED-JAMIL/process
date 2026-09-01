package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import process.model.dto.ResponseDto;
import process.model.enums.KafkaSecretKind;
import process.model.service.KafkaSecretService;
import process.util.ProcessUtil;

import java.util.List;

/**
 * Uploading the certificates a Kafka connection needs, and turning them into stores.
 *
 * Its own controller rather than more endpoints on the profile one, because these carry files
 * rather than a row and the two have different failure modes worth keeping apart.
 *
 * TENANT_ADMIN, matching KafkaConnectionProfileRestApi: whoever may create the connection is
 * exactly who may give it its certificates, and a role hierarchy means a platform admin is
 * included. Anything finer than that happens per-object inside the service, which decides who may
 * point at a stored file rather than who may reach this controller.
 *
 * @author Nabeel Ahmed
 * */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/kafkaSecret.json")
@PreAuthorize("hasRole('TENANT_ADMIN')")
public class KafkaSecretRestApi {

    private final Logger logger = LoggerFactory.getLogger(KafkaSecretRestApi.class);

    private final KafkaSecretService kafkaSecretService;

    public KafkaSecretRestApi(KafkaSecretService kafkaSecretService) {
        this.kafkaSecretService = kafkaSecretService;
    }

    @RequestMapping(value = "/uploadSecret", method = RequestMethod.POST)
    public ResponseEntity<?> uploadSecret(@RequestParam("file") MultipartFile file,
        @RequestParam KafkaSecretKind kind) {
        try {
            return new ResponseEntity<>(this.kafkaSecretService.uploadSecret(file, kind), HttpStatus.OK);
        } catch (Exception ex) {
            // The message is withheld deliberately: everything a caller can usefully act on is
            // already returned as a validation failure above, and what reaches here is internal.
            this.logger.error("An error occurred while uploadSecret.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500),
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/generateTruststore", method = RequestMethod.POST)
    public ResponseEntity<?> generateTruststore(@RequestBody List<String> caObjectKeys) {
        try {
            return new ResponseEntity<>(this.kafkaSecretService.generateTruststore(caObjectKeys), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while generateTruststore.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500),
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/generateKeystore", method = RequestMethod.POST)
    public ResponseEntity<?> generateKeystore(@RequestParam String certificateObjectKey,
        @RequestParam String privateKeyObjectKey) {
        try {
            return new ResponseEntity<>(
                this.kafkaSecretService.generateKeystore(certificateObjectKey, privateKeyObjectKey), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while generateKeystore.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ProcessUtil.INTERNAL_ERROR_500),
                HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}

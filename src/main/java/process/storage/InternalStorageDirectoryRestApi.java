package process.storage;

import org.barco.platform.tenancy.AcrossTenants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import process.model.enums.Status;
import process.model.pojo.KafkaConnectionProfile;
import process.model.repository.KafkaConnectionProfileRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What Storage asks Core about the connections it now owns (MIG-68 B): which Kafka profiles name a
 * storage alias for a keystore or truststore -- so a connection is not renamed, retired or deleted
 * out from under one. Storage decides which of the named profiles actually resolve to a given
 * connection; Core only says which name it. Who a user id is, Storage asks identity-service
 * (/internal/identity/people); the /userNames lookup that used to sit here is gone (MIG-329).
 *
 * Internal-token only: the answer is not scoped to a signed-in caller.
 */
@RestController
@RequestMapping("/internal/storageDirectory")
public class InternalStorageDirectoryRestApi {

    private final Logger logger = LoggerFactory.getLogger(InternalStorageDirectoryRestApi.class);
    private final KafkaConnectionProfileRepository profiles;
    private final byte[] token;

    public InternalStorageDirectoryRestApi(KafkaConnectionProfileRepository profiles,
        @Value("${internal.service-token:}") String token) {
        this.profiles = profiles;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** Body {"alias": "..."}; answers [{profileName, tenantId (null for a platform profile), alias}]. */
    @PostMapping(value = "/kafkaReferences", produces = MediaType.APPLICATION_JSON_VALUE)
    @AcrossTenants("storage asks which Kafka profiles of any workspace name an object before it deletes it (service token)")
    public ResponseEntity<?> kafkaReferences(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody Map<String, Object> body) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        Object alias = body == null ? null : body.get("alias");
        if (alias == null || alias.toString().trim().isEmpty()) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
        String wanted = alias.toString().trim();
        List<Map<String, Object>> references = new ArrayList<>();
        for (KafkaConnectionProfile profile : this.profiles.findVisibleToPlatformAdmin(Status.Delete)) {
            if (wanted.equals(profile.getSslTruststoreBucket()) || wanted.equals(profile.getSslKeystoreBucket())) {
                Map<String, Object> reference = new LinkedHashMap<>();
                reference.put("profileName", profile.getProfileName());
                reference.put("tenantId", profile.getTenantId());
                reference.put("alias", wanted);
                references.add(reference);
            }
        }
        return new ResponseEntity<>(references, HttpStatus.OK);
    }

    private boolean admits(String presented) {
        boolean ok = this.token.length > 0 && presented != null
            && MessageDigest.isEqual(this.token, presented.trim().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            this.logger.warn("Refused a storage directory lookup without the internal token.");
        }
        return ok;
    }
}

package process.storage;

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
import process.util.UserNameResolver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What Storage asks Core about the connections it now owns (MIG-68 B): which Kafka profiles name a
 * storage alias for a keystore or truststore -- so a connection is not renamed, retired or deleted
 * out from under one -- and who the user ids stamped on a connection are. Storage decides which of
 * the named profiles actually resolve to a given connection; Core only says which name it.
 *
 * Internal-token only: neither answer is scoped to a signed-in caller.
 */
@RestController
@RequestMapping("/internal/storageDirectory")
public class InternalStorageDirectoryRestApi {

    private final Logger logger = LoggerFactory.getLogger(InternalStorageDirectoryRestApi.class);
    private final KafkaConnectionProfileRepository profiles;
    private final UserNameResolver names;
    private final byte[] token;

    public InternalStorageDirectoryRestApi(KafkaConnectionProfileRepository profiles, UserNameResolver names,
        @Value("${internal.service-token:}") String token) {
        this.profiles = profiles;
        this.names = names;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    /** Body {"alias": "..."}; answers [{profileName, tenantId (null for a platform profile), alias}]. */
    @PostMapping(value = "/kafkaReferences", produces = MediaType.APPLICATION_JSON_VALUE)
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

    /** Body {"ids": [..]}; answers {"<id>": "<display name>"} for the ids that are still people. */
    @PostMapping(value = "/userNames", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> userNames(@RequestHeader(value = "X-Internal-Token", required = false) String presented,
        @RequestBody Map<String, Object> body) {
        if (!this.admits(presented)) {
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        Set<Long> ids = new HashSet<>();
        Object raw = body == null ? null : body.get("ids");
        if (raw instanceof Collection) {
            for (Object id : (Collection<?>) raw) {
                if (id instanceof Number) {
                    ids.add(((Number) id).longValue());
                }
            }
        }
        Map<String, String> answer = new LinkedHashMap<>();
        for (Map.Entry<Long, String> name : this.names.namesFor(ids).entrySet()) {
            answer.put(String.valueOf(name.getKey()), name.getValue());
        }
        return new ResponseEntity<>(answer, HttpStatus.OK);
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

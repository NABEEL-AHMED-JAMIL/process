package process.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Optional;

/**
 * Where Notifications redeems a secretRef (MIG-22 part 4). Service to service only: the caller
 * proves itself with INTERNAL_SERVICE_TOKEN, compared in constant time, and the gateway refuses
 * /api/v1/internal from outside. With no token configured the endpoint is shut, not open.
 *
 * @author Nabeel Ahmed
 */
@RestController
@RequestMapping("/internal/secretRef")
public class InternalSecretRestApi {

    private final Logger logger = LoggerFactory.getLogger(InternalSecretRestApi.class);
    private final OneTimeSecrets secrets;
    private final byte[] token;

    public InternalSecretRestApi(OneTimeSecrets secrets, @Value("${internal.service-token:}") String token) {
        this.secrets = secrets;
        this.token = token == null ? new byte[0] : token.trim().getBytes(StandardCharsets.UTF_8);
    }

    // JSON always: jackson-dataformat-xml is on this classpath, and without an Accept header
    // content negotiation would answer in XML.
    @PostMapping(value = "/{ref}/redeem", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> redeem(@PathVariable String ref,
        @RequestHeader(value = "X-Internal-Token", required = false) String presented) {
        if (this.token.length == 0 || presented == null
            || !MessageDigest.isEqual(this.token, presented.trim().getBytes(StandardCharsets.UTF_8))) {
            this.logger.warn("Refused a secretRef redemption without the internal token.");
            return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
        }
        Optional<String> secret = this.secrets.redeem(ref);
        if (!secret.isPresent()) {
            return new ResponseEntity<>(HttpStatus.NOT_FOUND);
        }
        return new ResponseEntity<>(Collections.singletonMap("secret", secret.get()), HttpStatus.OK);
    }
}

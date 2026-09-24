package process.identity;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import process.security.signing.SigningKeys;

import java.util.concurrent.TimeUnit;

/**
 * GET /internal/jwks: Identity's public signing keys, as a JSON Web Key Set (MIG-92, P11).
 *
 * Public keys are not secrets, so no token is asked for -- every service reads this at start and
 * whenever a token names a key it has not seen. It is /internal all the same: the gateway answers 404
 * for /internal from outside, so the keys are served to the services, not to the internet.
 */
@RestController
@RequestMapping("/internal")
public class InternalJwksRestApi {

    private final SigningKeys keys;

    public InternalJwksRestApi(SigningKeys keys) {
        this.keys = keys;
    }

    @GetMapping(value = "/jwks", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> jwks() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS)).body(this.keys.jwks());
    }
}

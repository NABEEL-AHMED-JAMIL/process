package process.security.signing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Makes or opens the active signing key at startup, so a key that cannot be opened is found then and
 * not by the first person to sign in (MIG-92).
 *
 * While process still signs HS256 a failure here is an ERROR and startup goes on: nothing is signed with
 * the key yet, and /internal/jwks answering nothing is safer than no process. Once it signs RS256 the
 * key is every sign-in, and startup stops.
 */
@Component
public class SigningKeyWarmup implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(SigningKeyWarmup.class);

    private final SigningKeys keys;
    private final String signingAlgorithm;

    public SigningKeyWarmup(SigningKeys keys, @Value("${jwt.signing-algorithm:HS256}") String signingAlgorithm) {
        this.keys = keys;
        this.signingAlgorithm = signingAlgorithm == null ? "HS256" : signingAlgorithm.trim();
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            logger.info("Signing tokens {}; RS256 key {} is published at /internal/jwks.", this.signingAlgorithm,
                this.keys.active().getKid());
        } catch (RuntimeException ex) {
            if ("RS256".equalsIgnoreCase(this.signingAlgorithm)) {
                throw ex;
            }
            logger.error("No RS256 signing key could be made or opened; /internal/jwks is empty until it can: {}", ex.getMessage(), ex);
        }
    }
}

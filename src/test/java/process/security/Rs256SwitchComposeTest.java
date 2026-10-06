package process.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The owner's RS256 switch (MIG-92) goes through process/.env: JWT_ACCEPT_HS256=false, then JWT_SECRET_KEY
 * removed. Compose must hand the switch to process_app (it read jwt.accept-hs256 only from its default, so
 * the switch never reached it), and must still bring process up once the secret is gone (it refused to).
 */
class Rs256SwitchComposeTest {

    private static String compose() throws Exception {
        return new String(Files.readAllBytes(Paths.get("docker-compose.yml")), StandardCharsets.UTF_8);
    }

    @Test
    void theHs256SwitchReachesProcess() throws Exception {
        assertThat(compose()).contains("JWT_ACCEPT_HS256: ${JWT_ACCEPT_HS256:-true}");
    }

    @Test
    void processComesUpWithoutTheSharedSecret() throws Exception {
        assertThat(compose()).doesNotContain("${JWT_SECRET_KEY:?").contains("JWT_SECRET_KEY: ${JWT_SECRET_KEY:-}");
    }
}

package process.config;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MinioClient.builder().credentials(null, null) does not fail -- it builds a client that signs
 * nothing, so the bucket answers only if it happens to be world-readable and what the operator
 * sees is a 403 on the first object rather than the missing configuration behind it. Worse, a
 * bucket that IS world-readable is then reached by a deployment that believed it was
 * authenticating. The bean refuses to build instead.
 *
 * @author Nabeel Ahmed
 */
public class MinioAnonymousClientTest {

    /** No Spring context here, so the property-backed fields are set the way Spring would. */
    private MinioConfig configuredWith(String endpoint, String accessKey, String secretKey) throws Exception {
        MinioConfig config = new MinioConfig();
        this.set(config, "endpoint", endpoint);
        this.set(config, "accessKey", accessKey);
        this.set(config, "secretKey", secretKey);
        return config;
    }

    private void set(MinioConfig config, String name, String value) throws Exception {
        Field field = MinioConfig.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(config, value);
    }

    @Test
    void aMissingAccessKeyIsRefusedRatherThanBuiltAnonymously() throws Exception {
        MinioConfig config = this.configuredWith("http://localhost:9000", "", "someSecret");
        IllegalStateException thrown = assertThrows(IllegalStateException.class, config::minioClient);
        assertTrue(thrown.getMessage().contains("MINIO_ACCESS_KEY"),
            "the refusal should name what to set: " + thrown.getMessage());
    }

    @Test
    void aMissingSecretIsRefusedToo() throws Exception {
        MinioConfig config = this.configuredWith("http://localhost:9000", "someAccessKey", null);
        assertThrows(IllegalStateException.class, config::minioClient);
    }

    @Test
    void theEndpointIsStillCheckedFirst() throws Exception {
        MinioConfig config = this.configuredWith("  ", "someAccessKey", "someSecret");
        IllegalStateException thrown = assertThrows(IllegalStateException.class, config::minioClient);
        assertTrue(thrown.getMessage().contains("MINIO_ENDPOINT"),
            "an unset endpoint should still be reported as itself: " + thrown.getMessage());
    }

    @Test
    void aFullyConfiguredClientStillBuilds() throws Exception {
        // Nothing is dialled here -- building the client only sets up the SDK's own state -- so
        // this guards the check against tightening into something no valid deployment passes.
        MinioConfig config = this.configuredWith("http://localhost:9000", "someAccessKey", "someSecret");
        assertDoesNotThrow(config::minioClient);
    }

}

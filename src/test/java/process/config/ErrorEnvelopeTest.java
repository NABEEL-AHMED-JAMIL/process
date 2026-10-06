package process.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring Boot's own error answer (a 401 before any controller, an unmapped path) keeps its "message" field (MIG-204).
 * Boot 2.5+ leaves it out by default; the console and the api-check characterisation read the envelope
 * {timestamp, status, error, message, path} as Boot 2.3 wrote it, so the upgrade restores it.
 */
class ErrorEnvelopeTest {

    @Test
    void bootsErrorAnswerKeepsItsMessage() throws Exception {
        Properties properties = new Properties();
        try (InputStream in = ErrorEnvelopeTest.class.getClassLoader().getResourceAsStream("application.properties")) {
            properties.load(in);
        }
        assertThat(properties.getProperty("server.error.include-message")).isEqualTo("always");
    }
}

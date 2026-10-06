package process.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the profiles have to say out loud.
 *
 * Several security decisions are enforced by an @Value default in Java and were written nowhere
 * else, so the only way to find out a property existed was to read the class that reads it --
 * which is how a deployment ends up on the fail-closed default by accident rather than on
 * purpose. Each one is now declared per profile; this fails the build if a profile loses one.
 *
 * The second half is the rule that matters more: a property that carries a credential is
 * declared with an empty default and never a value, so nothing here can be deployed by someone
 * who never supplied one.
 *
 * @author Nabeel Ahmed
 */
public class ApplicationPropertiesDeclarationTest {

    private static final List<String> PROFILES =
        Arrays.asList("application-dev.properties", "application-stage.properties", "application-prod.properties");

    /** Read by code that would otherwise silently take its own default. */
    private static final List<String> REQUIRED_IN_EVERY_PROFILE = Arrays.asList(
        "storage.allow-instance-role",
        "kafka.secret-cache.dir",
        "kafka.ssl.local-store-dir",
        "kafka.topic.default-replication-factor",
        "worker.callback.token",
        "platform.admin.bootstrap-password",
        // The meter is a switch too: blank means the console runs unmetered, and an operator
        // must be able to see that is what is happening.
        "meter.url",
        "meter.service-key",
        // Whether the API documentation is generated at all (SwaggerConfig; owner, 2026-09-24).
        "process.api-docs.enabled");

    /** Nothing in this list may ever be committed with a value beside it. */
    private static final List<String> SECRETS = Arrays.asList(
        "worker.callback.token",
        "platform.admin.bootstrap-password",
        "jwt.secret.key",
        "lookup.encryption.key",
        "aws.access-key",
        "aws.secret-key",
        "meter.service-key");

    private Properties load(String resource) throws IOException {
        InputStream stream = this.getClass().getClassLoader().getResourceAsStream(resource);
        assertNotNull(stream, resource + " is missing from the classpath");
        try {
            Properties properties = new Properties();
            properties.load(stream);
            return properties;
        } finally {
            stream.close();
        }
    }

    @Test
    void everyProfileDeclaresThePropertiesItsCodeReads() throws IOException {
        for (String profile : PROFILES) {
            Properties properties = this.load(profile);
            for (String key : REQUIRED_IN_EVERY_PROFILE) {
                assertTrue(properties.containsKey(key),
                    profile + " does not declare " + key + ", so nobody deploying it can discover it exists");
            }
        }
    }

    /**
     * The converter and transcription ceilings used to be pinned here. They left with the code that
     * reads them (MIG-48) and are declared in media-service's application.yml, side by side as they
     * were here. A copy left behind would be a setting that looks live and changes nothing; what
     * process declares instead is where Media is.
     */
    @Test
    void theMediaCeilingsLeftWithMediaAndProcessSaysWhereMediaIs() throws IOException {
        Properties properties = this.load("application.properties");
        assertFalse(properties.containsKey("audio.extract.max-file-size-mb"), "declared by media-service now");
        assertFalse(properties.containsKey("document.converter.max-file-size-mb"), "declared by media-service now");
        assertTrue(properties.containsKey("media.url"), "HttpMedia reads media.url");
    }

    /**
     * The scheduler pool size, asserted because it was already lost once.
     *
     * Spring defaults it to a single thread, which puts every @Scheduled method in the application
     * on one: the minute-cycle ETL crons end up queued behind a query sweep that runs user SQL
     * against remote databases, and fixedDelay means a slow sweep does not delay them but stops
     * them outright. Nothing fails when the line goes missing -- the jobs simply stop being
     * dispatched -- so a test is the only thing that notices.
     */
    @Test
    void theSchedulerIsNotLeftOnASingleThread() throws IOException {
        Properties properties = this.load("application.properties");
        String poolSize = properties.getProperty("spring.task.scheduling.pool.size");
        assertTrue(poolSize != null && !poolSize.trim().isEmpty(),
            "spring.task.scheduling.pool.size must stay declared, or every cron shares one thread");
    }

    /**
     * Outside dev the API documentation is not generated, and the value is written down rather than
     * read from the environment: a stray variable must not publish a map of the API in production.
     */
    @Test
    void theApiDocsAreOffOutsideDev() throws IOException {
        for (String profile : Arrays.asList("application-stage.properties", "application-prod.properties")) {
            assertEquals("false", this.load(profile).getProperty("process.api-docs.enabled"),
                profile + " must declare process.api-docs.enabled=false");
        }
    }

    @Test
    void everyCredentialIsDeclaredWithAnEmptyDefault() throws IOException {
        for (String profile : PROFILES) {
            Properties properties = this.load(profile);
            for (String key : SECRETS) {
                String value = properties.getProperty(key);
                if (value == null) {
                    continue;
                }
                // ${VAR:} and nothing else: a placeholder with no fallback would fail to resolve,
                // and a fallback with anything after the colon is a committed credential.
                assertTrue(value.trim().matches("\\$\\{[A-Z0-9_]+:\\}"),
                    profile + " must supply " + key + " from the environment with an empty default, found: " + value);
            }
        }
    }

    @Test
    void theAmbientCredentialPathStaysOptInEverywhere() throws IOException {
        for (String profile : PROFILES) {
            Properties properties = this.load(profile);
            assertEquals("${STORAGE_ALLOW_INSTANCE_ROLE:false}",
                properties.getProperty("storage.allow-instance-role").trim(),
                profile + " must keep borrowing the host IAM role off unless a deployment asks for it");
        }
    }

    /**
     * MIG-129: only Liquibase changes etl_job's schema, and a changeset runs once -- in every profile, dev included.
     * dev ran update until then, which is how sixteen tables and most sequences came to exist without a changeset
     * (see the V50 header). Hibernate checks the schema against the entities and never writes it; the base
     * properties set nothing that could win over a profile, and the end-to-end suite's profile validates too.
     */
    @Test
    void liquibaseOwnsTheSchemaInEveryProfile() throws IOException {
        for (String profile : PROFILES) {
            Properties properties = this.load(profile);
            assertEquals("validate", properties.getProperty("spring.jpa.hibernate.ddl-auto"),
                profile + " runs Liquibase, so Hibernate must not issue schema changes of its own");
        }
        assertEquals(null, this.load("application.properties").getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("validate", this.load("application-e2e.properties").getProperty("spring.jpa.hibernate.ddl-auto"),
            "the end-to-end suite boots against the dev database: it checks the schema and never writes it");
    }
}

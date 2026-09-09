package process.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        "ai.allowed-endpoint-hosts",
        "kafka.secret-cache.dir",
        "kafka.ssl.local-store-dir",
        "kafka.topic.default-replication-factor",
        "worker.callback.token",
        "platform.admin.bootstrap-password",
        // Analytics Studio's governor. Declared in every profile because an absent limit is an
        // unlimited one: DuckDB runs in this JVM, so a missing memory ceiling is the container's
        // OOM killer taking the ETL dispatcher down with the query.
        "analytics.query.timeout-seconds",
        "analytics.query.max-rows",
        "analytics.query.max-concurrent",
        "analytics.preview.page-size",
        "analytics.duckdb.memory-limit",
        "analytics.duckdb.threads",
        // The three switches and the sampling ceiling, here for the reason above turned around:
        // a limit nobody declared is unlimited, and a SWITCH nobody declared is undiscoverable.
        // An operator cannot turn analytics off in an incident, or stop a platform admin
        // generating load, if the only record that the switch exists is an @Value default in a
        // class they would have to already know to look at.
        "analytics.enabled",
        "analytics.profile.sample-rows",
        "analytics.benchmark.enabled",
        "analytics.parquet.conversion-enabled");

    /** Nothing in this list may ever be committed with a value beside it. */
    private static final List<String> SECRETS = Arrays.asList(
        "worker.callback.token",
        "platform.admin.bootstrap-password",
        "jwt.secret.key",
        "lookup.encryption.key",
        "minio.access-key",
        "minio.secret-key",
        "aws.s3.access-key",
        "aws.s3.secret-key",
        "azure.storage.connection-string");

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

    @Test
    void theTranscriptionCeilingIsDeclaredBesideTheConvertersOne() throws IOException {
        Properties properties = this.load("application.properties");
        assertTrue(properties.containsKey("audio.extract.max-file-size-mb"),
            "audio.extract.max-file-size-mb belongs next to document.converter.max-file-size-mb");
        assertTrue(properties.containsKey("document.converter.max-file-size-mb"),
            "document.converter.max-file-size-mb should still be declared here");
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

    @Test
    void liquibaseOwnsTheSchemaWhereverItCannotBeDropped() throws IOException {
        List<String> managed = Arrays.asList("application-stage.properties", "application-prod.properties");
        for (String profile : managed) {
            Properties properties = this.load(profile);
            assertEquals("validate", properties.getProperty("spring.jpa.hibernate.ddl-auto"),
                profile + " runs Liquibase, so Hibernate must not issue schema changes of its own");
        }
    }
}

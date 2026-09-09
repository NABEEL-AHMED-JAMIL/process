package process.analytics.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.analytics.AnalyticsException;
import process.analytics.AnalyticsQueryService;
import process.analytics.DatasetRef;
import process.analytics.DatasetResolver;
import process.analytics.DuckDbSessionFactory;
import process.model.enums.UserRole;
import process.model.pojo.StorageConnection;
import process.security.TenantContext;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.analytics.integration.AnalyticsIntegrationEnvironment.BENCHMARK_CSV;
import static process.analytics.integration.AnalyticsIntegrationEnvironment.MINIO_BUCKET;
import static process.analytics.integration.AnalyticsIntegrationEnvironment.TENANT_ID;

/**
 * The lock-down, asserted against a session that is genuinely talking to the network.
 *
 * {@code DuckDbLockdownTest} already makes these claims, and makes them well -- but it makes them
 * about a session with no endpoint, which never opens a socket and never loads a secret. That
 * leaves one question it cannot reach and this file exists for: <b>does the lock-down survive the
 * configuration a real read requires?</b> A working session has had {@code INSTALL httpfs},
 * {@code LOAD httpfs} and a {@code CREATE SECRET} run on it before {@code lockDown()} is reached,
 * and an extension that adds a filesystem is precisely the kind of thing that could put one back.
 * The order inside {@code DuckDbSessionFactory.open} is what makes that safe, and an argument
 * about ordering is worth exactly as much as the test that runs it in the real order.
 *
 * <b>The control comes first.</b> Every refusal below is worthless if the session simply does not
 * work, so {@link #aRemoteSessionGenuinelyReadsFromTheObjectStore} reads real rows over the
 * network out of the same session the refusals are asserted on. Without it, a MinIO with the
 * wrong credentials would make this entire class pass.
 *
 * Two layers are tested separately and deliberately. The GATE refuses a statement that names a
 * location, before anything is executed; the FILESYSTEM refuses a read that gets past it. Neither
 * can do the other's job -- reading a local file is a perfectly good read, so no read/write
 * classifier can catch it -- and that overlap is the design, not a redundancy.
 *
 * @author Nabeel Ahmed
 */
class RemoteSessionLockdownIntegrationTest {

    private DuckDbSessionFactory sessions;
    private StorageConnection connection;
    private AnalyticsQueryService service;
    private DatasetResolver resolver;

    /** Stands in for anything on the deployment's disk worth stealing: the .env, a mounted secret. */
    private File secretOnDisk;

    @BeforeEach
    void setUp() throws Exception {
        AnalyticsIntegrationEnvironment.requireBenchmarkData();
        this.sessions = AnalyticsIntegrationEnvironment.sessions();
        this.connection = AnalyticsIntegrationEnvironment.minioConnection();
        this.service = AnalyticsIntegrationEnvironment.service();
        this.resolver = AnalyticsIntegrationEnvironment.resolverOver(this.connection);
        TenantContext.set(TENANT_ID, UserRole.TENANT_USER.name(), 5001L, "analytics-it@example.test");

        this.secretOnDisk = File.createTempFile("analytics-remote-lockdown", ".csv");
        Files.write(this.secretOnDisk.toPath(),
            "secret\nDB_PASSWORD=hunter2\n".getBytes(StandardCharsets.UTF_8));
        this.secretOnDisk.deleteOnExit();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        if (this.service != null) {
            this.service.shutdown();
        }
        if (this.secretOnDisk != null) {
            this.secretOnDisk.delete();
        }
    }

    /**
     * The control: this session really does read from object storage.
     *
     * It also happens to be the narrowest end-to-end proof in the suite that httpfs, the secret
     * and the endpoint are all correct, which is why it asserts a row count rather than merely
     * that no exception was thrown.
     */
    @Test
    void aRemoteSessionGenuinelyReadsFromTheObjectStore() throws Exception {
        Connection duck = this.sessions.open(this.connection);
        try {
            Statement statement = duck.createStatement();
            try {
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM read_csv_auto('s3://"
                    + MINIO_BUCKET + "/" + BENCHMARK_CSV + "')");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1))
                    .as("the session must actually have reached the object store")
                    .isGreaterThan(0L);
            } finally {
                statement.close();
            }
        } finally {
            duck.close();
        }
    }

    /**
     * The same session, in the same state, cannot read a file on the machine it runs on.
     *
     * This is the assertion the unit suite cannot make: httpfs is loaded and a secret is
     * attached, so the session has been through every configuration step a working read needs,
     * and the local filesystem is still gone.
     */
    @Test
    void aRemoteSessionStillCannotReadALocalFile() throws Exception {
        final String path = AnalyticsIntegrationEnvironment
            .sqlLiteral(this.secretOnDisk.getAbsolutePath());

        // The control, and it is not ceremony. An unlocked DuckDB reads this file happily, so the
        // refusal below is the lock-down and not a bad path, an unreadable file or a reader that
        // would have failed anyway -- each of which would let this test pass while proving
        // nothing at all.
        Connection plain = DriverManager.getConnection("jdbc:duckdb:");
        try {
            Statement statement = plain.createStatement();
            try {
                ResultSet rows = statement.executeQuery(
                    "SELECT count(*) FROM read_csv_auto('" + path + "')");
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1))
                    .as("an unrestricted DuckDB must be able to read this file")
                    .isGreaterThan(0L);
            } finally {
                statement.close();
            }
        } finally {
            plain.close();
        }

        Connection duck = this.sessions.open(this.connection);
        try {
            final Statement statement = duck.createStatement();
            try {
                assertThatThrownBy(() ->
                    statement.executeQuery("SELECT * FROM read_csv_auto('" + path + "')"))
                    .isInstanceOf(SQLException.class)
                    .satisfies(refusal -> {
                        String message = refusal.getMessage().toLowerCase();
                        assertThat(message.contains("disabled")
                            || message.contains("localfilesystem")
                            || message.contains("not allowed")
                            || message.contains("permission"))
                            .as("the refusal should name the filesystem rule, got: %s", message)
                            .isTrue();
                    });
            } finally {
                statement.close();
            }
        } finally {
            duck.close();
        }
    }

    /** And cannot write one, which is the same hole in the other direction. */
    @Test
    void aRemoteSessionStillCannotWriteALocalFile() throws Exception {
        File target = new File(this.secretOnDisk.getParentFile(), "analytics-remote-must-not-exist.csv");
        target.delete();
        target.deleteOnExit();
        final String path = AnalyticsIntegrationEnvironment.sqlLiteral(target.getAbsolutePath());

        Connection duck = this.sessions.open(this.connection);
        try {
            final Statement statement = duck.createStatement();
            try {
                assertThatThrownBy(() ->
                    statement.execute("COPY (SELECT 1 AS a) TO '" + path + "' (FORMAT CSV)"))
                    .isInstanceOf(SQLException.class);
            } finally {
                statement.close();
            }
        } finally {
            duck.close();
        }
        assertThat(target).doesNotExist();
    }

    /**
     * The configuration is frozen even after the extension that made the session useful was loaded.
     *
     * Both directions are asserted, because they fail differently if the ordering in open() ever
     * changes: raising the ceiling would let one query take the container down, and putting the
     * local filesystem back would undo every other test in this file at once.
     */
    @Test
    void aRemoteSessionCannotUnlockItsOwnConfiguration() throws Exception {
        Connection duck = this.sessions.open(this.connection);
        try {
            final Statement statement = duck.createStatement();
            try {
                assertThatThrownBy(() -> statement.execute("SET memory_limit='16GB'"))
                    .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.execute("SET disabled_filesystems=''"))
                    .isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.execute("SET lock_configuration=false"))
                    .isInstanceOf(SQLException.class);
            } finally {
                statement.close();
            }
        } finally {
            duck.close();
        }
    }

    /**
     * A user's own SQL naming a local file is turned away before the engine is asked.
     *
     * The gate refuses this rather than the filesystem, and the distinction is worth asserting on
     * the message: a refusal that arrived from the reader would mean the statement had already
     * been composed, the views already created and the location already bound.
     */
    @Test
    void aQueryTheUserWroteCannotNameALocalFile() throws Exception {
        final DatasetRef dataset = this.resolver.resolve("analytics-it-minio", BENCHMARK_CSV);
        final String path = AnalyticsIntegrationEnvironment
            .sqlLiteral(this.secretOnDisk.getAbsolutePath());

        assertThatThrownBy(() ->
            this.service.query(dataset, null, "SELECT * FROM read_csv_auto('" + path + "')"))
            .isInstanceOf(AnalyticsException.class)
            .hasMessageContaining("reads the datasets it was given, by name");

        // The control that keeps the assertion above honest: the very same service, session and
        // dataset answer a query that names nothing.
        assertThat(this.service.query(dataset, null, "SELECT count(*) AS n FROM dataset").getRows())
            .isNotEmpty();
    }
}

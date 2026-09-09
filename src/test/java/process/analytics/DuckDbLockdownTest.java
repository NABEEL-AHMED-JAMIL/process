package process.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.util.EncryptionUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the analytics engine cannot be turned against the machine it runs on.
 *
 * This runs a REAL DuckDB. It is the one test in the suite that would be worthless as a mock:
 * the claim being made is about what a specific engine refuses to do, and a stubbed engine
 * refuses whatever the stub says. DuckDB is embedded and in-memory, so the cost of being honest
 * here is a few hundred milliseconds.
 *
 * The threat is inverted from the usual one. Normally the database is trusted and the input is
 * not; here the ENGINE is the dangerous part -- it can read local files, write them, open sockets
 * and install extensions, all as the backend process. Analytics Studio's whole purpose is to
 * point it at data a user chose, and phase three will let users write the SQL. These assertions
 * are what makes that safe to build.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class DuckDbLockdownTest {

    private DuckDbSessionFactory factory;
    private File secretOnDisk;

    @BeforeEach
    void setUp() throws IOException {
        AnalyticsLimits limits = new AnalyticsLimits();
        ReflectionTestUtils.setField(limits, "memoryLimit", "256MB");
        ReflectionTestUtils.setField(limits, "threads", 1);
        ReflectionTestUtils.setField(limits, "timeoutSeconds", 10);
        ReflectionTestUtils.setField(limits, "maxRows", 1000);
        ReflectionTestUtils.setField(limits, "previewPageSize", 100);
        ReflectionTestUtils.setField(limits, "maxConcurrentQueries", 2);

        // A real EncryptionUtil is not needed: the S3 branch tolerates absent credentials, which
        // is what an anonymous or IAM-role connection looks like anyway.
        this.factory = new DuckDbSessionFactory(limits, new EncryptionUtil());

        // Stands in for anything on the deployment's disk worth stealing: the .env, the jar, a
        // mounted secret. If a session can read this, it can read those.
        this.secretOnDisk = File.createTempFile("analytics-lockdown", ".csv");
        Files.write(this.secretOnDisk.toPath(),
            "secret\nDB_PASSWORD=hunter2\n".getBytes("UTF-8"));
        this.secretOnDisk.deleteOnExit();
    }

    /** A MinIO connection with no endpoint, so no network call is attempted by these tests. */
    private StorageConnection connection() {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(1L);
        connection.setProvider(StorageProvider.MINIO);
        connection.setAlias("test-store");
        return connection;
    }

    @Test
    void aSessionCannotReadAFileFromTheLocalDisk() throws Exception {
        try (Connection duck = this.factory.open(connection());
             Statement statement = duck.createStatement()) {

            String path = this.secretOnDisk.getAbsolutePath().replace("'", "''");
            assertThatThrownBy(() ->
                statement.executeQuery("SELECT * FROM read_csv_auto('" + path + "')"))
                .isInstanceOf(SQLException.class)
                // The local filesystem is removed from the session, so this fails in the reader
                // rather than being caught by a validator somebody could later forget to call.
                .satisfies(ex -> {
                    String message = ex.getMessage().toLowerCase();
                    assertThat(message.contains("disabled")
                        || message.contains("localfilesystem")
                        || message.contains("not allowed")
                        || message.contains("permission"))
                        .as("the refusal should name the filesystem rule, got: %s", message)
                        .isTrue();
                });
        }
    }

    @Test
    void aSessionCannotWriteAFileToTheLocalDisk() throws Exception {
        File target = new File(this.secretOnDisk.getParentFile(), "analytics-should-not-exist.csv");
        target.deleteOnExit();

        try (Connection duck = this.factory.open(connection());
             Statement statement = duck.createStatement()) {

            String path = target.getAbsolutePath().replace("'", "''");
            assertThatThrownBy(() ->
                statement.execute("COPY (SELECT 1 AS a) TO '" + path + "' (FORMAT CSV)"))
                .isInstanceOf(SQLException.class);
        }
        assertThat(target).doesNotExist();
    }

    @Test
    void aSessionCannotRaiseItsOwnMemoryCeiling() throws Exception {
        try (Connection duck = this.factory.open(connection());
             Statement statement = duck.createStatement()) {

            // lock_configuration is set last for exactly this reason: SQL that runs afterwards --
            // including a user's, once SQL Studio exists -- cannot loosen the policy above it.
            assertThatThrownBy(() -> statement.execute("SET memory_limit='64GB'"))
                .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void aSessionCannotPutTheLocalFilesystemBack() throws Exception {
        try (Connection duck = this.factory.open(connection());
             Statement statement = duck.createStatement()) {

            assertThatThrownBy(() -> statement.execute("SET disabled_filesystems=''"))
                .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void aSessionCannotReachForAnExtensionThatIsNotAlreadyBundled() throws Exception {
        // The property phase three leans on hardest, and the one nothing asserted. httpfs is
        // loaded deliberately BEFORE the lock; the question is what a session can add after it.
        // If user-written SQL could pull in, say, postgres_scanner or spatial, it would reach a
        // filesystem and a socket by a route none of the refusals above cover.
        //
        // The mechanism is the local filesystem rule rather than a rule about extensions: an
        // extension that is not compiled into the driver has to be read from the extension
        // directory on disk, and there is no local disk left to read it from. Both verbs are
        // exercised on their OWN statement -- chained on one statement, LOAD merely inherits
        // "Statement was closed" from the failed INSTALL and asserts nothing.
        try (Connection duck = this.factory.open(connection())) {
            for (String sql : new String[] { "INSTALL spatial", "LOAD spatial" }) {
                assertThatThrownBy(() -> {
                    try (Statement fresh = duck.createStatement()) {
                        fresh.execute(sql);
                    }
                })
                    .as("%s should be refused", sql)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("LocalFileSystem");
            }
        }
    }

    @Test
    void anExtensionCompiledIntoTheDriverIsStillLoadable() throws Exception {
        // The honest boundary of the claim above, written down so nobody reads it as "no
        // extension can ever be loaded". json ships inside duckdb_jdbc, so it needs no disk and
        // LOADs fine. That is acceptable -- the bundled set are format readers, and they reach
        // the outside world only through the filesystem rules already asserted here -- but it is
        // acceptable as a JUDGEMENT about a known list, not because the door is shut.
        try (Connection duck = this.factory.open(connection());
             Statement statement = duck.createStatement()) {

            statement.execute("LOAD json");
        }
    }

    @Test
    void aSessionStillDoesTheJobItExistsFor() throws Exception {
        // The positive control. Every assertion above is a refusal, and a session that refused
        // everything -- including its own purpose -- would pass all of them while being useless.
        try (Connection duck = this.factory.open(connection());
             Statement statement = duck.createStatement();
             ResultSet rows = statement.executeQuery(
                 "SELECT region, sum(amount) AS total FROM (VALUES "
                 + "('North', 1200.00), ('South', 450.25), ('North', 800.50)) "
                 + "AS t(region, amount) GROUP BY region ORDER BY total DESC")) {

            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("region")).isEqualTo("North");
            assertThat(rows.getDouble("total")).isEqualTo(2000.50);
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("region")).isEqualTo("South");
        }
    }

    @Test
    void anFtpConnectionIsRefusedByNameRatherThanFailingInsideAScan() {
        StorageConnection ftp = connection();
        ftp.setProvider(StorageProvider.FTP);

        assertThatThrownBy(() -> this.factory.open(ftp))
            .isInstanceOf(SQLException.class)
            // Pins the two that actually work rather than the three the factory can build a
            // session for: AZURE never reaches here, so advertising it would be an overclaim.
            .hasMessageContaining("S3 and MinIO")
            .hasMessageNotContaining("Azure");
    }

    @Test
    void aMalformedMemoryLimitIsRejectedRatherThanInterpolatedIntoSql() {
        AnalyticsLimits bad = new AnalyticsLimits();
        ReflectionTestUtils.setField(bad, "memoryLimit", "512MB'; SET lock_configuration=false; --");
        ReflectionTestUtils.setField(bad, "threads", 1);
        DuckDbSessionFactory unsafe = new DuckDbSessionFactory(bad, new EncryptionUtil());

        // The value comes from a properties file rather than a user, but a property is still a
        // string an operator can mistype, and this one would have unlocked the engine.
        assertThatThrownBy(() -> unsafe.open(connection()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("memory-limit");
    }
}

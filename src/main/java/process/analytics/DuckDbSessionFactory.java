package process.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.util.EncryptionUtil;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Builds the one kind of DuckDB connection this application is allowed to have.
 *
 * The threat here is inverted from the usual one. Normally a database is trusted and the user
 * input is not; here the ENGINE is the dangerous part. DuckDB can read and write local files,
 * open network sockets and install extensions, so a session handed to a feature whose entire
 * point is running user-supplied SQL is arbitrary file access from inside the backend, running
 * as the backend. Every lock-down below exists for that reason, and the order matters: the
 * configuration is frozen at the end, so nothing downstream can loosen it.
 *
 * What a session is NOT allowed to do, after this factory has finished with it:
 *
 *   * read or write the local filesystem, so the deployment's own secrets, the application jar
 *     and /proc are unreachable no matter what SQL arrives;
 *   * install or load a further extension, so the allow-listed httpfs is the only one present;
 *   * change any of these settings back, because lock_configuration is set last.
 *
 * Credentials never appear in SQL text. They are attached with CREATE SECRET, which DuckDB
 * keeps out of query plans and error messages -- an important difference from the older
 * SET s3_access_key_id style, whose values turn up in anything that echoes a statement.
 *
 * A session is per query and closed with it. DuckDB's in-memory catalogue dies with the
 * connection, so one caller's registered dataset cannot leak into another's.
 *
 * @author Nabeel Ahmed
 */
@Component
public class DuckDbSessionFactory {

    private static final Logger logger = LoggerFactory.getLogger(DuckDbSessionFactory.class);

    /**
     * Loading the driver once, here, rather than relying on JDBC auto-discovery.
     *
     * DuckDB unpacks a native library on first use. Doing that inside a request thread makes the
     * first analytics call after a restart mysteriously slow and, if the unpack fails, produces a
     * ClassNotFound rather than a message naming the real problem.
     */
    static {
        try {
            Class.forName("org.duckdb.DuckDBDriver");
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException(
                "The DuckDB driver is not on the classpath. Analytics Studio cannot start.", ex);
        }
    }

    private final AnalyticsLimits limits;
    private final EncryptionUtil encryptionUtil;

    public DuckDbSessionFactory(AnalyticsLimits limits, EncryptionUtil encryptionUtil) {
        this.limits = limits;
        this.encryptionUtil = encryptionUtil;
    }

    /**
     * A locked-down in-memory session that can read exactly one storage connection.
     *
     * The caller owns the returned connection and must close it; try-with-resources at the call
     * site is the intended shape.
     */
    public Connection open(StorageConnection connection) throws SQLException {
        Connection duck = DriverManager.getConnection("jdbc:duckdb:");
        boolean handedOver = false;
        try {
            try (Statement statement = duck.createStatement()) {
                this.applyResourceLimits(statement);
                this.applyStorageCredentials(statement, connection);
                this.lockDown(statement);
            }
            handedOver = true;
            return duck;
        } finally {
            // A session that failed half-way through configuration is a session that is not
            // locked down. It must never escape this method.
            if (!handedOver) {
                try {
                    duck.close();
                } catch (SQLException ignored) {
                    // Nothing useful to do; the configuration failure below is the real error.
                }
            }
        }
    }

    private void applyResourceLimits(Statement statement) throws SQLException {
        statement.execute("SET memory_limit='" + sanitiseSetting(this.limits.getMemoryLimit()) + "'");
        statement.execute("SET threads=" + Math.max(1, this.limits.getThreads()));
        // Nothing is written, so no temporary directory is needed either. Leaving it unset keeps
        // a spill from quietly creating files next to the application.
        statement.execute("SET preserve_insertion_order=false");
    }

    /**
     * Attaches the connection's credentials, decrypted here and nowhere else.
     *
     * S3 and MinIO both speak the S3 protocol, so one secret shape covers them; the endpoint and
     * the path-style flag are what differ. Azure needs DuckDB's own azure extension and its
     * connection string, which is why it is a separate branch rather than more parameters on the
     * same one.
     */
    private void applyStorageCredentials(Statement statement, StorageConnection connection)
        throws SQLException {

        StorageProvider provider = connection.getProvider();
        if (provider == null) {
            throw new SQLException("Storage connection " + connection.getAlias() + " has no provider.");
        }
        switch (provider) {
            case S3:
            case MINIO:
                statement.execute("INSTALL httpfs");
                statement.execute("LOAD httpfs");
                statement.execute(this.s3Secret(connection));
                break;
            case AZURE:
                statement.execute("INSTALL azure");
                statement.execute("LOAD azure");
                statement.execute(this.azureSecret(connection));
                break;
            default:
                // FTP and FTPS are object storage to the browser but not to DuckDB, which has no
                // reader for them. Refused by name so the caller can say why rather than failing
                // later inside a scan.
                //
                // Names S3 and MinIO only, though the switch above still has an AZURE branch.
                // DatasetResolver gates AZURE as unverified before a request can reach this
                // factory (gap 13 / Q4), so a user who is told "it supports Azure Blob" is being
                // told something they cannot act on -- the same overclaim the gate exists to
                // stop, restated one layer down. When the Azure branch is verified and that gate
                // is deleted, this sentence goes back to naming three.
                throw new SQLException("Analytics Studio cannot read a " + provider
                    + " connection. It supports S3 and MinIO.");
        }
    }

    private String s3Secret(StorageConnection connection) throws SQLException {
        String endpoint = stripScheme(connection.getEndpoint());
        boolean useSsl = connection.getEndpoint() != null
            && connection.getEndpoint().trim().toLowerCase().startsWith("https");
        String key = this.decrypt(connection.getSecretKeyEnc());
        StringBuilder secret = new StringBuilder("CREATE OR REPLACE SECRET analytics_store (")
            .append("TYPE S3");
        if (notBlank(connection.getAccessKey())) {
            secret.append(", KEY_ID ").append(quote(connection.getAccessKey()));
        }
        if (notBlank(key)) {
            secret.append(", SECRET ").append(quote(key));
        }
        if (notBlank(connection.getRegion())) {
            secret.append(", REGION ").append(quote(connection.getRegion()));
        }
        if (notBlank(endpoint)) {
            // Only for a self-hosted store. Amazon's own endpoint is derived from the region, and
            // setting it explicitly breaks virtual-host addressing.
            secret.append(", ENDPOINT ").append(quote(endpoint));
            secret.append(", USE_SSL ").append(useSsl);
            // MinIO addresses a bucket as a path segment, not as a subdomain.
            secret.append(", URL_STYLE 'path'");
        }
        return secret.append(")").toString();
    }

    private String azureSecret(StorageConnection connection) throws SQLException {
        String connectionString = this.decrypt(connection.getAzureConnectionStringEnc());
        if (!notBlank(connectionString)) {
            throw new SQLException("Azure connection " + connection.getAlias()
                + " has no connection string, so Analytics Studio cannot read it.");
        }
        return "CREATE OR REPLACE SECRET analytics_store (TYPE AZURE, CONNECTION_STRING "
            + quote(connectionString) + ")";
    }

    /**
     * The last thing done to a session, and the reason the order in open() matters.
     *
     * disabled_filesystems removes the local reader entirely: after this, a query naming a path
     * on disk fails at the filesystem layer rather than being caught by a validator somebody
     * might later forget to call. lock_configuration then makes every setting above permanent for
     * the life of the connection, so no SQL that runs afterwards -- including a user's, once SQL
     * Studio exists -- can raise the memory ceiling or re-enable local files.
     */
    private void lockDown(Statement statement) throws SQLException {
        statement.execute("SET disabled_filesystems='LocalFileSystem'");
        statement.execute("SET lock_configuration=true");
    }

    private String decrypt(String cipherText) throws SQLException {
        if (!notBlank(cipherText)) {
            return null;
        }
        try {
            return this.encryptionUtil.decrypt(cipherText);
        } catch (Exception ex) {
            // Deliberately not including the cause's message: it can carry fragments of the
            // ciphertext, and this string is on its way to a user.
            logger.error("Could not decrypt a storage secret for Analytics Studio: {}", ex.getMessage());
            throw new SQLException("The stored credentials for this connection could not be read.");
        }
    }

    /** Single quotes doubled, the SQL-standard escape, so a credential cannot end the literal. */
    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * A setting value is interpolated into SQL, so it is restricted rather than escaped.
     *
     * These come from application properties, not from a user, but a property is still a string
     * an operator can mistype, and "512MB'; SET lock_configuration=false" would otherwise be a
     * configuration file that unlocks the engine.
     */
    private static String sanitiseSetting(String value) {
        if (value == null || !value.matches("[0-9]+[A-Za-z]{0,3}")) {
            throw new IllegalArgumentException(
                "analytics.duckdb.memory-limit must look like 512MB or 2GB, got: " + value);
        }
        return value;
    }

    private static String stripScheme(String endpoint) {
        if (endpoint == null) {
            return null;
        }
        return endpoint.trim().replaceFirst("^https?://", "").replaceAll("/+$", "");
    }

    private static boolean notBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

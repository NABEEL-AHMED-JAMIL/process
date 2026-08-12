package process.engine.query;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import process.model.enums.DatabaseType;
import process.model.pojo.DatabaseConnectionProfile;
import process.util.EncryptionUtil;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

/**
 * Opens a plain JDBC java.sql.Connection to a tenant's own external database, from a
 * DatabaseConnectionProfile -- one connection per call, no pool held between calls. A short-lived
 * DriverManager connection (closed by the caller in a try-with-resources immediately after the
 * single query it's for) is deliberately simpler than standing up a per-tenant-per-profile
 * connection pool that would sit open indefinitely: this is a "connect, run one query, stream
 * to CSV, disconnect" tool, not a persistent multi-query session, so pooling would only add
 * idle-connection/resource-exhaustion risk across many tenants x many profiles for no benefit.
 * Only DatabaseType.POSTGRES is wired up (the only JDBC driver already in this project's
 * dependencies) -- see DatabaseType's own javadoc for how to add another vendor later.
 * @author Nabeel Ahmed
 */
@Component
public class DatabaseConnectionFactory {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseConnectionFactory.class);

    /** Seconds -- both the JDBC login timeout and the driver-level TCP connect timeout, so a
     * misconfigured host/port fails fast instead of hanging the calling thread. */
    private static final int CONNECT_TIMEOUT_SECONDS = 10;

    private final EncryptionUtil encryptionUtil;

    public DatabaseConnectionFactory(EncryptionUtil encryptionUtil) {
        this.encryptionUtil = encryptionUtil;
    }

    /**
     * Method use to open a new JDBC connection for the given profile -- caller owns the
     * returned Connection and must close it (try-with-resources).
     * @param profile
     * @return Connection
     * */
    public Connection openConnection(DatabaseConnectionProfile profile) throws SQLException {
        if (profile.getDatabaseType() != DatabaseType.POSTGRES) {
            // Fails loud and specific rather than falling through to a confusing driver error --
            // see DatabaseType's javadoc for what adding a vendor here actually involves.
            throw new IllegalArgumentException(
                "Database type " + profile.getDatabaseType() + " is not yet supported by this Query Engine.");
        }
        String password = profile.getPasswordEncrypted() == null
            ? "" : this.encryptionUtil.decrypt(profile.getPasswordEncrypted());
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("user", profile.getUsername());
        connectionProperties.setProperty("password", password);
        connectionProperties.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));
        // tcpKeepAlive avoids a long-running streamed export getting silently dropped by an
        // idle-connection-reaping load balancer/firewall sitting between this app and the
        // tenant's database.
        connectionProperties.setProperty("tcpKeepAlive", "true");
        this.mergeAdditionalProperties(connectionProperties, profile.getAdditionalProperties());
        DriverManager.setLoginTimeout(CONNECT_TIMEOUT_SECONDS);
        String jdbcUrl = this.buildJdbcUrl(profile);
        return DriverManager.getConnection(jdbcUrl, connectionProperties);
    }

    private String buildJdbcUrl(DatabaseConnectionProfile profile) {
        return String.format("jdbc:postgresql://%s:%d/%s",
            profile.getHost(), profile.getPort(), profile.getDatabaseName());
    }

    /**
     * Method use to merge additionalProperties (a small free-form JSON object, e.g.
     * {"sslmode":"require"}) into the JDBC connection Properties -- malformed/unparseable JSON
     * is logged and ignored rather than failing the whole connection attempt over an optional field.
     * @param connectionProperties
     * @param additionalPropertiesJson
     * */
    private void mergeAdditionalProperties(Properties connectionProperties, String additionalPropertiesJson) {
        if (additionalPropertiesJson == null || additionalPropertiesJson.trim().isEmpty()) {
            return;
        }
        try {
            JsonObject json = new Gson().fromJson(additionalPropertiesJson, JsonObject.class);
            if (json == null) {
                return;
            }
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                if (entry.getValue() != null && entry.getValue().isJsonPrimitive()) {
                    connectionProperties.setProperty(entry.getKey(), entry.getValue().getAsString());
                }
            }
        } catch (Exception ex) {
            logger.warn("Ignoring unparseable additionalProperties on a database connection profile: {}", ex.getMessage());
        }
    }

}

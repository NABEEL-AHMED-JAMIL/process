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

@Component
public class DatabaseConnectionFactory {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseConnectionFactory.class);

    private static final int CONNECT_TIMEOUT_SECONDS = 10;

    private final EncryptionUtil encryptionUtil;

    public DatabaseConnectionFactory(EncryptionUtil encryptionUtil) {
        this.encryptionUtil = encryptionUtil;
    }

    public Connection openConnection(DatabaseConnectionProfile profile) throws SQLException {
        if (profile.getDatabaseType() != DatabaseType.POSTGRES) {

            throw new IllegalArgumentException(
                "Database type " + profile.getDatabaseType() + " is not yet supported by this Query Engine.");
        }
        String password = profile.getPasswordEncrypted() == null
            ? "" : this.encryptionUtil.decrypt(profile.getPasswordEncrypted());
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("user", profile.getUsername());
        connectionProperties.setProperty("password", password);
        connectionProperties.setProperty("connectTimeout", String.valueOf(CONNECT_TIMEOUT_SECONDS));

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

package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import java.sql.Timestamp;

/**
 * Secrets are write-only across this boundary: the client posts plaintext (secretKey,
 * azureConnectionString, password) and reads back only the *Configured booleans, so a
 * stored credential can never be retrieved through the API once saved -- same contract
 * AiAgentDto uses for apiKey/apiKeyConfigured.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StorageConnectionDto {

    private Long storageConnectionId;
    private Long tenantId;
    private String connectionName;
    private String alias;
    private StorageProvider provider;
    private String description;

    private String bucketName;
    private String endpoint;
    private String region;
    private String accessKey;
    private String secretKey;
    private Boolean secretKeyConfigured;

    private String azureAccountName;
    private String azureConnectionString;
    private Boolean azureConnectionStringConfigured;

    private String host;
    private Integer port;
    private String username;
    private String password;
    private Boolean passwordConfigured;
    private String baseDirectory;
    private Boolean passiveMode;
    private Boolean implicitTls;

    private Boolean isDefault;
    private Status status;
    private String connectionStatus;
    private Timestamp lastTestedAt;
    private String lastTestMessage;
    private Timestamp dateCreated;

    public StorageConnectionDto() {}

    public Long getStorageConnectionId() { return storageConnectionId; }
    public void setStorageConnectionId(Long storageConnectionId) { this.storageConnectionId = storageConnectionId; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public String getConnectionName() { return connectionName; }
    public void setConnectionName(String connectionName) { this.connectionName = connectionName; }

    public String getAlias() { return alias; }
    public void setAlias(String alias) { this.alias = alias; }

    public StorageProvider getProvider() { return provider; }
    public void setProvider(StorageProvider provider) { this.provider = provider; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getBucketName() { return bucketName; }
    public void setBucketName(String bucketName) { this.bucketName = bucketName; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public Boolean getSecretKeyConfigured() { return secretKeyConfigured; }
    public void setSecretKeyConfigured(Boolean secretKeyConfigured) { this.secretKeyConfigured = secretKeyConfigured; }

    public String getAzureAccountName() { return azureAccountName; }
    public void setAzureAccountName(String azureAccountName) { this.azureAccountName = azureAccountName; }

    public String getAzureConnectionString() { return azureConnectionString; }
    public void setAzureConnectionString(String azureConnectionString) { this.azureConnectionString = azureConnectionString; }

    public Boolean getAzureConnectionStringConfigured() { return azureConnectionStringConfigured; }
    public void setAzureConnectionStringConfigured(Boolean v) { this.azureConnectionStringConfigured = v; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public Boolean getPasswordConfigured() { return passwordConfigured; }
    public void setPasswordConfigured(Boolean passwordConfigured) { this.passwordConfigured = passwordConfigured; }

    public String getBaseDirectory() { return baseDirectory; }
    public void setBaseDirectory(String baseDirectory) { this.baseDirectory = baseDirectory; }

    public Boolean getPassiveMode() { return passiveMode; }
    public void setPassiveMode(Boolean passiveMode) { this.passiveMode = passiveMode; }

    public Boolean getImplicitTls() { return implicitTls; }
    public void setImplicitTls(Boolean implicitTls) { this.implicitTls = implicitTls; }

    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getConnectionStatus() { return connectionStatus; }
    public void setConnectionStatus(String connectionStatus) { this.connectionStatus = connectionStatus; }

    public Timestamp getLastTestedAt() { return lastTestedAt; }
    public void setLastTestedAt(Timestamp lastTestedAt) { this.lastTestedAt = lastTestedAt; }

    public String getLastTestMessage() { return lastTestMessage; }
    public void setLastTestMessage(String lastTestMessage) { this.lastTestMessage = lastTestMessage; }

    public Timestamp getDateCreated() { return dateCreated; }
    public void setDateCreated(Timestamp dateCreated) { this.dateCreated = dateCreated; }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}

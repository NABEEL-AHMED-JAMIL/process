package process.model.pojo;

import process.model.enums.Status;
import process.model.enums.StorageProvider;

/**
 * A storage connection as process holds one for the length of a request (MIG-70): storage-service owns
 * the table and the rows, and this is what RemoteStorageDirectory reads back -- a directory entry, or a
 * connection vended for one DuckDB session with its secret re-sealed under process's key. Never
 * persisted, never cached. tenantId null is the platform, as it has always been on this side.
 */
public class StorageConnection {

    private Long storageConnectionId;
    private Long tenantId;
    private String connectionName;
    private String alias;
    private StorageProvider provider;
    private String bucketName;
    private String endpoint;
    private String region;
    private String accessKey;
    private String secretKeyEnc;
    private String azureConnectionStringEnc;
    private Status status;

    public Long getStorageConnectionId() {
        return this.storageConnectionId;
    }

    public void setStorageConnectionId(Long storageConnectionId) {
        this.storageConnectionId = storageConnectionId;
    }

    public Long getTenantId() {
        return this.tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getConnectionName() {
        return this.connectionName;
    }

    public void setConnectionName(String connectionName) {
        this.connectionName = connectionName;
    }

    public String getAlias() {
        return this.alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public StorageProvider getProvider() {
        return this.provider;
    }

    public void setProvider(StorageProvider provider) {
        this.provider = provider;
    }

    public String getBucketName() {
        return this.bucketName;
    }

    public void setBucketName(String bucketName) {
        this.bucketName = bucketName;
    }

    public String getEndpoint() {
        return this.endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getRegion() {
        return this.region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccessKey() {
        return this.accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKeyEnc() {
        return this.secretKeyEnc;
    }

    public void setSecretKeyEnc(String secretKeyEnc) {
        this.secretKeyEnc = secretKeyEnc;
    }

    public String getAzureConnectionStringEnc() {
        return this.azureConnectionStringEnc;
    }

    public void setAzureConnectionStringEnc(String azureConnectionStringEnc) {
        this.azureConnectionStringEnc = azureConnectionStringEnc;
    }

    public Status getStatus() {
        return this.status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }
}

package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.hibernate.annotations.ParamDef;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.Filter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import javax.persistence.*;
import java.sql.Timestamp;

/**
 * A user-configurable connection to one storage backend (an S3/MinIO bucket, an Azure Blob
 * container, or an FTP/FTPS directory). Modelled on KafkaConnectionProfile -- same tenant
 * scoping, same encrypted-secret convention (*_enc columns hold EncryptionUtil ciphertext,
 * never plaintext), same test-connection bookkeeping.
 *
 * "alias" is the stable handle the rest of the application already passes around as its
 * `bucket` string (job_queue.bucket, document converter tasks, file chat, ...). Keeping the
 * alias equal to the real bucket name for existing object-store buckets is what lets this
 * table slot in underneath all of that without touching those call sites -- and it also gives
 * FTP, which has no bucket concept at all, something to be addressed by.
 */
@Entity
@Table(name = "storage_connection", indexes = {
    @Index(name = "idx_storage_connection_tenant_id", columnList = "tenant_id")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uq_storage_connection_alias", columnNames = { "alias" })
})
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@EntityListeners(AuditListener.class)
public class StorageConnection implements Audited {
    @Transient
    private String createdByName;

    @Transient
    private String updatedByName;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;


    @GenericGenerator(
        name = "storageConnectionSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "storage_connection_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "storage_connection_id")
    @GeneratedValue(generator = "storageConnectionSequenceGenerator")
    private Long storageConnectionId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", insertable = false, updatable = false)
    private Tenant tenant;

    @Column(name = "connection_name", nullable = false)
    private String connectionName;

    @Column(name = "alias", nullable = false)
    private String alias;

    @Column(name = "provider", nullable = false)
    @Enumerated(EnumType.STRING)
    private StorageProvider provider;

    @Column(name = "description")
    private String description;

    // --- Object stores (MINIO / S3 / AZURE) -------------------------------------------
    @Column(name = "bucket_name")
    private String bucketName;

    @Column(name = "endpoint")
    private String endpoint;

    @Column(name = "region")
    private String region;

    @Column(name = "access_key")
    private String accessKey;

    @Column(name = "secret_key_enc", length = 1000)
    private String secretKeyEnc;

    // Azure can authenticate either with an account name + key, or one connection string.
    @Column(name = "azure_account_name")
    private String azureAccountName;

    @Column(name = "azure_connection_string_enc", length = 2000)
    private String azureConnectionStringEnc;

    // --- FTP / FTPS -------------------------------------------------------------------
    @Column(name = "host")
    private String host;

    @Column(name = "port")
    private Integer port;

    @Column(name = "username")
    private String username;

    @Column(name = "password_enc", length = 1000)
    private String passwordEnc;

    // Everything under this directory is what the browser shows as the connection's root.
    @Column(name = "base_directory")
    private String baseDirectory;

    // Passive mode is the default that works through most firewalls/NAT; active mode needs
    // the server to open a connection back to us, which usually fails from inside a container.
    @Column(name = "passive_mode")
    private Boolean passiveMode = true;

    // FTPS only: implicit wraps the whole session in TLS on connect (classically port 990),
    // explicit connects in the clear then upgrades via AUTH TLS (usually port 21).
    @Column(name = "implicit_tls")
    private Boolean implicitTls = false;

    // --- Bookkeeping ------------------------------------------------------------------
    @Column(name = "is_default", nullable = false)
    private Boolean isDefault = false;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "connection_status")
    private String connectionStatus = "UNTESTED";

    @Column(name = "last_tested_at")
    private Timestamp lastTestedAt;

    @Column(name = "last_test_message", columnDefinition = "TEXT")
    private String lastTestMessage;

    @Column(name = "date_created")
    private Timestamp dateCreated;

    public StorageConnection() {}

    public Long getStorageConnectionId() { return storageConnectionId; }
    public void setStorageConnectionId(Long storageConnectionId) { this.storageConnectionId = storageConnectionId; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }

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

    public String getSecretKeyEnc() { return secretKeyEnc; }
    public void setSecretKeyEnc(String secretKeyEnc) { this.secretKeyEnc = secretKeyEnc; }

    public String getAzureAccountName() { return azureAccountName; }
    public void setAzureAccountName(String azureAccountName) { this.azureAccountName = azureAccountName; }

    public String getAzureConnectionStringEnc() { return azureConnectionStringEnc; }
    public void setAzureConnectionStringEnc(String azureConnectionStringEnc) { this.azureConnectionStringEnc = azureConnectionStringEnc; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordEnc() { return passwordEnc; }
    public void setPasswordEnc(String passwordEnc) { this.passwordEnc = passwordEnc; }

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


    @Override
    public Long getCreatedBy() {
        return createdBy;
    }

    @Override
    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    @Override
    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    @Override
    public Long getUpdatedBy() {
        return updatedBy;
    }

    @Override
    public String getCreatedByName() {
        return createdByName;
    }

    @Override
    public void setCreatedByName(String createdByName) {
        this.createdByName = createdByName;
    }

    @Override
    public String getUpdatedByName() {
        return updatedByName;
    }

    @Override
    public void setUpdatedByName(String updatedByName) {
        this.updatedByName = updatedByName;
    }
}

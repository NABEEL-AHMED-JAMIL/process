package process.config;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import process.model.pojo.StorageConnection;
import process.model.service.ObjectStorageService;
import process.model.service.impl.AzureBlobObjectStorageServiceImpl;
import process.model.service.impl.FtpObjectStorageServiceImpl;
import process.model.service.impl.MinioObjectStorageServiceImpl;
import process.model.service.impl.S3ObjectStorageServiceImpl;
import process.util.EncryptionUtil;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;

/**
 * Turns a stored StorageConnection into a ready-to-use ObjectStorageService bound to that
 * connection's own credentials -- the per-connection counterpart to MinioConfig/S3Config/
 * AzureBlobConfig, which can only ever produce one globally-configured client each.
 *
 * Built clients are cached, since constructing an SDK client sets up connection pools and
 * is wasteful to repeat per request. The cache key includes a fingerprint of the connection's
 * settings so that editing a connection's credentials takes effect immediately instead of
 * serving a stale client built from the old values.
 *
 * @author Nabeel Ahmed
 */
@Component
public class StorageClientFactory {

    /** Backs the short-lived FTP directory-listing cache; see RedisConfig. */
    private final org.springframework.cache.CacheManager cacheManager;

    private static final Logger logger = LoggerFactory.getLogger(StorageClientFactory.class);

    private final EncryptionUtil encryptionUtil;
    private final Map<String, ObjectStorageService> cache = new ConcurrentHashMap<>();

    /** Opt-in, and off unless a deployment says otherwise; see ambientCredentialsAllowed. */
    @Value("${storage.allow-instance-role:false}")
    private boolean allowInstanceRole;

    public StorageClientFactory(EncryptionUtil encryptionUtil,
        org.springframework.cache.CacheManager cacheManager) {
        this.cacheManager = cacheManager;
        this.encryptionUtil = encryptionUtil;
    }

    public ObjectStorageService serviceFor(StorageConnection connection) {
        String key = this.cacheKey(connection);
        return this.cache.computeIfAbsent(key, k -> this.build(connection));
    }

    /** Builds without consulting the cache -- used by "Test Connection", which must exercise
     *  the values just entered rather than whatever was last successfully built. */
    public ObjectStorageService buildUncached(StorageConnection connection) {
        return this.build(connection);
    }

    public void evict(StorageConnection connection) {
        this.cache.remove(this.cacheKey(connection));
    }

    public void evictAll() {
        this.cache.clear();
    }

    private String cacheKey(StorageConnection c) {
        return c.getStorageConnectionId() + "|" + c.getProvider() + "|" + c.getEndpoint() + "|"
            + c.getRegion() + "|" + c.getAccessKey() + "|" + hash(c.getSecretKeyEnc()) + "|"
            + c.getAzureAccountName() + "|" + hash(c.getAzureConnectionStringEnc()) + "|"
            + c.getHost() + "|" + c.getPort() + "|" + c.getUsername() + "|" + hash(c.getPasswordEnc()) + "|"
            + c.getBaseDirectory() + "|" + c.getPassiveMode() + "|" + c.getImplicitTls();
    }

    private static String hash(String value) {
        return value == null ? "-" : String.valueOf(value.hashCode());
    }

    private ObjectStorageService build(StorageConnection connection) {
        if (connection.getProvider() == null) {
            throw new IllegalStateException("Storage connection " + connection.getAlias() + " has no provider set.");
        }
        switch (connection.getProvider()) {
            case MINIO:
                return new MinioObjectStorageServiceImpl(this.minioClient(connection));
            case S3:
                return new S3ObjectStorageServiceImpl(this.s3Client(connection));
            case AZURE:
                return new AzureBlobObjectStorageServiceImpl(this.blobServiceClient(connection));
            case FTP:
            case FTPS:
                return new FtpObjectStorageServiceImpl(connection, this.decrypt(connection.getPasswordEnc()),
                    this.cacheManager == null ? null : this.cacheManager.getCache("ftpListing"));
            default:
                throw new IllegalStateException("Unsupported storage provider: " + connection.getProvider());
        }
    }

    /**
     * Lists the buckets/containers the credentials can see, so a connection can be configured by
     * picking a name rather than typing one and finding out it was wrong at first use.
     *
     * This is a different permission from reading a bucket: S3 needs s3:ListAllMyBuckets, which
     * deliberately-scoped keys often lack. A key that can read its one bucket perfectly well may
     * still fail here, so callers should treat a failure as "can't enumerate" rather than "bad
     * credentials", and let the name be typed instead.
     */
    public List<String> listAvailableBuckets(StorageConnection connection) throws Exception {
        if (connection.getProvider() == null) {
            throw new IllegalStateException("Select a provider first.");
        }
        switch (connection.getProvider()) {
            case MINIO:
                return this.minioClient(connection).listBuckets().stream()
                    .map(io.minio.messages.Bucket::name)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(java.util.stream.Collectors.toList());
            case S3:
                return this.s3Client(connection).listBuckets().buckets().stream()
                    .map(software.amazon.awssdk.services.s3.model.Bucket::name)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(java.util.stream.Collectors.toList());
            case AZURE:
                java.util.List<String> containers = new java.util.ArrayList<>();
                this.blobServiceClient(connection).listBlobContainers()
                    .forEach(item -> containers.add(item.getName()));
                containers.sort(String.CASE_INSENSITIVE_ORDER);
                return containers;
            case FTP:
            case FTPS:
                throw new IllegalStateException(
                    "FTP has no concept of buckets -- set the base directory instead.");
            default:
                throw new IllegalStateException("Unsupported storage provider: " + connection.getProvider());
        }
    }

    /**
     * Whether a connection carrying no keys of its own may fall through to the SDK's default
     * credential chain -- that is, borrow whatever IAM identity the process itself runs as.
     *
     * Two conditions, both required. The deployment has to turn it on with
     * storage.allow-instance-role, and the connection has to be platform-level (no tenant):
     * a tenant's connection reaching for the platform's identity would let whoever created it
     * read every bucket that identity can see, which is the whole S3 account rather than the
     * one bucket the connection names.
     */
    public boolean ambientCredentialsAllowed(Long tenantId) {
        return this.allowInstanceRole && tenantId == null;
    }

    private String decrypt(String cipherText) {
        if (cipherText == null || cipherText.trim().isEmpty()) {
            return null;
        }
        try {
            return this.encryptionUtil.decrypt(cipherText);
        } catch (Exception e) {
            logger.error("Could not decrypt a stored storage-connection secret: {}", e.getMessage());
            throw new IllegalStateException(
                "A stored credential for this connection could not be decrypted -- re-enter it and save again.");
        }
    }

    private MinioClient minioClient(StorageConnection connection) {
        this.require(connection.getEndpoint(), "endpoint", connection);
        return MinioClient.builder()
            .endpoint(connection.getEndpoint().trim())
            .credentials(connection.getAccessKey(), this.decrypt(connection.getSecretKeyEnc()))
            .build();
    }

    private S3Client s3Client(StorageConnection connection) {
        S3ClientBuilder builder = S3Client.builder();
        String region = connection.getRegion() != null && !connection.getRegion().trim().isEmpty()
            ? connection.getRegion().trim()
            : "us-east-1";
        builder.region(Region.of(region));
        String accessKey = connection.getAccessKey();
        String secretKey = this.decrypt(connection.getSecretKeyEnc());
        if (accessKey != null && !accessKey.trim().isEmpty() && secretKey != null) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey.trim(), secretKey)));
        } else if (!this.ambientCredentialsAllowed(connection.getTenantId())) {
            throw new IllegalStateException("An S3 connection needs an access key and a secret key.");
        }
        // Past that check with no keys, the SDK's default provider chain takes over -- which is
        // how an IAM role attached to the host/pod is picked up.

        if (connection.getEndpoint() != null && !connection.getEndpoint().trim().isEmpty()) {
            builder.endpointOverride(URI.create(connection.getEndpoint().trim()));
            // S3-compatible endpoints (Wasabi, Ceph, MinIO-behind-S3-API) generally don't
            // support virtual-host style addressing, so keep the bucket in the path.
            builder.forcePathStyle(true);
        }
        return builder.build();
    }

    private BlobServiceClient blobServiceClient(StorageConnection connection) {
        String connectionString = this.decrypt(connection.getAzureConnectionStringEnc());
        if (connectionString != null && !connectionString.trim().isEmpty()) {
            return new BlobServiceClientBuilder().connectionString(connectionString.trim()).buildClient();
        }
        String accountName = connection.getAzureAccountName();
        String accountKey = this.decrypt(connection.getSecretKeyEnc());
        if (accountName == null || accountName.trim().isEmpty() || accountKey == null) {
            throw new IllegalStateException(
                "Azure connection " + connection.getAlias()
                    + " needs either a connection string, or an account name plus account key.");
        }
        String endpoint = connection.getEndpoint() != null && !connection.getEndpoint().trim().isEmpty()
            ? connection.getEndpoint().trim()
            : "https://" + accountName.trim() + ".blob.core.windows.net";
        return new BlobServiceClientBuilder()
            .endpoint(endpoint)
            .credential(new StorageSharedKeyCredential(accountName.trim(), accountKey))
            .buildClient();
    }

    private void require(String value, String field, StorageConnection connection) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(
                "Storage connection " + connection.getAlias() + " is missing a required field: " + field + ".");
        }
    }

}

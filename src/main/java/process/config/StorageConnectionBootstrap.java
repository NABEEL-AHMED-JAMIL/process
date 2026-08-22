package process.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.LookupData;
import process.model.pojo.StorageConnection;
import process.model.repository.LookupDataRepository;
import process.model.repository.StorageConnectionRepository;
import process.util.EncryptionUtil;
import java.sql.Timestamp;
import java.util.Set;

/**
 * One-time, idempotent migration of bucket configuration out of environment variables and
 * into the storage_connection table.
 *
 * Buckets used to be declared as BUCKET_LIST lookup rows carrying only a name and a provider,
 * with the actual credentials supplied globally through per-provider environment
 * variables -- which meant every bucket of a given provider had to share one account, and the
 * secrets lived in docker-compose.yml where they end up committed.
 *
 * On startup this copies whatever is currently configured into a proper per-bucket connection
 * (secrets encrypted at rest via EncryptionUtil), so the environment variables are only needed
 * for the single boot that performs the migration and can be dropped afterwards.
 *
 * It only ever creates rows that are missing -- an existing connection for an alias is left
 * exactly as it is, so this is safe to re-run and never overwrites credentials someone has
 * since changed through the UI.
 */
@Component
public class StorageConnectionBootstrap implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(StorageConnectionBootstrap.class);
    private static final String BUCKET_LIST = "BUCKET_LIST";

    private final LookupDataRepository lookupDataRepository;
    private final StorageConnectionRepository storageConnectionRepository;
    private final EncryptionUtil encryptionUtil;

    @Value("${minio.endpoint:}")
    private String minioEndpoint;

    @Value("${minio.access-key:}")
    private String minioAccessKey;

    @Value("${minio.secret-key:}")
    private String minioSecretKey;

    @Value("${azure.storage.connection-string:}")
    private String azureConnectionString;

    public StorageConnectionBootstrap(LookupDataRepository lookupDataRepository,
        StorageConnectionRepository storageConnectionRepository,
        EncryptionUtil encryptionUtil) {
        this.lookupDataRepository = lookupDataRepository;
        this.storageConnectionRepository = storageConnectionRepository;
        this.encryptionUtil = encryptionUtil;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        try {
            this.migrateBucketLookups();
        } catch (Exception e) {
            // Never let a migration problem stop the application from starting -- the legacy
            // BUCKET_LIST path still resolves these buckets, so the worst case is that they
            // keep using the environment credentials until this is looked at.
            logger.error("Storage connection bootstrap failed; leaving BUCKET_LIST lookups in place.", e);
        }
    }

    private void migrateBucketLookups() {
        LookupData parent = this.lookupDataRepository.findByLookupType(BUCKET_LIST);
        if (parent == null) {
            return;
        }
        Set<LookupData> children = parent.getChildren();
        if (children == null || children.isEmpty()) {
            return;
        }
        int created = 0;
        for (LookupData child : children) {
            String alias = child.getLookupValue();
            if (alias == null || alias.trim().isEmpty()) {
                continue;
            }
            alias = alias.trim();
            if (this.storageConnectionRepository.findByAlias(alias).isPresent()) {
                continue;
            }
            StorageProvider provider = this.parseProvider(child.getDescription());
            if (provider == null) {
                logger.warn("Skipping BUCKET_LIST entry '{}': unrecognised provider '{}'.",
                    alias, child.getDescription());
                continue;
            }
            StorageConnection connection = this.buildConnection(child, alias, provider);
            if (connection == null) {
                continue;
            }
            this.storageConnectionRepository.save(connection);
            created++;
            logger.info("Migrated BUCKET_LIST bucket '{}' ({}) into a storage connection.", alias, provider);
        }
        if (created > 0) {
            logger.info("Storage connection bootstrap created {} connection(s) from BUCKET_LIST lookups. "
                + "The provider environment variables are no longer required for these buckets.", created);
        }
    }

    private StorageProvider parseProvider(String description) {
        if (description == null || description.trim().isEmpty()) {
            return null;
        }
        try {
            return StorageProvider.valueOf(description.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private StorageConnection buildConnection(LookupData child, String alias, StorageProvider provider) {
        StorageConnection connection = new StorageConnection();
        connection.setTenantId(child.getTenantId());
        connection.setConnectionName(child.getLookupType() != null && !child.getLookupType().trim().isEmpty()
            ? child.getLookupType().trim()
            : alias);
        connection.setAlias(alias);
        connection.setProvider(provider);
        connection.setBucketName(alias);
        connection.setDescription("Migrated from the BUCKET_LIST lookup.");
        connection.setStatus(Status.Active);
        connection.setConnectionStatus("UNTESTED");
        connection.setDateCreated(new Timestamp(System.currentTimeMillis()));

        switch (provider) {
            case MINIO:
                if (this.isBlank(this.minioEndpoint)) {
                    logger.warn("Skipping bucket '{}': MINIO_ENDPOINT isn't configured, so there is "
                        + "nothing to migrate into a connection.", alias);
                    return null;
                }
                connection.setEndpoint(this.minioEndpoint.trim());
                connection.setAccessKey(this.trimToNull(this.minioAccessKey));
                if (!this.isBlank(this.minioSecretKey)) {
                    connection.setSecretKeyEnc(this.encryptionUtil.encrypt(this.minioSecretKey.trim()));
                }
                return connection;
            case AZURE:
                if (!this.isBlank(this.azureConnectionString)) {
                    connection.setAzureConnectionStringEnc(
                        this.encryptionUtil.encrypt(this.azureConnectionString.trim()));
                }
                return connection;
            case S3:
                // Nothing to copy: the S3 client falls back to the AWS SDK's default provider
                // chain (env vars, profile, or the host's IAM role) when no keys are stored,
                // which is exactly the behaviour these buckets already had.
                connection.setRegion(this.trimToNull(System.getenv("AWS_DEFAULT_REGION")));
                return connection;
            default:
                // FTP/FTPS never existed as a BUCKET_LIST provider, so there is no env-based
                // configuration that could be migrated for one.
                return null;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String trimToNull(String value) {
        return this.isBlank(value) ? null : value.trim();
    }

}

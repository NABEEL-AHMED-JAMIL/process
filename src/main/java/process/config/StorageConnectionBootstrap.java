package process.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import process.model.enums.Status;
import process.model.service.KafkaSecretService;
import process.model.enums.StorageProvider;
import process.model.pojo.LookupData;
import process.model.pojo.StorageConnection;
import process.model.repository.LookupDataRepository;
import process.model.repository.StorageConnectionRepository;
import process.util.EncryptionUtil;
import java.sql.Timestamp;
import java.util.Optional;
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
 *
 * @author Nabeel Ahmed
 */
@Component
public class StorageConnectionBootstrap implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(StorageConnectionBootstrap.class);
    private static final String BUCKET_LIST = "BUCKET_LIST";

    private final LookupDataRepository lookupDataRepository;
    private final StorageConnectionRepository storageConnectionRepository;
    private final EncryptionUtil encryptionUtil;
    /** Each step gets its own transaction, so the catch in run() sees the commit; see run(). */
    private final TransactionTemplate transactionTemplate;

    @Value("${minio.endpoint:}")
    private String minioEndpoint;

    @Value("${minio.access-key:}")
    private String minioAccessKey;

    @Value("${minio.secret-key:}")
    private String minioSecretKey;

    @Value("${azure.storage.connection-string:}")
    private String azureConnectionString;

    /** Where profile pictures live. Platform-owned, so it carries no tenant. */
    @Value(StoragePropertyDefaults.AVATAR_BUCKET)
    private String avatarBucket;

    public StorageConnectionBootstrap(LookupDataRepository lookupDataRepository,
        StorageConnectionRepository storageConnectionRepository,
        EncryptionUtil encryptionUtil,
        PlatformTransactionManager transactionManager) {
        this.lookupDataRepository = lookupDataRepository;
        this.storageConnectionRepository = storageConnectionRepository;
        this.encryptionUtil = encryptionUtil;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Each step in its own transaction, and the catch outside it.
     *
     * This method used to be @Transactional with the try/catch inside, which could not honour the
     * promise the comment below makes. StorageConnection takes its id from a sequence, so save()
     * does not insert there and then -- the INSERT goes to the database at commit, and the commit
     * happens after this method has returned and the catch block is gone. A constraint failure
     * therefore escaped run() no matter what was caught: Spring Boot wrapped it as "Failed to
     * execute ApplicationRunner" and the context did not start. Two instances coming up together
     * against one database is enough to reach it -- both find etl-avatar missing, both save, and
     * the one that loses the unique index on the alias would have exited instead of carrying on.
     *
     * The transaction still has to exist, because migrateBucketLookups walks LookupData.children,
     * which is lazy. Putting it inside each step rather than around both also means a failure in
     * one does not roll back the other.
     */
    @Override
    public void run(ApplicationArguments args) {
        this.runStep("default bucket seeding", this::ensureDefaultBuckets);
        this.runStep("BUCKET_LIST migration", this::migrateBucketLookups);
    }

    private void runStep(String description, Runnable step) {
        try {
            this.transactionTemplate.executeWithoutResult(status -> step.run());
        } catch (Exception e) {
            // Never let a migration problem stop the application from starting -- the legacy
            // BUCKET_LIST path still resolves these buckets, so the worst case is that they
            // keep using the environment credentials until this is looked at.
            logger.error("Storage connection bootstrap step '{}' failed; leaving BUCKET_LIST "
                + "lookups in place.", description, e);
        }
    }

    /**
     * Creates the two buckets the application itself depends on, when they are missing.
     *
     * These are not a migration of anything -- they are the platform's own storage, named in the
     * requirements as the two defaults, and nothing else creates them. Until now they existed only
     * because somebody had inserted them by hand: on a freshly migrated database there was no
     * storage_connection for either, so the first avatar upload and the first Kafka certificate
     * upload both failed with "Unknown bucket", and the platform-bucket guard had no row to
     * recognise and so protected nothing.
     *
     * Owned by the platform, meaning tenant_id stays null. That is what makes
     * StorageBrowserServiceImpl treat them as platform buckets: only a PLATFORM_ADMIN may browse
     * them, while everyone else reaches them through the avatar and Kafka workflows alone.
     *
     * Only ever creates what is absent, so an installation that has already configured either one
     * -- pointed it at real S3, given it different credentials -- keeps exactly what it has.
     */
    private void ensureDefaultBuckets() {
        String secretBucket = KafkaSecretService.SECRET_BUCKET;
        // Verbatim: StoragePropertyDefaults.AVATAR_BUCKET has already trimmed it and supplied the
        // default for a blank one, and it is the same expression the guard and the uploader read,
        // so the row created here is named what they ask for. Normalising a second time here is
        // how the two came to disagree in the first place.
        this.ensurePlatformBucket(this.avatarBucket,
            "ETL Avatars", "Profile pictures. Each user owns the folder under their own id.");
        this.ensurePlatformBucket(secretBucket, "ETL Bucket",
            "Application storage, including Kafka certificates under kafka-secrets/.");
    }

    private void ensurePlatformBucket(String alias, String connectionName, String description) {
        if (this.storageConnectionRepository.findByAlias(alias).isPresent()) {
            return;
        }
        if (this.isBlank(this.minioEndpoint)) {
            // Left for the operator rather than guessed at: a connection pointing nowhere would
            // look configured and fail at the first upload, which is worse than being absent.
            logger.warn("Default bucket '{}' has no storage connection and MINIO_ENDPOINT is not "
                + "set, so one cannot be created. Avatar and Kafka certificate uploads will fail "
                + "until a connection for it exists.", alias);
            return;
        }
        StorageConnection connection = new StorageConnection();
        connection.setTenantId(null);
        connection.setConnectionName(connectionName);
        connection.setAlias(alias);
        connection.setProvider(StorageProvider.MINIO);
        connection.setBucketName(alias);
        connection.setDescription(description);
        connection.setStatus(Status.Active);
        connection.setConnectionStatus("UNTESTED");
        connection.setDateCreated(new Timestamp(System.currentTimeMillis()));
        connection.setEndpoint(this.minioEndpoint.trim());
        connection.setAccessKey(this.trimToNull(this.minioAccessKey));
        if (!this.isBlank(this.minioSecretKey)) {
            connection.setSecretKeyEnc(this.encryptionUtil.encrypt(this.minioSecretKey.trim()));
        }
        this.storageConnectionRepository.save(connection);
        logger.info("Created the default platform storage connection '{}'.", alias);
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
            Optional<StorageConnection> existing = this.storageConnectionRepository.findByAlias(alias);
            if (existing.isPresent()) {
                this.warnIfAnotherTenantHoldsTheAlias(existing.get(), child, alias);
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

    /**
     * Says so when one tenant's bucket name has already been taken by another tenant's connection.
     *
     * An alias is unique platform-wide, but BUCKET_LIST let every tenant name a bucket for itself,
     * so two of them could each hold an entry reading "reports". Only the first becomes a
     * connection and the second tenant quietly loses the bucket from its browser -- and which one
     * wins depends on the order the children come back in. Nothing else reports that, and the
     * skip above is otherwise indistinguishable from an ordinary already-migrated re-run.
     *
     * A platform-owned row is not a collision: etl-bucket ships as a lookup entry and is meant to
     * be taken over by the default seeded above.
     */
    private void warnIfAnotherTenantHoldsTheAlias(StorageConnection existing, LookupData child, String alias) {
        if (existing.getTenantId() == null || child.getTenantId() == null
            || existing.getTenantId().equals(child.getTenantId())) {
            return;
        }
        logger.warn("BUCKET_LIST entry '{}' belongs to tenant {}, but a storage connection for that "
            + "alias is already owned by tenant {}. Aliases are unique platform-wide, so tenant {} "
            + "can no longer reach this bucket -- give it an alias of its own.",
            alias, child.getTenantId(), existing.getTenantId(), child.getTenantId());
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

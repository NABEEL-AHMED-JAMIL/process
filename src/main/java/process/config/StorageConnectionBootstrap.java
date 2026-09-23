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

    /**
     * The platform's own AWS identity -- the pair SES signs with. What the platform bucket is
     * created with, so that one set of keys covers everything the platform itself does on AWS.
     */
    @Value("${aws.region:us-east-1}")
    private String awsRegion;

    @Value("${aws.endpoint:}")
    private String awsEndpoint;

    @Value("${aws.access-key:}")
    private String awsAccessKey;

    @Value("${aws.secret-key:}")
    private String awsSecretKey;

    /** Whether a key-less platform connection may borrow the instance role; mirrors StorageClientFactory. */
    @Value("${storage.allow-instance-role:false}")
    private boolean allowInstanceRole;

    /** Where profile pictures live. Platform-owned, so it carries no tenant. */
    @Value(StoragePropertyDefaults.AVATAR_BUCKET)
    private String avatarBucket;

    /** Where Kafka key material lives, under kafka-secrets/. Platform-owned likewise. */
    @Value(StoragePropertyDefaults.CONFIG_BUCKET)
    private String configBucket;

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
     * Not a migration of anything -- they are the platform's own storage, and nothing else
     * creates them. Profile pictures go in one, Kafka key material in the other: the pictures
     * are something every user reaches one object of, the key material is something no tenant
     * reaches at all, and keeping them apart keeps the guard on each simple. Everything else a
     * workspace stores goes in a connection it adds itself from Settings > Storage.
     *
     * On S3, with the platform's shared aws.* identity: the same pair SES signs with, and on a
     * deployment whose instance role covers the buckets, no keys at all. Until now they were
     * seeded on MinIO from a second set of environment variables that no longer exist.
     *
     * Owned by the platform, meaning tenant_id stays null. That is what makes
     * StorageBrowserServiceImpl treat them as platform buckets: only a PLATFORM_ADMIN may browse
     * them, while everyone else reaches them through the avatar and Kafka workflows alone.
     *
     * Only ever creates what is absent, so an installation that has already configured either
     * -- pointed it at a different bucket, given it different credentials -- keeps exactly what
     * it has.
     */
    private void ensureDefaultBuckets() {
        // Verbatim: StoragePropertyDefaults has already trimmed each and supplied the default for
        // a blank one, and they are the same expressions the guard and the uploaders read, so the
        // rows created here are named what they ask for. Normalising a second time here is how
        // the two came to disagree in the first place.
        this.ensurePlatformBucket(this.avatarBucket, "ETL Avatars",
            "Profile pictures. Each user owns the folder under their own id.");
        this.ensurePlatformBucket(this.configBucket, "ETL Config",
            "Platform configuration: Kafka certificates and the stores built from them, under kafka-secrets/.");
    }

    private void ensurePlatformBucket(String alias, String connectionName, String description) {
        if (this.storageConnectionRepository.findByTenantIdIsNullAndAlias(alias).isPresent()) {
            return;
        }
        boolean hasKeys = !this.isBlank(this.awsAccessKey) && !this.isBlank(this.awsSecretKey);
        if (!hasKeys && !this.allowInstanceRole) {
            // Left for the operator rather than guessed at: a connection with no way to sign a
            // request would look configured and fail at the first upload, which is worse than
            // being absent.
            logger.warn("Default bucket '{}' has no storage connection and neither AWS_ACCESS_KEY/"
                + "AWS_SECRET_KEY nor STORAGE_ALLOW_INSTANCE_ROLE is set, so one cannot be created. "
                + "Uploads to it will fail until a connection for it exists.", alias);
            return;
        }
        StorageConnection connection = new StorageConnection();
        connection.setTenantId(null);
        connection.setConnectionName(connectionName);
        connection.setAlias(alias);
        connection.setProvider(StorageProvider.S3);
        connection.setBucketName(alias);
        connection.setDescription(description);
        connection.setStatus(Status.Active);
        connection.setConnectionStatus("UNTESTED");
        connection.setDateCreated(new Timestamp(System.currentTimeMillis()));
        connection.setRegion(this.trimToNull(this.awsRegion));
        connection.setEndpoint(this.trimToNull(this.awsEndpoint));
        if (hasKeys) {
            connection.setAccessKey(this.awsAccessKey.trim());
            connection.setSecretKeyEnc(this.encryptionUtil.encrypt(this.awsSecretKey.trim()));
        }
        this.storageConnectionRepository.save(connection);
        logger.info("Created the default platform storage connection '{}' on S3{}.", alias,
            hasKeys ? "" : " (no keys: signing with the instance role)");
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
            // Names are per workspace (MIG-53): the entry is skipped only when its own workspace
            // already has the name, or the platform reserves it.
            if (new process.storage.StorageConnectionLookup(this.storageConnectionRepository)
                    .aliasUnavailable(child.getTenantId(), alias, null)) {
                final String taken = alias;
                this.storageConnectionRepository.findAllByAlias(alias).stream().findFirst()
                    .ifPresent(holder -> this.warnIfAnotherTenantHoldsTheAlias(holder, child, taken));
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
     * An alias is unique within a workspace, and a platform name is reserved everywhere; BUCKET_LIST let every tenant name a bucket for itself,
     * so two of them could each hold an entry reading "reports". Only the first becomes a
     * connection and the second tenant quietly loses the bucket from its browser -- and which one
     * wins depends on the order the children come back in. Nothing else reports that, and the
     * skip above is otherwise indistinguishable from an ordinary already-migrated re-run.
     *
     * A platform-owned row is not a collision: a lookup entry naming the platform bucket is meant
     * to be taken over by the default seeded above.
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
                // There is no global MinIO account any more to copy an endpoint and keys from;
                // a MinIO bucket is a connection somebody adds with its own credentials.
                logger.warn("Skipping bucket '{}': a MinIO bucket has to be added as a storage "
                    + "connection from Settings > Storage, with its own endpoint and keys.", alias);
                return null;
            case AZURE:
                // Likewise: no global Azure account to copy a connection string from.
                logger.warn("Skipping bucket '{}': an Azure container has to be added as a storage "
                    + "connection from Settings > Storage, with its own connection string or account key.", alias);
                return null;
            case S3:
                // Nothing to copy: the S3 client falls back to the AWS SDK's default provider
                // chain (env vars, profile, or the host's IAM role) when no keys are stored,
                // which is exactly the behaviour these buckets already had.
                connection.setRegion(this.trimToNull(this.awsRegion));
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

package process.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.model.repository.LookupDataRepository;
import process.model.repository.StorageConnectionRepository;
import process.util.EncryptionUtil;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two buckets the platform keeps for itself have to exist for the application to work.
 *
 * Nothing created them. They were present on the running installation only because somebody had
 * inserted the rows by hand, so a freshly migrated database had no storage connection for either
 * and the first avatar upload and the first Kafka certificate upload both failed with "Unknown
 * bucket" -- while the platform-bucket guard, which recognises them by their row, protected
 * nothing at all. They are seeded on S3 with the platform's shared aws.* identity, where they
 * used to be seeded on MinIO from a second set of environment variables.
 */
public class DefaultBucketBootstrapTest {

    private final LookupDataRepository lookupDataRepository = mock(LookupDataRepository.class);
    private final StorageConnectionRepository storageConnectionRepository =
        mock(StorageConnectionRepository.class);
    private final EncryptionUtil encryptionUtil = mock(EncryptionUtil.class);

    private StorageConnectionBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        // A stub manager, because each step now runs in its own transaction through a
        // TransactionTemplate rather than under an @Transactional run(). Nothing here asserts on
        // transaction boundaries; the template simply has to have one to call.
        this.bootstrap = new StorageConnectionBootstrap(
            this.lookupDataRepository, this.storageConnectionRepository, this.encryptionUtil,
            mock(PlatformTransactionManager.class));
        this.configureAws(this.bootstrap);
        when(this.encryptionUtil.encrypt(anyString())).thenReturn("ciphertext");
        // No BUCKET_LIST parent, so the legacy migration does nothing and only the new seeding runs.
        when(this.lookupDataRepository.findByLookupType(anyString())).thenReturn(null);
    }

    /** LocalStack-shaped: an endpoint and a static key pair, the local form of the aws.* identity. */
    private void configureAws(StorageConnectionBootstrap target) {
        ReflectionTestUtils.setField(target, "awsRegion", "us-east-1");
        ReflectionTestUtils.setField(target, "awsEndpoint", "http://localstack:4566");
        ReflectionTestUtils.setField(target, "awsAccessKey", "probe-access");
        ReflectionTestUtils.setField(target, "awsSecretKey", "probe-secret");
        ReflectionTestUtils.setField(target, "allowInstanceRole", false);
        ReflectionTestUtils.setField(target, "avatarBucket", "etl-avatar");
        ReflectionTestUtils.setField(target, "configBucket", "etl-config");
    }

    private List<StorageConnection> saved() {
        ArgumentCaptor<StorageConnection> captor = ArgumentCaptor.forClass(StorageConnection.class);
        verify(this.storageConnectionRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void createsBothPlatformBucketsOnAFreshDatabase() {
        when(this.storageConnectionRepository.findByAlias(anyString())).thenReturn(Optional.empty());

        this.bootstrap.run(mock(ApplicationArguments.class));

        List<StorageConnection> created = this.saved();
        assertThat(created).extracting(StorageConnection::getAlias)
            .containsExactlyInAnyOrder("etl-avatar", "etl-config");
        assertThat(created).allSatisfy(connection -> {
            // tenant_id null is what makes StorageBrowserServiceImpl treat these as platform
            // buckets: browsable by a platform admin, reachable by everyone else only through the
            // avatar and Kafka workflows.
            assertThat(connection.getTenantId()).isNull();
            assertThat(connection.getProvider()).isEqualTo(StorageProvider.S3);
            assertThat(connection.getStatus()).isEqualTo(Status.Active);
            assertThat(connection.getRegion()).isEqualTo("us-east-1");
            assertThat(connection.getEndpoint()).isEqualTo("http://localstack:4566");
            assertThat(connection.getAccessKey()).isEqualTo("probe-access");
            assertThat(connection.getSecretKeyEnc()).isEqualTo("ciphertext");
        });
    }

    /** The MinIO-era Kafka bucket is gone: nothing seeds it, whatever its old name was. */
    @Test
    void doesNotSeedTheOldKafkaSecretBucket() {
        when(this.storageConnectionRepository.findByAlias(anyString())).thenReturn(Optional.empty());

        this.bootstrap.run(mock(ApplicationArguments.class));

        assertThat(this.saved()).extracting(StorageConnection::getAlias).doesNotContain("etl-bucket");
    }

    /** An installation that already configured one keeps exactly what it has. */
    @Test
    void leavesAnExistingConnectionAlone() {
        StorageConnection existing = new StorageConnection();
        existing.setAlias("etl-avatar");
        when(this.storageConnectionRepository.findByAlias("etl-avatar")).thenReturn(Optional.of(existing));
        when(this.storageConnectionRepository.findByAlias("etl-config")).thenReturn(Optional.empty());

        this.bootstrap.run(mock(ApplicationArguments.class));

        assertThat(this.saved()).extracting(StorageConnection::getAlias).containsExactly("etl-config");
    }

    @Test
    void createsNothingTwiceWhenBothAlreadyExist() {
        StorageConnection existing = new StorageConnection();
        when(this.storageConnectionRepository.findByAlias(anyString())).thenReturn(Optional.of(existing));

        this.bootstrap.run(mock(ApplicationArguments.class));

        verify(this.storageConnectionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    /**
     * A connection with no way to sign a request would look configured on the storage screen and
     * fail at the first upload, which is harder to diagnose than one that is plainly absent.
     */
    @Test
    void refusesToInventAConnectionWithoutKeysOrAnInstanceRole() {
        ReflectionTestUtils.setField(this.bootstrap, "awsAccessKey", "");
        ReflectionTestUtils.setField(this.bootstrap, "awsSecretKey", "");
        when(this.storageConnectionRepository.findByAlias(anyString())).thenReturn(Optional.empty());

        this.bootstrap.run(mock(ApplicationArguments.class));

        verify(this.storageConnectionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    /**
     * A deployment whose instance role covers the bucket stores no keys at all; the row is created
     * key-less and StorageClientFactory lets the SDK's default chain sign for it.
     */
    @Test
    void seedsAKeylessConnectionWhenTheInstanceRoleIsAllowed() {
        ReflectionTestUtils.setField(this.bootstrap, "awsAccessKey", "");
        ReflectionTestUtils.setField(this.bootstrap, "awsSecretKey", "");
        ReflectionTestUtils.setField(this.bootstrap, "awsEndpoint", "");
        ReflectionTestUtils.setField(this.bootstrap, "allowInstanceRole", true);
        when(this.storageConnectionRepository.findByAlias(anyString())).thenReturn(Optional.empty());

        this.bootstrap.run(mock(ApplicationArguments.class));

        assertThat(this.saved()).hasSize(2).allSatisfy(connection -> {
            assertThat(connection.getProvider()).isEqualTo(StorageProvider.S3);
            assertThat(connection.getAccessKey()).isNull();
            assertThat(connection.getSecretKeyEnc()).isNull();
            assertThat(connection.getEndpoint()).isNull();
        });
    }

    /** Each bucket follows its property, so an installation may name them something else. */
    @Test
    void honoursConfiguredBucketNames() {
        ReflectionTestUtils.setField(this.bootstrap, "avatarBucket", "company-faces");
        ReflectionTestUtils.setField(this.bootstrap, "configBucket", "company-config");
        when(this.storageConnectionRepository.findByAlias(anyString())).thenReturn(Optional.empty());

        this.bootstrap.run(mock(ApplicationArguments.class));

        assertThat(this.saved()).extracting(StorageConnection::getAlias)
            .containsExactlyInAnyOrder("company-faces", "company-config");
    }


    /**
     * The promise the catch block makes, tested where it is actually broken: at commit.
     *
     * run() used to be @Transactional with the try/catch inside it. StorageConnection's id comes
     * from a sequence, so save() does not insert -- the INSERT and any constraint violation with
     * it arrive at commit, which Spring performs after run() has returned and the catch is gone.
     * The application then failed to start with "Failed to execute ApplicationRunner", which is
     * exactly what the comment in that catch block said must never happen. Two instances starting
     * against one database is enough: both see etl-avatar missing, both save, and one loses the
     * unique index on the alias.
     *
     * Making the manager throw on commit reproduces that arrival point. It also pins the second
     * half of the fix -- one failed step must not take the other down with it -- which is only
     * true because each step now has its own transaction.
     */
    @Test
    void aFailureArrivingAtCommitDoesNotStopTheApplicationStarting() {
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        java.util.concurrent.atomic.AtomicInteger commits = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (commits.incrementAndGet() == 1) {
                throw new org.springframework.dao.DataIntegrityViolationException(
                    "duplicate key value violates unique constraint \"uq_storage_connection_alias\"");
            }
            return null;
        }).when(transactionManager).commit(org.mockito.ArgumentMatchers.any());

        StorageConnectionBootstrap bootstrap = new StorageConnectionBootstrap(
            this.lookupDataRepository, this.storageConnectionRepository, this.encryptionUtil,
            transactionManager);
        this.configureAws(bootstrap);
        when(this.storageConnectionRepository.findByAlias(anyString())).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatCode(() -> bootstrap.run(mock(ApplicationArguments.class)))
            .as("a bootstrap failure must never stop the application from starting")
            .doesNotThrowAnyException();

        assertThat(commits.get())
            .as("the second step must still be attempted after the first one failed, which is"
                + " only true while each step has a transaction of its own")
            .isEqualTo(2);
    }
}

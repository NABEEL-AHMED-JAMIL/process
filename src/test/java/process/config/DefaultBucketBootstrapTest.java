package process.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.model.repository.LookupDataRepository;
import process.model.repository.StorageConnectionRepository;
import process.model.service.KafkaSecretService;
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
 * The two buckets the requirements call the defaults have to exist for the application to work.
 *
 * Nothing created them. They were present on the running installation only because somebody had
 * inserted the rows by hand, so a freshly migrated database had no storage connection for either
 * and the first avatar upload and the first Kafka certificate upload both failed with "Unknown
 * bucket" -- while the platform-bucket guard, which recognises them by their row, protected
 * nothing at all.
 */
class DefaultBucketBootstrapTest {

    private final LookupDataRepository lookupDataRepository = mock(LookupDataRepository.class);
    private final StorageConnectionRepository storageConnectionRepository =
        mock(StorageConnectionRepository.class);
    private final EncryptionUtil encryptionUtil = mock(EncryptionUtil.class);

    private StorageConnectionBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        this.bootstrap = new StorageConnectionBootstrap(
            this.lookupDataRepository, this.storageConnectionRepository, this.encryptionUtil);
        ReflectionTestUtils.setField(this.bootstrap, "minioEndpoint", "http://minio:9000");
        ReflectionTestUtils.setField(this.bootstrap, "minioAccessKey", "probe-access");
        ReflectionTestUtils.setField(this.bootstrap, "minioSecretKey", "probe-secret");
        ReflectionTestUtils.setField(this.bootstrap, "avatarBucket", "etl-avatar");
        when(this.encryptionUtil.encrypt(anyString())).thenReturn("ciphertext");
        // No BUCKET_LIST parent, so the legacy migration does nothing and only the new seeding runs.
        when(this.lookupDataRepository.findByLookupType(anyString())).thenReturn(null);
    }

    private List<StorageConnection> saved() {
        ArgumentCaptor<StorageConnection> captor = ArgumentCaptor.forClass(StorageConnection.class);
        verify(this.storageConnectionRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void createsBothDefaultBucketsOnAFreshDatabase() {
        when(this.storageConnectionRepository.findByAlias(anyString())).thenReturn(Optional.empty());

        this.bootstrap.run(mock(ApplicationArguments.class));

        List<StorageConnection> created = this.saved();
        assertThat(created).extracting(StorageConnection::getAlias)
            .containsExactlyInAnyOrder("etl-avatar", KafkaSecretService.SECRET_BUCKET);
        assertThat(created).allSatisfy(connection -> {
            // tenant_id null is what makes StorageBrowserServiceImpl treat these as platform
            // buckets: browsable by a platform admin, reachable by everyone else only through the
            // avatar and Kafka workflows.
            assertThat(connection.getTenantId()).isNull();
            assertThat(connection.getProvider()).isEqualTo(StorageProvider.MINIO);
            assertThat(connection.getStatus()).isEqualTo(Status.Active);
            assertThat(connection.getEndpoint()).isEqualTo("http://minio:9000");
            assertThat(connection.getSecretKeyEnc()).isEqualTo("ciphertext");
        });
    }

    /** An installation that already configured one keeps exactly what it has. */
    @Test
    void leavesAnExistingConnectionAlone() {
        StorageConnection existing = new StorageConnection();
        existing.setAlias("etl-avatar");
        when(this.storageConnectionRepository.findByAlias("etl-avatar")).thenReturn(Optional.of(existing));
        when(this.storageConnectionRepository.findByAlias(KafkaSecretService.SECRET_BUCKET))
            .thenReturn(Optional.empty());

        this.bootstrap.run(mock(ApplicationArguments.class));

        assertThat(this.saved()).extracting(StorageConnection::getAlias)
            .containsExactly(KafkaSecretService.SECRET_BUCKET);
    }

    @Test
    void createsNothingTwiceWhenBothAlreadyExist() {
        StorageConnection existing = new StorageConnection();
        when(this.storageConnectionRepository.findByAlias(anyString())).thenReturn(Optional.of(existing));

        this.bootstrap.run(mock(ApplicationArguments.class));

        verify(this.storageConnectionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    /**
     * A connection pointing nowhere would look configured on the storage screen and fail at the
     * first upload, which is harder to diagnose than one that is plainly absent.
     */
    @Test
    void refusesToInventAConnectionWhenMinioIsNotConfigured() {
        ReflectionTestUtils.setField(this.bootstrap, "minioEndpoint", "");
        when(this.storageConnectionRepository.findByAlias(anyString())).thenReturn(Optional.empty());

        this.bootstrap.run(mock(ApplicationArguments.class));

        verify(this.storageConnectionRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    /** The avatar bucket follows its property, so an installation may name it something else. */
    @Test
    void honoursAConfiguredAvatarBucketName() {
        ReflectionTestUtils.setField(this.bootstrap, "avatarBucket", "company-faces");
        when(this.storageConnectionRepository.findByAlias(anyString())).thenReturn(Optional.empty());

        this.bootstrap.run(mock(ApplicationArguments.class));

        assertThat(this.saved()).extracting(StorageConnection::getAlias)
            .containsExactlyInAnyOrder("company-faces", KafkaSecretService.SECRET_BUCKET);
    }

}

package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.config.StorageClientFactory;
import process.model.dto.BucketSummaryDto;
import process.model.dto.LookupDataDto;
import process.model.repository.StorageConnectionRepository;
import process.model.service.KafkaSecretService;
import process.model.service.ObjectStorageService;
import process.security.TenantContext;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The platform buckets when nothing in storage_connection names them.
 *
 * StorageBrowserServiceImplTenantIsolationTest stubs a tenant-less connection row for etl-avatar
 * and etl-bucket, which is how a hand-configured deployment ends up. No migration creates either
 * row: etl-bucket ships as a BUCKET_LIST lookup entry and etl-avatar as a property, so on a
 * freshly migrated database the guard had nothing to find and did not fire.
 *
 * That mattered because BUCKET_LIST is one of the families a tenant admin may add to. Entering
 * "etl-bucket" as their own bucket put it in their object browser and resolved the platform's own
 * MinIO client -- every tenant's Kafka private keys, every tenant's PDF uploads, every picture.
 *
 * @author Nabeel Ahmed
 */
class PlatformBucketNamedGuardTest {

    private static final long TENANT_A = 1001L;
    private static final String AVATAR_BUCKET = "etl-avatar";
    private static final String PLATFORM_BUCKET = KafkaSecretService.SECRET_BUCKET;

    private final LookupDataCacheService lookupDataCacheService = mock(LookupDataCacheService.class);
    private final StorageConnectionRepository storageConnectionRepository =
        mock(StorageConnectionRepository.class);
    private final StorageClientFactory storageClientFactory = mock(StorageClientFactory.class);
    private final ObjectStorageService minio = mock(ObjectStorageService.class);

    private StorageBrowserServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new StorageBrowserServiceImpl(this.lookupDataCacheService,
            this.storageConnectionRepository, this.storageClientFactory,
            this.minio, mock(ObjectStorageService.class), mock(ObjectStorageService.class),
            AVATAR_BUCKET);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** What a tenant admin can produce for themselves through Settings > Lookup. */
    private void givenTenantOwnedLookupFor(String bucket) {
        LookupDataDto child = new LookupDataDto();
        child.setLookupType("My bucket");
        child.setLookupValue(bucket);
        child.setDescription("MINIO");
        child.setTenantId(TENANT_A);
        LookupDataDto parent = new LookupDataDto();
        parent.setLookupValue("BUCKET_LIST");
        parent.setChildren(new HashSet<>(Collections.singletonList(child)));
        when(this.lookupDataCacheService.getParentLookupById("BUCKET_LIST")).thenReturn(parent);
    }

    private void actAsTenantAdmin() {
        TenantContext.set(TENANT_A, "TENANT_ADMIN", 61L, "admin@tenant.example");
    }

    @Test
    void aLookupEntryOfItsOwnDoesNotOpenThePlatformBucket() {
        this.givenTenantOwnedLookupFor(PLATFORM_BUCKET);
        this.actAsTenantAdmin();

        assertThatThrownBy(() -> this.service.listObjects(PLATFORM_BUCKET, "kafka-secrets/", null, 50))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown bucket");
        assertThatThrownBy(() -> this.service.downloadObject(
                PLATFORM_BUCKET, "kafka-secrets/9/u/2026-08-31/client.key", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown bucket");
        assertThatThrownBy(() -> this.service.deleteFolder(PLATFORM_BUCKET, "kafka-secrets/"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown bucket");
        verify(this.minio, never()).listObjects(anyString(), anyString(), any(), anyInt());
        verify(this.minio, never()).getObjectContent(anyString(), anyString(), any(), any());
        verify(this.minio, never()).deleteFolder(anyString(), anyString());
    }

    /** The avatar bucket is named by a property alone, so it has even less to be inferred from. */
    @Test
    void norTheAvatarBucket() {
        this.givenTenantOwnedLookupFor(AVATAR_BUCKET);
        this.actAsTenantAdmin();

        assertThatThrownBy(() -> this.service.downloadObject(AVATAR_BUCKET, "9/profile/avatar.png", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown bucket");
        verify(this.minio, never()).getObjectContent(anyString(), anyString(), any(), any());
    }

    // The own-picture exception is unaffected and stays where it is exercised against a properly
    // configured platform connection: StorageBrowserServiceImplTenantIsolationTest.

    @Test
    void andItIsNotOfferedInTheBucketListEither() {
        this.givenTenantOwnedLookupFor(PLATFORM_BUCKET);
        this.actAsTenantAdmin();

        List<BucketSummaryDto> buckets = this.service.listBuckets();

        assertThat(buckets).extracting(BucketSummaryDto::getBucket).doesNotContain(PLATFORM_BUCKET);
    }

    @Test
    void aPlatformAdminStillReachesIt() {
        this.givenTenantOwnedLookupFor(PLATFORM_BUCKET);
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");

        this.service.deleteFolder(PLATFORM_BUCKET, "kafka-secrets/");

        verify(this.minio).deleteFolder(PLATFORM_BUCKET, "kafka-secrets/");
    }

    /** And so does a workflow, which is the reason the trusted path exists. */
    @Test
    void aWorkflowStillReachesIt() {
        this.givenTenantOwnedLookupFor(PLATFORM_BUCKET);

        this.service.readForWorkflow(PLATFORM_BUCKET, "kafka-secrets/9/u/2026-08-31/truststore-a1b2c3d4.p12");

        verify(this.minio).getObjectContent(
            PLATFORM_BUCKET, "kafka-secrets/9/u/2026-08-31/truststore-a1b2c3d4.p12", null, null);
    }

}

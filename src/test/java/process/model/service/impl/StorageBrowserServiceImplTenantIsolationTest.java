package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import process.config.StorageClientFactory;
import process.model.dto.LookupDataDto;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.model.repository.StorageConnectionRepository;
import process.model.service.ObjectStorageService;
import process.security.TenantContext;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Who may reach what in a platform-owned bucket.
 *
 * etl-avatar and etl-bucket have no tenant of their own, which is what makes them shared: every
 * user's picture, every Kafka truststore and every PDF upload lands in one of the two. So the
 * usual "does this connection belong to your tenant" test says nothing about them, and the whole
 * rule is the one enforced here -- a platform admin manages them, everybody else reaches exactly
 * one object, their own avatar, and an application workflow reaches a file it wrote itself.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class StorageBrowserServiceImplTenantIsolationTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 2002L;
    private static final long USER_A = 61L;
    private static final String AVATAR_BUCKET = "etl-avatar";
    private static final String PLATFORM_BUCKET = "etl-bucket";
    private static final String TENANT_BUCKET = "tenant-a-exports";
    private static final String LEGACY_BUCKET = "legacy-lookup-bucket";

    @Mock
    private LookupDataCacheService lookupDataCacheService;
    @Mock
    private StorageConnectionRepository storageConnectionRepository;
    @Mock
    private StorageClientFactory storageClientFactory;
    @Mock
    private ObjectStorageService minioObjectStorageService;
    @Mock
    private ObjectStorageService s3ObjectStorageService;
    @Mock
    private ObjectStorageService azureBlobObjectStorageService;

    private StorageBrowserServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new StorageBrowserServiceImpl(this.lookupDataCacheService,
            this.storageConnectionRepository, this.storageClientFactory,
            this.minioObjectStorageService, this.s3ObjectStorageService, this.azureBlobObjectStorageService,
            AVATAR_BUCKET);

        // The guard resolves by alias alone. Matching only Active rows let a retired or
        // soft-deleted platform connection fall through to the legacy lookup path, where a tenant
        // could name the same bucket -- so both stubs are set and the guard reads findByAlias.
        for (String bucket : new String[] { AVATAR_BUCKET, PLATFORM_BUCKET }) {
            lenient().when(this.storageConnectionRepository.findByAlias(bucket))
                .thenReturn(Optional.of(this.connection(bucket, null)));
            lenient().when(this.storageConnectionRepository.findByAliasAndStatus(bucket, Status.Active))
                .thenReturn(Optional.of(this.connection(bucket, null)));
        }
        lenient().when(this.storageConnectionRepository.findByAlias(TENANT_BUCKET))
            .thenReturn(Optional.of(this.connection(TENANT_BUCKET, TENANT_A)));
        lenient().when(this.storageConnectionRepository.findByAliasAndStatus(TENANT_BUCKET, Status.Active))
            .thenReturn(Optional.of(this.connection(TENANT_BUCKET, TENANT_A)));
        lenient().when(this.storageClientFactory.serviceFor(any())).thenReturn(this.minioObjectStorageService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** The alias doubles as the real bucket name, so nothing is rewritten on the way through. */
    private StorageConnection connection(String alias, Long tenantId) {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(700L);
        connection.setTenantId(tenantId);
        connection.setConnectionName(alias);
        connection.setAlias(alias);
        connection.setBucketName(alias);
        connection.setProvider(StorageProvider.MINIO);
        connection.setStatus(Status.Active);
        return connection;
    }

    private void actAsTenantUser(long tenantId, long appUserId) {
        TenantContext.set(tenantId, "TENANT_USER", appUserId, "tenant-user@example.com");
    }

    private void actAsPlatformAdmin() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@example.com");
    }

    @Test
    void aTenantUserCannotDeleteAFolderInAPlatformBucket() {
        this.actAsTenantUser(TENANT_A, USER_A);

        assertThatThrownBy(() -> this.service.deleteFolder(PLATFORM_BUCKET, "kafka-secrets/"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown bucket");
        verify(this.minioObjectStorageService, never()).deleteFolder(anyString(), anyString());
    }

    @Test
    void aTenantUserCannotRenameAFolderInAPlatformBucket() {
        this.actAsTenantUser(TENANT_A, USER_A);

        assertThatThrownBy(() -> this.service.renameFolder(AVATAR_BUCKET, "9/profile/", "mine"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown bucket");
        verify(this.minioObjectStorageService, never()).renameFolder(anyString(), anyString(), anyString());
    }

    @Test
    void aTenantUserCannotDeleteAnotherUsersProfileFolder() {
        this.actAsTenantUser(TENANT_A, USER_A);

        assertThatThrownBy(() -> this.service.deleteFolder(AVATAR_BUCKET, "9/profile/"))
            .isInstanceOf(IllegalArgumentException.class);
        verify(this.minioObjectStorageService, never()).deleteFolder(anyString(), anyString());
    }

    @Test
    void aTenantUserCannotDownloadAnotherUsersPicture() {
        this.actAsTenantUser(TENANT_A, USER_A);

        assertThatThrownBy(() -> this.service.downloadObject(AVATAR_BUCKET, "9/profile/avatar.png", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown bucket");
        verify(this.minioObjectStorageService, never()).getObjectContent(anyString(), anyString(), any(), any());
    }

    @Test
    void aTenantUserCannotDownloadAnotherTenantsUploadFromThePlatformBucket() {
        this.actAsTenantUser(TENANT_B, 999L);

        assertThatThrownBy(() -> this.service.downloadObject(PLATFORM_BUCKET, "document-converter/41/input.docx", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown bucket");
        verify(this.minioObjectStorageService, never()).getObjectContent(anyString(), anyString(), any(), any());
    }

    @Test
    void aTenantUserCannotReadMetadataOrListAPlatformBucket() {
        this.actAsTenantUser(TENANT_A, USER_A);

        assertThatThrownBy(() -> this.service.getObjectMetadata(PLATFORM_BUCKET, "kafka-secrets/2024/truststore.p12"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> this.service.listObjects(PLATFORM_BUCKET, "", null, 50))
            .isInstanceOf(IllegalArgumentException.class);
        verify(this.minioObjectStorageService, never()).getObjectMetadata(anyString(), anyString());
        verify(this.minioObjectStorageService, never()).listObjects(anyString(), anyString(), any(), anyInt());
    }

    @Test
    void aTenantUserMayStillReachTheirOwnPicture() {
        this.actAsTenantUser(TENANT_A, USER_A);

        this.service.downloadObject(AVATAR_BUCKET, USER_A + "/profile/avatar.png", null, null);

        verify(this.minioObjectStorageService)
            .getObjectContent(AVATAR_BUCKET, USER_A + "/profile/avatar.png", null, null);
    }

    @Test
    void anIdThatMerelyBeginsWithTheCallersIsADifferentPerson() {
        this.actAsTenantUser(TENANT_A, 1248L);

        assertThatThrownBy(() -> this.service.downloadObject(AVATAR_BUCKET, "12480/profile/avatar.png", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown bucket");
        verify(this.minioObjectStorageService, never()).getObjectContent(anyString(), anyString(), any(), any());
    }

    @Test
    void aKeyThatWalksOutOfTheProfileFolderIsRefusedOutright() {
        this.actAsTenantUser(TENANT_A, USER_A);

        // The FTP backends collapse this to 9/profile/avatar.png after the prefix test would have
        // passed it, so the traversal has to be refused before anything authorises the key.
        assertThatThrownBy(() -> this.service.downloadObject(
                AVATAR_BUCKET, USER_A + "/profile/../../9/profile/avatar.png", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid key");
        assertThatThrownBy(() -> this.service.deleteObject(
                AVATAR_BUCKET, USER_A + "/profile/../9/profile/avatar.png"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid key");
        verifyNoInteractions(this.minioObjectStorageService);
    }

    @Test
    void aBackslashIsTheSameTrickWithTheOtherSeparator() {
        this.actAsTenantUser(TENANT_A, USER_A);

        assertThatThrownBy(() -> this.service.deleteObject(AVATAR_BUCKET, USER_A + "/profile/..\\9\\avatar.png"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid key");
        verifyNoInteractions(this.minioObjectStorageService);
    }

    @Test
    void theOwnProfileExceptionDoesNotExtendToTheOtherPlatformBucket() {
        this.actAsTenantUser(TENANT_A, USER_A);

        // Avatars live in one bucket. On the key alone, "61/profile/" also read as this caller's
        // own object in etl-bucket, where it names nothing anybody owns and its neighbours are
        // Kafka key material and other tenants' uploads.
        assertThatThrownBy(() -> this.service.downloadObject(
                PLATFORM_BUCKET, USER_A + "/profile/avatar.png", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown bucket");
        assertThatThrownBy(() -> this.service.uploadObject(PLATFORM_BUCKET, USER_A + "/profile/avatar.png",
                new ByteArrayInputStream(new byte[] { 1 }), 1L, "image/png"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown bucket");
        assertThatThrownBy(() -> this.service.deleteObject(PLATFORM_BUCKET, USER_A + "/profile/avatar.png"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown bucket");
        verifyNoInteractions(this.minioObjectStorageService);
    }

    @Test
    void aPlatformAdminManagesThePlatformBuckets() {
        this.actAsPlatformAdmin();

        this.service.deleteFolder(PLATFORM_BUCKET, "kafka-secrets/");
        this.service.renameFolder(PLATFORM_BUCKET, "kafka-secrets/", "kafka-material");

        verify(this.minioObjectStorageService).deleteFolder(PLATFORM_BUCKET, "kafka-secrets/");
        verify(this.minioObjectStorageService).renameFolder(PLATFORM_BUCKET, "kafka-secrets/", "kafka-material/");
    }

    @Test
    void anApplicationWorkflowStillReachesTheFileItWrote() {
        this.actAsTenantUser(TENANT_A, USER_A);

        // DocumentConverterServiceImpl has already checked the task row belongs to this caller,
        // and builds the key from it -- which is why this one is allowed where a browse is not.
        this.service.readForWorkflow(PLATFORM_BUCKET, "document-converter/41/input.docx");

        verify(this.minioObjectStorageService)
            .getObjectContent(PLATFORM_BUCKET, "document-converter/41/input.docx", null, null);
    }

    @Test
    void aTenantUserStillManagesTheirOwnTenantsBucket() {
        this.actAsTenantUser(TENANT_A, USER_A);

        this.service.deleteFolder(TENANT_BUCKET, "exports/");
        this.service.deleteObjects(TENANT_BUCKET, Collections.singletonList("exports/run-1.csv"));

        verify(this.minioObjectStorageService).deleteFolder(TENANT_BUCKET, "exports/");
        verify(this.minioObjectStorageService).deleteObjects(eq(TENANT_BUCKET), any());
    }

    @Test
    void oneTenantStillCannotReachAnothersBucket() {
        this.actAsTenantUser(TENANT_B, 999L);

        assertThatThrownBy(() -> this.service.deleteFolder(TENANT_BUCKET, "exports/"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown bucket");
        verify(this.minioObjectStorageService, never()).deleteFolder(anyString(), anyString());
    }

    @Test
    void anUnauthenticatedContextOwnsNothing() {
        assertThat(TenantContext.getAppUserId()).isNull();

        assertThatThrownBy(() -> this.service.downloadObject(AVATAR_BUCKET, "61/profile/avatar.png", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown bucket");
        verify(this.minioObjectStorageService, never()).getObjectContent(anyString(), anyString(), any(), any());
    }

    @Test
    void aWorkflowReachesALegacyBucketOnAThreadThatHasNoTenant() {
        // The scheduler and the startup provisioner run with no TenantContext at all, while a
        // bucket configured the old way carries the tenant it was stamped with at seed time. So
        // every test the browse path applies finds nobody to match and refuses: the console works
        // and the dispatch fails, which is the split the trusted read exists to avoid.
        this.givenLegacyBucketLookup();
        assertThat(TenantContext.getTenantId()).isNull();

        this.service.readForWorkflow(LEGACY_BUCKET, "kafka-secrets/2024/truststore.p12");

        verify(this.minioObjectStorageService)
            .getObjectContent(LEGACY_BUCKET, "kafka-secrets/2024/truststore.p12", null, null);
    }

    @Test
    void browsingThatSameLegacyBucketIsStillNarrowedToItsTenant() {
        this.givenLegacyBucketLookup();
        this.actAsTenantUser(TENANT_B, 999L);

        assertThatThrownBy(() -> this.service.downloadObject(LEGACY_BUCKET, "exports/run-1.csv", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown bucket");
        verify(this.minioObjectStorageService, never()).getObjectContent(anyString(), anyString(), any(), any());
    }

    @Test
    void anUploadedNameThatKeepsASeparatorIsRefusedRatherThanStored() {
        this.actAsTenantUser(TENANT_A, USER_A);

        // Paths.get leaves the backslash in on a Linux JVM, so this composed a key that every read
        // path then refused -- an object the browser could create and could never open or delete.
        MockMultipartFile file = new MockMultipartFile("file", "a\\b.txt", "text/plain", new byte[] { 1 });

        assertThatThrownBy(() -> this.service.uploadObject(TENANT_BUCKET, "exports/", file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid key");
        verify(this.minioObjectStorageService, never())
            .uploadObject(anyString(), anyString(), any(), anyLong(), any());
    }

    @Test
    void anUploadWithNoNameAtAllIsRefusedRatherThanThrowingNull() {
        this.actAsTenantUser(TENANT_A, USER_A);
        MultipartFile nameless = mock(MultipartFile.class);
        when(nameless.getOriginalFilename()).thenReturn(null);

        assertThatThrownBy(() -> this.service.uploadObject(TENANT_BUCKET, "exports/", nameless))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no name");
        verifyNoInteractions(this.minioObjectStorageService);
    }

    /** A bucket that exists only as a BUCKET_LIST lookup row, stamped with the default tenant. */
    private void givenLegacyBucketLookup() {
        LookupDataDto child = new LookupDataDto();
        child.setLookupType("Legacy bucket");
        child.setLookupValue(LEGACY_BUCKET);
        child.setDescription("MINIO");
        child.setTenantId(TENANT_A);
        LookupDataDto parent = new LookupDataDto();
        parent.setLookupValue("BUCKET_LIST");
        parent.setChildren(new HashSet<>(Collections.singletonList(child)));
        when(this.lookupDataCacheService.getParentLookupById("BUCKET_LIST")).thenReturn(parent);
    }
}

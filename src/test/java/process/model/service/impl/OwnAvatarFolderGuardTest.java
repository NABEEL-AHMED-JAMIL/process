package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.config.StorageClientFactory;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.model.repository.StorageConnectionRepository;
import process.model.service.ObjectStorageService;
import process.security.TenantContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The edge of the own-picture exception: the folder itself, and a key that only reads as one.
 *
 * The exception is expressed as "this key starts with your own folder", and "61/profile/" starts
 * with "61/profile/" as readily as a file inside it does -- so deleteFolder, renameFolder and
 * createFolder all reached it with a prefix rather than a file, which is managing a bucket the
 * platform admin owns rather than putting a picture in it. The rule that stops that is one line in
 * isOwnProfileObject and had no test of its own: PlatformBucketAccessTest asserts against a copy of
 * the rule written in the test class, which does not carry it, and every other case in
 * StorageBrowserServiceImplTenantIsolationTest names somebody else's folder, where the id settles
 * it before the folder shape is ever reached.
 *
 * The platform connection is stubbed exactly as a configured deployment has it, and
 * theSameSetupStillHandsThemTheirOwnPicture is the control: the bucket resolves in this fixture, so
 * a refusal below can only have come from the guard and not from there being nothing to resolve.
 *
 * @author Nabeel Ahmed
 */
class OwnAvatarFolderGuardTest {

    private static final long TENANT_A = 1001L;
    private static final long USER_A = 61L;
    private static final String AVATAR_BUCKET = "etl-avatar";
    private static final String OWN_FOLDER = USER_A + "/profile/";

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
        StorageConnection platform = new StorageConnection();
        platform.setStorageConnectionId(700L);
        platform.setTenantId(null);
        platform.setConnectionName("ETL Avatars");
        platform.setAlias(AVATAR_BUCKET);
        platform.setBucketName(AVATAR_BUCKET);
        platform.setProvider(StorageProvider.MINIO);
        platform.setStatus(Status.Active);
        when(this.storageConnectionRepository.findByAlias(AVATAR_BUCKET)).thenReturn(Optional.of(platform));
        when(this.storageConnectionRepository.findByAliasAndStatus(AVATAR_BUCKET, Status.Active))
            .thenReturn(Optional.of(platform));
        when(this.storageClientFactory.serviceFor(any())).thenReturn(this.minio);
        TenantContext.set(TENANT_A, "TENANT_USER", USER_A, "tenant-user@example.com");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void theSameSetupStillHandsThemTheirOwnPicture() {
        this.service.downloadObject(AVATAR_BUCKET, OWN_FOLDER + "avatar.png", null, null);

        verify(this.minio).getObjectContent(AVATAR_BUCKET, OWN_FOLDER + "avatar.png", null, null);
    }

    @Test
    void butNotToDeleteTheFolderThePictureIsIn() {
        // Deleting <appUserId>/profile/ takes the object out from under the avatar_key already
        // recorded on the row, which is a bucket the platform admin manages losing a file.
        assertThatThrownBy(() -> this.service.deleteFolder(AVATAR_BUCKET, OWN_FOLDER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Unknown bucket: " + AVATAR_BUCKET + ".");
        verifyNoInteractions(this.minio);
    }

    @Test
    void norToRenameIt() {
        assertThatThrownBy(() -> this.service.renameFolder(AVATAR_BUCKET, OWN_FOLDER, "mine"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Unknown bucket: " + AVATAR_BUCKET + ".");
        verifyNoInteractions(this.minio);
    }

    @Test
    void norToBuildFoldersOfTheirOwnInsideIt() {
        assertThatThrownBy(() -> this.service.createFolder(AVATAR_BUCKET, OWN_FOLDER, "keep"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Unknown bucket: " + AVATAR_BUCKET + ".");
        verifyNoInteractions(this.minio);
    }

    /**
     * "." resolves to nothing at all, so it is the traversal segment that looks harmless -- and it
     * is refused rather than collapsed, because a rewritten key is not the key that was authorised.
     */
    @Test
    void aDotSegmentIsRefusedEvenInsideTheirOwnFolder() {
        assertThatThrownBy(() -> this.service.downloadObject(
                AVATAR_BUCKET, OWN_FOLDER + "./avatar.png", null, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid key");
        verifyNoInteractions(this.minio);
    }

}

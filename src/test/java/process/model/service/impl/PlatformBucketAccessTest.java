package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import process.model.repository.StorageConnectionRepository;
import process.model.service.ObjectStorageService;
import process.security.TenantContext;
import process.config.StorageClientFactory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The rule that lets a tenant user reach their own avatar in a platform bucket, and nothing else.
 *
 * Every user's picture now lives in etl-avatar, which has no tenant. Reading was once left open
 * on the argument that naming one object by key is harmless; it is not, because the ids in those
 * keys are sequential. So the same scope now covers reading, writing and listing alike -- without
 * it a tenant could collect every user's picture, or overwrite somebody else's.
 *
 * Every case below calls the real isOwnProfileObject on a real StorageBrowserServiceImpl. This
 * class used to carry a private re-implementation of it instead -- "mirrors isOwnProfileObject
 * without needing a Spring context around it" -- which meant ten passing tests that never loaded
 * the file they are named after: the whole guard could be deleted from the service and this suite
 * would still be green. The copy had already drifted, too. Production refuses a key ending in
 * "/", which is the line that stops deleteFolder and renameFolder from using the avatar exception
 * against the caller's own folder; the mirror had no such rule, so it answered true where the
 * real code answers false. A copy of a security rule is not a test of it.
 *
 * Reflection rather than a public seam, because the method is private on purpose and widening it
 * for a test would be a worse trade than reaching past the modifier here. Nothing else about the
 * service is exercised, so its collaborators are bare mocks.
 *
 * @author Nabeel Ahmed
 */
public class PlatformBucketAccessTest {

    private static final String AVATAR_BUCKET = "etl-avatar";

    private final StorageBrowserServiceImpl service = new StorageBrowserServiceImpl(
        mock(LookupDataCacheService.class),
        mock(StorageConnectionRepository.class),
        mock(StorageClientFactory.class),
        mock(ObjectStorageService.class),
        mock(ObjectStorageService.class),
        mock(ObjectStorageService.class),
        AVATAR_BUCKET);

    /** The guard reads the caller from the thread, so a case is set up by becoming that caller. */
    private boolean ownProfile(Long callerId, String bucket, String key) {
        TenantContext.clear();
        if (callerId != null) {
            TenantContext.set(7L, "TENANT_USER", callerId, "user-" + callerId);
        }
        Boolean answer = ReflectionTestUtils.invokeMethod(
            this.service, "isOwnProfileObject", bucket, key);
        return Boolean.TRUE.equals(answer);
    }

    /** Every case below is about the key, so it asks about the bucket the avatars are in. */
    private boolean ownProfile(Long callerId, String key) {
        return this.ownProfile(callerId, AVATAR_BUCKET, key);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void aUserMayWriteTheirOwnPicture() {
        assertTrue(ownProfile(1248L, "1248/profile/avatar.jpg"));
        assertTrue(ownProfile(1248L, "1248/profile/avatar.png"));
    }

    @Test
    void theExceptionBelongsToTheAvatarBucketAlone() {
        // "1248/profile/" describes a real folder in one bucket and nothing at all in the other,
        // where the neighbouring folders are Kafka key material and other tenants' uploads.
        assertFalse(ownProfile(1248L, "etl-bucket", "1248/profile/avatar.jpg"),
            "a folder name is not a licence in a bucket the avatars were never in");
        assertTrue(ownProfile(1248L, AVATAR_BUCKET, "1248/profile/avatar.jpg"));
    }

    @Test
    void aUserMayNotWriteSomebodyElsesPicture() {
        assertFalse(ownProfile(1248L, "1249/profile/avatar.jpg"),
            "a neighbour's folder is not theirs to write");
    }

    @Test
    void thePrefixMustNotBeSatisfiedByALongerId() {
        // "12480/profile/..." starts with "1248" as text; the separator is what stops it.
        assertFalse(ownProfile(1248L, "12480/profile/avatar.jpg"),
            "an id that merely begins with theirs is a different person");
    }

    @Test
    void nothingOutsideTheProfileFolderIsAllowed() {
        assertFalse(ownProfile(1248L, "1248/exports/payroll.csv"));
        assertFalse(ownProfile(1248L, "1248/avatar.jpg"));
        assertFalse(ownProfile(1248L, "avatars/1248/profile/avatar.jpg"));
    }

    /**
     * The case the old mirror got wrong, and the reason a copy is not a test.
     *
     * A key ending in "/" is a prefix, not an object, and it is how deleteFolder and renameFolder
     * arrive. "1248/profile/" starts with "1248/profile/", so on the prefix test alone the
     * exception meant for putting one picture in a platform bucket would have let a user delete
     * or rename their whole folder there.
     */
    @Test
    void theCallersOwnFolderIsNotOneOfTheirObjects() {
        assertFalse(ownProfile(1248L, "1248/profile/"),
            "a trailing separator names a folder, which deleteFolder and renameFolder act on");
        assertFalse(ownProfile(1248L, "1248/profile/holiday/"),
            "a folder deeper inside their own is still a folder");
    }

    @Test
    void listingIsNeverSomebodysOwnObject() {
        // A null key means enumerate rather than name -- which is exactly what must stay closed.
        assertFalse(ownProfile(1248L, null));
    }

    @Test
    void anAnonymousCallerOwnsNothing() {
        assertFalse(ownProfile(null, "1248/profile/avatar.jpg"));
    }

    @Test
    void aKeyMayNotWalkBackOutOfTheProfileFolder() {
        // The FTP backends collapse these before touching the server, so the string that reads as
        // the caller's own folder is not the path that would be written.
        assertFalse(ownProfile(1248L, "1248/profile/../../1249/profile/avatar.jpg"),
            "a traversal makes the prefix say one folder and the write land in another");
        assertFalse(ownProfile(1248L, "1248/profile/../avatar.jpg"));
        assertFalse(ownProfile(1248L, "1248/profile/..\\1249\\avatar.jpg"),
            "a backslash is the same trick with the other separator");
        assertFalse(ownProfile(1248L, "/1248/profile/avatar.jpg"),
            "a leading separator is a different path once it is resolved");
    }

    @Test
    void aDotSegmentIsRefusedEvenWhereItWouldBeHarmless() {
        // "./" resolves to nothing, but a key is refused rather than rewritten -- otherwise the
        // check and the backend are reasoning about two different strings again.
        assertFalse(ownProfile(1248L, "1248/profile/./avatar.jpg"));
    }
}

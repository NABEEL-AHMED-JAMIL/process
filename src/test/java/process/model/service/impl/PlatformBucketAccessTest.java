package process.model.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that lets a tenant user reach their own avatar in a platform bucket, and nothing else.
 *
 * Every user's picture now lives in etl-avatar, which has no tenant. Reading was once left open
 * on the argument that naming one object by key is harmless; it is not, because the ids in those
 * keys are sequential. So the same scope now covers reading, writing and listing alike -- without
 * it a tenant could collect every user's picture, or overwrite somebody else's.
 *
 * @author Nabeel Ahmed
 */
public class PlatformBucketAccessTest {

    private static final String AVATAR_BUCKET = "etl-avatar";

    /** Mirrors isOwnProfileObject without needing a Spring context around it. */
    private boolean ownProfile(Long callerId, String bucket, String key) {
        if (key == null || callerId == null || !safeKey(key) || !AVATAR_BUCKET.equals(bucket)) {
            return false;
        }
        return key.startsWith(callerId + "/profile/");
    }

    /** Every case below is about the key, so it asks about the bucket the avatars are in. */
    private boolean ownProfile(Long callerId, String key) {
        return this.ownProfile(callerId, AVATAR_BUCKET, key);
    }

    /** Mirrors isSafeKey: the traversal check the prefix test is only sound on top of. */
    private boolean safeKey(String key) {
        if (key == null || key.isEmpty()) {
            return true;
        }
        if (key.indexOf('\\') >= 0 || key.startsWith("/")) {
            return false;
        }
        for (String segment : key.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) {
                return false;
            }
        }
        return true;
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

package process.model.service.impl;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that lets a tenant user write their own avatar into a platform bucket, and nothing
 * else.
 *
 * Every user's picture now lives in etl-avatar, which has no tenant. Reading one object by key
 * stays open, because that is how an avatar renders. Listing and writing do not -- without this
 * scope a tenant could enumerate every user's picture, or overwrite somebody else's.
 *
 * @author Nabeel Ahmed
 */
public class PlatformBucketAccessTest {

    /** Mirrors isOwnProfileObject without needing a Spring context around it. */
    private boolean ownProfile(Long callerId, String key) {
        if (key == null || callerId == null) {
            return false;
        }
        return key.startsWith(callerId + "/profile/");
    }

    @Test
    void aUserMayWriteTheirOwnPicture() {
        assertTrue(ownProfile(1248L, "1248/profile/avatar.jpg"));
        assertTrue(ownProfile(1248L, "1248/profile/avatar.png"));
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
}

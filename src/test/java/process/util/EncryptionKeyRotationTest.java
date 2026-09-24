package process.util;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-5: process's encryption key is rotated, because the old one was committed as a fallback in git.
 * The new key has an id and seals through platform-commons' keyring ("k<id>:" + GCM, byte-compatible
 * with process's own format). The old key stays only to OPEN what it sealed until every value has been
 * re-sealed (EncryptionReseal), and is then removed from the environment.
 */
class EncryptionKeyRotationTest {

    private static String newKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static EncryptionUtil util(String legacy, String currentId, String current) {
        EncryptionUtil util = new EncryptionUtil();
        ReflectionTestUtils.setField(util, "base64Key", legacy);
        ReflectionTestUtils.setField(util, "currentKeyId", currentId);
        ReflectionTestUtils.setField(util, "currentKey", current);
        return util;
    }

    @Test
    void withACurrentKeyNewValuesAreSealedUnderItAndTaggedWithItsId() {
        EncryptionUtil util = util(newKey(), "p2026a", newKey());
        String sealed = util.encrypt("sasl-secret");
        assertThat(sealed).startsWith("kp2026a:");
        assertThat(util.decrypt(sealed)).isEqualTo("sasl-secret");
        assertThat(util.isCurrent(sealed)).isTrue();
    }

    @Test
    void whatTheOldKeySealedStillOpensUntilItIsReSealed() {
        String old = newKey();
        String sealedByOld = util(old, null, null).encrypt("truststore-pass");
        EncryptionUtil rotated = util(old, "p2026a", newKey());

        assertThat(rotated.isCurrent(sealedByOld)).isFalse();
        assertThat(rotated.decrypt(sealedByOld)).isEqualTo("truststore-pass");
        String resealed = rotated.encrypt(rotated.decrypt(sealedByOld));
        assertThat(rotated.isCurrent(resealed)).isTrue();
    }

    /** Once the old key is removed, only what the new key sealed opens: that is what voids the leak. */
    @Test
    void withTheOldKeyGoneOnlyTheNewKeysValuesOpen() {
        String old = newKey();
        String current = newKey();
        String sealedByOld = util(old, null, null).encrypt("x");
        String sealedByNew = util(old, "p2026a", current).encrypt("y");
        EncryptionUtil afterRotation = util(null, "p2026a", current);

        assertThat(afterRotation.decrypt(sealedByNew)).isEqualTo("y");
        assertThatThrownBy(() -> afterRotation.decrypt(sealedByOld)).isInstanceOf(IllegalStateException.class);
    }

    /** No current key configured: the old behaviour, unchanged, for tests and a first boot. */
    @Test
    void withoutACurrentKeyItSealsAsItAlwaysDid() {
        String old = newKey();
        EncryptionUtil util = util(old, null, null);
        String sealed = util.encrypt("v");
        // Untagged: base64 has no ':', so no "k<id>:" prefix (a bare leading 'k' is chance, 1 in 64).
        assertThat(sealed).doesNotContain(":");
        assertThat(util.decrypt(sealed)).isEqualTo("v");
    }

    private static EncryptionUtil required(String currentId, String current) {
        EncryptionUtil util = util(null, currentId, current);
        ReflectionTestUtils.setField(util, "required", true);
        return util;
    }

    /** Where a key is required (every deployed profile), a blank one refuses to boot, naming the variable. */
    @Test
    void aRequiredKeyThatIsBlankRefusesToBootNamingTheVariable() {
        assertThatThrownBy(() -> required(null, null).checkAtStartup())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("PROCESS_ENCRYPTION_KEY");
        assertThatThrownBy(() -> required("p2026a", " ").checkAtStartup())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("PROCESS_ENCRYPTION_KEY");
        assertThatThrownBy(() -> required(null, newKey()).checkAtStartup())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("PROCESS_ENCRYPTION_KEY_ID");
    }

    /** A key that is not 256 bits of base64 is refused at boot, not at the first secret someone saves. */
    @Test
    void aMalformedKeyRefusesToBoot() {
        assertThatThrownBy(() -> required("p2026a", "c2hvcnQ=").checkAtStartup())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("PROCESS_ENCRYPTION_KEY");
        assertThatThrownBy(() -> required("p2026a", "not base64 at all!").checkAtStartup())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("PROCESS_ENCRYPTION_KEY");
    }

    @Test
    void aGoodKeyBootsAndSoDoesNoKeyWhereNoneIsRequired() {
        required("p2026a", newKey()).checkAtStartup();
        util(null, null, null).checkAtStartup();
    }
}

package process.util;

import org.barco.platform.crypto.EncryptionUtil;
import org.barco.platform.crypto.Keyring;

/**
 * platform-commons' keyring encryption under process's current key (MIG-5): values it seals are
 * tagged "k<id>:". A class of its own only because process's own EncryptionUtil shares the name.
 */
final class KeyringSeal {

    private final EncryptionUtil keyring;

    KeyringSeal(String keyId, String base64Key) {
        this.keyring = new EncryptionUtil(Keyring.builder().key(keyId, base64Key).current(keyId).build());
    }

    String encrypt(String plainText) {
        return this.keyring.encrypt(plainText);
    }

    String decrypt(String sealed) {
        return this.keyring.decrypt(sealed);
    }
}

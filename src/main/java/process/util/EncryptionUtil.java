package process.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * @author Nabeel Ahmed
 * */
@Component
public class EncryptionUtil {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;

    /**
     * The key everything was sealed under before MIG-5, in process's untagged format. It was committed
     * as a fallback in git, so it only OPENS what it sealed -- nothing new is sealed under it once a
     * current key is configured -- and it is removed from the environment after EncryptionReseal has
     * re-sealed every value.
     */
    @Value("${lookup.encryption.key:}")
    private String base64Key;

    /** MIG-5: the current key and its id. Values it seals are tagged "k<id>:" (platform-commons' format). */
    @Value("${process.encryption.key-id:}")
    private String currentKeyId;

    @Value("${process.encryption.key:}")
    private String currentKey;

    private volatile KeyringSeal keyring;

    public String encrypt(String plainText) {
        if (this.hasCurrentKey()) {
            return this.keyring().encrypt(plainText);
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, this.secretKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] ivAndCipherText = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, ivAndCipherText, 0, iv.length);
            System.arraycopy(cipherText, 0, ivAndCipherText, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(ivAndCipherText);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to encrypt lookup value: " + ex.getMessage(), ex);
        }
    }

    public String decrypt(String cipherTextBase64) {
        if (this.hasCurrentKey() && this.isCurrent(cipherTextBase64)) {
            try {
                return this.keyring().decrypt(cipherTextBase64);
            } catch (RuntimeException ex) {
                throw new IllegalStateException("Failed to decrypt lookup value: " + ex.getMessage(), ex);
            }
        }
        try {
            byte[] ivAndCipherText = Base64.getDecoder().decode(cipherTextBase64);
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            System.arraycopy(ivAndCipherText, 0, iv, 0, GCM_IV_LENGTH_BYTES);
            int cipherTextLength = ivAndCipherText.length - GCM_IV_LENGTH_BYTES;
            byte[] cipherText = new byte[cipherTextLength];
            System.arraycopy(ivAndCipherText, GCM_IV_LENGTH_BYTES, cipherText, 0, cipherTextLength);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, this.secretKey(), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to decrypt lookup value: " + ex.getMessage(), ex);
        }
    }

    /** Whether a stored value is sealed under the current key, so needs no re-seal. */
    public boolean isCurrent(String sealed) {
        return this.hasCurrentKey() && sealed != null && sealed.startsWith("k" + this.currentKeyId.trim() + ":");
    }

    public boolean hasCurrentKey() {
        return this.currentKeyId != null && !this.currentKeyId.trim().isEmpty()
            && this.currentKey != null && !this.currentKey.trim().isEmpty();
    }

    private KeyringSeal keyring() {
        if (this.keyring == null) {
            synchronized (this) {
                if (this.keyring == null) {
                    this.keyring = new KeyringSeal(this.currentKeyId.trim(), this.currentKey.trim());
                }
            }
        }
        return this.keyring;
    }

    private SecretKey secretKey() {
        if (this.base64Key == null || this.base64Key.trim().isEmpty()) {
            throw new IllegalStateException(
                "LOOKUP_ENCRYPTION_KEY environment variable is not set; cannot encrypt/decrypt lookup values.");
        }
        return new SecretKeySpec(Base64.getDecoder().decode(this.base64Key.trim()), "AES");
    }

}

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
 * Utility use to encrypt/decrypt sensitive lookup values (e.g. credentials) before they're
 * persisted, using AES-256-GCM. The key comes from the LOOKUP_ENCRYPTION_KEY environment
 * variable (a base64-encoded 32-byte key) -- it is never hardcoded or committed. Generate
 * one locally with: openssl rand -base64 32
 * Storage format is base64(IV || ciphertext+tag) -- a fresh random IV per call, since
 * reusing an IV with the same GCM key breaks its security guarantees.
 * @author Nabeel Ahmed
 */
@Component
public class EncryptionUtil {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int GCM_IV_LENGTH_BYTES = 12;

    @Value("${lookup.encryption.key:}")
    private String base64Key;

    /**
     * Method use to encrypt a plain text lookup value
     * @param plainText
     * @return String
     * */
    public String encrypt(String plainText) {
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

    /**
     * Method use to decrypt a previously encrypted lookup value
     * @param cipherTextBase64
     * @return String
     * */
    public String decrypt(String cipherTextBase64) {
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

    /**
     * Method use to build the AES key from LOOKUP_ENCRYPTION_KEY, failing clearly if it's
     * not configured rather than silently falling back to an insecure default.
     * @return SecretKey
     * */
    private SecretKey secretKey() {
        if (this.base64Key == null || this.base64Key.trim().isEmpty()) {
            throw new IllegalStateException(
                "LOOKUP_ENCRYPTION_KEY environment variable is not set; cannot encrypt/decrypt lookup values.");
        }
        return new SecretKeySpec(Base64.getDecoder().decode(this.base64Key.trim()), "AES");
    }

}

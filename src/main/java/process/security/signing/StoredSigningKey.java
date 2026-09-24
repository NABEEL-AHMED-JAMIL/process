package process.security.signing;

import java.sql.Timestamp;

/**
 * One row of identity_signing_key: an RS256 key pair, the private half sealed (MIG-92, P11).
 *
 * @author Nabeel Ahmed
 */
public final class StoredSigningKey {

    public static final String ACTIVE = "active";
    public static final String RETIRED = "retired";

    private final String kid;
    private final String publicKey;
    private final String sealedPrivateKey;
    private final String status;
    private final Timestamp createdAt;
    private final Timestamp retiredAt;

    public StoredSigningKey(String kid, String publicKey, String sealedPrivateKey, String status, Timestamp createdAt,
        Timestamp retiredAt) {
        this.kid = kid;
        this.publicKey = publicKey;
        this.sealedPrivateKey = sealedPrivateKey;
        this.status = status;
        this.createdAt = createdAt;
        this.retiredAt = retiredAt;
    }

    /** The key id tokens carry in their header. */
    public String getKid() { return this.kid; }
    /** The public key, X.509 DER, base64. Not a secret: it is what the JWKS publishes. */
    public String getPublicKey() { return this.publicKey; }
    /** The private key, PKCS#8 DER base64, sealed by EncryptionUtil under the current key. Null once withdrawn. */
    public String getSealedPrivateKey() { return this.sealedPrivateKey; }
    public String getStatus() { return this.status; }
    public Timestamp getCreatedAt() { return this.createdAt; }
    public Timestamp getRetiredAt() { return this.retiredAt; }
}

package process.security.signing;

import process.identity.IdentityInProcess;
import org.barco.platform.security.Jwks;
import org.barco.platform.security.PublicKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.util.EncryptionUtil;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Identity's RS256 signing keys (MIG-92, P11): made here, kept here, and only the public halves ever
 * leave.
 *
 * The first instance to start with no key makes one -- RSA 2048, its id the RFC 7638 thumbprint of the
 * public half -- seals the private half with EncryptionUtil under the current key, and stores it. The
 * database allows one active key, so two instances starting together store one and both use it. No
 * signing secret is handed out through an environment file: the only secret involved is the
 * encryption key process already holds.
 *
 * Other services verify against {@link #jwks()}, published at /internal/jwks. process verifies its own
 * tokens against the same keys, read from the database rather than over HTTP.
 *
 * @author Nabeel Ahmed
 */
@IdentityInProcess
@Component
public class SigningKeys implements PublicKeys {

    private static final Logger logger = LoggerFactory.getLogger(SigningKeys.class);

    /** The least time between two reloads prompted by a key id nobody has: made-up kids cannot drive queries. */
    static final long RELOAD_FLOOR_MILLIS = 5_000;

    /** What a token is signed with. */
    public static final class Active {
        private final String kid;
        private final PrivateKey privateKey;

        Active(String kid, PrivateKey privateKey) {
            this.kid = kid;
            this.privateKey = privateKey;
        }

        public String getKid() { return this.kid; }
        public PrivateKey getPrivateKey() { return this.privateKey; }
    }

    private final SigningKeyStore store;
    private final EncryptionUtil seal;
    private final LongSupplier clock;

    private volatile Active active;
    private volatile Map<String, RSAPublicKey> published = Collections.emptyMap();
    private volatile long loadedAt = Long.MIN_VALUE;

    @Autowired
    public SigningKeys(SigningKeyStore store, EncryptionUtil seal) {
        this(store, seal, System::currentTimeMillis);
    }

    SigningKeys(SigningKeyStore store, EncryptionUtil seal, LongSupplier clock) {
        this.store = store;
        this.seal = seal;
        this.clock = clock;
    }

    /** The key to sign with now, made and stored first if there is none. */
    public Active active() {
        Active held = this.active;
        if (held != null) {
            return held;
        }
        synchronized (this) {
            if (this.active == null) {
                StoredSigningKey stored = this.store.active().orElseGet(this::makeAndStore);
                this.active = new Active(stored.getKid(), this.unseal(stored.getSealedPrivateKey()));
                this.reload();
            }
            return this.active;
        }
    }

    private StoredSigningKey makeAndStore() {
        KeyPair pair = generate();
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
        StoredSigningKey made = new StoredSigningKey(thumbprint(publicKey),
            Base64.getEncoder().encodeToString(publicKey.getEncoded()),
            this.seal.encrypt(Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())),
            StoredSigningKey.ACTIVE, null, null);
        if (this.store.insertActiveIfNone(made)) {
            logger.info("Made RS256 signing key {}; its public half is published at /internal/jwks.", made.getKid());
            return made;
        }
        // Another instance stored one first: use theirs, so every instance signs with one key.
        return this.store.active().orElseThrow(() -> new IllegalStateException("No active signing key, and none could be stored."));
    }

    /** The public key for a kid, for process's own verification; reloads from the database at most every few seconds. */
    @Override
    public PublicKey forKid(String kid) {
        if (kid == null) {
            return null;
        }
        if (this.loadedAt == Long.MIN_VALUE) {
            this.active();
        }
        RSAPublicKey key = this.published.get(kid);
        if (key == null && this.clock.getAsLong() - this.loadedAt >= RELOAD_FLOOR_MILLIS) {
            synchronized (this) {
                if (this.clock.getAsLong() - this.loadedAt >= RELOAD_FLOOR_MILLIS) {
                    this.reload();
                }
            }
            key = this.published.get(kid);
        }
        return key;
    }

    /** The JWKS every other service verifies against: public halves only, active and recently retired. */
    public String jwks() {
        if (this.loadedAt == Long.MIN_VALUE) {
            this.active();
        }
        synchronized (this) {
            this.reload();
        }
        return Jwks.publish(this.published);
    }

    /**
     * Retires the active key and makes a new one. The old key stays published until the tokens it signed
     * have expired, so rotating signs nobody out.
     */
    public synchronized String rotate() {
        Active current = this.active();
        this.store.retire(current.getKid());
        this.active = null;
        return this.active().getKid();
    }

    private void reload() {
        Map<String, RSAPublicKey> keys = new LinkedHashMap<>();
        for (StoredSigningKey key : this.store.published()) {
            keys.put(key.getKid(), publicKey(key.getPublicKey()));
        }
        this.published = Collections.unmodifiableMap(keys);
        this.loadedAt = this.clock.getAsLong();
    }

    private PrivateKey unseal(String sealed) {
        try {
            byte[] der = Base64.getDecoder().decode(this.seal.decrypt(sealed));
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception ex) {
            throw new IllegalStateException("The active signing key cannot be opened with the current encryption key.", ex);
        }
    }

    private static RSAPublicKey publicKey(String base64) {
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (Exception ex) {
            throw new IllegalStateException("A stored signing key's public half is unreadable.", ex);
        }
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception ex) {
            throw new IllegalStateException("RSA is part of every JVM", ex);
        }
    }

    /** RFC 7638: SHA-256 over {"e","kty","n"} in that order, base64url. The same key always has the same id. */
    static String thumbprint(RSAPublicKey key) {
        String canonical = "{\"e\":\"" + b64u(key.getPublicExponent()) + "\",\"kty\":\"RSA\",\"n\":\"" + b64u(key.getModulus()) + "\"}";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is part of every JVM", ex);
        }
    }

    private static String b64u(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            bytes = trimmed;
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}

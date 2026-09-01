package process.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/**
 * Turns the PEM files a broker hands out into the keystore and truststore a Kafka client needs.
 *
 * Every managed Kafka service (Confluent, Aiven, MSK, Redpanda) gives you certificates, and every
 * Kafka client wants a Java keystore -- so somebody has always had to run keytool by hand and get
 * six flags right before a connection profile could be saved. The conversion is short enough to do
 * here, which removes the step entirely.
 *
 * Deliberately pure JDK. The project targets Java 8 and has no BouncyCastle on the classpath, and
 * pulling one in to parse a key format the JDK already reads would be a poor trade. The cost of
 * that choice is PKCS#1: a "BEGIN RSA PRIVATE KEY" file cannot be read without an ASN.1 parser, so
 * it is refused by name with the one-line openssl command that converts it, rather than failing
 * later with something unreadable about an invalid key spec.
 *
 * PKCS12 throughout rather than JKS. JKS is Oracle-proprietary and was superseded as the default
 * store type in Java 9; anything generated here is read back by a client whose store type is set
 * explicitly, so there is no reason to write the legacy format.
 *
 * @author Nabeel Ahmed
 * */
public final class KafkaCertificateUtil {

    /** What a generated store is written as. Callers must set the matching ssl.*.type on the client. */
    public static final String STORE_TYPE = "PKCS12";

    private static final String BEGIN_PKCS1_RSA = "BEGIN RSA PRIVATE KEY";
    private static final String BEGIN_PKCS1_EC = "BEGIN EC PRIVATE KEY";
    private static final String BEGIN_ENCRYPTED = "BEGIN ENCRYPTED PRIVATE KEY";

    /** The algorithms a broker's client key is realistically issued under. Tried in order. */
    private static final String[] KEY_ALGORITHMS = { "RSA", "EC", "DSA" };

    private KafkaCertificateUtil() {}

    /**
     * Reads every certificate in a PEM or DER file.
     *
     * A CA file often holds a whole chain rather than one certificate, so this returns all of them
     * and the caller decides what to trust. CertificateFactory reads both encodings from the same
     * stream, so the file does not have to be identified first.
     */
    public static List<X509Certificate> readCertificates(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("The certificate file is empty.");
        }
        Collection<? extends Certificate> parsed;
        try {
            parsed = CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(bytes));
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                "That file is not a readable X.509 certificate. Expected a PEM file beginning "
                + "\"-----BEGIN CERTIFICATE-----\", or the same certificate in DER form.", ex);
        }
        List<X509Certificate> certificates = new ArrayList<>();
        for (Certificate certificate : parsed) {
            if (certificate instanceof X509Certificate) {
                certificates.add((X509Certificate) certificate);
            }
        }
        if (certificates.isEmpty()) {
            throw new IllegalArgumentException("No X.509 certificate was found in that file.");
        }
        return certificates;
    }

    /**
     * Reads an unencrypted PKCS#8 private key.
     *
     * The three refusals below are all formats a broker will hand somebody, so each names itself
     * and says what to do about it. Letting them fall through to KeyFactory would surface as
     * "InvalidKeySpecException", which tells the person uploading the file nothing at all.
     */
    public static PrivateKey readPrivateKey(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("The private key file is empty.");
        }
        String text = new String(bytes, StandardCharsets.UTF_8);

        if (text.contains(BEGIN_ENCRYPTED)) {
            throw new IllegalArgumentException(
                "That private key is password-protected. Decrypt it first with: "
                + "openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem");
        }
        if (text.contains(BEGIN_PKCS1_RSA) || text.contains(BEGIN_PKCS1_EC)) {
            throw new IllegalArgumentException(
                "That private key is in the older PKCS#1 format, which this server cannot read. "
                + "Convert it with: openssl pkcs8 -topk8 -nocrypt -in key.pem -out key-pkcs8.pem "
                + "and upload the result.");
        }

        byte[] der = looksLikePem(text) ? decodePemBody(text) : bytes;
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        for (String algorithm : KEY_ALGORITHMS) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (Exception ignored) {
                // Tried in turn because a PKCS#8 file does not say which algorithm it holds until
                // it is parsed; only the last failure is worth reporting.
            }
        }
        throw new IllegalArgumentException(
            "That private key could not be read. It must be an unencrypted PKCS#8 key -- a PEM "
            + "file beginning \"-----BEGIN PRIVATE KEY-----\".");
    }

    /**
     * Builds a truststore holding the certificates a client should trust.
     *
     * Only needed for a broker whose certificate is signed by a private CA. A cluster behind a
     * well-known public CA is already covered by the JVM's own truststore, and generating one here
     * would narrow trust rather than widen it.
     */
    public static byte[] buildTruststore(List<X509Certificate> certificates, char[] password) {
        requirePassword(password);
        if (certificates == null || certificates.isEmpty()) {
            throw new IllegalArgumentException("A truststore needs at least one certificate.");
        }
        try {
            KeyStore store = KeyStore.getInstance(STORE_TYPE);
            store.load(null, password);
            int index = 0;
            for (X509Certificate certificate : certificates) {
                // Alias from the subject where there is one: a person opening the generated file
                // with keytool should see which CA each entry is, not "cert-0".
                store.setCertificateEntry(aliasFor(certificate, "ca-" + index), certificate);
                index++;
            }
            return toBytes(store, password);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not build the truststore: " + ex.getMessage(), ex);
        }
    }

    /**
     * Builds a keystore holding the client's own certificate and key, for mutual TLS.
     *
     * The chain is stored in the order given, which must run leaf-first -- that is the order both
     * TLS and every CA's download bundle use, so the caller almost always has it already.
     */
    public static byte[] buildKeystore(PrivateKey privateKey, List<X509Certificate> chain, char[] password) {
        requirePassword(password);
        if (privateKey == null) {
            throw new IllegalArgumentException("A keystore needs a private key.");
        }
        if (chain == null || chain.isEmpty()) {
            throw new IllegalArgumentException("A keystore needs the client certificate the key belongs to.");
        }
        try {
            KeyStore store = KeyStore.getInstance(STORE_TYPE);
            store.load(null, password);
            store.setKeyEntry(aliasFor(chain.get(0), "client"), privateKey, password,
                chain.toArray(new Certificate[0]));
            return toBytes(store, password);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not build the keystore: " + ex.getMessage(), ex);
        }
    }

    /**
     * Whether a key and a certificate belong together.
     *
     * Uploading the wrong pair is an easy mistake and the resulting keystore fails at the TLS
     * handshake with nothing to say why, hours later and on a different machine. Comparing the
     * public keys catches it while the person is still looking at the upload form.
     */
    public static boolean keyMatchesCertificate(PrivateKey privateKey, X509Certificate certificate) {
        if (privateKey == null || certificate == null) {
            return false;
        }
        try {
            // Signing something and verifying it against the certificate, rather than asking a
            // keystore to accept the pair: whether a store validates the match is an
            // implementation detail that varies by provider, and this answers the question
            // directly whatever the algorithm turns out to be.
            byte[] probe = "kafka-certificate-pairing-probe".getBytes(StandardCharsets.UTF_8);
            String algorithm = signatureAlgorithmFor(privateKey.getAlgorithm());
            if (algorithm == null) {
                return false;
            }
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(privateKey);
            signer.update(probe);
            byte[] signature = signer.sign();

            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(probe);
            return verifier.verify(signature);
        } catch (Exception ex) {
            return false;
        }
    }

    private static String signatureAlgorithmFor(String keyAlgorithm) {
        if ("RSA".equalsIgnoreCase(keyAlgorithm)) {
            return "SHA256withRSA";
        }
        if ("EC".equalsIgnoreCase(keyAlgorithm) || "ECDSA".equalsIgnoreCase(keyAlgorithm)) {
            return "SHA256withECDSA";
        }
        if ("DSA".equalsIgnoreCase(keyAlgorithm)) {
            return "SHA256withDSA";
        }
        return null;
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("A PKCS12 store cannot be written without a password.");
        }
    }

    private static boolean looksLikePem(String text) {
        return text.contains("-----BEGIN");
    }

    private static byte[] decodePemBody(String text) {
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("-----")) {
                continue;
            }
            body.append(trimmed);
        }
        try {
            return Base64.getDecoder().decode(body.toString());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                "That PEM file's contents are not valid base64 and could not be decoded.", ex);
        }
    }

    /** Keystore aliases are lower-cased by some tools, so they are normalised here to match. */
    private static String aliasFor(X509Certificate certificate, String fallback) {
        String name = certificate.getSubjectX500Principal() == null
            ? null : certificate.getSubjectX500Principal().getName();
        if (name == null || name.trim().isEmpty()) {
            return fallback;
        }
        for (String part : name.split(",")) {
            String trimmed = part.trim();
            if (trimmed.regionMatches(true, 0, "CN=", 0, 3) && trimmed.length() > 3) {
                return trimmed.substring(3).trim().toLowerCase();
            }
        }
        return fallback;
    }

    private static byte[] toBytes(KeyStore store, char[] password) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.store(out, password);
        return out.toByteArray();
    }

}

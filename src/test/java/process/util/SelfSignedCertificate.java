package process.util;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.io.ByteArrayInputStream;
import java.util.Date;
import java.util.TimeZone;
import java.text.SimpleDateFormat;

/**
 * Makes a throwaway self-signed X.509 certificate for tests, by encoding one.
 *
 * There is no public JDK API for issuing a certificate, and the internal one both moved package
 * between Java 8 and 9 and is module-encapsulated on the Java 17 that actually builds this
 * project -- reflection at it silently skipped every test that needed a certificate, which is
 * worse than having none. The alternatives were a subprocess to keytool or a committed key pair;
 * encoding a v1 certificate by hand is about eighty lines and avoids both. Nothing that resembles
 * a credential ends up in the repository, and the tests run everywhere.
 *
 * v1 deliberately: no extensions are needed to exercise parsing, truststore entries, keystore
 * entries or key/certificate pairing, and the version field is the only part of v3 that would
 * add encoding work.
 *
 * @author Nabeel Ahmed
 * */
public final class SelfSignedCertificate {

    /** SHA256withRSA, as { 1.2.840.113549.1.1.11 }. */
    private static final byte[] SHA256_WITH_RSA =
        { 0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B };

    /** id-at-commonName, as { 2.5.4.3 }. */
    private static final byte[] COMMON_NAME = { 0x06, 0x03, 0x55, 0x04, 0x03 };

    private final X509Certificate certificate;
    private final KeyPair keyPair;

    private SelfSignedCertificate(X509Certificate certificate, KeyPair keyPair) {
        this.certificate = certificate;
        this.keyPair = keyPair;
    }

    public X509Certificate certificate() { return this.certificate; }

    public PrivateKey privateKey() { return this.keyPair.getPrivate(); }

    public static SelfSignedCertificate issue(String commonName) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();

        byte[] algorithm = derSequence(SHA256_WITH_RSA, new byte[] { 0x05, 0x00 });
        byte[] name = derSequence(derSet(derSequence(COMMON_NAME, derUtf8(commonName))));
        byte[] validity = derSequence(
            derUtcTime(new Date(System.currentTimeMillis() - 60_000L)),
            derUtcTime(new Date(System.currentTimeMillis() + 86_400_000L)));

        byte[] tbs = derSequence(
            derInteger(BigInteger.valueOf(System.nanoTime()).abs().add(BigInteger.ONE)),
            algorithm,
            name,
            validity,
            name,
            // An RSA public key already encodes as a SubjectPublicKeyInfo, so it drops straight in.
            pair.getPublic().getEncoded());

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(tbs);
        byte[] signature = signer.sign();

        byte[] encoded = derSequence(tbs, algorithm, derBitString(signature));
        X509Certificate issued = (X509Certificate) CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(encoded));
        return new SelfSignedCertificate(issued, pair);
    }

    // ---- a very small DER encoder ---------------------------------------------------------

    private static byte[] derSequence(byte[]... parts) { return tagged(0x30, concat(parts)); }

    private static byte[] derSet(byte[]... parts) { return tagged(0x31, concat(parts)); }

    private static byte[] derUtf8(String value) {
        return tagged(0x0C, value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] derInteger(BigInteger value) { return tagged(0x02, value.toByteArray()); }

    private static byte[] derBitString(byte[] value) {
        // The leading zero is the count of unused bits in the final octet, always none here.
        return tagged(0x03, concat(new byte[] { 0x00 }, value));
    }

    private static byte[] derUtcTime(Date when) {
        SimpleDateFormat format = new SimpleDateFormat("yyMMddHHmmss'Z'");
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tagged(0x17, format.format(when).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] tagged(int tag, byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        if (body.length < 0x80) {
            out.write(body.length);
        } else {
            byte[] length = BigInteger.valueOf(body.length).toByteArray();
            int offset = length[0] == 0 ? 1 : 0;
            out.write(0x80 | (length.length - offset));
            out.write(length, offset, length.length - offset);
        }
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

}

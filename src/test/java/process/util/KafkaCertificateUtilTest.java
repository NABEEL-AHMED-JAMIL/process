package process.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Nothing here embeds key material. Every key and certificate the tests need is generated in
 * memory when the test runs, so the repository never carries something that looks like a
 * credential even though it protects nothing.
 */
class KafkaCertificateUtilTest {

    private static final char[] PASSWORD = "unit-test-store".toCharArray();

    @Test
    void readsAnUnencryptedPkcs8KeyAsPem() throws Exception {
        PrivateKey original = generateKeyPair().getPrivate();
        PrivateKey parsed = KafkaCertificateUtil.readPrivateKey(asPem(original).getBytes(StandardCharsets.UTF_8));
        assertThat(parsed.getAlgorithm()).isEqualTo(original.getAlgorithm());
        assertThat(parsed.getEncoded()).isEqualTo(original.getEncoded());
    }

    @Test
    void readsTheSameKeyInRawDer() throws Exception {
        PrivateKey original = generateKeyPair().getPrivate();
        PrivateKey parsed = KafkaCertificateUtil.readPrivateKey(original.getEncoded());
        assertThat(parsed.getEncoded()).isEqualTo(original.getEncoded());
    }

    /**
     * The refusal that matters most: PKCS#1 is what openssl produces by default, so somebody will
     * upload one, and the message has to be the thing that unblocks them.
     */
    @Test
    void namesPkcs1AndGivesTheConversionCommand() {
        byte[] pkcs1 = ("-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n")
            .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> KafkaCertificateUtil.readPrivateKey(pkcs1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("PKCS#1")
            .hasMessageContaining("openssl pkcs8 -topk8 -nocrypt");
    }

    @Test
    void namesAnEncryptedKeyRatherThanFailingOnTheKeySpec() {
        byte[] encrypted = ("-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----\n")
            .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> KafkaCertificateUtil.readPrivateKey(encrypted))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("password-protected");
    }

    @Test
    void refusesAnEmptyOrUnreadableKey() {
        assertThatThrownBy(() -> KafkaCertificateUtil.readPrivateKey(new byte[0]))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KafkaCertificateUtil.readPrivateKey("not a key at all".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("PKCS#8");
    }

    @Test
    void refusesSomethingThatIsNotACertificate() {
        assertThatThrownBy(() -> KafkaCertificateUtil.readCertificates("hello".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("BEGIN CERTIFICATE");
        assertThatThrownBy(() -> KafkaCertificateUtil.readCertificates(new byte[0]))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsAPemCertificateBackOut() throws Exception {
        X509Certificate certificate = issued().certificate();
        List<X509Certificate> parsed = KafkaCertificateUtil.readCertificates(
            asPem(certificate).getBytes(StandardCharsets.UTF_8));
        assertThat(parsed).hasSize(1);
        assertThat(parsed.get(0).getSubjectX500Principal()).isEqualTo(certificate.getSubjectX500Principal());
    }

    @Test
    void buildsATruststoreThatContainsTheCertificate() throws Exception {
        byte[] store = KafkaCertificateUtil.buildTruststore(
            Collections.singletonList(issued().certificate()), PASSWORD);
        KeyStore readBack = KeyStore.getInstance(KafkaCertificateUtil.STORE_TYPE);
        readBack.load(new ByteArrayInputStream(store), PASSWORD);
        assertThat(Collections.list(readBack.aliases())).hasSize(1);
    }

    @Test
    void buildsAKeystoreHoldingTheKeyAndItsCertificate() throws Exception {
        SelfSignedCertificate pair = issued();
        byte[] store = KafkaCertificateUtil.buildKeystore(
            pair.privateKey(), Collections.singletonList(pair.certificate()), PASSWORD);
        KeyStore readBack = KeyStore.getInstance(KafkaCertificateUtil.STORE_TYPE);
        readBack.load(new ByteArrayInputStream(store), PASSWORD);
        String alias = Collections.list(readBack.aliases()).get(0);
        assertThat(readBack.isKeyEntry(alias)).isTrue();
        assertThat(readBack.getKey(alias, PASSWORD)).isNotNull();
    }

    @Test
    void spotsAKeyThatDoesNotBelongToTheCertificate() throws Exception {
        SelfSignedCertificate pair = issued();
        assertThat(KafkaCertificateUtil.keyMatchesCertificate(pair.privateKey(), pair.certificate())).isTrue();
        assertThat(KafkaCertificateUtil.keyMatchesCertificate(
            issued().privateKey(), pair.certificate())).isFalse();
    }

    @Test
    void refusesToWriteAStoreWithNoPassword() throws Exception {
        X509Certificate certificate = issued().certificate();
        assertThatThrownBy(() -> KafkaCertificateUtil.buildTruststore(
                Collections.singletonList(certificate), new char[0]))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("password");
    }

    @Test
    void refusesAnEmptyTruststoreOrAKeystoreWithNoCertificate() throws Exception {
        assertThatThrownBy(() -> KafkaCertificateUtil.buildTruststore(Collections.<X509Certificate>emptyList(), PASSWORD))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KafkaCertificateUtil.buildKeystore(
                generateKeyPair().getPrivate(), Collections.<X509Certificate>emptyList(), PASSWORD))
            .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- helpers -------------------------------------------------------------------------

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static SelfSignedCertificate issued() throws Exception {
        return SelfSignedCertificate.issue("kafka-test");
    }

    private static String asPem(PrivateKey key) {
        return wrap("PRIVATE KEY", key.getEncoded());
    }

    private static String asPem(X509Certificate certificate) throws Exception {
        return wrap("CERTIFICATE", certificate.getEncoded());
    }

    private static String wrap(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }

}

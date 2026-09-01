package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;
import process.model.dto.KafkaSecretDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ResponseDto;
import process.model.enums.KafkaSecretKind;
import process.model.repository.AppUserRepository;
import process.model.service.KafkaSecretService;
import process.security.TenantContext;
import process.util.EncryptionUtil;
import process.util.KafkaCertificateUtil;
import process.util.KafkaSecretPath;
import process.util.SelfSignedCertificate;

import javax.crypto.KeyGenerator;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static process.util.ProcessUtil.SUCCESS;

/**
 * The certificate workflow end to end, with real crypto and a bucket in memory.
 *
 * The unit tests either side of this one check the pieces: that a PEM parses, that a path cannot
 * be escaped, that ownership is refused. What none of them could show is whether the thing the
 * workflow actually produces is usable. Driving it end to end is what surfaced the store type
 * never being declared -- generated stores are PKCS12 while the Kafka client defaults to JKS --
 * a mismatch the running JDK happens to paper over today and would not on a Java 8 runtime.
 *
 * So this drives the real service, the real certificate utility and the real encryption, and then
 * opens the resulting store the way a Kafka client would: same password, same declared type. If it
 * opens and holds what it should, the feature works.
 */
class KafkaCertificateWorkflowTest {

    private static final Long UPLOADER = 1248L;
    private static final Long TENANT = 5L;

    /** The bucket, as a map. Enough to be the storage layer for a workflow that only puts and gets. */
    private final Map<String, byte[]> objects = new HashMap<>();

    private KafkaSecretService service;
    private EncryptionUtil encryptionUtil;

    @BeforeEach
    void setUp() throws Exception {
        process.model.service.StorageBrowserService storage =
            mock(process.model.service.StorageBrowserService.class);
        doAnswer(this::rememberObject).when(storage)
            .uploadForWorkflow(anyString(), anyString(), any(), anyLong(), anyString());
        when(storage.readForWorkflow(anyString(), anyString())).thenAnswer(call -> {
            byte[] stored = this.objects.get(call.getArgument(1));
            return stored == null ? null : new ObjectContentDto(
                new ByteArrayInputStream(stored), "application/octet-stream", stored.length, "file");
        });

        this.encryptionUtil = new EncryptionUtil();
        // A key made here and thrown away with the JVM, so nothing credential-shaped is committed.
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        ReflectionTestUtils.setField(this.encryptionUtil, "base64Key",
            Base64.getEncoder().encodeToString(generator.generateKey().getEncoded()));

        AppUserRepository users = mock(AppUserRepository.class);
        this.service = new KafkaSecretServiceImpl(storage, users, this.encryptionUtil);
        ReflectionTestUtils.setField(this.service, "maxFileSizeKb", 512);

        TenantContext.set(TENANT, "TENANT_ADMIN", UPLOADER, "admin@tenant.example");
    }

    private Object rememberObject(InvocationOnMock call) throws Exception {
        java.io.InputStream in = call.getArgument(2);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            out.write(chunk, 0, read);
        }
        this.objects.put(call.getArgument(1), out.toByteArray());
        return null;
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private MultipartFile pem(String fileName, String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der);
        String text = "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
        return new MockMultipartFile("file", fileName, "application/x-pem-file",
            text.getBytes(StandardCharsets.UTF_8));
    }

    private KafkaSecretDto upload(MultipartFile file, KafkaSecretKind kind) throws Exception {
        ResponseDto response = this.service.uploadSecret(file, kind);
        assertThat(response.getStatus()).isEqualTo(SUCCESS);
        return (KafkaSecretDto) response.getData();
    }

    // ---- the whole journey -----------------------------------------------------------------

    /**
     * Steps 3 to 8 of the workflow: upload, validate, decide, generate, store, describe. The
     * assertion that matters is the last one -- the store opens.
     */
    @Test
    void aCaCertificateBecomesATruststoreThatActuallyOpens() throws Exception {
        SelfSignedCertificate ca = SelfSignedCertificate.issue("Test Root CA");

        KafkaSecretDto uploaded = this.upload(
            this.pem("ca.pem", "CERTIFICATE", ca.certificate().getEncoded()), KafkaSecretKind.CA_CERTIFICATE);

        // Where the server put it, not where a caller asked for it.
        assertThat(uploaded.getObjectKey())
            .startsWith("kafka-secrets/" + UPLOADER + "/")
            .endsWith("/ca.pem");
        assertThat(uploaded.getBucket()).isEqualTo(KafkaSecretService.SECRET_BUCKET);
        // What the person uploading needs in order to tell production from staging.
        assertThat(uploaded.getSubject()).isEqualTo("Test Root CA");
        assertThat(uploaded.getIssuer()).isEqualTo("Test Root CA");
        assertThat(uploaded.getExpired()).isFalse();

        ResponseDto built = this.service.generateTruststore(
            Collections.singletonList(uploaded.getObjectKey()));
        assertThat(built.getStatus()).isEqualTo(SUCCESS);
        KafkaSecretDto truststore = (KafkaSecretDto) built.getData();

        // Beside the certificate it came from, so a rotation can retire the pair together, and
        // under a name of its own -- see aSecondStoreDoesNotOverwriteTheFirst.
        assertThat(truststore.getObjectKey())
            .startsWith(KafkaSecretPath.parse(uploaded.getObjectKey()).prefix() + "truststore-")
            .endsWith(".p12");

        // The password never left the server in the clear, and it opens the store.
        assertThat(truststore.getStorePasswordEnc()).isNotNull();
        char[] password = this.encryptionUtil.decrypt(truststore.getStorePasswordEnc()).toCharArray();

        KeyStore opened = KeyStore.getInstance(KafkaCertificateUtil.STORE_TYPE);
        opened.load(new ByteArrayInputStream(this.objects.get(truststore.getObjectKey())), password);
        assertThat(Collections.list(opened.aliases())).hasSize(1);
        String alias = Collections.list(opened.aliases()).get(0);
        assertThat(opened.isCertificateEntry(alias)).isTrue();
        assertThat(((X509Certificate) opened.getCertificate(alias)).getSubjectX500Principal())
            .isEqualTo(ca.certificate().getSubjectX500Principal());
    }

    @Test
    void aClientCertificateAndItsKeyBecomeAKeystoreThatActuallyOpens() throws Exception {
        SelfSignedCertificate client = SelfSignedCertificate.issue("kafka-client");

        KafkaSecretDto certificate = this.upload(
            this.pem("client.crt", "CERTIFICATE", client.certificate().getEncoded()),
            KafkaSecretKind.CLIENT_CERTIFICATE);
        KafkaSecretDto key = this.upload(
            this.pem("client.key", "PRIVATE KEY", client.privateKey().getEncoded()),
            KafkaSecretKind.CLIENT_PRIVATE_KEY);

        ResponseDto built = this.service.generateKeystore(
            certificate.getObjectKey(), key.getObjectKey());
        assertThat(built.getStatus()).isEqualTo(SUCCESS);
        KafkaSecretDto keystore = (KafkaSecretDto) built.getData();

        char[] password = this.encryptionUtil.decrypt(keystore.getStorePasswordEnc()).toCharArray();
        KeyStore opened = KeyStore.getInstance(KafkaCertificateUtil.STORE_TYPE);
        opened.load(new ByteArrayInputStream(this.objects.get(keystore.getObjectKey())), password);

        String alias = Collections.list(opened.aliases()).get(0);
        assertThat(opened.isKeyEntry(alias)).isTrue();
        assertThat(opened.getKey(alias, password)).isNotNull();
        assertThat(opened.getCertificateChain(alias)).hasSize(1);
    }

    /** A chain split across two downloads is ordinary, and both ends must end up trusted. */
    @Test
    void aChainSplitAcrossTwoFilesBecomesOneTruststore() throws Exception {
        SelfSignedCertificate root = SelfSignedCertificate.issue("Root CA");
        SelfSignedCertificate intermediate = SelfSignedCertificate.issue("Intermediate CA");

        KafkaSecretDto first = this.upload(
            this.pem("root.pem", "CERTIFICATE", root.certificate().getEncoded()), KafkaSecretKind.CA_CERTIFICATE);
        KafkaSecretDto second = this.upload(
            this.pem("intermediate.pem", "CERTIFICATE", intermediate.certificate().getEncoded()),
            KafkaSecretKind.CA_CERTIFICATE);

        ResponseDto built = this.service.generateTruststore(
            java.util.Arrays.asList(first.getObjectKey(), second.getObjectKey()));
        KafkaSecretDto truststore = (KafkaSecretDto) built.getData();

        char[] password = this.encryptionUtil.decrypt(truststore.getStorePasswordEnc()).toCharArray();
        KeyStore opened = KeyStore.getInstance(KafkaCertificateUtil.STORE_TYPE);
        opened.load(new ByteArrayInputStream(this.objects.get(truststore.getObjectKey())), password);
        assertThat(Collections.list(opened.aliases())).hasSize(2);
    }

    /**
     * Two stores from one certificate, and the first still opens.
     *
     * Building a second truststore from a CA already in use is ordinary -- a chain gains an
     * intermediate, or a second profile is set up against the same broker. Each store gets its own
     * random password, kept encrypted on whichever profile was saved against it, so a shared name
     * left the earlier profile pointing at bytes its password could no longer open. Nothing
     * reports that: the profile saves, and the connection fails at its next handshake.
     */
    @Test
    void aSecondStoreDoesNotOverwriteTheFirst() throws Exception {
        SelfSignedCertificate ca = SelfSignedCertificate.issue("Test Root CA");
        KafkaSecretDto uploaded = this.upload(
            this.pem("ca.pem", "CERTIFICATE", ca.certificate().getEncoded()), KafkaSecretKind.CA_CERTIFICATE);

        KafkaSecretDto first = (KafkaSecretDto) this.service
            .generateTruststore(Collections.singletonList(uploaded.getObjectKey())).getData();
        KafkaSecretDto second = (KafkaSecretDto) this.service
            .generateTruststore(Collections.singletonList(uploaded.getObjectKey())).getData();

        assertThat(second.getObjectKey()).isNotEqualTo(first.getObjectKey());
        // Both are still in the folder of the certificate they were built from.
        String prefix = KafkaSecretPath.parse(uploaded.getObjectKey()).prefix();
        assertThat(first.getObjectKey()).startsWith(prefix);
        assertThat(second.getObjectKey()).startsWith(prefix);

        // The assertion that matters: the older profile's store still opens with its own password.
        KeyStore opened = KeyStore.getInstance(KafkaCertificateUtil.STORE_TYPE);
        opened.load(new ByteArrayInputStream(this.objects.get(first.getObjectKey())),
            this.encryptionUtil.decrypt(first.getStorePasswordEnc()).toCharArray());
        assertThat(Collections.list(opened.aliases())).hasSize(1);
    }

    // ---- the refusals somebody will actually hit --------------------------------------------

    /** openssl produces PKCS#1 by default, so this is the message that unblocks people. */
    @Test
    void aPkcs1KeyIsRefusedWithTheCommandThatConvertsIt() throws Exception {
        MultipartFile pkcs1 = new MockMultipartFile("file", "client.key", "application/x-pem-file",
            "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n"
                .getBytes(StandardCharsets.UTF_8));

        ResponseDto response = this.service.uploadSecret(pkcs1, KafkaSecretKind.CLIENT_PRIVATE_KEY);

        assertThat(response.getStatus()).isNotEqualTo(SUCCESS);
        assertThat(response.getMessage()).contains("PKCS#1").contains("openssl pkcs8 -topk8 -nocrypt");
        // Nothing unreadable was left in the bucket.
        assertThat(this.objects).isEmpty();
    }

    /** Uploading the wrong half of a pair is easy, and the handshake would not say so. */
    @Test
    void aKeyFromADifferentCertificateIsRefusedBeforeAnyStoreIsBuilt() throws Exception {
        SelfSignedCertificate client = SelfSignedCertificate.issue("kafka-client");
        SelfSignedCertificate somebodyElse = SelfSignedCertificate.issue("other-client");

        KafkaSecretDto certificate = this.upload(
            this.pem("client.crt", "CERTIFICATE", client.certificate().getEncoded()),
            KafkaSecretKind.CLIENT_CERTIFICATE);
        KafkaSecretDto wrongKey = this.upload(
            this.pem("other.key", "PRIVATE KEY", somebodyElse.privateKey().getEncoded()),
            KafkaSecretKind.CLIENT_PRIVATE_KEY);

        ResponseDto response = this.service.generateKeystore(
            certificate.getObjectKey(), wrongKey.getObjectKey());

        assertThat(response.getStatus()).isNotEqualTo(SUCCESS);
        assertThat(response.getMessage()).contains("does not belong to that certificate");
        assertThat(this.objects.keySet()).noneMatch(key -> key.contains("keystore-"));
    }

    @Test
    void aFileThatIsNotACertificateIsRefusedRatherThanStored() throws Exception {
        MultipartFile nonsense = new MockMultipartFile("file", "ca.pem", "text/plain",
            "this is not a certificate".getBytes(StandardCharsets.UTF_8));

        ResponseDto response = this.service.uploadSecret(nonsense, KafkaSecretKind.CA_CERTIFICATE);

        assertThat(response.getStatus()).isNotEqualTo(SUCCESS);
        assertThat(this.objects).isEmpty();
    }

    /** Someone else's upload cannot be turned into a store, however it is named. */
    @Test
    void aTruststoreCannotBeBuiltFromAnotherUsersCertificate() throws Exception {
        SelfSignedCertificate ca = SelfSignedCertificate.issue("Test Root CA");
        KafkaSecretDto uploaded = this.upload(
            this.pem("ca.pem", "CERTIFICATE", ca.certificate().getEncoded()), KafkaSecretKind.CA_CERTIFICATE);

        TenantContext.set(TENANT, "TENANT_USER", 99L, "someone@tenant.example");
        ResponseDto response = this.service.generateTruststore(
            Collections.singletonList(uploaded.getObjectKey()));

        assertThat(response.getStatus()).isNotEqualTo(SUCCESS);
        assertThat(response.getMessage()).contains("could not be found");
    }

}

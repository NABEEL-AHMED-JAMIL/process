package process.e2e;

import com.google.gson.Gson;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import process.model.dto.KafkaConnectionProfileDto;
import process.model.dto.ObjectContentDto;
import process.model.enums.KafkaSecretKind;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.Tenant;
import process.model.service.KafkaSecretService;
import process.model.service.impl.StorageBrowserServiceImpl;
import process.util.SelfSignedCertificate;

import javax.crypto.KeyGenerator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kafka certificates and connection profiles, driven over real HTTP.
 *
 * The unit tests around KafkaSecretServiceImpl set a TenantContext by hand and call the service.
 * That proves the ownership rule inside canUseObject and nothing about who reaches it: the two
 * controllers here are annotated TENANT_ADMIN, the context they authorise against is built by the
 * JWT filter from a token, and a per-object rule is only worth as much as the identity it is
 * handed. So every call below arrives as a request, carrying a real token, for a user this test
 * has just created -- which is the only way to find out that one company's administrator cannot
 * finish another company's certificate workflow.
 *
 * Two things stand in for infrastructure, and neither is on the path any of these assertions
 * turn on:
 *
 * The object store. etl-bucket resolves through a storage_connection row that points at a MinIO
 * the test JVM cannot reach, and -- more to the point -- an object written to a real bucket is
 * not covered by the transaction that rolls this test back, so a suite that uploads certificates
 * would leave a folder of them behind on every run. The bucket is a map here, on the same terms
 * the harness mocks LibreOffice. Everything that decides an outcome is real: the filter chain,
 * the @PreAuthorize checks, the certificate parsing, the store building, the encryption and the
 * profile row in the database.
 *
 * The encryption key. The e2e profile defines no lookup.encryption.key, and building a store
 * encrypts its password, so without one every generate call is a 500 that says nothing. One is
 * generated per run and thrown away with the JVM rather than written down.
 *
 * @author Nabeel Ahmed
 * */
class KafkaSecretE2EIT extends E2ESupport {

    /** The shape the requirement names: kafka-secrets/{userid}/{uuid}/{date}/anyfile. */
    private static final String KEY_SHAPE = "kafka-secrets/%d/[0-9a-f-]{36}/\\d{4}-\\d{2}-\\d{2}/%s";

    private static final String E2E_ENCRYPTION_KEY = freshAesKey();

    @DynamicPropertySource
    static void encryptionKey(DynamicPropertyRegistry registry) {
        registry.add("lookup.encryption.key", () -> E2E_ENCRYPTION_KEY);
    }

    @MockBean private StorageBrowserServiceImpl storageBrowserService;

    /** The bucket. Enough to be the storage layer for a workflow that only puts and gets. */
    private final Map<String, byte[]> objects = new LinkedHashMap<>();

    @BeforeEach
    void standInForTheObjectStore() {
        this.objects.clear();
        doAnswer(this::rememberObject).when(this.storageBrowserService)
            .uploadForWorkflow(anyString(), anyString(), any(InputStream.class), anyLong(), anyString());
        when(this.storageBrowserService.readForWorkflow(anyString(), anyString())).thenAnswer(call -> {
            byte[] stored = this.objects.get(call.getArgument(1));
            return stored == null ? null : new ObjectContentDto(
                new ByteArrayInputStream(stored), "application/octet-stream", stored.length, "file");
        });
    }

    // ---- the journey a person actually makes -------------------------------------------------

    /**
     * The key is the whole of the ownership rule -- every later request is authorised by reading
     * the user id back out of it -- so it has to be the server's own layout, under the id of
     * whoever sent the request, and not something the caller had any say in.
     */
    @Test
    void aTenantAdminUploadingACaCertificateGetsBackAKeyUnderItsOwnUserId() throws Exception {
        Tenant tenant = this.newTenant("kafka-secret");
        AppUser admin = this.newUser(UserRole.TENANT_ADMIN, tenant);
        SelfSignedCertificate ca = SelfSignedCertificate.issue("Production Root CA");

        String body = this.mvc.perform(this.uploadAs(admin,
                this.pem("ca.pem", "CERTIFICATE", ca.certificate().getEncoded()),
                KafkaSecretKind.CA_CERTIFICATE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.bucket").value(KafkaSecretService.SECRET_BUCKET))
            // What the console shows so somebody can tell production from staging without keytool.
            .andExpect(jsonPath("$.data.subject").value("Production Root CA"))
            .andExpect(jsonPath("$.data.issuer").value("Production Root CA"))
            .andExpect(jsonPath("$.data.expired").value(false))
            .andReturn().getResponse().getContentAsString();

        assertThat(this.text(body, "$.data.objectKey"))
            .matches(String.format(KEY_SHAPE, admin.getAppUserId(), "ca\\.pem"));
    }

    /**
     * The store lands in the certificate's own folder, so the pair can be recognised as one
     * rotation later and retired together, and under a name of its own so a second generation
     * cannot write over a store an existing profile still holds the password for.
     */
    @Test
    void aTruststoreIsBuiltBesideTheCertificateItCameFrom() throws Exception {
        Tenant tenant = this.newTenant("kafka-secret");
        AppUser admin = this.newUser(UserRole.TENANT_ADMIN, tenant);
        String certificateKey = this.uploadCa(admin, "Production Root CA", "ca.pem");

        String body = this.mvc.perform(this.postAs(admin, "/kafkaSecret.json/generateTruststore",
                new Gson().toJson(Collections.singletonList(certificateKey))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.kind").value("TRUSTSTORE"))
            .andReturn().getResponse().getContentAsString();

        String truststoreKey = this.text(body, "$.data.objectKey");
        assertThat(truststoreKey)
            .matches(String.format(KEY_SHAPE, admin.getAppUserId(), "truststore-[0-9a-f]+\\.p12"))
            .startsWith(this.folderOf(certificateKey))
            .isNotEqualTo(certificateKey);
        // The password for it never left the server in the clear.
        assertThat(this.text(body, "$.data.storePasswordEnc")).isNotEmpty();
        assertThat(body).doesNotContain("\"storePassword\"");
    }

    /**
     * A saved profile is read back by the screen that lists them, and a store password that came
     * back with it would be a credential handed to every operator who can open that screen. The
     * flags are the whole of what a form needs: they say a password is set without saying what.
     */
    @Test
    void aSavedProfileReportsItsPasswordsAsConfiguredWithoutReturningThem() throws Exception {
        Tenant tenant = this.newTenant("kafka-secret");
        AppUser admin = this.newUser(UserRole.TENANT_ADMIN, tenant);
        String certificateKey = this.uploadCa(admin, "Production Root CA", "ca.pem");
        String truststore = this.generateTruststore(admin, certificateKey);

        String profileName = "e2e-profile-" + this.unique();
        KafkaConnectionProfileDto dto = new KafkaConnectionProfileDto();
        dto.setProfileName(profileName);
        dto.setBootstrapServers("broker.kafka.example:9093");
        dto.setSecurityProtocol("SSL");
        dto.setSslTruststoreBucket(KafkaSecretService.SECRET_BUCKET);
        dto.setSslTruststoreLocation(this.text(truststore, "$.data.objectKey"));
        // Handed back exactly as the server gave it: the store was built in an earlier request and
        // its password must not travel in the clear between the two.
        dto.setSslTruststorePasswordEnc(this.text(truststore, "$.data.storePasswordEnc"));

        this.mvc.perform(this.postAs(admin, "/kafkaConnectionProfile.json/addProfile",
                new Gson().toJson(dto)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.data.sslTruststorePasswordConfigured").value(true))
            .andExpect(jsonPath("$.data.sslTruststoreLocation").value(dto.getSslTruststoreLocation()))
            .andExpect(jsonPath("$.data.sslTruststorePassword").doesNotExist())
            .andExpect(jsonPath("$.data.sslTruststorePasswordEnc").doesNotExist())
            .andExpect(jsonPath("$.data.sslKeystorePassword").doesNotExist())
            .andExpect(jsonPath("$.data.sslKeystorePasswordEnc").doesNotExist())
            .andExpect(jsonPath("$.data.saslPassword").doesNotExist());

        // And again on the way out through the screen that lists them, which is the response an
        // operator's browser actually holds.
        String listed = this.mvc.perform(this.getAs(admin, "/kafkaConnectionProfile.json/fetchAllProfiles"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(listed).contains(profileName);
        assertThat(listed).doesNotContain(dto.getSslTruststorePasswordEnc());
        assertThat(listed).doesNotContain("\"sslTruststorePassword\"");
        assertThat(listed).doesNotContain("\"sslTruststorePasswordEnc\"");
        assertThat(listed).doesNotContain("\"saslPassword\"");
    }

    // ---- one company's administrator, another company's certificate --------------------------

    /**
     * An administrator's authority runs over the people it manages, not over another company's.
     * Both are TENANT_ADMIN, so the annotation lets both in and only the per-object rule separates
     * them -- which is exactly the rule a service-level test cannot check.
     */
    @Test
    void anotherCompanysAdminCannotBuildAStoreFromThisCompanysCertificate() throws Exception {
        AppUser admin = this.newUser(UserRole.TENANT_ADMIN, this.newTenant("kafka-secret-owner"));
        AppUser outsider = this.newUser(UserRole.TENANT_ADMIN, this.newTenant("kafka-secret-other"));
        String certificateKey = this.uploadCa(admin, "Production Root CA", "ca.pem");

        this.mvc.perform(this.postAs(outsider, "/kafkaSecret.json/generateTruststore",
                new Gson().toJson(Collections.singletonList(certificateKey))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ERROR"))
            // Not found rather than not allowed: an outsider learns nothing about what exists.
            .andExpect(jsonPath("$.message").value("That certificate could not be found."))
            .andExpect(jsonPath("$.data").doesNotExist());

        // Nothing was built from it either way.
        assertThat(this.objects.keySet()).noneMatch(key -> key.contains("truststore-"));
    }

    /**
     * The reference on the profile is trusted forever after: the Kafka client fetches the store on
     * a scheduler thread with no principal, through the read that asks no questions. If a profile
     * can name somebody else's key here, that read hands it over there.
     */
    @Test
    void anotherCompanysAdminCannotPointAProfileAtThisCompanysStore() throws Exception {
        AppUser admin = this.newUser(UserRole.TENANT_ADMIN, this.newTenant("kafka-secret-owner"));
        AppUser outsider = this.newUser(UserRole.TENANT_ADMIN, this.newTenant("kafka-secret-other"));
        String truststore = this.generateTruststore(admin,
            this.uploadCa(admin, "Production Root CA", "ca.pem"));

        String profileName = "e2e-borrowed-" + this.unique();
        KafkaConnectionProfileDto dto = new KafkaConnectionProfileDto();
        dto.setProfileName(profileName);
        dto.setBootstrapServers("broker.kafka.example:9093");
        dto.setSecurityProtocol("SSL");
        dto.setSslTruststoreBucket(KafkaSecretService.SECRET_BUCKET);
        dto.setSslTruststoreLocation(this.text(truststore, "$.data.objectKey"));
        dto.setSslTruststorePasswordEnc(this.text(truststore, "$.data.storePasswordEnc"));

        this.mvc.perform(this.postAs(outsider, "/kafkaConnectionProfile.json/addProfile",
                new Gson().toJson(dto)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ERROR"))
            .andExpect(jsonPath("$.message").value("That truststore could not be found."));

        // Refused, not merely unreported: no row was written for it.
        String listed = this.mvc.perform(this.getAs(outsider, "/kafkaConnectionProfile.json/fetchAllProfiles"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertThat(listed).doesNotContain(profileName);
    }

    /**
     * "PLATFORM_ADMIN can perform all actions on all details of TENANT_ADMIN and TENANT_USER" --
     * including finishing a workflow a tenant's administrator started, which is what support gets
     * asked to do when a certificate rotation stalls half way.
     */
    @Test
    void aPlatformAdminCanBuildAStoreFromAnyTenantsCertificate() throws Exception {
        AppUser admin = this.newUser(UserRole.TENANT_ADMIN, this.newTenant("kafka-secret-owner"));
        AppUser platformAdmin = this.newPlatformAdmin();
        String certificateKey = this.uploadCa(admin, "Production Root CA", "ca.pem");

        String body = this.mvc.perform(this.postAs(platformAdmin, "/kafkaSecret.json/generateTruststore",
                new Gson().toJson(Collections.singletonList(certificateKey))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andReturn().getResponse().getContentAsString();

        // Still in the uploader's folder, not the platform admin's -- the store belongs beside the
        // certificate it was built from whoever pressed the button.
        assertThat(this.text(body, "$.data.objectKey")).startsWith(this.folderOf(certificateKey));
    }

    // ---- the refusals somebody will actually hit ---------------------------------------------

    /**
     * The file is parsed before it is stored, so a mistake is reported while the person is still
     * looking at the form -- and the bucket never accumulates rubbish nothing can read.
     */
    @Test
    void aFileThatIsNotACertificateIsRefusedAndNothingIsStored() throws Exception {
        Tenant tenant = this.newTenant("kafka-secret");
        AppUser admin = this.newUser(UserRole.TENANT_ADMIN, tenant);
        MockMultipartFile nonsense = new MockMultipartFile("file", "ca.pem", "text/plain",
            "this is not a certificate".getBytes(StandardCharsets.UTF_8));

        this.mvc.perform(this.uploadAs(admin, nonsense, KafkaSecretKind.CA_CERTIFICATE))
            // The server's own message, not a stack trace and not a 500: this is a validation
            // failure the caller can act on.
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ERROR"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("X.509")))
            .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(this.objects).isEmpty();
    }

    /** openssl produces PKCS#1 by default, so this is the message that unblocks people. */
    @Test
    void aPkcs1PrivateKeyIsRefusedWithTheCommandThatConvertsIt() throws Exception {
        Tenant tenant = this.newTenant("kafka-secret");
        AppUser admin = this.newUser(UserRole.TENANT_ADMIN, tenant);
        MockMultipartFile pkcs1 = new MockMultipartFile("file", "client.key", "application/x-pem-file",
            "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n"
                .getBytes(StandardCharsets.UTF_8));

        this.mvc.perform(this.uploadAs(admin, pkcs1, KafkaSecretKind.CLIENT_PRIVATE_KEY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ERROR"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("PKCS#1")))
            .andExpect(jsonPath("$.message").value(
                org.hamcrest.Matchers.containsString("openssl pkcs8 -topk8 -nocrypt")));

        // A key that cannot be read must not be left in the bucket for a later request to find.
        assertThat(this.objects).isEmpty();
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** Uploads are multipart, so they cannot go through the harness's JSON request builders. */
    private RequestBuilder uploadAs(AppUser user, MockMultipartFile file, KafkaSecretKind kind) {
        return MockMvcRequestBuilders.multipart("/kafkaSecret.json/uploadSecret")
            .file(file)
            .param("kind", kind.name())
            .header("Authorization", "Bearer " + this.tokenFor(user));
    }

    private String uploadCa(AppUser user, String commonName, String fileName) throws Exception {
        SelfSignedCertificate ca = SelfSignedCertificate.issue(commonName);
        String body = this.mvc.perform(this.uploadAs(user,
                this.pem(fileName, "CERTIFICATE", ca.certificate().getEncoded()),
                KafkaSecretKind.CA_CERTIFICATE))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andReturn().getResponse().getContentAsString();
        return this.text(body, "$.data.objectKey");
    }

    private String generateTruststore(AppUser user, String certificateObjectKey) throws Exception {
        return this.mvc.perform(this.postAs(user, "/kafkaSecret.json/generateTruststore",
                new Gson().toJson(Collections.singletonList(certificateObjectKey))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andReturn().getResponse().getContentAsString();
    }

    /** A PEM the way a broker's operator would hand one over. */
    private MockMultipartFile pem(String fileName, String label, byte[] der) {
        String encoded = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der);
        String text = "-----BEGIN " + label + "-----\n" + encoded + "\n-----END " + label + "-----\n";
        return new MockMultipartFile("file", fileName, "application/x-pem-file",
            text.getBytes(StandardCharsets.UTF_8));
    }

    private String folderOf(String objectKey) {
        return objectKey.substring(0, objectKey.lastIndexOf('/') + 1);
    }

    private String text(String body, String path) {
        return JsonPath.read(body, path);
    }

    private Object rememberObject(InvocationOnMock call) throws Exception {
        InputStream in = call.getArgument(2);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            out.write(chunk, 0, read);
        }
        this.objects.put(call.getArgument(1), out.toByteArray());
        return null;
    }

    /** Made here and thrown away with the JVM, so nothing credential-shaped is committed. */
    private static String freshAesKey() {
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            return Base64.getEncoder().encodeToString(generator.generateKey().getEncoded());
        } catch (Exception ex) {
            throw new IllegalStateException("Could not make an encryption key for the e2e run.", ex);
        }
    }


    /**
     * The third listing to be caught sharing the platform's rows with every tenant, after the
     * object browser and the storage screen. A workspace brings its own Kafka, so the platform's
     * clusters -- their names, their broker addresses, their environment labels -- are not a
     * tenant's business.
     *
     * Dispatch is deliberately unaffected: KafkaConnectionResolver still falls back to the
     * platform default for a tenant with no profile of its own, on the server, without consulting
     * what that tenant can see.
     */
    @Test
    void aTenantAdminDoesNotSeeThePlatformsKafkaProfiles() throws Exception {
        Tenant company = this.newTenant("ajwa");
        AppUser admin = this.newUser(UserRole.TENANT_ADMIN, company);

        String body = this.mvc.perform(this.getAs(admin, "/kafkaConnectionProfile.json/fetchAllProfiles"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        // Every profile in the development database is platform-owned, so a correct answer for a
        // brand-new tenant carries none of them.
        assertThat(body).doesNotContain("Platform Local Broker");
    }

    @Test
    void aPlatformAdminStillSeesThePlatformsKafkaProfiles() throws Exception {
        AppUser admin = this.newPlatformAdmin();

        String body = this.mvc.perform(this.getAs(admin, "/kafkaConnectionProfile.json/fetchAllProfiles"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Platform Local Broker");
    }

}

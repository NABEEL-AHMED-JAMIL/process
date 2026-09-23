package process.storage.remote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import process.model.dto.ObjectContentDto;
import process.model.dto.ObjectMetadataDto;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.storage.TrustedAccess;
import process.storage.TrustedCaller;
import process.util.EncryptionUtil;
import process.util.StorageNotFound;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * process's side of storage-service (MIG-68): each call carries the credential its kind needs, and
 * each answer comes back meaning what process's callers already act on.
 */
class StorageServiceClientTest {

    private static final String TOKEN = "process-to-storage";
    private static final String USER = "Bearer user.jwt.here";

    @BeforeEach
    void signedIn() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", USER);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void signedOut() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int n;
        while ((n = in.read(chunk)) != -1) {
            out.write(chunk, 0, n);
        }
        return out.toByteArray();
    }

    @Test
    void aTrustedReadNamesItsCallerReasonAndWorkspaceWithTheServiceTokenOnly() throws Exception {
        try (StorageServiceStub storage = new StorageServiceStub("/api/v1/internal/storage/object", 200, "application/x-pkcs12",
            new byte[] {7, 8, 9})) {
            ObjectContentDto content = new StorageServiceClient(storage.url(), TOKEN).trustedRead(
                TrustedAccess.of(TrustedCaller.KAFKA_SECRETS, "truststore for profile 7").forTenant(2901L), "etl-config", "kafka-secrets/7/t.p12");

            assertThat(read(content.getContent())).containsExactly(7, 8, 9);
            assertThat(content.getContentType()).isEqualTo("application/x-pkcs12");
            assertThat(content.getFileName()).isEqualTo("truststore.p12");
            StorageServiceStub.Seen call = storage.last();
            assertThat(call.token).isEqualTo(TOKEN);
            assertThat(call.authorization).as("a trusted call never carries a user's token").isNull();
            assertThat(call.uri).contains("caller=KAFKA_SECRETS").contains("tenantId=2901").contains("bucket=etl-config")
                .contains("key=kafka-secrets%2F7%2Ft.p12").contains("reason=truststore%20for%20profile%207");
        }
    }

    @Test
    void aTrustedUploadStreamsTheBytesWithTheirTypeAndLength() throws Exception {
        try (StorageServiceStub storage = StorageServiceStub.json("/api/v1/internal/storage/object", 200, "{\"status\":\"SUCCESS\"}")) {
            new StorageServiceClient(storage.url(), TOKEN).trustedUpload(TrustedAccess.of(TrustedCaller.IDENTITY_AVATAR, "picture"),
                "etl-avatar", "61/profile/a.png", new ByteArrayInputStream(new byte[] {1, 2, 3}), 3, "image/png");

            StorageServiceStub.Seen call = storage.last();
            assertThat(call.method).isEqualTo("PUT");
            assertThat(call.body).containsExactly(1, 2, 3);
            assertThat(call.contentType).startsWith("image/png");
            assertThat(call.uri).doesNotContain("tenantId");
        }
    }

    /** A missing object must still read as missing to StorageNotFound, or avatars turn into 500s again. */
    @Test
    void aMissingObjectIsStillNotFoundAndARefusalIsStillARefusal() throws Exception {
        try (StorageServiceStub storage = StorageServiceStub.json("/api/v1/internal/storage/object", 404,
            "{\"status\":\"ERROR\",\"message\":\"No object at b/k.\"}")) {
            Throwable missing = catchThrowable(() -> new StorageServiceClient(storage.url(), TOKEN)
                .trustedRead(TrustedAccess.of(TrustedCaller.IDENTITY_AVATAR, "avatar"), "b", "k"));
            assertThat(StorageNotFound.isNotFound(missing)).isTrue();
        }
        try (StorageServiceStub storage = StorageServiceStub.json("/api/v1/internal/storage/object", 400,
            "{\"status\":\"ERROR\",\"message\":\"Unknown bucket: b. Add a storage connection for it first.\"}")) {
            assertThatThrownBy(() -> new StorageServiceClient(storage.url(), TOKEN)
                .trustedRead(TrustedAccess.of(TrustedCaller.IDENTITY_AVATAR, "avatar"), "b", "k"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Unknown bucket: b. Add a storage connection for it first.");
        }
    }

    @Test
    void aGuardedReadGoesAsTheSignedInUser() throws Exception {
        try (StorageServiceStub storage = StorageServiceStub.json("/api/v1/storage.json/objectMetadata", 200,
            "{\"status\":\"SUCCESS\",\"data\":{\"key\":\"q3/a.csv\",\"size\":42,\"etag\":\"e1\",\"previewable\":true,\"extra\":1}}")) {
            ObjectMetadataDto metadata = new HttpStorageBrowser(new StorageServiceClient(storage.url(), TOKEN)).getObjectMetadata("b", "q3/a.csv");

            assertThat(metadata.getSize()).isEqualTo(42L);
            assertThat(metadata.getEtag()).isEqualTo("e1");
            assertThat(storage.last().authorization).isEqualTo(USER);
            assertThat(storage.last().token).as("the public API is not the service's").isNull();
        }
    }

    @Test
    void aGuardedRefusalCarriesStoragesWords() throws Exception {
        try (StorageServiceStub storage = StorageServiceStub.json("/api/v1/storage.json/objectMetadata", 400,
            "{\"status\":\"ERROR\",\"message\":\"Invalid key: a/../b.\"}")) {
            assertThatThrownBy(() -> new HttpStorageBrowser(new StorageServiceClient(storage.url(), TOKEN)).getObjectMetadata("b", "a/../b"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid key: a/../b.");
        }
    }

    /** A key the caller composed goes up as its last segment into its folder -- the same key. */
    @Test
    void anUploadToAComposedKeyIsItsNameIntoItsFolder() throws Exception {
        try (StorageServiceStub storage = StorageServiceStub.json("/api/v1/storage.json/uploadObject", 200,
            "{\"status\":\"SUCCESS\",\"message\":\"File uploaded successfully.\"}")) {
            new HttpStorageBrowser(new StorageServiceClient(storage.url(), TOKEN)).uploadObject("acme-docs",
                "reports/2026/q3.pdf", new ByteArrayInputStream("pdf".getBytes(StandardCharsets.UTF_8)), 3, "application/pdf");

            String body = storage.last().bodyText();
            assertThat(body).contains("name=\"prefix\"").contains("reports/2026/").contains("filename=\"q3.pdf\"").contains("pdf");
            assertThat(storage.last().authorization).isEqualTo(USER);
        }
    }

    @Test
    void withNoSignedInCallerAGuardedCallIsNotMadeAtAll() {
        RequestContextHolder.resetRequestAttributes();
        assertThatThrownBy(() -> new HttpStorageBrowser(new StorageServiceClient("http://127.0.0.1:1", TOKEN)).listBuckets())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("No signed-in caller");
    }

    // ---- the directory ---------------------------------------------------------------------------

    @Test
    void aVendedConnectionComesBackSealedUnderProcesssOwnKeyForTheRequest() throws Exception {
        EncryptionUtil encryption = encryption();
        try (StorageServiceStub storage = StorageServiceStub.json("/api/v1/internal/storage-connections/resolve", 200,
            "{\"storageConnectionId\":1107,\"alias\":\"reports\",\"provider\":\"MINIO\",\"bucket\":\"acme-reports\","
                + "\"endpoint\":\"http://minio:9000\",\"region\":\"us-east-1\",\"credential\":{\"keyId\":\"AKIA\",\"secret\":\"s3cret\"}}")) {
            Optional<StorageConnection> vended = new RemoteStorageDirectory(new StorageServiceClient(storage.url(), TOKEN), encryption)
                .vendForCaller("reports");

            StorageConnection c = vended.get();
            assertThat(c.getStorageConnectionId()).isEqualTo(1107L);
            assertThat(c.getProvider()).isEqualTo(StorageProvider.MINIO);
            assertThat(c.getBucketName()).isEqualTo("acme-reports");
            assertThat(c.getAccessKey()).isEqualTo("AKIA");
            assertThat(c.getSecretKeyEnc()).isNotEqualTo("s3cret");
            assertThat(encryption.decrypt(c.getSecretKeyEnc())).isEqualTo("s3cret");
            assertThat(storage.last().token).isEqualTo(TOKEN);
            assertThat(storage.last().authorization).as("vending wants the user's own token too").isEqualTo(USER);
            assertThat(storage.last().bodyText()).contains("\"alias\":\"reports\"");
        }
    }

    @Test
    void aRefusedVendIsEmpty() throws Exception {
        try (StorageServiceStub storage = StorageServiceStub.json("/api/v1/internal/storage-connections/resolve", 404,
            "{\"status\":\"ERROR\",\"message\":\"Storage connection not found.\"}")) {
            assertThat(new RemoteStorageDirectory(new StorageServiceClient(storage.url(), TOKEN), encryption()).vendForCaller("archive")).isEmpty();
        }
    }

    @Test
    void onlyAMeasuredOrPartialSizeIsBytesAnythingElseIsMinusOne() throws Exception {
        StorageConnection c = new StorageConnection();
        c.setStorageConnectionId(1107L);
        String[][] cases = {
            {"{\"outcome\":\"MEASURED\",\"bytes\":42}", "42"},
            {"{\"outcome\":\"PARTIAL\",\"bytes\":1000000}", "1000000"},
            {"{\"outcome\":\"NOT_MEASURED\",\"reason\":\"AccessDenied\"}", "-1"},
            {"{\"outcome\":\"SKIPPED\",\"reason\":\"FTP\"}", "-1"}};
        for (String[] each : cases) {
            try (StorageServiceStub storage = StorageServiceStub.json("/api/v1/internal/storage-connections/1107/size", 200, each[0])) {
                long bytes = new RemoteStorageDirectory(new StorageServiceClient(storage.url(), TOKEN), encryption()).bytesIn(c);
                assertThat(bytes).as(each[0]).isEqualTo(Long.parseLong(each[1]));
                assertThat(storage.last().authorization).as("the nightly measurement has no user").isNull();
            }
        }
        try (StorageServiceStub storage = StorageServiceStub.json("/api/v1/internal/storage-connections/1107/size", 404, "")) {
            assertThat(new RemoteStorageDirectory(new StorageServiceClient(storage.url(), TOKEN), encryption()).bytesIn(c)).isEqualTo(-1L);
        }
    }

    @Test
    void anIdByAliasAndAWorkspacesConnectionsReadTheDirectory() throws Exception {
        try (StorageServiceStub storage = StorageServiceStub.json("/api/v1/internal/storage-connections", 200,
            "[{\"storageConnectionId\":1107,\"tenantId\":2901,\"alias\":\"reports\",\"provider\":\"MINIO\",\"bucketName\":\"b\",\"status\":\"Active\"},"
                + "{\"storageConnectionId\":1000,\"tenantId\":null,\"alias\":\"archive\",\"provider\":\"S3\",\"bucketName\":\"a\",\"status\":\"Inactive\"}]")) {
            RemoteStorageDirectory directory = new RemoteStorageDirectory(new StorageServiceClient(storage.url(), TOKEN), encryption());

            assertThat(directory.workspace(2901L)).extracting(StorageConnection::getTenantId).containsExactly(2901L, null);
            assertThat(storage.last().uri).endsWith("/internal/storage-connections?tenantId=2901");
            assertThat(directory.byAlias("reports")).hasSize(2);
            assertThat(storage.last().uri).contains("/byAlias?alias=reports");
        }
    }

    private static EncryptionUtil encryption() {
        EncryptionUtil util = new EncryptionUtil();
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        ReflectionTestUtils.setField(util, "base64Key", Base64.getEncoder().encodeToString(key));
        return util;
    }
}

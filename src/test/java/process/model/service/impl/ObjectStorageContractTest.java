package process.model.service.impl;

import com.azure.storage.blob.BlobServiceClientBuilder;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;
import process.config.StorageClientFactory;
import process.model.dto.BrowseObjectsResponseDto;
import process.model.dto.ObjectContentDto;
import process.model.dto.ObjectMetadataDto;
import process.model.dto.ObjectSummaryDto;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.model.service.ObjectStorageService;
import process.util.EncryptionUtil;
import process.util.StorageNotFound;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The four ObjectStorageService implementations, driven by one suite (MIG-160) -- FTP included, the
 * one routinely forgotten -- against real servers: LocalStack (S3), MinIO, Azurite (Azure) and an
 * embedded Apache FtpServer. Each is built the way production builds it, by StorageClientFactory from
 * a StorageConnection with an encrypted secret.
 *
 * The shared contract is asserted for all four. Where they differ, the difference is ASSERTED per
 * provider rather than smoothed over, because each is a thing a caller over the network could
 * wrongly assume away: a continuation token is provider-specific, FTP has no etag and cannot be
 * listed whole, and so on. A later Storage service must keep every one of these, or change it on
 * purpose with this suite going red first.
 *
 * Opt-in per backend: a backend that is not answering is skipped, not failed. Azurite starts with
 * `docker compose --profile storage-test up -d azurite`; LocalStack with `--profile aws`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ObjectStorageContractTest {

    private static final String BUCKET = "storage-contract-it";
    private static final String AZURITE = "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
        + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;"
        + "BlobEndpoint=http://127.0.0.1:10000/devstoreaccount1;";

    private final EncryptionUtil encryption = encryption();
    private final StorageClientFactory factory = new StorageClientFactory(this.encryption, null);
    private FtpServer ftp;
    private Path ftpRoot;
    private int ftpPort;
    private final String ftpPassword = UUID.randomUUID().toString();
    /** Each run in a folder of its own, so runs never read each other's objects. */
    private final String run = "run-" + UUID.randomUUID().toString().substring(0, 8) + "/";

    enum Backend { S3, MINIO, AZURE, FTP }

    static Stream<Backend> backends() {
        return Stream.of(Backend.values());
    }

    @BeforeAll
    void startFtpAndMakeBuckets() throws Exception {
        this.ftpRoot = Files.createTempDirectory("storage-contract-ftp");
        try (ServerSocket free = new ServerSocket(0)) {
            this.ftpPort = free.getLocalPort();
        }
        FtpServerFactory server = new FtpServerFactory();
        ListenerFactory listener = new ListenerFactory();
        listener.setPort(this.ftpPort);
        server.addListener("default", listener.createListener());
        BaseUser user = new BaseUser();
        user.setName("contract");
        user.setPassword(this.ftpPassword);
        user.setHomeDirectory(this.ftpRoot.toString());
        user.setAuthorities(Collections.<Authority>singletonList(new WritePermission()));
        server.getUserManager().save(user);
        this.ftp = server.createServer();
        this.ftp.start();

        if (answers("localhost", 4566)) {
            try (S3Client s3 = S3Client.builder().endpointOverride(URI.create("http://localhost:4566")).forcePathStyle(true)
                .region(Region.US_EAST_1).credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"))).build()) {
                try {
                    s3.createBucket(b -> b.bucket(BUCKET));
                } catch (Exception exists) {
                    // Already there from an earlier run.
                }
            }
        }
        if (answers("localhost", 9000)) {
            io.minio.MinioClient minio = io.minio.MinioClient.builder().endpoint("http://localhost:9000")
                .credentials(minioAccessKey(), minioSecretKey()).build();
            if (!minio.bucketExists(io.minio.BucketExistsArgs.builder().bucket(BUCKET).build())) {
                minio.makeBucket(io.minio.MakeBucketArgs.builder().bucket(BUCKET).build());
            }
        }
        if (answers("localhost", 10000)) {
            new BlobServiceClientBuilder().connectionString(AZURITE).buildClient().getBlobContainerClient(BUCKET).createIfNotExists();
        }
    }

    @AfterAll
    void stopFtp() {
        if (this.ftp != null) {
            this.ftp.stop();
        }
    }

    // ---- the shared contract ------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("backends")
    void anUploadReadsBackWithItsSizeAndBytes(Backend backend) throws Exception {
        ObjectStorageService store = this.store(backend);
        String key = this.run + "shared/q3.csv";
        byte[] bytes = "id,region\n1,south\n2,east\n".getBytes(StandardCharsets.UTF_8);

        store.uploadObject(BUCKET, key, new ByteArrayInputStream(bytes), bytes.length, "text/csv");

        ObjectMetadataDto metadata = store.getObjectMetadata(BUCKET, key);
        assertThat(metadata.getSize()).isEqualTo((long) bytes.length);
        assertThat(metadata.getName()).isEqualTo("q3.csv");
        assertThat(read(store.getObjectContent(BUCKET, key, null, null))).isEqualTo(bytes);
    }

    @ParameterizedTest
    @MethodSource("backends")
    void aRangedReadReturnsJustThoseBytes(Backend backend) throws Exception {
        ObjectStorageService store = this.store(backend);
        String key = this.run + "range/digits.txt";
        byte[] bytes = "0123456789".getBytes(StandardCharsets.UTF_8);
        store.uploadObject(BUCKET, key, new ByteArrayInputStream(bytes), bytes.length, "text/plain");

        ObjectContentDto part = store.getObjectContent(BUCKET, key, 2L, 5L);

        assertThat(new String(read(part), StandardCharsets.UTF_8)).isEqualTo("2345");
    }

    @ParameterizedTest
    @MethodSource("backends")
    void aListingShowsFilesAndFoldersOneLevelDown(Backend backend) {
        ObjectStorageService store = this.store(backend);
        String base = this.run + "listing/";
        this.put(store, base + "a.csv");
        this.put(store, base + "b.csv");
        this.put(store, base + "deeper/c.csv");

        BrowseObjectsResponseDto page = store.listObjects(BUCKET, base, null, 50);

        List<String> files = page.getObjects().stream().filter(o -> !o.isFolder()).map(ObjectSummaryDto::getName).collect(Collectors.toList());
        List<String> folders = page.getObjects().stream().filter(ObjectSummaryDto::isFolder).map(ObjectSummaryDto::getKey).collect(Collectors.toList());
        assertThat(files).containsExactlyInAnyOrder("a.csv", "b.csv");
        assertThat(folders).containsExactly(base + "deeper/");
    }

    @ParameterizedTest
    @MethodSource("backends")
    void aFolderIsDeletedWithEverythingUnderIt(Backend backend) {
        ObjectStorageService store = this.store(backend);
        String folder = this.run + "doomed/";
        this.put(store, folder + "a.csv");
        this.put(store, folder + "inner/b.csv");
        this.put(store, this.run + "kept.csv");

        store.deleteFolder(BUCKET, folder);

        assertThat(catchThrowable(() -> store.getObjectMetadata(BUCKET, folder + "a.csv"))).isNotNull();
        assertThat(catchThrowable(() -> store.getObjectMetadata(BUCKET, folder + "inner/b.csv"))).isNotNull();
        assertThat(store.getObjectMetadata(BUCKET, this.run + "kept.csv")).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("backends")
    void aFolderIsRenamedWithEverythingUnderIt(Backend backend) {
        ObjectStorageService store = this.store(backend);
        this.put(store, this.run + "old/a.csv");
        this.put(store, this.run + "old/inner/b.csv");

        store.renameFolder(BUCKET, this.run + "old/", this.run + "new/");

        assertThat(store.getObjectMetadata(BUCKET, this.run + "new/a.csv")).isNotNull();
        assertThat(store.getObjectMetadata(BUCKET, this.run + "new/inner/b.csv")).isNotNull();
        assertThat(catchThrowable(() -> store.getObjectMetadata(BUCKET, this.run + "old/a.csv"))).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("backends")
    void anObjectIsDeleted(Backend backend) {
        ObjectStorageService store = this.store(backend);
        String key = this.run + "gone.csv";
        this.put(store, key);

        store.deleteObject(BUCKET, key);

        assertThat(catchThrowable(() -> store.getObjectMetadata(BUCKET, key))).isNotNull();
    }

    // ---- where they differ, asserted --------------------------------------------------------------

    /**
     * A missing object: the object stores all fail in a shape StorageNotFound reads as "not found"
     * (that is what lets the REST layer answer 404 instead of 500). FTP does not -- it refuses outright
     * with an IllegalArgumentException, which the REST layer answers 400 with FTP's own sentence.
     */
    @ParameterizedTest
    @MethodSource("backends")
    void aMissingObjectIsNotFoundOnTheObjectStoresAndARefusalOnFtp(Backend backend) {
        ObjectStorageService store = this.store(backend);

        Throwable missing = catchThrowable(() -> store.getObjectMetadata(BUCKET, this.run + "never-written.csv"));

        if (backend == Backend.FTP) {
            assertThat(missing).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Not found on the FTP server");
        } else {
            assertThat(StorageNotFound.isNotFound(missing)).as(String.valueOf(missing)).isTrue();
        }
    }

    /**
     * Paging. The token is provider-specific and must never be treated as portable: MinIO EMULATES
     * paging (it fetches maxKeys + 1 and hands back the last object's name as the token); S3 returns
     * its own opaque token; Azure returns the service's marker (Azurite's happens to be the blob name,
     * real Azure's is encoded -- which is exactly why nothing may read it); FTP has no paging at all --
     * it returns the first maxKeys, never a token, and ignores one it is given.
     */
    @ParameterizedTest
    @MethodSource("backends")
    void theContinuationTokenIsTheProvidersOwn(Backend backend) {
        ObjectStorageService store = this.store(backend);
        String base = this.run + "paging/";
        for (String name : Arrays.asList("a.csv", "b.csv", "c.csv")) {
            this.put(store, base + name);
        }

        BrowseObjectsResponseDto first = store.listObjects(BUCKET, base, null, 2);
        assertThat(first.getObjects()).hasSize(2);

        switch (backend) {
            case MINIO:
                assertThat(first.getNextContinuationToken()).isEqualTo(first.getObjects().get(1).getKey());
                break;
            case FTP:
                assertThat(first.getNextContinuationToken()).isNull();
                assertThat(store.listObjects(BUCKET, base, "anything", 2).getObjects())
                    .extracting(ObjectSummaryDto::getKey).isEqualTo(first.getObjects().stream().map(ObjectSummaryDto::getKey).collect(Collectors.toList()));
                return;
            case AZURE:
                assertThat(first.getNextContinuationToken()).isNotNull();
                break;
            default:
                assertThat(first.getNextContinuationToken()).isNotNull()
                    .isNotEqualTo(first.getObjects().get(1).getKey());
        }
        BrowseObjectsResponseDto second = store.listObjects(BUCKET, base, first.getNextContinuationToken(), 2);
        assertThat(second.getObjects()).extracting(ObjectSummaryDto::getName).containsExactly("c.csv");
    }

    /** FTP has no etag; every object store has one. The chat panel's freshness check leans on it. */
    @ParameterizedTest
    @MethodSource("backends")
    void onlyFtpHasNoEtag(Backend backend) {
        ObjectStorageService store = this.store(backend);
        String key = this.run + "etag.csv";
        this.put(store, key);

        String etag = store.getObjectMetadata(BUCKET, key).getEtag();

        if (backend == Backend.FTP) {
            assertThat(etag).isNull();
        } else {
            assertThat(etag).isNotBlank();
        }
    }

    /** FTP cannot be listed whole -- which is why the nightly measurement skips it (non-object-store). */
    @ParameterizedTest
    @MethodSource("backends")
    void onlyFtpCannotBeListedWhole(Backend backend) {
        ObjectStorageService store = this.store(backend);
        String base = this.run + "whole/";
        this.put(store, base + "a.csv");
        this.put(store, base + "deep/b.csv");

        if (backend == Backend.FTP) {
            assertThatThrownBy(() -> store.listAllObjects(BUCKET, base, 100)).isInstanceOf(UnsupportedOperationException.class);
        } else {
            assertThat(store.listAllObjects(BUCKET, base, 100)).extracting(ObjectSummaryDto::getKey)
                .containsExactlyInAnyOrder(base + "a.csv", base + "deep/b.csv");
        }
    }

    /**
     * A range that starts past the end. MinIO, S3 and FTP guard it (the object stores serve the whole
     * object; FTP reads nothing). Azure has no such guard and fails outright.
     */
    @ParameterizedTest
    @MethodSource("backends")
    void aRangeStartingPastTheEndIsGuardedEverywhereButAzure(Backend backend) throws Exception {
        ObjectStorageService store = this.store(backend);
        String key = this.run + "short.txt";
        byte[] bytes = "0123".getBytes(StandardCharsets.UTF_8);
        store.uploadObject(BUCKET, key, new ByteArrayInputStream(bytes), bytes.length, "text/plain");

        Throwable outcome = catchThrowable(() -> read(store.getObjectContent(BUCKET, key, 10L, null)));

        if (backend == Backend.AZURE) {
            assertThat(outcome).isNotNull();
        } else {
            assertThat(outcome).as(String.valueOf(outcome)).isNull();
        }
    }

    /**
     * Deleting a batch that names an object that is not there is the same on all four: the missing key
     * counts as already gone and the rest are deleted. S3 and MinIO by S3's own semantics, Azure
     * because it deletes one by one with deleteIfExists, FTP because it logs a failed delete and
     * carries on. (MinIO does abort a batch on the first REAL error -- a refusal, not an absence.)
     */
    @ParameterizedTest
    @MethodSource("backends")
    void aBatchDeleteNamingAMissingObjectDeletesTheRest(Backend backend) {
        ObjectStorageService store = this.store(backend);
        String present = this.run + "batch/present.csv";
        String after = this.run + "batch/zz-after.csv";
        this.put(store, present);
        this.put(store, after);

        store.deleteObjects(BUCKET, Arrays.asList(present, this.run + "batch/missing.csv", after));

        assertThat(catchThrowable(() -> store.getObjectMetadata(BUCKET, present))).isNotNull();
        assertThat(catchThrowable(() -> store.getObjectMetadata(BUCKET, after))).isNotNull();
    }

    /**
     * Listing a folder that is not there. On an object store a prefix is only a string, so the answer
     * is an empty page; on FTP a directory is a thing, and listing one that does not exist fails. A
     * caller that lists after a delete or a rename must not assume the empty page.
     */
    @ParameterizedTest
    @MethodSource("backends")
    void listingAFolderThatIsNotThereIsEmptyExceptOnFtp(Backend backend) {
        ObjectStorageService store = this.store(backend);
        String nowhere = this.run + "never-made/";

        if (backend == Backend.FTP) {
            assertThatThrownBy(() -> store.listObjects(BUCKET, nowhere, null, 50)).hasMessageContaining("Non-existing");
        } else {
            assertThat(store.listObjects(BUCKET, nowhere, null, 50).getObjects()).isEmpty();
        }
    }

    // ---- plumbing ---------------------------------------------------------------------------

    private ObjectStorageService store(Backend backend) {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId((long) (backend.ordinal() + 900));
        connection.setAlias("contract-" + backend.name().toLowerCase());
        connection.setStatus(Status.Active);
        switch (backend) {
            case S3:
                assumeTrue(answers("localhost", 4566), "LocalStack is not running (docker compose --profile aws up -d localstack)");
                connection.setProvider(StorageProvider.S3);
                connection.setEndpoint("http://localhost:4566");
                connection.setRegion("us-east-1");
                connection.setAccessKey("test");
                connection.setSecretKeyEnc(this.encrypt("test"));
                break;
            case MINIO:
                assumeTrue(answers("localhost", 9000), "MinIO is not running on :9000");
                connection.setProvider(StorageProvider.MINIO);
                connection.setEndpoint("http://localhost:9000");
                connection.setAccessKey(minioAccessKey());
                connection.setSecretKeyEnc(this.encrypt(minioSecretKey()));
                break;
            case AZURE:
                assumeTrue(answers("localhost", 10000), "Azurite is not running (docker compose --profile storage-test up -d azurite)");
                connection.setProvider(StorageProvider.AZURE);
                connection.setAzureConnectionStringEnc(this.encrypt(AZURITE));
                break;
            default:
                connection.setProvider(StorageProvider.FTP);
                connection.setHost("localhost");
                connection.setPort(this.ftpPort);
                connection.setUsername("contract");
                connection.setPasswordEnc(this.encrypt(this.ftpPassword));
                connection.setBaseDirectory("/");
                connection.setPassiveMode(true);
        }
        connection.setBucketName(BUCKET);
        return this.factory.buildUncached(connection);
    }

    private void put(ObjectStorageService store, String key) {
        byte[] bytes = ("contents of " + key).getBytes(StandardCharsets.UTF_8);
        store.uploadObject(BUCKET, key, new ByteArrayInputStream(bytes), bytes.length, "text/csv");
    }

    private String encrypt(String plain) {
        try {
            return this.encryption.encrypt(plain);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] read(ObjectContentDto content) throws Exception {
        try (InputStream in = content.getContent()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static boolean answers(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 300);
            return true;
        } catch (Exception closed) {
            return false;
        }
    }

    private static String minioAccessKey() {
        String value = System.getenv("MINIO_ACCESS_KEY");
        return value == null ? "minioadmin" : value;
    }

    private static String minioSecretKey() {
        String value = System.getenv("MINIO_SECRET_KEY");
        return value == null ? "minioadmin123" : value;
    }

    private static EncryptionUtil encryption() {
        EncryptionUtil util = new EncryptionUtil();
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        ReflectionTestUtils.setField(util, "base64Key", Base64.getEncoder().encodeToString(key));
        return util;
    }
}

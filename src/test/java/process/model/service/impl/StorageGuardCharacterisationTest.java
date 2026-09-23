package process.model.service.impl;

import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.config.StorageClientFactory;
import process.model.dto.LookupDataDto;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.model.repository.StorageConnectionRepository;
import process.model.service.ObjectStorageService;
import process.security.TenantContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Storage's guarded path, pinned before Storage moves (MIG-51, MIG-158): the parts the existing
 * tenant-isolation, platform-bucket and own-avatar suites did not already hold, each named for the
 * incident it exists for.
 *
 * <ul>
 *   <li>isSafeKey refuses rather than rewrites, on every bucket, not only the avatar bucket -- and a
 *       null or empty key is safe, because it means "list".</li>
 *   <li>A platform bucket is recognised on a connection row of any status.</li>
 *   <li>Every "you may not have this bucket" answer reads the same, so the wording does not say
 *       whether the bucket exists.</li>
 *   <li>The loose tenant filter on storage_connection, and why it has to stay loose.</li>
 *   <li>Every public method that takes a key goes through requireSafeKey.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class StorageGuardCharacterisationTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 2002L;
    private static final long USER_A = 61L;
    private static final String AVATAR_BUCKET = "etl-avatar";
    private static final String CONFIG_BUCKET = "etl-config";
    private static final String PLATFORM_BUCKET = "etl-bucket";
    private static final String TENANT_A_BUCKET = "tenant-a-exports";
    private static final String TENANT_B_BUCKET = "tenant-b-exports";
    private static final String FTP_BUCKET = "tenant-a-ftp";

    @Mock private LookupDataCacheService lookupDataCacheService;
    @Mock private StorageConnectionRepository storageConnectionRepository;
    @Mock private StorageClientFactory storageClientFactory;
    @Mock private ObjectStorageService objectStorageService;

    private StorageBrowserServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new StorageBrowserServiceImpl(this.lookupDataCacheService, this.storageConnectionRepository,
            this.storageClientFactory, this.objectStorageService, AVATAR_BUCKET, CONFIG_BUCKET, org.mockito.Mockito.mock(process.storage.ObjectChangeLog.class));
        this.stub(PLATFORM_BUCKET, null, StorageProvider.MINIO);
        this.stub(TENANT_A_BUCKET, TENANT_A, StorageProvider.MINIO);
        this.stub(TENANT_B_BUCKET, TENANT_B, StorageProvider.MINIO);
        this.stub(FTP_BUCKET, TENANT_A, StorageProvider.FTP);
        lenient().when(this.storageClientFactory.serviceFor(any())).thenReturn(this.objectStorageService);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void stub(String alias, Long tenantId, StorageProvider provider) {
        StorageConnection row = connection(alias, tenantId, provider, Status.Active);
        process.storage.StorageRows.add(this.storageConnectionRepository, row);
        process.storage.StorageRows.add(this.storageConnectionRepository, row);
    }

    private static StorageConnection connection(String alias, Long tenantId, StorageProvider provider, Status status) {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(700L);
        connection.setTenantId(tenantId);
        connection.setConnectionName(alias);
        connection.setAlias(alias);
        connection.setBucketName(alias);
        connection.setProvider(provider);
        connection.setStatus(status);
        return connection;
    }

    private void actAsTenantUser() {
        TenantContext.set(TENANT_A, "TENANT_USER", USER_A, "tenant-user@example.com");
    }

    // ---- isSafeKey: refuse, never rewrite ---------------------------------------------------------

    /** A null or empty key is a listing of the bucket's root, not a key to refuse. */
    @Test
    void aNullOrEmptyPrefixIsAListingAndIsAllowed() {
        this.actAsTenantUser();

        this.service.listObjects(TENANT_A_BUCKET, null, null, 50);
        this.service.listObjects(TENANT_A_BUCKET, "", null, 50);

        verify(this.objectStorageService).listObjects(eq(TENANT_A_BUCKET), isNull(), isNull(), anyInt());
        verify(this.objectStorageService).listObjects(eq(TENANT_A_BUCKET), eq(""), isNull(), anyInt());
    }

    /**
     * The refusals, on a tenant's own bucket -- the existing suites pin them on the avatar bucket,
     * where the own-profile exception gives them a second reason to hold. Here they have only one:
     * a rewritten key is a key the caller never asked for. Exact message, and nothing reaches the
     * store.
     */
    @Test
    void anUnsafeKeyIsRefusedWithItsOwnNameOnAnyBucketAndNeverReachesTheStore() {
        this.actAsTenantUser();
        for (String key : Arrays.asList("/q3/a.csv", "q3\\a.csv", "q3/./a.csv", "q3/../b.csv", ".", "..", "q3/..")) {
            assertThatThrownBy(() -> this.service.downloadObject(TENANT_A_BUCKET, key, null, null))
                .as(key).isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid key: " + key + ".");
        }
        verifyNoInteractions(this.objectStorageService);
    }

    /**
     * Why refusing and not normalising: the FTP backends collapse "a/../b" to "b" of their own
     * accord, AFTER this class has decided whose key it is. A normalised key would be judged as one
     * path and written as another; a refused one is never written at all.
     */
    @Test
    void onAnFtpConnectionTheClimbIsRefusedBeforeTheServerCanCollapseIt() {
        this.actAsTenantUser();

        assertThatThrownBy(() -> this.service.deleteObject(FTP_BUCKET, "incoming/../../other-tenant/ledger.csv"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("Invalid key: incoming/../../other-tenant/ledger.csv.");
        verifyNoInteractions(this.objectStorageService);
    }

    /** A name that merely contains dots is an ordinary name; only a whole "." or ".." segment is a step. */
    @Test
    void dotsInsideANameAreNotASegment() {
        this.actAsTenantUser();

        this.service.getObjectMetadata(TENANT_A_BUCKET, "q3/..hidden/report..v2.csv");

        verify(this.objectStorageService).getObjectMetadata(TENANT_A_BUCKET, "q3/..hidden/report..v2.csv");
    }

    // ---- isPlatformBucket: the bucket, not the row's lifecycle --------------------------------------

    /**
     * An operator-added platform bucket whose only row is retired still guards the bucket. Matching
     * Active rows alone let the guard fall through to the legacy BUCKET_LIST path -- where a tenant
     * can have an entry of their own with the platform bucket's name and be handed its client.
     */
    @Test
    void aPlatformBucketWhoseRowIsRetiredIsStillAPlatformBucket() {
        String archive = "etl-archive";
        process.storage.StorageRows.add(this.storageConnectionRepository, connection(archive, null, StorageProvider.MINIO, Status.Delete));
        LookupDataDto own = new LookupDataDto();
        own.setLookupType("BUCKET_LIST_" + archive);
        own.setLookupValue(archive);
        own.setDescription("MINIO");
        own.setTenantId(TENANT_A);
        LookupDataDto parent = new LookupDataDto();
        parent.setChildren(Collections.singleton(own));
        lenient().when(this.lookupDataCacheService.getParentLookupById("BUCKET_LIST")).thenReturn(parent);
        this.actAsTenantUser();

        assertThatThrownBy(() -> this.service.downloadObject(archive, "kafka-secrets/truststore.p12", null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("Unknown bucket: " + archive + ".");
        verifyNoInteractions(this.objectStorageService);
    }

    // ---- one answer for every bucket the caller may not have -----------------------------------------

    /**
     * "Not yours" has to read exactly like "not there". A platform bucket, another tenant's bucket and
     * a bucket that does not exist must be refused in the same words; otherwise the wording tells a
     * tenant which of the platform's buckets exist, and which names belong to somebody else.
     */
    @Test
    void aBucketYouMayNotHaveIsRefusedExactlyLikeOneThatDoesNotExist() {
        lenient().when(this.lookupDataCacheService.getParentLookupById("BUCKET_LIST")).thenReturn(null);
        this.actAsTenantUser();

        Map<String, String> answers = new LinkedHashMap<>();
        for (String bucket : Arrays.asList(PLATFORM_BUCKET, AVATAR_BUCKET, CONFIG_BUCKET, TENANT_B_BUCKET, "no-such-bucket")) {
            Throwable refused = catchThrowable(() -> this.service.listObjects(bucket, null, null, 50));
            assertThat(refused).as(bucket).isInstanceOf(IllegalArgumentException.class);
            answers.put(bucket, refused.getMessage().replace(bucket, "<bucket>"));
        }

        assertThat(new java.util.HashSet<>(answers.values())).as(answers.toString()).hasSize(1);
        verifyNoInteractions(this.objectStorageService);
    }

    // ---- the loose tenant filter ------------------------------------------------------------------

    /**
     * Loose on purpose: "(tenant_id = :tenantId or tenant_id is null)". A strict filter hid etl-bucket
     * and etl-avatar from the alias lookup, which broke every workflow writing there AND silently
     * disabled the platform-bucket guard -- the guard can only refuse a connection it can see. The
     * narrowing is done in three places instead (collectBuckets.belongsToCaller, resolveService's
     * tenant refusal, StorageConnectionServiceImpl.isOwnedByCaller), each pinned in its own suite.
     */
    @Test
    void theStorageConnectionTenantFilterStaysLoose() {
        Filter filter = StorageConnection.class.getAnnotation(Filter.class);

        assertThat(filter.name()).isEqualTo("tenantFilter");
        assertThat(filter.condition()).isEqualTo("(tenant_id = :tenantId or tenant_id is null)");
    }

    // ---- every key goes through the guard --------------------------------------------------------

    /**
     * Structural: each public method that takes a key or prefix calls requireSafeKey itself, or hands
     * the key to a method that does. A new method that forgets is a new door around the refusal, and
     * nothing else would notice.
     */
    @Test
    void everyPublicMethodThatTakesAKeyGoesThroughRequireSafeKey() throws IOException {
        String source = new String(Files.readAllBytes(Paths.get(
            "src/main/java/process/model/service/impl/StorageBrowserServiceImpl.java")));
        Map<String, String> delegation = new LinkedHashMap<>();
        delegation.put("getObjectMetadataCached", "this.getObjectMetadata(");
        delegation.put("uploadObject/multipart", "this.uploadMultipart(");
        List<String> unguarded = new ArrayList<>();
        Matcher method = Pattern.compile("\n    public [^\n(]* (\\w+)\\(([^)]*)\\)[^{]*\\{").matcher(source);
        while (method.find()) {
            String params = method.group(2);
            if (!Pattern.compile("\\b(key|prefix|folderKey|keys)\\b").matcher(params).find()) {
                continue;
            }
            String body = body(source, method.end());
            String name = method.group(1) + (params.contains("MultipartFile") ? "/multipart" : "");
            boolean guarded = body.contains("requireSafeKey(")
                || (delegation.containsKey(name) && body.contains(delegation.get(name)));
            if (!guarded) {
                unguarded.add(name + "(" + params + ")");
            }
        }
        assertThat(unguarded).isEmpty();
        assertThat(body(source, source.indexOf("private void uploadMultipart("))).contains("this.requireSafeKey(key)");
    }

    private static String body(String source, int from) {
        int open = source.indexOf('{', from - 1);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            if (c == '}' && --depth == 0) {
                return source.substring(open, i + 1);
            }
        }
        throw new IllegalStateException("unbalanced");
    }
}

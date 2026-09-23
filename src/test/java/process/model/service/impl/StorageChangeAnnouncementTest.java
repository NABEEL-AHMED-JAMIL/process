package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.mock.web.MockMultipartFile;
import org.barco.platform.storage.ObjectChanged;
import process.config.StorageClientFactory;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.model.repository.StorageConnectionRepository;
import process.model.service.ObjectStorageService;
import process.security.TenantContext;
import process.storage.ObjectChangeLog;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ADR-013, Storage's side: every write that can leave Media's extracted text stale announces itself on
 * the outbox BEFORE the write -- so a write whose announcement could not be recorded is refused and
 * nothing changes, and a write that then fails costs only a cache miss. The fileChatExtract half of
 * the old @CacheEvict(allEntries) is gone: that cache is Media's, and Storage cannot reach it once it
 * is a service. fileChatMetadata is Storage's own and is still evicted locally.
 */
class StorageChangeAnnouncementTest {

    private static final String BUCKET = "tenant-a-exports";

    private final StorageConnectionRepository connections = mock(StorageConnectionRepository.class);
    private final StorageClientFactory factory = mock(StorageClientFactory.class);
    private final ObjectStorageService store = mock(ObjectStorageService.class);
    private final ObjectChangeLog changes = mock(ObjectChangeLog.class);
    private StorageBrowserServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new StorageBrowserServiceImpl(mock(LookupDataCacheService.class), this.connections, this.factory,
            this.store, "etl-avatar", "etl-config", this.changes);
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(5L);
        connection.setTenantId(1001L);
        connection.setAlias(BUCKET);
        connection.setBucketName(BUCKET);
        connection.setProvider(StorageProvider.MINIO);
        connection.setStatus(Status.Active);
        process.storage.StorageRows.add(this.connections, connection);
        process.storage.StorageRows.add(this.connections, connection);
        lenient().when(this.factory.serviceFor(any())).thenReturn(this.store);
        TenantContext.set(1001L, "TENANT_ADMIN", 61L, "ops@medaxis.example");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static InputStream bytes() {
        return new ByteArrayInputStream(new byte[] {1, 2, 3});
    }

    @Test
    void anUploadIsAnnouncedBeforeItIsWritten() {
        this.service.uploadObject(BUCKET, "q3/a.csv", bytes(), 3, "text/csv");

        InOrder order = inOrder(this.changes, this.store);
        order.verify(this.changes).object(BUCKET, "q3/a.csv", ObjectChanged.Reason.UPLOAD);
        order.verify(this.store).uploadObject(eq(BUCKET), eq("q3/a.csv"), any(), eq(3L), eq("text/csv"));
    }

    @Test
    void aMultipartUploadAndAWorkflowUploadAreAnnouncedToo() {
        this.service.uploadObject(BUCKET, "q3/", new MockMultipartFile("file", "b.csv", "text/csv", new byte[] {1}));
        this.service.uploadForWorkflow(process.storage.TrustedAccess.of(process.storage.TrustedCaller.KAFKA_SECRETS, "test").forTenant(1001L),
            BUCKET, "kafka/truststore.p12", bytes(), 3, "application/octet-stream");

        InOrder order = inOrder(this.changes, this.store);
        order.verify(this.changes).object(BUCKET, "q3/b.csv", ObjectChanged.Reason.UPLOAD);
        order.verify(this.store).uploadObject(eq(BUCKET), eq("q3/b.csv"), any(), anyLong(), anyString());
        order.verify(this.changes).object(BUCKET, "kafka/truststore.p12", ObjectChanged.Reason.UPLOAD);
        order.verify(this.store).uploadObject(eq(BUCKET), eq("kafka/truststore.p12"), any(), eq(3L), anyString());
    }

    @Test
    void deletesAreAnnouncedKeyByKeyBeforeTheyHappen() {
        this.service.deleteObject(BUCKET, "q3/a.csv");
        this.service.deleteObjects(BUCKET, Arrays.asList("q3/b.csv", "q3/c.csv"));

        InOrder order = inOrder(this.changes, this.store);
        order.verify(this.changes).object(BUCKET, "q3/a.csv", ObjectChanged.Reason.DELETE);
        order.verify(this.store).deleteObject(BUCKET, "q3/a.csv");
        order.verify(this.changes).object(BUCKET, "q3/b.csv", ObjectChanged.Reason.DELETE);
        order.verify(this.changes).object(BUCKET, "q3/c.csv", ObjectChanged.Reason.DELETE);
        order.verify(this.store).deleteObjects(BUCKET, Arrays.asList("q3/b.csv", "q3/c.csv"));
    }

    @Test
    void aFolderDeleteIsAnnouncedAsItsPrefix() {
        this.service.deleteFolder(BUCKET, "q3/");

        InOrder order = inOrder(this.changes, this.store);
        order.verify(this.changes).prefix(BUCKET, "q3/", ObjectChanged.Reason.DELETE_FOLDER);
        order.verify(this.store).deleteFolder(BUCKET, "q3/");
    }

    /**
     * Both prefixes. The old one because its objects are gone from there; the new one because a copy can
     * keep its etag, so text cached for an earlier object at the new path could otherwise be served for
     * the one that moved in.
     */
    @Test
    void aRenameIsAnnouncedForTheOldAndTheNewPrefix() {
        this.service.renameFolder(BUCKET, "reports/q3/", "q3-final");

        InOrder order = inOrder(this.changes, this.store);
        order.verify(this.changes).prefix(BUCKET, "reports/q3/", ObjectChanged.Reason.RENAME);
        order.verify(this.changes).prefix(BUCKET, "reports/q3-final/", ObjectChanged.Reason.RENAME);
        order.verify(this.store).renameFolder(BUCKET, "reports/q3/", "reports/q3-final/");
    }

    /** The acceptance rule: a write whose invalidation fails is reported to the caller -- and never happens. */
    @Test
    void aWriteWhoseAnnouncementCannotBeRecordedIsRefusedAndNeverReachesTheStore() {
        doThrow(new IllegalStateException("outbox unavailable")).when(this.changes).object(anyString(), anyString(), any());

        assertThatThrownBy(() -> this.service.deleteObject(BUCKET, "q3/a.csv"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("not deleted");
        verifyNoInteractions(this.store);
    }

    @Test
    void theWritesNoLongerEvictMediasCacheButStillEvictStoragesOwn() throws Exception {
        List<Method> writes = Arrays.asList(
            StorageBrowserServiceImpl.class.getMethod("uploadObject", String.class, String.class, org.springframework.web.multipart.MultipartFile.class),
            StorageBrowserServiceImpl.class.getMethod("uploadObject", String.class, String.class, InputStream.class, long.class, String.class),
            StorageBrowserServiceImpl.class.getMethod("uploadForWorkflow", process.storage.TrustedAccess.class, String.class, String.class, InputStream.class, long.class, String.class),
            StorageBrowserServiceImpl.class.getMethod("deleteObject", String.class, String.class),
            StorageBrowserServiceImpl.class.getMethod("deleteObjects", String.class, List.class),
            StorageBrowserServiceImpl.class.getMethod("deleteFolder", String.class, String.class),
            StorageBrowserServiceImpl.class.getMethod("renameFolder", String.class, String.class, String.class));
        for (Method write : writes) {
            CacheEvict evict = write.getAnnotation(CacheEvict.class);
            assertThat(evict).as(write.getName()).isNotNull();
            assertThat(evict.value()).as(write.getName()).containsExactly("fileChatMetadata");
        }
    }
}

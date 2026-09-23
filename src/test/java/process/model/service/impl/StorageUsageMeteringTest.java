package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import process.billing.MeterClient;
import process.billing.UsageEvent;
import process.config.StorageClientFactory;
import process.model.dto.ObjectMetadataDto;
import process.model.dto.ObjectSummaryDto;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.model.repository.StorageConnectionRepository;
import process.model.service.ObjectStorageService;
import process.security.TenantContext;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.io.ByteArrayInputStream;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import process.storage.ObjectChangeLog;
import process.storage.StorageRows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deletes count. A delete is told to the meter with the size of what left -- read before the
 * delete, because afterwards there is nothing to measure -- and a folder delete with every
 * object's. A platform admin in a platform bucket has no workspace and is not metered.
 */
@ExtendWith(MockitoExtension.class)
class StorageUsageMeteringTest {

    private static final long TENANT = 2905L;
    private static final String BUCKET = "medaxis-care-network";

    @Mock private LookupDataCacheService lookups;
    @Mock private StorageConnectionRepository connections;
    @Mock private StorageClientFactory factory;
    @Mock private ObjectStorageService store;
    @Mock private MeterClient meter;

    private StorageBrowserServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new StorageBrowserServiceImpl(this.lookups, this.connections, this.factory, this.store, "etl-avatar", "etl-config", Mockito.mock(ObjectChangeLog.class));
        ReflectionTestUtils.setField(this.service, "meter", this.meter);
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(1089L); connection.setTenantId(TENANT); connection.setAlias(BUCKET);
        connection.setBucketName(BUCKET); connection.setProvider(StorageProvider.S3); connection.setStatus(Status.Active);
        StorageRows.add(this.connections, connection);
        StorageRows.add(this.connections, connection);
        lenient().when(this.factory.serviceFor(any())).thenReturn(this.store);
        TenantContext.set(TENANT, "TENANT_ADMIN", 4385L, "emily@medaxis");
    }

    @AfterEach
    void tearDown() { TenantContext.clear(); }

    private List<UsageEvent> reported() {
        ArgumentCaptor<UsageEvent> captor = ArgumentCaptor.forClass(UsageEvent.class);
        verify(this.meter, Mockito.atLeast(0)).report(captor.capture());
        return captor.getAllValues();
    }

    private static ObjectMetadataDto meta(String key, long size) {
        return new ObjectMetadataDto(key, key, size, "2026-09-18T00:00:00Z", "e", "text/plain", true);
    }

    @Test
    void aDeleteCarriesTheSizeOfWhatLeft() {
        when(this.store.getObjectMetadata(BUCKET, "sales/orders.csv")).thenReturn(meta("sales/orders.csv", 2L * 1024 * 1024 * 1024));

        this.service.deleteObject(BUCKET, "sales/orders.csv");

        verify(this.store).deleteObject(BUCKET, "sales/orders.csv");
        List<UsageEvent> events = this.reported();
        assertThat(events).extracting(e -> e.meter).containsExactly("storage.ops.delete", "storage.bytes.deleted");
        UsageEvent bytes = events.get(1);
        assertThat(bytes.quantity).isEqualTo(2.0 * 1024 * 1024 * 1024);
        assertThat(bytes.unit).isEqualTo("byte");
        assertThat(bytes.tenantId).isEqualTo(TENANT);
        assertThat(bytes.actorUserId).isEqualTo(4385L);
        assertThat(bytes.subjectType).isEqualTo("object");
        assertThat(bytes.subjectId).isEqualTo(BUCKET + "/sales/orders.csv");
        assertThat(events.stream().map(e -> e.dedupeKey).collect(Collectors.toSet())).hasSize(2);
    }

    @Test
    void aFolderDeleteReportsEveryObjectUnderIt() {
        ObjectSummaryDto a = new ObjectSummaryDto(); a.setKey("old/a.csv"); a.setFolder(false); a.setSize(1024L * 1024 * 1024);
        ObjectSummaryDto b = new ObjectSummaryDto(); b.setKey("old/sub/b.csv"); b.setFolder(false); b.setSize(3L * 1024 * 1024 * 1024);
        when(this.store.listAllObjects(BUCKET, "old/", 200_000)).thenReturn(Arrays.asList(a, b));

        this.service.deleteFolder(BUCKET, "old/");

        verify(this.store).deleteFolder(BUCKET, "old/");
        List<UsageEvent> events = this.reported();
        assertThat(events.stream().filter(e -> e.meter.equals("storage.ops.delete")).count()).isEqualTo(2);
        assertThat(events.stream().filter(e -> e.meter.equals("storage.bytes.deleted")).mapToDouble(e -> e.quantity).sum()).isEqualTo(4.0 * 1024 * 1024 * 1024);
    }

    @Test
    void aSizeThatCannotBeReadStillLetsTheDeleteHappenAndCountsTheOperation() {
        when(this.store.getObjectMetadata(BUCKET, "gone.txt")).thenThrow(new RuntimeException("no such key"));

        this.service.deleteObject(BUCKET, "gone.txt");

        verify(this.store).deleteObject(BUCKET, "gone.txt");
        assertThat(this.reported()).extracting(e -> e.meter).containsExactly("storage.ops.delete");
    }

    @Test
    void anUploadAndADownloadAreWritesAndReadsWithTheirBytes() {
        this.service.uploadObject(BUCKET, "in/x.csv", new ByteArrayInputStream(new byte[10]), 512L * 1024 * 1024, "text/csv");
        List<UsageEvent> events = this.reported();
        assertThat(events).extracting(e -> e.meter).containsExactly("storage.ops.write", "storage.bytes.written");
        assertThat(events.get(1).quantity).isEqualTo(512.0 * 1024 * 1024);
    }

    @Test
    void noWorkspaceMeansNothingIsMetered() {
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        StorageConnection platform = new StorageConnection();
        platform.setStorageConnectionId(1L); platform.setAlias("etl-bucket"); platform.setBucketName("etl-bucket");
        platform.setProvider(StorageProvider.S3); platform.setStatus(Status.Active);
        StorageRows.add(this.connections, platform);
        StorageRows.add(this.connections, platform);

        this.service.deleteObject("etl-bucket", "claims/x.txt");

        verify(this.store).deleteObject("etl-bucket", "claims/x.txt");
        verify(this.meter, never()).report(any());
    }

    /**
     * The sizes a folder delete reports are looked up first, and that lookup failing must never fail
     * the delete (MIG-122): sizesUnder swallows the listing's failure on purpose. The folder still goes;
     * only the metering is poorer for it.
     */
    @Test
    void aSizeLookupThatFailsNeverBlocksTheFolderDelete() {
        Mockito.when(this.store.listAllObjects(ArgumentMatchers.eq(BUCKET),
                ArgumentMatchers.eq("q3/"), ArgumentMatchers.anyInt()))
            .thenThrow(new RuntimeException("listing timed out"));

        this.service.deleteFolder(BUCKET, "q3/");

        verify(this.store).deleteFolder(BUCKET, "q3/");
    }
}

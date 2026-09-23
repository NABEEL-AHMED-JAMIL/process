package process.engine.cron;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import process.billing.Meter;
import process.billing.MeterClient;
import process.billing.UsageEvent;
import process.config.StorageClientFactory;
import process.model.dto.ObjectSummaryDto;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.model.repository.AppUserRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.StorageConnectionRepository;
import process.model.repository.TenantRepository;
import process.model.service.ObjectStorageService;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The nightly storage measurement's deliberate swallow (MIG-122). A bucket that cannot be listed is
 * NOT measured as empty: bytesIn answers -1 and the connection is skipped, because "we could not
 * look" billed as "there is nothing there" is a zero bill for a full bucket. And a bucket past the
 * object cap still reports what was counted -- a partial sum, said in the log, never a failure.
 */
class UsageMeasurerBytesInTest {

    private final MeterClient meter = mock(MeterClient.class);
    private final StorageConnectionRepository connections = mock(StorageConnectionRepository.class);
    private final StorageClientFactory factory = mock(StorageClientFactory.class);
    private final UsageMeasurerCron cron = new UsageMeasurerCron(this.meter, mock(TenantRepository.class), this.connections,
        this.factory, mock(AppUserRepository.class), mock(SourceTaskTypeRepository.class));

    private static StorageConnection bucket(long id, String alias) {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(id);
        connection.setAlias(alias);
        connection.setBucketName(alias);
        connection.setProvider(StorageProvider.MINIO);
        connection.setStatus(Status.Active);
        connection.setTenantId(1001L);
        return connection;
    }

    private static ObjectSummaryDto object(long size) {
        return new ObjectSummaryDto("o", "o", false, size, null);
    }

    @Test
    void aBucketThatCannotBeListedIsMinusOneNotZero() {
        StorageConnection unreachable = bucket(1, "down");
        ObjectStorageService store = mock(ObjectStorageService.class);
        when(this.factory.serviceFor(unreachable)).thenReturn(store);
        when(store.listAllObjects(eq("down"), anyString(), anyInt())).thenThrow(new RuntimeException("Connection refused"));

        assertThat(this.cron.bytesIn(unreachable)).isEqualTo(-1L);
    }

    @Test
    void andTheNightSkipsItRatherThanBillingItAsEmpty() {
        StorageConnection unreachable = bucket(1, "down");
        StorageConnection counted = bucket(2, "up");
        ObjectStorageService down = mock(ObjectStorageService.class);
        ObjectStorageService up = mock(ObjectStorageService.class);
        when(this.factory.serviceFor(unreachable)).thenReturn(down);
        when(this.factory.serviceFor(counted)).thenReturn(up);
        when(down.listAllObjects(eq("down"), anyString(), anyInt())).thenThrow(new RuntimeException("Connection refused"));
        when(up.listAllObjects(eq("up"), anyString(), anyInt())).thenReturn(Arrays.asList(object(1024), object(2048)));
        when(this.connections.findByTenantIdAndStatus(1001L, Status.Active)).thenReturn(Arrays.asList(unreachable, counted));

        this.cron.measureTenant(1001L, LocalDate.of(2026, 9, 23));

        ArgumentCaptor<UsageEvent> reported = ArgumentCaptor.forClass(UsageEvent.class);
        verify(this.meter, atLeast(1)).report(reported.capture());
        List<String> storageKeys = new ArrayList<>();
        for (UsageEvent event : reported.getAllValues()) {
            if (Meter.STORAGE_GB_HOURS.key().equals(event.meter)) {
                storageKeys.add(event.dedupeKey);
            }
        }
        assertThat(storageKeys).containsExactly("measure#1001#storage#2#2026-09-23");
    }

    @Test
    void aBucketPastTheObjectCapStillReportsWhatWasCounted() {
        StorageConnection huge = bucket(3, "huge");
        ObjectStorageService store = mock(ObjectStorageService.class);
        when(this.factory.serviceFor(huge)).thenReturn(store);
        when(store.listAllObjects(eq("huge"), anyString(), anyInt()))
            .thenReturn(Collections.nCopies(UsageMeasurerCron.MAX_OBJECTS_PER_BUCKET, object(2)));

        assertThat(this.cron.bytesIn(huge)).isEqualTo(2L * UsageMeasurerCron.MAX_OBJECTS_PER_BUCKET);
    }
}

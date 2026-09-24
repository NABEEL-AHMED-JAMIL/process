package process.engine.cron;

import org.barco.platform.meter.UsageEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import process.billing.MeterClient;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.model.repository.AppUserRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.TenantRepository;
import process.storage.remote.RemoteStorageDirectory;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The nightly storage measurement's deliberate swallow (MIG-122), now that Storage does the counting
 * (MIG-193). A bucket Storage could not measure comes back -1 and the connection is skipped, because
 * "we could not look" billed as "there is nothing there" is a zero bill for a full bucket. A bucket
 * past the object cap comes back as its partial sum and is reported. Retired connections are not
 * measured at all. (The cap and the outcomes themselves: storage-service's ConnectionDirectoryTest.)
 */
class UsageMeasurerBytesInTest {

    private static final long TENANT = 1001L;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 23);

    private final MeterClient meter = mock(MeterClient.class);
    private final RemoteStorageDirectory storage = mock(RemoteStorageDirectory.class);
    private final UsageMeasurerCron cron = new UsageMeasurerCron(this.meter, mock(TenantRepository.class), this.storage,
        mock(AppUserRepository.class), mock(SourceTaskTypeRepository.class));

    private static StorageConnection bucket(long id, String alias, Status status) {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(id);
        connection.setAlias(alias);
        connection.setBucketName(alias);
        connection.setProvider(StorageProvider.MINIO);
        connection.setStatus(status);
        connection.setTenantId(TENANT);
        return connection;
    }

    private List<UsageEvent> reported(int times) {
        ArgumentCaptor<UsageEvent> events = forClass(UsageEvent.class);
        verify(this.meter, times(times)).report(events.capture());
        return events.getAllValues();
    }

    @Test
    void aBucketStorageCouldNotMeasureIsSkippedNotBilledAsEmpty() {
        StorageConnection unreachable = bucket(1L, "unreachable", Status.Active);
        when(this.storage.workspace(TENANT)).thenReturn(Arrays.asList(unreachable));
        when(this.storage.bytesIn(unreachable)).thenReturn(-1L);

        assertThat(this.cron.measureTenant(TENANT, DAY)).isZero();
        verify(this.meter, never()).report(any());
    }

    @Test
    void aPartialSumPastTheCapIsStillReportedAndAHealthyBucketBeside() {
        StorageConnection huge = bucket(1L, "huge", Status.Active);
        StorageConnection small = bucket(2L, "small", Status.Active);
        StorageConnection unreachable = bucket(3L, "unreachable", Status.Active);
        when(this.storage.workspace(TENANT)).thenReturn(Arrays.asList(huge, small, unreachable));
        when(this.storage.bytesIn(huge)).thenReturn(1_000_000L);
        when(this.storage.bytesIn(small)).thenReturn(1024L * 1024 * 1024);
        when(this.storage.bytesIn(unreachable)).thenReturn(-1L);

        assertThat(this.cron.measureTenant(TENANT, DAY)).isEqualTo(2);
        List<UsageEvent> events = this.reported(2);
        assertThat(events).extracting(e -> e.subjectId).containsExactly("huge", "small");
        assertThat(events.get(1).quantity).as("one GB for a whole day").isEqualTo(24.0);
        assertThat(events.get(0).dedupeKey).isEqualTo("measure#1001#storage#1#" + DAY);
    }

    @Test
    void aRetiredConnectionIsNotMeasured() {
        StorageConnection retired = bucket(1L, "old", Status.Inactive);
        when(this.storage.workspace(TENANT)).thenReturn(Arrays.asList(retired));

        assertThat(this.cron.measureTenant(TENANT, DAY)).isZero();
        verify(this.storage, never()).bytesIn(retired);
    }
}

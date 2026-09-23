package process.storage.remote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import process.analytics.AnalyticsException;
import process.analytics.DatasetRef;
import process.analytics.DatasetResolver;
import process.billing.MeterClient;
import process.billing.UsageEvent;
import process.config.StorageClientFactory;
import process.engine.cron.UsageMeasurerCron;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.model.repository.AppUserRepository;
import process.model.repository.SourceTaskTypeRepository;
import process.model.repository.StorageConnectionRepository;
import process.model.repository.TenantRepository;
import process.security.TenantContext;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The consumers' remote branches: once Storage owns the connections, none of them reads process's table. */
class RemoteConsumersTest {

    private final StorageConnectionRepository table = mock(StorageConnectionRepository.class);
    private final RemoteStorageDirectory remote = mock(RemoteStorageDirectory.class);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static StorageConnection connection(long id, String alias, StorageProvider provider, String bucket, Status status) {
        StorageConnection c = new StorageConnection();
        c.setStorageConnectionId(id);
        c.setTenantId(2901L);
        c.setAlias(alias);
        c.setProvider(provider);
        c.setBucketName(bucket);
        c.setStatus(status);
        return c;
    }

    @Test
    void analyticsReadsTheConnectionStorageVends() throws Exception {
        DatasetResolver resolver = new DatasetResolver(this.table);
        ReflectionTestUtils.setField(resolver, "remote", this.remote);
        when(this.remote.vendForCaller("reports")).thenReturn(Optional.of(
            connection(1107L, "reports", StorageProvider.MINIO, "acme-reports", Status.Active)));

        DatasetRef ref = resolver.resolve("reports", "q3/sales.csv");

        assertThat(ref.getBucket()).isEqualTo("acme-reports");
        assertThat(ref.getConnection().getStorageConnectionId()).isEqualTo(1107L);
        verifyNoInteractions(this.table);
    }

    @Test
    void aConnectionStorageWillNotVendIsNotFoundInTheOneWording() {
        DatasetResolver resolver = new DatasetResolver(this.table);
        ReflectionTestUtils.setField(resolver, "remote", this.remote);
        when(this.remote.vendForCaller("archive")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve("archive", "a.csv"))
            .isInstanceOf(AnalyticsException.class).hasMessage("Storage connection not found.");
        verifyNoInteractions(this.table);
    }

    @Test
    void theNightlyMeasurementAsksStorageForTheWorkspacesActiveBucketsAndTheirSizes() {
        MeterClient meter = mock(MeterClient.class);
        UsageMeasurerCron cron = new UsageMeasurerCron(meter, mock(TenantRepository.class), this.table,
            mock(StorageClientFactory.class), mock(AppUserRepository.class), mock(SourceTaskTypeRepository.class));
        ReflectionTestUtils.setField(cron, "remote", this.remote);
        StorageConnection measured = connection(1107L, "reports", StorageProvider.MINIO, "acme-reports", Status.Active);
        StorageConnection failing = connection(1108L, "flaky", StorageProvider.S3, "flaky", Status.Active);
        StorageConnection retired = connection(1109L, "old", StorageProvider.MINIO, "old", Status.Inactive);
        when(this.remote.workspace(2901L)).thenReturn(Arrays.asList(measured, failing, retired));
        when(this.remote.bytesIn(measured)).thenReturn(1024L * 1024 * 1024);
        when(this.remote.bytesIn(failing)).thenReturn(-1L);

        int events = ReflectionTestUtils.invokeMethod(cron, "measureTenant", 2901L, LocalDate.of(2026, 9, 23));

        assertThat(events).isEqualTo(1);
        verify(meter, times(1)).report(any(UsageEvent.class));
        verify(this.remote, never()).bytesIn(retired);
        verifyNoInteractions(this.table);
    }
}

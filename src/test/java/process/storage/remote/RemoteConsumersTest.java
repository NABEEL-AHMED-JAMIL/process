package process.storage.remote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import process.analytics.AnalyticsException;
import process.analytics.DatasetRef;
import process.analytics.DatasetResolver;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.security.TenantContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Analytics reads the connections storage-service vends (MIG-185); there is no table left to read. */
class RemoteConsumersTest {

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
        DatasetResolver resolver = new DatasetResolver(this.remote);
        when(this.remote.vendForCaller("reports")).thenReturn(Optional.of(
            connection(1107L, "reports", StorageProvider.MINIO, "acme-reports", Status.Active)));

        DatasetRef ref = resolver.resolve("reports", "q3/sales.csv");

        assertThat(ref.getBucket()).isEqualTo("acme-reports");
        assertThat(ref.getConnection().getStorageConnectionId()).isEqualTo(1107L);
    }

    @Test
    void aConnectionStorageWillNotVendIsNotFoundInTheOneWording() {
        DatasetResolver resolver = new DatasetResolver(this.remote);
        when(this.remote.vendForCaller("archive")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve("archive", "a.csv"))
            .isInstanceOf(AnalyticsException.class).hasMessage("Storage connection not found.");
    }

}

package process.analytics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.model.repository.StorageConnectionRepository;
import process.security.TenantContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The gate every dataset has to pass through.
 *
 * DatasetRef's constructor is package-private and this class is its only caller, so these are the
 * checks that cannot be skipped by a future call site. That makes them worth pinning hard: a
 * regression here is not a wrong answer on a screen, it is the analytics engine pointed somewhere
 * it should not be.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class DatasetResolverTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 2002L;

    @Mock private StorageConnectionRepository storageConnectionRepository;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private DatasetResolver resolver() {
        return new DatasetResolver(this.storageConnectionRepository);
    }

    private StorageConnection connection(Long tenantId, StorageProvider provider) {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(7L);
        connection.setTenantId(tenantId);
        connection.setProvider(provider);
        connection.setAlias("store");
        connection.setBucketName("etl-bucket");
        connection.setStatus(Status.Active);
        return connection;
    }

    private void exists(StorageConnection connection) {
        lenient().when(this.storageConnectionRepository.findByAlias(any()))
            .thenReturn(Optional.of(connection));
    }

    // ---- the happy path, so a refusal below is a refusal and not a broken fixture -------------

    @Test
    void resolvesAFileTheCallersOwnTenantOwns() throws Exception {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "user@acme.test");
        exists(connection(TENANT_A, StorageProvider.MINIO));

        DatasetRef dataset = resolver().resolve("store", "etl-demo/sales.csv");

        assertThat(dataset.getFormat()).isEqualTo(DatasetRef.Format.CSV);
        assertThat(dataset.url()).isEqualTo("s3://etl-bucket/etl-demo/sales.csv");
        assertThat(dataset.isMultiFile()).isFalse();
    }

    @Test
    void treatsAGlobAsOneMultiFileDataset() throws Exception {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "user@acme.test");
        exists(connection(TENANT_A, StorageProvider.S3));

        DatasetRef dataset = resolver().resolve("store", "orders/2026/*.parquet");

        assertThat(dataset.isMultiFile()).isTrue();
        assertThat(dataset.getFormat()).isEqualTo(DatasetRef.Format.PARQUET);
        // union_by_name, so a folder whose files gained a column still reads as one table.
        assertThat(dataset.scanExpression())
            .contains("read_parquet(")
            .contains("union_by_name=true")
            .contains("filename=true");
    }

    // ---- tenancy ------------------------------------------------------------------------------

    @Test
    void refusesAConnectionBelongingToAnotherWorkspace() {
        TenantContext.set(TENANT_B, "TENANT_USER", 2L, "user@other.test");
        exists(connection(TENANT_A, StorageProvider.MINIO));

        assertThatThrownBy(() -> resolver().resolve("store", "etl-demo/sales.csv"))
            .isInstanceOf(AnalyticsException.class)
            // Deliberately the SAME wording as a connection that does not exist: telling the two
            // apart would let a caller walk the id space and learn what other tenants own.
            .hasMessage("Storage connection not found.");
    }

    @Test
    void refusesAConnectionThatDoesNotExistWithTheSameWords() {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "user@acme.test");
        when(this.storageConnectionRepository.findByAlias(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver().resolve("store", "etl-demo/sales.csv"))
            .isInstanceOf(AnalyticsException.class)
            .hasMessage("Storage connection not found.");
    }

    @Test
    void refusesASoftDeletedConnection() {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "user@acme.test");
        StorageConnection deleted = connection(TENANT_A, StorageProvider.MINIO);
        deleted.setStatus(Status.Delete);
        exists(deleted);

        assertThatThrownBy(() -> resolver().resolve("store", "etl-demo/sales.csv"))
            .isInstanceOf(AnalyticsException.class)
            .hasMessage("Storage connection not found.");
    }

    @Test
    void refusesAPlatformOwnedConnectionToATenantUser() {
        // A storage_connection with no tenant is platform-owned, and the object browser refuses it
        // to everyone but a platform admin (StorageBrowserServiceImpl.isPlatformBucket :464,
        // collectBuckets :105-107). This resolver reached for TenantOwnership.isVisibleToCaller,
        // which PUBLISHES a null-tenant row to every tenant -- correct for the shared catalogues
        // it was written for, and a cross-tenant read here. The two screens share these connection
        // rows, so the analytics reader must never be the more permissive of the pair.
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "user@acme.test");
        exists(connection(null, StorageProvider.MINIO));

        assertThatThrownBy(() -> resolver().resolve("store", "etl-demo/sales.csv"))
            .isInstanceOf(AnalyticsException.class)
            .hasMessage("Storage connection not found.");
    }

    @Test
    void readsAPlatformOwnedConnectionForAPlatformAdmin() throws Exception {
        // The other half of the rule, and the control that keeps the test above honest: refusing
        // a platform row to everybody would pass that assertion while breaking the admin.
        TenantContext.set(null, "PLATFORM_ADMIN", 9L, "admin@platform.test");
        exists(connection(null, StorageProvider.MINIO));

        DatasetRef dataset = resolver().resolve("store", "etl-demo/sales.csv");

        assertThat(dataset.getBucket()).isEqualTo("etl-bucket");
    }

    @Test
    void refusesADeactivatedConnection() {
        // Not merely not-Deleted: the picker offers Active connections only (collectBuckets :104),
        // so a looser filter here keeps a deactivated connection serving data through an API where
        // nobody can see that it is still live.
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "user@acme.test");
        StorageConnection inactive = connection(TENANT_A, StorageProvider.MINIO);
        inactive.setStatus(Status.Inactive);
        exists(inactive);

        assertThatThrownBy(() -> resolver().resolve("store", "etl-demo/sales.csv"))
            .isInstanceOf(AnalyticsException.class)
            .hasMessage("Storage connection not found.");
    }

    // ---- the path itself ----------------------------------------------------------------------

    @Test
    void refusesAPathThatClimbsOutOfTheBucket() {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "user@acme.test");
        exists(connection(TENANT_A, StorageProvider.MINIO));

        assertThatThrownBy(() -> resolver().resolve("store", "../../etc/passwd.csv"))
            .isInstanceOf(AnalyticsException.class)
            .hasMessageContaining("\"..\"");
    }

    @Test
    void refusesAPathCarryingAQuoteThatWouldEndTheSqlLiteral() {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "user@acme.test");
        exists(connection(TENANT_A, StorageProvider.MINIO));

        // The scan expression interpolates the path into SQL. The allow-list is what stops this
        // reaching it, not the escaping downstream -- both, but this one first.
        assertThatThrownBy(() ->
            resolver().resolve("store", "a.csv'); DROP TABLE x; --"))
            .isInstanceOf(AnalyticsException.class)
            .hasMessageContaining("characters this reader does not accept");
    }

    @Test
    void takesTheBucketFromTheConnectionRecord_notFromTheRequest() throws Exception {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "user@acme.test");
        StorageConnection connection = connection(TENANT_A, StorageProvider.MINIO);
        connection.setAlias("reports-store");
        connection.setBucketName("locked-bucket");
        exists(connection);

        DatasetRef dataset = resolver().resolve("reports-store", "sales.csv");

        // The strongest property this class has, and the reason the API takes no bucket at all:
        // "use these credentials against a different bucket" is not a request that can be made.
        assertThat(dataset.getBucket()).isEqualTo("locked-bucket");
        assertThat(dataset.url()).isEqualTo("s3://locked-bucket/sales.csv");
    }

    @Test
    void fallsBackToTheAliasWhenTheConnectionNamesNoBucket() throws Exception {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "user@acme.test");
        StorageConnection connection = connection(TENANT_A, StorageProvider.MINIO);
        connection.setAlias("etl-bucket");
        connection.setBucketName(null);
        exists(connection);

        // Matches what StorageBrowserServiceImpl does, so the two screens agree on where a
        // connection points when its bucket field was never filled in.
        assertThat(resolver().resolve("etl-bucket", "a.csv").getBucket()).isEqualTo("etl-bucket");
    }

    @Test
    void refusesAFileTypeItCannotRead() {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "user@acme.test");
        exists(connection(TENANT_A, StorageProvider.MINIO));

        assertThatThrownBy(() -> resolver().resolve("store", "notes/report.pdf"))
            .isInstanceOf(AnalyticsException.class)
            .hasMessageContaining("does not read this file type");
    }

    @Test
    void refusesAProviderDuckDbCannotRead() {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "user@acme.test");
        exists(connection(TENANT_A, StorageProvider.FTP));

        // FTP is object storage to the browser but not to the query engine, and saying so by
        // name beats failing later inside a scan.
        assertThatThrownBy(() -> resolver().resolve("store", "sales.csv"))
            .isInstanceOf(AnalyticsException.class)
            .hasMessageContaining("reads object storage");
    }

    @Test
    void refusesAzureAsUnverifiedRatherThanAsUnsupported() {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "user@acme.test");
        exists(connection(TENANT_A, StorageProvider.AZURE));

        // The value of this refusal is entirely in the message being TRUE. Azure is not an
        // unsupported provider -- DuckDbSessionFactory :130-134 branches for it and DatasetRef
        // emits azure:// -- it is an unrun one, and either tidier phrasing ("not supported", or
        // a bare failure) would leave the user with a false picture of where this module stands.
        // Pinned word for word, because a well-meaning rewording is precisely how the honesty
        // gets lost once the reason has faded from memory.
        assertThatThrownBy(() -> resolver().resolve("store", "etl-demo/sales.csv"))
            .isInstanceOf(AnalyticsException.class)
            .hasMessage("Analytics Studio has not been verified against Azure Blob yet, so it "
                + "will not read this connection.");
    }

    @Test
    void stillResolvesTheTwoProvidersThatWereVerified() throws Exception {
        // The positive control for the test above, and the reason it means anything: a gate that
        // refused every object store would satisfy that assertion perfectly while making the
        // module read nothing at all. S3 and MinIO are the two that were verified live, so they
        // are the two that must still come through here.
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "user@acme.test");

        exists(connection(TENANT_A, StorageProvider.S3));
        assertThat(resolver().resolve("store", "etl-demo/sales.csv").url())
            .isEqualTo("s3://etl-bucket/etl-demo/sales.csv");

        exists(connection(TENANT_A, StorageProvider.MINIO));
        assertThat(resolver().resolve("store", "etl-demo/sales.csv").url())
            .isEqualTo("s3://etl-bucket/etl-demo/sales.csv");
    }

    @Test
    void asksForWhatIsMissingRatherThanFailingGenerically() {
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "user@acme.test");

        assertThatThrownBy(() -> resolver().resolve(null, "a.csv"))
            .hasMessageContaining("storage connection");
        assertThatThrownBy(() -> resolver().resolve("   ", "a.csv"))
            .hasMessageContaining("storage connection");
        assertThatThrownBy(() -> resolver().resolve("store", ""))
            .hasMessageContaining("file or a folder pattern");
    }

    @Test
    void detectsEveryFormatItClaimsToRead() {
        assertThat(DatasetRef.Format.of("a/b/orders.CSV")).isEqualTo(DatasetRef.Format.CSV);
        assertThat(DatasetRef.Format.of("orders.tsv")).isEqualTo(DatasetRef.Format.TSV);
        assertThat(DatasetRef.Format.of("events.json")).isEqualTo(DatasetRef.Format.JSON);
        assertThat(DatasetRef.Format.of("events.jsonl")).isEqualTo(DatasetRef.Format.JSON);
        assertThat(DatasetRef.Format.of("events.ndjson")).isEqualTo(DatasetRef.Format.JSON);
        assertThat(DatasetRef.Format.of("orders.parquet")).isEqualTo(DatasetRef.Format.PARQUET);
        assertThat(DatasetRef.Format.of("orders")).isNull();
        assertThat(DatasetRef.Format.of(null)).isNull();
    }
}

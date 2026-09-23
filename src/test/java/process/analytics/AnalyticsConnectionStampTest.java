package process.analytics;

import org.junit.jupiter.api.Test;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.AnalyticsQuery;
import process.model.pojo.AnalyticsQueryRun;
import process.model.pojo.StorageConnection;
import process.model.repository.StorageConnectionRepository;
import process.storage.StorageRows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Analytics rows carry the storage connection's id beside its alias (MIG-53 part b). An alias is only
 * unique within a workspace now, and Storage is leaving process, so a saved query, dataset, analysis,
 * run or benchmark names the exact connection it was written against -- stamped on every write by one
 * listener, so no write path can forget, with the same rule Storage resolves by: the row's own
 * workspace's connection, else the platform's.
 */
class AnalyticsConnectionStampTest {

    private final StorageConnectionRepository repository = mock(StorageConnectionRepository.class);
    private final AnalyticsConnectionStamp stamp = new AnalyticsConnectionStamp();

    /** Installed as production installs it; the rule is the lookup's (JdbcConnectionIdResolver is tested on Postgres). */
    @org.junit.jupiter.api.BeforeEach
    void install() {
        AnalyticsConnectionStamp.use((tenantId, alias) -> new process.storage.StorageConnectionLookup(this.repository)
            .forRow(tenantId, alias).map(StorageConnection::getStorageConnectionId).orElse(null));
    }

    @org.junit.jupiter.api.AfterEach
    void uninstall() {
        AnalyticsConnectionStamp.use(null);
    }

    private StorageConnection row(long id, Long tenant, String alias) {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(id);
        connection.setTenantId(tenant);
        connection.setAlias(alias);
        connection.setProvider(StorageProvider.MINIO);
        connection.setStatus(Status.Active);
        return StorageRows.add(this.repository, connection);
    }

    @Test
    void aRowNamesItsOwnWorkspacesConnectionOverThePlatformsAndNotAnotherWorkspaces() {
        this.row(1L, 1001L, "exports");
        this.row(2L, 2002L, "exports");
        this.row(3L, null, "etl-bucket");
        AnalyticsQuery query = new AnalyticsQuery();
        query.setTenantId(1001L);
        query.setConnectionAlias("exports");
        query.setSecondConnectionAlias("etl-bucket");

        this.stamp.stamp(query);

        assertThat(query.getStorageConnectionId()).isEqualTo(1L);
        assertThat(query.getSecondStorageConnectionId()).isEqualTo(3L);
    }

    @Test
    void anAliasThatNamesNothingLeavesTheIdEmptyRatherThanGuessing() {
        this.row(2L, 2002L, "exports");
        AnalyticsQueryRun run = new AnalyticsQueryRun();
        run.setTenantId(1001L);
        run.setConnectionAlias("exports");

        this.stamp.stamp(run);

        assertThat(run.getStorageConnectionId()).isNull();
        assertThat(run.getSecondStorageConnectionId()).isNull();
    }

    @Test
    void changingTheAliasRestampsTheId() {
        this.row(1L, 1001L, "exports");
        this.row(4L, 1001L, "archive");
        AnalyticsQuery query = new AnalyticsQuery();
        query.setTenantId(1001L);
        query.setConnectionAlias("exports");
        this.stamp.stamp(query);

        query.setConnectionAlias("archive");
        this.stamp.stamp(query);

        assertThat(query.getStorageConnectionId()).isEqualTo(4L);
    }

    /**
     * A row with no workspace -- a platform admin's run -- names the platform's connection, else the one
     * workspace's that uses the name, as the admin's own resolution does; ambiguous stays empty, since
     * a write must never fail over this.
     */
    @Test
    void aPlatformAdminsRowNamesTheOneWorkspaceThatUsesTheNameAndNothingWhenSeveralDo() {
        this.row(5L, 2905L, "worker-store");
        this.row(1L, 1001L, "exports");
        this.row(2L, 2002L, "exports");
        AnalyticsQueryRun single = new AnalyticsQueryRun();
        single.setConnectionAlias("worker-store");
        AnalyticsQueryRun ambiguous = new AnalyticsQueryRun();
        ambiguous.setConnectionAlias("exports");

        this.stamp.stamp(single);
        this.stamp.stamp(ambiguous);

        assertThat(single.getStorageConnectionId()).isEqualTo(5L);
        assertThat(ambiguous.getStorageConnectionId()).isNull();
    }
}

package process.storage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.config.StorageClientFactory;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.model.repository.StorageConnectionRepository;
import process.model.service.ObjectStorageService;
import process.model.service.impl.LookupDataCacheService;
import process.model.service.impl.StorageBrowserServiceImpl;
import process.security.TenantContext;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Aliases are unique per workspace, not platform-wide (MIG-53, P4), and every lookup says whose alias
 * it means. With a platform-wide alias, the name a caller sends as "bucket" could resolve through a
 * connection that is not theirs -- the recorded alias/bucket confusion -- and a tenant could name
 * another tenant's connection wherever an alias is accepted.
 */
class TenantQualifiedAliasTest {

    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 2002L;

    private final List<StorageConnection> rows = new ArrayList<>();
    private final StorageConnectionRepository repository = mock(StorageConnectionRepository.class);
    private final StorageClientFactory factory = mock(StorageClientFactory.class);
    private final ObjectStorageService store = mock(ObjectStorageService.class);
    private StorageConnectionLookup lookup;
    private StorageBrowserServiceImpl storage;

    @BeforeEach
    void setUp() {
        lenient().when(this.repository.findByTenantIdAndAlias(any(), anyString())).thenAnswer(call -> this.rows.stream()
            .filter(r -> call.getArgument(0).equals(r.getTenantId()) && r.getAlias().equals(call.getArgument(1))).findFirst());
        lenient().when(this.repository.findByTenantIdIsNullAndAlias(anyString())).thenAnswer(call -> this.rows.stream()
            .filter(r -> r.getTenantId() == null && r.getAlias().equals(call.getArgument(0))).findFirst());
        lenient().when(this.repository.findAllByAlias(anyString())).thenAnswer(call -> {
            List<StorageConnection> found = new ArrayList<>();
            this.rows.stream().filter(r -> r.getAlias().equals(call.getArgument(0))).forEach(found::add);
            return found;
        });
        lenient().when(this.factory.serviceFor(any())).thenReturn(this.store);
        this.lookup = new StorageConnectionLookup(this.repository);
        this.storage = new StorageBrowserServiceImpl(mock(LookupDataCacheService.class), this.repository, this.factory, this.store,
            "etl-avatar", "etl-config", mock(ObjectChangeLog.class));
        this.row(7L, null, "etl-config", "etl-config");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private StorageConnection row(long id, Long tenant, String alias, String bucketName) {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(id);
        connection.setTenantId(tenant);
        connection.setAlias(alias);
        connection.setBucketName(bucketName);
        connection.setProvider(StorageProvider.MINIO);
        connection.setStatus(Status.Active);
        this.rows.add(connection);
        return connection;
    }

    @Test
    void twoWorkspacesMayUseTheSameAliasAndEachReachesItsOwn() {
        StorageConnection a = this.row(1L, TENANT_A, "exports", "tenant-a-real-bucket");
        StorageConnection b = this.row(2L, TENANT_B, "exports", "tenant-b-real-bucket");

        TenantContext.set(TENANT_A, "TENANT_USER", 61L, "a@medaxis.example");
        this.storage.getObjectMetadata("exports", "q3.csv");
        TenantContext.set(TENANT_B, "TENANT_USER", 62L, "b@carebridge.example");
        this.storage.getObjectMetadata("exports", "q3.csv");

        verify(this.factory).serviceFor(a);
        verify(this.factory).serviceFor(b);
    }

    /** The incident: a name B sends as "bucket" must never resolve through A's connection. */
    @Test
    void aNameAnotherWorkspaceUsesIsUnknownHereAndItsClientIsNeverBuilt() {
        StorageConnection a = this.row(1L, TENANT_A, "shared", "a-real");
        this.row(2L, TENANT_B, "b-docs", "shared");
        TenantContext.set(TENANT_B, "TENANT_USER", 62L, "b@carebridge.example");

        assertThatThrownBy(() -> this.storage.getObjectMetadata("shared", "q3.csv"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageStartingWith("Unknown bucket: shared.");
        verify(this.factory, never()).serviceFor(a);
    }

    @Test
    void aPlatformAdminReachesAWorkspacesConnectionWhenTheNameIsUnambiguous() {
        StorageConnection a = this.row(1L, TENANT_A, "a-only", "a-real");
        TenantContext.set(null, "PLATFORM_ADMIN", 1000L, "admin@platform.local");

        this.storage.getObjectMetadata("a-only", "q3.csv");

        verify(this.factory).serviceFor(a);
    }

    @Test
    void aPlatformAdminIsToldWhenANameBelongsToSeveralWorkspaces() {
        this.row(1L, TENANT_A, "exports", "a-real");
        this.row(2L, TENANT_B, "exports", "b-real");
        TenantContext.set(null, "PLATFORM_ADMIN", 1000L, "admin@platform.local");

        assertThatThrownBy(() -> this.storage.getObjectMetadata("exports", "q3.csv"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("more than one workspace");
        verify(this.factory, never()).serviceFor(any());
    }

    /** A trusted read carries the tenant of the row it came from, so a workspace's own alias resolves. */
    @Test
    void aTrustedReadResolvesWithinTheTenantItNames() {
        StorageConnection a = this.row(1L, TENANT_A, "certs", "a-real");
        this.row(2L, TENANT_B, "certs", "b-real");

        this.storage.readForWorkflow(TrustedAccess.of(TrustedCaller.KAFKA_TEMPLATE_PROVIDER, "profile 41").forTenant(TENANT_A),
            "certs", "truststore.p12");

        verify(this.factory).serviceFor(a);
    }

    @Test
    void aTrustedReadWithNoTenantReachesOnlyThePlatformsConnection() {
        this.row(1L, TENANT_A, "certs", "a-real");

        assertThatThrownBy(() -> this.storage.readForWorkflow(TrustedAccess.of(TrustedCaller.KAFKA_SECRETS, "x"), "certs", "k"))
            .isInstanceOf(IllegalArgumentException.class);
        this.storage.readForWorkflow(TrustedAccess.of(TrustedCaller.KAFKA_SECRETS, "x"), "etl-config", "kafka-secrets/k");
        verify(this.factory).serviceFor(this.rows.get(0));
    }

    // ---- which names a new connection may take ------------------------------------------------------

    @Test
    void aWorkspaceMayTakeANameAnotherWorkspaceUsesButNotItsOwnTwiceNorAPlatformName() {
        this.row(1L, TENANT_A, "exports", "a-real");

        assertThat(this.lookup.aliasUnavailable(TENANT_B, "exports", null)).as("another workspace's name").isFalse();
        assertThat(this.lookup.aliasUnavailable(TENANT_A, "exports", null)).as("its own, twice").isTrue();
        assertThat(this.lookup.aliasUnavailable(TENANT_A, "exports", 1L)).as("its own row, renamed to itself").isFalse();
        assertThat(this.lookup.aliasUnavailable(TENANT_A, "etl-config", null)).as("a platform name").isTrue();
    }

    /** A platform name must not shadow a workspace's: the platform takes only names nobody uses. */
    @Test
    void thePlatformMayNotTakeANameAWorkspaceUses() {
        this.row(1L, TENANT_A, "exports", "a-real");

        assertThat(this.lookup.aliasUnavailable(null, "exports", null)).isTrue();
        assertThat(this.lookup.aliasUnavailable(null, "fresh-name", null)).isFalse();
    }

    /** Structural: no lookup by alias alone survives, so nobody can reintroduce the confusion by accident. */
    @Test
    void theRepositoryHasNoLookupByAliasAlone() {
        List<String> names = new ArrayList<>();
        for (Method method : StorageConnectionRepository.class.getDeclaredMethods()) {
            names.add(method.getName());
        }
        assertThat(names).doesNotContain("findByAlias", "findByAliasAndStatus")
            .contains("findByTenantIdAndAlias", "findByTenantIdIsNullAndAlias", "findAllByAlias");
    }

    /**
     * The entity must not declare a platform-wide alias constraint. In dev, Hibernate's ddl-auto=update
     * re-created uq_storage_connection_alias on every startup, right after Liquibase (V55) dropped it,
     * which quietly restored the platform-wide rule. The per-workspace index is Liquibase's.
     */
    @Test
    void theEntityDeclaresNoPlatformWideAliasConstraint() {
        Table table = StorageConnection.class.getAnnotation(Table.class);
        for (UniqueConstraint unique : table.uniqueConstraints()) {
            assertThat(unique.columnNames()).as(unique.name()).isNotEqualTo(new String[] {"alias"});
        }
    }
}

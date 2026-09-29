package process.tenancy;

import org.barco.platform.contract.RowSecurityCatalog;
import org.barco.platform.tenancy.RowSecurity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import process.security.TenantContext;

import java.sql.Connection;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static process.tenancy.CoreProbeFixture.A;
import static process.tenancy.CoreProbeFixture.B;
import static process.tenancy.CoreProbeFixture.B_JOB;
import static process.tenancy.CoreProbeFixture.PLATFORM_PROFILE;

/**
 * MIG-258 on etl_job as the changelog builds it (V181), with the probe fixture's three workspaces and the platform's
 * rows: every tenant table is guarded for process_app, and a raw native query run as process_app -- no tenant in its
 * SQL -- sees no workspace's rows without the session setting, only A's with A, and every one with the all-tenants
 * flag. The five tables that keep platform rows every workspace reads show those rows to every session, and never
 * let a workspace write one.
 */
class CoreRowSecurityPostgresTest {

    static final String APP = "process_app";
    static final Set<String> PLATFORM_READABLE = new HashSet<>(Arrays.asList("kafka_connection_profile", "source_task_type",
        "storage_connection", "lookup_data", "user_directory"));

    private static CoreProbeFixture world;

    @BeforeAll
    static void create() throws Exception {
        world = CoreProbeFixture.create("core_rls");
    }

    @AfterAll
    static void close() throws Exception {
        if (world != null) world.close();
    }

    @Test
    void everyTenantTableIsGuardedForProcessApp() throws Exception {
        try (Connection c = world.db.pool().getConnection()) {
            assertThat(RowSecurityCatalog.tenantTables(c)).hasSizeGreaterThanOrEqualTo(42);
            assertThat(RowSecurityCatalog.unguarded(c, APP)).isEmpty();
        }
    }

    /** The world holds both workspaces in the tables a request reaches, so the checks below cannot pass by emptiness. */
    @Test
    void theWorldHoldsBothWorkspacesWhereItMatters() throws Exception {
        JdbcTemplate login = world.db.jdbc();
        for (String table : Arrays.asList("source_job", "scheduler", "job_queue", "job_audit_logs", "source_task", "source_task_type",
            "kafka_connection_profile", "pipeline", "pipeline_config", "task_reference", "tenant",
            "app_user")) {
            assertThat(login.queryForList("SELECT DISTINCT tenant_id FROM " + table, Long.class)).as(table).contains(A, B);
        }
    }

    @Test
    void withNoWorkspaceSetANativeQuerySeesOnlyPlatformRows() throws Exception {
        try (Connection c = world.db.pool().getConnection()) {
            Map<String, Map<Long, Long>> seen = RowSecurityCatalog.seenAs(c, APP, null, false);
            assertThat(RowSecurityCatalog.foreignRows(seen, null)).isEmpty();
            seen.forEach((table, counts) -> {
                if (!PLATFORM_READABLE.contains(table)) {
                    assertThat(counts).as(table).isEmpty();
                }
            });
            assertThat(seen.get("kafka_connection_profile")).containsOnlyKeys((Long) null);
        }
    }

    @Test
    void withWorkspaceASetANativeQuerySeesOnlyAAndThePlatformsSharedRows() throws Exception {
        try (Connection c = world.db.pool().getConnection()) {
            Map<String, Map<Long, Long>> seen = RowSecurityCatalog.seenAs(c, APP, A, false);
            assertThat(RowSecurityCatalog.foreignRows(seen, A)).isEmpty();
            seen.forEach((table, counts) -> {
                if (!PLATFORM_READABLE.contains(table)) {
                    assertThat(counts.keySet()).as(table).allMatch(t -> t != null && t == A);
                }
            });
            assertThat(seen.get("source_job")).containsOnlyKeys(A);
            assertThat(seen.get("kafka_connection_profile")).containsOnlyKeys(null, A);
        }
    }

    @Test
    void theAllTenantsFlagSeesEveryWorkspace() throws Exception {
        try (Connection c = world.db.pool().getConnection()) {
            Map<String, Map<Long, Long>> seen = RowSecurityCatalog.seenAs(c, APP, null, true);
            assertThat(seen.get("source_job")).containsKeys(A, B);
            assertThat(seen.get("job_queue")).containsKeys(A, B);
        }
    }

    /** Through the application's own pool: the caller decides, whatever the SQL says. */
    @Test
    void theApplicationsPoolFollowsTheCaller() {
        JdbcTemplate app = world.db.appJdbc();
        try {
            TenantContext.set(A, "TENANT_ADMIN", CoreProbeFixture.ADMIN_A, "alice@acme.example");
            assertThat(app.queryForObject("SELECT current_user", String.class)).isEqualTo(APP);
            assertThat(app.queryForList("SELECT DISTINCT tenant_id FROM source_job", Long.class)).containsOnly(A);
            assertThat(app.queryForObject("SELECT count(*) FROM source_job WHERE job_id = ?", Long.class, B_JOB)).isZero();
            assertThat(RowSecurity.acrossTenants("test", () -> app.queryForObject("SELECT count(*) FROM source_job WHERE job_id = ?",
                Long.class, B_JOB))).isEqualTo(1L);
            assertThat(RowSecurity.forTenant(B, () -> app.queryForList("SELECT DISTINCT tenant_id FROM source_job", Long.class)))
                .containsOnly(B);
            TenantContext.set(null, "PLATFORM_ADMIN", CoreProbeFixture.ROOT, "root@platform.example");
            assertThat(app.queryForList("SELECT DISTINCT tenant_id FROM source_job", Long.class)).contains(A, B);
            TenantContext.clear();
            assertThat(app.queryForObject("SELECT count(*) FROM source_job", Long.class)).isZero();
        } finally {
            TenantContext.clear();
        }
    }

    /** WITH CHECK: A cannot write into B, nor write a platform row, nor change one. */
    @Test
    void aWorkspaceCannotWriteAnotherWorkspacesRowsOrThePlatforms() {
        JdbcTemplate app = world.db.appJdbc();
        try {
            TenantContext.set(A, "TENANT_ADMIN", CoreProbeFixture.ADMIN_A, "alice@acme.example");
            assertThatThrownBy(() -> app.update("UPDATE source_job SET tenant_id = ? WHERE tenant_id = ?", B, A))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("row-level security");
            assertThatThrownBy(() -> app.update("INSERT INTO kafka_connection_profile (tenant_id, profile_name, bootstrap_servers, "
                + "security_protocol, status) VALUES (NULL, 'planted', 'x:9092', 'PLAINTEXT', 'Active')"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("row-level security");
            assertThat(app.update("UPDATE kafka_connection_profile SET profile_name = 'renamed' WHERE kafka_connection_profile_id = ?",
                PLATFORM_PROFILE)).isZero();
            assertThat(app.update("DELETE FROM job_audit_logs WHERE tenant_id = ?", B)).isZero();
        } finally {
            TenantContext.clear();
        }
        assertThat(world.db.jdbc().queryForObject("SELECT profile_name FROM kafka_connection_profile WHERE kafka_connection_profile_id = ?",
            String.class, PLATFORM_PROFILE)).isEqualTo("Platform Default Kafka");
    }

    /**
     * A child row filed without its tenant takes its parent's (V102's trigger), reading the parent as the session: A's
     * run under A lands in A; B's parent, invisible to A, gives no tenant and the row is refused.
     */
    @Test
    void theParentTenantTriggerReadsTheParentAsTheSession() {
        JdbcTemplate login = world.db.jdbc();
        Long aRun = login.queryForObject("SELECT min(job_queue_id) FROM job_queue WHERE tenant_id = ?", Long.class, A);
        Long bRun = login.queryForObject("SELECT min(job_queue_id) FROM job_queue WHERE tenant_id = ?", Long.class, B);
        JdbcTemplate app = world.db.appJdbc();
        try {
            TenantContext.set(A, "TENANT_ADMIN", CoreProbeFixture.ADMIN_A, "alice@acme.example");
            Long filed = app.queryForObject("INSERT INTO job_audit_logs (job_audit_log_id, date_created, job_queue_id, log_detail, status)"
                + " VALUES (nextval('job_audit_logs_source_seq') + 900000, now(), ?, 'rls trigger check', 'Active') RETURNING tenant_id",
                Long.class, aRun);
            assertThat(filed).isEqualTo(A);
            assertThatThrownBy(() -> app.update("INSERT INTO job_audit_logs (job_audit_log_id, date_created, job_queue_id, log_detail, status)"
                + " VALUES (nextval('job_audit_logs_source_seq') + 900000, now(), ?, 'planted', 'Active')", bRun))
                .isInstanceOf(DataAccessException.class);
        } finally {
            TenantContext.clear();
        }
    }

    /** process_app reads and writes rows and owns nothing; Liquibase's own tables are not its. */
    @Test
    void processAppHasTheApplicationsGrantsAndNoMore() {
        JdbcTemplate login = world.db.jdbc();
        List<String> tables = login.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename NOT LIKE"
            + " 'databasechangelog%'", String.class);
        Map<String, Boolean> missing = new TreeMap<>();
        for (String table : tables) {
            for (String privilege : Arrays.asList("SELECT", "INSERT", "UPDATE", "DELETE")) {
                if (!login.queryForObject("SELECT has_table_privilege(?, ?, ?)", Boolean.class, APP, "public." + table, privilege)) {
                    missing.put(table + " " + privilege, false);
                }
            }
        }
        assertThat(missing).isEmpty();
        assertThat(login.queryForObject("SELECT has_table_privilege(?, 'public.databasechangelog', 'INSERT')", Boolean.class, APP)).isFalse();
        assertThat(login.queryForObject("SELECT count(*) FROM pg_class c JOIN pg_roles r ON r.oid = c.relowner WHERE r.rolname = ?",
            Long.class, APP)).isZero();
        assertThat(login.queryForObject("SELECT rolcanlogin FROM pg_roles WHERE rolname = ?", Boolean.class, APP)).isFalse();
    }
}

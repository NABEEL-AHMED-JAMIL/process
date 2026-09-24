package process.directory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchPostgres;
import process.identity.IdentityPort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-166: the continuous control. No foreign key says a Core row's tenant_id names a workspace any more,
 * so the audit anti-joins every tenant_id Core holds against Identity's workspaces: an id Identity does not
 * know is reported as an orphan, with the rows that hold it, table by table; a workspace Identity deleted
 * whose jobs are still active gets the tenant.deleted reaction it missed. Identity out of reach is "could not
 * check", never "everything is an orphan".
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD.
 */
class TenantOrphanAuditPostgresTest {

    private static ScratchPostgres db;
    private JdbcTemplate sql;
    private final IdentityPort identity = mock(IdentityPort.class);
    private TenantOrphanAudit audit;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("mig166_orphans");
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) db.close();
    }

    @BeforeEach
    void setUp() {
        this.sql = db.jdbc();
        this.sql.update("DELETE FROM scheduler");
        this.sql.update("DELETE FROM source_job");
        this.sql.update("TRUNCATE user_directory");
        this.audit = new TenantOrphanAudit(this.sql, this.identity, new WorkspaceRetirement(this.sql));
    }

    private void job(long id, long tenantId, String status) {
        this.sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
            + "VALUES (?, now(), 'Auto', ?, ?, 1, ?)", id, "job " + id, status, tenantId);
        this.sql.update("INSERT INTO scheduler (scheduler_id, frequency, job_id, start_date, start_time, tenant_id) "
            + "VALUES (?, 'Daily', ?, current_date, '00:00', ?)", id, id, tenantId);
    }

    private static IdentityPort.Workspace workspace(long id, String status) {
        return new IdentityPort.Workspace(id, "W" + id, "w" + id, status);
    }

    @Test
    void anIdIdentityDoesNotKnowIsReportedWithTheRowsThatHoldIt() {
        job(1, 2901, "Active");
        job(2, 2902, "Active");
        job(3, 2902, "Inactive");
        when(this.identity.workspaces(any())).thenReturn(Collections.singletonList(workspace(2901, "Active")));

        TenantOrphanAudit.Report report = this.audit.run();

        assertThat(report.isChecked()).isTrue();
        assertThat(report.getOrphans()).containsOnlyKeys(2902L);
        assertThat(report.getOrphans().get(2902L)).containsEntry("source_job", 2L).containsEntry("scheduler", 2L);
        assertThat(report.getRetired()).isEmpty();
        assertThat(this.sql.queryForObject("SELECT job_status FROM source_job WHERE job_id = 2", String.class))
            .as("an orphan is reported, never changed: nobody knows whose it is").isEqualTo("Active");
    }

    @Test
    void aWorkspaceIdentityDeletedHasTheReactionItMissedAndIsReported() {
        job(4, 2903, "Active");
        job(5, 2901, "Active");
        when(this.identity.workspaces(any())).thenReturn(Arrays.asList(workspace(2901, "Active"), workspace(2903, "Delete")));

        TenantOrphanAudit.Report report = this.audit.run();

        assertThat(report.getOrphans()).isEmpty();
        assertThat(report.getRetired()).containsEntry(2903L, 1);
        assertThat(this.sql.queryForObject("SELECT job_status FROM source_job WHERE job_id = 4", String.class)).isEqualTo("Inactive");
        assertThat(this.sql.queryForObject("SELECT job_status FROM source_job WHERE job_id = 5", String.class)).isEqualTo("Active");
        assertThat(this.audit.run().getRetired()).as("nothing left to retire the second time").isEmpty();
    }

    @Test
    void identityOutOfReachIsCouldNotCheckAndChangesNothing() {
        job(6, 2903, "Active");
        when(this.identity.workspaces(any())).thenThrow(new IdentityPort.Unavailable("identity down", null));

        TenantOrphanAudit.Report report = this.audit.run();

        assertThat(report.isChecked()).isFalse();
        assertThat(report.getReason()).contains("identity down");
        assertThat(report.getOrphans()).isEmpty();
        assertThat(this.sql.queryForObject("SELECT job_status FROM source_job WHERE job_id = 6", String.class)).isEqualTo("Active");
    }

    @Test
    void identityIsAskedInBatchesItAccepts() {
        for (long id = 1; id <= 620; id++) {
            this.sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
                + "VALUES (?, now(), 'Manual', 'j', 'Inactive', 1, ?)", 10_000 + id, 50_000 + id);
        }
        when(this.identity.workspaces(any())).thenReturn(new ArrayList<>());

        TenantOrphanAudit.Report report = this.audit.run();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> asked = ArgumentCaptor.forClass(Collection.class);
        verify(this.identity, atLeast(2)).workspaces(asked.capture());
        assertThat(asked.getAllValues()).allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(500));
        assertThat(report.getOrphans()).hasSize(620);
    }

    @Test
    void theAuditReadsCoresOwnTablesAndLeavesMovedFrozenAndProjectedOnesAlone() {
        when(this.identity.workspaces(any())).thenReturn(new ArrayList<>());
        List<String> audited = this.audit.run().getTables();
        assertThat(audited).contains("source_job", "scheduler", "job_queue", "source_task", "source_task_type", "pipeline",
            "kafka_connection_profile", "task_reference", "pipeline_config");
        // Identity's own, a projection of Identity, and every table moved out (read-only here, by trigger).
        assertThat(audited).doesNotContain("tenant", "app_user", "page_access_profile", "user_page_access", "user_directory",
            "lookup_data", "storage_connection", "invoice", "billing_account", "analytics_query", "ai_prompt");
        List<String> readOnly = this.sql.queryForList("SELECT DISTINCT c.relname FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid "
            + "WHERE NOT t.tgisinternal AND t.tgname LIKE '%\\_read\\_only'", String.class);
        assertThat(readOnly).isNotEmpty();
        assertThat(audited.stream().filter(readOnly::contains).collect(Collectors.toList())).isEmpty();
    }
}

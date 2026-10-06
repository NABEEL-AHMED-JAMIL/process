package process.directory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.AcrossTenantsProxy;
import process.ScratchPostgres;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-153 / MIG-166: what Core does with Identity's lifecycle events. A person's event lands in
 * user_directory; a workspace deleted in Identity has its active jobs made Inactive -- a status update,
 * never a cascade -- so nothing keeps firing for a workspace that no longer exists. Every other workspace
 * event changes nothing in Core. An unreadable message is skipped, never retried forever.
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD.
 */
class IdentityEventsListenerPostgresTest {

    private static ScratchPostgres db;
    private JdbcTemplate sql;
    private IdentityEventsListener listener;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("mig166_listener");
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) db.close();
    }

    @BeforeEach
    void setUp() {
        this.sql = db.jdbc();
        this.sql.update("TRUNCATE user_directory");
        this.sql.update("DELETE FROM scheduler");
        this.sql.update("DELETE FROM source_job");
        this.sql.update("DELETE FROM workspace_directory");
        // As the application runs it (MIG-258): process_app, nobody signed in, behind the across-tenants proxy.
        this.listener = AcrossTenantsProxy.of(new IdentityEventsListener(new UserDirectory(db.appJdbc()),
            new WorkspaceRetirement(db.appJdbc()), new WorkspaceDirectory(db.appJdbc())));
    }

    private void job(long id, long tenantId, String status) {
        this.sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
            + "VALUES (?, now(), 'Auto', ?, ?, 1, ?)", id, "job " + id, status, tenantId);
        this.sql.update("INSERT INTO scheduler (scheduler_id, frequency, job_id, start_date, start_time, tenant_id) "
            + "VALUES (?, 'Daily', ?, current_date, '00:00', ?)", id, id, tenantId);
    }

    private String jobStatus(long id) {
        return this.sql.queryForObject("SELECT job_status FROM source_job WHERE job_id = ?", String.class, id);
    }

    private boolean eligible(long id) {
        return this.sql.queryForObject("SELECT dispatch_eligible FROM scheduler WHERE job_id = ?", Boolean.class, id);
    }

    @Test
    void aPersonsEventsKeepTheDirectoryAtTheirLatestState() {
        this.listener.onUser(IdentityEventsFixture.user("user.created", 7001, 2901L, "ada@acme.io", "Ada", "Active",
            "2026-09-24T10:00:00.000001Z"));
        this.listener.onUser(IdentityEventsFixture.user("user.renamed", 7001, 2901L, "ada@acme.io", "Ada Lovelace", "Active",
            "2026-09-24T10:05:00.000001Z"));
        this.listener.onUser(IdentityEventsFixture.user("user.created", 1, null, "root@platform", null, "Active",
            "2026-09-24T10:00:00Z"));

        UserDirectory.Entry ada = new UserDirectory(this.sql).find(Collections.singletonList(7001L)).get(7001L);
        assertThat(ada.getFullName()).isEqualTo("Ada Lovelace");
        assertThat(ada.getTenantId()).isEqualTo(2901L);
        UserDirectory.Entry root = new UserDirectory(this.sql).find(Collections.singletonList(1L)).get(1L);
        assertThat(root.getTenantId()).isNull();
        assertThat(root.getDisplayName()).isEqualTo("root@platform");
    }

    @Test
    void aDeletedPersonStaysInTheDirectorySoTheirOldWorkStillShowsWhoDidIt() {
        this.listener.onUser(IdentityEventsFixture.user("user.deleted", 7002, 2901L, "gone@acme.io", "Gone", "Delete",
            "2026-09-24T10:00:00Z"));
        assertThat(new UserDirectory(this.sql).find(Collections.singletonList(7002L)).get(7002L).getStatus()).isEqualTo("Delete");
    }

    @Test
    void aWorkspaceDeletedInIdentityStopsItsJobsAndNoOneElses() {
        job(1, 2901, "Active");
        job(2, 2901, "Inactive");
        job(3, 2901, "Delete");
        job(4, 2902, "Active");
        assertThat(eligible(1)).isTrue();

        this.listener.onTenant(IdentityEventsFixture.tenant("tenant.deleted", 2901, "acme", "Delete", "2026-09-24T10:00:00Z"));

        assertThat(jobStatus(1)).isEqualTo("Inactive");
        assertThat(eligible(1)).as("the scheduler stops offering it for dispatch").isFalse();
        assertThat(jobStatus(2)).isEqualTo("Inactive");
        assertThat(jobStatus(3)).as("a deleted job stays deleted").isEqualTo("Delete");
        assertThat(jobStatus(4)).as("another workspace is untouched").isEqualTo("Active");
        assertThat(this.sql.queryForObject("SELECT count(*) FROM source_job", Long.class)).as("nothing is removed").isEqualTo(4L);

        // Redelivered: the same answer, no error.
        this.listener.onTenant(IdentityEventsFixture.tenant("tenant.deleted", 2901, "acme", "Delete", "2026-09-24T10:00:00Z"));
        assertThat(jobStatus(1)).isEqualTo("Inactive");
    }

    /**
     * Event audit E8: the topic is compacted and read from the beginning, so a replay hands over a deletion long since
     * followed by a restore. Core already holds the newer state (workspace_directory), so the restored workspace's jobs
     * are left as they are; a deletion as new as what Core knows is still acted on.
     */
    @Test
    void aReplayedDeletionOlderThanTheWorkspacesStateLeavesItsJobsAlone() {
        job(6, 2904, "Active");
        // Deleted at 10:00, restored at 11:00 -- the directory's listener has seen the restore.
        this.listener.onWorkspaceStatus(IdentityEventsFixture.tenant("tenant.status.changed", 2904, "initech", "Active",
            "2026-09-24T11:00:00Z"));

        this.listener.onTenant(IdentityEventsFixture.tenant("tenant.deleted", 2904, "initech", "Delete", "2026-09-24T10:00:00Z"));
        assertThat(jobStatus(6)).as("the replayed deletion predates the restore").isEqualTo("Active");

        this.listener.onTenant(IdentityEventsFixture.tenant("tenant.deleted", 2904, "initech", "Delete", "2026-09-24T12:00:00Z"));
        assertThat(jobStatus(6)).as("a deletion after the restore").isEqualTo("Inactive");
    }

    @Test
    void otherWorkspaceEventsChangeNothingInCore() {
        job(5, 2903, "Active");
        this.listener.onTenant(IdentityEventsFixture.tenant("tenant.status.changed", 2903, "globex", "Suspended", "2026-09-24T10:00:00Z"));
        this.listener.onTenant(IdentityEventsFixture.tenant("tenant.renamed", 2903, "globex2", "Active", "2026-09-24T10:00:00Z"));
        this.listener.onTenant(IdentityEventsFixture.tenant("tenant.created", 2903, "globex", "Active", "2026-09-24T10:00:00Z"));
        assertThat(jobStatus(5)).isEqualTo("Active");
    }

    @Test
    void anUnreadableMessageIsSkipped() {
        this.listener.onUser("not json");
        this.listener.onUser("{\"eventType\": \"user.created\", \"payload\": {\"username\": \"no id\"}}");
        this.listener.onTenant("{\"eventType\": \"tenant.deleted\", \"payload\": {}}");
        assertThat(this.sql.queryForObject("SELECT count(*) FROM user_directory", Long.class)).isZero();
    }
}

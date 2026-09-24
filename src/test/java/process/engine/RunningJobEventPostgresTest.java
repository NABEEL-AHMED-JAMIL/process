package process.engine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchJpa;
import process.ScratchPostgres;
import process.model.enums.JobStatus;
import process.model.projection.SourceJobProjection;
import process.model.repository.SourceJobRepository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-151 against a real Postgres (V85): source_job.assigned_username is kept by the database, and the
 * live job-event read -- the real repository method, through Hibernate -- takes it from there.
 *
 * Opt-in: runs when NOTIFICATIONS_TEST_DB_URL points at a Postgres server (see ScratchPostgres).
 */
class RunningJobEventPostgresTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 24, 12, 0);

    private static ScratchPostgres db;
    private static ScratchJpa jpa;
    private JdbcTemplate sql;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("job_event");
        jpa = new ScratchJpa(db);
    }

    @AfterAll
    static void drop() throws Exception {
        if (jpa != null) {
            jpa.close();
        }
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void rows() {
        this.sql = db.jdbc();
        this.sql.update("DELETE FROM scheduler WHERE job_id BETWEEN 9501 AND 9599");
        this.sql.update("DELETE FROM source_job WHERE job_id BETWEEN 9501 AND 9599");
        this.sql.update("DELETE FROM app_user WHERE app_user_id BETWEEN 95001 AND 95099");
    }

    private void user(long appUserId, String username) {
        this.sql.update("INSERT INTO app_user (app_user_id, full_name, password, status, user_role, username) "
            + "VALUES (?, 'Ops', 'x', 'Active', 'TENANT_USER', ?)", appUserId, username);
    }

    private void job(long jobId, Long assignee) {
        this.sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, "
            + "assigned_user_id, job_running_status) VALUES (?, ?, 'Auto', ?, 'Active', 1, ?, 'Running')",
            jobId, Timestamp.valueOf(NOW), "event-" + jobId, assignee);
    }

    private String usernameOn(long jobId) {
        return this.sql.queryForObject("SELECT assigned_username FROM source_job WHERE job_id = ?", String.class, jobId);
    }

    @Test
    void theUsernameIsWrittenWithTheAssigneeAndFollowsARenameInOneStatement() {
        this.user(95001, "ops@medaxis.example");
        this.user(95002, "night@medaxis.example");
        this.job(9501, 95001L);
        this.job(9502, 95001L);
        assertThat(this.usernameOn(9501)).isEqualTo("ops@medaxis.example");

        int renamed = this.sql.update("UPDATE app_user SET username = 'operations@medaxis.example' WHERE app_user_id = 95001");
        assertThat(renamed).isEqualTo(1);
        assertThat(this.usernameOn(9501)).isEqualTo("operations@medaxis.example");
        assertThat(this.usernameOn(9502)).isEqualTo("operations@medaxis.example");

        this.sql.update("UPDATE source_job SET assigned_user_id = 95002 WHERE job_id = 9502");
        assertThat(this.usernameOn(9502)).isEqualTo("night@medaxis.example");

        this.sql.update("UPDATE source_job SET assigned_user_id = NULL WHERE job_id = 9502");
        assertThat(this.usernameOn(9502)).isNull();
    }

    @Test
    void theEventReadReturnsTheJobItsOwnerAndItsNextRunThroughHibernate() {
        this.user(95003, "ops@medaxis.example");
        this.job(9503, 95003L);
        this.job(9504, null);
        this.sql.update("INSERT INTO scheduler (scheduler_id, job_id, start_date, start_time, frequency, next_run_at, expired) "
            + "VALUES (9503, 9503, ?, '00:00:00', 'Daily', ?, false)", NOW.toLocalDate(), Timestamp.valueOf(NOW.plusDays(1)));
        SourceJobRepository jobs = jpa.repository(SourceJobRepository.class);

        List<SourceJobProjection> events = jpa.transactions().execute(status -> jobs.fetchRunningJobEvent(Arrays.asList(9503L, 9504L)));

        assertThat(events).hasSize(2);
        SourceJobProjection owned = events.stream().filter(e -> e.getJobId() == 9503L).findFirst().get();
        assertThat(owned.getAssignedUsername()).isEqualTo("ops@medaxis.example");
        assertThat(owned.getAssignedUserId()).isEqualTo(95003L);
        assertThat(owned.getJobRunningStatus()).isEqualTo(JobStatus.Running);
        assertThat(owned.getNextRunAt()).startsWith("2026-09-25");
        SourceJobProjection unowned = events.stream().filter(e -> e.getJobId() == 9504L).findFirst().get();
        assertThat(unowned.getAssignedUsername()).isNull();
        List<SourceJobProjection> none = jpa.transactions().execute(status -> jobs.fetchRunningJobEvent(Collections.singletonList(9599L)));
        assertThat(none).isEmpty();
    }
}

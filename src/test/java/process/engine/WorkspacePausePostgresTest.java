package process.engine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchJpa;
import process.ScratchPostgres;
import process.directory.IdentityEventsListener;
import process.directory.UserDirectory;
import process.directory.WorkspaceDirectory;
import process.directory.WorkspaceRetirement;
import process.model.repository.JobAuditLogRepository;
import process.model.repository.JobQueueRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.repository.TaskReferenceRepository;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.notifications.NotificationPort;
import process.util.OpenSearchAuditLogClient;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Owner decision 2026-09-24 (MIG-166 follow-up): a Suspended or Inactive workspace's scheduled jobs are
 * PAUSED -- each due slot is recorded as a Skip and the schedule moves on, nothing about the job changes --
 * and they resume by themselves when the workspace is Active again. Only a deletion makes jobs Inactive.
 *
 * Driven end to end: Identity's workspace event through IdentityEventsListener into workspace_directory,
 * then the real enqueuer (ProducerBulkEngine.addJobInQueue, its claim, BulkAction, Hibernate) over a
 * Postgres built by the real changelog. Nothing on this path can call Identity: the only thing the pause
 * reads is the local view. Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD (see ScratchPostgres).
 *
 * Bounded: an enqueuer that stopped advancing a paused slot would reclaim it for ever, so a hang is a failure here.
 */
@Timeout(120)
class WorkspacePausePostgresTest {

    private static final long TENANT = 9601L;
    private static final long OTHER_TENANT = 9602L;

    private static ScratchPostgres db;
    private static ScratchJpa jpa;
    private JdbcTemplate sql;
    private IdentityEventsListener listener;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("workspace_pause");
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
        this.sql.update("DELETE FROM job_audit_logs");
        this.sql.update("DELETE FROM job_queue WHERE job_id BETWEEN 9601 AND 9699");
        this.sql.update("DELETE FROM scheduler WHERE job_id BETWEEN 9601 AND 9699");
        this.sql.update("DELETE FROM source_job WHERE job_id BETWEEN 9601 AND 9699");
        this.sql.update("TRUNCATE workspace_directory");
        for (long tenant : new long[] { TENANT, OTHER_TENANT }) {
            this.sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?) "
                + "ON CONFLICT DO NOTHING", tenant, "WP" + tenant, "Pause fixture " + tenant);
        }
        this.listener = new IdentityEventsListener(new UserDirectory(this.sql), new WorkspaceRetirement(this.sql),
            new WorkspaceDirectory(this.sql));
    }

    /** The enqueuer as the application wires it: the pause reads workspace_directory and nothing else. */
    private ProducerBulkEngine enqueuer() {
        TransactionServiceImpl store = new TransactionServiceImpl(jpa.repository(SourceJobRepository.class),
            jpa.repository(SchedulerRepository.class), jpa.repository(JobQueueRepository.class),
            jpa.repository(TaskReferenceRepository.class), jpa.repository(JobAuditLogRepository.class),
            jpa.repository(SourceTaskRepository.class), mock(OpenSearchAuditLogClient.class));
        BulkAction bulkAction = new BulkAction(store, mock(NotificationPort.class));
        ProducerBulkEngine engine = new ProducerBulkEngine(bulkAction, store, mock(JobMail.class), null, null,
            jpa.transactionManager());
        engine.useWorkspaceDirectory(new WorkspaceDirectory(this.sql));
        return engine;
    }

    private void dailyJob(long jobId, long tenantId, LocalDateTime due) {
        LocalDateTime now = LocalDateTime.now();
        this.sql.update("INSERT INTO source_job (tenant_id, job_id, date_created, execution, job_name, job_status, priority) "
            + "VALUES (?, ?, ?, 'Auto', ?, 'Active', 1)", tenantId, jobId, Timestamp.valueOf(now.minusDays(2)), "pause-" + jobId);
        this.sql.update("UPDATE source_job SET complete_job = false, fail_job = false, skip_job = false WHERE job_id = ?", jobId);
        this.sql.update("INSERT INTO scheduler (scheduler_id, job_id, start_date, start_time, frequency, interval_value, "
            + "next_run_at, expired, tenant_id) VALUES (?, ?, ?, ?, 'Daily', '1', ?, false, ?)", jobId, jobId,
            Date.valueOf(now.toLocalDate().minusDays(1)), Time.valueOf(due.toLocalTime()), Timestamp.valueOf(due), tenantId);
    }

    /** A day goes by: the next slot falls due (what the next minute's tick would find on a real clock). */
    private void nextSlotFallsDue(long jobId, LocalDateTime due) {
        this.sql.update("UPDATE scheduler SET next_run_at = ? WHERE job_id = ?", Timestamp.valueOf(due), jobId);
    }

    private void identitySays(String type, long tenantId, String status, String at) {
        this.listener.onWorkspaceStatus("{\"payload\": {\"status\": \"" + status + "\", \"tenantId\": " + tenantId
            + ", \"tenantCode\": \"WP" + tenantId + "\", \"tenantName\": \"Pause fixture\", \"updatedAt\": \"" + at
            + "\"}, \"eventId\": \"" + UUID.randomUUID() + "\", \"producer\": \"identity-service\", \"tenantId\": " + tenantId
            + ", \"eventType\": \"" + type + "\", \"occurredAt\": \"" + at + "\"}");
    }

    private List<String> runsOf(long jobId) {
        return this.sql.queryForList("SELECT job_status FROM job_queue WHERE job_id = ? ORDER BY job_queue_id", String.class, jobId);
    }

    private List<String> auditOf(long jobId) {
        return this.sql.queryForList("SELECT a.log_detail FROM job_audit_logs a JOIN job_queue q ON q.job_queue_id = a.job_queue_id "
            + "WHERE q.job_id = ? ORDER BY a.job_audit_log_id", String.class, jobId);
    }

    private LocalDateTime nextRunAt(long jobId) {
        return this.sql.queryForObject("SELECT next_run_at FROM scheduler WHERE job_id = ?", Timestamp.class, jobId).toLocalDateTime();
    }

    private String jobStatus(long jobId) {
        return this.sql.queryForObject("SELECT job_status FROM source_job WHERE job_id = ?", String.class, jobId);
    }

    @Test
    void identitysEventsPauseAndResumeAWorkspaceInTheLocalView() {
        WorkspaceDirectory view = new WorkspaceDirectory(this.sql);
        assertThat(view.pauseOf(TENANT)).as("a workspace the view has never heard of runs as today").isEmpty();

        this.identitySays("tenant.created", TENANT, "Active", "2026-09-24T10:00:00.000001Z");
        assertThat(view.pauseOf(TENANT)).isEmpty();

        this.identitySays("tenant.status.changed", TENANT, "Suspended", "2026-09-24T10:05:00.000001Z");
        assertThat(view.pauseOf(TENANT)).get().extracting(WorkspaceDirectory.Pause::getStatus).isEqualTo("Suspended");

        // Redelivered late, an older state never rolls the view back.
        this.identitySays("tenant.created", TENANT, "Active", "2026-09-24T10:00:00.000001Z");
        assertThat(view.pauseOf(TENANT)).isPresent();

        this.identitySays("tenant.status.changed", TENANT, "Inactive", "2026-09-24T10:06:00Z");
        assertThat(view.pauseOf(TENANT)).get().extracting(WorkspaceDirectory.Pause::getStatus).isEqualTo("Inactive");

        this.identitySays("tenant.status.changed", TENANT, "Active", "2026-09-24T10:10:00Z");
        assertThat(view.pauseOf(TENANT)).as("Active again: resumed").isEmpty();

        this.identitySays("tenant.deleted", OTHER_TENANT, "Delete", "2026-09-24T10:10:00Z");
        assertThat(view.pauseOf(OTHER_TENANT)).as("a deleted workspace is retired (MIG-166), not paused").isEmpty();
    }

    @Test
    void aSlotDuringSuspensionIsSkippedNotQueuedAndTheJobIsNotChanged() {
        LocalDateTime due = LocalDateTime.now().minusMinutes(1).withNano(0);
        this.dailyJob(9601, TENANT, due);
        this.dailyJob(9602, OTHER_TENANT, due);
        this.identitySays("tenant.status.changed", TENANT, "Suspended", "2026-09-24T10:05:00Z");

        this.enqueuer().addJobInQueue();

        assertThat(this.runsOf(9601)).as("recorded as a skip, never queued").containsExactly("Skip");
        assertThat(this.nextRunAt(9601)).as("the schedule still moves on, so no backlog builds").isEqualTo(due.plusDays(1));
        assertThat(this.jobStatus(9601)).as("the job itself is not changed").isEqualTo("Active");
        assertThat(this.sql.queryForObject("SELECT dispatch_eligible FROM scheduler WHERE job_id = 9601", Boolean.class)).isTrue();
        assertThat(this.sql.queryForObject("SELECT job_running_status FROM source_job WHERE job_id = 9601", String.class))
            .as("nothing was queued, so the job's running status did not move").isNull();
        assertThat(this.sql.queryForObject("SELECT job_status_message FROM job_queue WHERE job_id = 9601", String.class))
            .contains("Suspended").contains("paused");
        assertThat(this.runsOf(9602)).as("another workspace's job runs as usual").containsExactly("Queue");

        // The next slot of the same pause -- renamed meanwhile, still Suspended: skipped again, but the audit
        // line is written once per pause, not per slot or per event.
        this.identitySays("tenant.renamed", TENANT, "Suspended", "2026-09-24T10:30:00Z");
        this.nextSlotFallsDue(9601, due);
        this.enqueuer().addJobInQueue();
        assertThat(this.runsOf(9601)).containsExactly("Skip", "Skip");
        List<String> audit = this.auditOf(9601);
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)).contains("paused").contains("Suspended").contains(String.valueOf(TENANT));
    }

    @Test
    void resumeFiresTheNextSlotOnlyAndSaysSoOnce() {
        LocalDateTime due = LocalDateTime.now().minusMinutes(1).withNano(0);
        this.dailyJob(9603, TENANT, due);
        this.identitySays("tenant.status.changed", TENANT, "Suspended", "2026-09-24T10:05:00Z");
        this.enqueuer().addJobInQueue();
        this.nextSlotFallsDue(9603, due);
        this.enqueuer().addJobInQueue();
        assertThat(this.runsOf(9603)).containsExactly("Skip", "Skip");

        this.identitySays("tenant.status.changed", TENANT, "Active", "2026-09-24T11:00:00Z");
        this.enqueuer().addJobInQueue();
        assertThat(this.runsOf(9603)).as("nothing fires until the next slot is due").containsExactly("Skip", "Skip");

        this.nextSlotFallsDue(9603, due);
        this.enqueuer().addJobInQueue();

        assertThat(this.runsOf(9603)).as("the next slot runs -- one run, no backlog, no Missed rows")
            .containsExactly("Skip", "Skip", "Queue");
        assertThat(this.nextRunAt(9603)).isEqualTo(due.plusDays(1));
        List<String> audit = this.auditOf(9603);
        assertThat(audit).hasSize(3);
        assertThat(audit.get(0)).contains("paused");
        assertThat(audit).anySatisfy(line -> assertThat(line).contains("resumed"));
        assertThat(audit).anySatisfy(line -> assertThat(line).contains("now in the queue"));
        assertThat(this.sql.queryForObject("SELECT paused_since FROM scheduler WHERE job_id = 9603", Timestamp.class)).isNull();

        // A later slot of the resumed job is ordinary again: no second "resumed" line.
        this.sql.update("UPDATE job_queue SET job_status = 'Completed' WHERE job_id = 9603 AND job_status = 'Queue'");
        this.nextSlotFallsDue(9603, due);
        this.enqueuer().addJobInQueue();
        assertThat(this.auditOf(9603)).filteredOn(line -> line.contains("resumed")).hasSize(1);
    }

    @Test
    void aDeletedWorkspaceKeepsTodaysBehaviourItsJobsAreMadeInactive() {
        LocalDateTime due = LocalDateTime.now().minusMinutes(1).withNano(0);
        this.dailyJob(9604, TENANT, due);
        this.listener.onTenant("{\"payload\": {\"status\": \"Delete\", \"tenantId\": " + TENANT + ", \"updatedAt\": "
            + "\"2026-09-24T10:05:00Z\"}, \"eventType\": \"tenant.deleted\", \"tenantId\": " + TENANT + "}");
        this.identitySays("tenant.deleted", TENANT, "Delete", "2026-09-24T10:05:00Z");

        this.enqueuer().addJobInQueue();

        assertThat(this.jobStatus(9604)).isEqualTo("Inactive");
        assertThat(this.runsOf(9604)).as("out of dispatch altogether: not even a skip").isEmpty();
    }

    @Test
    void aWorkspaceTheViewHasNotHeardOfRunsAsToday() {
        LocalDateTime due = LocalDateTime.now().minusMinutes(1).withNano(0);
        this.dailyJob(9605, TENANT, due);

        this.enqueuer().addJobInQueue();

        assertThat(this.runsOf(9605)).containsExactly("Queue");
    }
}

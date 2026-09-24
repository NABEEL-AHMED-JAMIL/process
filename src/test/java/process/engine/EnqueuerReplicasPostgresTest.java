package process.engine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchJpa;
import process.ScratchPostgres;
import process.model.repository.JobAuditLogRepository;
import process.model.repository.JobQueueRepository;
import process.model.repository.TaskReferenceRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.notifications.NotificationPort;
import process.util.OpenSearchAuditLogClient;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * MIG-152 and T1 with the real enqueuer: ProducerBulkEngine.addJobInQueue, the real BulkAction and
 * TransactionServiceImpl, Spring Data repositories built from the real interfaces, Hibernate, and a
 * Postgres built by the real changelog -- so the claim's native query, its list parameter and its
 * Optional entity result run exactly as the application runs them.
 *
 * Two replicas start the same tick on the same due slot with no ShedLock between them: the slot is
 * enqueued once, its cursor advanced once. Opt-in: runs when NOTIFICATIONS_TEST_DB_URL points at a
 * Postgres server (see ScratchPostgres); nothing outside the throwaway database is touched.
 */
class EnqueuerReplicasPostgresTest {

    private static ScratchPostgres db;
    private static ScratchJpa jpa;
    private JdbcTemplate sql;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("enqueuer_replicas");
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
        this.sql.update("DELETE FROM job_queue WHERE job_id BETWEEN 9401 AND 9499");
        this.sql.update("DELETE FROM scheduler WHERE job_id BETWEEN 9401 AND 9499");
        this.sql.update("DELETE FROM source_job WHERE job_id BETWEEN 9401 AND 9499");
    }

    /** One replica, as the application wires it; audit lines fall back to the database. */
    private static ProducerBulkEngine replica() {
        TransactionServiceImpl store = new TransactionServiceImpl(jpa.repository(SourceJobRepository.class),
            jpa.repository(SchedulerRepository.class), jpa.repository(JobQueueRepository.class),
            jpa.repository(TaskReferenceRepository.class), jpa.repository(JobAuditLogRepository.class),
            jpa.repository(SourceTaskRepository.class), mock(OpenSearchAuditLogClient.class));
        BulkAction bulkAction = new BulkAction(store, mock(NotificationPort.class));
        return new ProducerBulkEngine(bulkAction, store, mock(JobMail.class), null, null, jpa.transactionManager());
    }

    private void dueSlot(long jobId, LocalDateTime nextRunAt) {
        LocalDateTime now = LocalDateTime.now();
        // Every job has a tenant (V102: its runs and schedule carry it, NOT NULL).
        this.sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (9999, 'Active', 'FIXTURE', 'Fixture') "
            + "ON CONFLICT DO NOTHING");
        this.sql.update("INSERT INTO source_job (tenant_id, job_id, date_created, execution, job_name, job_status, priority) "
            + "VALUES (9999, ?, ?, 'Auto', ?, 'Active', 1)", jobId, Timestamp.valueOf(now.minusDays(2)), "replica-" + jobId);
        this.sql.update("UPDATE source_job SET complete_job = false, fail_job = false, skip_job = false WHERE job_id = ?", jobId);
        this.sql.update("INSERT INTO scheduler (scheduler_id, job_id, start_date, start_time, frequency, interval_value, "
            + "next_run_at, expired) VALUES (?, ?, ?, ?, 'Daily', '1', ?, false)", jobId, jobId, Date.valueOf(now.toLocalDate().minusDays(1)),
            Time.valueOf(nextRunAt.toLocalTime()), Timestamp.valueOf(nextRunAt));
    }

    private List<String> runsOf(long jobId) {
        return this.sql.queryForList("SELECT job_status FROM job_queue WHERE job_id = ? ORDER BY job_queue_id",
            String.class, jobId);
    }

    @Test
    void twoReplicasOnTheSameTickEnqueueTheSlotOnceAndAdvanceItOnce() throws Exception {
        LocalDateTime due = LocalDateTime.now().minusMinutes(1).withNano(0);
        this.dueSlot(9401, due);
        CyclicBarrier sameTick = new CyclicBarrier(2);
        ExecutorService replicas = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> ticks = new ArrayList<>();
            for (int replica = 0; replica < 2; replica++) {
                ProducerBulkEngine engine = replica();
                ticks.add(replicas.submit(() -> {
                    sameTick.await(10, TimeUnit.SECONDS);
                    engine.addJobInQueue();
                    return null;
                }));
            }
            for (Future<?> tick : ticks) {
                tick.get(30, TimeUnit.SECONDS);
            }
        } finally {
            replicas.shutdownNow();
        }

        assertThat(this.runsOf(9401)).containsExactly("Queue");
        LocalDateTime next = this.sql.queryForObject("SELECT next_run_at FROM scheduler WHERE job_id = 9401", Timestamp.class)
            .toLocalDateTime();
        assertThat(next).isEqualTo(due.plusDays(1));
        assertThat(this.sql.queryForObject("SELECT job_running_status FROM source_job WHERE job_id = 9401", String.class))
            .isEqualTo("Queue");
    }

    /** The count still decides the ordinary case: a job with a run in flight gets a Skip for its slot. */
    @Test
    void aSlotOfAJobWithARunInFlightIsRecordedAsASkip() {
        LocalDateTime due = LocalDateTime.now().minusMinutes(1).withNano(0);
        this.dueSlot(9402, due);
        this.sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, start_time, date_created, status) "
            + "VALUES (94021, 9402, 'Start', ?, ?, 'Active')", Timestamp.valueOf(due.minusHours(1)), Timestamp.valueOf(due.minusHours(1)));

        replica().addJobInQueue();

        assertThat(this.runsOf(9402)).containsExactlyInAnyOrder("Start", "Skip");
        assertThat(this.sql.queryForObject("SELECT next_run_at FROM scheduler WHERE job_id = 9402", Timestamp.class)
            .toLocalDateTime()).isEqualTo(due.plusDays(1));
    }

    /** A Manual job's schedule is not claimed at all: dispatch_eligible carries the recorded fix. */
    @Test
    void aManualJobsScheduleIsNeverEnqueued() {
        LocalDateTime due = LocalDateTime.now().minusMinutes(1).withNano(0);
        this.dueSlot(9403, due);
        this.sql.update("UPDATE source_job SET execution = 'Manual' WHERE job_id = 9403");

        replica().addJobInQueue();

        assertThat(this.runsOf(9403)).isEmpty();
    }
}

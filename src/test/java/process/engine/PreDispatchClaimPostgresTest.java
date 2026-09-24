package process.engine;

import process.util.BusinessTime;
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
import process.util.OpenSearchAuditLogClient;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * MIG-134 against a real Postgres (V86): which runs the pre-dispatch phase takes, through the
 * application's own repository methods over Hibernate.
 *
 * Only queued, unsent, unprepared, due runs; locked SKIP LOCKED so a second replica takes none of what
 * the first is claiming; and leased, so none of what the first is still preparing either -- until the
 * lease runs out, when a preparer that died leaves the run to be taken again. And a prepared run is
 * handed on only while it is still queued and unsent.
 *
 * Opt-in: runs when NOTIFICATIONS_TEST_DB_URL points at a Postgres server (see ScratchPostgres).
 */
class PreDispatchClaimPostgresTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 24, 12, 0);

    private static ScratchPostgres db;
    private static ScratchJpa jpa;
    private JdbcTemplate sql;
    private TransactionServiceImpl store;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("pre_dispatch_claim");
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
        this.sql.update("DELETE FROM job_queue WHERE job_id BETWEEN 9701 AND 9799");
        this.sql.update("DELETE FROM source_job WHERE job_id BETWEEN 9701 AND 9799");
        this.store = new TransactionServiceImpl(jpa.repository(SourceJobRepository.class),
            jpa.repository(SchedulerRepository.class), jpa.repository(JobQueueRepository.class),
            jpa.repository(TaskReferenceRepository.class), jpa.repository(JobAuditLogRepository.class),
            jpa.repository(SourceTaskRepository.class), mock(OpenSearchAuditLogClient.class));
    }

    private void run(long jobQueueId, String status, boolean sent, LocalDateTime preparedAt, LocalDateTime nextAttemptAt) {
        long jobId = 9700 + (jobQueueId % 100);
        // Every job has a tenant (V102: its runs and schedule carry it, NOT NULL).
        this.sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (9999, 'Active', 'FIXTURE', 'Fixture') "
            + "ON CONFLICT DO NOTHING");
        this.sql.update("INSERT INTO source_job (tenant_id, job_id, date_created, execution, job_name, job_status, priority) "
            + "VALUES (9999, ?, ?, 'Auto', ?, 'Active', 1) ON CONFLICT DO NOTHING", jobId, BusinessTime.timestampOf(NOW), "claim-" + jobId);
        this.sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, date_created, status, job_send, prepared_at, "
            + "next_attempt_at) VALUES (?, ?, ?, ?, 'Active', ?, ?, ?)", jobQueueId, jobId, status, BusinessTime.timestampOf(NOW),
            sent, preparedAt == null ? null : BusinessTime.timestampOf(preparedAt),
            nextAttemptAt == null ? null : BusinessTime.timestampOf(nextAttemptAt));
    }

    private List<Long> claim(LocalDateTime at) {
        return jpa.transactions().execute(status -> this.store.claimRunsToPrepare(at, 100, at.plusMinutes(30)));
    }

    @Test
    void onlyQueuedUnsentUnpreparedDueRunsAreTaken() {
        this.run(970101, "Queue", false, null, null);
        this.run(970102, "Queue", true, null, null);
        this.run(970103, "Queue", false, NOW.minusMinutes(1), null);
        this.run(970104, "Queue", false, null, NOW.plusMinutes(5));
        this.run(970105, "Start", false, null, null);
        this.run(970106, "queue", false, null, NOW.minusMinutes(5));

        assertThat(this.claim(NOW)).containsExactly(970101L, 970106L);
    }

    @Test
    void aClaimIsLeasedAndNotTakenAgainUntilTheLeaseRunsOut() {
        this.run(970201, "Queue", false, null, null);

        assertThat(this.claim(NOW)).containsExactly(970201L);
        assertThat(this.claim(NOW.plusMinutes(29))).as("still leased").isEmpty();
        assertThat(this.claim(NOW.plusMinutes(31))).as("a preparer that died leaves it to be taken again").containsExactly(970201L);
    }

    /** Two replicas at once: the second skips what the first has locked, instead of waiting for it. */
    @Test
    void aSecondReplicaTakesNothingTheFirstIsClaiming() throws Exception {
        for (long id = 970301; id <= 970310; id++) {
            this.run(id, "Queue", false, null, null);
        }
        CountDownLatch firstHolds = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService replicas = Executors.newSingleThreadExecutor();
        try {
            Future<List<Long>> first = replicas.submit(() -> jpa.transactions().execute(status -> {
                List<Long> ids = this.store.claimRunsToPrepare(NOW, 100, NOW.plusMinutes(30));
                firstHolds.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return ids;
            }));
            assertThat(firstHolds.await(10, TimeUnit.SECONDS)).isTrue();

            List<Long> second = this.claim(NOW);
            release.countDown();

            assertThat(second).isEmpty();
            assertThat(first.get(10, TimeUnit.SECONDS)).hasSize(10);
        } finally {
            replicas.shutdownNow();
        }
    }

    /** A run closed or dispatched while it was being prepared is left as it is. */
    @Test
    void aPreparedDocumentIsHandedOnOnlyWhileTheRunStillWaitsForIt() {
        this.run(970401, "Queue", false, null, null);
        this.run(970402, "Failed", false, null, null);

        Integer handedOn = jpa.transactions().execute(status -> this.store.markPrepared(970401L, "<p/>", NOW, "corr-970401-x"));
        Integer closed = jpa.transactions().execute(status -> this.store.markPrepared(970402L, "<p/>", NOW, "corr-970402-x"));

        assertThat(handedOn).isEqualTo(1);
        assertThat(closed).isZero();
        assertThat(this.sql.queryForObject("SELECT correlation_id FROM job_queue WHERE job_queue_id = 970401", String.class))
            .isEqualTo("corr-970401-x");
        assertThat(this.sql.queryForObject("SELECT prepared_at FROM job_queue WHERE job_queue_id = 970402", Timestamp.class)).isNull();
    }
}

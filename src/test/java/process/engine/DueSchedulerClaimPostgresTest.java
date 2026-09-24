package process.engine;

import process.util.BusinessTime;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import process.ScratchPostgres;
import process.model.repository.SchedulerRepository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-152 against a real Postgres (V84): the enqueuer's loop as row-level claims.
 *
 * The claim is SchedulerRepository.claimNextDueScheduler's own SQL: due, dispatch_eligible, not
 * expired, oldest next_run_at first, ONE row, FOR UPDATE SKIP LOCKED. A slot is claimed, enqueued and
 * its next_run_at advanced in one local transaction, so N replicas share the loop with no coordinator
 * and a replica that dies part-way leaves nothing behind for the next tick to repeat. dispatch_eligible
 * is kept by triggers in the same transaction as every source_job write -- including the native bulk
 * updates Hibernate never sees -- and it carries the execution = 'Auto' fix: a job switched to Manual
 * stops being enqueued.
 *
 * Opt-in: runs when NOTIFICATIONS_TEST_DB_URL points at a Postgres server (see ScratchPostgres).
 */
class DueSchedulerClaimPostgresTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 24, 12, 0);

    private static ScratchPostgres db;
    private JdbcTemplate sql;
    private TransactionTemplate transactions;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("due_claim");
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void rows() {
        this.sql = db.jdbc();
        this.transactions = db.transactions();
        this.sql.update("DELETE FROM job_queue WHERE job_id BETWEEN 9301 AND 9399");
        this.sql.update("DELETE FROM scheduler WHERE job_id BETWEEN 9301 AND 9399");
        this.sql.update("DELETE FROM source_job WHERE job_id BETWEEN 9301 AND 9399");
    }

    private void job(long jobId, String status, String execution) {
        // Every job has a tenant (V102: its runs and schedule carry it, NOT NULL).
        this.sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (9999, 'Active', 'FIXTURE', 'Fixture') "
            + "ON CONFLICT DO NOTHING");
        this.sql.update("INSERT INTO source_job (tenant_id, job_id, date_created, execution, job_name, job_status, priority) "
            + "VALUES (9999, ?, ?, ?, ?, ?, 1)", jobId, BusinessTime.timestampOf(NOW.minusDays(1)), execution, "claim-" + jobId, status);
    }

    private void scheduler(long schedulerId, long jobId, LocalDateTime nextRunAt) {
        this.sql.update("INSERT INTO scheduler (scheduler_id, job_id, start_date, start_time, frequency, next_run_at, expired) "
            + "VALUES (?, ?, ?, '00:00:00', 'Daily', ?, false)", schedulerId, jobId,
            Date.valueOf(NOW.toLocalDate().minusDays(1)), BusinessTime.timestampOf(nextRunAt));
    }

    private boolean eligible(long jobId) {
        return this.sql.queryForObject("SELECT dispatch_eligible FROM scheduler WHERE job_id = ?", Boolean.class, jobId);
    }

    /** The repository's claim, as JDBC runs it: the passed-over list as a Postgres array. */
    private static String claimSql() throws Exception {
        String sql = SchedulerRepository.class.getMethod("claimNextDueScheduler", Timestamp.class, List.class)
            .getAnnotation(Query.class).value();
        return sql.replace(":now", "?").replace("(:passed)", "(select unnest(string_to_array(?, ',')::bigint[]))")
            .replace("scheduler.*", "scheduler.scheduler_id");
    }

    private Long claim(JdbcTemplate on, List<Long> passed) throws Exception {
        List<Long> found = on.queryForList(claimSql(), Long.class, BusinessTime.timestampOf(NOW),
            passed.stream().map(String::valueOf).collect(Collectors.joining(",")));
        return found.isEmpty() ? null : found.get(0);
    }

    // ---- dispatch_eligible -----------------------------------------------------------------------------------

    @Test
    void eligibilityFollowsTheJobInTheSameTransactionAsItsWrite() {
        this.job(9301, "Active", "Auto");
        this.scheduler(9301, 9301, NOW.minusMinutes(1));
        assertThat(this.eligible(9301)).as("a new schedule of an Active Auto job").isTrue();

        this.sql.update("UPDATE source_job SET execution = 'Manual' WHERE job_id = 9301");
        assertThat(this.eligible(9301)).as("the recorded Auto-to-Manual fix").isFalse();

        this.sql.update("UPDATE source_job SET execution = 'Auto' WHERE job_id = 9301");
        assertThat(this.eligible(9301)).isTrue();

        this.sql.update("UPDATE source_job SET job_status = 'Inactive' WHERE job_id = 9301");
        assertThat(this.eligible(9301)).isFalse();

        this.sql.update("UPDATE source_job SET job_status = 'Active' WHERE job_id = 9301");
        assertThat(this.eligible(9301)).isTrue();
    }

    /** A rolled-back job write rolls its eligibility back with it: one local transaction. */
    @Test
    void aRolledBackJobWriteLeavesEligibilityAsItWas() {
        this.job(9302, "Active", "Auto");
        this.scheduler(9302, 9302, NOW.minusMinutes(1));

        this.transactions.execute(status -> {
            db.jdbc().update("UPDATE source_job SET job_status = 'Delete' WHERE job_id = 9302");
            status.setRollbackOnly();
            return null;
        });

        assertThat(this.eligible(9302)).isTrue();
    }

    @Test
    void aManualOrNotActiveJobIsNeverEligible() {
        this.job(9303, "Active", "Manual");
        this.job(9304, "Delete", "Auto");
        this.scheduler(9303, 9303, NOW.minusMinutes(1));
        this.scheduler(9304, 9304, NOW.minusMinutes(1));

        assertThat(this.eligible(9303)).isFalse();
        assertThat(this.eligible(9304)).isFalse();
    }

    // ---- the claim -----------------------------------------------------------------------------------------------

    @Test
    void theClaimTakesTheOldestDueEligibleSlotOnly() throws Exception {
        this.job(9310, "Active", "Auto");
        this.job(9311, "Active", "Auto");
        this.job(9312, "Active", "Manual");
        this.job(9313, "Active", "Auto");
        this.scheduler(9310, 9310, NOW.minusMinutes(5));
        this.scheduler(9311, 9311, NOW.minusMinutes(30));
        this.scheduler(9312, 9312, NOW.minusHours(2));
        this.scheduler(9313, 9313, NOW.plusMinutes(1));

        assertThat(this.claim(this.sql, Collections.singletonList(-1L))).isEqualTo(9311L);
        assertThat(this.claim(this.sql, Arrays.asList(-1L, 9311L))).isEqualTo(9310L);
        assertThat(this.claim(this.sql, Arrays.asList(-1L, 9311L, 9310L))).isNull();
    }

    /** N replicas, one loop, no coordinator: every due slot is taken exactly once. */
    @Test
    void threeReplicasShareTheLoopAndEverySlotIsClaimedOnce() throws Exception {
        for (long jobId = 9320; jobId < 9340; jobId++) {
            this.job(jobId, "Active", "Auto");
            this.scheduler(jobId, jobId, NOW.minusMinutes(jobId - 9300));
        }
        ConcurrentLinkedQueue<Long> claimed = new ConcurrentLinkedQueue<>();
        ExecutorService replicas = Executors.newFixedThreadPool(3);
        try {
            List<Future<?>> loops = new ArrayList<>();
            for (int replica = 0; replica < 3; replica++) {
                loops.add(replicas.submit(() -> {
                    while (true) {
                        Long slot = this.transactions.execute(status -> {
                            try {
                                JdbcTemplate own = db.jdbc();
                                Long id = this.claim(own, Collections.singletonList(-1L));
                                if (id != null) {
                                    Thread.sleep(20);
                                    own.update("UPDATE scheduler SET next_run_at = next_run_at + interval '1 day' WHERE scheduler_id = ?", id);
                                }
                                return id;
                            } catch (Exception failed) {
                                throw new IllegalStateException(failed);
                            }
                        });
                        if (slot == null) {
                            return null;
                        }
                        claimed.add(slot);
                    }
                }));
            }
            for (Future<?> loop : loops) {
                loop.get(30, TimeUnit.SECONDS);
            }
        } finally {
            replicas.shutdownNow();
        }

        assertThat(claimed).hasSize(20).doesNotHaveDuplicates();
    }

    /**
     * A replica dies after claiming: its transaction -- claim, run row and advanced cursor together -- is
     * rolled back, the other replica could not take the slot while it was held, and takes it once after.
     * Exactly one run for the slot, and the next tick finds nothing due.
     */
    @Test
    void aReplicaThatDiesAfterClaimingLeavesTheSlotToBeEnqueuedExactlyOnce() throws Exception {
        this.job(9350, "Active", "Auto");
        this.scheduler(9350, 9350, NOW.minusMinutes(1));
        CountDownLatch claimedByA = new CountDownLatch(1);
        CountDownLatch killA = new CountDownLatch(1);
        ExecutorService replicas = Executors.newFixedThreadPool(1);
        try {
            Future<?> a = replicas.submit(() -> this.transactions.execute(status -> {
                try {
                    JdbcTemplate own = db.jdbc();
                    assertThat(this.claim(own, Collections.singletonList(-1L))).isEqualTo(9350L);
                    own.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, start_time, date_created, status) "
                        + "VALUES (3501, 9350, 'Queue', ?, ?, 'Active')", BusinessTime.timestampOf(NOW), BusinessTime.timestampOf(NOW));
                    claimedByA.countDown();
                    killA.await(10, TimeUnit.SECONDS);
                } catch (Exception failed) {
                    throw new IllegalStateException(failed);
                }
                status.setRollbackOnly();
                return null;
            }));
            assertThat(claimedByA.await(10, TimeUnit.SECONDS)).isTrue();

            Long whileHeld = this.transactions.execute(status -> {
                try {
                    return this.claim(db.jdbc(), Collections.singletonList(-1L));
                } catch (Exception failed) {
                    throw new IllegalStateException(failed);
                }
            });
            assertThat(whileHeld).as("skipped, not waited for").isNull();

            killA.countDown();
            a.get(10, TimeUnit.SECONDS);
        } finally {
            replicas.shutdownNow();
        }

        this.transactions.execute(status -> {
            try {
                JdbcTemplate own = db.jdbc();
                assertThat(this.claim(own, Collections.singletonList(-1L))).isEqualTo(9350L);
                own.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, start_time, date_created, status) "
                    + "VALUES (3502, 9350, 'Queue', ?, ?, 'Active')", BusinessTime.timestampOf(NOW), BusinessTime.timestampOf(NOW));
                own.update("UPDATE scheduler SET next_run_at = next_run_at + interval '1 day' WHERE scheduler_id = 9350");
            } catch (Exception failed) {
                throw new IllegalStateException(failed);
            }
            return null;
        });

        assertThat(this.sql.queryForList("SELECT job_queue_id FROM job_queue WHERE job_id = 9350", Long.class))
            .containsExactly(3502L);
        assertThat(this.claim(this.sql, Collections.singletonList(-1L))).as("the next tick").isNull();
    }
}

package process.time;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchJpa;
import process.model.pojo.JobQueue;
import process.model.repository.JobQueueRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.service.impl.TransactionServiceImpl;
import process.schema.ScratchEtlJob;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-28 / MIG-163: the queries that take a cutoff from the application's clock select the same rows after V100
 * as before it -- the stall sweep (findStalledRuns: COALESCE(start_time, date_created) &lt; now - 360 minutes), the
 * dispatcher's pick-up (next_attempt_at &lt;= its eligibleAt), and the pre-dispatch claim (next_attempt_at and
 * prepare_lease_until against its now).
 *
 * The cutoff stays a parameter, as it always was: the reason given for that (the database's now() is UTC and the
 * application's clock was Chicago) is gone with V100, but the paged pass still needs one cutoff for all its pages.
 *
 * "Before" is the query as the old application ran it: Chicago wall-clock text against a naive column, the
 * cutoff bound the way Hibernate bound a LocalDateTime on the Chicago JVM (its offset ignored against a naive
 * column). "After" is TransactionServiceImpl -- where the application's LocalDateTime becomes the query's
 * parameter -- on the JVM this suite runs on, UTC. The rows sit a second either side of each cutoff, and inside
 * the repeated hour of 1 November.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchEtlJob).
 */
class CutoffSelectionPostgresTest {

    private static final LocalDateTime SWEEP_CUTOFF = LocalDateTime.of(2026, 1, 15, 6, 0);
    private static final LocalDateTime AMBIGUOUS_CUTOFF = LocalDateTime.of(2026, 11, 1, 1, 30);
    private static final LocalDateTime DISPATCH_AT = LocalDateTime.of(2026, 1, 15, 12, 0);

    private static ScratchEtlJob db;
    private static List<Long> staleBefore;
    private static List<Long> staleAmbiguousBefore;
    private static List<Long> dispatchableBefore;
    private static List<Long> preparableBefore;

    @BeforeAll
    static void selectBeforeAndMigrate() throws Exception {
        db = ScratchEtlJob.buildUpTo("cutoff_selection", TimestamptzMigrationPostgresTest.V100);
        JdbcTemplate sql = db.sql();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (3301, 'Active', 'CUT', 'Cutoff')");
        long job = 33000;
        // Stall sweep: status, start_time, date_created. One in-flight run per job (V83), so a job each.
        Object[][] runs = {
            {330001L, "Start", "2026-01-15 05:59:59", "2026-01-15 05:00:00"},   // stalled
            {330002L, "Running", "2026-01-15 06:00:00", "2026-01-15 05:00:00"}, // exactly the cutoff: not
            {330003L, "Queue", null, "2026-01-15 05:59:59"},                    // never started, queued before: stalled
            {330004L, "Start", "2026-01-15 06:00:01", "2026-01-15 05:00:00"},   // not
            {330005L, "Completed", "2026-01-15 01:00:00", "2026-01-15 01:00:00"}, // finished: never
            {330006L, "Queue", null, "2026-01-15 07:00:00"},                    // not
            {330011L, "Start", "2026-11-01 01:15:00", "2026-11-01 01:00:00"},   // inside the repeated hour, before 01:30
            {330012L, "Start", "2026-11-01 01:45:00", "2026-11-01 01:00:00"},   // after it
            {330013L, "Start", "2026-11-01 00:59:00", "2026-11-01 00:30:00"},   // an hour before
        };
        for (Object[] run : runs) {
            sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
                + "VALUES (?, now(), 'Auto', 'cutoff', 'Active', 1, 3301)", ++job);
            sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, status, job_send, start_time, date_created) "
                + "VALUES (?, ?, ?, 'Active', true, ?::timestamp, ?::timestamp)", run[0], job, run[1], run[2], run[3]);
        }
        // Dispatcher and pre-dispatch claim: queued, unsent; prepared or not; next_attempt_at around 12:00. Created in
        // 2027, so no stall cutoff here reaches them.
        Object[][] queued = {
            {330101L, "2026-01-15 11:59:59", true, null},
            {330102L, "2026-01-15 12:00:00", true, null},   // <= : eligible
            {330103L, "2026-01-15 12:00:01", true, null},   // not
            {330104L, null, true, null},
            {330111L, "2026-01-15 11:59:59", false, null},
            {330112L, "2026-01-15 12:00:01", false, null},
            {330113L, null, false, "2026-01-15 12:00:01"},  // leased past now: not
            {330114L, null, false, "2026-01-15 11:59:59"},  // lease run out: claimable
        };
        for (Object[] run : queued) {
            sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
                + "VALUES (?, now(), 'Auto', 'cutoff', 'Active', 1, 3301)", ++job);
            sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, status, job_send, date_created, next_attempt_at, "
                + "prepared_at, prepare_lease_until) VALUES (?, ?, 'Queue', 'Active', false, '2027-01-01', ?::timestamp, "
                + (Boolean.TRUE.equals(run[2]) ? "'2026-01-15 10:00:00'" : "NULL") + ", ?::timestamp)", run[0], job, run[1], run[3]);
        }

        String stale = "SELECT job_queue_id FROM job_queue WHERE UPPER(job_status) IN ('QUEUE', 'START', 'RUNNING') "
            + "AND COALESCE(start_time, date_created) IS NOT NULL AND COALESCE(start_time, date_created) < ?::timestamp ORDER BY 1";
        staleBefore = sql.queryForList(stale, Long.class, SWEEP_CUTOFF.toString());
        staleAmbiguousBefore = sql.queryForList(stale, Long.class, AMBIGUOUS_CUTOFF.toString());
        dispatchableBefore = sql.queryForList("SELECT job_queue_id FROM job_queue WHERE UPPER(job_status) = 'QUEUE' AND job_send = false "
            + "AND prepared_at IS NOT NULL AND (next_attempt_at IS NULL OR next_attempt_at <= ?::timestamp) ORDER BY 1", Long.class,
            DISPATCH_AT.toString());
        preparableBefore = sql.queryForList("SELECT job_queue_id FROM job_queue WHERE UPPER(job_status) = 'QUEUE' AND job_send = false "
            + "AND prepared_at IS NULL AND (next_attempt_at IS NULL OR next_attempt_at <= ?::timestamp) "
            + "AND (prepare_lease_until IS NULL OR prepare_lease_until < ?::timestamp) ORDER BY 1", Long.class,
            DISPATCH_AT.toString(), DISPATCH_AT.toString());
        db.finish();
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    private static <T> T withService(Function<TransactionServiceImpl, T> call) {
        try (ScratchJpa jpa = new ScratchJpa(db.dataSource())) {
            TransactionServiceImpl service = new TransactionServiceImpl(jpa.repository(SourceJobRepository.class),
                jpa.repository(SchedulerRepository.class), jpa.repository(JobQueueRepository.class), null, null, null, null);
            return jpa.transactions().execute(status -> {
                status.setRollbackOnly();
                return call.apply(service);
            });
        }
    }

    private static List<Long> ids(List<JobQueue> runs) {
        return runs.stream().map(JobQueue::getJobQueueId).sorted().collect(Collectors.toList());
    }

    @Test
    void theBeforeSelectionsAreTheOnesTheRowsWereWrittenFor() {
        assertThat(staleBefore).containsExactly(330001L, 330003L);
        // Every January run still in flight is older than 1 November too.
        assertThat(staleAmbiguousBefore).containsExactly(330001L, 330002L, 330003L, 330004L, 330006L, 330011L, 330013L);
        assertThat(dispatchableBefore).containsExactly(330101L, 330102L, 330104L);
        assertThat(preparableBefore).containsExactly(330111L, 330114L);
    }

    @Test
    void theStallSweepSelectsTheSameRuns() {
        List<Long> stale = withService(service -> ids(service.findStalledRuns(SWEEP_CUTOFF)));
        List<Long> staleAmbiguous = withService(service -> ids(service.findStalledRuns(AMBIGUOUS_CUTOFF)));
        assertThat(stale).isEqualTo(staleBefore);
        assertThat(staleAmbiguous).isEqualTo(staleAmbiguousBefore);
    }

    @Test
    void theDispatcherPicksUpTheSameRuns() {
        List<Long> dispatchable = withService(service -> ids(service.findAllJobForTodayWithLimit(100L, DISPATCH_AT)));
        assertThat(dispatchable).isEqualTo(dispatchableBefore);
    }

    @Test
    void thePreDispatchClaimTakesTheSameRuns() {
        List<Long> claimed = withService(service -> service.claimRunsToPrepare(DISPATCH_AT, 100, DISPATCH_AT.plusMinutes(5)));
        assertThat(claimed.stream().sorted().collect(Collectors.toList())).isEqualTo(preparableBefore);
    }
}

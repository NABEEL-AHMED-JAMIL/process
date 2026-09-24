package process.engine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import process.ScratchPostgres;
import process.model.repository.JobQueueRepository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * MIG-113 / MIG-135 against a real Postgres (V83): the partial unique index, and T1.
 *
 * T1 is the gate for the wave: a job due now produces exactly ONE in-flight job_queue row with two
 * enqueuers running. Here both enqueuers are held at the worst moment -- each has run the dispatcher's
 * own busy count and seen 0 -- and then both insert. Before V83 both rows landed; now the index takes
 * one and refuses the other with the violation OneRunInFlight recognises, which the enqueuer turns
 * into its ordinary "already in queue" skip (OneRunInFlightTest).
 *
 * Opt-in: runs when NOTIFICATIONS_TEST_DB_URL points at a Postgres server (see ScratchPostgres).
 */
class OneRunInFlightPostgresTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 24, 12, 0);
    private static final String V83 = "src/main/resources/db/changelog/changelog-sets/"
        + "V83.0-one-in-flight-run-per-job/V83__one_in_flight_run_per_job.sql";

    private static ScratchPostgres db;
    private JdbcTemplate sql;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("one_in_flight");
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
        this.sql.update("DELETE FROM job_queue WHERE job_id BETWEEN 9201 AND 9299");
        this.sql.update("DELETE FROM source_job WHERE job_id BETWEEN 9201 AND 9299");
    }

    private void job(long jobId) {
        // Every job has a tenant (V102: its runs and schedule carry it, NOT NULL).
        this.sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (9999, 'Active', 'FIXTURE', 'Fixture') "
            + "ON CONFLICT DO NOTHING");
        this.sql.update("INSERT INTO source_job (tenant_id, job_id, date_created, execution, job_name, job_status, priority) "
            + "VALUES (9999, ?, ?, 'Auto', ?, 'Active', 1)", jobId, Timestamp.valueOf(NOW.minusDays(1)), "p12-" + jobId);
    }

    private static void run(JdbcTemplate sql, long jobQueueId, long jobId, String status) {
        sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, start_time, date_created, status) "
            + "VALUES (?, ?, ?, ?, ?, 'Active')", jobQueueId, jobId, status,
            Timestamp.valueOf(NOW), Timestamp.valueOf(NOW));
    }

    private int inFlight(long jobId) throws Exception {
        String count = JobQueueRepository.class.getMethod("getCountForInQueueJobByJobId", Long.class)
            .getAnnotation(Query.class).value().replace("?1", "?");
        return this.sql.queryForObject(count, Integer.class, jobId);
    }

    /** T1: both enqueuers pass the COUNT(*), both insert; the database keeps exactly one. */
    @Test
    void twoEnqueuersThatBothSawNothingInFlightLeaveExactlyOneRun() throws Exception {
        this.job(9201);
        TransactionTemplate transactions = db.transactions();
        CyclicBarrier bothCounted = new CyclicBarrier(2);
        ExecutorService enqueuers = Executors.newFixedThreadPool(2);
        try {
            List<Future<Throwable>> outcomes = new ArrayList<>();
            for (long jobQueueId : new long[] {2011L, 2012L}) {
                Callable<Throwable> enqueue = () -> catchThrowable(() -> transactions.execute(status -> {
                    try {
                        assertThat(this.inFlight(9201)).isZero();
                        bothCounted.await(10, TimeUnit.SECONDS);
                    } catch (Exception interrupted) {
                        throw new IllegalStateException(interrupted);
                    }
                    run(db.jdbc(), jobQueueId, 9201, "Queue");
                    return null;
                }));
                outcomes.add(enqueuers.submit(enqueue));
            }
            Throwable first = outcomes.get(0).get(20, TimeUnit.SECONDS);
            Throwable second = outcomes.get(1).get(20, TimeUnit.SECONDS);

            assertThat(first == null ^ second == null).as("exactly one enqueuer wins").isTrue();
            assertThat(OneRunInFlight.isViolation(first != null ? first : second))
                .as("and the loser is refused by the index, recognisably").isTrue();
            assertThat(this.inFlight(9201)).isEqualTo(1);
        } finally {
            enqueuers.shutdownNow();
        }
    }

    /** Only in-flight rows count: a job's history of finished, skipped and missed runs is untouched. */
    @Test
    void finishedSkippedAndMissedRunsDoNotOccupyTheJob() {
        this.job(9202);
        run(this.sql, 2021L, 9202, "Completed");
        run(this.sql, 2022L, 9202, "Failed");
        run(this.sql, 2023L, 9202, "Skip");
        run(this.sql, 2024L, 9202, "Missed");
        run(this.sql, 2025L, 9202, "Interrupt");

        run(this.sql, 2026L, 9202, "Queue");
        run(this.sql, 2027L, 9202, "Skip");

        assertThat(this.sql.queryForObject("SELECT count(*) FROM job_queue WHERE job_id = 9202", Integer.class)).isEqualTo(7);
    }

    /** Start is in flight too -- the state the old "Run now" check forgot -- whatever case it is written in. */
    @Test
    void everyInFlightStatusInAnyCaseOccupiesTheJob() {
        String[][] pairs = {{"Start", "Queue"}, {"running", "Queue"}, {"QUEUE", "Start"}};
        long jobId = 9203;
        long jobQueueId = 2031;
        for (String[] pair : pairs) {
            this.job(jobId);
            run(this.sql, jobQueueId++, jobId, pair[0]);
            long second = jobQueueId++;
            long job = jobId;
            Throwable refused = catchThrowable(() -> run(this.sql, second, job, pair[1]));
            assertThat(OneRunInFlight.isViolation(refused)).as("%s then %s", pair[0], pair[1]).isTrue();
            jobId++;
        }
    }

    /** A retry re-uses its own row, Running back to Queue: the same row, so no conflict with itself. */
    @Test
    void aRetryRequeuingItsOwnRowIsNotAConflict() {
        this.job(9210);
        run(this.sql, 2101L, 9210, "Running");

        int updated = this.sql.update("UPDATE job_queue SET job_status = 'Queue', job_send = false WHERE job_queue_id = 2101");

        assertThat(updated).isEqualTo(1);
    }

    /**
     * The changeset resolves duplicates before it builds the index: per job it keeps the most advanced
     * run (Running, then Start, then Queue; the newest among equals) and closes the rest as Interrupt.
     */
    @Test
    void existingDuplicatesAreResolvedBeforeTheIndexIsBuilt() throws Exception {
        this.sql.execute("DROP INDEX ux_job_queue_one_in_flight_per_job");
        this.job(9220);
        this.job(9221);
        run(this.sql, 2201L, 9220, "Queue");
        run(this.sql, 2202L, 9220, "Running");
        run(this.sql, 2203L, 9220, "Start");
        run(this.sql, 2204L, 9220, "Queue");
        run(this.sql, 2205L, 9221, "Queue");
        run(this.sql, 2206L, 9221, "queue");
        run(this.sql, 2207L, 9221, "Completed");

        String script = new String(Files.readAllBytes(Paths.get(V83)), StandardCharsets.UTF_8)
            .replaceAll("(?m)^\\s*--.*$", "");
        for (String statement : script.split(";")) {
            if (!statement.trim().isEmpty()) {
                this.sql.execute(statement.trim());
            }
        }

        List<Map<String, Object>> rows = this.sql.queryForList(
            "SELECT job_queue_id, job_status, end_time, job_status_message FROM job_queue "
                + "WHERE job_id IN (9220, 9221) ORDER BY job_queue_id");
        assertThat(rows).extracting(row -> row.get("job_status")).containsExactly(
            "Interrupt", "Running", "Interrupt", "Interrupt", "Interrupt", "queue", "Completed");
        for (Map<String, Object> row : rows) {
            if ("Interrupt".equals(row.get("job_status"))) {
                assertThat(row.get("end_time")).isNotNull();
                assertThat((String) row.get("job_status_message")).contains("V83");
            }
        }
        assertThat(this.sql.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE indexname = 'ux_job_queue_one_in_flight_per_job'", Integer.class))
            .isEqualTo(1);
    }
}

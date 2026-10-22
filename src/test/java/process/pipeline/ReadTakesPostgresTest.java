package process.pipeline;

import org.barco.platform.tenancy.RowSecurity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import process.ScratchPostgres;
import process.pipeline.backing.ReadTakes;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Owner 2026-10-21, run_read_take (V221) against a real etl_job as process_app: runs of one job side by side (a workflow's
 * requests) never take the same item, a failed run's items are free again, a completed run's are forgotten, two takes at
 * the same moment split the items, and another workspace sees none of it. Opt-in on NOTIFICATIONS_TEST_DB_URL (see
 * ScratchPostgres).
 */
class ReadTakesPostgresTest {

    static final long A = 4721L;
    static final long B = 4722L;
    static final long JOB = 9711L;
    static final String S3 = "s3|lake|in/";

    private static ScratchPostgres db;
    private JdbcTemplate sql;
    private JdbcReadTakes takes;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("run_read_takes");
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
        this.sql.update("DELETE FROM run_read_take");
        this.sql.update("DELETE FROM job_queue WHERE job_id = ?", JOB);
        this.sql.update("DELETE FROM source_job WHERE job_id = ?", JOB);
        for (long tenant : new long[] {A, B}) {
            this.sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?) ON CONFLICT DO NOTHING",
                tenant, "TAKE" + tenant, "Takes " + tenant);
        }
        this.sql.update("INSERT INTO source_job (tenant_id, job_id, date_created, execution, job_name, job_status, priority, created_by) "
            + "VALUES (?, ?, ?, 'Manual', 'takes', 'Active', 1, 61)", A, JOB, Timestamp.valueOf(LocalDateTime.now().minusDays(1)));
        this.takes = new JdbcReadTakes(db.appJdbc(), new DataSourceTransactionManager(db.appJdbc().getDataSource()));
    }

    /** A workflow request's run of the job (side by side with the others), in the given status. */
    private void run(long runId, String status) {
        this.sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, start_time, date_created, status, workflow_instance_id) "
            + "VALUES (?, ?, ?, now(), now(), 'Active', ?)", runId, JOB, status, runId);
    }

    private void status(long runId, String status) {
        this.sql.update("UPDATE job_queue SET job_status = ? WHERE job_queue_id = ?", status, runId);
    }

    private ReadTakes.Taken take(long runId, List<String> items, Set<String> fresh, int limit) throws Exception {
        return RowSecurity.forTenant(A, () -> this.takes.take(runId, JOB, "read", S3, items, fresh, limit));
    }

    private static Set<String> set(String... items) {
        return new HashSet<>(Arrays.asList(items));
    }

    @Test
    void runsSideBySideTakeDifferentItemsAndAFailedRunsComeAgain() throws Exception {
        List<String> all = Arrays.asList("1:a", "2:b", "3:c");
        run(971101L, "Running");
        run(971102L, "Running");
        ReadTakes.Taken first = take(971101L, all, set("1:a", "2:b", "3:c"), 2);
        assertThat(first.items).containsExactly("1:a", "2:b");
        assertThat(first.more).isTrue();
        ReadTakes.Taken beside = take(971102L, all, set("1:a", "2:b", "3:c"), 10);
        assertThat(beside.items).containsExactly("3:c");
        assertThat(beside.heldByOthers).isEqualTo(2);
        assertThat(take(971101L, all, set(), 10).items).as("a run's later try keeps its own, behind the mark or not")
            .containsExactly("1:a", "2:b");

        // 971102 completes (its take is forgotten: it is behind the watermark); 971101 fails: its items are free again.
        status(971102L, "Completed");
        status(971101L, "Failed");
        assertThat(RowSecurity.forTenant(A, () -> this.takes.released(JOB, "read", S3))).containsExactly("1:a", "2:b");
        run(971103L, "Running");
        ReadTakes.Taken again = take(971103L, Arrays.asList("1:a", "2:b", "3:c", "4:d"), set("4:d"), 10);
        assertThat(again.items).as("not the completed run's 3:c; the failed run's, and what is new").containsExactly("1:a", "2:b", "4:d");
        assertThat(this.sql.queryForObject("SELECT count(*) FROM run_read_take WHERE job_queue_id = 971102", Long.class))
            .as("the completed run's takes are forgotten").isZero();
        assertThat(this.sql.queryForObject("SELECT tenant_id FROM run_read_take WHERE item = '4:d'", Long.class)).isEqualTo(A);

        assertThat(RowSecurity.forTenant(B, () -> this.takes.released(JOB, "read", S3))).isEmpty();
        assertThat(RowSecurity.forTenant(B, () -> db.appJdbc().queryForObject("SELECT count(*) FROM run_read_take", Long.class))).isZero();
    }

    @Test
    void twoTakesAtTheSameMomentSplitTheItems() throws Exception {
        List<String> all = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            all.add(i + ":object-" + i);
        }
        Set<String> fresh = new HashSet<>(all);
        long[] runs = {971111L, 971112L, 971113L, 971114L};
        for (long r : runs) {
            run(r, "Running");
        }
        CyclicBarrier start = new CyclicBarrier(runs.length);
        ExecutorService pool = Executors.newFixedThreadPool(runs.length);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (long r : runs) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    return take(r, all, fresh, 15).items;
                }));
            }
            List<String> together = new ArrayList<>();
            for (Future<List<String>> f : futures) {
                together.addAll(f.get(60, TimeUnit.SECONDS));
            }
            assertThat(together).as("every item once, whoever asked first").hasSize(all.size()).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(all);
        } finally {
            pool.shutdownNow();
        }
    }
}

package process.pipeline;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.ScratchPostgres;
import process.pipeline.backing.ObjectReadMarks;
import org.barco.platform.tenancy.RowSecurity;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * read_s3 "since the last run" against a real etl_job as process_app: run_object_read keeps a run's newest object; only a
 * COMPLETED run's mark is the job's watermark (per step, bucket and prefix); the row is held to the run's workspace, and
 * another workspace sees none of it. Opt-in on NOTIFICATIONS_TEST_DB_URL (see ScratchPostgres).
 */
class ObjectReadMarksPostgresTest {

    static final long A = 4711L;
    static final long B = 4712L;
    static final Instant T = Instant.parse("2026-10-20T10:00:00Z");

    private static ScratchPostgres db;
    private JdbcTemplate sql;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchPostgres.create("object_read_marks");
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
        this.sql.update("DELETE FROM run_object_read");
        this.sql.update("DELETE FROM job_queue WHERE job_id BETWEEN 9701 AND 9709");
        this.sql.update("DELETE FROM source_job WHERE job_id BETWEEN 9701 AND 9709");
        for (long tenant : new long[] {A, B}) {
            this.sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?) ON CONFLICT DO NOTHING",
                tenant, "OBJ" + tenant, "Objects " + tenant);
        }
        this.sql.update("INSERT INTO source_job (tenant_id, job_id, date_created, execution, job_name, job_status, priority, created_by) "
            + "VALUES (?, 9701, ?, 'Manual', 'objects-9701', 'Active', 1, 61)", A, Timestamp.valueOf(LocalDateTime.now().minusDays(1)));
    }

    private void run(long runId, String status) {
        this.sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, start_time, date_created, status) "
            + "VALUES (?, 9701, ?, now(), now(), 'Active')", runId, status);
    }

    @Test
    void onlyACompletedRunsMarkIsTheWatermarkAndItStaysInItsWorkspace() throws Exception {
        JdbcObjectReadMarks marks = new JdbcObjectReadMarks(db.appJdbc());
        run(970101L, "Completed");
        run(970102L, "Failed");
        run(970103L, "Completed");
        RowSecurity.forTenant(A, () -> {
            marks.record(970101L, "list", 9701L, "lake", "in/", new ObjectReadMarks.Mark(T, "in/a"), 1, 1);
            marks.record(970102L, "list", 9701L, "lake", "in/", new ObjectReadMarks.Mark(T.plusSeconds(600), "in/z"), 1, 1);
            marks.record(970103L, "list", 9701L, "lake", "in/", new ObjectReadMarks.Mark(T.plusSeconds(60), "in/b"), 1, 1);
            // A try asked again replaces its run's row.
            marks.record(970103L, "list", 9701L, "lake", "in/", new ObjectReadMarks.Mark(T.plusSeconds(120), "in/c"), 2, 2);
            return null;
        });

        ObjectReadMarks.Mark mark = RowSecurity.forTenant(A, () -> marks.watermark(9701L, "list", "lake", "in/")).get();
        assertThat(mark.key).as("the failed run's later mark is not the job's").isEqualTo("in/c");
        assertThat(mark.modified).isEqualTo(T.plusSeconds(120));
        assertThat(RowSecurity.forTenant(A, () -> marks.watermark(9701L, "list", "lake", "other/"))).isEmpty();
        assertThat(RowSecurity.forTenant(A, () -> marks.watermark(9701L, "other-step", "lake", "in/"))).isEmpty();
        assertThat(this.sql.queryForObject("SELECT tenant_id FROM run_object_read WHERE job_queue_id = 970103", Long.class)).isEqualTo(A);
        assertThat(RowSecurity.forTenant(B, () -> marks.watermark(9701L, "list", "lake", "in/"))).isEmpty();
        assertThat(RowSecurity.forTenant(B, () -> db.appJdbc().queryForObject("SELECT count(*) FROM run_object_read", Long.class))).isZero();
    }
}

package process.engine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.schema.ScratchEtlJob;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V220's way back: a workflow's run still in flight beside another run of its job would break the old one-in-flight index,
 * so the rollback closes it (Interrupt, saying why) before rebuilding the index; then V220 applies again.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchEtlJob).
 */
class WorkflowRunsRollbackPostgresTest {

    static final String V220 = "220.0-workflow-runs";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 12, 0);
    private static ScratchEtlJob db;

    @BeforeAll
    static void migrate() throws Exception {
        db = ScratchEtlJob.buildUpTo("workflow_runs_rollback", V220);
        db.finish();
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    private static long count(String sql, Object... args) {
        return db.sql().queryForObject(sql, Long.class, args);
    }

    @Test
    void theRollbackClosesAWorkflowRunBesideAnotherAndV220AppliesAgain() throws Exception {
        JdbcTemplate sql = db.sql();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (9999, 'Active', 'FIXTURE', 'Fixture') ON CONFLICT DO NOTHING");
        sql.update("INSERT INTO source_job (tenant_id, job_id, date_created, execution, job_name, job_status, priority) "
            + "VALUES (9999, 9401, ?, 'Manual', 'episode pipeline', 'Active', 1)", Timestamp.valueOf(NOW));
        for (Object[] r : new Object[][] {{940101L, null}, {940102L, 1001L}, {940103L, 1002L}}) {
            sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, start_time, date_created, status, workflow_instance_id) "
                + "VALUES (?, 9401, 'Running', ?, ?, 'Active', ?)", r[0], Timestamp.valueOf(NOW), Timestamp.valueOf(NOW), r[1]);
        }
        db.rollback((int) count("SELECT count(*) FROM databasechangelog WHERE orderexecuted >= "
            + "(SELECT orderexecuted FROM databasechangelog WHERE id = ?)", V220));
        try {
            assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_name = 'job_queue' AND column_name = 'workflow_instance_id'"))
                .isZero();
            assertThat(count("SELECT count(*) FROM job_queue WHERE job_id = 9401 AND UPPER(job_status) IN ('QUEUE','START','RUNNING')"))
                .as("the job's own run stays in flight; both workflow runs are closed").isEqualTo(1);
            assertThat(db.sql().queryForObject("SELECT job_status_message FROM job_queue WHERE job_queue_id = 940102", String.class))
                .startsWith("Closed by V220 rollback");
        } finally {
            db.finish();
        }
        assertThat(count("SELECT count(*) FROM pg_indexes WHERE indexname = 'ux_job_queue_one_in_flight_per_workflow'")).isEqualTo(1);
    }
}

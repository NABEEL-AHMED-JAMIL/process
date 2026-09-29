package process.schema;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V188 (Wave 4) as the changelog builds it: run_output -- a run's result manifest, one row per step execution, its
 * tenant taken from the step, guarded as every tenant table (V181), gone with its step -- and V188 rolls back to exactly
 * what was there before and applies again.
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD (see ScratchEtlJob).
 */
class RunOutputSchemaPostgresTest {

    static final String V188 = "188.0-run-output";
    private static final long A = 8841L;
    private static final long B = 8842L;

    private static ScratchEtlJob db;
    private static JdbcTemplate sql;
    private static long stepOfA;
    private static long stepOfB;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("run_output_v188");
        sql = db.sql();
        stepOfA = step(A, 884101L, 88410001L);
        stepOfB = step(B, 884201L, 88420001L);
    }

    private static long step(long tenant, long job, long run) {
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?)", tenant, "t" + tenant,
            "T" + tenant);
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
            + "VALUES (?, now(), 'Manual', 'manifest job', 'Active', 1, ?)", job, tenant);
        sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, date_created, start_time, status, job_send, attempt) "
            + "VALUES (?, ?, 'Completed', now(), now(), 'Active', true, 1)", run, job);
        return sql.queryForObject("INSERT INTO step_execution (job_queue_id, step_index, task_code, step_key, status) "
            + "VALUES (?, 0, 'save_file', 'keep', 'Completed') RETURNING step_execution_id", Long.class, run);
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void oneRowPerStepExecutionWithItsStepsTenantGoneWithTheStep() {
        long id = sql.queryForObject("INSERT INTO run_output (step_execution_id, kind, name, format, row_count, byte_count, run_dataset_id) "
            + "VALUES (?, 'file', 'claims.csv', 'csv', 2, 40, 1234) RETURNING run_output_id", Long.class, stepOfA);
        assertThat(id).isGreaterThanOrEqualTo(1000L);
        assertThat(sql.queryForObject("SELECT tenant_id FROM run_output WHERE run_output_id = ?", Long.class, id)).isEqualTo(A);
        assertThatThrownBy(() -> sql.update("INSERT INTO run_output (step_execution_id, kind, name, format, bucket_alias, object_key) "
            + "VALUES (?, 'bucket', 'x.csv', 'csv', 'exports', 'x.csv')", stepOfA))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ux_run_output_step");
        sql.update("DELETE FROM step_execution WHERE step_execution_id = ?", stepOfA);
        assertThat(sql.queryForObject("SELECT count(*) FROM run_output WHERE run_output_id = ?", Long.class, id)).isZero();
        stepOfA = sql.queryForObject("INSERT INTO step_execution (job_queue_id, step_index, task_code, step_key, status) "
            + "VALUES (88410001, 0, 'save_file', 'keep', 'Completed') RETURNING step_execution_id", Long.class);
    }

    @Test
    void aFileIsADatasetAndAnUploadIsABucketObject() {
        assertThatThrownBy(() -> sql.update("INSERT INTO run_output (step_execution_id, kind, name, format) VALUES (?, 'file', 'a.csv', "
            + "'csv')", stepOfB)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_run_output_file");
        assertThatThrownBy(() -> sql.update("INSERT INTO run_output (step_execution_id, kind, name, format, bucket_alias) VALUES "
            + "(?, 'bucket', 'a.csv', 'csv', 'exports')", stepOfB))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_run_output_bucket");
        assertThatThrownBy(() -> sql.update("INSERT INTO run_output (step_execution_id, kind, name, format, run_dataset_id) VALUES "
            + "(?, 'file', 'a.parquet', 'parquet', 1)", stepOfB))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_run_output_format");
        assertThatThrownBy(() -> sql.update("INSERT INTO run_output (step_execution_id, kind, name, format, run_dataset_id) VALUES "
            + "(?, 'report', 'a.csv', 'csv', 1)", stepOfB))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_run_output_kind");
    }

    @Test
    void theTableIsGuardedAndTheApplicationSeesAndWritesItsOwnWorkspaceOnly() throws Exception {
        assertThat(sql.queryForMap("SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid = 'public.run_output'::regclass"))
            .containsEntry("relrowsecurity", true).containsEntry("relforcerowsecurity", true);
        assertThat(sql.queryForObject("SELECT qual FROM pg_policies WHERE tablename = 'run_output' AND policyname = 'tenant_isolation'",
            String.class)).isEqualTo(sql.queryForObject("SELECT qual FROM pg_policies WHERE tablename = 'run_ai_step'", String.class));
        sql.update("INSERT INTO run_output (step_execution_id, kind, name, format, bucket_alias, object_key) VALUES (?, 'bucket', "
            + "'b.csv', 'csv', 'exports', 'out/b.csv') ON CONFLICT DO NOTHING", stepOfB);
        try (Connection connection = db.connect()) {
            connection.createStatement().execute("SET ROLE process_app");
            connection.createStatement().execute("SELECT set_config('app.tenant_id', '" + A + "', false)");
            ResultSet seen = connection.createStatement().executeQuery("SELECT DISTINCT tenant_id FROM run_output");
            List<Long> tenants = new ArrayList<>();
            while (seen.next()) {
                tenants.add(seen.getLong(1));
            }
            assertThat(tenants).doesNotContain(B);
            assertThatThrownBy(() -> connection.createStatement().execute("INSERT INTO run_output (step_execution_id, tenant_id, kind, "
                + "name, format, bucket_alias, object_key) VALUES (" + stepOfB + ", " + B + ", 'bucket', 'c.csv', 'csv', 'x', 'c.csv')"))
                .hasMessageContaining("row-level security");
            assertThat(connection.createStatement().executeUpdate("DELETE FROM run_output WHERE tenant_id = " + B))
                .as("B's manifest is not A's to remove").isZero();
        }
    }

    @Test
    void theRollbackTakesItOffAndV188AppliesAgain() throws Exception {
        int after = sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE orderexecuted > "
            + "(SELECT orderexecuted FROM databasechangelog WHERE id = ?)", Integer.class, V188);
        if (after > 0) {
            db.rollback(after);
        }
        db.rollback(1);
        try {
            assertThat(sql.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' "
                + "AND table_name = 'run_output'", Long.class)).isZero();
            assertThat(sql.queryForObject("SELECT count(*) FROM pg_sequences WHERE schemaname = 'public' "
                + "AND sequencename = 'run_output_seq'", Long.class)).isZero();
        } finally {
            db.finish();
        }
        assertThat(sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE id = ?", Long.class, V188)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT count(*) FROM pg_policies WHERE tablename = 'run_output'", Long.class)).isEqualTo(1);
    }
}

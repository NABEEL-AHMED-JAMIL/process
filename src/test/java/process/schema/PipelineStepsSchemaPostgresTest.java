package process.schema;

import java.sql.ResultSet;
import java.sql.Connection;
import org.barco.platform.tenancy.RowSecurity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V183 (MIG-230) as the changelog builds it: pipeline_definition -- a version per save, in its pipeline's workspace, a
 * JSON object with steps, kept exactly as written -- step_execution's step columns with their vocabularies, and
 * step_log, gone with its step. Both new tables are guarded as every tenant table (V181). V183 rolls back to exactly
 * what was there before and applies again.
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD (see ScratchEtlJob).
 */
class PipelineStepsSchemaPostgresTest {

    static final String V183 = "183.0-pipeline-steps";
    private static final long A = 7831L;
    private static final long B = 7832L;
    private static final long PLATFORM_PIPELINE = 78300L;

    private static ScratchEtlJob db;
    private static JdbcTemplate sql;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("pipeline_steps_v183");
        sql = db.sql();
        for (long tenant : new long[] {A, B}) {
            sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
                + "VALUES (?, now(), 'Manual', 'claims', 'Active', 1, ?)", tenant * 10, tenant);
            sql.update("INSERT INTO pipeline (pipeline_key, tenant_id, pipeline_id, pipeline_name, status) VALUES (?, ?, ?, 'Claims', 'Active')",
                tenant * 10, tenant, "P" + tenant);
        }
        sql.update("INSERT INTO pipeline (pipeline_key, tenant_id, pipeline_id, pipeline_name, status) VALUES (?, NULL, 'PLAT', 'Platform', 'Active')",
            PLATFORM_PIPELINE);
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    private static long definition(long pipelineKey, int version, String json) {
        return sql.queryForObject("INSERT INTO pipeline_definition (pipeline_key, version, definition) VALUES (?, ?, ?::json) "
            + "RETURNING pipeline_definition_id", Long.class, pipelineKey, version, json);
    }

    private static long run(long tenant, long id) {
        sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, date_created, status, attempt) "
            + "VALUES (?, ?, 'Completed', now(), 'Active', 1)", id, tenant * 10);
        return id;
    }

    private static long step(long runId, int index, String key) {
        return sql.queryForObject("INSERT INTO step_execution (job_queue_id, step_index, task_code, step_key, on_error) "
            + "VALUES (?, ?, 'sample', ?, 'fail') RETURNING step_execution_id", Long.class, runId, index, key);
    }

    private static final String STEPS = "{\"version\":1,\"steps\":[{\"key\":\"read\",\"task\":\"sample\",\"config\":{\"z\":1,\"a\":2}}]}";

    @Test
    void aDefinitionIsInItsPipelinesWorkspaceKeptExactlyAsWritten() {
        long id = definition(A * 10, 1, STEPS);
        assertThat(sql.queryForObject("SELECT tenant_id FROM pipeline_definition WHERE pipeline_definition_id = ?", Long.class, id))
            .isEqualTo(A);
        // json, not jsonb: the keys keep the order they were written in.
        assertThat(sql.queryForObject("SELECT definition::text FROM pipeline_definition WHERE pipeline_definition_id = ?", String.class, id))
            .isEqualTo(STEPS);
        assertThatThrownBy(() -> sql.update("INSERT INTO pipeline_definition (tenant_id, pipeline_key, version, definition) "
            + "VALUES (?, ?, 2, ?::json)", B, A * 10, STEPS)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("fk_pipeline_definition_pipeline_tenant");
        assertThatThrownBy(() -> definition(PLATFORM_PIPELINE, 1, STEPS)).as("a platform pipeline has no workspace to keep one in")
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void oneRowPerVersionAnObjectWithStepsAndAPositiveVersion() {
        definition(B * 10, 1, STEPS);
        assertThatThrownBy(() -> definition(B * 10, 1, STEPS)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ux_pipeline_definition_version");
        String[][] bad = {
            {"[1]", "ck_pipeline_definition_object"},
            {"{\"version\":1}", "ck_pipeline_definition_steps"},
            {"{\"version\":1,\"steps\":[]}", "ck_pipeline_definition_steps"},
            {"{\"version\":1,\"steps\":{}}", "ck_pipeline_definition_steps"}};
        int version = 10;
        for (String[] b : bad) {
            int v = version++;
            assertThatThrownBy(() -> definition(B * 10, v, b[0])).as(b[0]).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(b[1]);
        }
        assertThatThrownBy(() -> definition(B * 10, 0, STEPS)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_pipeline_definition_version");
    }

    @Test
    void aStepNamesItsKeyTriesAndOnErrorOnceEachPerAttempt() {
        long runA = run(A, 783101);
        long defA = definition(A * 10, 5, STEPS);
        long stepA = step(runA, 0, "read");
        assertThat(sql.queryForMap("SELECT tries, step_key, on_error FROM step_execution WHERE step_execution_id = ?", stepA))
            .containsEntry("tries", 0).containsEntry("step_key", "read").containsEntry("on_error", "fail");
        sql.update("UPDATE step_execution SET pipeline_definition_id = ?, tries = 3, status_message = 'ok' WHERE step_execution_id = ?",
            defA, stepA);
        assertThatThrownBy(() -> step(runA, 1, "read")).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ux_step_execution_run_step_key");
        assertThatThrownBy(() -> sql.update("UPDATE step_execution SET on_error = 'ignore' WHERE step_execution_id = ?", stepA))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_step_execution_on_error");
        assertThatThrownBy(() -> sql.update("UPDATE step_execution SET tries = -1 WHERE step_execution_id = ?", stepA))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_step_execution_tries");
        assertThatThrownBy(() -> sql.update("UPDATE step_execution SET pipeline_definition_id = 1 WHERE step_execution_id = ?", stepA))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_step_execution_definition");
    }

    @Test
    void aStepsLogIsInItsWorkspaceInOrderAndGoesWithItsRun() {
        long runA = run(A, 783201);
        long stepA = step(runA, 0, "read");
        sql.update("INSERT INTO step_log (step_execution_id, line_no, message) VALUES (?, 1, 'one')", stepA);
        sql.update("INSERT INTO step_log (step_execution_id, line_no, level, message) VALUES (?, 2, 'WARN', 'two')", stepA);
        assertThat(sql.queryForObject("SELECT tenant_id FROM step_log WHERE step_execution_id = ? AND line_no = 1", Long.class, stepA))
            .isEqualTo(A);
        assertThatThrownBy(() -> sql.update("INSERT INTO step_log (step_execution_id, line_no, message) VALUES (?, 2, 'again')", stepA))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ux_step_log_line");
        assertThatThrownBy(() -> sql.update("INSERT INTO step_log (step_execution_id, line_no, level, message) VALUES (?, 3, 'DEBUG', 'x')",
            stepA)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_step_log_level");
        assertThatThrownBy(() -> sql.update("INSERT INTO step_log (step_execution_id, line_no, message) VALUES (?, 0, 'x')", stepA))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_step_log_line_no");
        assertThatThrownBy(() -> sql.update("INSERT INTO step_log (tenant_id, step_execution_id, line_no, message) VALUES (?, ?, 4, 'x')",
            B, stepA)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_step_log_step_tenant");
        sql.update("DELETE FROM job_queue WHERE job_queue_id = ?", runA);
        assertThat(sql.queryForObject("SELECT count(*) FROM step_log WHERE step_execution_id = ?", Long.class, stepA)).isZero();
    }

    @Test
    void bothNewTablesAreGuardedAndTheApplicationSeesItsOwnWorkspaceOnly() throws Exception {
        for (String table : new String[] {"pipeline_definition", "step_log"}) {
            assertThat(sql.queryForMap("SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid = ?::regclass", "public." + table))
                .as(table).containsEntry("relrowsecurity", true).containsEntry("relforcerowsecurity", true);
            assertThat(sql.queryForList("SELECT policyname FROM pg_policies WHERE tablename = ?", String.class, table)).as(table)
                .containsExactly("tenant_isolation");
            assertThat(sql.queryForObject("SELECT qual FROM pg_policies WHERE tablename = ?", String.class, table))
                .isEqualTo(sql.queryForObject("SELECT qual FROM pg_policies WHERE tablename = 'run_ai_step'", String.class));
        }
        definition(A * 10, 20, STEPS);
        definition(B * 10, 20, STEPS);
        try (Connection connection = db.connect()) {
            connection.createStatement().execute("SET ROLE process_app");
            connection.createStatement().execute("SELECT set_config('app.tenant_id', '" + A + "', false)");
            ResultSet seen = connection.createStatement().executeQuery("SELECT DISTINCT tenant_id FROM pipeline_definition");
            List<Long> tenants = new ArrayList<>();
            while (seen.next()) {
                tenants.add(seen.getLong(1));
            }
            assertThat(tenants).containsExactly(A);
            assertThatThrownBy(() -> connection.createStatement().execute("INSERT INTO pipeline_definition (pipeline_key, version, "
                + "definition) VALUES (" + B * 10 + ", 21, '" + STEPS + "'::json)")).hasMessageContaining("row-level security");
        }
        assertThat(RowSecurity.OWNED_ROWS).isNotEmpty();
    }

    /** V183's rollback takes off exactly its own tables, sequences and columns; then V183 applies again. */
    @Test
    void theRollbackTakesItAllOffAndV183AppliesAgain() throws Exception {
        int after = sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE orderexecuted > "
            + "(SELECT orderexecuted FROM databasechangelog WHERE id = ?)", Integer.class, V183);
        if (after > 0) {
            db.rollback(after);
        }
        List<String> withV183 = columns("step_execution");
        db.rollback(1);
        try {
            for (String gone : new String[] {"pipeline_definition", "step_log"}) {
                assertThat(sql.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' "
                    + "AND table_name = ?", Long.class, gone)).as(gone).isZero();
                assertThat(sql.queryForObject("SELECT count(*) FROM pg_sequences WHERE schemaname = 'public' AND sequencename = ?",
                    Long.class, gone + "_seq")).as(gone).isZero();
            }
            List<String> kept = new ArrayList<>(withV183);
            kept.removeIf(c -> Arrays.asList("step_key", "pipeline_definition_id", "tries", "on_error", "status_message")
                .contains(c.substring(0, c.indexOf(' '))));
            assertThat(columns("step_execution")).isEqualTo(kept);
        } finally {
            db.finish();
        }
        assertThat(columns("step_execution")).isEqualTo(withV183);
        assertThat(sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE id = ?", Long.class, V183)).isEqualTo(1);
        Map<String, Object> policy = sql.queryForMap("SELECT count(*) AS n FROM pg_policies WHERE tablename IN ('pipeline_definition', 'step_log')");
        assertThat(((Number) policy.get("n")).intValue()).isEqualTo(2);
    }

    private static List<String> columns(String table) {
        return sql.queryForList("SELECT column_name || ' ' || data_type || ' ' || is_nullable FROM information_schema.columns "
            + "WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position", String.class, table);
    }
}

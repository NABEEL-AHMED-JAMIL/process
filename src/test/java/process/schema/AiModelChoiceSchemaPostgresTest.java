package process.schema;

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
 * V182 (Wave 4, MIG-242's Core part) as the changelog builds it: the schedule's and the run's model per step
 * (model_profiles, a JSON object or nothing), run_ai_step -- in its run's workspace, one row per step per attempt, gone
 * with its run, its vocabularies held by CHECKs -- and result_record's model columns. V182 rolls back to exactly what
 * was there before and applies again.
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD (see ScratchEtlJob).
 */
class AiModelChoiceSchemaPostgresTest {

    static final String V182 = "182.0-ai-model-choice";
    private static final long A = 7811L;
    private static final long B = 7812L;

    private static ScratchEtlJob db;
    private static JdbcTemplate sql;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("ai_model_choice_v182");
        sql = db.sql();
        for (long tenant : new long[] {A, B}) {
            sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
                + "VALUES (?, now(), 'Manual', 'wound_assessment', 'Active', 1, ?)", tenant * 10, tenant);
        }
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    private static long run(long tenant, long id) {
        sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, date_created, status, attempt) "
            + "VALUES (?, ?, 'Completed', now(), 'Active', 1)", id, tenant * 10);
        return id;
    }

    private static void step(long runId, int attempt, String key) {
        sql.update("INSERT INTO run_ai_step (job_queue_id, attempt, step_key, run_in, outcome) VALUES (?, ?, ?, 'server', 'answered')",
            runId, attempt, key);
    }

    @Test
    void theScheduleAndTheRunHoldAJsonObjectOrNothing() {
        sql.update("UPDATE source_job SET model_profiles = '{\"summary\":\"1204\"}' WHERE job_id = ?", A * 10);
        sql.update("UPDATE source_job SET model_profiles = NULL WHERE job_id = ?", A * 10);
        long runA = run(A, 781101);
        sql.update("UPDATE job_queue SET model_profiles = '{}' WHERE job_queue_id = ?", runA);
        for (String bad : new String[] {"[1]", "\"1204\"", "12"}) {
            assertThatThrownBy(() -> sql.update("UPDATE source_job SET model_profiles = ? WHERE job_id = ?", bad, A * 10))
                .as(bad).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_source_job_model_profiles_object");
            assertThatThrownBy(() -> sql.update("UPDATE job_queue SET model_profiles = ? WHERE job_queue_id = ?", bad, runA))
                .as(bad).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_job_queue_model_profiles_object");
        }
    }

    @Test
    void runAiStepCarriesAWorkspaceTheAuditColumnsAndInstants() {
        Map<String, Object> tenant = sql.queryForMap("SELECT data_type, is_nullable FROM information_schema.columns "
            + "WHERE table_schema = 'public' AND table_name = 'run_ai_step' AND column_name = 'tenant_id'");
        assertThat(tenant).containsEntry("data_type", "bigint").containsEntry("is_nullable", "NO");
        assertThat(sql.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' "
            + "AND table_name = 'run_ai_step'", String.class)).contains("created_by", "updated_by", "date_created", "date_updated");
        assertThat(sql.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' "
            + "AND table_name = 'run_ai_step' AND data_type = 'timestamp without time zone'", String.class)).isEmpty();
    }

    @Test
    void aStepTakesItsRunsWorkspaceAndCannotNameAnother() {
        long runA = run(A, 781201);
        step(runA, 1, "summary");
        assertThat(sql.queryForObject("SELECT tenant_id FROM run_ai_step WHERE job_queue_id = ?", Long.class, runA)).isEqualTo(A);
        assertThatThrownBy(() -> sql.update("INSERT INTO run_ai_step (tenant_id, job_queue_id, step_key, run_in, outcome) "
            + "VALUES (?, ?, 'caption', 'worker', 'handed')", B, runA)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("fk_run_ai_step_run_tenant");
    }

    @Test
    void oneRowPerStepPerAttemptAndTheyGoWithTheirRun() {
        long runA = run(A, 781301);
        step(runA, 1, "summary");
        assertThatThrownBy(() -> step(runA, 1, "summary")).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ux_run_ai_step_run_step");
        step(runA, 2, "summary");
        sql.update("DELETE FROM job_queue WHERE job_queue_id = ?", runA);
        assertThat(sql.queryForObject("SELECT count(*) FROM run_ai_step WHERE job_queue_id = ?", Long.class, runA)).isZero();
    }

    @Test
    void theVocabulariesAreHeld() {
        long runA = run(A, 781401);
        List<String[]> bad = Arrays.asList(
            new String[] {"run_in", "'elsewhere'", "ck_run_ai_step_run_in"},
            new String[] {"outcome", "'maybe'", "ck_run_ai_step_outcome"},
            new String[] {"model_choice", "'whim'", "ck_run_ai_step_model_choice"},
            new String[] {"profile_source, model_profile", "'console', '1204'", "ck_run_ai_step_profile_source"},
            new String[] {"prompt_version", "0", "ck_run_ai_step_prompt_version"},
            new String[] {"attempt", "0", "ck_run_ai_step_attempt"});
        int n = 0;
        for (String[] b : bad) {
            String key = "s" + (n++);
            String columns = "job_queue_id, step_key, run_in, outcome" + (Arrays.asList("run_in", "outcome").contains(b[0]) ? "" : ", " + b[0]);
            String values = "?, ?, " + ("run_in".equals(b[0]) ? b[1] : "'server'") + ", " + ("outcome".equals(b[0]) ? b[1] : "'answered'")
                + (Arrays.asList("run_in", "outcome").contains(b[0]) ? "" : ", " + b[1]);
            assertThatThrownBy(() -> sql.update("INSERT INTO run_ai_step (" + columns + ") VALUES (" + values + ")", runA, key))
                .as(b[0]).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(b[2]);
        }
        // A profile is asked by the run or its schedule, and a source names who asked one.
        assertThatThrownBy(() -> sql.update("INSERT INTO run_ai_step (job_queue_id, step_key, run_in, outcome, model_profile) "
            + "VALUES (?, 'p1', 'server', 'answered', '1204')", runA)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_run_ai_step_profile_asked");
        assertThatThrownBy(() -> sql.update("INSERT INTO run_ai_step (job_queue_id, step_key, run_in, outcome, profile_source) "
            + "VALUES (?, 'p2', 'server', 'answered', 'run')", runA)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_run_ai_step_profile_asked");
        assertThatThrownBy(() -> sql.update("INSERT INTO result_record (job_queue_id, result, model_choice) VALUES (?, '{}'::jsonb, 'whim')",
            runA)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_result_record_model_choice");
    }

    @Test
    void aResultNamesTheModelThatMadeIt() {
        assertThat(columns("result_record")).contains("step_key character varying YES", "model character varying YES",
            "model_option_id bigint YES", "model_choice character varying YES");
    }

    /** V182's rollback takes off exactly its own table, sequence and columns; then V182 applies again. */
    @Test
    void theRollbackTakesItAllOffAndV182AppliesAgain() throws Exception {
        int after = sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE orderexecuted > "
            + "(SELECT orderexecuted FROM databasechangelog WHERE id = ?)", Integer.class, V182);
        if (after > 0) {
            db.rollback(after);
        }
        List<String> withV182Source = columns("source_job");
        List<String> withV182Queue = columns("job_queue");
        List<String> withV182Result = columns("result_record");
        db.rollback(1);
        try {
            assertThat(sql.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' "
                + "AND table_name = 'run_ai_step'", Long.class)).isZero();
            assertThat(sql.queryForObject("SELECT count(*) FROM pg_sequences WHERE schemaname = 'public' AND sequencename = 'run_ai_step_seq'",
                Long.class)).isZero();
            assertThat(columns("source_job")).isEqualTo(without(withV182Source, "model_profiles"));
            assertThat(columns("job_queue")).isEqualTo(without(withV182Queue, "model_profiles"));
            assertThat(columns("result_record")).isEqualTo(without(withV182Result, "step_key", "model", "model_option_id", "model_choice"));
        } finally {
            db.finish();
        }
        assertThat(sql.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' "
            + "AND table_name = 'run_ai_step'", Long.class)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE id = ?", Long.class, V182)).isEqualTo(1);
    }

    private static List<String> without(List<String> columns, String... names) {
        List<String> kept = new ArrayList<>(columns);
        kept.removeIf(c -> Arrays.asList(names).contains(c.substring(0, c.indexOf(' '))));
        return kept;
    }

    private static List<String> columns(String table) {
        return sql.queryForList("SELECT column_name || ' ' || data_type || ' ' || is_nullable FROM information_schema.columns "
            + "WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position", String.class, table);
    }
}

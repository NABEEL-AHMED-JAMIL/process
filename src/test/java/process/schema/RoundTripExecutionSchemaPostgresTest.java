package process.schema;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import process.model.enums.JobStatus;
import process.model.enums.ReviewDecision;
import process.model.enums.ReviewParty;
import process.model.enums.ReviewStatus;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-225, V180: Core's part of the round-trip and execution schema -- step_execution, run_dataset, result_record,
 * result_review -- as the changelog builds it. Every row carries its run's workspace (filled from the parent when a
 * writer leaves it out, refused when it names another), times are instants, the status CHECKs list exactly the
 * enums' values, steps and datasets go with their run, results are not deleted from under a run, and V180 rolls back
 * to exactly what was there before and applies again.
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD (see ScratchEtlJob).
 */
class RoundTripExecutionSchemaPostgresTest {

    static final String V180 = "180.0-round-trip-execution";
    static final List<String> TABLES = Arrays.asList("step_execution", "run_dataset", "result_record", "result_review");
    private static final long A = 7801L;
    private static final long B = 7802L;

    private static ScratchEtlJob db;
    private static JdbcTemplate sql;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("round_trip_v180");
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

    /** A finished run of workspace {@code tenant}'s job (one in flight per job, V83); tenant_id comes from the job (V102). */
    private static long run(long tenant, long id) {
        sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, date_created, status, attempt) "
            + "VALUES (?, ?, 'Completed', now(), 'Active', 1)", id, tenant * 10);
        return id;
    }

    private static long step(long runId, int attempt, int index, String status) {
        return sql.queryForObject("INSERT INTO step_execution (job_queue_id, attempt, step_index, task_code, status, started_at) "
            + "VALUES (?, ?, ?, 'ai_assessment', ?, now()) RETURNING step_execution_id", Long.class, runId, attempt, index, status);
    }

    private static long result(long runId) {
        return sql.queryForObject("INSERT INTO result_record (job_queue_id, contract_version_id, subject_ref, result, model_connection_id, "
            + "prompt_id, prompt_version) VALUES (?, 1001, 'hmac:3f2a', '{\"case_id\":\"WC-88213\"}'::jsonb, 1002, 1003, 4) "
            + "RETURNING result_record_id", Long.class, runId);
    }

    @Test
    void everyTableCarriesAWorkspaceTheAuditColumnsAndInstants() {
        for (String table : TABLES) {
            Map<String, Object> tenant = sql.queryForMap("SELECT data_type, is_nullable FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = ? AND column_name = 'tenant_id'", table);
            assertThat(tenant).as(table).containsEntry("data_type", "bigint").containsEntry("is_nullable", "NO");
            assertThat(sql.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?",
                String.class, table)).as(table).contains("created_by", "updated_by", "date_created", "date_updated");
            // V100: process stores instants; no new naive timestamp column (TimestampColumns' inventory stays as it is).
            assertThat(sql.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ? "
                + "AND data_type = 'timestamp without time zone'", String.class, table)).as(table).isEmpty();
        }
    }

    /** The deferred customer-integration tables are not built here, nor anywhere in Core (owner, 2026-09-28). */
    @Test
    void theDeferredCustomerIntegrationTablesAreNotHere() {
        assertThat(sql.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN "
            + "('intake_batch', 'intake_record', 'result_manifest', 'api_client', 'destination', 'delivery', 'stored_file')", Long.class))
            .isZero();
    }

    /** A writer that leaves tenant_id out gets the parent's, down the whole chain. */
    @Test
    void aRowTakesItsParentsWorkspace() {
        long runA = run(A, 780101);
        long stepA = step(runA, 1, 0, "Completed");
        long dataset = sql.queryForObject("INSERT INTO run_dataset (step_execution_id, name, storage_key, row_count, columns) "
            + "VALUES (?, 'patients', 'datasets/EXEC-1/0/patients.json', 12, '[\"id\",\"name\"]'::jsonb) RETURNING run_dataset_id",
            Long.class, stepA);
        long resultA = result(runA);
        long review = sql.queryForObject("INSERT INTO result_review (result_record_id, party, reviewer, decision) "
            + "VALUES (?, 'INTERNAL', 'reviewer@platform.example', 'APPROVED') RETURNING result_review_id", Long.class, resultA);
        assertThat(sql.queryForObject("SELECT tenant_id FROM step_execution WHERE step_execution_id = ?", Long.class, stepA)).isEqualTo(A);
        assertThat(sql.queryForObject("SELECT tenant_id FROM run_dataset WHERE run_dataset_id = ?", Long.class, dataset)).isEqualTo(A);
        assertThat(sql.queryForObject("SELECT tenant_id FROM result_record WHERE result_record_id = ?", Long.class, resultA)).isEqualTo(A);
        assertThat(sql.queryForObject("SELECT tenant_id FROM result_review WHERE result_review_id = ?", Long.class, review)).isEqualTo(A);
    }

    /** Tenant isolation held by the database: no row can name another workspace than its parent's. */
    @Test
    void aRowCannotNameAnotherWorkspaceThanItsParents() {
        long runA = run(A, 780201);
        assertThatThrownBy(() -> sql.update("INSERT INTO step_execution (tenant_id, job_queue_id, step_index, task_code) "
            + "VALUES (?, ?, 0, 'read_api')", B, runA)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("fk_step_execution_run_tenant");
        long stepA = step(runA, 1, 0, "Running");
        assertThatThrownBy(() -> sql.update("INSERT INTO run_dataset (tenant_id, step_execution_id, name, storage_key) "
            + "VALUES (?, ?, 'x', 'datasets/x.json')", B, stepA)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("fk_run_dataset_step_tenant");
        assertThatThrownBy(() -> sql.update("INSERT INTO result_record (tenant_id, job_queue_id, result) VALUES (?, ?, '{}'::jsonb)", B, runA))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_result_record_run_tenant");
        long resultA = result(runA);
        assertThatThrownBy(() -> sql.update("INSERT INTO result_review (tenant_id, result_record_id, party, reviewer, decision) "
            + "VALUES (?, ?, 'CUSTOMER', 'dr.lee@customer.example', 'APPROVED')", B, resultA))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_result_review_result_tenant");
    }

    /** One row per step per attempt: a retried run records its steps again, the first attempt's stay. */
    @Test
    void aStepIsRecordedOncePerAttempt() {
        long runA = run(A, 780301);
        step(runA, 1, 0, "Failed");
        assertThatThrownBy(() -> step(runA, 1, 0, "Running")).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ux_step_execution_run_step");
        step(runA, 2, 0, "Completed");
        assertThatThrownBy(() -> sql.update("UPDATE step_execution SET ended_at = started_at - interval '1 minute' WHERE job_queue_id = ?", runA))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_step_execution_times");
    }

    @Test
    void aResultStartsPendingAndEachPartyReviewsItOnce() {
        long resultA = result(run(A, 780401));
        assertThat(sql.queryForObject("SELECT review_status FROM result_record WHERE result_record_id = ?", String.class, resultA))
            .isEqualTo(ReviewStatus.PENDING.name());
        sql.update("INSERT INTO result_review (result_record_id, party, reviewer, decision) VALUES (?, 'INTERNAL', 'a@platform.example', 'APPROVED')",
            resultA);
        assertThatThrownBy(() -> sql.update("INSERT INTO result_review (result_record_id, party, reviewer, decision) "
            + "VALUES (?, 'INTERNAL', 'b@platform.example', 'REJECTED')", resultA)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ux_result_review_party");
        sql.update("INSERT INTO result_review (result_record_id, party, reviewer, decision) VALUES (?, 'CUSTOMER', 'dr.lee@customer.example', "
            + "'REJECTED')", resultA);
        assertThatThrownBy(() -> sql.update("INSERT INTO result_record (job_queue_id, result, prompt_version) VALUES (780401, '{}'::jsonb, 2)"))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_result_record_prompt_version");
        assertThatThrownBy(() -> sql.update("INSERT INTO result_record (job_queue_id, result) VALUES (780401, '[1]'::jsonb)"))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_result_record_result_object");
    }

    /** Steps and their datasets are parts of a run and go with it; a run that has results is not deleted from under them. */
    @Test
    void stepsGoWithTheirRunAndResultsAreKept() {
        long withSteps = run(A, 780501);
        long stepA = step(withSteps, 1, 0, "Completed");
        sql.update("INSERT INTO run_dataset (step_execution_id, name, storage_key) VALUES (?, 'rows', 'datasets/780501/0/rows.json')", stepA);
        sql.update("DELETE FROM job_queue WHERE job_queue_id = ?", withSteps);
        assertThat(sql.queryForObject("SELECT count(*) FROM step_execution WHERE job_queue_id = ?", Long.class, withSteps)).isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM run_dataset WHERE step_execution_id = ?", Long.class, stepA)).isZero();

        long withResult = run(A, 780502);
        result(withResult);
        assertThatThrownBy(() -> sql.update("DELETE FROM job_queue WHERE job_queue_id = ?", withResult))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_result_record_run_tenant");
    }

    @Test
    void theCheckConstraintsListExactlyTheValuesTheEnumsStore() {
        assertThat(checkValues("ck_step_execution_status_enum")).isEqualTo(names(JobStatus.values()));
        assertThat(checkValues("ck_result_record_review_status_enum")).isEqualTo(names(ReviewStatus.values()));
        assertThat(checkValues("ck_result_review_party_enum")).isEqualTo(names(ReviewParty.values()));
        assertThat(checkValues("ck_result_review_decision_enum")).isEqualTo(names(ReviewDecision.values()));
    }

    /** V180's rollback takes off exactly its own four tables and sequences, job_queue as it was; then V180 applies again. */
    @Test
    void theRollbackTakesItAllOffAndV180AppliesAgain() throws Exception {
        List<String> jobQueueColumns = columns("job_queue");
        List<String> jobQueueConstraints = sql.queryForList("SELECT conname FROM pg_constraint WHERE conrelid = 'public.job_queue'::regclass "
            + "ORDER BY 1", String.class);
        // The rows other tests left would stop nothing: a rollback drops the tables with their rows. Only V180 and after.
        db.rollback(sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE orderexecuted >= "
            + "(SELECT orderexecuted FROM databasechangelog WHERE id = ?)", Integer.class, V180));
        try {
            assertThat(sql.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN "
                + "('step_execution', 'run_dataset', 'result_record', 'result_review')", Long.class)).isZero();
            assertThat(sql.queryForObject("SELECT count(*) FROM pg_sequences WHERE schemaname = 'public' AND sequencename IN "
                + "('step_execution_seq', 'run_dataset_seq', 'result_record_seq', 'result_review_seq')", Long.class)).isZero();
            assertThat(columns("job_queue")).isEqualTo(jobQueueColumns);
            assertThat(sql.queryForList("SELECT conname FROM pg_constraint WHERE conrelid = 'public.job_queue'::regclass ORDER BY 1",
                String.class)).isEqualTo(jobQueueConstraints);
            assertThat(sql.queryForObject("SELECT count(*) FROM pg_proc WHERE proname = 'tenant_id_from_parent'", Long.class))
                .as("V102's function stays").isEqualTo(1);
        } finally {
            db.finish();
        }
        assertThat(sql.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN "
            + "('step_execution', 'run_dataset', 'result_record', 'result_review')", Long.class)).isEqualTo(4);
        assertThat(sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE id = ?", Long.class, V180)).isEqualTo(1);
    }

    private static List<String> columns(String table) {
        return sql.queryForList("SELECT column_name || ' ' || data_type || ' ' || is_nullable FROM information_schema.columns "
            + "WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position", String.class, table);
    }

    private static Set<String> checkValues(String constraint) {
        String definition = sql.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?", String.class, constraint);
        Set<String> values = new TreeSet<>();
        Matcher m = Pattern.compile("'([^']+)'").matcher(definition);
        while (m.find()) {
            values.add(m.group(1));
        }
        return values;
    }

    private static Set<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
    }
}

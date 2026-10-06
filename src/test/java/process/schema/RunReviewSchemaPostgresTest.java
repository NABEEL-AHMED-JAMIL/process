package process.schema;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import process.model.enums.ReviewDecision;
import process.model.enums.ReviewParty;
import process.pipeline.review.RunReviewStatus;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V189 (MIG-237) as the changelog builds it: run_review_decision -- one decision per party per run, its tenant taken
 * from the run, insert-only for the application -- and run_review, the run's review status; both guarded as every
 * tenant table (V181), gone with their run, and spelled as their enums. V189 rolls back to exactly what was there before and applies again.
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD (see ScratchEtlJob).
 */
class RunReviewSchemaPostgresTest {

    static final String V189 = "189.0-run-review";
    private static final long A = 8941L;
    private static final long B = 8942L;
    private static final long RUN_OF_A = 89410001L;
    private static final long RUN_OF_B = 89420001L;

    private static ScratchEtlJob db;
    private static JdbcTemplate sql;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("run_review_v189");
        sql = db.sql();
        run(A, 894101L, RUN_OF_A);
        run(B, 894201L, RUN_OF_B);
    }

    private static void run(long tenant, long job, long run) {
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?)", tenant, "t" + tenant,
            "T" + tenant);
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
            + "VALUES (?, now(), 'Manual', 'reviewed job', 'Active', 1, ?)", job, tenant);
        sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, date_created, start_time, status, job_send, attempt) "
            + "VALUES (?, ?, 'Completed', now(), now(), 'Active', true, 1)", run, job);
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void oneDecisionPerPartyPerRunWithTheRunsTenant() {
        long id = sql.queryForObject("INSERT INTO run_review_decision (job_queue_id, attempt, party, decision, comment, reviewer_user_id) "
            + "VALUES (?, 1, 'INTERNAL', 'APPROVED', 'looks right', 7) RETURNING run_review_decision_id", Long.class, RUN_OF_A);
        assertThat(id).isGreaterThanOrEqualTo(1000L);
        assertThat(sql.queryForObject("SELECT tenant_id FROM run_review_decision WHERE run_review_decision_id = ?", Long.class, id)).isEqualTo(A);
        assertThatThrownBy(() -> sql.update("INSERT INTO run_review_decision (job_queue_id, attempt, party, decision, reason) "
            + "VALUES (?, 1, 'INTERNAL', 'REJECTED', 'changed my mind')", RUN_OF_A))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ux_run_review_decision_run_party");
        sql.update("INSERT INTO run_review_decision (job_queue_id, attempt, party, decision, reason) VALUES (?, 1, 'CUSTOMER', 'REJECTED', "
            + "'totals are off')", RUN_OF_A);
    }

    @Test
    void aDecisionIsOneOfTwoByOneOfTwoPartiesAndARejectionSaysWhy() {
        assertThatThrownBy(() -> sql.update("INSERT INTO run_review_decision (job_queue_id, attempt, party, decision) VALUES (?, 1, "
            + "'PARTNER', 'APPROVED')", RUN_OF_B)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_run_review_decision_party_enum");
        assertThatThrownBy(() -> sql.update("INSERT INTO run_review_decision (job_queue_id, attempt, party, decision) VALUES (?, 1, "
            + "'INTERNAL', 'MAYBE')", RUN_OF_B)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_run_review_decision_decision_enum");
        assertThatThrownBy(() -> sql.update("INSERT INTO run_review_decision (job_queue_id, attempt, party, decision, reason) VALUES (?, 1, "
            + "'INTERNAL', 'REJECTED', '  ')", RUN_OF_B)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_run_review_decision_reason");
        assertThatThrownBy(() -> sql.update("INSERT INTO run_review_decision (job_queue_id, attempt, party, decision) VALUES (?, 0, "
            + "'INTERNAL', 'APPROVED')", RUN_OF_B)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_run_review_decision_attempt");
    }

    /** V164's rule: a party, decision or status column takes exactly its enum's spellings. */
    @Test
    void theColumnsTakeTheirEnumsSpellings() {
        assertThat(checkValues("ck_run_review_decision_party_enum")).isEqualTo(names(ReviewParty.values()));
        assertThat(checkValues("ck_run_review_decision_decision_enum")).isEqualTo(names(ReviewDecision.values()));
        assertThat(checkValues("ck_run_review_status_enum")).isEqualTo(names(RunReviewStatus.values()));
    }

    @Test
    void aRunsStatusNeverSaysNotRequiredWhenItRequiresAReview() {
        assertThatThrownBy(() -> sql.update("INSERT INTO run_review (job_queue_id, required_parties, status) VALUES (?, 'INTERNAL', "
            + "'NOT_REQUIRED')", RUN_OF_B)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_run_review_not_required");
        assertThatThrownBy(() -> sql.update("INSERT INTO run_review (job_queue_id, required_parties, status) VALUES (?, 'PARTNER', "
            + "'PENDING')", RUN_OF_B)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_run_review_required");
        assertThatThrownBy(() -> sql.update("INSERT INTO run_review (job_queue_id, required_parties, status, rerun_job_queue_id) "
            + "VALUES (?, 'INTERNAL', 'APPROVED', 1)", RUN_OF_B)).isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("ck_run_review_rerun");
    }

    @Test
    void theTablesAreGuardedAndTheApplicationAddsDecisionsButNeverChangesOrRemovesOne() throws Exception {
        for (String table : new String[] {"run_review_decision", "run_review"}) {
            assertThat(sql.queryForMap("SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid = ?::regclass", "public." + table))
                .containsEntry("relrowsecurity", true).containsEntry("relforcerowsecurity", true);
            assertThat(sql.queryForObject("SELECT qual FROM pg_policies WHERE tablename = ? AND policyname = 'tenant_isolation'",
                String.class, table)).isEqualTo(sql.queryForObject("SELECT qual FROM pg_policies WHERE tablename = 'run_ai_step'",
                String.class));
        }
        sql.update("INSERT INTO run_review_decision (job_queue_id, attempt, party, decision) VALUES (?, 1, 'INTERNAL', 'APPROVED') "
            + "ON CONFLICT DO NOTHING", RUN_OF_B);
        try (Connection connection = db.connect(); Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE process_app");
            statement.execute("SELECT set_config('app.tenant_id', '" + A + "', false)");
            ResultSet seen = statement.executeQuery("SELECT DISTINCT tenant_id FROM run_review_decision");
            List<Long> tenants = new ArrayList<>();
            while (seen.next()) {
                tenants.add(seen.getLong(1));
            }
            assertThat(tenants).doesNotContain(B);
            assertThatThrownBy(() -> statement.execute("INSERT INTO run_review_decision (job_queue_id, tenant_id, attempt, party, decision) "
                + "VALUES (" + RUN_OF_B + ", " + B + ", 1, 'CUSTOMER', 'APPROVED')")).hasMessageContaining("row-level security");
            assertThatThrownBy(() -> statement.execute("UPDATE run_review_decision SET decision = 'APPROVED'"))
                .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.execute("DELETE FROM run_review_decision")).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.execute("DELETE FROM run_review")).hasMessageContaining("permission denied");
            statement.execute("INSERT INTO run_review (job_queue_id, required_parties, status) VALUES (" + RUN_OF_A + ", 'INTERNAL', "
                + "'PENDING') ON CONFLICT (job_queue_id) DO UPDATE SET status = 'PENDING'");
            assertThat(statement.executeUpdate("UPDATE run_review SET status = 'APPROVED', decided_at = now() WHERE job_queue_id = "
                + RUN_OF_A)).isEqualTo(1);
            assertThat(statement.executeUpdate("UPDATE run_review SET status = 'APPROVED' WHERE tenant_id = " + B))
                .as("B's status is not A's to move").isZero();
        }
    }

    @Test
    void theReviewsGoWithTheirRunEvenWhenTheApplicationRemovesIt() throws Exception {
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
            + "VALUES (894102, now(), 'Manual', 'short-lived', 'Active', 1, ?)", A);
        sql.update("INSERT INTO job_queue (job_queue_id, job_id, job_status, date_created, start_time, status, job_send, attempt) "
            + "VALUES (89410002, 894102, 'Completed', now(), now(), 'Active', true, 1)");
        sql.update("INSERT INTO run_review_decision (job_queue_id, attempt, party, decision) VALUES (89410002, 1, 'INTERNAL', 'APPROVED')");
        sql.update("INSERT INTO run_review (job_queue_id, required_parties, status) VALUES (89410002, 'INTERNAL', 'APPROVED')");
        try (Connection connection = db.connect(); Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE process_app");
            statement.execute("SELECT set_config('app.tenant_id', '" + A + "', false)");
            assertThat(statement.executeUpdate("DELETE FROM job_queue WHERE job_queue_id = 89410002")).isEqualTo(1);
        }
        assertThat(sql.queryForObject("SELECT count(*) FROM run_review_decision WHERE job_queue_id = 89410002", Long.class)).isZero();
        assertThat(sql.queryForObject("SELECT count(*) FROM run_review WHERE job_queue_id = 89410002", Long.class)).isZero();
    }

    @Test
    void theRollbackTakesThemOffAndV189AppliesAgain() throws Exception {
        int after = sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE orderexecuted > "
            + "(SELECT orderexecuted FROM databasechangelog WHERE id = ?)", Integer.class, V189);
        if (after > 0) {
            db.rollback(after);
        }
        db.rollback(1);
        try {
            assertThat(sql.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' "
                + "AND table_name IN ('run_review_decision', 'run_review')", Long.class)).isZero();
            assertThat(sql.queryForObject("SELECT count(*) FROM pg_sequences WHERE schemaname = 'public' "
                + "AND sequencename = 'run_review_decision_seq'", Long.class)).isZero();
        } finally {
            db.finish();
        }
        assertThat(sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE id = ?", Long.class, V189)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT count(*) FROM pg_policies WHERE tablename IN ('run_review_decision', 'run_review')", Long.class))
            .isEqualTo(2);
    }

    private static Set<String> checkValues(String constraint) {
        String definition = sql.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?", String.class,
            constraint);
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

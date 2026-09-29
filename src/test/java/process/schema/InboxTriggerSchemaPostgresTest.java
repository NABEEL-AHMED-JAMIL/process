package process.schema;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V185 (MIG-239, Core's half of the inbox) as the changelog builds it: job_inbox_trigger -- one per job, in its job's
 * workspace, gone with its job -- and inbox_arrival -- one row per arrival per job (the idempotency of "start once"),
 * in its job's workspace, Started always naming its run and Skipped never -- both under row security as every tenant
 * table (V181); and the file on job_queue (input_bucket, input_key). V185 rolls back to exactly what was there before
 * and applies again.
 *
 * Opt-in: NOTIFICATIONS_TEST_DB_URL / _USER / _PASSWORD (see ScratchEtlJob).
 */
class InboxTriggerSchemaPostgresTest {

    static final String V185 = "185.0-inbox-trigger";
    private static final long A = 7851L;
    private static final long B = 7852L;
    private static final String ARRIVAL = "0b8f6a52-3f5e-4c1a-9d7e-5a4b3c2d1e0f";

    private static ScratchEtlJob db;
    private static JdbcTemplate sql;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("inbox_trigger_v185");
        sql = db.sql();
        for (long tenant : new long[] {A, B}) {
            sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
                + "VALUES (?, now(), 'Manual', 'claims_intake', 'Active', 1, ?)", tenant * 10, tenant);
        }
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    private static void arrival(long jobId, String outcome, Long jobQueueId) {
        sql.update("INSERT INTO inbox_arrival (arrival_id, job_id, bucket, storage_key, file_name, bytes, outcome, job_queue_id) "
            + "VALUES (?, ?, 'acme-inbox', 'intake/2026/09/28/x-a.csv', 'a.csv', 10, ?, ?)", ARRIVAL, jobId, outcome, jobQueueId);
    }

    @Test
    void aTriggerTakesItsJobsWorkspaceIsOnePerJobAndGoesWithIt() {
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
            + "VALUES (?, now(), 'Manual', 'short_lived', 'Active', 1, ?)", A * 10 + 1, A);
        sql.update("INSERT INTO job_inbox_trigger (job_id, file_pattern) VALUES (?, '*.csv')", A * 10 + 1);
        assertThat(sql.queryForMap("SELECT tenant_id, enabled FROM job_inbox_trigger WHERE job_id = ?", A * 10 + 1))
            .containsEntry("tenant_id", A).containsEntry("enabled", true);
        assertThatThrownBy(() -> sql.update("INSERT INTO job_inbox_trigger (job_id) VALUES (?)", A * 10 + 1))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("pk_job_inbox_trigger");
        assertThatThrownBy(() -> sql.update("INSERT INTO job_inbox_trigger (job_id, tenant_id) VALUES (?, ?)", B * 10, A))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_job_inbox_trigger_job_tenant");
        sql.update("DELETE FROM source_job WHERE job_id = ?", A * 10 + 1);
        assertThat(sql.queryForObject("SELECT count(*) FROM job_inbox_trigger WHERE job_id = ?", Long.class, A * 10 + 1)).isZero();
    }

    @Test
    void anArrivalIsRecordedOncePerJobAndStartedAlwaysNamesItsRun() {
        arrival(A * 10, "Started", 991L);
        assertThat(sql.queryForObject("SELECT tenant_id FROM inbox_arrival WHERE job_id = ?", Long.class, A * 10)).isEqualTo(A);
        assertThatThrownBy(() -> arrival(A * 10, "Skipped", null))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ux_inbox_arrival_arrival_job");
        // The same arrival for another job is another row: one file can start several pipelines.
        arrival(B * 10, "Skipped", null);
        assertThatThrownBy(() -> sql.update("INSERT INTO inbox_arrival (arrival_id, job_id, bucket, storage_key, file_name, bytes, outcome) "
            + "VALUES ('11111111-1111-4111-8111-111111111111', ?, 'b', 'intake/k', 'k', 1, 'Started')", A * 10))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_inbox_arrival_started_run");
        assertThatThrownBy(() -> sql.update("INSERT INTO inbox_arrival (arrival_id, job_id, bucket, storage_key, file_name, bytes, outcome, "
            + "job_queue_id) VALUES ('22222222-2222-4222-8222-222222222222', ?, 'b', 'intake/k', 'k', 1, 'Skipped', 5)", A * 10))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_inbox_arrival_started_run");
        assertThatThrownBy(() -> sql.update("INSERT INTO inbox_arrival (arrival_id, job_id, bucket, storage_key, file_name, bytes, outcome) "
            + "VALUES ('33333333-3333-4333-8333-333333333333', ?, 'b', 'intake/k', 'k', 1, 'Maybe')", A * 10))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_inbox_arrival_outcome");
        assertThatThrownBy(() -> sql.update("INSERT INTO inbox_arrival (tenant_id, arrival_id, job_id, bucket, storage_key, file_name, bytes, "
            + "outcome) VALUES (?, '44444444-4444-4444-8444-444444444444', ?, 'b', 'intake/k', 'k', 1, 'Skipped')", B, A * 10))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_inbox_arrival_job_tenant");
    }

    @Test
    void bothTablesAreTenantTablesUnderRowSecurityWithInstants() {
        for (String table : Arrays.asList("job_inbox_trigger", "inbox_arrival")) {
            assertThat(sql.queryForObject("SELECT relrowsecurity AND relforcerowsecurity FROM pg_class WHERE relname = ?", Boolean.class, table))
                .as(table).isTrue();
            assertThat(sql.queryForList("SELECT policyname FROM pg_policies WHERE tablename = ?", String.class, table))
                .as(table).containsExactly("tenant_isolation");
            assertThat(sql.queryForObject("SELECT has_table_privilege('process_app', ?, 'INSERT')", Boolean.class, "public." + table))
                .as(table).isTrue();
            assertThat(sql.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name = ? AND data_type = 'timestamp without time zone'", String.class, table)).as(table).isEmpty();
            assertThat(sql.queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name = ? AND column_name = 'tenant_id'", String.class, table)).as(table).isEqualTo("NO");
        }
        assertThat(columns("job_queue")).contains("input_bucket character varying YES", "input_key character varying YES");
    }

    /** V185's rollback takes off exactly its own tables, sequence and columns; then V185 applies again. */
    @Test
    void theRollbackTakesItAllOffAndV185AppliesAgain() throws Exception {
        int after = sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE orderexecuted > "
            + "(SELECT orderexecuted FROM databasechangelog WHERE id = ?)", Integer.class, V185);
        if (after > 0) {
            db.rollback(after);
        }
        List<String> withV185Queue = columns("job_queue");
        db.rollback(1);
        try {
            assertThat(sql.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' "
                + "AND table_name IN ('job_inbox_trigger', 'inbox_arrival')", String.class)).isEmpty();
            assertThat(sql.queryForObject("SELECT count(*) FROM pg_sequences WHERE schemaname = 'public' AND sequencename = 'inbox_arrival_seq'",
                Long.class)).isZero();
            assertThat(columns("job_queue")).isEqualTo(without(withV185Queue, "input_bucket", "input_key"));
        } finally {
            db.finish();
        }
        assertThat(sql.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' "
            + "AND table_name IN ('job_inbox_trigger', 'inbox_arrival')", Long.class)).isEqualTo(2);
        assertThat(sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE id = ?", Long.class, V185)).isEqualTo(1);
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

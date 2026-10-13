package process.schema;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V211 (MIG-347) as Postgres holds it: backfill and backfill_slot under row security for process_app, one open period per job
 * and start, job_queue's lanes (one in-flight run per job and lane; a lane past 0 only for a backfill's run), the checks on a
 * backfill's state, step, concurrency and size -- and the way back, and on again.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchEtlJob).
 */
class BackfillSchemaPostgresTest {

    static final String V211 = "211.0-backfill-and-run-parameters";
    private static final long A = 9451L;
    private static final long JOB = 945101L;

    private static ScratchEtlJob db;
    private static JdbcTemplate sql;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("backfill_v211");
        sql = db.sql();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', 'bf', 'Backfill')", A);
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
            + "VALUES (?, now(), 'Manual', 'synthetic job', 'Active', 1, ?)", JOB, A);
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    static long backfill() {
        return sql.queryForObject("INSERT INTO backfill (tenant_id, job_id, step, range_from, range_to, zone, concurrency, total) VALUES "
            + "(?, ?, 'DAILY', '2026-10-01', '2026-10-02', 'America/Chicago', 2, 2) RETURNING backfill_id", Long.class, A, JOB);
    }

    @Test
    void bothTablesAreGuardedAsEveryTenantTable() {
        for (String table : new String[] {"backfill", "backfill_slot"}) {
            assertThat(sql.queryForMap("SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid = ?::regclass", "public." + table))
                .as(table).containsEntry("relrowsecurity", true).containsEntry("relforcerowsecurity", true);
            assertThat(sql.queryForObject("SELECT qual FROM pg_policies WHERE tablename = ?", String.class, table))
                .isEqualTo(sql.queryForObject("SELECT qual FROM pg_policies WHERE tablename = 'run_approval'", String.class));
            assertThat(sql.queryForObject("SELECT has_table_privilege('process_app', ?, 'INSERT')", Boolean.class, "public." + table)).isTrue();
        }
        assertThat(sql.queryForObject("SELECT has_sequence_privilege('process_app', 'public.backfill_seq', 'USAGE')", Boolean.class)).isTrue();
    }

    @Test
    void aBackfillsStateStepConcurrencyAndSizeAreChecked() {
        String insert = "INSERT INTO backfill (tenant_id, job_id, step, range_from, range_to, zone, concurrency, total, status) VALUES "
            + "(?, ?, ?, '2026-10-01', '2026-10-02', 'UTC', ?, ?, ?)";
        assertThatThrownBy(() -> sql.update(insert, A, JOB, "MONTHLY", 1, 1, "RUNNING")).hasMessageContaining("ck_backfill_step");
        assertThatThrownBy(() -> sql.update(insert, A, JOB, "DAILY", 5, 1, "RUNNING")).hasMessageContaining("ck_backfill_concurrency");
        assertThatThrownBy(() -> sql.update(insert, A, JOB, "DAILY", 1, 367, "RUNNING")).hasMessageContaining("ck_backfill_total");
        assertThatThrownBy(() -> sql.update(insert, A, JOB, "DAILY", 1, 1, "COMPLETED")).hasMessageContaining("ck_backfill_ended");
        assertThatThrownBy(() -> sql.update(insert, A, 1L, "DAILY", 1, 1, "RUNNING")).hasMessageContaining("fk_backfill_job_tenant");
    }

    @Test
    void oneOpenPeriodPerJobAndStartAndADoneOneFreesIt() {
        long first = backfill();
        long second = backfill();
        String slot = "INSERT INTO backfill_slot (backfill_id, slot_start, slot_end, run_date, tenant_id, job_id, state) VALUES "
            + "(?, '2026-10-01T05:00:00Z', '2026-10-02T05:00:00Z', '2026-10-01', ?, ?, ?)";
        sql.update(slot, first, A, JOB, "PENDING");
        assertThatThrownBy(() -> sql.update(slot, second, A, JOB, "PENDING")).isInstanceOf(DuplicateKeyException.class)
            .hasMessageContaining("ux_backfill_slot_open_per_job");
        sql.update("UPDATE backfill_slot SET state = 'CANCELLED' WHERE backfill_id = ?", first);
        sql.update(slot, second, A, JOB, "PENDING");
        assertThatThrownBy(() -> sql.update("UPDATE backfill_slot SET state = 'QUEUED' WHERE backfill_id = ?", second))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_backfill_slot_run");
        sql.update("DELETE FROM backfill WHERE backfill_id IN (?, ?)", first, second);
        assertThat(sql.queryForObject("SELECT count(*) FROM backfill_slot WHERE backfill_id IN (?, ?)", Long.class, first, second)).isZero();
    }

    @Test
    void theOneInFlightIndexIsPerJobAndLane() {
        assertThat(sql.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'ux_job_queue_one_in_flight_per_job'", String.class))
            .contains("(job_id, run_lane)").contains("'QUEUE'").contains("'START'").contains("'RUNNING'");
        assertThat(sql.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_name = 'job_queue' AND column_name = "
            + "'run_lane'", String.class)).isEqualTo("0");
    }

    @Test
    void theRollbackTakesItOffAndV211AppliesAgain() throws Exception {
        int after = sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE orderexecuted > "
            + "(SELECT orderexecuted FROM databasechangelog WHERE id = ?)", Integer.class, V211);
        if (after > 0) {
            db.rollback(after);
        }
        db.rollback(1);
        try {
            assertThat(sql.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN "
                + "('backfill', 'backfill_slot')", Long.class)).isZero();
            assertThat(sql.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name = 'job_queue' AND column_name IN "
                + "('run_params', 'run_slot_at', 'run_lane', 'backfill_id')", Long.class)).isZero();
            assertThat(sql.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'ux_job_queue_one_in_flight_per_job'",
                String.class)).contains("(job_id)").doesNotContain("run_lane");
        } finally {
            db.finish();
        }
        assertThat(sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE id = ?", Long.class, V211)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT count(*) FROM pg_policies WHERE tablename IN ('backfill', 'backfill_slot')", Long.class))
            .isEqualTo(2);
    }
}

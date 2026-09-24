package process.tenancy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.schema.ScratchEtlJob;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MIG-29 / MIG-164 (P2, DEF-034): V102 gives the five tenantless tables that are still process's a tenant_id,
 * backfilled by JOIN through each one's single parent, NOT NULL, with a foreign key to tenant and one onto the
 * parent's (id, tenant_id) -- so a row's tenant is its parent's by the database's rule, not by a reconciliation.
 *
 * Built from the changelog up to V102 and loaded as a long-lived etl_job holds these tables -- two tenants, no
 * tenant_id anywhere below the parents -- then migrated.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchEtlJob).
 */
class TenantIdOnChildTablesPostgresTest {

    static final String V102 = "102.0-tenant-id-on-child-tables";
    private static final long A = 3401L;
    private static final long B = 3402L;

    /** child table -> the JOIN that derives its tenant from its parent. */
    static final Map<String, String> DERIVED = new LinkedHashMap<>();

    static {
        DERIVED.put("job_queue", "SELECT c.job_queue_id AS id, c.tenant_id, p.tenant_id AS parent FROM job_queue c JOIN source_job p ON p.job_id = c.job_id");
        DERIVED.put("scheduler", "SELECT c.scheduler_id AS id, c.tenant_id, p.tenant_id AS parent FROM scheduler c JOIN source_job p ON p.job_id = c.job_id");
        DERIVED.put("job_audit_logs", "SELECT c.job_audit_log_id AS id, c.tenant_id, p.tenant_id AS parent FROM job_audit_logs c "
            + "JOIN job_queue q ON q.job_queue_id = c.job_queue_id JOIN source_job p ON p.job_id = q.job_id");
        DERIVED.put("source_task_payload", "SELECT c.task_payload_id AS id, c.tenant_id, p.tenant_id AS parent FROM source_task_payload c "
            + "JOIN source_task p ON p.task_detail_id = c.payload_id");
        DERIVED.put("pipeline_field", "SELECT c.pipeline_field_id AS id, c.tenant_id, p.tenant_id AS parent FROM pipeline_field c "
            + "JOIN pipeline p ON p.pipeline_key = c.pipeline_key");
    }

    private static ScratchEtlJob db;
    private static final Map<String, Long> ROWS_BEFORE = new LinkedHashMap<>();

    @BeforeAll
    static void migrate() throws Exception {
        db = ScratchEtlJob.buildUpTo("tenant_id_children", V102);
        JdbcTemplate sql = db.sql();
        seed(sql);
        for (String table : DERIVED.keySet()) {
            ROWS_BEFORE.put(table, sql.queryForObject("SELECT count(*) FROM " + table, Long.class));
        }
        db.finish();
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    /** Two tenants' worth of every parent and child, as the tables held them before V102. */
    static void seed(JdbcTemplate sql) {
        for (long tenant : new long[] {A, B}) {
            long t = tenant * 10;
            sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', ?, ?)", tenant, "T" + tenant, "T" + tenant);
            sql.update("INSERT INTO source_task_type (source_task_type_id, service_name, description, queue_topic_partition, tenant_id) "
                + "VALUES (?, 'w', 'd', 'topic=t&partitions=[*]', ?)", t + 1, tenant);
            sql.update("INSERT INTO source_task (task_detail_id, task_name, task_status, source_task_type_id, tenant_id) VALUES (?, 'task', 'Active', ?, ?)",
                t + 2, t + 1, tenant);
            sql.update("INSERT INTO source_task_payload (task_payload_id, tag_key, tag_value, payload_id) VALUES (?, 'bucket', 'b', ?)", t + 3, t + 2);
            sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id, task_detail_id) "
                + "VALUES (?, now(), 'Auto', 'job', 'Active', 1, ?, ?)", t + 4, tenant, t + 2);
            sql.update("INSERT INTO scheduler (scheduler_id, job_id, start_date, start_time, frequency, interval_value, expired) "
                + "VALUES (?, ?, '2026-01-01', '09:00', 'Daily', '1', false)", t + 5, t + 4);
            sql.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status) VALUES (?, now(), ?, 'Completed', 'Active')", t + 6, t + 4);
            sql.update("INSERT INTO job_audit_logs (job_audit_log_id, date_created, job_queue_id, log_detail, status) VALUES (?, now(), ?, 'line', 'Active')",
                t + 7, t + 6);
            sql.update("INSERT INTO pipeline (pipeline_key, pipeline_id, pipeline_name, tenant_id, status) VALUES (?, 'F-T', 'pipeline', ?, 'Active')",
                t + 8, tenant);
            sql.update("INSERT INTO pipeline_field (pipeline_field_id, pipeline_key, tag_key, label) VALUES (?, ?, 'k', 'K')", t + 9, t + 8);
        }
    }

    private static long count(String sql) {
        return db.sql().queryForObject(sql, Long.class);
    }

    @Test
    void everyRowCarriesItsParentsTenantAndNoneIsLeftNull() {
        Map<String, Long> rowsAfter = new LinkedHashMap<>();
        for (Map.Entry<String, String> table : DERIVED.entrySet()) {
            rowsAfter.put(table.getKey(), count("SELECT count(*) FROM " + table.getKey()));
            assertThat(count("SELECT count(*) FROM " + table.getKey() + " WHERE tenant_id IS NULL")).as(table.getKey()).isZero();
            // The reconciliation: rows whose tenant disagrees with the one the JOIN derives.
            assertThat(count("SELECT count(*) FROM (" + table.getValue() + ") r WHERE r.tenant_id IS DISTINCT FROM r.parent"))
                .as(table.getKey()).isZero();
            assertThat(count("SELECT count(*) FROM " + table.getKey() + " WHERE tenant_id = " + A)).as(table.getKey()).isEqualTo(1);
        }
        System.out.println("V102 backfilled " + rowsAfter);
        assertThat(rowsAfter).isEqualTo(ROWS_BEFORE);
    }

    @Test
    void theColumnIsNotNullWithAForeignKeyOntoTheParent() {
        for (String table : DERIVED.keySet()) {
            assertThat(db.sql().queryForObject("SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND table_name = ? AND column_name = 'tenant_id'", String.class, table)).as(table).isEqualTo("NO");
            assertThat(db.sql().queryForList("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = ?::regclass AND contype = 'f'",
                String.class, table)).as(table)
                .noneMatch(fk -> fk.contains("REFERENCES tenant("))
                .anyMatch(fk -> fk.contains(", tenant_id) REFERENCES") && fk.endsWith("ON UPDATE CASCADE"));
        }
    }

    @Test
    void aRowCannotDisagreeWithItsParent() {
        JdbcTemplate sql = db.sql();
        long a = A * 10;
        assertThatThrownBy(() -> sql.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status, tenant_id) "
            + "VALUES (990001, now(), ?, 'Completed', 'Active', ?)", a + 4, B)).hasMessageContaining("fk_job_queue_job_tenant");
        assertThatThrownBy(() -> sql.update("UPDATE scheduler SET tenant_id = ? WHERE scheduler_id = ?", B, a + 5))
            .hasMessageContaining("fk_scheduler_job_tenant");
        assertThatThrownBy(() -> sql.update("INSERT INTO job_audit_logs (job_audit_log_id, date_created, job_queue_id, log_detail, status, tenant_id) "
            + "VALUES (990002, now(), ?, 'x', 'Active', ?)", a + 6, B)).hasMessageContaining("fk_job_audit_logs_run_tenant");
        assertThatThrownBy(() -> sql.update("INSERT INTO source_task_payload (task_payload_id, tag_key, tag_value, payload_id, tenant_id) "
            + "VALUES (990003, 'k', 'v', ?, ?)", a + 2, B)).hasMessageContaining("fk_source_task_payload_task_tenant");
        assertThatThrownBy(() -> sql.update("INSERT INTO pipeline_field (pipeline_field_id, pipeline_key, tag_key, label, tenant_id) VALUES (990004, ?, 'k2', 'K', ?)", a + 8, B)).hasMessageContaining("fk_pipeline_field_pipeline_tenant");
    }

    @Test
    void aWriterThatLeavesItOutGetsTheParents() {
        JdbcTemplate sql = db.sql();
        long b = B * 10;
        sql.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status) VALUES (990011, now(), ?, 'Skip', 'Active')", b + 4);
        sql.update("INSERT INTO job_audit_logs (job_audit_log_id, date_created, job_queue_id, log_detail, status) VALUES (990012, now(), 990011, 'x', 'Active')");
        sql.update("INSERT INTO source_task_payload (task_payload_id, tag_key, tag_value, payload_id) VALUES (990013, 'k', 'v', ?)", b + 2);
        sql.update("INSERT INTO pipeline_field (pipeline_field_id, pipeline_key, tag_key, label) VALUES (990014, ?, 'k3', 'K')", b + 8);
        assertThat(count("SELECT tenant_id FROM job_queue WHERE job_queue_id = 990011")).isEqualTo(B);
        assertThat(count("SELECT tenant_id FROM job_audit_logs WHERE job_audit_log_id = 990012")).isEqualTo(B);
        assertThat(count("SELECT tenant_id FROM source_task_payload WHERE task_payload_id = 990013")).isEqualTo(B);
        assertThat(count("SELECT tenant_id FROM pipeline_field WHERE pipeline_field_id = 990014")).isEqualTo(B);
        sql.update("DELETE FROM job_audit_logs WHERE job_audit_log_id = 990012");
        sql.update("DELETE FROM job_queue WHERE job_queue_id = 990011");
        sql.update("DELETE FROM source_task_payload WHERE task_payload_id = 990013");
        sql.update("DELETE FROM pipeline_field WHERE pipeline_field_id = 990014");
        // A tag Hibernate inserts before pointing it at its task has no parent yet: it must bring its own tenant.
        assertThatThrownBy(() -> sql.update("INSERT INTO source_task_payload (task_payload_id, tag_key, tag_value) VALUES (990015, 'k', 'v')"))
            .hasMessageContaining("tenant_id");
    }

    @Test
    void aJobMovedToAnotherTenantTakesItsRunsSchedulerAndAuditTrailAlong() {
        JdbcTemplate sql = db.sql();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (3403, 'Active', 'T3403', 'T3403')");
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
            + "VALUES (990021, now(), 'Auto', 'mover', 'Active', 1, 3403)");
        sql.update("INSERT INTO scheduler (scheduler_id, job_id, start_date, start_time, frequency, interval_value, expired) "
            + "VALUES (990022, 990021, '2026-01-01', '09:00', 'Daily', '1', false)");
        sql.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status) VALUES (990023, now(), 990021, 'Completed', 'Active')");
        sql.update("INSERT INTO job_audit_logs (job_audit_log_id, date_created, job_queue_id, log_detail, status) VALUES (990024, now(), 990023, 'x', 'Active')");
        sql.update("UPDATE source_job SET tenant_id = ? WHERE job_id = 990021", A);
        assertThat(count("SELECT tenant_id FROM scheduler WHERE scheduler_id = 990022")).isEqualTo(A);
        assertThat(count("SELECT tenant_id FROM job_queue WHERE job_queue_id = 990023")).isEqualTo(A);
        assertThat(count("SELECT tenant_id FROM job_audit_logs WHERE job_audit_log_id = 990024")).isEqualTo(A);
    }

    @Test
    void theParentKeysAndTheirCascadesStayAsTheyWere() {
        JdbcTemplate sql = db.sql();
        // pipeline_field still goes with its pipeline (ON DELETE CASCADE), and the single-column keys are all still there.
        sql.update("INSERT INTO pipeline (pipeline_key, pipeline_id, pipeline_name, tenant_id, status) VALUES (990031, 'F-GONE', 'gone', ?, 'Active')", A);
        sql.update("INSERT INTO pipeline_field (pipeline_field_id, pipeline_key, tag_key, label) VALUES (990032, 990031, 'k', 'K')");
        sql.update("DELETE FROM pipeline WHERE pipeline_key = 990031");
        assertThat(count("SELECT count(*) FROM pipeline_field WHERE pipeline_field_id = 990032")).isZero();
        for (String fk : new String[] {"fk_job_queue_source_job", "fk_scheduler_source_job", "fk_job_audit_logs_job_queue",
            "fk_pipeline_field_pipeline"}) {
            assertThat(count("SELECT count(*) FROM pg_constraint WHERE conname = '" + fk + "'")).as(fk).isEqualTo(1);
        }
        assertThat(count("SELECT count(*) FROM pg_constraint WHERE conrelid = 'source_task_payload'::regclass AND contype = 'f' "
            + "AND pg_get_constraintdef(oid) = 'FOREIGN KEY (payload_id) REFERENCES source_task(task_detail_id)'")).isEqualTo(1);
    }

    @Test
    void theRollbackTakesItAllOffAndV102AppliesAgain() throws Exception {
        JdbcTemplate sql = db.sql();
        db.rollback(sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE orderexecuted >= "
            + "(SELECT orderexecuted FROM databasechangelog WHERE id = ?)", Integer.class, V102));
        try {
            for (String table : DERIVED.keySet()) {
                assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_name = '" + table + "' AND column_name = 'tenant_id'"))
                    .as(table).isZero();
            }
            assertThat(count("SELECT count(*) FROM pg_proc WHERE proname = 'tenant_id_from_parent'")).isZero();
        } finally {
            db.finish();
        }
        assertThat(count("SELECT count(*) FROM job_queue WHERE tenant_id IS NULL")).isZero();
    }

    /**
     * Workspaces live in identity_db since the MIG-107 cutover; etl_job's tenant is a frozen copy a new
     * workspace never reaches. A key from these five tables to it would refuse every run of every workspace
     * made after the cutover, so there is none -- the composite keys onto each parent are the guard.
     */
    @Test
    void noneOfTheFiveReferencesTheFrozenTenantTable() {
        assertThat(db.sql().queryForObject("SELECT count(*) FROM pg_constraint WHERE contype = 'f' "
            + "AND confrelid = 'public.tenant'::regclass AND conrelid::regclass::text IN "
            + "('job_queue', 'scheduler', 'job_audit_logs', 'source_task_payload', 'pipeline_field')", Integer.class)).isZero();
    }
}

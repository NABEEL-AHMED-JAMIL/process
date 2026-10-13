package process.schema;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V210 (MIG-355) as Postgres holds it: the two tenant tables under row security for process_app, the platform's own two
 * not (they hold no workspace), one OPEN alert per rule and subject, a workspace rule never a platform kind and the other way
 * round, the six platform rules seeded, api_event_out's 'alert' rows -- and the way back, and on again.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchEtlJob).
 */
class MonitoringSchemaPostgresTest {

    static final String V210 = "210.0-monitoring";
    private static final long A = 8851L;
    private static final long JOB = 885101L;

    private static ScratchEtlJob db;
    private static JdbcTemplate sql;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("monitoring_v210");
        sql = db.sql();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (?, 'Active', 'mon', 'Monitoring')", A);
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
            + "VALUES (?, now(), 'Manual', 'watched job', 'Active', 1, ?)", JOB, A);
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void theTenantTablesAreGuardedAndThePlatformsAreNot() {
        for (String table : new String[]{"alert_rule", "alert_incident"}) {
            assertThat(sql.queryForMap("SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid = ?::regclass", "public." + table))
                .as(table).containsEntry("relrowsecurity", true).containsEntry("relforcerowsecurity", true);
            assertThat(sql.queryForObject("SELECT qual FROM pg_policies WHERE tablename = ?", String.class, table))
                .isEqualTo(sql.queryForObject("SELECT qual FROM pg_policies WHERE tablename = 'run_approval'", String.class));
        }
        for (String table : new String[]{"service_health_sample", "platform_probe"}) {
            assertThat(sql.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name = ? AND column_name = 'tenant_id'",
                Long.class, table)).as(table).isZero();
            assertThat(sql.queryForObject("SELECT has_table_privilege('process_app', ?, 'INSERT')", Boolean.class, "public." + table)).isTrue();
        }
    }

    @Test
    void thePlatformsSixRulesAreSeededAndAWorkspaceRuleIsAWorkspacesKind() {
        assertThat(sql.queryForList("SELECT kind FROM alert_rule WHERE tenant_id IS NULL ORDER BY alert_rule_id", String.class))
            .containsExactly("SERVICE_UNHEALTHY", "SERVICE_RESTARTED", "MEMORY_HIGH", "KAFKA_LAG", "DISK_LOW", "BILLING_MISMATCH");
        assertThatThrownBy(() -> sql.update("INSERT INTO alert_rule (tenant_id, kind, name) VALUES (?, 'DISK_LOW', 'x')", A))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_alert_rule_scope");
        assertThatThrownBy(() -> sql.update("INSERT INTO alert_rule (tenant_id, kind, name) VALUES (NULL, 'RUN_FAILED', 'x')"))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_alert_rule_scope");
        assertThatThrownBy(() -> sql.update("INSERT INTO alert_rule (tenant_id, kind, name, job_id) VALUES (?, 'RUN_FAILED', 'x', 999)", A))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_alert_rule_job_tenant");
    }

    @Test
    void oneOpenAlertPerRuleAndSubject() {
        Long rule = sql.queryForObject("INSERT INTO alert_rule (tenant_id, kind, name, job_id) VALUES (?, 'RUN_FAILED', 'r', ?) "
            + "RETURNING alert_rule_id", Long.class, A, JOB);
        sql.update("INSERT INTO alert_incident (tenant_id, alert_rule_id, kind, subject, severity, title) VALUES (?, ?, 'RUN_FAILED', 'pipeline:1', "
            + "'WARNING', 't')", A, rule);
        assertThatThrownBy(() -> sql.update("INSERT INTO alert_incident (tenant_id, alert_rule_id, kind, subject, severity, title) "
            + "VALUES (?, ?, 'RUN_FAILED', 'pipeline:1', 'WARNING', 't')", A, rule))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ux_alert_incident_open");
        sql.update("UPDATE alert_incident SET state = 'RESOLVED', resolved_at = now(), resolved_how = 'AUTO' WHERE alert_rule_id = ?", rule);
        sql.update("INSERT INTO alert_incident (tenant_id, alert_rule_id, kind, subject, severity, title) VALUES (?, ?, 'RUN_FAILED', 'pipeline:1', "
            + "'WARNING', 't')", A, rule);
        assertThatThrownBy(() -> sql.update("UPDATE alert_incident SET state = 'RESOLVED' WHERE alert_rule_id = ? AND state = 'OPEN'", rule))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_alert_incident_resolved");
        sql.update("INSERT INTO api_event_out (tenant_id, kind, alert_incident_id, alert_event) SELECT ?, 'alert', max(alert_incident_id), 'opened' "
            + "FROM alert_incident", A);
        assertThatThrownBy(() -> sql.update("INSERT INTO api_event_out (tenant_id, kind, alert_event) VALUES (?, 'alert', 'opened')", A))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_api_event_out_alert");
        sql.update("DELETE FROM alert_rule WHERE alert_rule_id = ?", rule);
        assertThat(sql.queryForObject("SELECT count(*) FROM alert_incident WHERE alert_rule_id = ?", Long.class, rule)).isZero();
    }

    @Test
    void theRollbackTakesItOffAndV210AppliesAgain() throws Exception {
        int after = sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE orderexecuted > "
            + "(SELECT orderexecuted FROM databasechangelog WHERE id = ?)", Integer.class, V210);
        if (after > 0) {
            db.rollback(after);
        }
        sql.update("DELETE FROM api_event_out WHERE kind = 'alert'");
        db.rollback(1);
        try {
            assertThat(sql.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN "
                + "('alert_rule', 'alert_incident', 'service_health_sample', 'platform_probe')", Long.class)).isZero();
            assertThat(sql.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_name = 'api_event_out' "
                + "AND column_name IN ('alert_incident_id', 'alert_event')", Long.class)).isZero();
            assertThat(sql.queryForObject("SELECT count(*) FROM pg_indexes WHERE indexname IN ('idx_job_queue_failed_ended', "
                + "'idx_run_review_pending')", Long.class)).isZero();
        } finally {
            db.finish();
        }
        assertThat(sql.queryForObject("SELECT count(*) FROM databasechangelog WHERE id = ?", Long.class, V210)).isEqualTo(1);
        assertThat(sql.queryForObject("SELECT count(*) FROM pg_policies WHERE tablename IN ('alert_rule', 'alert_incident')", Long.class))
            .isEqualTo(2);
    }
}

package process.model.service.impl;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.model.enums.JobAuditMarker;
import process.schema.ScratchEtlJob;
import process.security.TenantContext;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-77 (DEF-156) end to end in SQL: the run report's exec_seconds against a changelog-built database.
 *
 * A run whose worker wrote the marker on pick-up reports its real execution time -- non-negative, not the
 * -1 sentinel, and far below the queued-to-finished figure beside it. The same run with the line reworded
 * by one letter reports -1: the silent zeroing the shared constant exists to prevent, shown happening.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchEtlJob).
 */
class RunReportExecSecondsPostgresTest {

    private static ScratchEtlJob db;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("run_report");
        JdbcTemplate sql = db.sql();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (2901, 'Active', 'CHS', 'CareBridge')");
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
            + "VALUES (1196, '2026-09-01 09:00', 'Auto', 'Nightly claims', 'Active', 1, 2901)");
        // Queued at :00, picked up at :41.25, done at :41.48 -- the deployment's measured shape.
        for (long run : new long[] {5073L, 5074L}) {
            sql.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status, start_time, end_time) "
                + "VALUES (?, '2026-09-21 14:00:00', 1196, 'Completed', 'Active', '2026-09-21 14:00:00', '2026-09-21 14:00:41.48')", run);
        }
        sql.update("INSERT INTO job_audit_logs (job_audit_log_id, date_created, job_queue_id, log_detail, status) "
            + "VALUES (1, '2026-09-21 14:00:41.25', 5073, ?, 'Active')", JobAuditMarker.JOB_STARTED.logDetail());
        sql.update("INSERT INTO job_audit_logs (job_audit_log_id, date_created, job_queue_id, log_detail, status) "
            + "VALUES (2, '2026-09-21 14:00:41.25', 5074, 'Job Started', 'Active')");
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void aRunWithTheMarkerReportsItsExecutionTimeNotTheWait() {
        Map<String, Object> run = this.reportRow(5073L);

        assertThat((BigDecimal) run.get("exec_seconds")).isEqualByComparingTo("0.23");
        assertThat(((Number) run.get("seconds")).longValue()).isEqualTo(41L);
    }

    @Test
    void aRewordedMarkerSilentlyBecomesTheSentinel() {
        assertThat(((Number) this.reportRow(5074L).get("exec_seconds")).intValue()).isEqualTo(-1);
    }

    private Map<String, Object> reportRow(long runId) {
        TenantContext.set(2901L, "TENANT_ADMIN", 42L, "ops@carebridge.test");
        String report = new QueryService().runReportRows("2026-09-21", "2026-09-21");
        return db.sql().queryForMap("select * from (" + report + ") r where r.run_id = ?", runId);
    }
}

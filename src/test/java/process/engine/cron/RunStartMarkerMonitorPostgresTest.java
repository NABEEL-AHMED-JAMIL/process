package process.engine.cron;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.model.enums.JobAuditMarker;
import process.model.service.impl.QueryService;
import process.schema.ScratchEtlJob;
import process.security.TenantContext;
import process.settings.OrchestrationSettings;
import process.util.OpenSearchAuditLogClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MIG-77 criterion 4 against a changelog-built etl_job: the monitor's one query counts exactly the runs whose
 * run-report row is the exec_seconds -1 sentinel, among the runs that finished in its window, and it is
 * answered from indexes.
 *
 * Opt-in: needs NOTIFICATIONS_TEST_DB_URL (see ScratchEtlJob).
 */
class RunStartMarkerMonitorPostgresTest {

    /** 12:00 in Chicago. The window is the 24 hours before it. */
    private static final Instant NOW = Instant.parse("2026-09-21T17:00:00Z");

    private static ScratchEtlJob db;

    @BeforeAll
    static void build() throws Exception {
        db = ScratchEtlJob.build("marker_monitor");
        JdbcTemplate sql = db.sql();
        sql.update("INSERT INTO tenant (tenant_id, status, tenant_code, tenant_name) VALUES (2901, 'Active', 'CHS', 'CareBridge')");
        sql.update("INSERT INTO source_job (job_id, date_created, execution, job_name, job_status, priority, tenant_id) "
            + "VALUES (1196, '2026-09-01 09:00', 'Auto', 'Nightly claims', 'Active', 1, 2901)");
        // In the window: two marked, one reworded, one with no line at all, one Failed and unmarked.
        run(sql, 6001, "Completed", "2026-09-21T10:00:00Z", "2026-09-21T10:00:41Z", JobAuditMarker.JOB_STARTED.logDetail());
        run(sql, 6002, "Failed", "2026-09-21T11:00:00Z", "2026-09-21T11:00:41Z", JobAuditMarker.JOB_STARTED.logDetail());
        run(sql, 6003, "Completed", "2026-09-21T12:00:00Z", "2026-09-21T12:00:41Z", "Job Started");
        run(sql, 6004, "Completed", "2026-09-20T18:00:00Z", "2026-09-20T18:00:41Z", null);
        run(sql, 6005, "Failed", "2026-09-21T13:00:00Z", "2026-09-21T13:00:41Z", "service-1 started X (attempt 1)");
        // Outside it: finished before the window, after its end, not finished, not a worker's outcome.
        run(sql, 6010, "Completed", "2026-09-19T10:00:00Z", "2026-09-19T10:00:41Z", null);
        run(sql, 6011, "Completed", "2026-09-21T16:59:00Z", "2026-09-21T17:30:00Z", null);
        run(sql, 6012, "Running", "2026-09-21T14:00:00Z", null, null);
        run(sql, 6013, "Interrupt", "2026-09-21T14:00:00Z", "2026-09-21T15:00:00Z", null);
    }

    private static void run(JdbcTemplate sql, long id, String status, String start, String end, String line) {
        sql.update("INSERT INTO job_queue (job_queue_id, date_created, job_id, job_status, status, start_time, end_time, tenant_id) "
                + "VALUES (?, cast(? as timestamptz), 1196, ?, 'Active', cast(? as timestamptz), cast(? as timestamptz), 2901)",
            id, start, status, start, end);
        if (line != null) {
            sql.update("INSERT INTO job_audit_logs (job_audit_log_id, date_created, job_queue_id, log_detail, status) "
                + "VALUES (?, cast(? as timestamptz) + interval '1 second', ?, ?, 'Active')", id, start, id, line);
        }
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    private RunStartMarkerMonitor monitor(SimpleMeterRegistry registry) {
        return new RunStartMarkerMonitor(db.sql(), new OpenSearchAuditLogClient(), new OrchestrationSettings(db.sql()),
            registry, 24, 0.10, 3, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void itCountsTheFinishedRunsInTheWindowAndThoseWithoutTheExactMarker() {
        RunStartMarkerMonitor.Sample sample = this.monitor(new SimpleMeterRegistry())
            .sample(NOW.minusSeconds(24 * 3600), NOW);

        assertThat(sample.finished).isEqualTo(5);
        assertThat(sample.unmarked).as("6003 reworded, 6004 none, 6005 another line").isEqualTo(3);
    }

    /** The gauge's numerator is the report's sentinel: the same runs, read the report's own way. */
    @Test
    void theUnmarkedRunsAreExactlyTheRunReportsMinusOnes() {
        TenantContext.set(2901L, "TENANT_ADMIN", 42L, "ops@carebridge.test");
        try {
            String report = new QueryService().runReportRows("2026-09-20", "2026-09-21");
            List<Long> sentinels = db.sql().queryForList("select run_id from (" + report + ") r "
                + "where r.exec_seconds = -1 and r.run_id between 6001 and 6005 order by run_id", Long.class);

            assertThat(sentinels).containsExactly(6003L, 6004L, 6005L);
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void aMeasurementPublishesTheShareAndWarnsPastTheThreshold() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        boolean warned = this.monitor(registry).measureOnce();

        assertThat(warned).isTrue();
        assertThat(registry.get(RunStartMarkerMonitor.MISSING_RATIO).gauge().value()).isEqualTo(0.6);
        assertThat(registry.get(RunStartMarkerMonitor.FINISHED).gauge().value()).isEqualTo(5.0);
    }

    /**
     * Cheap: with sequential scans priced out, the plan reads job_queue through its day index and the audit log
     * through its run index -- no new index, no table scan as either table grows.
     */
    @Test
    void theQueryIsAnsweredFromExistingIndexes() {
        JdbcTemplate sql = db.sql();
        sql.execute("set enable_seqscan = off");
        try {
            String plan = String.join("\n", sql.queryForList("explain " + RunStartMarkerMonitor.QUERY, String.class,
                RunStartMarkerMonitor.queryArguments(NOW.minusSeconds(24 * 3600), NOW)));

            assertThat(plan).contains("idx_job_queue_date_created_day").contains("idx_job_audit_logs_job_queue_id")
                .doesNotContain("Seq Scan");
        } finally {
            sql.execute("set enable_seqscan = on");
        }
    }
}

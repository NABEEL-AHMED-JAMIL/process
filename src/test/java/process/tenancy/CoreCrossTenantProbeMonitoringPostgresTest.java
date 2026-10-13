package process.tenancy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static process.tenancy.CoreProbeFixture.A;
import static process.tenancy.CoreProbeFixture.ADMIN_OF_A;
import static process.tenancy.CoreProbeFixture.ADMIN_OF_C;
import static process.tenancy.CoreProbeFixture.A_JOB;
import static process.tenancy.CoreProbeFixture.A_RUN;
import static process.tenancy.CoreProbeFixture.B;
import static process.tenancy.CoreProbeFixture.B_JOB;
import static process.tenancy.CoreProbeFixture.B_RUN;
import static process.tenancy.CoreProbeFixture.NO_WORKSPACE;
import static process.tenancy.CoreProbeFixture.REFUSED;
import static process.tenancy.CoreProbeFixture.SUCCEEDED;
import static process.tenancy.CoreProbeFixture.USER_OF_A;
import static process.tenancy.CoreProbeFixture.tenantlessAdmin;
import static process.tenancy.CoreProbeFixture.tenantlessUser;

/**
 * MIG-166's cross-tenant probe for MIG-355: Runs › Failed (message.json/failedRuns), the bulk retry (sourceJob.json/bulkRetry)
 * and a workspace's alert rules and alerts (reliability.json/alerts/...), called by workspace A's people, by C's admin and by
 * callers with no workspace, aimed at B's failed run, rule and alert -- against a real etl_job as process_app, so row-level
 * security answers as well as the code. Nothing of B's comes back, B's rule and alert are neither changed nor removed, and a
 * caller with no workspace is refused.
 */
class CoreCrossTenantProbeMonitoringPostgresTest {

    private static CoreProbeFixture fx;
    private static long bRule;
    private static long bAlert;

    @BeforeAll
    static void build() throws Exception {
        fx = CoreProbeFixture.create("core_probe_monitoring");
        JdbcTemplate sql = fx.db.jdbc();
        Timestamp ended = Timestamp.from(Instant.now().minusSeconds(600));
        sql.update("UPDATE job_queue SET job_status = 'Failed', end_time = ?, end_reason = 'WORKER' WHERE job_queue_id IN (?, ?)", ended, A_RUN,
            B_RUN);
        bRule = sql.queryForObject("INSERT INTO alert_rule (tenant_id, kind, name, job_id) VALUES (?, 'RUN_FAILED', 'Bravo Secret Job alert', ?) "
            + "RETURNING alert_rule_id", Long.class, B, B_JOB);
        bAlert = sql.queryForObject("INSERT INTO alert_incident (tenant_id, alert_rule_id, kind, subject, severity, title, detail) VALUES "
            + "(?, ?, 'RUN_FAILED', 'pipeline:1', 'WARNING', 'Bravo Secret Job failed', 'bravo run message') RETURNING alert_incident_id",
            Long.class, B, bRule);
    }

    @AfterAll
    static void drop() throws Exception {
        if (fx != null) {
            fx.close();
        }
    }

    @BeforeEach
    void forgetEarlierCalls() {
        fx.reset();
    }

    private static Map<String, Object> window() {
        Map<String, Object> body = new HashMap<>();
        body.put("from", Instant.now().minusSeconds(86_400).toString());
        body.put("to", Instant.now().plusSeconds(60).toString());
        return body;
    }

    @Test
    void failedRunsAreTheCallersWorkspacesOnly() {
        assertThat(fx.probe("POST message.json/failedRuns", ADMIN_OF_A, () -> fx.failedRuns.failedRuns(window())))
            .contains(SUCCEEDED).contains("\"runId\":" + A_RUN).doesNotContain("\"runId\":" + B_RUN);
        Map<String, Object> naming = window();
        naming.put("jobIds", Collections.singletonList(B_JOB));
        naming.put("tenantIds", Collections.singletonList(B));
        assertThat(fx.probe("POST message.json/failedRuns(naming B's)", ADMIN_OF_A, () -> fx.failedRuns.failedRuns(naming)))
            .contains(SUCCEEDED).contains("\"total\":0");
        assertThat(fx.probe("POST message.json/failedRuns", USER_OF_A, () -> fx.failedRuns.failedRuns(window())))
            .contains(SUCCEEDED).doesNotContain("\"runId\":" + B_RUN);
        assertThat(fx.probe("POST message.json/failedRuns(as C)", ADMIN_OF_C, () -> fx.failedRuns.failedRuns(window())))
            .contains(SUCCEEDED).contains("\"total\":0");
        for (Long none : NO_WORKSPACE) {
            assertThat(fx.probe("POST message.json/failedRuns", tenantlessUser(none), () -> fx.failedRuns.failedRuns(window())))
                .contains(REFUSED);
        }
        assertThat(fx.leaks).isEmpty();
    }

    @Test
    void aBulkRetryOfAnotherWorkspacesRunIsNotFound() {
        String before = fx.foreignRows();
        Map<String, Object> body = new HashMap<>();
        body.put("jobQueueIds", Arrays.asList(B_RUN));
        body.put("dryRun", true);
        assertThat(fx.probe("POST sourceJob.json/bulkRetry", ADMIN_OF_A, () -> fx.failedRuns.bulkRetry(body)))
            .contains(SUCCEEDED).contains("\"reason\":\"not-found\"").doesNotContain("\"done\":[{");
        Map<String, Object> real = new HashMap<>(body);
        real.put("dryRun", false);
        assertThat(fx.probe("POST sourceJob.json/bulkRetry", ADMIN_OF_A, () -> fx.failedRuns.bulkRetry(real)))
            .contains(SUCCEEDED).contains("0 retried, 1 skipped.");
        for (Long none : NO_WORKSPACE) {
            assertThat(fx.probe("POST sourceJob.json/bulkRetry", tenantlessAdmin(none), () -> fx.failedRuns.bulkRetry(real))).contains(REFUSED);
        }
        assertThat(fx.foreignRows()).isEqualTo(before);
        assertThat(fx.leaks).isEmpty();
    }

    @Test
    void anotherWorkspacesRulesAndAlertsAreNeitherReadNorChanged() {
        String before = fx.foreignRows();
        assertThat(fx.probe("GET reliability.json/alerts/rules", ADMIN_OF_A, () -> fx.alerts.list())).contains(SUCCEEDED)
            .contains("\"rules\":[]");
        assertThat(fx.probe("GET reliability.json/alerts/incidents", ADMIN_OF_A, () -> fx.alerts.incidents(null, null))).contains(SUCCEEDED)
            .contains("0 open alert(s).");
        Map<String, Object> theirs = new HashMap<>();
        theirs.put("id", bRule);
        theirs.put("kind", "RUN_FAILED");
        theirs.put("name", "taken over");
        assertThat(fx.probe("POST reliability.json/alerts/rules(B's id)", ADMIN_OF_A, () -> fx.alerts.save(theirs))).contains(REFUSED)
            .contains("No such alert rule.");
        Map<String, Object> theirJob = new HashMap<>();
        theirJob.put("kind", "RUN_FAILED");
        theirJob.put("jobId", B_JOB);
        assertThat(fx.probe("POST reliability.json/alerts/rules", ADMIN_OF_A, () -> fx.alerts.save(theirJob))).contains(REFUSED)
            .contains("No such pipeline in this workspace");
        assertThat(fx.probe("POST reliability.json/alerts/rules/delete", ADMIN_OF_A,
            () -> fx.alerts.delete(Collections.singletonMap("id", bRule)))).contains(REFUSED);
        assertThat(fx.probe("POST reliability.json/alerts/incidents/resolve", ADMIN_OF_A,
            () -> fx.alerts.resolve(Collections.singletonMap("id", bAlert)))).contains(REFUSED);
        assertThat(fx.probe("GET reliability.json/alerts/rules(as C)", ADMIN_OF_C, () -> fx.alerts.list())).contains("\"rules\":[]");
        for (Long none : NO_WORKSPACE) {
            assertThat(fx.probe("GET reliability.json/alerts/rules", tenantlessAdmin(none), () -> fx.alerts.list())).contains(REFUSED);
            assertThat(fx.probe("GET reliability.json/alerts/incidents", tenantlessAdmin(none), () -> fx.alerts.incidents("open", 10)))
                .contains(REFUSED);
        }
        assertThat(fx.foreignRows()).isEqualTo(before);
        assertThat(fx.db.jdbc().queryForObject("SELECT name FROM alert_rule WHERE alert_rule_id = ?", String.class, bRule))
            .isEqualTo("Bravo Secret Job alert");
        assertThat(fx.db.jdbc().queryForObject("SELECT state FROM alert_incident WHERE alert_incident_id = ?", String.class, bAlert))
            .isEqualTo("OPEN");

        Map<String, Object> mine = new HashMap<>();
        mine.put("kind", "RUN_FAILED");
        mine.put("jobId", A_JOB);
        assertThat(fx.probe("POST reliability.json/alerts/rules(mine)", ADMIN_OF_A, () -> fx.alerts.save(mine))).contains(SUCCEEDED);
        assertThat(fx.db.jdbc().queryForObject("SELECT tenant_id FROM alert_rule WHERE job_id = ?", Long.class, A_JOB)).isEqualTo(A);
        assertThat(fx.leaks).isEmpty();
    }
}

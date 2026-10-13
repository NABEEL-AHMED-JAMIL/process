package process.tenancy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static process.tenancy.CoreProbeFixture.A;
import static process.tenancy.CoreProbeFixture.ADMIN_OF_A;
import static process.tenancy.CoreProbeFixture.ADMIN_OF_C;
import static process.tenancy.CoreProbeFixture.B;
import static process.tenancy.CoreProbeFixture.B_JOB;
import static process.tenancy.CoreProbeFixture.B_RUN;
import static process.tenancy.CoreProbeFixture.C;
import static process.tenancy.CoreProbeFixture.NO_WORKSPACE;
import static process.tenancy.CoreProbeFixture.PLATFORM;
import static process.tenancy.CoreProbeFixture.REFUSED;
import static process.tenancy.CoreProbeFixture.SUCCEEDED;
import static process.tenancy.CoreProbeFixture.tenantlessAdmin;

/**
 * MIG-166's cross-tenant probe for MIG-354: a workspace's data retention (setting.json/retention/...) called by A's
 * administrator, by C's and by callers with no workspace, aimed at B's policy, hold, pass and audit -- against a real
 * etl_job as process_app, so row-level security answers as well as the code. Naming B (tenantId) still works on the
 * caller's own workspace; B's hold cannot be released, B's pipeline and run cannot be held; nothing of B's comes back and
 * none of B's rows changes; a caller with no workspace is refused.
 */
class CoreCrossTenantProbeRetentionPostgresTest {

    private static CoreProbeFixture fx;
    private static long bHold;

    @BeforeAll
    static void build() throws Exception {
        fx = CoreProbeFixture.create("core_probe_retention");
        JdbcTemplate sql = fx.db.jdbc();
        sql.update("INSERT INTO retention_policy (tenant_id, enabled) VALUES (?, true)", B);
        sql.update("INSERT INTO retention_rule (tenant_id, data_class, keep_days) VALUES (?, 'RUN_LOGS', 30)", B);
        bHold = sql.queryForObject("INSERT INTO legal_hold (tenant_id, scope, job_id, reason) VALUES (?, 'PIPELINE', ?, 'Bravo Secret Job hold') "
            + "RETURNING legal_hold_id", Long.class, B, B_JOB);
        long pass = sql.queryForObject("INSERT INTO retention_pass (tenant_id, started_how, state, finished_at, note) VALUES (?, 'SCHEDULE', "
            + "'DONE', now(), 'bravo run message') RETURNING retention_pass_id", Long.class, B);
        sql.update("INSERT INTO retention_pass_class (retention_pass_id, tenant_id, data_class, state, note) VALUES (?, ?, 'RUN_LOGS', 'DONE', "
            + "'bravo audit line')", pass, B);
        sql.update("INSERT INTO retention_audit (tenant_id, action, detail) VALUES (?, 'HOLD_PLACED', 'Bravo Secret Job hold placed')", B);
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

    private static Map<String, Object> body(Object... pairs) {
        Map<String, Object> out = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], pairs[i + 1]);
        }
        return out;
    }

    @Test
    void readsAreTheCallersWorkspaceEvenWhenTheyNameAnother() {
        assertThat(fx.probe("GET setting.json/retention", ADMIN_OF_A, () -> fx.retention.view(null))).contains(SUCCEEDED)
            .contains("\"tenantId\":" + A).contains("\"holds\":[]").contains("\"enabled\":false");
        assertThat(fx.probe("GET setting.json/retention(naming B)", ADMIN_OF_A, () -> fx.retention.view(B))).contains(SUCCEEDED)
            .contains("\"tenantId\":" + A).doesNotContain("\"tenantId\":" + B);
        assertThat(fx.probe("GET setting.json/retention/passes", ADMIN_OF_A, () -> fx.retention.passes(B, 10))).contains(SUCCEEDED)
            .contains("0 retention pass(es).");
        assertThat(fx.probe("GET setting.json/retention/audit", ADMIN_OF_A, () -> fx.retention.audit(B, 10))).contains(SUCCEEDED)
            .contains("0 audit line(s).");
        assertThat(fx.probe("POST setting.json/retention/preview", ADMIN_OF_A, () -> fx.retention.preview(body("tenantId", B,
            "classes", body("RUN_LOGS", 1))))).contains(SUCCEEDED).contains("\"tenantId\":" + A);
        assertThat(fx.probe("GET setting.json/retention(as C)", ADMIN_OF_C, () -> fx.retention.view(B))).contains(SUCCEEDED)
            .contains("\"tenantId\":" + C);
        assertThat(fx.leaks).isEmpty();
    }

    @Test
    void anotherWorkspacesPolicyHoldsAndRunsAreNeitherChangedNorHeld() {
        String before = fx.foreignRows();
        assertThat(fx.probe("POST setting.json/retention/run", ADMIN_OF_A, () -> fx.retention.run(body("tenantId", B)))).contains(REFUSED)
            .contains("Retention is off in this workspace");
        assertThat(fx.probe("POST setting.json/retention/confirm", ADMIN_OF_A, () -> fx.retention.confirm(body("tenantId", B,
            "dataClass", "RUN_LOGS", "expectedCount", 1)))).contains(REFUSED).contains("kept forever");
        assertThat(fx.probe("POST setting.json/retention/holds(B's pipeline)", ADMIN_OF_A, () -> fx.retention.placeHold(body("scope", "pipeline",
            "jobId", B_JOB, "reason", "taken over")))).contains(REFUSED).contains("Name one of this workspace's pipelines");
        assertThat(fx.probe("POST setting.json/retention/holds(B's run)", ADMIN_OF_A, () -> fx.retention.placeHold(body("scope", "run",
            "runId", B_RUN, "reason", "taken over")))).contains(REFUSED).contains("Name one of this workspace's runs");
        assertThat(fx.probe("POST setting.json/retention/holds/release", ADMIN_OF_A, () -> fx.retention.releaseHold(body("id", bHold,
            "reason", "taken over")))).contains(REFUSED).contains("No such hold.");
        // Saving while naming B saves the caller's own policy; B's is as it was.
        assertThat(fx.probe("POST setting.json/retention", ADMIN_OF_A, () -> fx.retention.save(body("tenantId", B, "enabled", false,
            "classes", body("RUN_LOGS", 3650))))).contains(SUCCEEDED).contains("\"tenantId\":" + A);
        assertThat(fx.foreignRows()).isEqualTo(before);
        assertThat(fx.db.jdbc().queryForObject("SELECT keep_days FROM retention_rule WHERE tenant_id = ? AND data_class = 'RUN_LOGS'", Integer.class,
            B)).isEqualTo(30);
        assertThat(fx.leaks).isEmpty();
    }

    @Test
    void aCallerWithNoWorkspaceIsRefusedAndAPlatformAdministratorMustNameOne() {
        String before = fx.foreignRows();
        for (Long none : NO_WORKSPACE) {
            assertThat(fx.probe("GET setting.json/retention", tenantlessAdmin(none), () -> fx.retention.view(B))).contains(REFUSED);
            assertThat(fx.probe("POST setting.json/retention", tenantlessAdmin(none), () -> fx.retention.save(body("enabled", true))))
                .contains(REFUSED);
            assertThat(fx.probe("POST setting.json/retention/holds", tenantlessAdmin(none), () -> fx.retention.placeHold(body("scope",
                "workspace", "reason", "x")))).contains(REFUSED);
            assertThat(fx.probe("POST setting.json/retention/run", tenantlessAdmin(none), () -> fx.retention.run(body()))).contains(REFUSED);
        }
        assertThat(fx.probe("GET setting.json/retention(platform, none named)", PLATFORM, () -> fx.retention.view(null))).contains(REFUSED)
            .contains("Name the workspace (tenantId).");
        assertThat(fx.foreignRows()).isEqualTo(before);
        assertThat(fx.leaks).isEmpty();
    }
}

package process.tenancy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import process.api.CustomerBackfillsRestApi;
import process.backfill.BackfillRestApi;
import process.backfill.BackfillSettings;
import process.backfill.BackfillStore;
import process.backfill.Backfills;
import process.billing.BillingPort;
import process.billing.WorkspaceZones;
import process.customer.ApiReceipts;
import process.customer.CustomerBackfills;
import process.customer.Idempotency;
import process.pipeline.PipelineDefinitionStore;
import process.security.TenantContext;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static process.tenancy.CoreProbeFixture.A;
import static process.tenancy.CoreProbeFixture.ADMIN_OF_A;
import static process.tenancy.CoreProbeFixture.ADMIN_OF_C;
import static process.tenancy.CoreProbeFixture.A_JOB;
import static process.tenancy.CoreProbeFixture.A_PIPELINE;
import static process.tenancy.CoreProbeFixture.B;
import static process.tenancy.CoreProbeFixture.B_JOB;
import static process.tenancy.CoreProbeFixture.B_PIPELINE;
import static process.tenancy.CoreProbeFixture.B_RUN;
import static process.tenancy.CoreProbeFixture.COLLEAGUE_JOB;
import static process.tenancy.CoreProbeFixture.Caller;
import static process.tenancy.CoreProbeFixture.NO_WORKSPACE;
import static process.tenancy.CoreProbeFixture.REFUSED;
import static process.tenancy.CoreProbeFixture.SUCCEEDED;
import static process.tenancy.CoreProbeFixture.USER_OF_A;
import static process.tenancy.CoreProbeFixture.tenantlessAdmin;
import static process.tenancy.CoreProbeFixture.tenantlessUser;

/**
 * MIG-166's cross-tenant probe for MIG-347: every backfill endpoint -- the console's under sourceJob.json and the customer
 * API's -- called by workspace A's administrator and tenant user, C's administrator, callers with no workspace and an API
 * client of A, aimed at B's schedule, B's backfill and B's run, against a real etl_job as process_app. Nothing of B's comes
 * back, B's backfill is neither paused, cancelled nor retried, no backfill is started for B's schedule, and a caller with no
 * workspace is refused -- while A's own backfill starts.
 */
class CoreCrossTenantProbeBackfillPostgresTest {

    static final Caller CLIENT_OF_A = new Caller(A, "API_CLIENT", null, "client:cl_acme");
    private static final String DEFINITION = "{\"version\":1,\"steps\":[{\"key\":\"read\",\"task\":\"sample\",\"config\":{\"rows\":[{\"id\":1}]}}]}";

    private static CoreProbeFixture fx;
    private static BackfillRestApi api;
    private static CustomerBackfillsRestApi customer;
    private static long bBackfill;
    private static String from;
    private static String to;

    @BeforeAll
    static void build() throws Exception {
        fx = CoreProbeFixture.create("core_probe_backfill");
        JdbcTemplate sql = fx.db.jdbc();
        for (long pipeline : new long[] {A_PIPELINE, B_PIPELINE}) {
            sql.update("INSERT INTO pipeline_definition (tenant_id, pipeline_key, version, definition) SELECT tenant_id, pipeline_key, 1, ?::json "
                + "FROM pipeline WHERE pipeline_key = ?", DEFINITION, pipeline);
        }
        LocalDate today = LocalDate.now(ZoneId.of("America/Chicago"));
        from = today.minusDays(5).toString();
        to = today.minusDays(4).toString();
        bBackfill = sql.queryForObject("INSERT INTO backfill (tenant_id, job_id, status, step, range_from, range_to, zone, concurrency, total, "
            + "ended_at) VALUES (?, ?, 'FAILED', 'DAILY', ?::date, ?::date, 'America/Chicago', 2, 2, now()) RETURNING backfill_id", Long.class, B,
            B_JOB, from, to);
        sql.update("INSERT INTO backfill_slot (backfill_id, slot_start, slot_end, run_date, tenant_id, job_id, state, job_queue_id, note) VALUES "
            + "(?, ?::date::timestamptz, ?::date::timestamptz + interval '1 day', ?::date, ?, ?, 'FAILED', ?, 'bravo run message marker')",
            bBackfill, from, from, from, B, B_JOB, B_RUN);
        sql.update("UPDATE job_queue SET backfill_id = ?, run_lane = 1, job_status = 'Failed' WHERE job_queue_id = ?", bBackfill, B_RUN);

        JdbcTemplate app = fx.db.appJdbc();
        BillingPort billing = new BillingPort() {
            @Override
            public String zoneOf(long tenantId) {
                return "America/Chicago";
            }

            @Override
            public RunCosts runCosts(long tenantId, LocalDate from, LocalDate to, Collection<Long> ids) {
                return new RunCosts("USD", "America/Chicago", Collections.emptyMap());
            }
        };
        Backfills backfills = new Backfills(new BackfillStore(app), new PipelineDefinitionStore(app), new WorkspaceZones(billing), billing,
            BackfillSettings.defaults(), new TransactionTemplate(fx.jpa.transactionManager()), Clock.systemUTC(), tenant -> Optional.empty());
        api = new BackfillRestApi(backfills);
        customer = new CustomerBackfillsRestApi(new CustomerBackfills(backfills, new Idempotency(new ApiReceipts(app))));
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

    static Map<String, Object> body(long jobId) {
        Map<String, Object> body = new HashMap<>();
        body.put("jobId", jobId);
        body.put("from", from);
        body.put("to", to);
        return body;
    }

    static Map<String, Object> theirs() {
        return Collections.singletonMap("backfillId", bBackfill);
    }

    private static String asClient(String endpoint, CoreProbeFixture.Endpoint call, String... scopes) {
        return fx.probe(endpoint, CLIENT_OF_A, () -> {
            TenantContext.setApiClient("cl_acme", new LinkedHashSet<>(Arrays.asList(scopes)));
            return call.call();
        });
    }

    @Test
    void anotherWorkspacesScheduleBackfillAndRunAreNotFound() {
        String before = fx.foreignRows();
        assertThat(fx.probe("POST sourceJob.json/backfills/preview", ADMIN_OF_A, () -> api.preview(body(B_JOB)))).contains(REFUSED)
            .contains("No such schedule.");
        assertThat(fx.probe("POST sourceJob.json/backfills/start", ADMIN_OF_A, () -> api.start(body(B_JOB)))).contains(REFUSED)
            .contains("No such schedule.");
        assertThat(fx.probe("GET sourceJob.json/backfills", ADMIN_OF_A, () -> api.list(null, null, 1, 25))).contains(SUCCEEDED)
            .doesNotContain("\"backfillId\":" + bBackfill);
        assertThat(fx.probe("GET sourceJob.json/backfills(B's job)", ADMIN_OF_A, () -> api.list(B_JOB, null, 1, 25))).contains(SUCCEEDED)
            .contains("\"total\":0");
        assertThat(fx.probe("GET sourceJob.json/backfills/detail", ADMIN_OF_A, () -> api.detail(bBackfill, null, 1, null, null))).contains(REFUSED)
            .contains("No such backfill.");
        assertThat(fx.probe("POST sourceJob.json/backfills/pause", ADMIN_OF_A, () -> api.pause(theirs()))).contains(REFUSED);
        assertThat(fx.probe("POST sourceJob.json/backfills/resume", ADMIN_OF_A, () -> api.resume(theirs()))).contains(REFUSED);
        assertThat(fx.probe("POST sourceJob.json/backfills/cancel", ADMIN_OF_A, () -> api.cancel(theirs()))).contains(REFUSED);
        assertThat(fx.probe("POST sourceJob.json/backfills/retryFailed", ADMIN_OF_A, () -> api.retryFailed(theirs()))).contains(REFUSED);
        assertThat(fx.probe("GET sourceJob.json/backfills/ofRun", ADMIN_OF_A, () -> api.ofRun(B_RUN))).contains(SUCCEEDED)
            .doesNotContain("\"data\"");
        assertThat(fx.probe("GET sourceJob.json/backfills(as C)", ADMIN_OF_C, () -> api.list(null, null, 1, 25))).contains("\"total\":0");
        // A tenant user: a colleague's schedule is not theirs.
        assertThat(fx.probe("POST sourceJob.json/backfills/preview(colleague's)", USER_OF_A, () -> api.preview(body(COLLEAGUE_JOB))))
            .contains(REFUSED).contains("No such schedule.");
        for (Long none : NO_WORKSPACE) {
            assertThat(fx.probe("GET sourceJob.json/backfills", tenantlessAdmin(none), () -> api.list(null, null, 1, 25))).contains(REFUSED);
            assertThat(fx.probe("POST sourceJob.json/backfills/start", tenantlessUser(none), () -> api.start(body(B_JOB)))).contains(REFUSED);
            assertThat(fx.probe("GET sourceJob.json/backfills/ofRun", tenantlessAdmin(none), () -> api.ofRun(B_RUN))).doesNotContain("\"data\"");
        }
        assertThat(fx.foreignRows()).isEqualTo(before);
        assertThat(fx.db.jdbc().queryForObject("SELECT status FROM backfill WHERE backfill_id = ?", String.class, bBackfill)).isEqualTo("FAILED");
        assertThat(fx.db.jdbc().queryForObject("SELECT count(*) FROM backfill WHERE job_id = ?", Long.class, B_JOB)).isEqualTo(1);
        assertThat(fx.leaks).isEmpty();
    }

    @Test
    void anApiClientOfABackfillsNothingOfBs() {
        String before = fx.foreignRows();
        byte[] request = ("{\"from\":\"" + from + "\",\"to\":\"" + to + "\"}").getBytes(StandardCharsets.UTF_8);
        assertThat(asClient("POST customer/pipelines/{pipelineId}/backfills", () -> customer.start(String.valueOf(B_JOB), "probe-backfill-b-01",
            request), "runs:write")).contains("\"status\":404");
        assertThat(asClient("GET customer/backfills/{backfillId}", () -> customer.get(String.valueOf(bBackfill)), "runs:read"))
            .contains("\"status\":404");
        assertThat(asClient("POST customer/backfills/{backfillId}/cancel", () -> customer.cancel(String.valueOf(bBackfill),
            "probe-backfill-b-02"), "runs:write")).contains("\"status\":404");
        // Without the scope, refused before anything is read.
        assertThat(asClient("POST customer/pipelines/{pipelineId}/backfills(no scope)", () -> customer.start(String.valueOf(A_JOB),
            "probe-backfill-a-00", request), "runs:read")).contains("\"status\":403").contains("insufficient-scope");
        assertThat(fx.foreignRows()).isEqualTo(before);

        // A's own: started, read and cancelled.
        String started = asClient("POST customer/pipelines/{pipelineId}/backfills(A's own)", () -> customer.start(String.valueOf(A_JOB),
            "probe-backfill-a-01", request), "runs:write");
        assertThat(started).contains("\"status\":\"running\"").contains("\"pipelineId\":\"" + A_JOB + "\"").contains("\"total\":2");
        String id = fx.db.jdbc().queryForObject("SELECT max(backfill_id)::text FROM backfill WHERE job_id = ?", String.class, A_JOB);
        assertThat(asClient("GET customer/backfills/{backfillId}(A's own)", () -> customer.get(id), "runs:read"))
            .contains("\"backfillId\":\"" + id + "\"").contains("\"periods\":[");
        assertThat(asClient("POST customer/backfills/{backfillId}/cancel(A's own)", () -> customer.cancel(id, "probe-backfill-a-02"), "runs:write"))
            .contains("\"status\":\"cancelled\"");
        assertThat(fx.db.jdbc().queryForObject("SELECT tenant_id FROM backfill WHERE backfill_id = ?::bigint", Long.class, id)).isEqualTo(A);
        assertThat(fx.leaks).isEmpty();
    }
}

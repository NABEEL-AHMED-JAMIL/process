package process.tenancy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import process.pipeline.review.RunReviewRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static process.tenancy.CoreProbeFixture.*;

/**
 * MIG-166's cross-tenant probe for MIG-237's run review: sourceJob.json/review and sourceJob.json/review/decide, called
 * by workspace A's tenant user and admin, aimed at workspace B's runs and at a colleague's, and by callers with no
 * workspace -- against a real etl_job as process_app (CoreProbeFixture), so row-level security answers as well as the
 * code. Both workspaces' pipelines require the internal review and every run is Completed. A probe passes when nothing
 * of B's (or, for a tenant user, of the colleague's) comes back, every refusal reads as a run that does not exist, and
 * no review row of anyone else's is written.
 */
class CoreCrossTenantProbeRunReviewPostgresTest {

    private static final String REVIEW = "{\"version\":1,\"source\":{\"type\":\"task\"},"
        + "\"steps\":[{\"key\":\"legacy\",\"task\":\"legacy\"}],"
        + "\"settings\":{\"review\":{\"required\":[\"internal\"]}}}";

    private static CoreProbeFixture fx;

    @BeforeAll
    static void build() throws Exception {
        fx = CoreProbeFixture.create("core_probe_run_review");
        JdbcTemplate sql = fx.db.jdbc();
        for (long pipeline : new long[] {A_PIPELINE, B_PIPELINE}) {
            sql.update("INSERT INTO pipeline_definition (tenant_id, pipeline_key, version, definition, date_created) "
                + "SELECT tenant_id, pipeline_key, 1, ?::json, now() - interval '30 days' FROM pipeline WHERE pipeline_key = ?",
                REVIEW, pipeline);
        }
        sql.update("UPDATE job_queue SET job_status = 'Completed' WHERE job_queue_id IN (?, ?, ?, ?)", A_RUN, COLLEAGUE_RUN, B_RUN,
            B_RUN_NAMING_USER_A);
        for (long run : new long[] {A_RUN, B_RUN}) {
            sql.update("INSERT INTO result_record (job_queue_id, result) VALUES (?, '{\"total\": 3}'::jsonb)", run);
        }
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

    private static RunReviewRequest decision(long run, String decision, String reason) {
        RunReviewRequest request = new RunReviewRequest();
        request.setJobQueueId(run);
        request.setDecision(decision);
        request.setComment("probe comment");
        request.setReason(reason);
        return request;
    }

    @Test
    void noReviewOfAnotherWorkspaceOrAColleagueIsReadOrDecided() {
        String before = fx.foreignRows();
        for (Caller caller : new Caller[] {USER_OF_A, ADMIN_OF_A}) {
            long[] runs = caller == USER_OF_A ? new long[] {B_RUN, B_RUN_NAMING_USER_A, COLLEAGUE_RUN}
                : new long[] {B_RUN, B_RUN_NAMING_USER_A};
            for (long theirs : runs) {
                assertThat(fx.probe("GET sourceJob.json/review", caller, () -> fx.runReview.review(theirs)))
                    .contains(REFUSED).contains("Run not found with jobQueueId.");
                assertThat(fx.probe("POST sourceJob.json/review/decide", caller,
                    () -> fx.runReview.decide(decision(theirs, "REJECTED", "probe says no")))).contains(REFUSED);
            }
        }
        assertThat(fx.probe("POST sourceJob.json/review/decide(B's, as admin)", ADMIN_OF_A,
            () -> fx.runReview.decide(decision(B_RUN, "APPROVED", null)))).contains("Run not found with jobQueueId.");
        assertThat(fx.probe("GET sourceJob.json/review(A's, as C)", ADMIN_OF_C, () -> fx.runReview.review(A_RUN)))
            .contains("Run not found with jobQueueId.");
        for (Long none : NO_WORKSPACE) {
            Caller caller = tenantlessUser(none);
            assertThat(fx.probe("GET sourceJob.json/review", caller, () -> fx.runReview.review(A_RUN))).contains(REFUSED);
            assertThat(fx.probe("POST sourceJob.json/review/decide", caller, () -> fx.runReview.decide(decision(A_RUN, "APPROVED", null))))
                .contains(REFUSED);
        }
        assertThat(fx.leaks).isEmpty();
        assertThat(fx.foreignRows()).isEqualTo(before);
        assertThat(fx.db.jdbc().queryForObject("SELECT count(*) FROM run_review_decision WHERE job_queue_id <> ?", Long.class, A_RUN))
            .isZero();
    }

    @Test
    void theConsoleRecordsTheInternalReviewOnlyAndATenantUserDoesNotDecide() {
        RunReviewRequest asCustomer = decision(COLLEAGUE_RUN, "APPROVED", null);
        asCustomer.setParty("customer");
        assertThat(fx.probe("POST sourceJob.json/review/decide(customer party)", ADMIN_OF_A, () -> fx.runReview.decide(asCustomer)))
            .contains(REFUSED).contains("A customer review is recorded by the customer through the API");
        assertThat(fx.probe("POST sourceJob.json/review/decide(tenant user)", USER_OF_A,
            () -> fx.runReview.decide(decision(A_RUN, "APPROVED", null))))
            .contains(REFUSED).contains("Only a tenant administrator can approve or reject a run's results.");
        assertThat(fx.db.jdbc().queryForObject("SELECT count(*) FROM run_review_decision WHERE job_queue_id = ?", Long.class,
            COLLEAGUE_RUN)).isZero();
        assertThat(fx.leaks).isEmpty();
    }

    /** The control: A's admin reads A's pending review, approves it, and the decision shows on the review and the manifest. */
    @Test
    void myWorkspacesRunIsReviewedAndAudited() {
        assertThat(fx.probe("GET sourceJob.json/review(mine)", USER_OF_A, () -> fx.runReview.review(A_RUN)))
            .contains(SUCCEEDED).contains("\"reviewStatus\":\"PENDING\"").contains("\"required\":[\"internal\"]");
        assertThat(fx.probe("POST sourceJob.json/review/decide(mine)", ADMIN_OF_A,
            () -> fx.runReview.decide(decision(A_RUN, "APPROVED", null))))
            .contains(SUCCEEDED).contains("\"reviewStatus\":\"APPROVED\"").contains("\"reviewer\":\"alice@acme.example\"");
        assertThat(fx.probe("GET sourceJob.json/runOutputs(reviewed)", USER_OF_A, () -> fx.stepTimeline.runOutputs(A_RUN, null)))
            .contains(SUCCEEDED).contains("\"reviewStatus\":\"APPROVED\"");
        assertThat(fx.probe("POST sourceJob.json/review/decide(again)", ADMIN_OF_A,
            () -> fx.runReview.decide(decision(A_RUN, "REJECTED", "second thoughts"))))
            .contains(REFUSED).contains("This run's results are already approved.");
        JdbcTemplate sql = fx.db.jdbc();
        assertThat(sql.queryForObject("SELECT tenant_id FROM run_review_decision WHERE job_queue_id = ?", Long.class, A_RUN)).isEqualTo(A);
        assertThat(sql.queryForObject("SELECT status FROM run_review WHERE job_queue_id = ?", String.class, A_RUN)).isEqualTo("APPROVED");
        assertThat(sql.queryForObject("SELECT review_status FROM result_record WHERE job_queue_id = ?", String.class, A_RUN))
            .as("the run's results take its decision").isEqualTo("APPROVED");
        assertThat(sql.queryForObject("SELECT review_status FROM result_record WHERE job_queue_id = ?", String.class, B_RUN))
            .isEqualTo("PENDING");
        assertThat(sql.queryForObject("SELECT count(*) FROM job_audit_logs WHERE job_queue_id = ? AND log_detail LIKE "
            + "'Result review: internal APPROVED by alice@acme.example%'", Long.class, A_RUN)).isEqualTo(1);
        assertThat(fx.leaks).isEmpty();
    }
}

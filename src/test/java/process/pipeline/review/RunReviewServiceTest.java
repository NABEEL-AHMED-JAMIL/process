package process.pipeline.review;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobDto;
import process.model.enums.JobStatus;
import process.model.enums.ReviewDecision;
import process.model.enums.ReviewParty;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.model.service.SourceJobService;
import process.model.service.impl.TransactionServiceImpl;
import process.pipeline.InMemoryStepStore;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.StepStore;
import process.security.TenantContext;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-237's service: the review a run shows, and a decision recorded by the party the caller is -- permission per
 * party and role, the run's workspace and owner, the state machine through the store, the audit line, and a rejection's
 * re-run through Run now.
 */
class RunReviewServiceTest {

    private static final long TENANT = 3237L;
    private static final long OTHER_TENANT = 3238L;
    private static final long JOB = 3240L;
    private static final long RUN = 9237L;
    private static final long RERUN = 9238L;
    private static final long DEFINITION = 4237L;
    private static final long ADMIN = 71L;
    private static final long USER = 72L;
    private static final Timestamp MADE = Timestamp.valueOf("2026-09-29 09:30:00");

    private final JobQueueRepository runs = mock(JobQueueRepository.class);
    private final SourceJobRepository jobs = mock(SourceJobRepository.class);
    private final InMemoryStepStore steps = new InMemoryStepStore();
    private final PipelineDefinitionStore definitions = mock(PipelineDefinitionStore.class);
    private final InMemoryRunReviewStore store = new InMemoryRunReviewStore();
    private final SourceJobService sourceJobs = mock(SourceJobService.class);
    private final TransactionServiceImpl transactions = mock(TransactionServiceImpl.class);
    private final RunReviewService service = new RunReviewService(this.runs, this.jobs, new RunReviews(this.steps, this.definitions,
        this.store), this.store, this.sourceJobs, this.transactions);
    private JobQueue run;
    private SourceJob job;

    @BeforeEach
    void setUp() {
        this.asAdmin();
        this.run = new JobQueue();
        this.run.setJobQueueId(RUN);
        this.run.setJobId(JOB);
        this.run.setTenantId(TENANT);
        this.run.setAttempt(2);
        this.run.setJobStatus(JobStatus.Completed);
        this.run.setDateCreated(MADE);
        this.job = new SourceJob();
        this.job.setJobId(JOB);
        this.job.setTenantId(TENANT);
        this.job.setJobStatus(Status.Active);
        this.job.setCreatedBy(USER);
        SourceTask task = new SourceTask();
        task.setPipelineId("F237001");
        this.job.setTaskDetail(task);
        when(this.runs.findById(RUN)).thenReturn(Optional.of(this.run));
        when(this.jobs.findById(JOB)).thenReturn(Optional.of(this.job));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void asAdmin() {
        TenantContext.set(TENANT, "TENANT_ADMIN", ADMIN, "ada@acme.example");
    }

    /** The run follows a step-engine definition whose settings.review requires these parties. */
    private void requires(String... parties) {
        PipelineDefinitionStore.Stored stored = new PipelineDefinitionStore.Stored();
        stored.id = DEFINITION;
        stored.tenantId = TENANT;
        stored.version = 3;
        stored.json = "{\"version\":1,\"steps\":[{\"key\":\"read\",\"task\":\"sample\"}],\"settings\":{\"review\":{\"required\":["
            + String.join(",", Arrays.stream(parties).map(p -> "\"" + p + "\"").toArray(String[]::new)) + "]}}}";
        this.steps.plan(RUN, 2, DEFINITION, Collections.singletonList(new StepStore.Planned(0, "read", "sample", "fail")));
        when(this.definitions.byId(DEFINITION)).thenReturn(Optional.of(stored));
    }

    private static RunReviewRequest request(String decision, String reason, Boolean rerun) {
        RunReviewRequest request = new RunReviewRequest();
        request.setJobQueueId(RUN);
        request.setDecision(decision);
        request.setComment("checked the totals");
        request.setReason(reason);
        request.setRerun(rerun);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseDto answer) {
        assertThat(answer.getStatus()).as(answer.getMessage()).isEqualTo("SUCCESS");
        return (Map<String, Object>) answer.getData();
    }

    private static void refused(ResponseDto answer, String message) {
        assertThat(answer.getStatus()).isEqualTo("ERROR");
        assertThat(answer.getMessage()).isEqualTo(message);
    }

    // ------------------------------------------------------------------------------------------------- the review read

    @Test
    void aRunOfAPipelineWithoutAReviewSettingNeedsNoReview() {
        Map<String, Object> review = data(this.service.review(RUN));
        assertThat(review).containsEntry("reviewStatus", "NOT_REQUIRED").containsEntry("required", Collections.emptyList())
            .containsEntry("decisions", Collections.emptyList()).containsEntry("jobQueueId", RUN);
        verify(this.definitions, atLeastOnce()).latestFor(TENANT, "F237001", MADE);
    }

    @Test
    void aRunThatRequiresReviewStartsPendingAndTheAdminMayDecide() {
        this.requires("internal");
        Map<String, Object> review = data(this.service.review(RUN));
        assertThat(review).containsEntry("reviewStatus", "PENDING").containsEntry("required", Collections.singletonList("internal"));
        assertThat((Map<String, Object>) review.get("you")).containsEntry("party", "internal").containsEntry("canDecide", true);
    }

    @Test
    void aWorkerRunFollowsItsPipelinesDefinitionAsItStoodWhenTheRunWasMade() {
        PipelineDefinitionStore.Stored legacy = new PipelineDefinitionStore.Stored();
        legacy.id = DEFINITION;
        legacy.json = "{\"version\":1,\"source\":{\"type\":\"task\"},\"steps\":[{\"key\":\"legacy\",\"task\":\"legacy\"}],"
            + "\"settings\":{\"review\":{\"required\":[\"internal\",\"customer\"]}}}";
        when(this.definitions.latestFor(TENANT, "F237001", MADE)).thenReturn(Optional.of(legacy));
        assertThat(data(this.service.review(RUN))).containsEntry("reviewStatus", "PENDING")
            .containsEntry("required", Arrays.asList("internal", "customer"));
    }

    @Test
    void aTenantUserReadsTheReviewOfTheirOwnJobsRunButMayNotDecide() {
        this.requires("internal");
        TenantContext.set(TENANT, "TENANT_USER", USER, "uma@acme.example");
        Map<String, Object> review = data(this.service.review(RUN));
        assertThat((Map<String, Object>) review.get("you")).containsEntry("canDecide", false)
            .containsEntry("refusal", "Only a tenant administrator can approve or reject a run's results.");
        TenantContext.set(TENANT, "TENANT_USER", 99L, "someone@acme.example");
        refused(this.service.review(RUN), "Run not found with jobQueueId.");
    }

    @Test
    void anotherWorkspacesRunIsNotFoundToReadOrDecide() {
        this.requires("internal");
        TenantContext.set(OTHER_TENANT, "TENANT_ADMIN", 81L, "bob@bravo.example");
        refused(this.service.review(RUN), "Run not found with jobQueueId.");
        refused(this.service.consoleDecide(request("APPROVED", null, null)), "Run not found with jobQueueId.");
        assertThat(this.store.decisions).isEmpty();
        verify(this.transactions, never()).saveJobAuditLogs(anyLong(), anyString());
    }

    // ---------------------------------------------------------------------------------------------------- decisions

    @Test
    void anAdminApprovesTheInternalReviewAndTheDecisionIsRecordedAuditedAndSettlesTheResults() throws Exception {
        this.requires("internal");
        Map<String, Object> review = data(this.service.consoleDecide(request("APPROVED", null, null)));

        assertThat(review).containsEntry("reviewStatus", "APPROVED");
        assertThat(this.store.decisions).hasSize(1);
        RunReviewStore.Decision decision = this.store.decisions.get(0);
        assertThat(decision.party).isEqualTo(ReviewParty.INTERNAL);
        assertThat(decision.decision).isEqualTo(ReviewDecision.APPROVED);
        assertThat(decision.attempt).isEqualTo(2);
        assertThat(decision.reviewerUserId).isEqualTo(ADMIN);
        assertThat(decision.reviewerName).isEqualTo("ada@acme.example");
        assertThat(decision.comment).isEqualTo("checked the totals");
        assertThat(this.store.statuses.get(RUN).decidedAt).isNotNull();
        assertThat(this.store.settledResults).containsEntry(RUN, RunReviewStatus.APPROVED);
        verify(this.transactions).saveJobAuditLogs(eq(RUN), contains("Result review: internal APPROVED by ada@acme.example (user 71)"));
        verify(this.transactions).saveJobAuditLogs(eq(RUN), contains("The run's results are now APPROVED."));
        verify(this.sourceJobs, never()).runSourceJob(any(SourceJobDto.class));
    }

    @Test
    void aTenantUserCannotDecide() {
        this.requires("internal");
        TenantContext.set(TENANT, "TENANT_USER", USER, "uma@acme.example");
        refused(this.service.consoleDecide(request("APPROVED", null, null)), "Only a tenant administrator can approve or reject a run's results.");
        assertThat(this.store.decisions).isEmpty();
    }

    @Test
    void theConsoleRefusesTheCustomersReview() {
        this.requires("internal", "customer");
        RunReviewRequest asCustomer = request("APPROVED", null, null);
        asCustomer.setParty("customer");
        refused(this.service.consoleDecide(asCustomer),
            "A customer review is recorded by the customer through the API; the console records the internal review only.");
        asCustomer.setParty("partner");
        refused(this.service.consoleDecide(asCustomer), "party is internal (the console records the internal review only).");
        assertThat(this.store.decisions).isEmpty();
    }

    @Test
    void aCustomerCannotRecordTheInternalReviewThroughAnyPath() {
        this.requires("internal", "customer");
        TenantContext.set(TENANT, ReviewParties.API_CLIENT_ROLE, null, "client-7");
        refused(this.service.consoleDecide(request("APPROVED", null, null)),
            "An internal review is recorded by the workspace's tenant administrators in the console; a customer cannot record one.");
        refused(this.service.decide(request("APPROVED", null, null), ReviewParty.INTERNAL),
            "An internal review is recorded by the workspace's tenant administrators in the console; a customer cannot record one.");
        assertThat(this.store.decisions).isEmpty();
    }

    @Test
    void aPlatformAdministratorIsNeitherParty() {
        this.requires("internal");
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "root@platform.example");
        refused(this.service.consoleDecide(request("APPROVED", null, null)),
            "Only the workspace's own people and its API clients review a run's results.");
    }

    @Test
    void bothRequiredStaysPendingUntilTheCustomerApprovesThroughTheCustomersOwnPath() {
        this.requires("internal", "customer");
        assertThat(data(this.service.consoleDecide(request("APPROVED", null, null)))).containsEntry("reviewStatus", "PENDING");
        assertThat(this.store.settledResults).isEmpty();

        TenantContext.set(TENANT, ReviewParties.API_CLIENT_ROLE, null, "client-7");
        assertThat(data(this.service.decide(request("APPROVED", null, null), ReviewParty.CUSTOMER))).containsEntry("reviewStatus", "APPROVED");
        assertThat(this.store.settledResults).containsEntry(RUN, RunReviewStatus.APPROVED);
    }

    @Test
    void theCustomerRejectsAfterTheInternalApproval() {
        this.requires("internal", "customer");
        data(this.service.consoleDecide(request("APPROVED", null, null)));
        TenantContext.set(TENANT, ReviewParties.API_CLIENT_ROLE, null, "client-7");
        assertThat(data(this.service.decide(request("REJECTED", "totals are off", null), ReviewParty.CUSTOMER)))
            .containsEntry("reviewStatus", "REJECTED");
        this.asAdmin();
        refused(this.service.consoleDecide(request("APPROVED", null, null)), "This run's results are already rejected.");
    }

    @Test
    void aPartyDecidesOnce() {
        this.requires("internal", "customer");
        data(this.service.consoleDecide(request("APPROVED", null, null)));
        refused(this.service.consoleDecide(request("REJECTED", "second thoughts", null)),
            "The internal review of this run is already recorded: approved.");
        assertThat(this.store.decisions).hasSize(1);
    }

    @Test
    void aRunThatNeedsNoReviewOrNotOursTakesNoDecision() {
        refused(this.service.consoleDecide(request("APPROVED", null, null)), "This run's pipeline asks for no review of its results.");
        this.requires("customer");
        refused(this.service.consoleDecide(request("APPROVED", null, null)), "This run's pipeline does not ask for an internal review.");
        assertThat(this.store.decisions).isEmpty();
    }

    @Test
    void onlyACompletedRunsResultsAreReviewed() {
        this.requires("internal");
        this.run.setJobStatus(JobStatus.Running);
        refused(this.service.consoleDecide(request("APPROVED", null, null)),
            "Only a completed run's results can be reviewed; this run is Running.");
    }

    @Test
    void aDecisionIsApprovedOrRejectedAndARejectionSaysWhy() {
        this.requires("internal");
        refused(this.service.consoleDecide(request("MAYBE", null, null)), "decision is APPROVED or REJECTED.");
        refused(this.service.consoleDecide(request("REJECTED", "  ", null)), "A rejection says why: reason is required.");
        refused(this.service.consoleDecide(request("APPROVED", null, true)), "rerun goes with a rejection only.");
        RunReviewRequest none = request("APPROVED", null, null);
        none.setJobQueueId(null);
        refused(this.service.consoleDecide(none), "JobQueueId missing.");
        assertThat(this.store.decisions).isEmpty();
    }

    // -------------------------------------------------------------------------------------------------------- re-run

    @Test
    void aRejectionWithRerunQueuesTheJobAgainAndLinksTheNewRun() throws Exception {
        this.requires("internal");
        when(this.sourceJobs.runSourceJob(any(SourceJobDto.class))).thenReturn(new ResponseDto("SUCCESS", "queued"));
        JobQueue again = new JobQueue();
        again.setJobQueueId(RERUN);
        again.setJobId(JOB);
        List<JobQueue> all = Arrays.asList(this.run, again);
        when(this.runs.findAllByJobId(JOB)).thenReturn(all);

        Map<String, Object> review = data(this.service.consoleDecide(request("REJECTED", "wrong input file", true)));

        assertThat(review).containsEntry("reviewStatus", "REJECTED").containsEntry("rerunJobQueueId", RERUN);
        assertThat((Map<String, Object>) review.get("rerun")).containsEntry("queued", true).containsEntry("jobQueueId", RERUN);
        assertThat(this.store.decisions.get(0).reason).isEqualTo("wrong input file");
        assertThat(this.store.settledResults).containsEntry(RUN, RunReviewStatus.REJECTED);
        verify(this.sourceJobs).runSourceJob(argThat((SourceJobDto dto) -> dto.getJobId() == JOB));
        verify(this.transactions).saveJobAuditLogs(eq(RUN), contains("Reason: wrong input file"));
        verify(this.transactions).saveJobAuditLogs(eq(RUN), contains("run " + RERUN + " runs the job again"));
        verify(this.transactions).saveJobAuditLogs(eq(RERUN), contains("run " + RUN + "'s results were rejected"));
    }

    @Test
    void aRejectionStandsWhenTheRerunIsRefused() throws Exception {
        this.requires("internal");
        when(this.sourceJobs.runSourceJob(any(SourceJobDto.class))).thenReturn(new ResponseDto("ERROR",
            "A job can't be run while its last run is still in flight ('Queue', 'Start', 'Running')."));

        ResponseDto answer = this.service.consoleDecide(request("REJECTED", "wrong input file", true));

        Map<String, Object> review = data(answer);
        assertThat(review).containsEntry("reviewStatus", "REJECTED").containsEntry("rerunJobQueueId", null);
        assertThat((Map<String, Object>) review.get("rerun")).containsEntry("queued", false)
            .containsEntry("message", "A job can't be run while its last run is still in flight ('Queue', 'Start', 'Running').");
        assertThat(answer.getMessage()).contains("was not run again");
        assertThat(this.store.decisions).hasSize(1);
    }
}

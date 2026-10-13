package process.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import process.model.dto.ResponseDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.pipeline.InMemoryStepStore;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.StepStore;
import process.pipeline.approval.RunApprovalStore;
import process.pipeline.review.BulkEligibility;
import process.pipeline.review.InMemoryRunReviewStore;
import process.pipeline.review.ReviewPolicy;
import process.pipeline.review.ReviewPolicyStore;
import process.pipeline.review.RunReviews;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bulk review: workflow-service asks Core, with the internal token, whether Task inbox subjects about pipeline runs may be
 * decided in bulk -- a run's review ("pipeline-run:N") and an Approval step's request ("pipeline-approval:N", by its run's
 * pipeline) -- answered by the pipeline's individual-review flag and the workspace's bulk approve, as that workspace.
 */
class InternalReviewEligibilityRestApiTest {

    private static final long TENANT = 2960L;
    private static final long OTHER = 2961L;

    private final JobQueueRepository runs = mock(JobQueueRepository.class);
    private final SourceJobRepository jobs = mock(SourceJobRepository.class);
    private final InMemoryStepStore steps = new InMemoryStepStore();
    private final PipelineDefinitionStore definitions = mock(PipelineDefinitionStore.class);
    private final RunApprovalStore approvals = mock(RunApprovalStore.class);
    private final Map<Long, ReviewPolicyStore.Saved> policies = new HashMap<>();
    private final ReviewPolicy policy = new ReviewPolicy(new ReviewPolicyStore() {
        @Override
        public Optional<Saved> find(long tenantId) {
            return Optional.ofNullable(InternalReviewEligibilityRestApiTest.this.policies.get(tenantId));
        }

        @Override
        public void save(long tenantId, boolean bulkApproveRuns, Long by) {
            Saved saved = new Saved();
            saved.tenantId = tenantId;
            saved.bulkApproveRuns = bulkApproveRuns;
            saved.updatedAt = Instant.now();
            InternalReviewEligibilityRestApiTest.this.policies.put(tenantId, saved);
        }
    });
    private final InternalReviewEligibilityRestApi api = new InternalReviewEligibilityRestApi(new BulkEligibility(this.runs, this.jobs,
        new RunReviews(this.steps, this.definitions, new InMemoryRunReviewStore()), this.approvals, this.policy,
        mock(PlatformTransactionManager.class)), "shared-token");

    @BeforeEach
    void setUp() {
        this.run(8301L, 3240L, TENANT, "Readmission stats", "{\"required\":[\"internal\"]}");
        this.run(8302L, 3241L, TENANT, "Discharge letters", "{\"required\":[\"internal\"],\"individual\":true}");
        this.run(8401L, 3243L, OTHER, "Bravo's job", "{\"required\":[\"internal\"]}");
        this.approval(55L, TENANT, 8302L);
        this.approval(56L, TENANT, 8301L);
        this.approval(57L, OTHER, 8401L);
    }

    private void run(long id, long jobId, long tenant, String name, String review) {
        JobQueue run = new JobQueue();
        run.setJobQueueId(id);
        run.setJobId(jobId);
        run.setTenantId(tenant);
        run.setAttempt(1);
        run.setJobStatus(JobStatus.Completed);
        when(this.runs.findById(id)).thenReturn(Optional.of(run));
        SourceJob job = new SourceJob();
        job.setJobId(jobId);
        job.setTenantId(tenant);
        job.setJobName(name);
        job.setJobStatus(Status.Active);
        job.setTaskDetail(new SourceTask());
        when(this.jobs.findById(jobId)).thenReturn(Optional.of(job));
        PipelineDefinitionStore.Stored stored = new PipelineDefinitionStore.Stored();
        stored.id = id + 1000;
        stored.tenantId = tenant;
        stored.version = 1;
        stored.json = "{\"version\":1,\"steps\":[{\"key\":\"read\",\"task\":\"sample\"}],\"settings\":{\"review\":" + review + "}}";
        when(this.definitions.byId(id + 1000)).thenReturn(Optional.of(stored));
        this.steps.plan(id, 1, id + 1000, Collections.singletonList(new StepStore.Planned(0, "read", "sample", "fail")));
    }

    private void approval(long id, long tenant, long run) {
        RunApprovalStore.Approval approval = new RunApprovalStore.Approval();
        approval.runApprovalId = id;
        approval.tenantId = tenant;
        approval.jobQueueId = run;
        when(this.approvals.find(id)).thenReturn(Optional.of(approval));
    }

    private static InternalReviewEligibilityRestApi.EligibilityRequest request(String action, String... subjects) {
        InternalReviewEligibilityRestApi.EligibilityRequest request = new InternalReviewEligibilityRestApi.EligibilityRequest();
        request.setTenantId(TENANT);
        request.setAction(action);
        request.setSubjects(Arrays.asList(subjects));
        return request;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> answers(InternalReviewEligibilityRestApi.EligibilityRequest request) {
        ResponseEntity<?> answer = this.api.bulkEligibility("shared-token", request);
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (Map<String, Map<String, Object>>) ((ResponseDto) answer.getBody()).getData();
    }

    @Test
    void withoutTheInternalTokenNothingIsAnswered() {
        assertThat(this.api.bulkEligibility(null, request("approve", "pipeline-run:8301")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(this.api.bulkEligibility("guess", request("approve", "pipeline-run:8301")).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(new InternalReviewEligibilityRestApi(mock(BulkEligibility.class), "").bulkEligibility("", request("approve"))
            .getStatusCode()).as("no token configured: nothing is admitted").isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(this.runs, never()).findById(anyLong());
    }

    @Test
    void aRequestWithoutWorkspaceActionOrSubjectsIsA400() {
        InternalReviewEligibilityRestApi.EligibilityRequest noAction = request("maybe", "pipeline-run:8301");
        assertThat(this.api.bulkEligibility("shared-token", noAction).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        InternalReviewEligibilityRestApi.EligibilityRequest noTenant = request("approve", "pipeline-run:8301");
        noTenant.setTenantId(null);
        assertThat(this.api.bulkEligibility("shared-token", noTenant).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void eachSubjectIsAnsweredByItsPipelinesFlagAsTheWorkspace() {
        Map<String, Map<String, Object>> answers = this.answers(request("reject", "pipeline-run:8301", "pipeline-run:8302",
            "pipeline-approval:55", "pipeline-approval:56", "pipeline-run:8401", "pipeline-approval:57", "pipeline-run:404",
            "form:12", "pipeline-run:abc"));

        assertThat(answers.get("pipeline-run:8301")).containsEntry("allowed", true).containsEntry("group", "Readmission stats");
        assertThat(answers.get("pipeline-approval:56")).containsEntry("allowed", true).containsEntry("group", "Readmission stats");
        assertThat(answers.get("pipeline-run:8302")).containsEntry("allowed", false).containsEntry("reason", "individual-review");
        assertThat(answers.get("pipeline-approval:55")).as("its run's pipeline decides").containsEntry("reason", "individual-review");
        for (String unknown : new String[] {"pipeline-run:8401", "pipeline-approval:57", "pipeline-run:404", "form:12", "pipeline-run:abc"}) {
            assertThat(answers.get(unknown)).as(unknown).containsEntry("allowed", false).containsEntry("reason", "not-found")
                .doesNotContainKey("group");
        }
        assertThat(String.valueOf(answers)).doesNotContain("Bravo");
    }

    @Test
    void bulkApproveOffRefusesApproveOnlyAndIndividualReviewStillComesFirst() {
        ReviewPolicyStore.Saved off = new ReviewPolicyStore.Saved();
        off.tenantId = TENANT;
        off.bulkApproveRuns = false;
        this.policies.put(TENANT, off);
        Map<String, Map<String, Object>> approve = this.answers(request("approve", "pipeline-run:8301", "pipeline-run:8302"));
        assertThat(approve.get("pipeline-run:8301")).containsEntry("allowed", false).containsEntry("reason", "bulk-off");
        assertThat(approve.get("pipeline-run:8302")).containsEntry("reason", "individual-review");
        assertThat(this.answers(request("reject", "pipeline-run:8301")).get("pipeline-run:8301")).containsEntry("allowed", true);
    }
}

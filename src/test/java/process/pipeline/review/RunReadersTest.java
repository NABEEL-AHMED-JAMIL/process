package process.pipeline.review;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import process.identity.IdentityPort;
import process.model.dto.ResponseDto;
import process.model.enums.JobStatus;
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
import process.pipeline.approval.RunApprovalStore;
import process.security.TenantContext;

import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UX review 2026-10-12 (R2): a run's reviewers read it -- the tenant users its pipeline names for the internal review, by
 * group (access profile) or role -- besides its owner. Nobody else does: another profile (a viewer), another workspace, a
 * run that needs no internal review, a caller Identity cannot place. And reading is all: deciding stays as it was.
 */
class RunReadersTest {

    private static final long TENANT = 2960L;
    private static final long OTHER_TENANT = 2961L;
    private static final long JOB = 2907L;
    private static final long RUN = 8214L;
    private static final long DEFINITION = 4300L;
    private static final long OWNER = 4640L;
    private static final long REVIEWER = 4641L;
    private static final long VIEWER = 4642L;
    private static final long REVIEWER_PROFILE = 1063L;
    private static final long VIEWER_PROFILE = 1064L;

    private final JobQueueRepository runs = mock(JobQueueRepository.class);
    private final SourceJobRepository jobs = mock(SourceJobRepository.class);
    private final InMemoryStepStore steps = new InMemoryStepStore();
    private final PipelineDefinitionStore definitions = mock(PipelineDefinitionStore.class);
    private final InMemoryRunReviewStore store = new InMemoryRunReviewStore();
    private final IdentityPort identity = mock(IdentityPort.class);
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final RunReviews reviews = new RunReviews(this.steps, this.definitions, this.store);
    private final RunApprovalStore approvals = mock(RunApprovalStore.class);
    private final RunReaders readers = new RunReaders(this.runs, this.jobs, this.reviews, this.identity, this.approvals, this.now::get);
    private JobQueue run;
    private SourceJob job;

    @BeforeEach
    void setUp() {
        this.run = new JobQueue();
        this.run.setJobQueueId(RUN);
        this.run.setJobId(JOB);
        this.run.setTenantId(TENANT);
        this.run.setAttempt(1);
        this.run.setJobStatus(JobStatus.Completed);
        this.run.setDateCreated(Timestamp.valueOf("2026-10-12 08:00:00"));
        this.job = new SourceJob();
        this.job.setJobId(JOB);
        this.job.setTenantId(TENANT);
        this.job.setJobStatus(Status.Active);
        this.job.setCreatedBy(OWNER);
        SourceTask task = new SourceTask();
        task.setPipelineId("PATIENT_360");
        this.job.setTaskDetail(task);
        when(this.runs.findById(RUN)).thenReturn(Optional.of(this.run));
        when(this.jobs.findById(JOB)).thenReturn(Optional.of(this.job));
        this.person(REVIEWER, TENANT, REVIEWER_PROFILE);
        this.person(VIEWER, TENANT, VIEWER_PROFILE);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void person(long id, long tenant, Long profile) {
        when(this.identity.person(id)).thenReturn(Optional.of(new IdentityPort.Person(id, tenant, "u" + id, "U " + id, "TENANT_USER",
            "Active", null, null, profile)));
    }

    private static void as(long tenant, String role, long user) {
        TenantContext.set(tenant, role, user, "u" + user);
    }

    /** The run follows a definition whose settings.review is this JSON. */
    private void review(String json) {
        PipelineDefinitionStore.Stored stored = new PipelineDefinitionStore.Stored();
        stored.id = DEFINITION;
        stored.tenantId = TENANT;
        stored.version = 1;
        stored.json = "{\"version\":1,\"steps\":[{\"key\":\"read\",\"task\":\"sample\"}],\"settings\":{\"review\":" + json + "}}";
        this.steps.plan(RUN, 1, DEFINITION, Collections.singletonList(new StepStore.Planned(0, "read", "sample", "fail")));
        when(this.definitions.byId(DEFINITION)).thenReturn(Optional.of(stored));
    }

    private void reviewedByGroup(long profile) {
        this.review("{\"required\":[\"internal\"],\"reviewers\":{\"kind\":\"group\",\"value\":\"" + profile + "\"}}");
    }

    @Test
    void theOwnerAndAnAdministratorReadTheRunAsBefore() {
        this.reviewedByGroup(REVIEWER_PROFILE);
        as(TENANT, "TENANT_USER", OWNER);
        assertThat(this.readers.readable(RUN)).isPresent();
        assertThat(this.readers.readsAsReviewerOnly(RUN)).isFalse();
        as(TENANT, "TENANT_ADMIN", 4999L);
        assertThat(this.readers.readable(RUN)).isPresent();
    }

    @Test
    void aMemberOfTheReviewingGroupReadsTheRunAsItsReviewer() {
        this.reviewedByGroup(REVIEWER_PROFILE);
        as(TENANT, "TENANT_USER", REVIEWER);
        assertThat(this.readers.readable(RUN)).hasValueSatisfying(read -> assertThat(read.run.getJobQueueId()).isEqualTo(RUN));
        assertThat(this.readers.readsAsReviewerOnly(RUN)).isTrue();
    }

    @Test
    void aViewerOnAnotherProfileReadsNothing() {
        this.reviewedByGroup(REVIEWER_PROFILE);
        as(TENANT, "TENANT_USER", VIEWER);
        assertThat(this.readers.readable(RUN)).isEmpty();
    }

    @Test
    void theSameProfileIdInAnotherWorkspaceReadsNothing() {
        this.reviewedByGroup(REVIEWER_PROFILE);
        this.person(5001L, OTHER_TENANT, REVIEWER_PROFILE);
        as(OTHER_TENANT, "TENANT_USER", 5001L);
        assertThat(this.readers.readable(RUN)).isEmpty();
        // Nor a caller whose token says this workspace while Identity places them in another.
        this.person(5002L, OTHER_TENANT, REVIEWER_PROFILE);
        as(TENANT, "TENANT_USER", 5002L);
        assertThat(this.readers.readable(RUN)).isEmpty();
    }

    @Test
    void aRunThatNeedsNoInternalReviewHasNoReviewersToReadIt() {
        this.review("{\"required\":[\"customer\"],\"reviewers\":{\"kind\":\"group\",\"value\":\"" + REVIEWER_PROFILE + "\"}}");
        as(TENANT, "TENANT_USER", REVIEWER);
        assertThat(this.readers.readable(RUN)).isEmpty();
        this.review("{\"required\":[]}");
        assertThat(this.readers.readable(RUN)).isEmpty();
    }

    @Test
    void theDefaultReviewersAreTheAdministratorsSoNoTenantUserReadsByIt() {
        this.review("{\"required\":[\"internal\"]}");
        as(TENANT, "TENANT_USER", REVIEWER);
        assertThat(this.readers.readable(RUN)).isEmpty();
    }

    @Test
    void reviewersNamedByRoleAreEveryTenantUserOfTheWorkspace() {
        this.review("{\"required\":[\"internal\"],\"reviewers\":{\"kind\":\"role\",\"value\":\"TENANT_USER\"}}");
        as(TENANT, "TENANT_USER", VIEWER);
        assertThat(this.readers.readable(RUN)).isPresent();
        as(OTHER_TENANT, "TENANT_USER", VIEWER);
        assertThat(this.readers.readable(RUN)).isEmpty();
    }

    @Test
    void aDeletedJobOrAnApiClientOrNoCallerReadsNothing() {
        this.reviewedByGroup(REVIEWER_PROFILE);
        TenantContext.set(TENANT, "API_CLIENT", null, "client");
        assertThat(this.readers.readable(RUN)).isEmpty();
        TenantContext.clear();
        assertThat(this.readers.readable(RUN)).isEmpty();
        as(TENANT, "TENANT_USER", REVIEWER);
        this.job.setJobStatus(Status.Delete);
        assertThat(this.readers.readable(RUN)).isEmpty();
    }

    @Test
    void whenIdentityCannotSayTheReviewerIsReadAsNobodyAndTheGroupIsKeptAMinute() {
        this.reviewedByGroup(REVIEWER_PROFILE);
        as(TENANT, "TENANT_USER", 6001L);
        when(this.identity.person(6001L)).thenThrow(new IdentityPort.Unavailable("down", null));
        assertThat(this.readers.readable(RUN)).isEmpty();

        as(TENANT, "TENANT_USER", REVIEWER);
        assertThat(this.readers.readable(RUN)).isPresent();
        assertThat(this.readers.readable(RUN)).isPresent();
        verify(this.identity, times(1)).person(REVIEWER);
        this.now.addAndGet(RunReaders.GROUP_TTL_MILLIS + 1);
        assertThat(this.readers.readable(RUN)).isPresent();
        verify(this.identity, times(2)).person(REVIEWER);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theReviewerReadsTheReviewAndFindsTheRunWaitingButCannotDecideHere() {
        this.reviewedByGroup(REVIEWER_PROFILE);
        RunReviewService service = new RunReviewService(this.runs, this.jobs, this.reviews, this.store, mock(SourceJobService.class),
            mock(TransactionServiceImpl.class));
        service.setReaders(this.readers);
        this.store.undecided.add(RUN);
        as(TENANT, "TENANT_USER", REVIEWER);

        ResponseDto review = service.review(RUN);
        assertThat(review.getStatus()).as(review.getMessage()).isEqualTo("SUCCESS");
        Map<String, Object> you = (Map<String, Object>) ((Map<String, Object>) review.getData()).get("you");
        assertThat(you).containsEntry("canDecide", false);
        assertThat((String) you.get("refusal")).contains("Task inbox");

        ResponseDto waiting = service.waiting(null);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ((Map<String, Object>) waiting.getData()).get("runs");
        assertThat(rows).extracting(r -> r.get("jobQueueId")).containsExactly(RUN);

        RunReviewRequest decide = new RunReviewRequest();
        decide.setJobQueueId(RUN);
        decide.setDecision("APPROVED");
        assertThat(service.consoleDecide(decide).getStatus()).isEqualTo("ERROR");

        as(TENANT, "TENANT_USER", VIEWER);
        assertThat(service.review(RUN).getStatus()).isEqualTo("ERROR");
        assertThat((List<?>) ((Map<String, Object>) service.waiting(null).getData()).get("runs")).isEmpty();
    }

    /** One Approval step of the run, waiting on these reviewers. */
    private void approvalBy(String kind, String value) {
        RunApprovalStore.Approval approval = new RunApprovalStore.Approval();
        approval.runApprovalId = 1006L;
        approval.tenantId = TENANT;
        approval.jobQueueId = RUN;
        approval.status = RunApprovalStore.PENDING;
        approval.reviewersKind = kind;
        approval.reviewersValue = value;
        when(this.approvals.ofRun(RUN)).thenReturn(Collections.singletonList(approval));
    }

    @Test
    void aMemberOfTheGroupAnApprovalStepWentToReadsTheRunWhateverItsReview() {
        this.review("{\"required\":[]}");
        this.approvalBy("group", String.valueOf(REVIEWER_PROFILE));
        as(TENANT, "TENANT_USER", REVIEWER);
        assertThat(this.readers.readable(RUN)).isPresent();
        assertThat(this.readers.readsAsReviewerOnly(RUN)).isTrue();
        // Another profile, and the same profile id in another workspace, read nothing.
        as(TENANT, "TENANT_USER", VIEWER);
        assertThat(this.readers.readable(RUN)).isEmpty();
        this.person(5001L, OTHER_TENANT, REVIEWER_PROFILE);
        as(OTHER_TENANT, "TENANT_USER", 5001L);
        assertThat(this.readers.readable(RUN)).isEmpty();
    }

    @Test
    void anApprovalByTheAdministratorsOpensTheRunToNoTenantUserAndARoleApprovalToThatRole() {
        this.review("{\"required\":[]}");
        this.approvalBy("role", "TENANT_ADMIN");
        as(TENANT, "TENANT_USER", REVIEWER);
        assertThat(this.readers.readable(RUN)).isEmpty();
        this.approvalBy("role", "TENANT_USER");
        assertThat(this.readers.readable(RUN)).isPresent();
    }

    @Test
    void aRunWithNoApprovalAndNoReviewHasNoReviewersToReadIt() {
        this.review("{\"required\":[]}");
        when(this.approvals.ofRun(RUN)).thenReturn(Collections.emptyList());
        as(TENANT, "TENANT_USER", REVIEWER);
        assertThat(this.readers.readable(RUN)).isEmpty();
    }

    @Test
    void identityIsNotAskedForAnOwner() {
        this.reviewedByGroup(REVIEWER_PROFILE);
        as(TENANT, "TENANT_USER", OWNER);
        this.readers.readable(RUN);
        verify(this.identity, times(0)).person(anyLong());
    }
}

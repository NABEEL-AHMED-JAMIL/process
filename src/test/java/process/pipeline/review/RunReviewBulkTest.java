package process.pipeline.review;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import process.customer.CustomerEventJournal;
import process.model.dto.ResponseDto;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bulk review (2026-10-12), Core's half: POST sourceJob.json/review/bulkDecide decides each run on its own by a single
 * decision's rules and path -- every skip reason, partial results, the audit line and bulk_id per run, the same events,
 * idempotency by bulkId, dry run, the caps, the comment rules and the review policy -- and the review read and the
 * waiting list say whether a run's pipeline requires individual review.
 */
class RunReviewBulkTest {

    private static final long TENANT = 3237L;
    private static final long OTHER_TENANT = 3238L;
    private static final long ADMIN = 71L;
    private static final long USER = 72L;
    private static final long JOB = 3240L;
    private static final long INDIVIDUAL_JOB = 3241L;
    private static final long NO_REVIEW_JOB = 3242L;
    private static final long THEIR_JOB = 3243L;
    private static final long DEF_INTERNAL = 4237L;
    private static final long DEF_INDIVIDUAL = 4238L;
    private static final Timestamp MADE = Timestamp.valueOf("2026-10-12 09:30:00");
    private static final String BULK = "6f1c2b9e-0d4a-4b7e-9a51-3c2f1e0b7a11";

    private final JobQueueRepository runs = mock(JobQueueRepository.class);
    private final SourceJobRepository jobs = mock(SourceJobRepository.class);
    private final InMemoryStepStore steps = new InMemoryStepStore();
    private final PipelineDefinitionStore definitions = mock(PipelineDefinitionStore.class);
    private final InMemoryRunReviewStore store = new InMemoryRunReviewStore();
    private final TransactionServiceImpl transactions = mock(TransactionServiceImpl.class);
    private final CustomerEventJournal events = mock(CustomerEventJournal.class);
    private final RunReviews reviews = new RunReviews(this.steps, this.definitions, this.store);
    private final RunReviewService service = new RunReviewService(this.runs, this.jobs, this.reviews, this.store,
        mock(SourceJobService.class), this.transactions);
    private final MapPolicyStore policies = new MapPolicyStore();
    private final ReviewPolicy policy = new ReviewPolicy(this.policies);
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final RunReviewBulk bulk = new RunReviewBulk(this.service, this.policy, mock(PlatformTransactionManager.class), this.now::get);

    /** review_policy in memory. */
    static final class MapPolicyStore implements ReviewPolicyStore {
        final Map<Long, Saved> rows = new HashMap<>();

        @Override
        public Optional<Saved> find(long tenantId) {
            return Optional.ofNullable(this.rows.get(tenantId));
        }

        @Override
        public void save(long tenantId, boolean bulkApproveRuns, Long by) {
            Saved saved = new Saved();
            saved.tenantId = tenantId;
            saved.bulkApproveRuns = bulkApproveRuns;
            saved.updatedBy = by;
            saved.updatedAt = Instant.parse("2026-10-12T15:00:00Z");
            this.rows.put(tenantId, saved);
        }
    }

    @BeforeEach
    void setUp() {
        this.asAdmin();
        this.service.setEventJournal(this.events);
        this.job(JOB, TENANT, "Readmission stats");
        this.job(INDIVIDUAL_JOB, TENANT, "Discharge letters");
        this.job(NO_REVIEW_JOB, TENANT, "Nightly copy");
        this.job(THEIR_JOB, OTHER_TENANT, "Bravo's secret job");
        this.definition(DEF_INTERNAL, "{\"required\":[\"internal\"]}");
        this.definition(DEF_INDIVIDUAL, "{\"required\":[\"internal\"],\"individual\":true}");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void asAdmin() {
        TenantContext.set(TENANT, "TENANT_ADMIN", ADMIN, "ada@acme.example");
    }

    private void job(long id, long tenant, String name) {
        SourceJob job = new SourceJob();
        job.setJobId(id);
        job.setTenantId(tenant);
        job.setJobName(name);
        job.setJobStatus(Status.Active);
        job.setCreatedBy(USER);
        SourceTask task = new SourceTask();
        task.setPipelineId("F" + id);
        job.setTaskDetail(task);
        when(this.jobs.findById(id)).thenReturn(Optional.of(job));
    }

    private void definition(long id, String review) {
        PipelineDefinitionStore.Stored stored = new PipelineDefinitionStore.Stored();
        stored.id = id;
        stored.tenantId = TENANT;
        stored.version = 1;
        stored.json = "{\"version\":1,\"steps\":[{\"key\":\"read\",\"task\":\"sample\"}],\"settings\":{\"review\":" + review + "}}";
        when(this.definitions.byId(id)).thenReturn(Optional.of(stored));
    }

    /** A run of {@code job}, completed unless said, following {@code definition} (null: none -- no review). */
    private long run(long id, long job, long tenant, Long definition, JobStatus status) {
        JobQueue run = new JobQueue();
        run.setJobQueueId(id);
        run.setJobId(job);
        run.setTenantId(tenant);
        run.setAttempt(1);
        run.setJobStatus(status);
        run.setDateCreated(MADE);
        when(this.runs.findById(id)).thenReturn(Optional.of(run));
        if (definition != null) {
            this.steps.plan(id, 1, definition, Collections.singletonList(new StepStore.Planned(0, "read", "sample", "fail")));
        }
        return id;
    }

    private long reviewed(long id) {
        return this.run(id, JOB, TENANT, DEF_INTERNAL, JobStatus.Completed);
    }

    private static RunReviewBulkRequest request(String decision, String comment, String bulkId, Boolean dryRun, Long... ids) {
        RunReviewBulkRequest request = new RunReviewBulkRequest();
        request.setJobQueueIds(new ArrayList<>(Arrays.asList(ids)));
        request.setDecision(decision);
        request.setComment(comment);
        request.setBulkId(bulkId);
        request.setDryRun(dryRun);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseDto answer) {
        assertThat(answer.getStatus()).as(answer.getMessage()).isEqualTo("SUCCESS");
        return (Map<String, Object>) answer.getData();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(ResponseDto answer, String which) {
        return (List<Map<String, Object>>) data(answer).get(which);
    }

    private static Map<Object, Object> reasons(ResponseDto answer) {
        return list(answer, "skipped").stream().collect(Collectors.toMap(item -> item.get("id"), item -> item.get("reason")));
    }

    private static void refusedWith(Runnable call, HttpStatus status, String message) {
        assertThatThrownBy(call::run).isInstanceOf(RunReviewBulk.Refused.class).hasMessage(message)
            .satisfies(thrown -> assertThat(((RunReviewBulk.Refused) thrown).getStatus()).isEqualTo(status));
    }

    // ------------------------------------------------------------------------------------------------ each run decided

    @Test
    void eachRunIsApprovedOnItsOwnWithItsOwnAuditLineBulkIdAndEvent() {
        this.reviewed(8301L);
        this.reviewed(8302L);

        ResponseDto answer = this.bulk.decide(request("APPROVED", "checked the totals", BULK, null, 8301L, 8302L));

        assertThat(answer.getMessage()).isEqualTo("2 approved, 0 skipped.");
        assertThat(data(answer)).containsEntry("bulkId", BULK).containsEntry("action", "approve").containsEntry("dryRun", false)
            .containsEntry("requested", 2);
        List<Map<String, Object>> done = list(answer, "done");
        assertThat(done).extracting(item -> item.get("id")).containsExactly(8301L, 8302L);
        assertThat(done.get(0)).containsEntry("label", "Run #8301 · Readmission stats").containsEntry("group", "Readmission stats")
            .containsEntry("link", "/pipelines/schedules/" + JOB + "/runs/8301/logs").containsEntry("message", "Approved.");
        assertThat(this.store.decisions).hasSize(2).allSatisfy(d -> {
            assertThat(d.bulkId).isEqualTo(BULK);
            assertThat(d.party).isEqualTo(ReviewParty.INTERNAL);
            assertThat(d.decision).isEqualTo(ReviewDecision.APPROVED);
            assertThat(d.reviewerUserId).isEqualTo(ADMIN);
            assertThat(d.comment).isEqualTo("checked the totals");
        });
        assertThat(this.store.statuses.get(8301L).status).isEqualTo(RunReviewStatus.APPROVED);
        for (long run : new long[] {8301L, 8302L}) {
            verify(this.transactions).saveJobAuditLogs(run, "Result review: internal APPROVED by ada@acme.example (user 71) (attempt 1). "
                + "Comment: checked the totals. The run's results are now APPROVED. Bulk decision " + BULK + ".");
            // The same event as a single decision: run.review.decided, then run-review.decided closes the Task inbox task.
            verify(this.events).reviewDecided(TENANT, run);
        }
    }

    @Test
    void aBulkRejectionsCommentIsEachRunsReasonAndNoRunIsRunAgain() {
        this.reviewed(8301L);

        ResponseDto answer = this.bulk.decide(request("REJECTED", "  totals are off  ", BULK, false, 8301L));

        assertThat(answer.getMessage()).isEqualTo("1 rejected, 0 skipped.");
        assertThat(data(answer)).containsEntry("action", "reject");
        RunReviewStore.Decision decision = this.store.decisions.get(0);
        assertThat(decision.decision).isEqualTo(ReviewDecision.REJECTED);
        assertThat(decision.reason).isEqualTo("totals are off");
        assertThat(decision.comment).isNull();
        assertThat(this.store.statuses.get(8301L).rerunJobQueueId).isNull();
        verify(this.transactions).saveJobAuditLogs(eq(8301L), contains("Reason: totals are off."));
    }

    // ------------------------------------------------------------------------------------------------- skip reasons

    @Test
    void everyRunTheSingleRulesRefuseIsSkippedWithItsReason() {
        this.reviewed(8301L);
        this.run(8302L, JOB, TENANT, DEF_INTERNAL, JobStatus.Failed);
        this.run(8303L, NO_REVIEW_JOB, TENANT, null, JobStatus.Completed);
        this.run(8304L, INDIVIDUAL_JOB, TENANT, DEF_INDIVIDUAL, JobStatus.Completed);
        this.reviewed(8305L);
        data(this.bulk.decide(request("APPROVED", null, "earlier-0001", null, 8305L)));

        ResponseDto answer = this.bulk.decide(request("APPROVED", null, BULK, null, 8301L, 8302L, 8303L, 8304L, 8305L, 9999L));

        assertThat(answer.getMessage()).isEqualTo("1 approved, 5 skipped.");
        assertThat(reasons(answer)).containsEntry(8302L, "not-completed").containsEntry(8303L, "no-review")
            .containsEntry(8304L, "individual-review").containsEntry(8305L, "already-decided").containsEntry(9999L, "not-found");
        Map<String, Object> notFound = list(answer, "skipped").stream().filter(item -> item.get("id").equals(9999L)).findFirst().get();
        assertThat(notFound).containsEntry("message", "Not found.").containsEntry("label", null).containsEntry("group", null)
            .containsEntry("link", null);
        Map<String, Object> decided = list(answer, "skipped").stream().filter(item -> item.get("id").equals(8305L)).findFirst().get();
        assertThat(decided).containsEntry("message", "This run's results are already approved.")
            .containsEntry("label", "Run #8305 · Readmission stats");
        assertThat(this.store.decisions).extracting(d -> d.jobQueueId).containsExactlyInAnyOrder(8301L, 8305L);
    }

    @Test
    void anotherWorkspacesRunIsNotFoundAndNothingOfItIsNamed() {
        this.run(8401L, THEIR_JOB, OTHER_TENANT, DEF_INTERNAL, JobStatus.Completed);

        ResponseDto answer = this.bulk.decide(request("REJECTED", "probe says no", BULK, null, 8401L));

        assertThat(answer.getStatus()).isEqualTo("SUCCESS");
        assertThat(reasons(answer)).containsExactly(entry(8401L, "not-found"));
        assertThat(String.valueOf(answer.getData())).doesNotContain("Bravo").doesNotContain(String.valueOf(THEIR_JOB));
        assertThat(this.store.decisions).isEmpty();
        verify(this.transactions, never()).saveJobAuditLogs(anyLong(), anyString());
    }

    @Test
    void aTenantUserDecidesNothingInBulk() {
        this.reviewed(8301L);
        TenantContext.set(TENANT, "TENANT_USER", USER, "uma@acme.example");

        ResponseDto answer = this.bulk.decide(request("APPROVED", null, BULK, null, 8301L));

        assertThat(reasons(answer)).containsEntry(8301L, "not-yours");
        assertThat(this.store.decisions).isEmpty();
    }

    @Test
    void withBulkApproveOffApprovalsAreSkippedButRejectionsGoOn() {
        this.reviewed(8301L);
        this.reviewed(8302L);
        data(this.policy.save(false));

        ResponseDto approve = this.bulk.decide(request("APPROVED", null, BULK, null, 8301L));
        assertThat(reasons(approve)).containsEntry(8301L, "bulk-off");
        assertThat(list(approve, "skipped").get(0).get("message")).isEqualTo(
            "This workspace does not allow bulk approve of pipeline runs: approve it on its own.");

        ResponseDto reject = this.bulk.decide(request("REJECTED", "wrong cohort", "reject-0001", null, 8302L));
        assertThat(list(reject, "done")).extracting(item -> item.get("id")).containsExactly(8302L);
        assertThat(this.store.decisions).extracting(d -> d.jobQueueId).containsExactly(8302L);
    }

    @Test
    void aPipelineThatRequiresIndividualReviewIsSkippedForApproveAndReject() {
        this.run(8304L, INDIVIDUAL_JOB, TENANT, DEF_INDIVIDUAL, JobStatus.Completed);

        assertThat(reasons(this.bulk.decide(request("APPROVED", null, BULK, null, 8304L)))).containsEntry(8304L, "individual-review");
        assertThat(reasons(this.bulk.decide(request("REJECTED", "no", BULK, null, 8304L)))).containsEntry(8304L, "individual-review");
        assertThat(this.store.decisions).isEmpty();
        // ... and decided one by one as before.
        RunReviewRequest single = new RunReviewRequest();
        single.setJobQueueId(8304L);
        single.setDecision("APPROVED");
        assertThat(this.service.consoleDecide(single).getStatus()).isEqualTo("SUCCESS");
    }

    // ------------------------------------------------------------------------------------------------ partial results

    @Test
    void oneRunFailingNeitherStopsNorTakesBackTheOthers() {
        this.reviewed(8301L);
        this.reviewed(8302L);
        this.reviewed(8303L);
        doThrow(new IllegalStateException("audit store down")).when(this.transactions).saveJobAuditLogs(eq(8302L), anyString());

        ResponseDto answer = this.bulk.decide(request("APPROVED", null, BULK, null, 8301L, 8302L, 8303L));

        assertThat(answer.getStatus()).isEqualTo("SUCCESS");
        assertThat(answer.getMessage()).isEqualTo("2 approved, 0 skipped, 1 failed.");
        assertThat(list(answer, "done")).extracting(item -> item.get("id")).containsExactly(8301L, 8303L);
        assertThat(list(answer, "failed")).singleElement().satisfies(item -> assertThat(item)
            .containsEntry("id", 8302L).containsEntry("label", "Run #8302 · Readmission stats")
            .containsEntry("message", "Could not be recorded: an internal error (logged)."));
        verify(this.events).reviewDecided(TENANT, 8301L);
        verify(this.events).reviewDecided(TENANT, 8303L);
    }

    // --------------------------------------------------------------------------------------------- idempotent, dry run

    @Test
    void theSameBulkIdAgainRecordsNothingTwice() {
        this.reviewed(8301L);
        this.reviewed(8302L);
        data(this.bulk.decide(request("APPROVED", null, BULK, null, 8301L)));

        ResponseDto again = this.bulk.decide(request("APPROVED", null, BULK, null, 8301L, 8302L));

        assertThat(list(again, "done")).extracting(item -> item.get("message"))
            .containsExactly("Already recorded in this bulk decision.", "Approved.");
        assertThat(this.store.decisions).hasSize(2);
        verify(this.transactions, times(1)).saveJobAuditLogs(eq(8301L), anyString());
        verify(this.events, times(1)).reviewDecided(TENANT, 8301L);
    }

    @Test
    void aDryRunChecksEverythingAndWritesNothing() {
        this.reviewed(8301L);
        this.run(8302L, JOB, TENANT, DEF_INTERNAL, JobStatus.Running);

        ResponseDto answer = this.bulk.decide(request("APPROVED", null, BULK, true, 8301L, 8302L));

        assertThat(answer.getMessage()).isEqualTo("1 would be approved, 1 skipped.");
        assertThat(data(answer)).containsEntry("dryRun", true);
        assertThat(list(answer, "done").get(0)).containsEntry("message", "Would be approved.");
        assertThat(reasons(answer)).containsEntry(8302L, "not-completed");
        assertThat(this.store.decisions).isEmpty();
        assertThat(this.store.statuses).isEmpty();
        verify(this.transactions, never()).saveJobAuditLogs(anyLong(), anyString());
        verify(this.events, never()).reviewDecided(anyLong(), anyLong());
        // The real call with the same bulkId then decides it.
        assertThat(this.bulk.decide(request("APPROVED", null, BULK, false, 8301L)).getMessage()).isEqualTo("1 approved, 0 skipped.");
    }

    // ------------------------------------------------------------------------------------------- the request's rules

    @Test
    void atMostAHundredDistinctRunsAtOnce() {
        Long[] hundred = LongStream.rangeClosed(1, 100).boxed().toArray(Long[]::new);
        Long[] withADuplicate = Arrays.copyOf(hundred, 101);
        withADuplicate[100] = 1L;
        assertThat(data(this.bulk.decide(request("APPROVED", null, BULK, true, withADuplicate)))).containsEntry("requested", 100);

        Long[] hundredAndOne = LongStream.rangeClosed(1, 101).boxed().toArray(Long[]::new);
        refusedWith(() -> this.bulk.decide(request("APPROVED", null, BULK, true, hundredAndOne)), HttpStatus.BAD_REQUEST,
            "At most 100 runs at once; 101 were listed.");
        refusedWith(() -> this.bulk.decide(request("APPROVED", null, BULK, true)), HttpStatus.BAD_REQUEST,
            "List the runs to decide (jobQueueIds).");
    }

    @Test
    void aRejectionNeedsACommentAndNoCommentIsLongerThanFiveHundred() {
        this.reviewed(8301L);
        refusedWith(() -> this.bulk.decide(request("REJECTED", "   ", BULK, null, 8301L)), HttpStatus.BAD_REQUEST,
            "A rejection says why: the comment is required.");
        char[] long501 = new char[501];
        Arrays.fill(long501, 'x');
        refusedWith(() -> this.bulk.decide(request("APPROVED", new String(long501), BULK, null, 8301L)), HttpStatus.BAD_REQUEST,
            "The comment is at most 500 characters.");
        refusedWith(() -> this.bulk.decide(request("MAYBE", null, BULK, null, 8301L)), HttpStatus.BAD_REQUEST,
            "decision is APPROVED or REJECTED.");
        refusedWith(() -> this.bulk.decide(request("APPROVED", null, "no spaces!", null, 8301L)), HttpStatus.BAD_REQUEST,
            "bulkId is 8 to 64 letters, digits and '-'.");
        assertThat(this.store.decisions).isEmpty();
    }

    @Test
    void aBulkIdIsMadeWhenTheClientSendsNone() {
        this.reviewed(8301L);
        String made = (String) data(this.bulk.decide(request("APPROVED", null, null, null, 8301L))).get("bulkId");
        assertThat(made).matches(RunReviewBulk.BULK_ID.pattern());
        assertThat(this.store.decisions.get(0).bulkId).isEqualTo(made);
    }

    @Test
    void twentyBulkRequestsAMinutePerCallerThenA429() {
        for (int i = 0; i < 20; i++) {
            this.bulk.decide(request("APPROVED", null, BULK, true, 1L));
        }
        refusedWith(() -> this.bulk.decide(request("APPROVED", null, BULK, true, 1L)), HttpStatus.TOO_MANY_REQUESTS,
            "Too many bulk decisions in a minute; wait a moment and try again.");
        // Another caller has a minute of their own.
        TenantContext.set(TENANT, "TENANT_ADMIN", 73L, "ben@acme.example");
        assertThat(this.bulk.decide(request("APPROVED", null, BULK, true, 1L)).getStatus()).isEqualTo("SUCCESS");
        // A minute on, the first caller may again.
        this.asAdmin();
        this.now.addAndGet(60_000L);
        assertThat(this.bulk.decide(request("APPROVED", null, BULK, true, 1L)).getStatus()).isEqualTo("SUCCESS");
    }

    /** Validated first, then counted (as workflow-service and ai-service do): a refused request never uses up the cap. */
    @Test
    void aRequestThatIsNotValidIsA400AndIsNotCounted() {
        for (int i = 0; i < 25; i++) {
            refusedWith(() -> this.bulk.decide(request("APPROVED", null, BULK, true)), HttpStatus.BAD_REQUEST,
                "List the runs to decide (jobQueueIds).");
            refusedWith(() -> this.bulk.decide(request("MAYBE", null, BULK, true, 1L)), HttpStatus.BAD_REQUEST,
                "decision is APPROVED or REJECTED.");
        }
        for (int i = 0; i < 20; i++) {
            assertThat(this.bulk.decide(request("APPROVED", null, BULK, true, 1L)).getStatus()).isEqualTo("SUCCESS");
        }
        refusedWith(() -> this.bulk.decide(request("APPROVED", null, BULK, true, 1L)), HttpStatus.TOO_MANY_REQUESTS,
            "Too many bulk decisions in a minute; wait a moment and try again.");
        // Past the cap, a request that is not valid is still told why (400), not 429.
        refusedWith(() -> this.bulk.decide(request("REJECTED", null, BULK, true, 1L)), HttpStatus.BAD_REQUEST,
            "A rejection says why: the comment is required.");
    }

    // ---------------------------------------------------------------------------------------------------- the policy

    @Test
    void thePolicyIsOnUntilAnAdministratorTurnsItOff() {
        assertThat(data(this.policy.read())).containsEntry("bulkApproveRuns", true).containsEntry("saved", false)
            .containsEntry("updatedBy", null);
        refusedWith(() -> this.policy.save(null), HttpStatus.BAD_REQUEST, "bulkApproveRuns is true or false.");

        Map<String, Object> saved = data(this.policy.save(false));

        assertThat(saved).containsEntry("bulkApproveRuns", false).containsEntry("saved", true).containsEntry("updatedBy", ADMIN);
        assertThat(saved.get("updatedAt")).isNotNull();
        assertThat(this.policy.bulkApproveRuns(TENANT)).isFalse();
        assertThat(this.policy.bulkApproveRuns(OTHER_TENANT)).as("another workspace's own").isTrue();
        TenantContext.set(null, "PLATFORM_ADMIN", 1L, "admin@platform.local");
        refusedWith(this.policy::read, HttpStatus.BAD_REQUEST, ReviewPolicy.NO_WORKSPACE);
        refusedWith(() -> this.policy.save(true), HttpStatus.BAD_REQUEST, ReviewPolicy.NO_WORKSPACE);
    }

    // --------------------------------------------------------------------------------- individual on the review reads

    @Test
    void theReviewAndTheWaitingListSayWhetherARunIsDecidedOneByOne() {
        this.reviewed(8301L);
        this.run(8304L, INDIVIDUAL_JOB, TENANT, DEF_INDIVIDUAL, JobStatus.Completed);
        this.store.undecided.addAll(Arrays.asList(8304L, 8301L));

        assertThat(data(this.service.review(8301L))).containsEntry("individual", false);
        assertThat(data(this.service.review(8304L))).containsEntry("individual", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> waiting = (List<Map<String, Object>>) data(this.service.waiting(null)).get("runs");
        assertThat(waiting).extracting(row -> row.get("jobQueueId") + ":" + row.get("individual")).containsExactly("8304:true", "8301:false");
    }
}

package process.pipeline.review;

import org.junit.jupiter.api.Test;
import process.model.enums.JobStatus;
import process.model.enums.ReviewDecision;
import process.model.enums.ReviewParty;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.pipeline.InMemoryStepStore;
import process.pipeline.PipelineDefinitionStore;
import process.pipeline.StepStore;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-326: a page of runs reads its reviews at once ({@link RunReviews#statuses}) and gets, run for run, the status the run's
 * own summary gives: a decided row's status; else the pinned definition's required parties against the decisions; a run
 * that follows no definition is NOT_REQUIRED. Each definition is read once for the page.
 */
class RunReviewsPageTest {

    private static final long TENANT = 3326L;
    private static final long JOB = 3327L;
    private static final long DEFINITION = 4326L;

    private final InMemoryStepStore steps = new InMemoryStepStore();
    private final PipelineDefinitionStore definitions = mock(PipelineDefinitionStore.class);
    private final InMemoryRunReviewStore store = new InMemoryRunReviewStore();
    private final RunReviews reviews = new RunReviews(this.steps, this.definitions, this.store);

    private static JobQueue run(long id) {
        JobQueue run = new JobQueue();
        run.setJobQueueId(id);
        run.setJobId(JOB);
        run.setTenantId(TENANT);
        run.setAttempt(1);
        run.setJobStatus(JobStatus.Completed);
        run.setDateCreated(Timestamp.valueOf("2026-10-06 09:30:00"));
        return run;
    }

    private void follows(long runId) {
        this.steps.plan(runId, 1, DEFINITION, Collections.singletonList(new StepStore.Planned(0, "read", "sample", "fail")));
    }

    @Test
    void aPageGetsEachRunsOwnSummaryStatusAndReadsEachDefinitionOnce() {
        PipelineDefinitionStore.Stored stored = new PipelineDefinitionStore.Stored();
        stored.id = DEFINITION;
        stored.tenantId = TENANT;
        stored.version = 1;
        stored.json = "{\"version\":1,\"steps\":[{\"key\":\"read\",\"task\":\"sample\"}],\"settings\":{\"review\":{\"required\":[\"customer\"]}}}";
        when(this.definitions.byId(DEFINITION)).thenReturn(Optional.of(stored));
        SourceJob job = new SourceJob();
        job.setJobId(JOB);
        job.setTenantId(TENANT);
        SourceTask task = new SourceTask();
        task.setPipelineId("F326001");
        job.setTaskDetail(task);

        JobQueue waiting = run(9401L);      // follows the definition, nobody decided: PENDING
        JobQueue approved = run(9402L);     // a decided row: its status
        JobQueue decidedNoRow = run(9403L); // a decision recorded, no row yet: the rule over the decisions
        JobQueue plain = run(9404L);        // follows no definition at all: NOT_REQUIRED
        this.follows(waiting.getJobQueueId());
        this.follows(approved.getJobQueueId());
        this.follows(decidedNoRow.getJobQueueId());
        this.store.lockPending(approved.getJobQueueId(), EnumSet.of(ReviewParty.CUSTOMER));
        this.store.settle(approved.getJobQueueId(), RunReviewStatus.APPROVED, Instant.parse("2026-10-06T15:00:00Z"));
        RunReviewStore.Decision decision = new RunReviewStore.Decision();
        decision.jobQueueId = decidedNoRow.getJobQueueId();
        decision.attempt = 1;
        decision.party = ReviewParty.CUSTOMER;
        decision.decision = ReviewDecision.REJECTED;
        decision.decidedAt = Instant.parse("2026-10-06T15:01:00Z");
        this.store.record(decision);

        List<JobQueue> page = Arrays.asList(waiting, approved, decidedNoRow, plain);
        Map<Long, SourceJob> jobs = new HashMap<>();
        jobs.put(JOB, job);
        Map<Long, RunReviewStatus> statuses = this.reviews.statuses(page, jobs);
        verify(this.definitions, times(1)).byId(DEFINITION);

        for (JobQueue run : page) {
            assertThat(statuses.get(run.getJobQueueId()).name()).as("run %s", run.getJobQueueId())
                .isEqualTo(this.reviews.summary(run, job).get("reviewStatus"));
        }
        assertThat(statuses).containsEntry(9401L, RunReviewStatus.PENDING).containsEntry(9402L, RunReviewStatus.APPROVED)
            .containsEntry(9404L, RunReviewStatus.NOT_REQUIRED);
    }

    @Test
    void aRunWhoseJobIsNotGivenIsLeftOutAndAnEmptyPageReadsNothing() {
        assertThat(this.reviews.statuses(Collections.singletonList(run(9405L)), Collections.emptyMap())).isEmpty();
        assertThat(this.reviews.statuses(Collections.emptyList(), Collections.emptyMap())).isEmpty();
    }
}

package process.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import process.engine.ProducerBulkEngine;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.service.impl.TransactionServiceImpl;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-424: a Run pipeline step's call -- the workspace's own active step pipeline only, its parameters checked, at most three
 * deep and never a pipeline already in the chain; the called run runs on the caller's thread and its rows are the answer.
 */
class PipelineCallsTest {

    private static final long TENANT = 1006L;
    private static final long CALLER_RUN = 500L;

    private final TransactionServiceImpl jobs = mock(TransactionServiceImpl.class);
    private final ProducerBulkEngine producer = mock(ProducerBulkEngine.class);
    private final StepStore steps = mock(StepStore.class);
    private final PipelineParameters parameters = mock(PipelineParameters.class);
    private final RunResults results = mock(RunResults.class);
    private final StepEngine engine = mock(StepEngine.class);
    private final List<PipelineCalls.Link> chain = new ArrayList<>();
    private PipelineCalls calls;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wire() {
        ObjectProvider<StepEngine> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(this.engine);
        this.calls = new PipelineCalls(this.jobs, this.producer, this.steps, this.parameters, this.results, mock(JdbcTemplate.class),
            mock(PlatformTransactionManager.class), provider) {
            @Override
            List<Link> chain(long runId) {
                return PipelineCallsTest.this.chain;
            }
        };
        PipelineCalls.pollMillis = 1;
        this.link(CALLER_RUN, 40L, null, null);
        when(this.producer.workspacePause(TENANT)).thenReturn(Optional.empty());
    }

    private void link(long run, long job, Long calledBy, Integer depth) {
        PipelineCalls.Link link = new PipelineCalls.Link();
        link.runId = run;
        link.jobId = job;
        link.calledBy = calledBy;
        link.depth = depth;
        this.chain.add(link);
    }

    private SourceJob job(long id, long tenant, Status status) {
        SourceJob job = new SourceJob();
        job.setJobId(id);
        job.setTenantId(tenant);
        job.setJobStatus(status);
        job.setJobName("Care plan");
        when(this.jobs.findByJobId(id)).thenReturn(Optional.of(job));
        when(this.parameters.runsSteps(job)).thenReturn(true);
        return job;
    }

    private static PipelineParameters.Checked checked(String runParams, String refusal) {
        return new PipelineParameters.Checked(runParams, null, refusal);
    }

    @Test
    void anotherWorkspacesPipelineIsNotThere() {
        job(41L, 1013L, Status.Active);
        assertThatThrownBy(() -> this.calls.call(TENANT, CALLER_RUN, 41L, null, new RunResults.Selection(), 10))
            .isInstanceOf(PipelineCalls.Refused.class).hasMessage("No pipeline 41 in this workspace.");
        assertThatThrownBy(() -> this.calls.call(TENANT, CALLER_RUN, 999L, null, new RunResults.Selection(), 10))
            .isInstanceOf(PipelineCalls.Refused.class).hasMessage("No pipeline 999 in this workspace.");
        verify(this.producer, never()).addCalledJobInQueue(any(), anyLong(), anyInt(), any());
    }

    @Test
    void aChainNeverCallsAPipelineAlreadyInIt() {
        // Run 500 (job 40) was called by run 400 (job 41): calling 41 again is a loop.
        this.chain.clear();
        this.link(CALLER_RUN, 40L, 400L, 1);
        this.link(400L, 41L, null, null);
        job(41L, TENANT, Status.Active);
        assertThatThrownBy(() -> this.calls.call(TENANT, CALLER_RUN, 41L, null, new RunResults.Selection(), 10))
            .isInstanceOf(PipelineCalls.Refused.class)
            .hasMessage("Pipeline 41 is already in this call chain (runs 400 (pipeline 41) > 500 (pipeline 40)): a pipeline never calls "
                + "itself, directly or through another.");
        // And a pipeline never calls itself directly.
        job(40L, TENANT, Status.Active);
        assertThatThrownBy(() -> this.calls.call(TENANT, CALLER_RUN, 40L, null, new RunResults.Selection(), 10))
            .isInstanceOf(PipelineCalls.Refused.class).hasMessageStartingWith("Pipeline 40 is already in this call chain");
        verify(this.producer, never()).addCalledJobInQueue(any(), anyLong(), anyInt(), any());
    }

    @Test
    void aChainIsAtMostThreeDeep() {
        this.chain.clear();
        this.link(CALLER_RUN, 40L, 401L, 3);
        job(42L, TENANT, Status.Active);
        assertThatThrownBy(() -> this.calls.call(TENANT, CALLER_RUN, 42L, null, new RunResults.Selection(), 10))
            .isInstanceOf(PipelineCalls.Refused.class)
            .hasMessage("Run pipeline calls at most 3 pipelines deep; this run is already 3 deep.");
    }

    @Test
    void anInactiveOrLegacyPipelineAndAPausedWorkspaceCallNothing() {
        job(43L, TENANT, Status.Inactive);
        assertThatThrownBy(() -> this.calls.call(TENANT, CALLER_RUN, 43L, null, new RunResults.Selection(), 10))
            .hasMessage("Pipeline 43 (Care plan) is not active.");
        SourceJob legacy = job(44L, TENANT, Status.Active);
        when(this.parameters.runsSteps(legacy)).thenReturn(false);
        assertThatThrownBy(() -> this.calls.call(TENANT, CALLER_RUN, 44L, null, new RunResults.Selection(), 10))
            .hasMessage("Pipeline 44 (Care plan) does not run on Core's step engine, so it cannot be called.");
        job(45L, TENANT, Status.Active);
        when(this.producer.workspacePause(TENANT)).thenReturn(Optional.of("Suspended"));
        assertThatThrownBy(() -> this.calls.call(TENANT, CALLER_RUN, 45L, null, new RunResults.Selection(), 10))
            .hasMessage("This workspace is Suspended, so its runs are paused.");
    }

    @Test
    void parametersAreCheckedAsRunWithChecksThem() {
        SourceJob job = job(46L, TENANT, Status.Active);
        Map<String, Object> given = Collections.singletonMap("mrn", "{{mrn}}");
        when(this.parameters.check(job, given)).thenReturn(checked(null, "The run's parameters do not stand: mrn: text without '{{'."));
        assertThatThrownBy(() -> this.calls.call(TENANT, CALLER_RUN, 46L, given, new RunResults.Selection(), 10))
            .isInstanceOf(PipelineCalls.Refused.class).hasMessage("The run's parameters do not stand: mrn: text without '{{'.");
    }

    @Test
    void theCalledRunRunsOnTheCallersThreadAndItsRowsAreTheAnswer() throws Exception {
        SourceJob job = job(47L, TENANT, Status.Active);
        Map<String, Object> given = Collections.singletonMap("mrn", "MRN-1");
        when(this.parameters.check(job, given)).thenReturn(checked("{\"params\":{\"mrn\":\"MRN-1\"}}", null));
        JobQueue called = new JobQueue();
        called.setJobQueueId(501L);
        called.setTenantId(TENANT);
        called.setJobStatus(JobStatus.Queue);
        when(this.producer.addCalledJobInQueue(job, CALLER_RUN, 1, "{\"params\":{\"mrn\":\"MRN-1\"}}")).thenReturn(called);
        when(this.steps.claimForEngine(eq(501L), any())).thenReturn(true);
        Thread[] ranOn = new Thread[1];
        doAnswer(call -> {
            ranOn[0] = Thread.currentThread();
            called.setJobStatus(JobStatus.Completed);
            called.setJobStatusMessage("2 of 2 step(s) completed.");
            return null;
        }).when(this.engine).runCalled(job, called);
        when(this.jobs.findJobQueueByJobQueueId(501L)).thenAnswer(call -> Optional.of(called));
        Dataset answer = Dataset.of(Collections.singletonList(Collections.singletonMap("risk", "High")));
        when(this.results.rows(eq(501L), any(), eq(10), any())).thenReturn(answer);

        PipelineCalls.Called done = this.calls.call(TENANT, CALLER_RUN, 47L, given, new RunResults.Selection(), 10);

        assertThat(ranOn[0]).isSameAs(Thread.currentThread());
        assertThat(done.ok()).isTrue();
        assertThat(done.runId).isEqualTo(501L);
        assertThat(done.rows.getRows()).containsExactly(Collections.singletonMap("risk", "High"));
    }

    @Test
    void aSecondCallOfTheSameJobWhileTheFirstIsInFlightIsRefusedInWords() {
        SourceJob job = job(48L, TENANT, Status.Active);
        when(this.parameters.check(job, null)).thenReturn(checked(null, null));
        when(this.producer.addCalledJobInQueue(any(), anyLong(), anyInt(), any())).thenThrow(new RuntimeException("insert failed",
            new SQLException("duplicate key value violates unique constraint \"ux_job_queue_one_in_flight_per_caller\"", "23505")));
        assertThatThrownBy(() -> this.calls.call(TENANT, CALLER_RUN, 48L, null, new RunResults.Selection(), 10))
            .isInstanceOf(PipelineCalls.Refused.class).hasMessage("A run of pipeline 48 this run called is still in flight.");
    }

    @Test
    void aCalledRunThatFailsAnswersNoRows() throws Exception {
        SourceJob job = job(49L, TENANT, Status.Active);
        when(this.parameters.check(job, null)).thenReturn(checked(null, null));
        JobQueue called = new JobQueue();
        called.setJobQueueId(502L);
        called.setJobStatus(JobStatus.Failed);
        called.setJobStatusMessage("Step 1 of 2 (read) failed: no such table.");
        when(this.producer.addCalledJobInQueue(job, CALLER_RUN, 1, null)).thenReturn(called);
        when(this.steps.claimForEngine(eq(502L), anyString())).thenReturn(true);
        when(this.jobs.findJobQueueByJobQueueId(502L)).thenReturn(Optional.of(called));
        PipelineCalls.Called done = this.calls.call(TENANT, CALLER_RUN, 49L, null, new RunResults.Selection(), 10);
        assertThat(done.ok()).isFalse();
        assertThat(done.status).isEqualTo("Failed");
        assertThat(done.rows.isEmpty()).isTrue();
    }
}

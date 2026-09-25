package process.engine;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;
import process.ai.AiStepService;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.enums.RunEnd;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.pojo.SourceTaskType;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.model.service.impl.MessageQServiceImpl;
import process.model.service.impl.NotifyServiceImpl;
import process.model.service.impl.QueryService;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.notifications.NotificationPort;
import process.security.RunCallbackTokens;
import process.security.TenantContext;
import process.slo.RunOutcomes;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * MIG-196: every code path that writes a terminal status records why and counts the run -- through a real BulkAction
 * and a real counter, over an in-memory job_queue. One test per path, and the two C7c cases: a retried run is
 * counted once, at its last attempt, whichever way that attempt ends.
 */
class RunEndPathsTest {

    private static final long TENANT = 2905L;
    private static final long JOB_ID = 1196L;

    private final Map<Long, JobQueue> rows = new HashMap<>();
    private TransactionServiceImpl store;
    private SourceJob job;
    private SimpleMeterRegistry registry;
    private BulkAction bulkAction;
    private JobMail jobMail;

    @BeforeEach
    void setUp() {
        this.store = mock(TransactionServiceImpl.class);
        this.jobMail = mock(JobMail.class);
        this.job = new SourceJob();
        this.job.setJobId(JOB_ID);
        this.job.setTenantId(TENANT);
        // The caller's own job (user 1): a TENANT_USER acts only on the jobs that name them (JobOwnership).
        this.job.setAssignedUserId(1L);
        this.job.setJobStatus(Status.Active);
        this.job.setMaxAttempts(1);
        this.job.setRetryBackoffSeconds(60);
        lenient().when(this.store.findByJobId(JOB_ID)).thenReturn(Optional.of(this.job));
        lenient().when(this.store.findByJobIdAndJobStatus(JOB_ID, Status.Active)).thenReturn(Optional.of(this.job));
        lenient().when(this.store.findJobQueueByJobQueueId(anyLong()))
            .thenAnswer(call -> Optional.ofNullable(this.rows.get((Long) call.getArgument(0))));
        lenient().doAnswer(call -> {
            JobQueue saved = call.getArgument(0);
            if (saved.getJobQueueId() == null) {
                saved.setJobQueueId(9000L + this.rows.size());
            }
            this.rows.put(saved.getJobQueueId(), saved);
            return null;
        }).when(this.store).saveOrUpdateJobQueue(any());
        this.registry = new SimpleMeterRegistry();
        this.bulkAction = new BulkAction(this.store, mock(NotificationPort.class), new RunOutcomes(this.registry));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private JobQueue run(long id, JobStatus status) {
        JobQueue run = new JobQueue();
        run.setJobQueueId(id);
        run.setJobId(JOB_ID);
        run.setTenantId(TENANT);
        run.setJobStatus(status);
        this.rows.put(id, run);
        return run;
    }

    private double count(String outcome, String reason, String slo) {
        Counter counter = this.registry.find(RunOutcomes.ENDED).tag("outcome", outcome).tag("reason", reason).tag("slo", slo).counter();
        return counter == null ? 0 : counter.count();
    }

    private double total() {
        return this.registry.find(RunOutcomes.ENDED).counters().stream().mapToDouble(Counter::count).sum();
    }

    // ---- the worker's callback (NotifyServiceImpl) ------------------------------------------------------------------

    private NotifyServiceImpl callbacks() {
        return new NotifyServiceImpl(this.bulkAction, this.jobMail, this.store, mock(NotificationPort.class));
    }

    private void workerReports(long runId, JobStatus status) {
        SourceJobQueueDto dto = new SourceJobQueueDto();
        dto.setJobId(JOB_ID);
        dto.setJobQueueId(runId);
        dto.setJobStatus(status);
        dto.setJobStatusMessage("worker says " + status);
        dto.setEndTime(LocalDateTime.of(2026, 9, 24, 9, 30));
        this.callbacks().changeState(dto);
    }

    /** What the dispatcher and the worker's Running do to a run between attempts. */
    private void dispatchedAndRunning(long runId) {
        this.bulkAction.changeJobQueueStatus(runId, JobStatus.Start, null);
        this.bulkAction.changeJobQueueStatus(runId, JobStatus.Running, null);
        this.job.setJobRunningStatus(JobStatus.Running);
    }

    @Test
    void aWorkersCompletedIsCountedGoodOnceAndItsRepeatIsNot() {
        this.run(7001, JobStatus.Running);
        this.job.setJobRunningStatus(JobStatus.Running);
        this.workerReports(7001, JobStatus.Completed);
        assertThat(this.rows.get(7001L).getEndReason()).isEqualTo(RunEnd.WORKER);
        assertThat(this.count("completed", "worker", "good")).isEqualTo(1);

        // Completed -> Completed is a legal transition (a redelivery under a new key): recorded, not counted again.
        this.job.setJobRunningStatus(JobStatus.Completed);
        this.workerReports(7001, JobStatus.Completed);
        assertThat(this.total()).isEqualTo(1);
    }

    @Test
    void aWorkersFailureWithNoAttemptLeftIsCountedBad() {
        this.run(7002, JobStatus.Running);
        this.job.setJobRunningStatus(JobStatus.Running);
        this.workerReports(7002, JobStatus.Failed);
        assertThat(this.rows.get(7002L).getJobStatus()).isEqualTo(JobStatus.Failed);
        assertThat(this.rows.get(7002L).getEndReason()).isEqualTo(RunEnd.WORKER);
        assertThat(this.count("failed", "worker", "bad")).isEqualTo(1);
        assertThat(this.total()).isEqualTo(1);
    }

    /** C7c: the row is reused. Two failures and a third attempt that fails: one run, one bad, counted at the end. */
    @Test
    void aRetriedRunThatFinallyFailsIsCountedOnce() {
        this.job.setMaxAttempts(3);
        this.run(7003, JobStatus.Running);
        this.job.setJobRunningStatus(JobStatus.Running);
        this.workerReports(7003, JobStatus.Failed);
        assertThat(this.rows.get(7003L).getJobStatus()).as("retried").isEqualTo(JobStatus.Queue);
        assertThat(this.rows.get(7003L).getEndReason()).isNull();
        assertThat(this.total()).as("a retry ends nothing").isZero();
        this.dispatchedAndRunning(7003);
        this.workerReports(7003, JobStatus.Failed);
        assertThat(this.total()).isZero();
        this.dispatchedAndRunning(7003);
        this.workerReports(7003, JobStatus.Failed);
        assertThat(this.rows.get(7003L).getAttempt()).isEqualTo(3);
        assertThat(this.count("failed", "worker", "bad")).isEqualTo(1);
        assertThat(this.total()).isEqualTo(1);
    }

    @Test
    void aRetriedRunThatFinallyCompletesIsCountedOnceAsGood() {
        this.job.setMaxAttempts(2);
        this.run(7004, JobStatus.Running);
        this.job.setJobRunningStatus(JobStatus.Running);
        this.workerReports(7004, JobStatus.Failed);
        this.dispatchedAndRunning(7004);
        this.workerReports(7004, JobStatus.Completed);
        assertThat(this.count("completed", "worker", "good")).isEqualTo(1);
        assertThat(this.total()).isEqualTo(1);
    }

    /** A transition the table refuses writes nothing, so it ends nothing -- whatever the table says at merge time. */
    @Test
    void aRefusedTransitionCountsNothing() {
        this.run(7005, JobStatus.Queue);
        this.job.setJobRunningStatus(JobStatus.Queue);
        this.workerReports(7005, JobStatus.Completed);
        assertThat(this.rows.get(7005L).getJobStatus()).isEqualTo(JobStatus.Queue);
        assertThat(this.rows.get(7005L).getEndReason()).isNull();
        assertThat(this.total()).isZero();
    }

    /** MIG-201's decline (Start -> Failed) is told apart by the RUN's status before the write. */
    @Test
    void aFailureWrittenOverARunThatNeverRanIsADecline() {
        this.run(7006, JobStatus.Start);
        JobStatus before = this.bulkAction.changeJobQueueStatus(7006L, JobStatus.Failed, "no such pipeline here");
        this.bulkAction.runEnded(7006L, before, JobStatus.Failed, RunEnd.reportedBy(before, JobStatus.Failed));
        assertThat(this.rows.get(7006L).getEndReason()).isEqualTo(RunEnd.DECLINED);
        assertThat(this.count("failed", "declined", "bad")).isEqualTo(1);
    }

    /**
     * Through the callback itself: the job reads Running (the edge is legal before and after MIG-201) while its run
     * never left Start -- the reason is the RUN's, so this is a decline, not a worker's failure.
     */
    @Test
    void theCallbackReadsTheDeclineFromTheRunsOwnStatus() {
        this.run(7007, JobStatus.Start);
        this.job.setJobRunningStatus(JobStatus.Running);
        this.workerReports(7007, JobStatus.Failed);
        assertThat(this.rows.get(7007L).getEndReason()).isEqualTo(RunEnd.DECLINED);
        assertThat(this.count("failed", "declined", "bad")).isEqualTo(1);
        assertThat(this.count("failed", "worker", "bad")).isZero();
    }

    // ---- a person at the console (MessageQServiceImpl) ------------------------------------------------------------

    private MessageQServiceImpl console() {
        JobQueueRepository runs = mock(JobQueueRepository.class);
        SourceJobRepository jobs = mock(SourceJobRepository.class);
        lenient().when(runs.findById(anyLong())).thenAnswer(call -> Optional.ofNullable(this.rows.get((Long) call.getArgument(0))));
        lenient().when(jobs.findById(JOB_ID)).thenReturn(Optional.of(this.job));
        TenantContext.set(TENANT, "TENANT_USER", 1L, "a@example.com");
        return new MessageQServiceImpl(this.bulkAction, mock(QueryService.class), runs, jobs, this.jobMail);
    }

    @Test
    void aPersonsFailAndInterruptAreExcluded() {
        this.run(7010, JobStatus.Running);
        this.run(7011, JobStatus.Start);
        MessageQServiceImpl console = this.console();
        console.failJobLogs(7010L);
        console.interruptJobLogs(7011L);
        assertThat(this.rows.get(7010L).getEndReason()).isEqualTo(RunEnd.OPERATOR);
        assertThat(this.rows.get(7011L).getEndReason()).isEqualTo(RunEnd.OPERATOR);
        assertThat(this.count("failed", "operator", "excluded")).isEqualTo(1);
        assertThat(this.count("interrupt", "operator", "excluded")).isEqualTo(1);
        assertThat(this.total()).isEqualTo(2);
    }

    // ---- the dispatch side (DispatchFailures, PreDispatchPhase) ----------------------------------------------------

    private DispatchFailures failures() {
        return new DispatchFailures(this.bulkAction, this.store, this.jobMail, TransactionOperations.withoutTransaction());
    }

    @Test
    void aDispatchFailureOutOfRetriesIsBadAndARefusalIsExcluded() {
        JobQueue sendFailed = this.run(7020, JobStatus.Queue);
        JobQueue unrouted = this.run(7021, JobStatus.Queue);
        this.failures().close(sendFailed, "the broker would not take it", true);
        this.failures().close(unrouted, "no Kafka connection resolves", false);
        assertThat(this.rows.get(7020L).getEndReason()).isEqualTo(RunEnd.DISPATCH);
        assertThat(this.rows.get(7021L).getEndReason()).isEqualTo(RunEnd.REFUSED);
        assertThat(this.count("failed", "dispatch", "bad")).isEqualTo(1);
        assertThat(this.count("failed", "refused", "excluded")).isEqualTo(1);
    }

    @Test
    void aDispatchFailureWithAttemptsLeftIsRetriedAndNotCounted() {
        this.job.setMaxAttempts(2);
        JobQueue sendFailed = this.run(7022, JobStatus.Queue);
        this.failures().close(sendFailed, "the broker would not take it", true);
        assertThat(this.rows.get(7022L).getJobStatus()).isEqualTo(JobStatus.Queue);
        assertThat(this.total()).isZero();
    }

    @Test
    void aFailedAiStepClosesTheRunAsAnAiStepNotADispatchFailure() {
        AiStepService steps = mock(AiStepService.class);
        SourceTaskType broker = new SourceTaskType();
        broker.setSourceTaskTypeId(31L);
        broker.setStatus(Status.Active);
        broker.setQueueTopicPartition("topic=etl.jobs&partitions=[*]");
        SourceTask task = new SourceTask();
        task.setTaskDetailId(4200L);
        task.setPipelineId("F1");
        task.setTaskPayload("<pipeline><summary/></pipeline>");
        task.setSourceTaskType(broker);
        this.job.setTaskDetail(task);
        lenient().when(steps.apply(eq(TENANT), eq("F1"), eq(7030L), any()))
            .thenReturn(new AiStepService.Outcome(null, "AI step <summary> failed: no active model connection"));
        JobQueue run = this.run(7030, JobStatus.Queue);
        PreDispatchPhase phase = new PreDispatchPhase(this.store, this.bulkAction, steps, this.jobMail,
            TransactionOperations.withoutTransaction(), mock(ExecutorService.class));
        assertThat(phase.decide(Optional.of(this.job), run).refusedAs).isEqualTo(RunEnd.AI_STEP);
        assertThat(phase.decide(Optional.empty(), run).refusedAs).isEqualTo(RunEnd.REFUSED);
        phase.prepare(Optional.of(this.job), run);
        assertThat(this.rows.get(7030L).getJobStatus()).isEqualTo(JobStatus.Failed);
        assertThat(this.rows.get(7030L).getEndReason()).isEqualTo(RunEnd.AI_STEP);
        assertThat(this.count("failed", "ai_step", "excluded")).isEqualTo(1);
    }

    // ---- the stall sweep (C4) ---------------------------------------------------------------------------------------

    @Test
    void theSweepClosesAsInterruptAndCountsItBadByItsReason() {
        JobQueue silent = this.run(7040, JobStatus.Running);
        silent.setStartTime(LocalDateTime.of(2026, 9, 24, 1, 0));
        JobQueue refused = this.run(7041, JobStatus.Start);
        refused.setRefusedCallbackAt(LocalDateTime.of(2026, 9, 24, 8, 0));
        refused.setRefusedCallbackStatus("Completed");
        lenient().when(this.store.findStalledRuns(any())).thenReturn(Collections.singletonList(silent));
        lenient().when(this.store.findRunsWithRefusedCallbacks()).thenReturn(Collections.singletonList(refused));
        new ProducerBulkEngine(this.bulkAction, this.store, this.jobMail, mock(RunCallbackTokens.class), null).reconcileStalledRuns();
        assertThat(silent.getJobStatus()).isEqualTo(JobStatus.Interrupt);
        assertThat(silent.getEndReason()).isEqualTo(RunEnd.STALLED);
        assertThat(refused.getJobStatus()).isEqualTo(JobStatus.Interrupt);
        assertThat(refused.getEndReason()).isEqualTo(RunEnd.TOKEN_EXPIRED);
        assertThat(this.count("interrupt", "stalled", "bad")).isEqualTo(1);
        assertThat(this.count("interrupt", "token_expired", "bad")).isEqualTo(1);
        assertThat(this.count("failed", "stalled", "bad")).as("C4: never Failed").isZero();
        assertThat(this.total()).isEqualTo(2);
    }

    // ---- runs made already over (BulkAction.createJobQueue) --------------------------------------------------------

    @Test
    void skipsAndMissedSlotsAreCountedExcludedAndAQueuedRunIsNotCounted() {
        JobQueue skipped = this.bulkAction.createJobQueue(JOB_ID, LocalDateTime.of(2026, 9, 24, 9, 0), JobStatus.Skip,
            "Job %s skip, already in queue.", true);
        JobQueue skippedByHand = this.bulkAction.createJobQueueV1(JOB_ID, LocalDateTime.of(2026, 9, 24, 9, 5), JobStatus.Skip,
            "Job %s skip, by user action.", true);
        JobQueue queued = this.bulkAction.createJobQueue(JOB_ID, LocalDateTime.of(2026, 9, 24, 9, 10), JobStatus.Queue,
            "Job %s now in the queue.", false);
        // What BulkAction.recordMissedRun writes for each slot a catch-up passes over.
        JobQueue missed = this.bulkAction.createJobQueue(JOB_ID, LocalDateTime.of(2026, 9, 24, 3, 0), JobStatus.Missed,
            "Job %s missed its scheduled run.", true);
        assertThat(skipped.getEndReason()).isEqualTo(RunEnd.SKIPPED);
        assertThat(skippedByHand.getEndReason()).isEqualTo(RunEnd.SKIPPED);
        assertThat(queued.getEndReason()).isNull();
        assertThat(missed.getEndReason()).isEqualTo(RunEnd.MISSED);
        assertThat(this.count("skip", "skipped", "excluded")).isEqualTo(2);
        assertThat(this.count("missed", "missed", "excluded")).isEqualTo(1);
        assertThat(this.total()).isEqualTo(3);
    }
}

package process.engine;

import process.util.BusinessTime;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionOperations;
import process.ai.AiStepService;
import process.ai.HttpAi;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.pojo.SourceTaskType;
import process.model.repository.JobQueueRepository;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.outbox.DispatchOutbox;
import process.security.RunCallbackTokens;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-159's dispatch blocker, resolved by MIG-25 and MIG-134.
 *
 * It was pinned that a pipeline's server AI steps ran synchronously on the scheduler's own thread,
 * inside the dispatch pass's seven-minute budget and ten-minute lock, and that one step could hold that
 * thread for HttpAi's ten-minute read timeout -- so one slow model call starved every queued job and
 * could let a second instance dispatch the same rows. Both pins are resolved: the steps run in the
 * pre-dispatch phase, on its own AI threads, and the dispatcher takes only runs that phase has finished
 * with. Here, a model call held open -- the stand-in for twenty minutes of provider timeouts -- does not
 * hold up an unrelated job, does not touch the dispatch pass, and is not prepared twice.
 */
class AiStepDispatchBlockerTest {

    private static final long TENANT = 2905L;

    private final BulkAction bulkAction = mock(BulkAction.class);
    private final TransactionServiceImpl transactionService = mock(TransactionServiceImpl.class);
    private final AiStepService aiStepService = mock(AiStepService.class);
    private final DispatchOutbox outbox = mock(DispatchOutbox.class);
    private final ExecutorService aiThreads = Executors.newFixedThreadPool(2);
    private final CountDownLatch modelAnswers = new CountDownLatch(1);

    @AfterEach
    void stop() {
        this.modelAnswers.countDown();
        this.aiThreads.shutdownNow();
    }

    private static SourceJob job(long jobId, String pipelineId) {
        SourceTaskType type = new SourceTaskType();
        type.setSourceTaskTypeId(31L);
        type.setStatus(Status.Active);
        type.setQueueTopicPartition("topic=claims&partitions=[*]");
        SourceTask task = new SourceTask();
        task.setSourceTaskType(type);
        task.setPipelineId(pipelineId);
        task.setTaskPayload("<pipeline><document>notes</document></pipeline>");
        SourceJob job = new SourceJob();
        job.setJobId(jobId);
        job.setTenantId(TENANT);
        job.setJobStatus(Status.Active);
        job.setTaskDetail(task);
        return job;
    }

    private JobQueue queued(long jobQueueId, long jobId, SourceJob job) {
        JobQueue run = new JobQueue();
        run.setJobQueueId(jobQueueId);
        run.setJobId(jobId);
        run.setJobStatus(JobStatus.Queue);
        when(this.transactionService.findJobQueueByJobQueueId(jobQueueId)).thenReturn(Optional.of(run));
        when(this.transactionService.findByJobIdAndJobStatus(jobId, Status.Active)).thenReturn(Optional.of(job));
        return run;
    }

    private PreDispatchPhase phase() {
        return new PreDispatchPhase(this.transactionService, this.bulkAction, this.aiStepService, mock(JobMail.class),
            TransactionOperations.withoutTransaction(), this.aiThreads);
    }

    /**
     * Job 1 has an AI step whose model call does not come back -- held for as long as the test lets it,
     * the stand-in for twenty minutes of provider timeouts; job 2 has none.
     */
    private void aStalledModelCallOnJobOne() {
        when(this.aiStepService.hasSteps(TENANT, "F1")).thenReturn(true);
        when(this.aiStepService.hasSteps(TENANT, "F2")).thenReturn(false);
        when(this.aiStepService.apply(eq(TENANT), eq("F1"), anyLong(), any())).thenAnswer(inv -> {
            this.modelAnswers.await(8, TimeUnit.SECONDS);
            return new AiStepService.Outcome("<pipeline><summary>late</summary></pipeline>", null);
        });
        when(this.aiStepService.apply(eq(TENANT), eq("F2"), anyLong(), any()))
            .thenAnswer(inv -> new AiStepService.Outcome(inv.getArgument(3), null));
        when(this.transactionService.markPrepared(anyLong(), any(), any(), any())).thenReturn(1);
    }

    @Test
    void aStalledModelCallDoesNotDelayAnUnrelatedQueuedJob() {
        this.aStalledModelCallOnJobOne();
        this.queued(501L, 1L, job(1L, "F1"));
        this.queued(502L, 2L, job(2L, "F2"));
        when(this.transactionService.claimRunsToPrepare(any(), anyInt(), any())).thenReturn(Arrays.asList(501L, 502L));

        long started = System.nanoTime();
        this.phase().runPass();
        long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(tookMs).as("the pass hands the AI run off and does not wait for it").isLessThan(5_000L);
        verify(this.transactionService).markPrepared(eq(502L), any(), any(), any());
        verify(this.transactionService, never()).markPrepared(eq(501L), any(), any(), any());

        this.modelAnswers.countDown();
        verify(this.transactionService, timeout(5_000)).markPrepared(eq(501L), eq("<pipeline><summary>late</summary></pipeline>"), any(), any());
    }

    /** The step runs on an AI thread -- never the scheduler's, which is where the dispatch lock is held. */
    @Test
    void theAiStepsNoLongerRunOnTheSchedulersThread() {
        this.aStalledModelCallOnJobOne();
        this.modelAnswers.countDown();
        this.queued(501L, 1L, job(1L, "F1"));
        when(this.transactionService.claimRunsToPrepare(any(), anyInt(), any())).thenReturn(Collections.singletonList(501L));
        AtomicReference<Thread> ranOn = new AtomicReference<>();
        when(this.aiStepService.apply(eq(TENANT), eq("F1"), eq(501L), any())).thenAnswer(inv -> {
            ranOn.set(Thread.currentThread());
            return new AiStepService.Outcome(inv.getArgument(3), null);
        });

        this.phase().runPass();

        verify(this.transactionService, timeout(5_000)).markPrepared(eq(501L), any(), any(), any());
        assertThat(ranOn.get()).isNotNull().isNotSameAs(Thread.currentThread());
    }

    /** The dispatch pass itself asks no AI step anything: it sends the document the phase prepared. */
    @Test
    void theDispatchPassNeverAsksTheAiService() {
        SourceJob job = job(1L, "F1");
        JobQueue run = this.queued(501L, 1L, job);
        run.setPreparedAt(BusinessTime.now());
        run.setDispatchPayload("<pipeline><summary>prepared</summary></pipeline>");
        when(this.transactionService.findAllJobForTodayWithLimit(anyLong(), any())).thenReturn(Collections.singletonList(run));
        ProducerBulkEngine dispatcher = new ProducerBulkEngine(this.bulkAction, this.transactionService, mock(JobMail.class),
            mock(RunCallbackTokens.class), this.outbox);

        dispatcher.startJobInCurrentTimeSlot();

        verify(this.outbox).write(any());
        verify(this.aiStepService, never()).apply(any(), any(), any(), any());
        verify(this.aiStepService, never()).hasSteps(any(), any());
    }

    /**
     * A job whose AI step is still pending is not dispatched -- the dispatcher's pick-up takes prepared
     * runs only -- and not prepared twice: a second pass while the first run is still answering does not
     * hand it to a second thread (and across replicas the claim's lease keeps it to one).
     */
    @Test
    void aRunStillBeingPreparedIsNeitherDispatchedNorPreparedTwice() throws Exception {
        String pickUp = JobQueueRepository.class.getMethod("findAllJobForTodayWithLimit", Long.class, Timestamp.class)
            .getAnnotation(Query.class).value();
        assertThat(pickUp).contains("prepared_at is not null");

        this.aStalledModelCallOnJobOne();
        this.queued(501L, 1L, job(1L, "F1"));
        when(this.transactionService.claimRunsToPrepare(any(), anyInt(), any())).thenReturn(Collections.singletonList(501L));
        PreDispatchPhase phase = this.phase();

        phase.runPass();
        phase.runPass();
        this.modelAnswers.countDown();

        verify(this.transactionService, timeout(5_000)).markPrepared(eq(501L), any(), any(), any());
        verify(this.aiStepService, times(1)).apply(eq(TENANT), eq("F1"), eq(501L), any());
    }

    /**
     * The numbers, re-derived (MIG-136). One server step still waits up to HttpAi's ten-minute read
     * timeout -- but on the pre-dispatch phase's lease, which covers three of them, not on the dispatch
     * pass, whose own worst case per row is its pause and one bounded transaction.
     */
    @Test
    void oneAiStepsWorstCaseNowFallsInsideThePreparationLeaseAndOutsideTheDispatchLock() {
        OkHttpClient http = (OkHttpClient) ReflectionTestUtils.getField(new HttpAi("http://ai:9150", "t"), "http");
        Duration oneStep = Duration.ofMillis(http.readTimeoutMillis());

        assertThat(oneStep).isEqualTo(Duration.ofMinutes(10));
        assertThat(DispatchTiming.PREPARE_LEASE).isGreaterThanOrEqualTo(oneStep.multipliedBy(3));
        Duration lastRowEnds = Duration.ofMillis(DispatchTiming.DISPATCH_BUDGET_MS + DispatchTiming.PER_ROW_PAUSE_MS)
            .plusSeconds(DispatchTiming.ROW_TRANSACTION_TIMEOUT_SECONDS);
        assertThat(lastRowEnds).isLessThan(DispatchTiming.DISPATCH_LOCK);
    }
}

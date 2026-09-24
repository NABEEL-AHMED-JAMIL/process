package process.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.ai.AiStepService;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.LookupData;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.pojo.SourceTaskType;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.security.RunCallbackTokens;
import process.util.ProcessUtil;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C7b writer A of four (MIG-140): ProducerBulkEngine.changeStatusForLastJob -- a run the dispatch
 * pass could not hand to a worker.
 *
 * Call paths: ProcessCron.startJobInCurrentTimeSlot -> ProducerBulkEngine.startJobInCurrentTimeSlot
 * -> pushMessageToQueue, and handleSendFailure on Kafka's own callback thread.
 *
 * Validation: NONE. Nothing reads the job's running status before Failed is written over it -- the
 * run is Queue by construction when it is dispatched, and this writer does not check even that.
 *
 * Retry: offered ONLY when the call site passes retryable=true -- the broker send failures and the
 * outer catch. A deleted or inactive job, a missing task or task type, an inactive or unparseable
 * broker and a failed AI step are configuration, will fail the same way every time, and are closed
 * at once. The retry is offered before any write, and its answer is the email gate: true means no
 * Failed status, no audit line, no notice and NO failure email.
 *
 * Four places write Failed and only the worker callback (writer B) is what a live worker calls; each
 * is pinned in its own class because each validates differently: FailedByDispatchTest,
 * FailedByWorkerCallbackTest, FailedByChangeJobStatusTest, FailedByOperatorTest.
 */
@ExtendWith(MockitoExtension.class)
class FailedByDispatchTest {

    private static final long JOB_ID = 1196L;
    private static final long QUEUE_ID = 5073L;

    @Mock private BulkAction bulkAction;
    @Mock private TransactionServiceImpl transactionService;
    @Mock private JobMail jobMail;
    @Mock private KafkaTemplateProvider kafkaTemplateProvider;
    @Mock private KafkaConnectionResolver kafkaConnectionResolver;
    @Mock private RunCallbackTokens runCallbackTokens;
    @Mock private AiStepService aiStepService;

    private ProducerBulkEngine engine;
    private JobQueue run;

    @BeforeEach
    void setUp() {
        this.engine = new ProducerBulkEngine(this.bulkAction, this.transactionService, this.jobMail,
            this.kafkaTemplateProvider, this.kafkaConnectionResolver, this.runCallbackTokens, this.aiStepService);
        this.run = new JobQueue();
        this.run.setJobQueueId(QUEUE_ID);
        this.run.setJobId(JOB_ID);
        this.run.setJobStatus(JobStatus.Queue);
    }

    /** The job as the mail check reads it: finished long ago, and wanting failure mail. */
    private void jobRowReads(JobStatus runningStatus, boolean failJob) {
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID);
        job.setJobRunningStatus(runningStatus);
        job.setFailJob(failJob);
        lenient().when(this.transactionService.findByJobId(JOB_ID)).thenReturn(Optional.of(job));
    }

    private void dispatchPassFinds(Optional<SourceJob> activeJob) {
        LookupData limit = new LookupData();
        limit.setLookupValue("10");
        when(this.transactionService.findByLookupType(ProcessUtil.QUEUE_FETCH_LIMIT)).thenReturn(limit);
        when(this.transactionService.findAllJobForTodayWithLimit(anyLong(), any()))
            .thenReturn(Collections.singletonList(this.run));
        when(this.transactionService.findByJobIdAndJobStatus(JOB_ID, Status.Active)).thenReturn(activeJob);
    }

    private void handleSendFailure(Throwable cause) throws Exception {
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID);
        Method method = ProducerBulkEngine.class.getDeclaredMethod("handleSendFailure",
            Throwable.class, String.class, SourceJob.class, JobQueue.class);
        method.setAccessible(true);
        try {
            method.invoke(this.engine, cause, "{}", job, this.run);
        } catch (InvocationTargetException wrapper) {
            throw (Exception) wrapper.getCause();
        }
    }

    // ---- validation: none ----------------------------------------------------------------------------

    /**
     * Failed is written over whatever the job row holds -- here Completed, from an earlier run.
     * Writer B would refuse Completed -> Failed; this writer never asks.
     */
    @Test
    void failedIsWrittenWithoutReadingTheJobsCurrentStatus() {
        this.jobRowReads(JobStatus.Completed, false);
        this.dispatchPassFinds(Optional.empty());

        this.engine.startJobInCurrentTimeSlot();

        verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Failed);
        verify(this.bulkAction).changeJobQueueStatus(QUEUE_ID, JobStatus.Failed,
            "Job 1196 failed in the queue because the main job is deleted or inactive.");
    }

    // ---- retry: only where the call site says so -----------------------------------------------------

    @Test
    void aDeletedOrInactiveJobIsClosedAtOnceWithoutARetry() {
        this.jobRowReads(JobStatus.Queue, true);
        this.dispatchPassFinds(Optional.empty());

        this.engine.startJobInCurrentTimeSlot();

        verify(this.bulkAction, never()).scheduleRetry(any(JobQueue.class), any());
        verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Failed);
        verify(this.jobMail).send(any(SourceJobQueueDto.class), eq(JobStatus.Failed));
    }

    @Test
    void anInactiveBrokerIsClosedAtOnceWithoutARetry() {
        this.jobRowReads(JobStatus.Queue, false);
        SourceTaskType type = new SourceTaskType();
        type.setStatus(Status.Inactive);
        SourceTask task = new SourceTask();
        task.setSourceTaskType(type);
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID);
        job.setTaskDetail(task);
        this.dispatchPassFinds(Optional.of(job));

        this.engine.startJobInCurrentTimeSlot();

        verify(this.bulkAction, never()).scheduleRetry(any(JobQueue.class), any());
        verify(this.bulkAction).changeJobQueueStatus(QUEUE_ID, JobStatus.Failed, "Broker is not active for job 1196.");
    }

    /**
     * The email gate. A send failure with attempts left writes nothing a person would see: no Failed
     * status, no audit line, no end time, no notice, and no email.
     */
    @Test
    void aSendFailureWithAttemptsLeftIsRetriedAndSendsNothing() throws Exception {
        when(this.bulkAction.scheduleRetry(eq(this.run), anyString())).thenReturn(true);

        this.handleSendFailure(new IllegalStateException("Failed to construct kafka producer"));

        verify(this.bulkAction).scheduleRetry(this.run,
            "Job 1196 could not be handed to the worker queue: Failed to construct kafka producer");
        verify(this.bulkAction, never()).changeJobStatus(anyLong(), any());
        verify(this.bulkAction, never()).changeJobQueueStatus(anyLong(), any(), any());
        verify(this.bulkAction, never()).saveJobAuditLogs(anyLong(), anyString());
        verify(this.bulkAction, never()).changeJobQueueEndDate(anyLong(), any());
        verify(this.bulkAction, never()).sendJobStatusNotification(anyLong(), any(), eq(true));
        verifyNoInteractions(this.jobMail);
    }

    /** Out of attempts: the retry is still asked FIRST, then the failure is written and mailed once. */
    @Test
    void aSendFailureOutOfAttemptsAsksForTheRetryFirstThenAnnouncesOnce() throws Exception {
        this.jobRowReads(JobStatus.Queue, true);
        when(this.bulkAction.scheduleRetry(eq(this.run), anyString())).thenReturn(false);

        this.handleSendFailure(new IllegalStateException("broker down"));

        String reason = "Job 1196 could not be handed to the worker queue: broker down";
        InOrder order = inOrder(this.bulkAction, this.jobMail);
        order.verify(this.bulkAction).scheduleRetry(this.run, reason);
        order.verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Failed);
        order.verify(this.bulkAction).changeJobQueueStatus(QUEUE_ID, JobStatus.Failed, reason);
        order.verify(this.bulkAction).saveJobAuditLogs(QUEUE_ID, reason);
        order.verify(this.bulkAction).changeJobQueueEndDate(eq(QUEUE_ID), any(LocalDateTime.class));
        order.verify(this.bulkAction).sendJobStatusNotification(JOB_ID, QUEUE_ID, true);
        order.verify(this.jobMail).send(any(SourceJobQueueDto.class), eq(JobStatus.Failed));
    }

    @Test
    void theFailureMailFollowsTheJobsFailedPreferenceOnly() throws Exception {
        this.jobRowReads(JobStatus.Queue, false);
        when(this.bulkAction.scheduleRetry(eq(this.run), anyString())).thenReturn(false);

        this.handleSendFailure(new IllegalStateException("broker down"));

        verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Failed);
        verifyNoInteractions(this.jobMail);
    }
}

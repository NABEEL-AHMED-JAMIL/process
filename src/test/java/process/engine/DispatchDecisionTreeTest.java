package process.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.concurrent.SettableListenableFuture;
import process.ai.AiStepService;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.pojo.SourceTaskType;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.security.RunCallbackTokens;
import process.security.TenantContext;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MIG-133: the dispatch decision tree in ProducerBulkEngine.pushMessageToQueue, and where the AI steps
 * sit inside it -- pinned before the AI steps move behind a network boundary.
 *
 * Eight rows, and which of them a retry is offered for:
 *
 *   1. no task attached                 -> Failed, NOT retryable
 *   2. no task type configured          -> Failed, NOT retryable
 *   3. broker (task type) not Active    -> Failed, NOT retryable
 *   4. broker topic/partition unparsable-> Failed, NOT retryable
 *   5. an AI step failed                -> Failed, NOT retryable   (AiStepService.Outcome.failed())
 *   6. anything thrown inside the try   -> Failed, RETRYABLE, "could not be dispatched"
 *   7. the broker refused the send      -> Failed, RETRYABLE, "could not be handed to the worker queue"
 *   8. the broker took it               -> the run moves to Start
 *
 * The AI steps run after the broker checks (rows 3 and 4 never reach them) and before the run's token
 * is minted and the message sent; every note they return is audit-logged BEFORE the failure check, so
 * a failed step still leaves its narrative in the run's history; and the document they rewrote is
 * what is sent, while the task's stored payload is never touched.
 *
 * Why the AI seam must not throw, verbatim from the task: "PromptRunner.run never throws and
 * AiStepService returns an Outcome rather than raising, precisely because a throw at this seam
 * silently reclassifies AI failures from non-retryable to retryable." Row 5 versus row 6 is that
 * reclassification, and ifTheAiStepThrewItWouldBeMisreportedAsARetryableDispatchFailure shows it.
 * That apply CAN throw today is AiStepAtDispatchTest's subject.
 */
@ExtendWith(MockitoExtension.class)
class DispatchDecisionTreeTest {

    private static final long TENANT = 2905L;
    private static final long OTHER_TENANT = 4102L;
    private static final long JOB_ID = 1196L;
    private static final long QUEUE_ID = 5073L;
    private static final long TASK_TYPE_ID = 31L;
    private static final String STORED = "<pipeline><claim_id>CLM-1</claim_id><summary/></pipeline>";
    private static final String ANSWERED = "<pipeline><claim_id>CLM-1</claim_id><summary>diabetes</summary></pipeline>";

    @Mock private BulkAction bulkAction;
    @Mock private TransactionServiceImpl transactionService;
    @Mock private JobMail jobMail;
    @Mock private KafkaTemplateProvider kafkaTemplateProvider;
    @Mock private KafkaConnectionResolver kafkaConnectionResolver;
    @Mock private RunCallbackTokens runCallbackTokens;
    @Mock private AiStepService aiStepService;
    @Mock private KafkaTemplate<String, String> template;

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

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static SourceJob job(SourceTask task) {
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID);
        job.setTenantId(TENANT);
        job.setJobStatus(Status.Active);
        job.setTaskDetail(task);
        return job;
    }

    private static SourceTask task(SourceTaskType type) {
        SourceTask task = new SourceTask();
        task.setTaskDetailId(4200L);
        task.setPipelineId("F1");
        task.setTaskPayload(STORED);
        task.setSourceTaskType(type);
        return task;
    }

    private static SourceTaskType broker(Status status, String topicPartition) {
        SourceTaskType type = new SourceTaskType();
        type.setSourceTaskTypeId(TASK_TYPE_ID);
        type.setStatus(status);
        type.setQueueTopicPartition(topicPartition);
        return type;
    }

    private static SourceJob dispatchable() {
        return job(task(broker(Status.Active, "topic=etl.jobs&partitions=[*]")));
    }

    private void push(SourceJob job) throws Exception {
        Method method = ProducerBulkEngine.class.getDeclaredMethod("pushMessageToQueue", SourceJob.class, JobQueue.class);
        method.setAccessible(true);
        try {
            method.invoke(this.engine, job, this.run);
        } catch (InvocationTargetException wrapper) {
            throw (Exception) wrapper.getCause();
        }
    }

    private void aiSteps(AiStepService.Outcome outcome) {
        when(this.aiStepService.apply(TENANT, "F1", QUEUE_ID, STORED)).thenReturn(outcome);
    }

    private static AiStepService.Outcome answered(String... notes) {
        AiStepService.Outcome outcome = new AiStepService.Outcome(ANSWERED, null);
        outcome.notes.addAll(Arrays.asList(notes));
        return outcome;
    }

    private SettableListenableFuture<SendResult<String, String>> brokerIsReached() {
        SettableListenableFuture<SendResult<String, String>> future = new SettableListenableFuture<>();
        when(this.kafkaTemplateProvider.getTemplate(any())).thenReturn(this.template);
        when(this.template.send(eq("etl.jobs"), anyString(), anyString())).thenReturn(future);
        return future;
    }

    private static SendResult<String, String> tookItAt(long offset) {
        return new SendResult<>(new ProducerRecord<>("etl.jobs", "k", "v"),
            new RecordMetadata(new TopicPartition("etl.jobs", 0), offset, 0L, 0L, 0L, 0, 0));
    }

    /** Closed as Failed with exactly this sentence; a retry asked for first only when it is retryable. */
    private void assertClosedAsFailed(String sentence, boolean retryable) {
        if (retryable) {
            verify(this.bulkAction).scheduleRetry(this.run, sentence);
        } else {
            verify(this.bulkAction, never()).scheduleRetry(any(JobQueue.class), any());
        }
        verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Failed);
        verify(this.bulkAction).changeJobQueueStatus(QUEUE_ID, JobStatus.Failed, sentence);
        verify(this.bulkAction).saveJobAuditLogs(QUEUE_ID, sentence);
        verify(this.bulkAction).sendJobStatusNotification(JOB_ID, QUEUE_ID, true);
    }

    // ---- rows 1 to 4: configuration, decided before any AI step runs ------------------------------------

    @Test
    void rowOneNoTaskIsClosedAtOnceAndNoAiStepRuns() throws Exception {
        this.push(job(null));

        this.assertClosedAsFailed("Job 1196 has no task attached, so there is nothing to dispatch.", false);
        verifyNoInteractions(this.aiStepService, this.runCallbackTokens, this.kafkaTemplateProvider);
    }

    @Test
    void rowTwoNoTaskTypeIsClosedAtOnceAndNoAiStepRuns() throws Exception {
        this.push(job(task(null)));

        this.assertClosedAsFailed("Job 1196 has no task type configured, so there is no broker to dispatch it to.", false);
        verifyNoInteractions(this.aiStepService, this.runCallbackTokens, this.kafkaTemplateProvider);
    }

    @Test
    void rowThreeAnInactiveBrokerIsClosedAtOnceAndNoAiStepRuns() throws Exception {
        this.push(job(task(broker(Status.Inactive, "topic=etl.jobs&partitions=[*]"))));

        this.assertClosedAsFailed("Broker is not active for job 1196.", false);
        verifyNoInteractions(this.aiStepService, this.runCallbackTokens, this.kafkaTemplateProvider);
    }

    @Test
    void rowFourAnUnparsableBrokerIsClosedAtOnceAndNoAiStepRuns() throws Exception {
        this.push(job(task(broker(Status.Active, "topic=etl jobs&partitions=[1]"))));

        this.assertClosedAsFailed("Broker configuration is invalid for job 1196: topic=etl jobs&partitions=[1]", false);
        verifyNoInteractions(this.aiStepService, this.runCallbackTokens, this.kafkaTemplateProvider);
    }

    // ---- row 5: the AI step ------------------------------------------------------------------------------

    @Test
    void rowFiveAFailedAiStepIsClosedAtOnceAndNothingIsSent() throws Exception {
        AiStepService.Outcome failed = new AiStepService.Outcome(null, "AI step <summary> failed: Daily token budget reached");
        this.aiSteps(failed);

        this.push(dispatchable());

        this.assertClosedAsFailed("Job 1196: AI step <summary> failed: Daily token budget reached", false);
        verifyNoInteractions(this.runCallbackTokens, this.kafkaTemplateProvider, this.kafkaConnectionResolver);
    }

    /** The narrative first, then the verdict: a failed step's notes are in the history before the Failed. */
    @Test
    void everyNoteIsAuditLoggedBeforeTheFailureIsActedOn() throws Exception {
        AiStepService.Outcome failed = new AiStepService.Outcome(null, "AI step <risk> failed: timeout");
        failed.notes.add("AI step <summary>: Summarise v3 answered in 1.2 s (40 in, 12 out tokens).");
        failed.notes.add("AI step <codes>: reused the answer already recorded for this run.");
        this.aiSteps(failed);

        this.push(dispatchable());

        InOrder order = inOrder(this.bulkAction);
        order.verify(this.bulkAction).saveJobAuditLogs(QUEUE_ID, failed.notes.get(0));
        order.verify(this.bulkAction).saveJobAuditLogs(QUEUE_ID, failed.notes.get(1));
        order.verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Failed);
    }

    // ---- rows 6 and 7: transient, and retryable -------------------------------------------------------------

    /** The outer catch: here the run's token could not be saved, so nothing was sent. */
    @Test
    void rowSixAThrowInsideTheTryIsARetryableDispatchFailure() throws Exception {
        this.aiSteps(answered());
        when(this.runCallbackTokens.issue(this.run)).thenThrow(new IllegalStateException("could not save the token"));

        this.push(dispatchable());

        this.assertClosedAsFailed("Job 1196 could not be dispatched: could not save the token", true);
        verifyNoInteractions(this.kafkaTemplateProvider);
    }

    @Test
    void rowSevenABrokerThatRefusesTheSendIsARetryableHandOffFailure() throws Exception {
        this.aiSteps(answered());
        SettableListenableFuture<SendResult<String, String>> send = this.brokerIsReached();

        this.push(dispatchable());
        send.setException(new IllegalStateException("send failed", new IllegalStateException("Topic etl.jobs not present in metadata")));

        this.assertClosedAsFailed("Job 1196 could not be handed to the worker queue: Topic etl.jobs not present in metadata", true);
    }

    /** A send that throws before it returns a future is the same row, by the inner catch. */
    @Test
    void rowSevenAlsoCoversASendThatThrowsOutright() throws Exception {
        this.aiSteps(answered());
        when(this.kafkaTemplateProvider.getTemplate(any())).thenThrow(new IllegalStateException("Failed to construct kafka producer"));

        this.push(dispatchable());

        this.assertClosedAsFailed("Job 1196 could not be handed to the worker queue: Failed to construct kafka producer", true);
    }

    // ---- row 8: sent ------------------------------------------------------------------------------------------

    @Test
    void rowEightASentRunMovesToStart() throws Exception {
        this.aiSteps(answered());
        SettableListenableFuture<SendResult<String, String>> send = this.brokerIsReached();

        this.push(dispatchable());
        send.set(tookItAt(4200L));

        assertThat(this.run.isJobSend()).isTrue();
        assertThat(this.run.getJobStatus()).isEqualTo(JobStatus.Start);
        assertThat(this.run.getJobStatusMessage()).isEqualTo("Handed to the worker queue at offset 4200.");
        verify(this.transactionService).updateJobQueue(this.run);
        verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Start);
        verify(this.bulkAction).saveJobAuditLogs(QUEUE_ID, "Job 1196 handed to the worker queue at offset 4200.");
        verify(this.bulkAction).sendJobStatusNotification(JOB_ID);
        verify(this.bulkAction, never()).changeJobStatus(anyLong(), eq(JobStatus.Failed));
        verify(this.bulkAction, never()).scheduleRetry(any(JobQueue.class), any());
    }

    // ---- where the AI steps sit, and what they may change ---------------------------------------------------

    @Test
    void theAiStepsRunAfterTheBrokerChecksAndBeforeTheTokenAndTheSend() throws Exception {
        this.aiSteps(answered("AI step <summary>: Summarise v3 answered in 1.2 s (40 in, 12 out tokens)."));
        this.brokerIsReached();

        this.push(dispatchable());

        InOrder order = inOrder(this.aiStepService, this.bulkAction, this.runCallbackTokens, this.template);
        order.verify(this.aiStepService).apply(TENANT, "F1", QUEUE_ID, STORED);
        order.verify(this.bulkAction).saveJobAuditLogs(QUEUE_ID,
            "AI step <summary>: Summarise v3 answered in 1.2 s (40 in, 12 out tokens).");
        order.verify(this.runCallbackTokens).issue(this.run);
        order.verify(this.template).send(eq("etl.jobs"), anyString(), anyString());
    }

    @Test
    void theRewrittenDocumentIsSentAndTheStoredOneIsNeverTouched() throws Exception {
        this.aiSteps(answered());
        this.brokerIsReached();
        SourceJob job = dispatchable();

        this.push(job);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(this.template).send(eq("etl.jobs"), anyString(), message.capture());
        JsonObject sent = JsonParser.parseString(message.getValue()).getAsJsonObject();
        assertThat(sent.get("taskPayload").getAsString()).isEqualTo(ANSWERED);
        assertThat(job.getTaskDetail().getTaskPayload()).isEqualTo(STORED);
        verify(this.transactionService, never()).saveOrUpdateJob(any());
    }

    /** No TenantContext on the scheduler thread: the job's own tenant is handed over explicitly. */
    @Test
    void theJobsOwnTenantIsHandedToTheAiStepsNotTheContexts() throws Exception {
        TenantContext.set(OTHER_TENANT, "TENANT_ADMIN", 1L, "someone@example.com");
        this.aiSteps(new AiStepService.Outcome(null, "no"));

        this.push(dispatchable());

        verify(this.aiStepService).apply(TENANT, "F1", QUEUE_ID, STORED);
        verify(this.aiStepService, never()).apply(eq(OTHER_TENANT), any(), any(), any());
    }

    /**
     * The hazard the Outcome contract exists for. Were apply to throw instead of returning a failed
     * Outcome, the throw would land in pushMessageToQueue's outer catch: row 6, not row 5 -- retryable,
     * and reported as "could not be dispatched". A remote client at this seam that lets a timeout
     * escape would make exactly this change, which is why the test exists before the seam moves.
     */
    @Test
    void ifTheAiStepThrewItWouldBeMisreportedAsARetryableDispatchFailure() throws Exception {
        when(this.aiStepService.apply(TENANT, "F1", QUEUE_ID, STORED))
            .thenThrow(new IllegalStateException("model host unreachable"));

        this.push(dispatchable());

        this.assertClosedAsFailed("Job 1196 could not be dispatched: model host unreachable", true);
        verifyNoInteractions(this.runCallbackTokens, this.kafkaTemplateProvider);
    }
}

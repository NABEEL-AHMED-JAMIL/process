package process.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.emailer.EmailMessagesFactory;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.LookupData;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.service.impl.TransactionServiceImpl;
import process.util.ProcessUtil;
import process.security.RunCallbackTokens;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Two ways the dispatcher used to stop working quietly.
 *
 * The batch size came from QUEUE_FETCH_LIMIT via a bare Long.valueOf with no null check and no
 * parse guard, inside a method-wide catch -- so a missing row, a value typed "5,000", or the
 * row's "Store encrypted" box ticked stopped job dispatch platform-wide with one server-log line
 * and nothing on screen. And pushMessageToQueue put its whole body inside a check for the task
 * type with no else, so a run it could not dispatch was left in Queue untouched: no status, no
 * audit line, no notification. Because the dispatcher counts Queue rows when deciding whether a
 * job is busy, that one row made the job permanently "already in queue", and nothing recovers it
 * -- reconcileStalledRuns looks only at Start and Running, and both Run now and Skip next refuse
 * a job in Queue.
 */
@ExtendWith(MockitoExtension.class)
public class ProducerBulkEngineDispatchGuardTest {

    private static final long JOB_ID = 1196L;
    private static final long JOB_QUEUE_ID = 5073L;

    @Mock private BulkAction bulkAction;
    @Mock private TransactionServiceImpl transactionService;
    @Mock private EmailMessagesFactory emailMessagesFactory;
    @Mock private KafkaTemplateProvider kafkaTemplateProvider;
    @Mock private KafkaConnectionResolver kafkaConnectionResolver;
    @Mock private RunCallbackTokens runCallbackTokens;
    @Mock private process.ai.AiStepService aiStepService;

    private ProducerBulkEngine engine;

    @BeforeEach
    void setUp() {
        this.engine = new ProducerBulkEngine(this.bulkAction, this.transactionService,
            this.emailMessagesFactory, this.kafkaTemplateProvider, this.kafkaConnectionResolver,
            this.runCallbackTokens, this.aiStepService);
        // No AI steps on these pipelines: the document goes through as stored.
        org.mockito.Mockito.lenient().when(this.aiStepService.apply(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(inv -> new process.ai.AiStepService.Outcome(inv.getArgument(3), null));
    }

    private static LookupData lookupValued(String value) {
        LookupData lookupData = new LookupData();
        lookupData.setLookupType(ProcessUtil.QUEUE_FETCH_LIMIT);
        lookupData.setLookupValue(value);
        return lookupData;
    }

    private long limitUsedForTheFetch() {
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        verify(this.transactionService).findAllJobForTodayWithLimit(captor.capture(), any());
        return captor.getValue();
    }

    // ---- an unreadable QUEUE_FETCH_LIMIT must not stop the platform ---------------------------

    @Test
    void aValidLimitIsUsedAsGiven() {
        when(this.transactionService.findByLookupType(ProcessUtil.QUEUE_FETCH_LIMIT))
            .thenReturn(lookupValued("5000"));
        when(this.transactionService.findAllJobForTodayWithLimit(anyLong(), any()))
            .thenReturn(Collections.emptyList());

        this.engine.startJobInCurrentTimeSlot();

        assertThat(this.limitUsedForTheFetch()).isEqualTo(5000L);
    }

    /** "5,000" is what an admin types when they mean five thousand. */
    @Test
    void anUnparsableLimitFallsBackInsteadOfKillingTheCycle() {
        when(this.transactionService.findByLookupType(ProcessUtil.QUEUE_FETCH_LIMIT))
            .thenReturn(lookupValued("5,000"));
        when(this.transactionService.findAllJobForTodayWithLimit(anyLong(), any()))
            .thenReturn(Collections.emptyList());

        this.engine.startJobInCurrentTimeSlot();

        // The pass still happened; the old code threw before the fetch and dispatched nothing,
        // for ever, every minute.
        assertThat(this.limitUsedForTheFetch()).isPositive();
    }

    /** Ciphertext, from ticking "Store encrypted" on a row the engine reads directly. */
    @Test
    void anEncryptedLimitFallsBackInsteadOfKillingTheCycle() {
        when(this.transactionService.findByLookupType(ProcessUtil.QUEUE_FETCH_LIMIT))
            .thenReturn(lookupValued("Xy8aQ2dF9k1LmNpQrStUvW=="));
        when(this.transactionService.findAllJobForTodayWithLimit(anyLong(), any()))
            .thenReturn(Collections.emptyList());

        this.engine.startJobInCurrentTimeSlot();

        assertThat(this.limitUsedForTheFetch()).isPositive();
    }

    /** The row deleted or renamed out from under findByLookupType. */
    @Test
    void aMissingLookupFallsBackInsteadOfKillingTheCycle() {
        when(this.transactionService.findByLookupType(ProcessUtil.QUEUE_FETCH_LIMIT)).thenReturn(null);
        when(this.transactionService.findAllJobForTodayWithLimit(anyLong(), any()))
            .thenReturn(Collections.emptyList());

        this.engine.startJobInCurrentTimeSlot();

        assertThat(this.limitUsedForTheFetch()).isPositive();
    }

    /** Zero or a negative would fetch nothing at all, which is the same outage by another route. */
    @Test
    void aZeroLimitFallsBackRatherThanFetchingNothing() {
        when(this.transactionService.findByLookupType(ProcessUtil.QUEUE_FETCH_LIMIT))
            .thenReturn(lookupValued("0"));
        when(this.transactionService.findAllJobForTodayWithLimit(anyLong(), any()))
            .thenReturn(Collections.emptyList());

        this.engine.startJobInCurrentTimeSlot();

        assertThat(this.limitUsedForTheFetch()).isPositive();
    }

    // ---- an undispatchable run is closed, not abandoned ----------------------------------------

    private void pushMessageToQueue(SourceJob sourceJob, JobQueue jobQueue) throws Exception {
        Method method = ProducerBulkEngine.class.getDeclaredMethod(
            "pushMessageToQueue", SourceJob.class, JobQueue.class);
        method.setAccessible(true);
        method.invoke(this.engine, sourceJob, jobQueue);
    }

    private static JobQueue queuedRun() {
        JobQueue jobQueue = new JobQueue();
        jobQueue.setJobQueueId(JOB_QUEUE_ID);
        jobQueue.setJobId(JOB_ID);
        jobQueue.setJobStatus(JobStatus.Queue);
        return jobQueue;
    }

    private void assertTheRunWasClosedWithAReason() {
        verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Failed);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(this.bulkAction).changeJobQueueStatus(eq(JOB_QUEUE_ID), eq(JobStatus.Failed), message.capture());
        // The operator has to be able to read why, and the id has to be in it.
        assertThat(message.getValue()).contains(String.valueOf(JOB_ID));
        verify(this.bulkAction).saveJobAuditLogs(eq(JOB_QUEUE_ID), anyString());
        verify(this.bulkAction).changeJobQueueEndDate(eq(JOB_QUEUE_ID), any());
        verify(this.bulkAction).sendJobStatusNotification(JOB_ID);
    }

    @Test
    void aTaskWithNoTaskTypeClosesTheRunRatherThanStrandingIt() throws Exception {
        SourceJob sourceJob = new SourceJob();
        sourceJob.setJobId(JOB_ID);
        sourceJob.setJobStatus(Status.Active);
        SourceTask sourceTask = new SourceTask();
        sourceTask.setTaskDetailId(4200L);
        sourceTask.setSourceTaskType(null);
        sourceJob.setTaskDetail(sourceTask);
        when(this.transactionService.findByJobId(JOB_ID)).thenReturn(Optional.empty());

        this.pushMessageToQueue(sourceJob, queuedRun());

        this.assertTheRunWasClosedWithAReason();
    }

    @Test
    void aJobWithNoTaskAtAllClosesTheRunRatherThanThrowing() throws Exception {
        SourceJob sourceJob = new SourceJob();
        sourceJob.setJobId(JOB_ID);
        sourceJob.setJobStatus(Status.Active);
        sourceJob.setTaskDetail(null);
        when(this.transactionService.findByJobId(JOB_ID)).thenReturn(Optional.empty());

        this.pushMessageToQueue(sourceJob, queuedRun());

        this.assertTheRunWasClosedWithAReason();
    }

}

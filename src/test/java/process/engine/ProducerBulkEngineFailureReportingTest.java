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
import process.model.pojo.Scheduler;
import process.model.pojo.SourceJob;
import process.model.service.impl.TransactionServiceImpl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * What the engine says about a run it could not dispatch, and what it says about one nobody
 * asked it to make.
 *
 * changeStatusForLastJob used to take a template and run String.format over it with the job id.
 * That suited the callers handing it a literal with one %s in it and was a trap for the one that
 * does not: handleSendFailure builds its message first, because it has the payload and the
 * broker's own error to put in it, and that payload carries the task's raw XML. A single literal
 * '%' anywhere in a task payload -- a threshold like "rate &gt; 10%", a URL-encoded path segment
 * -- made String.format throw UnknownFormatConversionException from the first line of the one
 * method whose job is to record why a run failed. The throw escaped through the Kafka callback,
 * so no status was changed, no audit line written and no notification sent: the run stayed in
 * Queue with no reason recorded, at the head of a capped fetch, and because Queue rows are what
 * getCountForInQueueJobByJobId counts, the job was treated as permanently busy from then on.
 *
 * Skipping is the other half. The one-argument sendJobStatusNotification means "a new outcome
 * transition just happened", and notifyJobOutcome answers it by reading
 * source_job.job_running_status. A skip moves no such thing -- it writes a Skip row against the
 * run that was due and leaves the job holding the PREVIOUS run's outcome -- so skipping a job
 * whose last run had finished raised a second "Job completed" in the notification centre, dated
 * to the moment the operator chose not to run it.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class ProducerBulkEngineFailureReportingTest {

    private static final long JOB_ID = 1196L;
    private static final long JOB_QUEUE_ID = 5073L;

    @Mock private BulkAction bulkAction;
    @Mock private TransactionServiceImpl transactionService;
    @Mock private EmailMessagesFactory emailMessagesFactory;
    @Mock private KafkaTemplateProvider kafkaTemplateProvider;
    @Mock private KafkaConnectionResolver kafkaConnectionResolver;

    private ProducerBulkEngine engine;

    @BeforeEach
    void setUp() {
        this.engine = new ProducerBulkEngine(this.bulkAction, this.transactionService,
            this.emailMessagesFactory, this.kafkaTemplateProvider, this.kafkaConnectionResolver);
    }

    private static SourceJob activeJob() {
        SourceJob sourceJob = new SourceJob();
        sourceJob.setJobId(JOB_ID);
        sourceJob.setJobStatus(Status.Active);
        return sourceJob;
    }

    private static JobQueue queuedRun() {
        JobQueue jobQueue = new JobQueue();
        jobQueue.setJobQueueId(JOB_QUEUE_ID);
        jobQueue.setJobId(JOB_ID);
        jobQueue.setJobStatus(JobStatus.Queue);
        return jobQueue;
    }

    /**
     * The callback path, reached directly. In production this runs inside Kafka's own listener,
     * which is precisely why an exception thrown here went nowhere anybody could see it.
     */
    private void handleSendFailure(Throwable cause, String payload, SourceJob sourceJob,
        JobQueue jobQueue) throws Exception {
        Method method = ProducerBulkEngine.class.getDeclaredMethod("handleSendFailure",
            Throwable.class, String.class, SourceJob.class, JobQueue.class);
        method.setAccessible(true);
        try {
            method.invoke(this.engine, cause, payload, sourceJob, jobQueue);
        } catch (InvocationTargetException wrapper) {
            // Unwrapped deliberately: the reflection wrapper hides which exception escaped, and
            // which exception escaped is the whole subject of this test.
            Throwable actual = wrapper.getCause();
            throw actual instanceof Exception ? (Exception) actual : new IllegalStateException(actual);
        }
    }

    // ---- a payload the message cannot be formatted with ---------------------------------------

    @Test
    void aPercentSignInATaskPayloadStillLeavesTheRunClosedWithItsReason() throws Exception {
        // Both of the shapes that actually occur: a percentage in a task parameter, and a
        // URL-encoded path segment. Either one is enough on its own.
        String payload = "{\"taskPayload\":\"<filter>rate &gt; 10% </filter>\","
            + "\"inputFolder\":\"etl%2Fin\"}";
        when(this.transactionService.findByJobId(JOB_ID)).thenReturn(Optional.empty());

        this.handleSendFailure(new IllegalStateException("broker down"), payload, activeJob(), queuedRun());

        verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Failed);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(this.bulkAction).changeJobQueueStatus(eq(JOB_QUEUE_ID), eq(JobStatus.Failed), message.capture());
        // The run is closed WITH ITS REASON, which is what this test is for: a '%' anywhere in the
        // payload used to make String.format throw out of the one method whose job is to record
        // why a run failed, leaving it in Queue with nothing written down.
        //
        // The payload itself is deliberately NOT in here any more. jobStatusMessage is rendered in
        // the Recent runs list, and four hundred characters of escaped task XML pushed the reason
        // past the end of a two-line clamp. It is written to the application log instead.
        assertThat(message.getValue())
            .contains(String.valueOf(JOB_ID))
            .contains("broker down")
            .doesNotContain("rate &gt; 10% ")
            .doesNotContain("etl%2Fin");
        verify(this.bulkAction).saveJobAuditLogs(eq(JOB_QUEUE_ID), anyString());
        verify(this.bulkAction).changeJobQueueEndDate(eq(JOB_QUEUE_ID), any());
        verify(this.bulkAction).sendJobStatusNotification(JOB_ID);
    }

    /** A payload with no '%' in it takes the same path, and reports the same way. */
    @Test
    void anOrdinaryPayloadIsLeftOutOfTheMessageJustTheSame() throws Exception {
        when(this.transactionService.findByJobId(JOB_ID)).thenReturn(Optional.empty());

        this.handleSendFailure(new IllegalStateException("timeout"), "{\"taskPayload\":\"<a/>\"}",
            activeJob(), queuedRun());

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(this.bulkAction).changeJobQueueStatus(eq(JOB_QUEUE_ID), eq(JobStatus.Failed), message.capture());
        assertThat(message.getValue()).contains("timeout").doesNotContain("<a/>");
    }

    @Test
    void theReasonIsTheROOTCauseSentence_notTheWrapperAndNotItsClassName() throws Exception {
        // What a Kafka send failure actually looks like: a wrapper whose own message is vaguer
        // than the thing that went wrong. Reported as "Job 2417 unable to send message=[{...400
        // characters...}] due to: Failed to construct kafka producer", the only actionable half
        // was off the end of the line.
        when(this.transactionService.findByJobId(JOB_ID)).thenReturn(Optional.empty());
        Throwable wrapped = new IllegalStateException("send failed",
            new IllegalArgumentException("No resolvable bootstrap urls given in bootstrap.servers"));

        this.handleSendFailure(wrapped, "{\"taskPayload\":\"<a/>\"}", activeJob(), queuedRun());

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(this.bulkAction).changeJobQueueStatus(eq(JOB_QUEUE_ID), eq(JobStatus.Failed), message.capture());
        assertThat(message.getValue())
            .contains("No resolvable bootstrap urls")
            .doesNotContain("IllegalArgumentException");
    }

    @Test
    void aCauseWithNoMessageAtAllStillNamesSomething() throws Exception {
        // Otherwise the run reads "Could not hand this run to the worker queue: null", which is
        // worse than the dump it replaced.
        when(this.transactionService.findByJobId(JOB_ID)).thenReturn(Optional.empty());

        this.handleSendFailure(new IllegalStateException(), "{}", activeJob(), queuedRun());

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(this.bulkAction).changeJobQueueStatus(eq(JOB_QUEUE_ID), eq(JobStatus.Failed), message.capture());
        assertThat(message.getValue()).contains("IllegalStateException").doesNotContain("null");
    }

    // ---- skipping a run is not an outcome ------------------------------------------------------

    @Test
    void skippingTheNextRunDoesNotAnnounceThePreviousRunsOutcomeAgain() {
        Scheduler scheduler = new Scheduler();
        scheduler.setJobId(JOB_ID);
        scheduler.setNextRunAt(LocalDateTime.now().plusHours(1));
        JobQueue skipRow = new JobQueue();
        skipRow.setJobQueueId(JOB_QUEUE_ID);
        skipRow.setJobId(JOB_ID);
        skipRow.setJobStatus(JobStatus.Skip);
        when(this.bulkAction.createJobQueueV1(eq(JOB_ID), any(), eq(JobStatus.Skip), anyString(), eq(true)))
            .thenReturn(skipRow);
        when(this.transactionService.findByJobId(JOB_ID)).thenReturn(Optional.empty());

        this.engine.skipManualJobInQueue(scheduler);

        // The browser still gets its state push; what it must not do is claim a fresh transition,
        // because the job's running status still holds the last run's verdict.
        verify(this.bulkAction).sendJobStatusNotification(JOB_ID, false);
        verify(this.bulkAction, never()).sendJobStatusNotification(JOB_ID);
    }

    /** Running a job by hand IS a transition, so the announcement must not have been lost. */
    @Test
    void runningAJobByHandIsStillAnnouncedAsANewTransition() {
        JobQueue queueRow = queuedRun();
        when(this.bulkAction.createJobQueueV1(eq(JOB_ID), any(), eq(JobStatus.Queue), anyString(), eq(false)))
            .thenReturn(queueRow);

        this.engine.addManualJobInQueue(activeJob());

        verify(this.bulkAction).sendJobStatusNotification(JOB_ID);
    }
}

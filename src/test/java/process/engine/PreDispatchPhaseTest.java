package process.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionOperations;
import process.ai.AiStepService;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.pojo.SourceTaskType;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;

import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-134: every run the pre-dispatch phase takes leaves it prepared or closed -- never neither.
 *
 * A run that is neither dispatched nor closed permanently disables its job, because the dispatcher
 * counts Queue, Start and Running to decide the job is busy; a pre-dispatch phase that left a row in
 * Queue while AI ran asynchronously would re-create exactly that. So each way the decision can go --
 * including the ones nobody planned for, an Error out of the AI seam -- is driven here, and for every one
 * exactly one of two things happens: the run is handed to the dispatcher (markPrepared), or it is closed
 * (a Failed, or a retry the job's own policy grants).
 */
@ExtendWith(MockitoExtension.class)
class PreDispatchPhaseTest {

    private static final long TENANT = 2905L;
    private static final long JOB_ID = 1196L;
    private static final long QUEUE_ID = 5073L;
    private static final String STORED = "<pipeline><document>notes</document></pipeline>";

    @Mock private BulkAction bulkAction;
    @Mock private TransactionServiceImpl transactionService;
    @Mock private JobMail jobMail;
    @Mock private AiStepService aiStepService;

    private PreDispatchPhase phase;
    private JobQueue run;

    @BeforeEach
    void setUp() {
        this.phase = new PreDispatchPhase(this.transactionService, this.bulkAction, this.aiStepService, this.jobMail,
            TransactionOperations.withoutTransaction(), new DispatchPipeline.SameThread());
        this.run = new JobQueue();
        this.run.setJobQueueId(QUEUE_ID);
        this.run.setJobId(JOB_ID);
        this.run.setJobStatus(JobStatus.Queue);
        lenient().when(this.transactionService.markPrepared(anyLong(), any(), any(), any())).thenReturn(1);
    }

    private static SourceJob job(Consumer<SourceTask> shape) {
        SourceTaskType type = new SourceTaskType();
        type.setSourceTaskTypeId(31L);
        type.setStatus(Status.Active);
        type.setQueueTopicPartition("topic=etl.jobs&partitions=[*]");
        SourceTask task = new SourceTask();
        task.setPipelineId("F1");
        task.setTaskPayload(STORED);
        task.setSourceTaskType(type);
        shape.accept(task);
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID);
        job.setTenantId(TENANT);
        job.setJobStatus(Status.Active);
        job.setTaskDetail(task);
        return job;
    }

    private int preparedCount() {
        return (int) mockingDetails(this.transactionService).getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals("markPrepared")).count();
    }

    /** Closed: offered a retry (which the job's own policy decides), or written Failed. */
    private boolean closed() {
        return mockingDetails(this.bulkAction).getInvocations().stream()
            .anyMatch(invocation -> invocation.getMethod().getName().equals("scheduleRetry")
                || (invocation.getMethod().getName().equals("changeJobQueueStatus")
                    && JobStatus.Failed.equals(invocation.getArguments()[1])));
    }

    private void assertPreparedXorClosed(String what) {
        assertThat(this.preparedCount()).as(what + ": handed over at most once").isLessThanOrEqualTo(1);
        assertThat(this.preparedCount() == 1 ^ this.closed()).as(what + ": prepared or closed, not both, not neither").isTrue();
    }

    @Test
    void aRunThatCanBeSentIsHandedToTheDispatcherWithItsDocument() {
        when(this.aiStepService.apply(TENANT, "F1", QUEUE_ID, STORED)).thenReturn(new AiStepService.Outcome("<answered/>", null));

        this.phase.prepare(Optional.of(job(task -> { })), this.run);

        verify(this.transactionService).markPrepared(eq(QUEUE_ID), eq("<answered/>"), any(), eq(this.run.getCorrelationId()));
        assertThat(this.run.getCorrelationId()).as("stamped for the dispatch and the callbacks to carry").isNotNull();
        this.assertPreparedXorClosed("sendable");
    }

    @Test
    void aJobDeletedOrDeactivatedSinceItWasQueuedIsClosed() {
        this.phase.prepare(Optional.empty(), this.run);

        verify(this.bulkAction).changeJobQueueStatus(QUEUE_ID, JobStatus.Failed,
            "Job 1196 failed in the queue because the main job is deleted or inactive.");
        this.assertPreparedXorClosed("no active job");
    }

    @Test
    void everyConfigurationFaultIsClosed() {
        this.phase.prepare(Optional.of(job(task -> task.setSourceTaskType(null))), this.run);
        this.assertPreparedXorClosed("no task type");
    }

    @Test
    void aFailedAiStepIsClosed() {
        when(this.aiStepService.apply(TENANT, "F1", QUEUE_ID, STORED)).thenReturn(new AiStepService.Outcome(null, "AI step <s> failed: x"));

        this.phase.prepare(Optional.of(job(task -> { })), this.run);

        this.assertPreparedXorClosed("failed step");
        verify(this.transactionService, never()).markPrepared(anyLong(), any(), any(), any());
    }

    @Test
    void anExceptionFromTheAiSeamIsClosedAsRetryable() {
        when(this.aiStepService.apply(TENANT, "F1", QUEUE_ID, STORED)).thenThrow(new IllegalStateException("model host unreachable"));

        this.phase.prepare(Optional.of(job(task -> { })), this.run);

        verify(this.bulkAction).scheduleRetry(this.run, "Job 1196 could not be dispatched: model host unreachable");
        this.assertPreparedXorClosed("exception");
    }

    /** Not only exceptions: an Error out of the seam must not leave the run in Queue either. */
    @Test
    void anErrorFromTheAiSeamIsClosedToo() {
        when(this.aiStepService.apply(TENANT, "F1", QUEUE_ID, STORED)).thenThrow(new StackOverflowError());

        this.phase.prepare(Optional.of(job(task -> { })), this.run);

        verify(this.bulkAction).scheduleRetry(this.run, "Job 1196 could not be dispatched: StackOverflowError");
        this.assertPreparedXorClosed("error");
    }

    /**
     * The one way out that is neither: the database refuses the outcome itself. The run keeps its lease,
     * which runs out, and the next pass takes it again -- the phase does not pretend it closed it.
     */
    @Test
    void anOutcomeThatCannotBeWrittenIsLeftToTheLease() {
        when(this.aiStepService.apply(TENANT, "F1", QUEUE_ID, STORED)).thenReturn(new AiStepService.Outcome("<answered/>", null));
        when(this.transactionService.markPrepared(anyLong(), any(), any(), any())).thenThrow(new IllegalStateException("connection lost"));

        this.phase.prepare(Optional.of(job(task -> { })), this.run);

        verify(this.bulkAction, never()).changeJobQueueStatus(anyLong(), any(), anyString());
        verify(this.bulkAction, never()).scheduleRetry(any(JobQueue.class), anyString());
    }
}

package process.engine;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import process.ai.AiStepService;
import process.api.SourceJobRestApi;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobDto;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.pojo.Scheduler;
import process.model.service.SourceJobService;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.security.RunCallbackTokens;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-113 / MIG-135 (P12): one in-flight run per job is a database constraint, and losing the race
 * to it is a business outcome, not a 500.
 *
 * getCountForInQueueJobByJobId stays as the cheap pre-filter; ux_job_queue_one_in_flight_per_job is the
 * enforcement. When two enqueuers both see a count of 0, the second insert is refused by the index,
 * and the enqueuer then does exactly what a positive count made it do: a Skip row, "already in queue".
 * The index itself, the resolution of old duplicates and the race are run against Postgres in
 * OneRunInFlightPostgresTest.
 */
@ExtendWith(MockitoExtension.class)
class OneRunInFlightTest {

    private static final long JOB_ID = 1196L;

    @Mock private BulkAction bulkAction;
    @Mock private TransactionServiceImpl transactionService;
    @Mock private JobMail jobMail;
    @Mock private KafkaTemplateProvider kafkaTemplateProvider;
    @Mock private KafkaConnectionResolver kafkaConnectionResolver;
    @Mock private RunCallbackTokens runCallbackTokens;
    @Mock private AiStepService aiStepService;

    private ProducerBulkEngine engine;

    @BeforeEach
    void setUp() {
        this.engine = new ProducerBulkEngine(this.bulkAction, this.transactionService, this.jobMail,
            this.kafkaTemplateProvider, this.kafkaConnectionResolver, this.runCallbackTokens, this.aiStepService);
    }

    /** What Spring hands back when the index refuses an insert, root cause and all. */
    static DataIntegrityViolationException refusedByTheIndex() {
        SQLException root = new SQLException("ERROR: duplicate key value violates unique constraint "
            + "\"ux_job_queue_one_in_flight_per_job\"\n  Detail: Key (job_id)=(1196) already exists.", "23505");
        return new DataIntegrityViolationException("could not execute statement",
            new ConstraintViolationException("could not execute statement", root, "ux_job_queue_one_in_flight_per_job"));
    }

    /**
     * One due slot, claimable until the pass gives up on it or advances it -- as the SKIP LOCKED claim
     * behaves: a slot whose transaction rolled back is still due, and is claimed again.
     */
    private Scheduler due() {
        Scheduler scheduler = new Scheduler();
        scheduler.setSchedulerId(501L);
        scheduler.setJobId(JOB_ID);
        boolean[] advanced = {false};
        lenient().doAnswer(inv -> advanced[0] = true).when(this.bulkAction).updateNextScheduler(scheduler);
        when(this.transactionService.claimNextDueScheduler(any(), any())).thenAnswer(inv -> {
            List<Long> passed = inv.getArgument(1);
            return advanced[0] || passed.contains(501L) ? Optional.empty() : Optional.of(scheduler);
        });
        return scheduler;
    }

    // ---- recognising the index ------------------------------------------------------------------------------

    @Test
    void theIndexsOwnViolationIsRecognisedAndNothingElseIs() {
        assertThat(OneRunInFlight.isViolation(refusedByTheIndex())).isTrue();
        assertThat(OneRunInFlight.isViolation(new DataIntegrityViolationException("x",
            new SQLException("duplicate key value violates unique constraint \"uk_scheduler_job_id\"", "23505")))).isFalse();
        assertThat(OneRunInFlight.isViolation(new DataIntegrityViolationException("x",
            new SQLException("null value in column \"job_id\" violates not-null constraint", "23502")))).isFalse();
        assertThat(OneRunInFlight.isViolation(new IllegalStateException("connection refused"))).isFalse();
        assertThat(OneRunInFlight.isViolation(null)).isFalse();
    }

    // ---- the enqueuer ----------------------------------------------------------------------------------------

    /**
     * The count saw nothing, the index saw the other enqueuer's row. The slot's transaction rolls back
     * whole, and the slot is claimed again: this time the count sees the winner, and the outcome is the
     * count's "busy" -- a Skip, not an error.
     */
    @Test
    void anEnqueuerThatLosesTheRaceToTheIndexRecordsASkipNotAnError() {
        Scheduler scheduler = this.due();
        when(this.bulkAction.getCountForInQueueJobByJobId(JOB_ID)).thenReturn(0, 1);
        when(this.bulkAction.createJobQueue(eq(JOB_ID), any(), eq(JobStatus.Queue), anyString(), eq(false)))
            .thenThrow(refusedByTheIndex());
        JobQueue skip = new JobQueue();
        skip.setJobQueueId(77L);
        skip.setJobId(JOB_ID);
        when(this.bulkAction.createJobQueue(eq(JOB_ID), any(), eq(JobStatus.Skip), eq("Job %s skip, already in queue."), eq(true)))
            .thenReturn(skip);

        this.engine.addJobInQueue();

        verify(this.bulkAction).saveJobAuditLogs(77L, "Job 1196 skip, already in queue.");
        verify(this.bulkAction, never()).changeJobStatus(anyLong(), eq(JobStatus.Queue));
        verify(this.bulkAction, never()).changeJobLastJobRun(anyLong(), any());
        verify(this.bulkAction).updateNextScheduler(scheduler);
        verify(this.bulkAction).sendJobStatusNotification(JOB_ID, false);
    }

    /** A slot the index refuses twice in one pass is left for the next tick: the loop never spins on it. */
    @Test
    void aSlotRefusedAgainIsLeftForTheNextTick() {
        this.due();
        when(this.bulkAction.getCountForInQueueJobByJobId(JOB_ID)).thenReturn(0);
        when(this.bulkAction.createJobQueue(eq(JOB_ID), any(), eq(JobStatus.Queue), anyString(), eq(false)))
            .thenThrow(refusedByTheIndex());

        this.engine.addJobInQueue();

        verify(this.bulkAction, times(2)).createJobQueue(eq(JOB_ID), any(), eq(JobStatus.Queue), anyString(), eq(false));
        verify(this.bulkAction, never()).updateNextScheduler(any());
    }

    /**
     * The run row first, then the job's status. The other order let a refused insert leave the job
     * reading Queue while its real run -- the one that won -- was Start or Running.
     */
    @Test
    void theRunRowIsWrittenBeforeTheJobIsMarkedQueued() {
        this.due();
        when(this.bulkAction.getCountForInQueueJobByJobId(JOB_ID)).thenReturn(0);
        JobQueue queued = new JobQueue();
        queued.setJobQueueId(78L);
        queued.setJobId(JOB_ID);
        queued.setStartTime(LocalDateTime.of(2026, 9, 24, 12, 0));
        when(this.bulkAction.createJobQueue(eq(JOB_ID), any(), eq(JobStatus.Queue), anyString(), eq(false))).thenReturn(queued);

        this.engine.addJobInQueue();

        InOrder order = inOrder(this.bulkAction);
        order.verify(this.bulkAction).createJobQueue(eq(JOB_ID), any(), eq(JobStatus.Queue), anyString(), eq(false));
        order.verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Queue);
        order.verify(this.bulkAction).changeJobLastJobRun(JOB_ID, queued.getStartTime());
        order.verify(this.bulkAction).sendJobStatusNotification(JOB_ID, true);
    }

    /** Anything else the insert throws is an error for this slot, not a quiet skip -- and not retried in this pass. */
    @Test
    void anyOtherFailureIsNotMistakenForBusy() {
        this.due();
        when(this.bulkAction.getCountForInQueueJobByJobId(JOB_ID)).thenReturn(0);
        when(this.bulkAction.createJobQueue(eq(JOB_ID), any(), eq(JobStatus.Queue), anyString(), eq(false)))
            .thenThrow(new IllegalStateException("connection refused"));

        this.engine.addJobInQueue();

        verify(this.bulkAction, times(1)).createJobQueue(eq(JOB_ID), any(), eq(JobStatus.Queue), anyString(), eq(false));
        verify(this.bulkAction, never()).createJobQueue(anyLong(), any(), eq(JobStatus.Skip), anyString(), anyBoolean());
    }

    // ---- Run now -----------------------------------------------------------------------------------------------

    /**
     * Run now checks the job's own status first; an operator racing the enqueuer (or another operator)
     * past that check meets the index at commit, and is told what the check would have told them.
     */
    @Test
    void runNowThatLosesTheRaceIsToldTheRunIsInFlight() throws Exception {
        SourceJobService service = mock(SourceJobService.class);
        when(service.runSourceJob(any())).thenThrow(refusedByTheIndex());
        SourceJobRestApi api = new SourceJobRestApi(service, null, null);
        SourceJobDto request = new SourceJobDto();
        request.setJobId(JOB_ID);

        ResponseEntity<?> response = api.runSourceJob(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((ResponseDto) response.getBody()).getStatus()).isEqualTo("ERROR");
        assertThat(((ResponseDto) response.getBody()).getMessage())
            .isEqualTo("A job can't be run while its last run is still in flight ('Queue', 'Start', 'Running').");
    }
}

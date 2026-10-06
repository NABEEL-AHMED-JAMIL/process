package process.engine;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import process.engine.cron.ProcessCron;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.pojo.SourceTaskType;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.outbox.DispatchOutbox;
import process.security.RunCallbackTokens;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * MIG-136: the four coupled dispatch values, re-derived for the phase structure, and the recorded
 * incident they exist for -- a 5000-row pass that outlived its lock and let a second instance dispatch
 * the same rows twice.
 *
 * The pass runs here on a fake clock: the per-row pause advances it instead of sleeping, and a row's
 * dispatch transaction can be made to take as long as its timeout allows. Whatever the rows cost, the
 * pass must stop starting them at the budget, so that the last one ends inside the lock.
 */
@ExtendWith(MockitoExtension.class)
class DispatchTimingTest {

    private static final Duration LOCK = Duration.ofMinutes(10);

    @Mock private BulkAction bulkAction;
    @Mock private TransactionServiceImpl transactionService;
    @Mock private JobMail jobMail;
    @Mock private RunCallbackTokens runCallbackTokens;
    @Mock private DispatchOutbox outbox;

    private final AtomicLong now = new AtomicLong();
    private ProducerBulkEngine engine;

    @BeforeEach
    void setUp() {
        this.engine = new ProducerBulkEngine(this.bulkAction, this.transactionService, this.jobMail,
            this.runCallbackTokens, this.outbox);
        this.engine.useClock(this.now::get, this.now::addAndGet);
    }

    private static SourceJob job(long jobId) {
        SourceTaskType type = new SourceTaskType();
        type.setSourceTaskTypeId(31L);
        type.setStatus(Status.Active);
        type.setQueueTopicPartition("topic=etl.jobs&partitions=[*]");
        SourceTask task = new SourceTask();
        task.setSourceTaskType(type);
        task.setTaskPayload("<p/>");
        SourceJob job = new SourceJob();
        job.setJobId(jobId);
        job.setTenantId(2905L);
        job.setJobStatus(Status.Active);
        job.setTaskDetail(task);
        return job;
    }

    /** A full 5000-row fetch of prepared runs. */
    private void fiveThousandPreparedRuns() {
        List<JobQueue> rows = new ArrayList<>();
        for (long id = 1; id <= 5000; id++) {
            JobQueue run = new JobQueue();
            run.setJobQueueId(id);
            run.setJobId(id);
            run.setJobStatus(JobStatus.Queue);
            run.setPreparedAt(LocalDateTime.now());
            run.setDispatchPayload("<p/>");
            rows.add(run);
        }
        when(this.transactionService.findOrchestrationSetting(any())).thenReturn("5000");
        when(this.transactionService.findAllJobForTodayWithLimit(anyLong(), any())).thenReturn(rows);
        lenient().when(this.transactionService.findByJobIdAndJobStatus(anyLong(), any()))
            .thenAnswer(inv -> Optional.of(job(inv.getArgument(0))));
    }

    private int written() {
        return (int) mockingDetails(this.outbox).getInvocations().stream()
            .filter(invocation -> invocation.getMethod().getName().equals("write")).count();
    }

    /** Rows that cost only their pause: 4200 fit in seven minutes, the other 800 are the next pass's. */
    @Test
    void aFiveThousandRowPassStopsAtTheBudgetWhenRowsAreCheap() {
        this.fiveThousandPreparedRuns();

        this.engine.startJobInCurrentTimeSlot();

        assertThat(this.written()).isBetween(4199, 4201);
        assertThat(Duration.ofMillis(this.now.get())).isLessThan(LOCK);
    }

    /** Rows that each take their whole transaction timeout: still over before the lock. */
    @Test
    void aFiveThousandRowPassEndsInsideTheLockEvenWhenEveryRowTakesItsWholeTimeout() {
        this.fiveThousandPreparedRuns();
        doAnswer(inv -> this.now.addAndGet(Duration.ofSeconds(DispatchTiming.ROW_TRANSACTION_TIMEOUT_SECONDS).toMillis()))
            .when(this.outbox).write(any());

        this.engine.startJobInCurrentTimeSlot();

        assertThat(this.written()).isLessThan(5000);
        assertThat(Duration.ofMillis(this.now.get())).as("when the last row the pass started finished").isLessThan(LOCK);
    }

    /** The coupling itself, read from the one place it is written and from the annotation that uses it. */
    @Test
    void theLastRowAPassStartsEndsInsideTheLockItRunsUnder() throws Exception {
        SchedulerLock lock = ProcessCron.class.getMethod("startJobInCurrentTimeSlot").getAnnotation(SchedulerLock.class);
        assertThat(lock.lockAtMostFor()).isEqualTo(DispatchTiming.DISPATCH_LOCK_AT_MOST_FOR);
        assertThat(DispatchTiming.DISPATCH_LOCK).isEqualTo(LOCK);

        Duration lastRowEnds = Duration.ofMillis(DispatchTiming.DISPATCH_BUDGET_MS + DispatchTiming.PER_ROW_PAUSE_MS)
            .plusSeconds(DispatchTiming.ROW_TRANSACTION_TIMEOUT_SECONDS);
        assertThat(lastRowEnds).isLessThan(DispatchTiming.DISPATCH_LOCK);
        assertThat(DispatchTiming.DEFAULT_QUEUE_FETCH_LIMIT).isEqualTo(1000L);
        assertThat(DispatchTiming.PER_ROW_PAUSE_MS).isEqualTo(100L);
    }

    /** And the transaction timeout is really set on the dispatcher the application builds. */
    @Test
    void theApplicationsDispatcherBoundsEachRowsTransaction() {
        ProducerBulkEngine wired = new ProducerBulkEngine(this.bulkAction, this.transactionService, this.jobMail,
            this.runCallbackTokens, this.outbox, mock(PlatformTransactionManager.class));

        Object transactions = ReflectionTestUtils.getField(wired, "transactions");

        assertThat(transactions).isInstanceOf(TransactionTemplate.class);
        assertThat(((TransactionTemplate) transactions).getTimeout()).isEqualTo(DispatchTiming.ROW_TRANSACTION_TIMEOUT_SECONDS);
    }
}

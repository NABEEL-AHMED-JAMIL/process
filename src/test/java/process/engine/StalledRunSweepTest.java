package process.engine;

import process.util.BusinessTime;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Query;
import org.springframework.scheduling.annotation.Scheduled;
import process.engine.cron.ProcessCron;
import process.model.enums.JobStatus;
import process.model.pojo.JobQueue;
import process.model.repository.JobQueueRepository;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.JobMail;
import process.security.RunCallbackTokens;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C4 (MIG-137): the stall sweep closes a run as Interrupt -- never Failed, never Completed -- and
 * only sometimes touches the job row.
 *
 * "A stalled run failed" is the obvious reading and it is wrong. The rule, verbatim from
 * ProducerBulkEngine.reconcileStalledRuns: "They are marked Interrupt rather than Completed or
 * Failed on purpose. What the worker managed before it went quiet is not knowable from here, and a
 * run recorded as finished when nobody knows whether it did is worse than one recorded as
 * interrupted."
 *
 * The sweep had no test at all (gap G10). These are the ten assertions of 16-testing-strategy
 * section 2.4 C4, against source. The selection predicate is asserted here as text and run against
 * a real Postgres, with a real shedlock table, in StalledRunSweepPostgresTest.
 *
 * The application clock: the sweep reads BusinessTime.now() with no seam, so the clock is
 * controlled by bracketing -- the instant the code read lies between the instants read either side
 * of the call, and each timestamp is asserted to within that window rather than approximately.
 */
@ExtendWith(MockitoExtension.class)
class StalledRunSweepTest {

    private static final long JOB_ID = 1196L;

    @Mock private BulkAction bulkAction;
    @Mock private TransactionServiceImpl transactionService;
    @Mock private JobMail jobMail;
    @Mock private RunCallbackTokens runCallbackTokens;
    @Mock private Logger logger;

    private ProducerBulkEngine engine;

    @BeforeEach
    void setUp() {
        this.engine = new ProducerBulkEngine(this.bulkAction, this.transactionService, this.jobMail, this.runCallbackTokens, null);
        this.engine.logger = this.logger;
    }

    private static JobQueue run(long jobQueueId, long jobId, JobStatus status, LocalDateTime startTime,
        LocalDateTime dateCreated) {
        JobQueue jobQueue = new JobQueue();
        jobQueue.setJobQueueId(jobQueueId);
        jobQueue.setJobId(jobId);
        jobQueue.setJobStatus(status);
        jobQueue.setStartTime(startTime);
        jobQueue.setDateCreated(dateCreated == null ? null : BusinessTime.timestampOf(dateCreated));
        return jobQueue;
    }

    private void sweep(JobQueue... stalled) {
        when(this.transactionService.findStalledRuns(any())).thenReturn(Arrays.asList(stalled));
        this.engine.reconcileStalledRuns();
    }

    private static void assertWithin(LocalDateTime actual, LocalDateTime from, LocalDateTime to) {
        assertThat(actual).isNotNull();
        assertThat(actual.isBefore(from) || actual.isAfter(to))
            .as("%s should lie between %s and %s", actual, from, to).isFalse();
    }

    // ---- 1. six hours, on the application clock --------------------------------------------------

    @Test
    void theCutoffIsSixHoursBeforeTheApplicationClock() throws Exception {
        Field constant = ProducerBulkEngine.class.getDeclaredField("STALLED_AFTER_MINUTES");
        constant.setAccessible(true);
        assertThat(constant.getLong(null)).isEqualTo(6 * 60);

        when(this.transactionService.findStalledRuns(any())).thenReturn(Collections.emptyList());
        LocalDateTime before = BusinessTime.now();
        this.engine.reconcileStalledRuns();
        LocalDateTime after = BusinessTime.now();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(this.transactionService).findStalledRuns(cutoff.capture());
        // The application's clock, not the database's: the two are five hours apart here.
        assertWithin(cutoff.getValue(), before.minusMinutes(360), after.minusMinutes(360));
    }

    // ---- 2. what is selected -----------------------------------------------------------------------

    /**
     * COALESCE, not start_time: a run that was never dispatched has no start_time and is still swept,
     * measured from when it was queued. Behaviour against a real database: StalledRunSweepPostgresTest.
     */
    @Test
    void theSelectionIsEveryInFlightStatusMeasuredFromCoalesceInIdOrder() throws Exception {
        Method method = JobQueueRepository.class.getMethod("findStalledRuns", Timestamp.class);
        Query query = method.getAnnotation(Query.class);
        assertThat(query.nativeQuery()).isTrue();
        String sql = query.value().replaceAll("\\s+", " ");
        assertThat(sql)
            .contains("where UPPER(job_status) in ('QUEUE', 'START', 'RUNNING')")
            .contains("and COALESCE(start_time, date_created) < ?1")
            .endsWith("order by job_queue_id asc");
    }

    // ---- 3 and 4. Interrupt, the end time, the sentence ------------------------------------------

    @Test
    void aStalledRunIsClosedAsInterruptWithTheSixHourSentence() {
        JobQueue stalled = run(5073L, JOB_ID, JobStatus.Running, BusinessTime.now().minusHours(7), null);
        when(this.bulkAction.getCountForInQueueJobByJobId(JOB_ID)).thenReturn(0);

        LocalDateTime before = BusinessTime.now();
        this.sweep(stalled);
        LocalDateTime after = BusinessTime.now();

        ArgumentCaptor<JobQueue> saved = ArgumentCaptor.forClass(JobQueue.class);
        verify(this.transactionService).saveJobQueue(saved.capture());
        assertThat(saved.getValue()).isSameAs(stalled);
        assertThat(saved.getValue().getJobStatus()).isEqualTo(JobStatus.Interrupt);
        assertWithin(saved.getValue().getEndTime(), before, after);
        assertThat(saved.getValue().getJobStatusMessage()).isEqualTo("Job 1196 stopped reporting and was "
            + "closed after 6 hours. Its worker may have finished the work -- check the output before running it again.");
    }

    /** Whatever state it was stranded in, the verdict is the same one -- and it is never an outcome. */
    @Test
    void noStalledRunIsEverRecordedAsFailedOrCompleted() {
        LocalDateTime longAgo = BusinessTime.now().minusHours(9);
        JobQueue queued = run(1L, 11L, JobStatus.Queue, null, longAgo);
        JobQueue started = run(2L, 12L, JobStatus.Start, longAgo, longAgo);
        JobQueue running = run(3L, 13L, JobStatus.Running, longAgo, longAgo);
        when(this.bulkAction.getCountForInQueueJobByJobId(anyLong())).thenReturn(0);

        this.sweep(queued, started, running);

        for (JobQueue closed : Arrays.asList(queued, started, running)) {
            assertThat(closed.getJobStatus()).as("run %s", closed.getJobQueueId()).isEqualTo(JobStatus.Interrupt);
            verify(this.bulkAction).changeJobStatus(closed.getJobId(), JobStatus.Interrupt);
        }
        verify(this.bulkAction, never()).changeJobStatus(anyLong(), eq(JobStatus.Failed));
        verify(this.bulkAction, never()).changeJobStatus(anyLong(), eq(JobStatus.Completed));
        verify(this.bulkAction, never()).changeJobQueueStatus(anyLong(), any(), any());
        verify(this.bulkAction, never()).changeJobQueueStatus(anyLong(), any());
        verifyNoInteractions(this.jobMail);
    }

    // ---- 5 and 6. two incidents, told apart here or nowhere --------------------------------------

    @Test
    void aRunThatStartedIsDescribedAsHavingGoneQuiet() {
        LocalDateTime startTime = LocalDateTime.of(2026, 9, 24, 3, 15, 42);
        JobQueue stalled = run(5073L, JOB_ID, JobStatus.Start, startTime, startTime.minusMinutes(1));

        this.sweep(stalled);

        verify(this.bulkAction).saveJobAuditLogs(5073L,
            "Run closed automatically: no update from the worker since 2026-09-24T03:15:42.");
    }

    @Test
    void aRunThatNeverStartedIsDescribedAsNeverPickedUp() {
        LocalDateTime queuedAt = LocalDateTime.of(2026, 9, 24, 3, 0);
        JobQueue stalled = run(5074L, JOB_ID, JobStatus.Queue, null, queuedAt);

        this.sweep(stalled);

        // date_created is a java.sql.Timestamp, and it is quoted in that type's own format.
        verify(this.bulkAction).saveJobAuditLogs(5074L,
            "Run closed automatically: queued at 2026-09-24 03:00:00.0 and never picked up.");
    }

    // ---- 7 and 8. the job row, only when nothing newer is in flight ---------------------------------

    @Test
    void theJobRowIsClosedTooWhenNothingElseIsInFlight() {
        JobQueue stalled = run(5073L, JOB_ID, JobStatus.Running, BusinessTime.now().minusHours(7), null);
        when(this.bulkAction.getCountForInQueueJobByJobId(JOB_ID)).thenReturn(0);

        this.sweep(stalled);

        verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Interrupt);
    }

    /**
     * The count is taken AFTER this run's own row was saved as Interrupt, so anything it still finds
     * is a newer run -- and that run's status on the job row is left alone.
     */
    @Test
    void aNewerRunInFlightLeavesTheJobRowAlone() {
        JobQueue stalled = run(5073L, JOB_ID, JobStatus.Running, BusinessTime.now().minusHours(7), null);
        when(this.bulkAction.getCountForInQueueJobByJobId(JOB_ID)).thenReturn(1);

        this.sweep(stalled);

        verify(this.transactionService).saveJobQueue(stalled);
        verify(this.bulkAction, never()).changeJobStatus(anyLong(), any());
    }

    @Test
    void eachRunIsSavedThenLoggedThenCountedThenAnnounced() {
        JobQueue stalled = run(5073L, JOB_ID, JobStatus.Running, BusinessTime.now().minusHours(7), null);
        when(this.bulkAction.getCountForInQueueJobByJobId(JOB_ID)).thenReturn(0);

        this.sweep(stalled);

        InOrder order = inOrder(this.transactionService, this.bulkAction);
        order.verify(this.transactionService).saveJobQueue(stalled);
        order.verify(this.bulkAction).saveJobAuditLogs(eq(5073L), anyString());
        order.verify(this.bulkAction).getCountForInQueueJobByJobId(JOB_ID);
        order.verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Interrupt);
        // The one-argument form: announced as a new transition, read back from the job row.
        order.verify(this.bulkAction).sendJobStatusNotification(JOB_ID);
    }

    // ---- 9. never a retry ------------------------------------------------------------------------------

    @Test
    void aStalledRunIsNeverOfferedARetry() {
        JobQueue stalled = run(5073L, JOB_ID, JobStatus.Running, BusinessTime.now().minusHours(7), null);
        stalled.setAttempt(1);

        this.sweep(stalled);

        verify(this.bulkAction, never()).scheduleRetry(any(JobQueue.class), any());
        verify(this.bulkAction, never()).scheduleRetry(anyLong(), anyLong(), any());
    }

    // ---- 10. one bad row does not stop the sweep ---------------------------------------------------------

    @Test
    void oneRunThrowingIsLoggedAndTheRestAreStillClosed() {
        LocalDateTime longAgo = BusinessTime.now().minusHours(8);
        JobQueue first = run(1L, 11L, JobStatus.Running, longAgo, null);
        JobQueue broken = run(2L, 12L, JobStatus.Running, longAgo, null);
        JobQueue last = run(3L, 13L, JobStatus.Running, longAgo, null);
        doAnswer(invocation -> {
            if (((JobQueue) invocation.getArgument(0)).getJobQueueId() == 2L) {
                throw new IllegalStateException("could not serialize access");
            }
            return null;
        }).when(this.transactionService).saveJobQueue(any());
        when(this.bulkAction.getCountForInQueueJobByJobId(anyLong())).thenReturn(0);

        this.sweep(first, broken, last);

        // Logged per run, with the root cause as ExceptionUtil renders it, and the loop goes on.
        verify(this.logger).error("Error closing stalled run {}: {}.", 2L,
            "java.lang.IllegalStateException: could not serialize access");
        verify(this.bulkAction).changeJobStatus(11L, JobStatus.Interrupt);
        verify(this.bulkAction).changeJobStatus(13L, JobStatus.Interrupt);
        verify(this.bulkAction, never()).changeJobStatus(eq(12L), any());
        verify(this.bulkAction, times(2)).sendJobStatusNotification(anyLong());
    }

    @Test
    void aSweepThatCannotReadIsLoggedNotThrown() {
        when(this.transactionService.findStalledRuns(any())).thenThrow(new IllegalStateException("connection refused"));

        this.engine.reconcileStalledRuns();

        verify(this.logger).error("Error in reconcileStalledRuns: {}.",
            "java.lang.IllegalStateException: connection refused");
        verifyNoInteractions(this.bulkAction);
    }

    @Test
    void anEmptySweepWritesNothing() {
        this.sweep();

        verifyNoInteractions(this.bulkAction);
        verify(this.transactionService, never()).saveJobQueue(any());
    }

    // ---- the cron that drives it ---------------------------------------------------------------------------

    /**
     * A quarter-hour apart under its own lock: it exists to catch something already stuck for six hours,
     * so noticing within fifteen minutes is ample and keeps a table scan off the minute cycle.
     */
    @Test
    void theCronRunsEveryFifteenMinutesUnderItsOwnLock() throws Exception {
        Method cron = ProcessCron.class.getMethod("reconcileStalledRuns");
        Scheduled scheduled = cron.getAnnotation(Scheduled.class);
        assertThat(scheduled.initialDelay()).isEqualTo(30_000L);
        assertThat(scheduled.fixedDelay()).isEqualTo(900_000L);
        SchedulerLock lock = cron.getAnnotation(SchedulerLock.class);
        assertThat(lock.name()).isEqualTo("reconcileStalledRuns");
        assertThat(lock.lockAtLeastFor()).isEqualTo("5S");
        assertThat(lock.lockAtMostFor()).isEqualTo("5M");

        ProducerBulkEngine delegate = mock(ProducerBulkEngine.class);
        new ProcessCron(delegate).reconcileStalledRuns();
        verify(delegate).reconcileStalledRuns();
    }
}

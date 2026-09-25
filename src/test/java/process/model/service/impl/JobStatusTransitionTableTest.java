package process.model.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Query;
import process.engine.BulkAction;
import process.model.converter.JobStatusConverter;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.notifications.JobMail;
import process.notifications.TestNotifications;

import javax.persistence.Converter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C7 (MIG-139), as MIG-201 changed it: the run state machine a worker callback is held to.
 *
 * MIG-201 (worker-runtime contract, section 16 items 2, 6 and 8) made three changes to what C7 first
 * pinned: Start -> Failed is LEGAL (a worker declines a run it will not start -- an unknown pipeline, the
 * wrong topic -- instead of leaving it in Start for the stall sweep); the verdict is read off the RUN row
 * (job_queue.job_status), not the job row; and a missing status is a refusal, not a NullPointerException.
 * The table, NotifyServiceImpl.isValidStatusTransition:
 *
 *   Queue     -> Start
 *   Start     -> Start | Running | Failed     (Start -> Start is an idempotent re-report; -> Failed declines)
 *   Running   -> Running | Failed | Completed (Running -> Running is the heartbeat)
 *   Failed    -> Failed
 *   Completed -> Completed
 *   otherwise -> refused, a missing status included
 *
 * Seven edges leave the three in-flight states; with the two terminal self-loops that is NINE
 * accepted cells out of the 64 the enum makes. Every cell is asserted, the refusals included, and
 * the enum itself is pinned so that a new status cannot be added without deciding its row and
 * column here.
 *
 * Why Running -> Running must stay accepted, verbatim from BulkAction.changeJobStatus: "that is how a
 * worker says it is still alive ... Suppressing repeats as 'not a change' would therefore switch off
 * the heartbeat and let a healthy long run be reported as stalled." What the heartbeat actually
 * moves is the live JobStatusChanged event every callback republishes -- the console's own
 * lastJobRun -- NOT source_job.last_job_run: the analysis said it advanced that column, and source
 * does not (see runningToRunningIsAcceptedEveryTimeAndRepublished).
 *
 * The verdict is read off the RUN row (JobQueue.jobStatus). It used to be the job row
 * (SourceJob.jobRunningStatus), so a callback's legality could depend on a transition some other run
 * wrote, and a job whose running status was null answered the worker 500 (a NullPointerException), which a
 * worker reads as "Core unavailable" and redelivers without limit.
 */
@ExtendWith(MockitoExtension.class)
class JobStatusTransitionTableTest {

    private static final long TENANT = 1001L;
    private static final long JOB_ID = 2410L;
    private static final long QUEUE_ID = 5705L;

    /** The accepted cells, and only those. */
    private static final Set<String> LEGAL = new HashSet<>(Arrays.asList(
        "Queue>Start",
        "Start>Start", "Start>Running", "Start>Failed",
        "Running>Running", "Running>Failed", "Running>Completed",
        "Failed>Failed",
        "Completed>Completed"));

    @Mock private BulkAction bulkAction;
    @Mock private JobMail jobMail;
    @Mock private TransactionServiceImpl transactionService;
    @Mock private TestNotifications.FeedSink feed;

    private NotifyServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new NotifyServiceImpl(this.bulkAction, this.jobMail, this.transactionService,
            TestNotifications.recording(this.feed, null, null));
    }

    /** The private verdict itself; a throw comes back as the throw. */
    private Boolean verdict(JobStatus current, JobStatus requested) throws Throwable {
        Method method = NotifyServiceImpl.class.getDeclaredMethod("isValidStatusTransition", JobStatus.class, JobStatus.class);
        method.setAccessible(true);
        try {
            return (Boolean) method.invoke(this.service, current, requested);
        } catch (InvocationTargetException wrapper) {
            throw wrapper.getCause();
        }
    }

    static Stream<Arguments> everyCellWithAKnownCurrentStatus() {
        List<Arguments> cells = new ArrayList<>();
        for (JobStatus current : JobStatus.values()) {
            for (JobStatus requested : JobStatus.values()) {
                cells.add(Arguments.of(current, requested));
            }
            cells.add(Arguments.of(current, null));
        }
        return cells.stream();
    }

    // ---- the table ---------------------------------------------------------------------------------

    @Test
    void theStatusSetIsExactlyTheEightThisTableWasDecidedFor() {
        assertThat(JobStatus.values())
            .as("a new status needs a decision about its row AND its column in this table")
            .containsExactly(JobStatus.Queue, JobStatus.Start, JobStatus.Running, JobStatus.Failed,
                JobStatus.Completed, JobStatus.Skip, JobStatus.Interrupt, JobStatus.Missed);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyCellWithAKnownCurrentStatus")
    void everyCellOfTheTable(JobStatus current, JobStatus requested) throws Throwable {
        assertThat(this.verdict(current, requested))
            .as("%s -> %s", current, requested)
            .isEqualTo(LEGAL.contains(current + ">" + requested));
    }

    @Test
    void exactlyNineCellsAreAccepted() throws Throwable {
        int accepted = 0;
        for (JobStatus current : JobStatus.values()) {
            for (JobStatus requested : JobStatus.values()) {
                accepted += this.verdict(current, requested) ? 1 : 0;
            }
        }
        assertThat(accepted).isEqualTo(9);
    }

    /**
     * MIG-201: a worker may decline a run it has not started (Start -> Failed), but it still cannot complete one
     * without having said it was Running.
     */
    @Test
    void aRunStillInStartMayBeDeclinedButNotCompleted() throws Throwable {
        assertThat(this.verdict(JobStatus.Start, JobStatus.Failed)).isTrue();
        assertThat(this.verdict(JobStatus.Start, JobStatus.Completed)).isFalse();
    }

    @Test
    void skipInterruptAndMissedLeadNowhereNotEvenToThemselves() throws Throwable {
        for (JobStatus dead : Arrays.asList(JobStatus.Skip, JobStatus.Interrupt, JobStatus.Missed)) {
            for (JobStatus requested : JobStatus.values()) {
                assertThat(this.verdict(dead, requested)).as("%s -> %s", dead, requested).isFalse();
            }
        }
    }

    /**
     * "null to anything is rejected, because a run that never ran cannot be driven by a callback" -- now by the
     * table itself. It used to be a NullPointerException before any write, which NotifyResetApi turned into a
     * 500 that a worker reads as Core being down (MIG-201, contract section 16 item 6).
     */
    @Test
    void noStatusIsARefusalByTheTableNotANullPointer() throws Throwable {
        for (JobStatus requested : JobStatus.values()) {
            assertThat(this.verdict(null, requested)).as("null -> %s", requested).isFalse();
        }
        assertThat(this.verdict(null, null)).isFalse();
    }

    /** Through the live callback: a run row with no status is the transition refusal (409 at the API), no write. */
    @Test
    void aRunWithNoStatusIsRefusedCleanlyAndNothingIsWritten() {
        this.given(JobStatus.Running, null);

        ResponseDto response = this.service.changeState(this.callback(JobStatus.Running));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).isEqualTo("Invalid status transition from no status to Running");
        verifyNoInteractions(this.bulkAction, this.jobMail);
    }

    /** And a JOB row with no running status no longer matters at all: the run row decides. */
    @Test
    void aJobThatNeverRanNoLongerStopsItsRunFromReporting() {
        this.given(null, JobStatus.Start);

        ResponseDto response = this.service.changeState(this.callback(JobStatus.Running));

        assertThat(response.getStatus()).isNotEqualTo("ERROR");
        verify(this.bulkAction).changeJobQueueStatus(QUEUE_ID, JobStatus.Running, "worker says Running");
    }

    // ---- the table as the live callback applies it ----------------------------------------------------

    /** The job row and the run row agreeing, as they do for a job's one in-flight run (V83). */
    private void givenRunRow(JobStatus status) {
        this.given(status, status);
    }

    private void given(JobStatus jobRunningStatus, JobStatus runStatus) {
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID);
        job.setTenantId(TENANT);
        job.setJobRunningStatus(jobRunningStatus);
        job.setFailJob(true);
        job.setCompleteJob(true);
        when(this.transactionService.findByJobIdAndJobStatus(JOB_ID, Status.Active)).thenReturn(Optional.of(job));
        this.runRow(runStatus);
    }

    private void runRow(JobStatus status) {
        JobQueue run = new JobQueue();
        run.setJobQueueId(QUEUE_ID);
        run.setJobId(JOB_ID);
        run.setJobStatus(status);
        lenient().when(this.transactionService.findJobQueueByJobQueueId(QUEUE_ID)).thenReturn(Optional.of(run));
    }

    private SourceJobQueueDto callback(JobStatus status) {
        SourceJobQueueDto dto = new SourceJobQueueDto();
        dto.setJobId(JOB_ID);
        dto.setJobQueueId(QUEUE_ID);
        dto.setJobStatus(status);
        dto.setJobStatusMessage("worker says " + status);
        return dto;
    }

    /** Refused before anything else is considered: a run in Start cannot be Completed without Running. */
    @Test
    void aCompletedCallbackFromStartIsRefusedBeforeAnyWrite() {
        this.givenRunRow(JobStatus.Start);

        ResponseDto response = this.service.changeState(this.callback(JobStatus.Completed));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).isEqualTo("Invalid status transition from Start to Completed");
        verify(this.bulkAction, never()).changeJobStatus(anyLong(), any());
        verify(this.bulkAction, never()).changeJobQueueStatus(anyLong(), any(), any());
        verify(this.bulkAction, never()).saveJobAuditLogs(anyLong(), anyString());
        verifyNoInteractions(this.jobMail);
    }

    /**
     * The heartbeat. Every repeat is written and republished -- changeJobStatus announces
     * unconditionally -- but none is a new transition, and none touches source_job.last_job_run:
     * that column is written only when a run is enqueued (BulkAction.changeJobLastJobRun), whatever
     * the analysis says. The stall sweep reads neither; it measures from the run's start_time.
     */
    @Test
    void runningToRunningIsAcceptedEveryTimeAndRepublished() {
        this.givenRunRow(JobStatus.Running);

        ResponseDto first = this.service.changeState(this.callback(JobStatus.Running));
        ResponseDto second = this.service.changeState(this.callback(JobStatus.Running));

        assertThat(first.getStatus()).isNotEqualTo("ERROR");
        assertThat(second.getStatus()).isNotEqualTo("ERROR");
        verify(this.bulkAction, times(2)).changeJobStatus(JOB_ID, JobStatus.Running);
        verify(this.bulkAction, times(2)).changeJobQueueStatus(QUEUE_ID, JobStatus.Running, "worker says Running");
        verify(this.bulkAction, times(2)).sendJobStatusNotification(JOB_ID, QUEUE_ID, false);
        verify(this.bulkAction, never()).changeJobLastJobRun(anyLong(), any());
    }

    /** Start -> Start is the idempotent re-report, accepted the same way. */
    @Test
    void startToStartIsAcceptedAsARepeat() {
        this.givenRunRow(JobStatus.Start);

        assertThat(this.service.changeState(this.callback(JobStatus.Start)).getStatus()).isNotEqualTo("ERROR");
        verify(this.bulkAction).sendJobStatusNotification(JOB_ID, QUEUE_ID, false);
    }

    /**
     * The RUN row decides (MIG-201), not the job row. Here the run reporting is Running while the job row
     * says Start (another writer moved it): the run's Completed is accepted as "Running to Completed". Before
     * MIG-201 it was refused as "Start to Completed" and the run was left for the stall sweep.
     */
    @Test
    void theVerdictReadsTheRunRowNotTheJobRow() {
        this.given(JobStatus.Start, JobStatus.Running);

        ResponseDto completed = this.service.changeState(this.callback(JobStatus.Completed));

        assertThat(completed.getStatus()).isNotEqualTo("ERROR");
        verify(this.bulkAction).changeJobQueueStatus(QUEUE_ID, JobStatus.Completed, "worker says Completed");
    }

    /** And the other way about: a job row in Running does not let a run still in Queue finish. */
    @Test
    void aJobRowThatDisagreesWithTheRunRowIsNotConsulted() {
        this.given(JobStatus.Running, JobStatus.Queue);

        ResponseDto completed = this.service.changeState(this.callback(JobStatus.Completed));

        assertThat(completed.getStatus()).isEqualTo("ERROR");
        assertThat(completed.getMessage()).isEqualTo("Invalid status transition from Queue to Completed");
        verify(this.bulkAction, never()).changeJobStatus(anyLong(), any());
    }

    // ---- how a status is stored and read -----------------------------------------------------------------

    /**
     * The analysis says an unknown database value resolves to null "not an exception". Source says
     * otherwise: EnumUtils.parseEnum returns null only for null or blank, folds case, and THROWS on
     * anything it cannot name. Pinned as source has it.
     */
    @Test
    void theConverterFoldsCaseReadsBlankAsNullAndRefusesAnUnknownValue() {
        assertThat(JobStatusConverter.class.getAnnotation(Converter.class).autoApply()).isTrue();
        JobStatusConverter converter = new JobStatusConverter();

        assertThat(converter.convertToDatabaseColumn(JobStatus.Running)).isEqualTo("Running");
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute("Running")).isEqualTo(JobStatus.Running);
        assertThat(converter.convertToEntityAttribute("RUNNING")).isEqualTo(JobStatus.Running);
        assertThat(converter.convertToEntityAttribute(" queue ")).isEqualTo(JobStatus.Queue);
        assertThat(converter.convertToEntityAttribute(null)).isNull();
        assertThat(converter.convertToEntityAttribute("   ")).isNull();
        assertThatThrownBy(() -> converter.convertToEntityAttribute("Cancelled"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("JobStatus");
    }

    /**
     * Written in the enum's exact case, compared case-blind on read: every native query in
     * JobQueueRepository that filters on job_status does it through UPPER(), so a test asserting
     * exact-case storage is over-specified.
     */
    @Test
    void everyNativeReadOfJobStatusIsCaseBlind() {
        int filtering = 0;
        for (Method method : JobQueueRepository.class.getMethods()) {
            Query query = method.getAnnotation(Query.class);
            if (query == null || !query.value().contains("job_status")) {
                continue;
            }
            // The run's status, not source_job's own j.job_status (Active/Delete, another enum).
            String where = query.value().substring(Math.max(0, query.value().toLowerCase().indexOf(" where ")))
                .replace("j.job_status", "");
            if (!where.contains("job_status")) {
                continue;
            }
            filtering++;
            assertThat(where.replace("UPPER(job_status)", "").replace("UPPER(q.job_status)", ""))
                .as(method.getName())
                .doesNotContain("job_status");
        }
        // Five since MIG-63 added noteRefusedCallback and findRunsWithRefusedCallbacks, seven since MIG-134
        // added findRunsToPrepare and markPrepared -- every one of them through UPPER().
        assertThat(filtering).as("queries filtering on a run's job_status").isEqualTo(7);
    }
}

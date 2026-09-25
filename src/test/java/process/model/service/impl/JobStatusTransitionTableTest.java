package process.model.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
 * C7 (MIG-139): the job state machine a worker callback is held to. Start -> Failed is ILLEGAL.
 *
 * The natural state machine lets a worker fail straight from Start; this one does not -- a worker
 * must pass through Running. The table, NotifyServiceImpl.isValidStatusTransition:
 *
 *   Queue     -> Start
 *   Start     -> Start | Running              (Start -> Start is an idempotent re-report)
 *   Running   -> Running | Failed | Completed (Running -> Running is the heartbeat)
 *   Failed    -> Failed
 *   Completed -> Completed
 *   otherwise -> refused
 *
 * Six edges leave the three in-flight states; with the two terminal self-loops that is EIGHT
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
 * The verdict is read off the JOB row (SourceJob.jobRunningStatus), not the run row, so a callback's
 * legality can depend on a transition some other run wrote.
 */
@ExtendWith(MockitoExtension.class)
class JobStatusTransitionTableTest {

    private static final long TENANT = 1001L;
    private static final long JOB_ID = 2410L;
    private static final long QUEUE_ID = 5705L;

    /** The accepted cells, and only those. */
    private static final Set<String> LEGAL = new HashSet<>(Arrays.asList(
        "Queue>Start",
        "Start>Start", "Start>Running",
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
    void exactlyEightCellsAreAccepted() throws Throwable {
        int accepted = 0;
        for (JobStatus current : JobStatus.values()) {
            for (JobStatus requested : JobStatus.values()) {
                accepted += this.verdict(current, requested) ? 1 : 0;
            }
        }
        assertThat(accepted).isEqualTo(8);
    }

    /** C7's headline: a worker cannot finish, either way, without having said it was Running. */
    @Test
    void aRunStillInStartCanNeitherFailNorComplete() throws Throwable {
        assertThat(this.verdict(JobStatus.Start, JobStatus.Failed)).isFalse();
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
     * "null to anything is rejected, because a job that never ran cannot be driven by a callback" --
     * true, but not by the table: the switch dereferences the null and throws. Through changeState
     * that is a NullPointerException before any write, which NotifyResetApi's catch turns into a 500,
     * not a refusal the worker can read. Pinned, not endorsed: whether a never-run job should answer
     * the worker 500 or a clean ERROR is undecided.
     */
    @Test
    @Tag("pinned-unreviewed")
    void aJobThatNeverRanIsRefusedByANullPointerNotByTheTable() {
        for (JobStatus requested : JobStatus.values()) {
            assertThatThrownBy(() -> this.verdict(null, requested)).isInstanceOf(NullPointerException.class);
        }
        assertThatThrownBy(() -> this.verdict(null, null)).isInstanceOf(NullPointerException.class);

        this.givenJobRow(null);
        assertThatThrownBy(() -> this.service.changeState(this.callback(JobStatus.Running)))
            .isInstanceOf(NullPointerException.class);
        verifyNoInteractions(this.bulkAction, this.jobMail);
    }

    // ---- the table as the live callback applies it ----------------------------------------------------

    private void givenJobRow(JobStatus jobRunningStatus) {
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID);
        job.setTenantId(TENANT);
        job.setJobRunningStatus(jobRunningStatus);
        job.setFailJob(true);
        job.setCompleteJob(true);
        when(this.transactionService.findByJobIdAndJobStatus(JOB_ID, Status.Active)).thenReturn(Optional.of(job));
        this.givenRunRow(JobStatus.Running);
    }

    private void givenRunRow(JobStatus status) {
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

    /** Refused before anything else is considered -- including a retry: an illegal Failed is not a failure. */
    @Test
    void aFailedCallbackFromStartIsRefusedBeforeAnyRetryOrWrite() {
        this.givenJobRow(JobStatus.Start);

        ResponseDto response = this.service.changeState(this.callback(JobStatus.Failed));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).isEqualTo("Invalid status transition from Start to Failed");
        verify(this.bulkAction, never()).scheduleRetry(anyLong(), anyLong(), any());
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
        this.givenJobRow(JobStatus.Running);

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
        this.givenJobRow(JobStatus.Start);

        assertThat(this.service.changeState(this.callback(JobStatus.Start)).getStatus()).isNotEqualTo("ERROR");
        verify(this.bulkAction).sendJobStatusNotification(JOB_ID, QUEUE_ID, false);
    }

    /**
     * The JOB row decides, not the run's own row. Here the run reporting is itself Running, but a
     * different run has since written Start to the job row -- so this run's Completed is refused as
     * "Start to Completed", and its Running is accepted as "Start to Running".
     */
    @Test
    void theVerdictReadsTheJobRowWhichAnotherRunMayHaveWritten() {
        this.givenJobRow(JobStatus.Start);
        this.givenRunRow(JobStatus.Running);

        ResponseDto completed = this.service.changeState(this.callback(JobStatus.Completed));
        assertThat(completed.getStatus()).isEqualTo("ERROR");
        assertThat(completed.getMessage()).isEqualTo("Invalid status transition from Start to Completed");

        ResponseDto running = this.service.changeState(this.callback(JobStatus.Running));
        assertThat(running.getStatus()).isNotEqualTo("ERROR");
    }

    /** And the other way about: a run row still in Queue does not stop a job row in Running from finishing. */
    @Test
    void aRunRowThatDisagreesWithTheJobRowIsNotConsulted() {
        this.givenJobRow(JobStatus.Running);
        this.givenRunRow(JobStatus.Queue);

        assertThat(this.service.changeState(this.callback(JobStatus.Completed)).getStatus()).isNotEqualTo("ERROR");
        verify(this.bulkAction).changeJobStatus(eq(JOB_ID), eq(JobStatus.Completed));
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

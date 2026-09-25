package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.invocation.Invocation;
import org.mockito.junit.jupiter.MockitoExtension;
import process.engine.BulkAction;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.notifications.JobMail;
import process.notifications.TestNotifications;
import process.security.TenantContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * C7b writer B of four (MIG-140): NotifyServiceImpl.changeState -- THE LIVE WORKER PATH.
 *
 * Call path: POST /changeState/jobId/{jobId}/jobQueueId/{jobQueueId}/jobStatus/{jobStatus} on
 * NotifyResetApi, authenticated by the run's own X-Worker-Token rather than a JWT, so there is no
 * TenantContext; the job is looked up by id and Active status alone.
 *
 * Validation: YES, and first -- the C7 table, read off the job row. An illegal Failed (from Start,
 * say) is refused before a retry is even considered.
 *
 * Retry: ALWAYS offered for a legal Failed, and BEFORE any write. Verbatim: "Offered before any of the
 * writes below ... Making them first and retrying afterwards would tell everyone the run had failed
 * moments before trying it again -- and on a job with three attempts, would send three failure emails
 * for one eventual failure." The retry's answer IS the email gate: true means no Failed status and no
 * failure email.
 *
 * The other three writers: FailedByDispatchTest, FailedByChangeJobStatusTest, FailedByOperatorTest.
 */
@ExtendWith(MockitoExtension.class)
class FailedByWorkerCallbackTest {

    private static final long TENANT = 1001L;
    private static final long JOB_ID = 2410L;
    private static final long QUEUE_ID = 5705L;

    @Mock private BulkAction bulkAction;
    @Mock private JobMail jobMail;
    @Mock private TransactionServiceImpl transactionService;
    @Mock private TestNotifications.FeedSink feed;

    private NotifyServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        this.service = new NotifyServiceImpl(this.bulkAction, this.jobMail, this.transactionService,
            TestNotifications.recording(this.feed, null, null));
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void givenJobRow(JobStatus runningStatus) {
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID);
        job.setTenantId(TENANT);
        job.setJobRunningStatus(runningStatus);
        job.setFailJob(true);
        when(this.transactionService.findByJobIdAndJobStatus(JOB_ID, Status.Active)).thenReturn(Optional.of(job));
        JobQueue run = new JobQueue();
        run.setJobQueueId(QUEUE_ID);
        run.setJobId(JOB_ID);
        when(this.transactionService.findJobQueueByJobQueueId(QUEUE_ID)).thenReturn(Optional.of(run));
    }

    private SourceJobQueueDto workerReportsFailed() {
        SourceJobQueueDto dto = new SourceJobQueueDto();
        dto.setJobId(JOB_ID);
        dto.setJobQueueId(QUEUE_ID);
        dto.setJobStatus(JobStatus.Failed);
        dto.setJobStatusMessage("source refused the connection");
        dto.setEndTime(LocalDateTime.of(2026, 9, 24, 9, 30));
        return dto;
    }

    private static List<String> namesOf(Object mock) {
        List<String> names = new ArrayList<>();
        for (Invocation invocation : mockingDetails(mock).getInvocations()) {
            names.add(invocation.getMethod().getName());
        }
        return names;
    }

    /**
     * Asserted at the moment scheduleRetry is asked: nothing has been written yet. The only thing
     * touched before it is the pair of reads that identify the job and the run.
     */
    @Test
    void theRetryIsAskedBeforeAnyWrite() {
        this.givenJobRow(JobStatus.Running);
        List<String> bulkCallsBeforeRetry = new ArrayList<>();
        List<String> storeCallsBeforeRetry = new ArrayList<>();
        List<String> mailCallsBeforeRetry = new ArrayList<>();
        when(this.bulkAction.scheduleRetry(eq(QUEUE_ID), eq(JOB_ID), anyString())).thenAnswer(invocation -> {
            bulkCallsBeforeRetry.addAll(namesOf(this.bulkAction));
            storeCallsBeforeRetry.addAll(namesOf(this.transactionService));
            mailCallsBeforeRetry.addAll(namesOf(this.jobMail));
            return false;
        });

        this.service.changeState(this.workerReportsFailed());

        assertThat(bulkCallsBeforeRetry).containsExactly("scheduleRetry");
        assertThat(storeCallsBeforeRetry).containsExactly("findByJobIdAndJobStatus", "findJobQueueByJobQueueId");
        assertThat(mailCallsBeforeRetry).isEmpty();

        InOrder order = inOrder(this.bulkAction, this.jobMail);
        order.verify(this.bulkAction).scheduleRetry(QUEUE_ID, JOB_ID, "source refused the connection");
        order.verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Failed);
        order.verify(this.bulkAction).changeJobQueueStatus(QUEUE_ID, JobStatus.Failed, "source refused the connection");
        order.verify(this.bulkAction).saveJobAuditLogs(QUEUE_ID, "source refused the connection");
        order.verify(this.bulkAction).sendJobStatusNotification(JOB_ID, QUEUE_ID, true);
        order.verify(this.bulkAction).changeJobQueueEndDate(QUEUE_ID, LocalDateTime.of(2026, 9, 24, 9, 30));
        order.verify(this.jobMail).send(any(SourceJobQueueDto.class), eq(JobStatus.Failed));
    }

    /** A retried attempt sends ZERO emails, and writes nothing that says it failed. */
    @Test
    void aRetriedAttemptSendsNoEmailAndWritesNoFailure() {
        this.givenJobRow(JobStatus.Running);
        when(this.bulkAction.scheduleRetry(eq(QUEUE_ID), eq(JOB_ID), anyString())).thenReturn(true);

        ResponseDto response = this.service.changeState(this.workerReportsFailed());

        assertThat(response.getMessage()).isEqualTo("Job 2410 run failed and has been queued for another attempt.");
        assertThat(response.getStatus()).as("not ERROR -- which is what makes NotifyResetApi retire the token").isNull();
        verifyNoInteractions(this.jobMail);
        verify(this.bulkAction, never()).changeJobStatus(anyLong(), any());
        verify(this.bulkAction, never()).changeJobQueueStatus(anyLong(), any(), any());
        verify(this.bulkAction, never()).saveJobAuditLogs(anyLong(), anyString());
        verify(this.bulkAction, never()).changeJobQueueEndDate(anyLong(), any());
        verify(this.bulkAction, never()).sendJobStatusNotification(anyLong(), any(), eq(true));
    }

    /** Three attempts, three Failed reports from the worker: ONE email, for the one real failure. */
    @Test
    void aJobWithThreeAttemptsFailingThreeTimesSendsOneEmail() {
        this.givenJobRow(JobStatus.Running);
        when(this.bulkAction.scheduleRetry(eq(QUEUE_ID), eq(JOB_ID), anyString())).thenReturn(true, true, false);

        for (int attempt = 1; attempt <= 3; attempt++) {
            this.service.changeState(this.workerReportsFailed());
        }

        verify(this.bulkAction, times(3)).scheduleRetry(eq(QUEUE_ID), eq(JOB_ID), anyString());
        verify(this.bulkAction, times(1)).changeJobStatus(JOB_ID, JobStatus.Failed);
        verify(this.jobMail, times(1)).send(any(SourceJobQueueDto.class), eq(JobStatus.Failed));
    }

    /** Validation first: an illegal Failed is not a failure, so it is not retried either. */
    @Test
    void anIllegalFailedIsRefusedBeforeTheRetryIsConsidered() {
        this.givenJobRow(JobStatus.Start);

        ResponseDto response = this.service.changeState(this.workerReportsFailed());

        assertThat(response.getStatus()).isEqualTo("ERROR");
        verify(this.bulkAction, never()).scheduleRetry(anyLong(), anyLong(), any());
        verify(this.bulkAction, never()).changeJobStatus(anyLong(), any());
        verifyNoInteractions(this.jobMail);
    }

    /** The worker has no session: nothing here needs a TenantContext, and none is set. */
    @Test
    void noTenantContextIsNeeded() {
        assertThat(TenantContext.getTenantId()).isNull();
        this.givenJobRow(JobStatus.Running);
        when(this.bulkAction.scheduleRetry(eq(QUEUE_ID), eq(JOB_ID), anyString())).thenReturn(false);

        ResponseDto response = this.service.changeState(this.workerReportsFailed());

        assertThat(response.getStatus()).isNotEqualTo("ERROR");
        verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Failed);
    }
}

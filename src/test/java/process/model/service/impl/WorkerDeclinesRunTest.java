package process.model.service.impl;

import org.barco.platform.meter.MeterReporter;
import org.barco.platform.meter.UsageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import process.engine.BulkAction;
import process.model.dto.ResponseDto;
import process.model.dto.SourceJobQueueDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.notifications.JobMail;
import process.notifications.TestNotifications;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static process.util.ProcessUtil.ERROR;

/**
 * MIG-201 (worker-runtime contract, section 4 and section 16 item 2): a worker may DECLINE a run it will not
 * start -- a pipelineId it does not have, a record on the wrong topic -- by reporting Failed straight from Start.
 *
 * Before, Start -> Failed was refused, so such a run sat in Start until the stall sweep interrupted it six hours
 * later. Now the decline closes it at once, as a failure a person can read (the worker's reason is the status
 * line, the audit line and the fail mail), with three differences from a run that failed while working:
 *
 * <ul>
 *   <li>it is not retried: a decline is a configuration answer, and another attempt meets the same
 *       configuration (the same reasoning as MIG-45's unrouted run);</li>
 *   <li>it is not metered: no work was done, so there is no pipeline.runs usage to bill;</li>
 *   <li>a run latched as sent but not yet moved to Start (the hand-off race, run 7309) may be declined too: from
 *       any worker's side it IS handed off.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class WorkerDeclinesRunTest {

    private static final long TENANT = 1001L;
    private static final long JOB_ID = 2410L;
    private static final long QUEUE_ID = 5705L;
    private static final String REASON = "service-1 refused this run: pipelineId F100001 is not in service-1's catalogue";
    private static final LocalDateTime ENDED = LocalDateTime.of(2026, 9, 24, 9, 30);

    @Mock private BulkAction bulkAction;
    @Mock private JobMail jobMail;
    @Mock private TransactionServiceImpl transactionService;
    @Mock private TestNotifications.FeedSink feed;
    @Mock private MeterReporter meter;

    private NotifyServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new NotifyServiceImpl(this.bulkAction, this.jobMail, this.transactionService,
            TestNotifications.recording(this.feed, null, null));
        ReflectionTestUtils.setField(this.service, "meter", this.meter);
    }

    private void runIn(JobStatus status, boolean latchedAsSent) {
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID);
        job.setTenantId(TENANT);
        job.setJobRunningStatus(status);
        job.setFailJob(true);
        when(this.transactionService.findByJobIdAndJobStatus(JOB_ID, Status.Active)).thenReturn(Optional.of(job));
        JobQueue run = new JobQueue();
        run.setJobQueueId(QUEUE_ID);
        run.setJobId(JOB_ID);
        run.setJobStatus(status);
        run.setJobSend(latchedAsSent);
        when(this.transactionService.findJobQueueByJobQueueId(QUEUE_ID)).thenReturn(Optional.of(run));
        lenient().when(this.bulkAction.scheduleRetry(anyLong(), anyLong(), anyString())).thenReturn(true);
    }

    private static SourceJobQueueDto failed() {
        SourceJobQueueDto dto = new SourceJobQueueDto();
        dto.setJobId(JOB_ID);
        dto.setJobQueueId(QUEUE_ID);
        dto.setJobStatus(JobStatus.Failed);
        dto.setJobStatusMessage(REASON);
        dto.setEndTime(ENDED);
        return dto;
    }

    @Test
    void aRunInStartIsClosedAsFailedWithTheWorkersReason() {
        this.runIn(JobStatus.Start, true);

        ResponseDto answer = this.service.changeState(failed());

        assertThat(answer.getStatus()).isNotEqualTo(ERROR);
        assertThat(answer.getMessage()).isEqualTo("Job 2410 status changed to Failed");
        InOrder order = inOrder(this.bulkAction, this.jobMail);
        order.verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Failed);
        order.verify(this.bulkAction).changeJobQueueStatus(QUEUE_ID, JobStatus.Failed, REASON);
        order.verify(this.bulkAction).saveJobAuditLogs(QUEUE_ID, REASON);
        order.verify(this.bulkAction).sendJobStatusNotification(JOB_ID, QUEUE_ID, true);
        order.verify(this.bulkAction).changeJobQueueEndDate(QUEUE_ID, ENDED);
        order.verify(this.jobMail).send(any(SourceJobQueueDto.class), eq(JobStatus.Failed));
    }

    /** Offered a retry, it would take it -- the stub says yes -- but a decline is never offered one. */
    @Test
    void aDeclineIsNotRetried() {
        this.runIn(JobStatus.Start, true);

        this.service.changeState(failed());

        verify(this.bulkAction, never()).scheduleRetry(anyLong(), anyLong(), any());
        verify(this.bulkAction).changeJobQueueStatus(QUEUE_ID, JobStatus.Failed, REASON);
    }

    @Test
    void aDeclineIsNotMetered() {
        this.runIn(JobStatus.Start, true);

        this.service.changeState(failed());

        verify(this.meter, never()).report(any(UsageEvent.class));
    }

    /** The contrast: a run that failed while working is still offered its retry, exactly as before. */
    @Test
    void aFailureAfterRunningIsStillOfferedItsRetry() {
        this.runIn(JobStatus.Running, true);

        ResponseDto answer = this.service.changeState(failed());

        verify(this.bulkAction).scheduleRetry(QUEUE_ID, JOB_ID, REASON);
        assertThat(answer.getMessage()).isEqualTo("Job 2410 run failed and has been queued for another attempt.");
    }

    /** And, not retried, still metered: it did work. */
    @Test
    void aFailureAfterRunningIsStillMetered() {
        this.runIn(JobStatus.Running, true);
        when(this.bulkAction.scheduleRetry(anyLong(), anyLong(), anyString())).thenReturn(false);

        this.service.changeState(failed());

        verify(this.meter).report(any(UsageEvent.class));
    }

    /** The hand-off race: latched as sent, still Queue on the row -- declinable, as Running is accepted. */
    @Test
    void aRunLatchedAsSentButStillInQueueMayBeDeclined() {
        this.runIn(JobStatus.Queue, true);

        ResponseDto answer = this.service.changeState(failed());

        assertThat(answer.getStatus()).isNotEqualTo(ERROR);
        verify(this.bulkAction, never()).scheduleRetry(anyLong(), anyLong(), any());
        verify(this.bulkAction).changeJobQueueStatus(QUEUE_ID, JobStatus.Failed, REASON);
    }

    /** A run never latched as sent was never handed to anyone: nothing can decline it. */
    @Test
    void aRunNeverLatchedAsSentCannotBeDeclined() {
        this.runIn(JobStatus.Queue, false);

        ResponseDto answer = this.service.changeState(failed());

        assertThat(answer.getStatus()).isEqualTo(ERROR);
        assertThat(answer.getMessage()).isEqualTo("Invalid status transition from Queue to Failed");
        verify(this.bulkAction, never()).changeJobQueueStatus(anyLong(), any(), any());
    }
}

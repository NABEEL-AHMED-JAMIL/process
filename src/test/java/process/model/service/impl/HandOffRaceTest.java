package process.model.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Found live on run 7309 (2026-09-24), after the dispatch outbox (MIG-136): the relay publishes, and
 * only after the broker's ack does a transaction move the run and the job to Start. A worker that
 * reads the message and reports Running within those milliseconds found the job still at Queue, was
 * told "Invalid status transition from Queue to Running", and -- as its contract says of a refusal --
 * skipped the run, which then sat in Start until the stall sweep.
 *
 * A run latched as sent (job_send) is handed off as far as any worker can tell: its message, and the
 * token the callback is proven by, exist only in what was published. So Running is legal from Queue
 * for that run -- and only for that run.
 */
@ExtendWith(MockitoExtension.class)
class HandOffRaceTest {

    private static final long JOB_ID = 2830L;
    private static final long RUN_ID = 7309L;

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

    private void jobAtQueueWithRun(boolean latchedAsSent) {
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID);
        job.setTenantId(2905L);
        job.setJobRunningStatus(JobStatus.Queue);
        when(this.transactionService.findByJobIdAndJobStatus(JOB_ID, Status.Active)).thenReturn(Optional.of(job));
        JobQueue run = new JobQueue();
        run.setJobQueueId(RUN_ID);
        run.setJobId(JOB_ID);
        run.setJobStatus(JobStatus.Queue);
        run.setJobSend(latchedAsSent);
        when(this.transactionService.findJobQueueByJobQueueId(RUN_ID)).thenReturn(Optional.of(run));
    }

    private static SourceJobQueueDto running() {
        SourceJobQueueDto dto = new SourceJobQueueDto();
        dto.setJobId(JOB_ID);
        dto.setJobQueueId(RUN_ID);
        dto.setJobStatus(JobStatus.Running);
        return dto;
    }

    @Test
    void aWorkerFasterThanTheBrokersAckIsNotRefused() {
        this.jobAtQueueWithRun(true);

        ResponseDto answer = this.service.changeState(running());

        assertThat(answer.getMessage()).doesNotContain("Invalid status transition");
    }

    @Test
    void aRunNeverLatchedAsSentStillCannotJumpToRunning() {
        this.jobAtQueueWithRun(false);

        ResponseDto answer = this.service.changeState(running());

        assertThat(answer.getMessage()).isEqualTo("Invalid status transition from Queue to Running");
    }
}

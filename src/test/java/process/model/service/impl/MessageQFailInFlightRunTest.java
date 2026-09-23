package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.notifications.JobMail;
import process.engine.BulkAction;
import process.model.dto.ResponseDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.JobQueueRepository;
import process.model.repository.SourceJobRepository;
import process.security.TenantContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Forcing a run that will never report back to a terminal state, from the queue screen.
 *
 * "Mark as failed" is offered on every in-flight row -- the screen's own inFlight() covers Queue,
 * Start and Running -- and the confirm dialog says what it is for: a run that is stuck and whose
 * worker will not report back. failJobLogs accepted only Queue, which is the one state where the
 * worker has not even been handed the job yet, so the operator confirmed the dialog and got back
 * "Only 'In Queue' Job can be fail." for precisely the rows the action exists for. "Mark as
 * interrupted" beside it has no status check at all and worked, so two neighbouring buttons
 * behaved differently on the same row for no reason visible from the screen.
 *
 * The set accepted here is the one the rest of the platform already treats as occupying the
 * queue: what getCountForInQueueJobByJobId counts, and what findStalledRuns sweeps. Terminal rows
 * stay refused, because re-failing a Completed run rewrites history something already recorded.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
public class MessageQFailInFlightRunTest {

    private static final long TENANT_A = 1001L;
    private static final long JOB_ID = 66L;
    private static final long QUEUE_ID = 91422L;

    @Mock private BulkAction bulkAction;
    @Mock private QueryService queryService;
    @Mock private JobQueueRepository jobQueueRepository;
    @Mock private SourceJobRepository sourceJobRepository;
    @Mock private JobMail jobMail;

    private MessageQServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new MessageQServiceImpl(this.bulkAction, this.queryService,
            this.jobQueueRepository, this.sourceJobRepository, this.jobMail);
        TenantContext.set(TENANT_A, "TENANT_USER", 1L, "a@example.com");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** The caller's own run, sitting in whichever state the test is about. */
    private ResponseDto markAsFailed(JobStatus held) {
        JobQueue jobQueue = new JobQueue();
        jobQueue.setJobQueueId(QUEUE_ID);
        jobQueue.setJobId(JOB_ID);
        jobQueue.setJobStatus(held);
        SourceJob sourceJob = new SourceJob();
        sourceJob.setJobId(JOB_ID);
        sourceJob.setTenantId(TENANT_A);
        sourceJob.setJobStatus(Status.Active);
        when(this.jobQueueRepository.findById(QUEUE_ID)).thenReturn(Optional.of(jobQueue));
        when(this.sourceJobRepository.findById(JOB_ID)).thenReturn(Optional.of(sourceJob));
        return this.service.failJobLogs(QUEUE_ID);
    }

    private void assertTheRunWasClosedAsFailed() {
        verify(this.bulkAction).changeJobStatus(JOB_ID, JobStatus.Failed);
        verify(this.bulkAction).changeJobQueueStatus(eq(QUEUE_ID), eq(JobStatus.Failed), anyString());
        verify(this.bulkAction).saveJobAuditLogs(eq(QUEUE_ID), anyString());
        // Without an end time the run reads as still going on every screen that measures its age.
        verify(this.bulkAction).changeJobQueueEndDate(eq(QUEUE_ID), any());
    }

    // ---- the states the screen offers the action in --------------------------------------------

    /** The case it was built for: dispatched, the worker went quiet, nothing is coming back. */
    @Test
    void aRunInStartCanBeForcedToFailed() {
        assertThat(this.markAsFailed(JobStatus.Start).getStatus()).isEqualTo("SUCCESS");
        this.assertTheRunWasClosedAsFailed();
    }

    @Test
    void aRunInRunningCanBeForcedToFailed() {
        assertThat(this.markAsFailed(JobStatus.Running).getStatus()).isEqualTo("SUCCESS");
        this.assertTheRunWasClosedAsFailed();
    }

    /** The one state that always worked still does. */
    @Test
    void aQueuedRunCanStillBeForcedToFailed() {
        assertThat(this.markAsFailed(JobStatus.Queue).getStatus()).isEqualTo("SUCCESS");
        this.assertTheRunWasClosedAsFailed();
    }

    // ---- and the ones it does not ---------------------------------------------------------------

    @Test
    void aCompletedRunIsRefusedRatherThanRewritten() {
        ResponseDto response = this.markAsFailed(JobStatus.Completed);

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("in flight");
        verify(this.bulkAction, never()).changeJobStatus(anyLong(), any());
        verify(this.bulkAction, never()).changeJobQueueStatus(anyLong(), any(), anyString());
    }

    @Test
    void anAlreadySkippedRunIsRefusedToo() {
        assertThat(this.markAsFailed(JobStatus.Skip).getStatus()).isEqualTo("ERROR");
        verify(this.bulkAction, never()).changeJobStatus(anyLong(), any());
    }
}

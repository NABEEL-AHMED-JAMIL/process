package process.model.service.impl;

import org.barco.platform.meter.Meter;
import org.barco.platform.meter.MeterReporter;
import org.barco.platform.meter.UsageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static process.util.ProcessUtil.ERROR;

/**
 * Owner rule "keep the bill" (2026-09-24): usage that happened is charged even if its job, task or workspace is
 * deleted afterwards or mid-run; deletion only stops FUTURE usage.
 *
 * A run whose job is deleted (or switched off) while it works still reports: the worker's callback lands --
 * its outcome recorded as reported, the run metered once -- because the run row and its token are the proof, not
 * the job's current status. What deletion does stop is future work: a failed run of a deleted job is not queued for
 * another attempt. Before this, changeState answered "not found or not active", the run sat in Running until the
 * stall sweep interrupted it, and its pipeline.runs usage was never reported.
 */
@ExtendWith(MockitoExtension.class)
class KeepTheBillCallbackTest {

    private static final long TENANT = 1001L;
    private static final long JOB_ID = 1196L;
    private static final long QUEUE_ID = 91422L;

    @Mock private BulkAction bulkAction;
    @Mock private JobMail jobMail;
    @Mock private TransactionServiceImpl transactionService;
    @Mock private TestNotifications.FeedSink feed;
    @Mock private MeterReporter meter;

    private NotifyServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new NotifyServiceImpl(this.bulkAction, this.jobMail, this.transactionService,
            TestNotifications.recording(this.feed, null, null, null));
        ReflectionTestUtils.setField(this.service, "meter", this.meter);
    }

    /** The job, deleted (or switched off) while its run was working: the run itself is still Running. */
    private void jobGoneMidRun(Status jobStatus) {
        SourceJob job = new SourceJob();
        job.setJobId(JOB_ID);
        job.setTenantId(TENANT);
        job.setJobStatus(jobStatus);
        job.setJobRunningStatus(JobStatus.Running);
        when(this.transactionService.findByJobIdAndJobStatus(JOB_ID, Status.Active)).thenReturn(Optional.empty());
        when(this.transactionService.findByJobId(JOB_ID)).thenReturn(Optional.of(job));
        JobQueue run = new JobQueue();
        run.setJobQueueId(QUEUE_ID);
        run.setJobId(JOB_ID);
        run.setTenantId(TENANT);
        run.setJobStatus(JobStatus.Running);
        when(this.transactionService.findJobQueueByJobQueueId(QUEUE_ID)).thenReturn(Optional.of(run));
    }

    private static SourceJobQueueDto report(JobStatus status) {
        SourceJobQueueDto dto = new SourceJobQueueDto();
        dto.setJobId(JOB_ID);
        dto.setJobQueueId(QUEUE_ID);
        dto.setJobStatus(status);
        dto.setJobStatusMessage("done: 31 minutes transcribed");
        return dto;
    }

    @ParameterizedTest
    @EnumSource(value = Status.class, names = { "Delete", "Inactive" })
    void aRunWhoseJobWasDeletedMidRunStillLandsItsOutcomeAndIsMetered(Status jobStatus) {
        this.jobGoneMidRun(jobStatus);

        ResponseDto answer = this.service.changeState(report(JobStatus.Completed));

        assertThat(answer.getStatus()).isNotEqualTo(ERROR);
        verify(this.bulkAction).changeJobQueueStatus(QUEUE_ID, JobStatus.Completed, "done: 31 minutes transcribed");
        ArgumentCaptor<UsageEvent> metered = ArgumentCaptor.forClass(UsageEvent.class);
        verify(this.meter).report(metered.capture());
        assertThat(metered.getValue().tenantId).isEqualTo(TENANT);
        assertThat(metered.getValue().meter).isEqualTo(Meter.PIPELINE_RUNS.key());
        assertThat(metered.getValue().jobQueueId).isEqualTo(QUEUE_ID);
    }

    /** Deletion stops future usage: the failed run is recorded and metered, never queued for another attempt. */
    @Test
    void aFailedRunOfADeletedJobIsNotRetried() {
        this.jobGoneMidRun(Status.Delete);

        ResponseDto answer = this.service.changeState(report(JobStatus.Failed));

        assertThat(answer.getStatus()).isNotEqualTo(ERROR);
        verify(this.bulkAction, never()).scheduleRetry(anyLong(), anyLong(), anyString());
        verify(this.bulkAction).changeJobQueueStatus(QUEUE_ID, JobStatus.Failed, "done: 31 minutes transcribed");
        verify(this.meter).report(any(UsageEvent.class));
    }

    @Test
    void itsLogLinesStillLand() {
        this.jobGoneMidRun(Status.Delete);

        ResponseDto answer = this.service.addLogs(report(JobStatus.Running));

        assertThat(answer.getStatus()).isNotEqualTo(ERROR);
        verify(this.bulkAction).saveJobAuditLogs(QUEUE_ID, "done: 31 minutes transcribed");
    }
}

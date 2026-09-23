package process.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.enums.JobStatus;
import process.model.pojo.Scheduler;
import process.model.projection.SourceJobProjection;
import process.model.service.NotificationCenterService;
import process.model.service.impl.TransactionServiceImpl;
import process.socket.JobEventPublisher;
import process.socket.NotificationService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import process.notifications.TestNotifications;

/**
 * A run the scheduler records as Missed after downtime.
 *
 * notifyJobOutcome announces what source_job.job_running_status says, and recording a missed slot
 * does not change that column -- it still holds the PREVIOUS run's outcome. recordMissedRun used
 * the one-argument sendJobStatusNotification, which means "a new outcome just happened", so a job
 * whose last run had completed sent its owner one "Job completed -- finished successfully" per
 * missed slot, each dated to a moment nothing ran. The skip path had the same bug and was fixed
 * by passing isNewTransition = false (ProducerBulkEngine.skipManualJobInQueue); this is the other
 * path MIG-19 names.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class BulkActionMissedRunNotificationTest {

    private static final long JOB_ID = 1196L;

    @Mock private TransactionServiceImpl transactionService;
    @Mock private NotificationService notificationService;
    @Mock private NotificationCenterService notificationCenterService;
    @Mock private JobEventPublisher jobEventPublisher;

    private BulkAction bulkAction;

    @BeforeEach
    void setUp() {
        this.bulkAction = new BulkAction(this.transactionService, TestNotifications.inProcess(this.jobEventPublisher,
            this.notificationService, this.notificationCenterService, null));
        SourceJobProjection job = mock(SourceJobProjection.class);
        lenient().when(job.getJobId()).thenReturn(JOB_ID);
        lenient().when(job.getTenantId()).thenReturn(2905L);
        lenient().when(job.getJobName()).thenReturn("Nightly export");
        lenient().when(job.getAssignedUserId()).thenReturn(10L);
        lenient().when(job.getAssignedUsername()).thenReturn("ops@medaxis.example");
        // What the last run that DID happen left behind.
        lenient().when(job.getJobRunningStatus()).thenReturn(JobStatus.Completed);
        lenient().when(this.transactionService.fetchRunningJobEvent(anyList())).thenReturn(Collections.singletonList(job));
    }

    private static Scheduler hourlyThreeHoursBehind() {
        Scheduler scheduler = new Scheduler();
        scheduler.setJobId(JOB_ID);
        scheduler.setFrequency("Hr");
        scheduler.setIntervalValue("1");
        scheduler.setStartDate(LocalDate.now().minusYears(1));
        scheduler.setStartTime(LocalTime.of(0, 0));
        scheduler.setNextRunAt(LocalDateTime.now().minusHours(3));
        return scheduler;
    }

    @Test
    void aMissedSlotDoesNotReannounceThePreviousRunsOutcome() {
        this.bulkAction.updateNextScheduler(hourlyThreeHoursBehind());

        verify(this.notificationCenterService, never()).create(any(), any(), any(), any(), anyString(), anyString(), anyString());
    }

    @Test
    void aMissedSlotStillUpdatesTheOwnersLiveView() {
        this.bulkAction.updateNextScheduler(hourlyThreeHoursBehind());

        verify(this.notificationService, atLeastOnce()).sendNotificationToSpecificUser(eq("ops@medaxis.example"), anyString());
    }
}

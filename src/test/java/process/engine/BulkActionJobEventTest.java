package process.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.enums.JobStatus;
import process.model.pojo.SourceJob;
import process.model.service.NotificationCenterService;
import process.model.service.impl.TransactionServiceImpl;
import process.socket.JobEventPublisher;
import process.socket.NotificationService;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import process.notifications.TestNotifications;

/**
 * That a status change reaches the job list over the socket.
 *
 * Only the external worker callback used to announce itself. Every transition the platform made
 * on its own -- Queue when a job was enqueued, Start when the engine picked it up, Interrupt,
 * and the engine's own Failed -- changed the row and told nobody, so a job sat at its previous
 * status until someone pressed Refresh on a screen that claims to be live. Eight of the nine
 * writers were silent.
 *
 * The publish now sits in changeJobStatus, which all nine go through. These tests hold it there:
 * they are what fails if it is moved back out to the callers, or made conditional.
 *
 * It goes out through publishStatusAfterCommit, because BulkAction is @Transactional and a status
 * announced before the write is durable is a lie if that transaction rolls back. With no
 * transaction open -- which is the case here -- that method publishes immediately, so these tests
 * assert on the call they would see in production either way.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
public class BulkActionJobEventTest {

    private static final long TENANT_A = 1001L;
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
    }

    private SourceJob job(JobStatus held) {
        SourceJob sourceJob = new SourceJob();
        sourceJob.setJobId(JOB_ID);
        sourceJob.setTenantId(TENANT_A);
        sourceJob.setJobRunningStatus(held);
        return sourceJob;
    }

    @Test
    void theEngineStartingAJobIsAnnouncedToThatTenantsJobList() {
        when(this.transactionService.findByJobId(JOB_ID)).thenReturn(Optional.of(job(JobStatus.Queue)));

        this.bulkAction.changeJobStatus(JOB_ID, JobStatus.Start);

        verify(this.jobEventPublisher).publishStatusAfterCommit(eq(TENANT_A), eq(JOB_ID), isNull(),
            eq(JobStatus.Start.name()), isNull());
    }

    @Test
    void enqueuingAJobIsAnnouncedToo() {
        when(this.transactionService.findByJobId(JOB_ID)).thenReturn(Optional.of(job(JobStatus.Completed)));

        this.bulkAction.changeJobStatus(JOB_ID, JobStatus.Queue);

        verify(this.jobEventPublisher).publishStatusAfterCommit(eq(TENANT_A), eq(JOB_ID), isNull(),
            eq(JobStatus.Queue.name()), isNull());
    }

    /**
     * Running -> Running is a legal transition, not a no-op: it is how a worker says it is still
     * alive, and the jobs table advances its stall clock on each one. Treating a repeat as "no
     * change" and skipping the publish would let a healthy long run be reported as stalled.
     */
    @Test
    void aRepeatOfTheStatusAlreadyHeldIsStillAnnouncedBecauseThatIsTheHeartbeat() {
        when(this.transactionService.findByJobId(JOB_ID)).thenReturn(Optional.of(job(JobStatus.Running)));

        this.bulkAction.changeJobStatus(JOB_ID, JobStatus.Running);

        verify(this.jobEventPublisher).publishStatusAfterCommit(eq(TENANT_A), eq(JOB_ID), isNull(),
            eq(JobStatus.Running.name()), isNull());
    }

    @Test
    void aJobThatDoesNotExistChangesNothingAndAnnouncesNothing() {
        when(this.transactionService.findByJobId(JOB_ID)).thenReturn(Optional.empty());

        this.bulkAction.changeJobStatus(JOB_ID, JobStatus.Start);

        verify(this.transactionService, never()).saveOrUpdateJob(any(SourceJob.class));
        verifyNoInteractions(this.jobEventPublisher);
    }
}

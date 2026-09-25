package process.engine;

import process.util.BusinessTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Query;
import process.model.enums.JobStatus;
import process.model.pojo.Scheduler;
import process.model.projection.SourceJobProjection;
import process.model.repository.SourceJobRepository;
import process.model.service.impl.TransactionServiceImpl;
import process.notifications.TestNotifications;

import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * MIG-151: the live job-event read is one local read, and a push nobody can address still goes out.
 *
 * fetchRunningJobEvent fires on every job-state event. It joined app_user -- a table that leaves Core
 * with Identity -- for the owner's username; the username now sits on source_job itself, kept by the
 * database whenever the assignee is written or renamed (V85), so the read touches Core's own tables and
 * nothing else. And the missed-run replay, which re-read the same job once per missed slot (up to fifty),
 * reads it once.
 */
@ExtendWith(MockitoExtension.class)
class RunningJobEventLocalReadTest {

    private static final long JOB_ID = 1196L;

    @Mock private TransactionServiceImpl transactionService;
    @Mock private TestNotifications.FeedSink feed;

    private BulkAction bulkAction;
    private SourceJobProjection job;

    @BeforeEach
    void setUp() {
        this.bulkAction = new BulkAction(this.transactionService, TestNotifications.recording(this.feed, null, null));
        this.job = mock(SourceJobProjection.class);
        lenient().when(this.job.getJobId()).thenReturn(JOB_ID);
        lenient().when(this.job.getTenantId()).thenReturn(2905L);
        lenient().when(this.job.getJobName()).thenReturn("Nightly export");
        lenient().when(this.job.getAssignedUserId()).thenReturn(10L);
        lenient().when(this.transactionService.fetchRunningJobEvent(anyList())).thenReturn(Collections.singletonList(this.job));
    }

    /** Zero cross-service reads on the publish path: the statement names Core's tables only. */
    @Test
    void theEventReadTouchesNoTableThatLeavesCore() throws Exception {
        String sql = SourceJobRepository.class.getMethod("fetchRunningJobEvent", List.class)
            .getAnnotation(Query.class).value().toLowerCase(Locale.ROOT);

        assertThat(sql).doesNotContain("app_user");
        assertThat(sql).contains("sj.assigned_username as assignedusername");
    }

    /** No username on the job: the tenant's event still goes out, with the id. */
    @Test
    void aJobWithNoUsernameStillPublishesItsEventByUserId() {
        lenient().when(this.job.getAssignedUsername()).thenReturn(null);
        lenient().when(this.job.getJobRunningStatus()).thenReturn(JobStatus.Completed);

        this.bulkAction.sendJobStatusNotification(JOB_ID, 5705L, true);

        verify(this.feed).publishStatusAfterCommit(eq(2905L), eq(JOB_ID), eq(5705L), eq("Completed"), any());
    }

    /** The fifty-slot catch-up: one read of the job -- not one per missed slot. */
    @Test
    void fiftyMissedSlotsReadTheJobOnce() {
        lenient().when(this.job.getAssignedUsername()).thenReturn("ops@medaxis.example");
        lenient().when(this.job.getJobRunningStatus()).thenReturn(JobStatus.Completed);
        Scheduler scheduler = new Scheduler();
        scheduler.setJobId(JOB_ID);
        scheduler.setFrequency("Mint");
        scheduler.setIntervalValue("1");
        scheduler.setStartDate(BusinessTime.today().minusDays(1));
        scheduler.setStartTime(LocalTime.of(0, 0));
        scheduler.setNextRunAt(BusinessTime.now().minusMinutes(200));

        this.bulkAction.updateNextScheduler(scheduler);

        ArgumentCaptor<List<Long>> read = ArgumentCaptor.forClass(List.class);
        verify(this.transactionService, times(1)).fetchRunningJobEvent(read.capture());
        assertThat(read.getValue()).containsExactly(JOB_ID);
    }
}

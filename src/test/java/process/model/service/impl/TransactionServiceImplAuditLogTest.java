package process.model.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.model.pojo.JobAuditLogs;
import process.model.pojo.JobQueue;
import process.model.repository.JobAuditLogRepository;
import process.model.repository.JobQueueRepository;
import process.model.repository.LookupDataRepository;
import process.model.repository.SchedulerRepository;
import process.model.repository.SourceJobRepository;
import process.model.repository.SourceTaskRepository;
import process.util.OpenSearchAuditLogClient;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit lines are the only record of what a run did, and two ways of losing or corrupting them
 * lived here: a partially rejected bulk was reported as a whole-batch failure, so the lines
 * OpenSearch had accepted were written to the database as well and then rendered twice; and the
 * write took any queue id at all, so a caller naming a job it owns could append to another
 * tenant's run.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceImplAuditLogTest {

    private static final long JOB_ID = 1196L;
    private static final long QUEUE_ID = 91422L;
    private static final long QUEUE_OF_ANOTHER_JOB = 91423L;
    private static final long ANOTHER_JOB_ID = 1197L;

    @Mock private SourceJobRepository sourceJobRepository;
    @Mock private SchedulerRepository schedulerRepository;
    @Mock private JobQueueRepository jobQueueRepository;
    @Mock private LookupDataRepository lookupDataRepository;
    @Mock private JobAuditLogRepository jobAuditLogRepository;
    @Mock private SourceTaskRepository sourceTaskRepository;
    @Mock private OpenSearchAuditLogClient openSearchAuditLogClient;

    private TransactionServiceImpl service;

    @BeforeEach
    void setUp() {
        this.service = new TransactionServiceImpl(this.sourceJobRepository, this.schedulerRepository,
            this.jobQueueRepository, this.lookupDataRepository, this.jobAuditLogRepository,
            this.sourceTaskRepository, this.openSearchAuditLogClient);
    }

    @Test
    void onlyTheLinesOpenSearchRejectedAreWrittenToTheDatabase() {
        List<String> lines = Arrays.asList("accepted line", "rejected line", "another accepted line");
        when(this.openSearchAuditLogClient.indexAllReturningFailures(anyList()))
            .thenAnswer(invocation -> {
                List<Object[]> entries = invocation.getArgument(0);
                return Collections.singletonList(entries.get(1));
            });

        this.service.saveJobAuditLogs(QUEUE_ID, lines);

        ArgumentCaptor<JobAuditLogs> saved = ArgumentCaptor.forClass(JobAuditLogs.class);
        verify(this.jobAuditLogRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getLogsDetail()).isEqualTo("rejected line");
        assertThat(saved.getValue().getJobQueueId()).isEqualTo(QUEUE_ID);
    }

    @Test
    void nothingIsWrittenToTheDatabaseWhenOpenSearchTookEveryLine() {
        when(this.openSearchAuditLogClient.indexAllReturningFailures(anyList()))
            .thenReturn(new ArrayList<Object[]>());

        this.service.saveJobAuditLogs(QUEUE_ID, Arrays.asList("first", "second"));

        verify(this.jobAuditLogRepository, never()).save(any(JobAuditLogs.class));
    }

    @Test
    void everyLineFallsBackWhenOpenSearchTookNone() {
        when(this.openSearchAuditLogClient.indexAllReturningFailures(anyList()))
            .thenAnswer(invocation -> invocation.getArgument(0));

        this.service.saveJobAuditLogs(QUEUE_ID, Arrays.asList("first", "second"));

        verify(this.jobAuditLogRepository, times(2)).save(any(JobAuditLogs.class));
    }

    @Test
    void aLineIsWrittenWhenTheQueueBelongsToTheNamedJob() {
        when(this.jobQueueRepository.findById(QUEUE_ID)).thenReturn(Optional.of(queueOf(QUEUE_ID, JOB_ID)));
        when(this.openSearchAuditLogClient.index(anyString(), anyLong(),
            anyString(), any(Timestamp.class)))
            .thenReturn(true);

        this.service.saveJobAuditLogs(JOB_ID, QUEUE_ID, "run started");

        verify(this.openSearchAuditLogClient).index(anyString(),
            eq(QUEUE_ID), eq("run started"),
            any(Timestamp.class));
    }

    @Test
    void aLineIsRefusedWhenTheQueueBelongsToAnotherJob() {
        when(this.jobQueueRepository.findById(QUEUE_OF_ANOTHER_JOB))
            .thenReturn(Optional.of(queueOf(QUEUE_OF_ANOTHER_JOB, ANOTHER_JOB_ID)));

        this.service.saveJobAuditLogs(JOB_ID, QUEUE_OF_ANOTHER_JOB, "run started");

        verifyNothingWasWritten();
    }

    @Test
    void aBatchIsRefusedWhenTheQueueBelongsToAnotherJob() {
        when(this.jobQueueRepository.findById(QUEUE_OF_ANOTHER_JOB))
            .thenReturn(Optional.of(queueOf(QUEUE_OF_ANOTHER_JOB, ANOTHER_JOB_ID)));

        this.service.saveJobAuditLogs(JOB_ID, QUEUE_OF_ANOTHER_JOB, Arrays.asList("first", "second"));

        verifyNothingWasWritten();
    }

    @Test
    void aLineIsRefusedWhenTheQueueDoesNotExist() {
        when(this.jobQueueRepository.findById(QUEUE_ID)).thenReturn(Optional.empty());

        this.service.saveJobAuditLogs(JOB_ID, QUEUE_ID, "run started");

        verifyNothingWasWritten();
    }

    @Test
    void aLineIsRefusedWhenNoJobWasNamed() {
        this.service.saveJobAuditLogs(null, QUEUE_ID, "run started");

        verify(this.jobQueueRepository, never()).findById(anyLong());
        verifyNothingWasWritten();
    }

    private void verifyNothingWasWritten() {
        verify(this.jobAuditLogRepository, never()).save(any(JobAuditLogs.class));
        verify(this.openSearchAuditLogClient, never()).index(anyString(), anyLong(),
            anyString(), any(Timestamp.class));
        verify(this.openSearchAuditLogClient, never()).indexAllReturningFailures(anyList());
    }

    private static JobQueue queueOf(Long jobQueueId, Long jobId) {
        JobQueue jobQueue = new JobQueue();
        jobQueue.setJobQueueId(jobQueueId);
        jobQueue.setJobId(jobId);
        return jobQueue;
    }

}

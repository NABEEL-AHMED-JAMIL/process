package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.engine.ProducerBulkEngine;
import process.model.dto.ResponseDto;
import process.model.dto.SchedulerDto;
import process.model.dto.SourceJobDto;
import process.model.enums.Execution;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.Scheduler;
import process.model.pojo.SourceJob;
import process.model.repository.*;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.OpenSearchAuditLogClient;
import process.util.UserNameResolver;

import javax.persistence.EntityManager;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import process.notifications.TestNotifications;
import org.junit.jupiter.api.Test;

/**
 * "Run now" and "Skip next" refuse a job whose last run is still in flight -- including one in
 * Start, which is where a dispatched run sits until its worker says Running.
 *
 * The platform treats Queue, Start and Running as occupying the queue: the dispatcher counts
 * those three when deciding a job is busy, the stall sweep clears those three, and the queue
 * screen offers its actions on those three. These two manual paths checked only Queue and
 * Running. So a job dispatched a moment ago -- in Start, its worker not yet reporting -- could be
 * run again by hand, and two runs of one job would write into the same output folder at once,
 * which is the one thing the rest of the platform is arranged never to allow.
 *
 * It was the fourth copy of the in-flight set and the one that drifted. The set now lives once,
 * on {@link JobStatus#IN_FLIGHT}, and this test holds both paths to every member of it.
 */
@ExtendWith(MockitoExtension.class)
class SourceJobInFlightGuardTest {

    private static final long TENANT_A = 1001L;
    private static final long CALLER_ID = 77L;
    private static final long JOB_ID = 1244L;

    @Mock private SourceJobRepository sourceJobRepository;
    @Mock private SchedulerRepository schedulerRepository;
    @Mock private SourceTaskRepository sourceTaskRepository;
    @Mock private JobAuditLogRepository jobAuditLogRepository;
    @Mock private TestNotifications.FeedSink jobEventPublisher;
    @Mock private JobQueueRepository jobQueueRepository;
    @Mock private LookupDataRepository lookupDataRepository;
    @Mock private AppUserRepository appUserRepository;
    @Mock private ProducerBulkEngine producerBulkEngine;
    @Mock private TenantFilterHelper tenantFilterHelper;
    @Mock private OpenSearchAuditLogClient openSearchAuditLogClient;
    @Mock private TestNotifications.NoticeSink notificationCenterService;
    @Mock private EntityManager entityManager;
    @Mock private UserNameResolver userNameResolver;

    private SourceJobServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        this.service = new SourceJobServiceImpl(this.sourceJobRepository, this.schedulerRepository,
            this.sourceTaskRepository, this.jobAuditLogRepository,
            this.jobQueueRepository, this.lookupDataRepository, this.appUserRepository,
            this.producerBulkEngine, this.tenantFilterHelper, this.openSearchAuditLogClient,
            TestNotifications.recording(this.jobEventPublisher, null, this.notificationCenterService, null), this.userNameResolver);
        Field em = SourceJobServiceImpl.class.getDeclaredField("entityManager");
        em.setAccessible(true);
        em.set(this.service, this.entityManager);
        lenient().doNothing().when(this.tenantFilterHelper).enableIfNeeded(any());
        TenantContext.set(TENANT_A, "TENANT_ADMIN", CALLER_ID, "admin-a@example.com");
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private SourceJob autoJobWhoseLastRunIs(JobStatus runningStatus) {
        SourceJob sourceJob = new SourceJob();
        sourceJob.setJobId(JOB_ID);
        sourceJob.setTenantId(TENANT_A);
        sourceJob.setJobStatus(Status.Active);
        sourceJob.setExecution(Execution.Auto);
        sourceJob.setJobRunningStatus(runningStatus);
        when(this.sourceJobRepository.findByJobIdAndJobStatus(JOB_ID, Status.Active))
            .thenReturn(Optional.of(sourceJob));
        return sourceJob;
    }

    private static SourceJobDto request() {
        SourceJobDto sourceJobDto = new SourceJobDto();
        sourceJobDto.setJobId(JOB_ID);
        return sourceJobDto;
    }

    // ---- refused while a run is in flight -----------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = { "Queue", "Start", "Running" })
    void runNowIsRefusedWhileTheLastRunIsInFlight(JobStatus inFlight) throws Exception {
        this.autoJobWhoseLastRunIs(inFlight);

        ResponseDto response = this.service.runSourceJob(request());

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("in flight");
        verify(this.producerBulkEngine, never()).addManualJobInQueue(any());
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = { "Queue", "Start", "Running" })
    void skipNextIsRefusedWhileTheLastRunIsInFlight(JobStatus inFlight) throws Exception {
        this.autoJobWhoseLastRunIs(inFlight);

        ResponseDto response = this.service.skipNextSourceJob(request());

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("in flight");
        // Refused at the guard, before the schedule is even looked at. Without this the test
        // passed for Start against the broken code: the guard let it through, the unstubbed
        // scheduler lookup came back empty, and "no scheduler to skip" was an ERROR for the wrong
        // reason -- a green test over exactly the defect it was written to catch.
        verify(this.schedulerRepository, never()).findSchedulerByJobId(any());
        verify(this.producerBulkEngine, never()).skipManualJobInQueue(any());
    }

    // ---- allowed once the last run has finished, however it finished ---------------------------

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = { "Failed", "Completed", "Skip", "Interrupt", "Missed" })
    void runNowIsAllowedOnceTheLastRunHasFinished(JobStatus finished) throws Exception {
        this.autoJobWhoseLastRunIs(finished);

        ResponseDto response = this.service.runSourceJob(request());

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(this.producerBulkEngine).addManualJobInQueue(any());
        // MIG-67: a DTO, never the entity, whose lazy graph Jackson walked after the transaction closed.
        assertThat(response.getData()).isInstanceOf(SourceJobDto.class);
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = { "Failed", "Completed", "Skip", "Interrupt", "Missed" })
    void skipNextIsAllowedOnceTheLastRunHasFinished(JobStatus finished) throws Exception {
        this.autoJobWhoseLastRunIs(finished);
        LocalDateTime dueAt = LocalDateTime.now().plusDays(1)
            .withHour(14).withMinute(0).withSecond(0).withNano(0);
        Scheduler scheduler = new Scheduler();
        scheduler.setSchedulerId(9001L);
        scheduler.setJobId(JOB_ID);
        scheduler.setFrequency("Daily");
        scheduler.setIntervalValue("1");
        scheduler.setStartDate(LocalDate.now());
        scheduler.setStartTime(LocalTime.of(14, 0));
        scheduler.setNextRunAt(dueAt);
        when(this.schedulerRepository.findSchedulerByJobId(JOB_ID)).thenReturn(Optional.of(scheduler));

        ResponseDto response = this.service.skipNextSourceJob(request());

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(this.producerBulkEngine).skipManualJobInQueue(scheduler);
        assertThat(response.getData()).isInstanceOf(SchedulerDto.class);
    }

    // ---- a job that has never run ---------------------------------------------------------------

    @Test
    void aJobThatHasNeverRunCanBeRunNow() throws Exception {
        this.autoJobWhoseLastRunIs(null);

        ResponseDto response = this.service.runSourceJob(request());

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(this.producerBulkEngine).addManualJobInQueue(any());
    }
}

package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.engine.ProducerBulkEngine;
import process.identity.TestIdentity;
import process.model.dto.ResponseDto;
import process.model.dto.SchedulerDto;
import process.model.dto.SourceJobDto;
import process.model.dto.SourceTaskDto;
import process.model.enums.Execution;
import process.model.enums.Status;
import process.model.enums.UserRole;
import process.model.pojo.AppUser;
import process.model.pojo.Scheduler;
import process.model.pojo.SourceJob;
import process.model.pojo.SourceTask;
import process.model.repository.*;
import process.notifications.TestNotifications;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.BusinessTime;
import process.util.OpenSearchAuditLogClient;
import process.util.UserNameResolver;

import javax.persistence.EntityManager;
import java.lang.reflect.Field;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * The Cron frequency (Wave 4) on a job's create and update paths: a good expression is stored tidied and seeds the next
 * run on its first slot; a bad or too-frequent one is refused before anything is written, in words the caller can act
 * on; re-posting the same Cron timetable leaves a skipped run skipped; moving a schedule off Cron drops the expression;
 * and the job list and detail carry the expression with nextRunAt. Driven through the service with mocked repositories,
 * as SourceJobLifecycleDefectTest.
 */
@ExtendWith(MockitoExtension.class)
public class SourceJobCronScheduleTest {

    private static final long TENANT_A = 1001L;
    private static final long CALLER_ID = 77L;
    private static final long TASK_ID = 4200L;
    private static final long JOB_ID = 1244L;

    @Mock private SourceJobRepository sourceJobRepository;
    @Mock private SchedulerRepository schedulerRepository;
    @Mock private SourceTaskRepository sourceTaskRepository;
    @Mock private JobAuditLogRepository jobAuditLogRepository;
    @Mock private TestNotifications.FeedSink jobEventPublisher;
    @Mock private JobQueueRepository jobQueueRepository;
    @Mock private TaskReferenceRepository taskReferenceRepository;
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
            this.jobQueueRepository, this.taskReferenceRepository, TestIdentity.over(this.appUserRepository, null),
            this.producerBulkEngine, this.tenantFilterHelper, this.openSearchAuditLogClient,
            TestNotifications.recording(this.jobEventPublisher, this.notificationCenterService, null), this.userNameResolver);
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

    private static SourceTask taskOwnedByA() {
        SourceTask sourceTask = new SourceTask();
        sourceTask.setTaskDetailId(TASK_ID);
        sourceTask.setTenantId(TENANT_A);
        sourceTask.setTaskName("orders nightly");
        sourceTask.setTaskStatus(Status.Active);
        return sourceTask;
    }

    private static AppUser callerUser() {
        AppUser appUser = new AppUser();
        appUser.setAppUserId(CALLER_ID);
        appUser.setUsername("admin-a@example.com");
        appUser.setTenantId(TENANT_A);
        appUser.setStatus(Status.Active);
        appUser.setUserRole(UserRole.TENANT_ADMIN);
        return appUser;
    }

    private static SchedulerDto cron(String expression) {
        SchedulerDto schedulerDto = new SchedulerDto();
        schedulerDto.setStartDate(BusinessTime.today());
        schedulerDto.setStartTime(LocalTime.of(0, 0));
        schedulerDto.setFrequency("Cron");
        schedulerDto.setCronExpression(expression);
        return schedulerDto;
    }

    private static SourceJobDto jobWith(SchedulerDto schedulerDto) {
        SourceTaskDto taskDetail = new SourceTaskDto();
        taskDetail.setTaskDetailId(TASK_ID);
        SourceJobDto sourceJobDto = new SourceJobDto();
        sourceJobDto.setJobName("weekday nine");
        sourceJobDto.setTaskDetail(taskDetail);
        sourceJobDto.setExecution(Execution.Auto);
        sourceJobDto.setPriority(1);
        if (schedulerDto != null) {
            sourceJobDto.setSchedulers(new LinkedHashSet<>(Collections.singletonList(schedulerDto)));
        }
        return sourceJobDto;
    }

    private Scheduler savedScheduler() {
        ArgumentCaptor<Scheduler> captor = ArgumentCaptor.forClass(Scheduler.class);
        verify(this.schedulerRepository).save(captor.capture());
        return captor.getValue();
    }

    private static SourceJob existingAutoJob() {
        SourceJob existing = new SourceJob();
        existing.setJobId(JOB_ID);
        existing.setTenantId(TENANT_A);
        existing.setJobStatus(Status.Active);
        existing.setExecution(Execution.Auto);
        return existing;
    }

    private void updateCollaboratorsResolve(Scheduler stored) {
        when(this.sourceJobRepository.findById(JOB_ID)).thenReturn(Optional.of(existingAutoJob()));
        when(this.sourceTaskRepository.findByTaskDetailIdAndTaskStatus(TASK_ID, Status.Active))
            .thenReturn(Optional.of(taskOwnedByA()));
        when(this.schedulerRepository.findSchedulerByJobId(JOB_ID)).thenReturn(Optional.ofNullable(stored));
    }

    // ---- create ------------------------------------------------------------------------------------------------------

    @Test
    void aCronJobIsStoredTidiedAndSeededOnItsFirstSlot() throws Exception {
        when(this.sourceTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(taskOwnedByA()));
        when(this.appUserRepository.findById(CALLER_ID)).thenReturn(Optional.of(callerUser()));

        ResponseDto response = this.service.addSourceJob(jobWith(cron("  0 9 *  * MON-FRI ")));

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        Scheduler written = this.savedScheduler();
        assertThat(written.getFrequency()).isEqualTo("Cron");
        assertThat(written.getCronExpression()).isEqualTo("0 9 * * MON-FRI");
        LocalDateTime first = written.getNextRunAt();
        assertThat(first).isAfter(BusinessTime.now());
        assertThat(first.toLocalTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(first.getDayOfWeek()).isNotIn(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);
        assertThat(written.isExpired()).isFalse();
    }

    @Test
    void anInvalidExpressionIsRefusedBeforeAnythingIsWritten() throws Exception {
        ResponseDto response = this.service.addSourceJob(jobWith(cron("0 9 * * FUNDAY")));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).startsWith("SourceJob schedule: ").contains("'0 9 * * FUNDAY' is not a cron expression");
        verify(this.sourceJobRepository, never()).saveAndFlush(any());
        verify(this.schedulerRepository, never()).save(any());
    }

    @Test
    void aSecondsLevelExpressionIsRefusedAsTooFrequent() throws Exception {
        ResponseDto response = this.service.addSourceJob(jobWith(cron("*/5 * * * * *")));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("at most once a minute");
        verify(this.sourceJobRepository, never()).saveAndFlush(any());
    }

    @Test
    void aCronScheduleWithNoExpressionIsRefused() throws Exception {
        ResponseDto response = this.service.addSourceJob(jobWith(cron(null)));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("needs a cron expression");
    }

    /** The start date and time only bound a Cron schedule; a caller that sends just the expression gets "from now". */
    @Test
    void aCronScheduleMayLeaveOutItsStart() throws Exception {
        when(this.sourceTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(taskOwnedByA()));
        when(this.appUserRepository.findById(CALLER_ID)).thenReturn(Optional.of(callerUser()));
        SchedulerDto bare = cron("*/15 * * * *");
        bare.setStartDate(null);
        bare.setStartTime(null);

        assertThat(this.service.addSourceJob(jobWith(bare)).getStatus()).isEqualTo("SUCCESS");

        Scheduler written = this.savedScheduler();
        assertThat(written.getStartDate()).isEqualTo(BusinessTime.today());
        assertThat(written.getStartTime()).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(written.getNextRunAt()).isAfter(BusinessTime.now()).isBeforeOrEqualTo(BusinessTime.now().plusMinutes(15));
        assertThat(written.getNextRunAt().getMinute() % 15).isZero();
    }

    /** A start in the future is honoured: the first slot is the first at or after it. */
    @Test
    void aFutureStartIsTheEarliestSlot() throws Exception {
        when(this.sourceTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(taskOwnedByA()));
        when(this.appUserRepository.findById(CALLER_ID)).thenReturn(Optional.of(callerUser()));
        SchedulerDto later = cron("0 * * * *");
        LocalDate start = BusinessTime.today().plusDays(3);
        later.setStartDate(start);
        later.setStartTime(LocalTime.of(10, 0));

        this.service.addSourceJob(jobWith(later));

        assertThat(this.savedScheduler().getNextRunAt()).isEqualTo(start.atTime(10, 0));
    }

    // ---- update ------------------------------------------------------------------------------------------------------

    @Test
    void anUpdateRefusesABadExpressionAndLeavesTheStoredScheduleAlone() throws Exception {
        ResponseDto response = this.service.updateSourceJob(withId(jobWith(cron("0 9 * *"))));

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("5 fields").contains("got 4");
        verify(this.schedulerRepository, never()).save(any());
        verify(this.sourceJobRepository, never()).saveAndFlush(any());
    }

    @Test
    void reSavingTheSameCronTimetableKeepsASkippedNextRun() throws Exception {
        Scheduler stored = new Scheduler();
        stored.setSchedulerId(9001L);
        stored.setJobId(JOB_ID);
        stored.setStartDate(BusinessTime.today());
        stored.setStartTime(LocalTime.MIDNIGHT);
        stored.setFrequency("Cron");
        stored.setCronExpression("0 9 * * MON-FRI");
        LocalDateTime skippedTo = BusinessTime.now().plusDays(6).withHour(9).withMinute(0).withSecond(0).withNano(0);
        stored.setNextRunAt(skippedTo);
        this.updateCollaboratorsResolve(stored);

        // The same expression, differently spaced, and no start: the same timetable.
        SchedulerDto posted = cron("0  9 * * MON-FRI");
        posted.setStartDate(null);
        posted.setStartTime(null);
        this.service.updateSourceJob(withId(jobWith(posted)));

        assertThat(stored.getNextRunAt()).isEqualTo(skippedTo);
        verify(this.schedulerRepository, never()).save(any());
    }

    @Test
    void aChangedExpressionIsReseeded() throws Exception {
        Scheduler stored = new Scheduler();
        stored.setSchedulerId(9001L);
        stored.setJobId(JOB_ID);
        stored.setStartDate(BusinessTime.today());
        stored.setStartTime(LocalTime.MIDNIGHT);
        stored.setFrequency("Cron");
        stored.setCronExpression("0 9 * * MON-FRI");
        stored.setNextRunAt(BusinessTime.now().plusDays(6));
        this.updateCollaboratorsResolve(stored);

        this.service.updateSourceJob(withId(jobWith(cron("30 * * * *"))));

        Scheduler saved = this.savedScheduler();
        assertThat(saved.getCronExpression()).isEqualTo("30 * * * *");
        assertThat(saved.getNextRunAt().getMinute()).isEqualTo(30);
        assertThat(saved.getNextRunAt()).isBeforeOrEqualTo(BusinessTime.now().plusHours(1));
    }

    @Test
    void movingAScheduleOffCronDropsTheExpression() throws Exception {
        Scheduler stored = new Scheduler();
        stored.setSchedulerId(9001L);
        stored.setJobId(JOB_ID);
        stored.setFrequency("Cron");
        stored.setCronExpression("0 9 * * *");
        this.updateCollaboratorsResolve(stored);
        SchedulerDto daily = new SchedulerDto();
        daily.setStartDate(BusinessTime.today());
        daily.setStartTime(LocalTime.of(2, 0));
        daily.setFrequency("Daily");
        daily.setIntervalValue("1");
        daily.setCronExpression("0 9 * * *");

        this.service.updateSourceJob(withId(jobWith(daily)));

        Scheduler saved = this.savedScheduler();
        assertThat(saved.getFrequency()).isEqualTo("Daily");
        assertThat(saved.getCronExpression()).isNull();
        assertThat(saved.getNextRunAt().toLocalTime()).isEqualTo(LocalTime.of(2, 0));
    }

    // ---- read back ---------------------------------------------------------------------------------------------------

    @Test
    void theJobListCarriesTheExpressionAndTheNextRun() throws Exception {
        SourceJob job = existingAutoJob();
        Scheduler stored = new Scheduler();
        stored.setSchedulerId(9001L);
        stored.setJobId(JOB_ID);
        stored.setStartDate(BusinessTime.today());
        stored.setStartTime(LocalTime.MIDNIGHT);
        stored.setFrequency("Cron");
        stored.setCronExpression("0 9 * * MON-FRI");
        LocalDateTime next = BusinessTime.now().plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0);
        stored.setNextRunAt(next);
        when(this.sourceJobRepository.findAllActiveAndInactiveJobs(eq(Status.Active), eq(Status.Inactive), any()))
            .thenReturn(Collections.singletonList(job));
        when(this.schedulerRepository.findByJobIdIn(anyList())).thenReturn(Collections.singletonList(stored));
        when(this.jobQueueRepository.countGroupByJobIds(anyList())).thenReturn(Collections.emptyList());

        ResponseDto response = this.service.listSourceJob();

        List<?> rows = (List<?>) response.getData();
        SchedulerDto shown = ((SourceJobDto) rows.get(0)).getScheduler();
        assertThat(shown.getFrequency()).isEqualTo("Cron");
        assertThat(shown.getCronExpression()).isEqualTo("0 9 * * MON-FRI");
        assertThat(shown.getNextRunAt()).isEqualTo(next);
    }

    private static SourceJobDto withId(SourceJobDto sourceJobDto) {
        sourceJobDto.setJobId(JOB_ID);
        return sourceJobDto;
    }
}

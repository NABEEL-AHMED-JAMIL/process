package process.model.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import process.identity.TestIdentity;
import process.engine.ProducerBulkEngine;
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
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.OpenSearchAuditLogClient;
import process.util.UserNameResolver;

import javax.persistence.EntityManager;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import process.notifications.TestNotifications;

/**
 * What a job's create and update paths do with the fields the caller actually sent.
 *
 * Three separate defects lived here and none of them was covered: the requested jobStatus was
 * discarded on create so every clone and every "save as Inactive" produced a live, scheduling
 * job; a posted timetable was discarded on update whenever the job had no Scheduler row yet, so
 * switching a Manual job to Auto saved nothing and said "Job updated."; and a payload carrying
 * two schedules wrote two rows, after which every by-id read of that job was an HTTP 500.
 *
 * Driven through the service with mocked repositories: the questions are all "what did it decide
 * to write", which is exactly what a captured save answers and what no integration test would say
 * more clearly.
 */
@ExtendWith(MockitoExtension.class)
public class SourceJobLifecycleDefectTest {

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

    // ---- fixtures ---------------------------------------------------------------------------

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

    private static SchedulerDto dailyAtTwo() {
        SchedulerDto schedulerDto = new SchedulerDto();
        schedulerDto.setStartDate(LocalDate.now());
        schedulerDto.setStartTime(LocalTime.of(2, 0));
        schedulerDto.setFrequency("Daily");
        schedulerDto.setIntervalValue("1");
        return schedulerDto;
    }

    private static SourceJobDto creationDto() {
        SourceTaskDto taskDetail = new SourceTaskDto();
        taskDetail.setTaskDetailId(TASK_ID);
        SourceJobDto sourceJobDto = new SourceJobDto();
        sourceJobDto.setJobName("nightly load (copy)");
        sourceJobDto.setTaskDetail(taskDetail);
        sourceJobDto.setExecution(Execution.Auto);
        sourceJobDto.setPriority(1);
        return sourceJobDto;
    }

    private void creationCollaboratorsResolve() {
        when(this.sourceTaskRepository.findById(TASK_ID)).thenReturn(Optional.of(taskOwnedByA()));
        when(this.appUserRepository.findById(CALLER_ID)).thenReturn(Optional.of(callerUser()));
    }

    private SourceJob savedJob() {
        ArgumentCaptor<SourceJob> captor = ArgumentCaptor.forClass(SourceJob.class);
        verify(this.sourceJobRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    // ---- the requested status is honoured on create ------------------------------------------

    /**
     * The clone path. jobs.ts posts Inactive and then tells the operator "it starts inactive";
     * the service wrote Active, so cloning an Auto job doubled its runs from the next slot.
     */
    @Test
    void createHonoursAnExplicitlyInactiveStatus() throws Exception {
        this.creationCollaboratorsResolve();
        SourceJobDto sourceJobDto = creationDto();
        sourceJobDto.setJobStatus(Status.Inactive);

        ResponseDto response = this.service.addSourceJob(sourceJobDto);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(this.savedJob().getJobStatus()).isEqualTo(Status.Inactive);
    }

    /** A caller that says nothing still gets a live job -- that is what the constant was for. */
    @Test
    void createStillDefaultsToActiveWhenNoStatusIsSent() throws Exception {
        this.creationCollaboratorsResolve();

        ResponseDto response = this.service.addSourceJob(creationDto());

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(this.savedJob().getJobStatus()).isEqualTo(Status.Active);
    }

    /** Honouring the field must not make the tombstone status creatable. */
    @Test
    void createRefusesToBeBornDeleted() throws Exception {
        SourceJobDto sourceJobDto = creationDto();
        sourceJobDto.setJobStatus(Status.Delete);

        ResponseDto response = this.service.addSourceJob(sourceJobDto);

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("cannot be created as Delete");
        verify(this.sourceJobRepository, never()).saveAndFlush(any());
    }

    /** An Inactive job must not be seeded with a live schedule that the copy then inherits. */
    @Test
    void anInactiveCloneStillRecordsItsTimetable() throws Exception {
        this.creationCollaboratorsResolve();
        SourceJobDto sourceJobDto = creationDto();
        sourceJobDto.setJobStatus(Status.Inactive);
        sourceJobDto.setSchedulers(new LinkedHashSet<>(Collections.singletonList(dailyAtTwo())));

        this.service.addSourceJob(sourceJobDto);

        // The row is written -- findDueSchedulers is what keeps an Inactive job from running,
        // not the absence of a schedule -- so switching it on later resumes rather than loses it.
        ArgumentCaptor<Scheduler> schedule = ArgumentCaptor.forClass(Scheduler.class);
        verify(this.schedulerRepository).save(schedule.capture());
        // MIG-29: with the job's tenant.
        assertThat(schedule.getValue().getTenantId()).isEqualTo(TENANT_A);
        assertThat(this.savedJob().getJobStatus()).isEqualTo(Status.Inactive);
    }

    // ---- a posted timetable is saved even when the job had none -------------------------------

    /**
     * Manual to Auto. The job has no Scheduler row, because a Manual job never gets one, and the
     * update path only ever touched an existing row -- so the timetable went nowhere and the
     * console said "Job updated."
     */
    @Test
    void switchingAManualJobToAutoCreatesTheSchedulerRow() throws Exception {
        SourceJob existing = new SourceJob();
        existing.setJobId(JOB_ID);
        existing.setTenantId(TENANT_A);
        existing.setJobStatus(Status.Active);
        existing.setExecution(Execution.Manual);
        when(this.sourceJobRepository.findById(JOB_ID)).thenReturn(Optional.of(existing));
        when(this.sourceTaskRepository.findByTaskDetailIdAndTaskStatus(TASK_ID, Status.Active))
            .thenReturn(Optional.of(taskOwnedByA()));
        when(this.schedulerRepository.findSchedulerByJobId(JOB_ID)).thenReturn(Optional.empty());

        SourceJobDto sourceJobDto = creationDto();
        sourceJobDto.setJobId(JOB_ID);
        sourceJobDto.setExecution(Execution.Auto);
        sourceJobDto.setSchedulers(new LinkedHashSet<>(Collections.singletonList(dailyAtTwo())));

        ResponseDto response = this.service.updateSourceJob(sourceJobDto);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        ArgumentCaptor<Scheduler> captor = ArgumentCaptor.forClass(Scheduler.class);
        verify(this.schedulerRepository).save(captor.capture());
        Scheduler written = captor.getValue();
        assertThat(written.getJobId()).isEqualTo(JOB_ID);
        assertThat(written.getFrequency()).isEqualTo("Daily");
        assertThat(written.getStartTime()).isEqualTo(LocalTime.of(2, 0));
        // Seeded, or findDueSchedulers has nothing to compare against and the job never runs.
        assertThat(written.getNextRunAt()).isNotNull();
        // MIG-29: a new row carries its job's tenant.
        assertThat(written.getTenantId()).isEqualTo(TENANT_A);
    }

    /** An existing row is still updated in place rather than duplicated. */
    @Test
    void anExistingSchedulerIsUpdatedRatherThanDuplicated() throws Exception {
        SourceJob existing = new SourceJob();
        existing.setJobId(JOB_ID);
        existing.setTenantId(TENANT_A);
        existing.setJobStatus(Status.Active);
        existing.setExecution(Execution.Auto);
        Scheduler current = new Scheduler();
        current.setSchedulerId(9001L);
        current.setJobId(JOB_ID);
        current.setFrequency("Weekly");
        when(this.sourceJobRepository.findById(JOB_ID)).thenReturn(Optional.of(existing));
        when(this.sourceTaskRepository.findByTaskDetailIdAndTaskStatus(TASK_ID, Status.Active))
            .thenReturn(Optional.of(taskOwnedByA()));
        when(this.schedulerRepository.findSchedulerByJobId(JOB_ID)).thenReturn(Optional.of(current));

        SourceJobDto sourceJobDto = creationDto();
        sourceJobDto.setJobId(JOB_ID);
        sourceJobDto.setSchedulers(new LinkedHashSet<>(Collections.singletonList(dailyAtTwo())));

        this.service.updateSourceJob(sourceJobDto);

        ArgumentCaptor<Scheduler> captor = ArgumentCaptor.forClass(Scheduler.class);
        verify(this.schedulerRepository).save(captor.capture());
        assertThat(captor.getValue().getSchedulerId()).isEqualTo(9001L);
        assertThat(captor.getValue().getFrequency()).isEqualTo("Daily");
    }

    // ---- re-posting an unchanged timetable keeps a skipped run skipped ----------------------------

    /**
     * UI review, 2026-09-24 (verified, high): every save re-posts the job's timetable (Email notifications, a priority
     * change, the editor), and the update re-seeded next_run_at from the start date -- bringing back the run the
     * operator had skipped, while the Skip row stayed in the history. An unchanged timetable now leaves the row alone.
     */
    @Test
    void reSavingAnUnchangedTimetableKeepsTheSkippedNextRun() throws Exception {
        SchedulerDto posted = dailyAtTwo();
        Scheduler stored = new Scheduler();
        stored.setSchedulerId(9001L);
        stored.setJobId(JOB_ID);
        stored.setStartDate(posted.getStartDate());
        stored.setStartTime(posted.getStartTime());
        stored.setFrequency(posted.getFrequency());
        stored.setIntervalValue(posted.getIntervalValue());
        LocalDateTime skippedTo = LocalDateTime.now().plusDays(2).withHour(2).withMinute(0).withSecond(0).withNano(0);
        stored.setNextRunAt(skippedTo);
        this.updateCollaboratorsResolve(stored);

        SourceJobDto sourceJobDto = creationDto();
        sourceJobDto.setJobId(JOB_ID);
        sourceJobDto.setSchedulers(new LinkedHashSet<>(Collections.singletonList(posted)));
        this.service.updateSourceJob(sourceJobDto);

        assertThat(stored.getNextRunAt()).isEqualTo(skippedTo);
    }

    @Test
    void aChangedTimetableIsStillReseeded() throws Exception {
        Scheduler stored = new Scheduler();
        stored.setSchedulerId(9001L);
        stored.setJobId(JOB_ID);
        stored.setFrequency("Weekly");
        stored.setNextRunAt(LocalDateTime.now().plusDays(9));
        this.updateCollaboratorsResolve(stored);

        SourceJobDto sourceJobDto = creationDto();
        sourceJobDto.setJobId(JOB_ID);
        sourceJobDto.setSchedulers(new LinkedHashSet<>(Collections.singletonList(dailyAtTwo())));
        this.service.updateSourceJob(sourceJobDto);

        Scheduler saved = this.savedScheduler();
        assertThat(saved.getFrequency()).isEqualTo("Daily");
        assertThat(saved.getNextRunAt()).isBefore(LocalDateTime.now().plusDays(2));
    }

    // ---- an end date can be taken off a schedule ----------------------------------------------

    private SourceJob existingAutoJob() {
        SourceJob existing = new SourceJob();
        existing.setJobId(JOB_ID);
        existing.setTenantId(TENANT_A);
        existing.setJobStatus(Status.Active);
        existing.setExecution(Execution.Auto);
        return existing;
    }

    private Scheduler savedScheduler() {
        ArgumentCaptor<Scheduler> captor = ArgumentCaptor.forClass(Scheduler.class);
        verify(this.schedulerRepository).save(captor.capture());
        return captor.getValue();
    }

    private void updateCollaboratorsResolve(Scheduler stored) {
        when(this.sourceJobRepository.findById(JOB_ID)).thenReturn(Optional.of(existingAutoJob()));
        when(this.sourceTaskRepository.findByTaskDetailIdAndTaskStatus(TASK_ID, Status.Active))
            .thenReturn(Optional.of(taskOwnedByA()));
        when(this.schedulerRepository.findSchedulerByJobId(JOB_ID)).thenReturn(Optional.of(stored));
    }

    /**
     * The DTO carries one nullable LocalDate, so "the caller left the field out" and "the caller
     * cleared the field" arrive as the same value -- and the assignment was the one on this
     * timetable that was guarded. Clearing End date in the editor therefore answered "Job save
     * with jobId N.", the console toasted "Job updated.", and the row kept the old date: the job
     * went on stopping for good on a date the operator had just deleted and could see was gone.
     */
    @Test
    void clearingTheEndDateRemovesItRatherThanKeepingTheStoredOne() throws Exception {
        Scheduler stored = new Scheduler();
        stored.setSchedulerId(9001L);
        stored.setJobId(JOB_ID);
        stored.setEndDate(LocalDate.of(2026, 3, 31));
        this.updateCollaboratorsResolve(stored);

        SourceJobDto sourceJobDto = creationDto();
        sourceJobDto.setJobId(JOB_ID);
        // dailyAtTwo() carries no end date, which is exactly what a cleared date input posts.
        sourceJobDto.setSchedulers(new LinkedHashSet<>(Collections.singletonList(dailyAtTwo())));

        ResponseDto response = this.service.updateSourceJob(sourceJobDto);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(this.savedScheduler().getEndDate()).isNull();
    }

    /** The other direction: a posted end date is still stored, so this is not "ignore the field". */
    @Test
    void aPostedEndDateIsStillStored() throws Exception {
        Scheduler stored = new Scheduler();
        stored.setSchedulerId(9001L);
        stored.setJobId(JOB_ID);
        this.updateCollaboratorsResolve(stored);

        LocalDate endsOn = LocalDate.now().plusMonths(1);
        SchedulerDto schedulerDto = dailyAtTwo();
        schedulerDto.setEndDate(endsOn);
        SourceJobDto sourceJobDto = creationDto();
        sourceJobDto.setJobId(JOB_ID);
        sourceJobDto.setSchedulers(new LinkedHashSet<>(Collections.singletonList(schedulerDto)));

        this.service.updateSourceJob(sourceJobDto);

        assertThat(this.savedScheduler().getEndDate()).isEqualTo(endsOn);
    }

    // ---- skip next run asks the question the engine answers ------------------------------------

    private SourceJob activeAutoJob() {
        SourceJob sourceJob = new SourceJob();
        sourceJob.setJobId(JOB_ID);
        sourceJob.setTenantId(TENANT_A);
        sourceJob.setJobStatus(Status.Active);
        sourceJob.setExecution(Execution.Auto);
        return sourceJob;
    }

    /** Hourly from 09:00, already seeded to a slot in the middle of a day. */
    private static Scheduler hourlySchedule(LocalDateTime dueAt, LocalDate endDate) {
        Scheduler scheduler = new Scheduler();
        scheduler.setSchedulerId(9001L);
        scheduler.setJobId(JOB_ID);
        scheduler.setFrequency("Hr");
        scheduler.setIntervalValue("1");
        scheduler.setStartDate(dueAt.toLocalDate());
        scheduler.setStartTime(LocalTime.of(9, 0));
        scheduler.setNextRunAt(dueAt);
        scheduler.setEndDate(endDate);
        return scheduler;
    }

    private static SourceJobDto skipRequest() {
        SourceJobDto sourceJobDto = new SourceJobDto();
        sourceJobDto.setJobId(JOB_ID);
        return sourceJobDto;
    }

    /**
     * An hourly schedule has slots all day, and the engine keeps accepting them while their DATE
     * is not after the end date -- applyNextRun and isLastFlight both compare that way. Skip next
     * run compared full timestamps against the end date at the schedule's START TIME, so on the
     * final day every slot after the first was judged beyond the window: the operator was told
     * "No more flight skip." about a run the engine was going to make within the hour, and there
     * was no other way to skip it.
     */
    @Test
    void aLaterSlotOnTheFinalDayCanStillBeSkipped() throws Exception {
        // Tomorrow at 14:00, so the slot being skipped is 15:00 on the end date itself and the
        // arithmetic never depends on what time of day the suite happens to run.
        LocalDateTime dueAt = LocalDateTime.now().plusDays(1)
            .withHour(14).withMinute(0).withSecond(0).withNano(0);
        Scheduler scheduler = hourlySchedule(dueAt, dueAt.toLocalDate());
        when(this.sourceJobRepository.findByJobIdAndJobStatus(JOB_ID, Status.Active))
            .thenReturn(Optional.of(activeAutoJob()));
        when(this.schedulerRepository.findSchedulerByJobId(JOB_ID)).thenReturn(Optional.of(scheduler));

        ResponseDto response = this.service.skipNextSourceJob(skipRequest());

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(this.producerBulkEngine).skipManualJobInQueue(scheduler);
        verify(this.schedulerRepository).save(scheduler);
    }

    /** Past the end date there really is nothing to skip, and that answer has to survive. */
    @Test
    void aScheduleWhoseNextSlotIsPastTheEndDateIsStillRefused() throws Exception {
        LocalDateTime dueAt = LocalDateTime.now().plusDays(1)
            .withHour(14).withMinute(0).withSecond(0).withNano(0);
        Scheduler scheduler = hourlySchedule(dueAt, dueAt.toLocalDate().minusDays(1));
        when(this.sourceJobRepository.findByJobIdAndJobStatus(JOB_ID, Status.Active))
            .thenReturn(Optional.of(activeAutoJob()));
        when(this.schedulerRepository.findSchedulerByJobId(JOB_ID)).thenReturn(Optional.of(scheduler));

        ResponseDto response = this.service.skipNextSourceJob(skipRequest());

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("No more flight skip.");
        verify(this.producerBulkEngine, never()).skipManualJobInQueue(any());
    }

    // ---- one job, one schedule ---------------------------------------------------------------

    /**
     * Two entries used to become two rows, and findSchedulerByJobId returns an Optional over a
     * column with no uniqueness -- so from then on opening, editing, re-activating or skipping
     * that job was HTTP 500 and only listSourceJob still worked.
     */
    @Test
    void createRefusesAPayloadCarryingTwoSchedules() throws Exception {
        SchedulerDto second = dailyAtTwo();
        second.setStartTime(LocalTime.of(6, 0));
        Set<SchedulerDto> two = new LinkedHashSet<>(Arrays.asList(dailyAtTwo(), second));
        SourceJobDto sourceJobDto = creationDto();
        sourceJobDto.setSchedulers(two);

        ResponseDto response = this.service.addSourceJob(sourceJobDto);

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("A job has one schedule");
        verify(this.schedulerRepository, never()).save(any());
        verify(this.sourceJobRepository, never()).saveAndFlush(any());
    }

    @Test
    void updateRefusesAPayloadCarryingTwoSchedules() throws Exception {
        SchedulerDto second = dailyAtTwo();
        second.setStartTime(LocalTime.of(6, 0));
        SourceJobDto sourceJobDto = creationDto();
        sourceJobDto.setJobId(JOB_ID);
        sourceJobDto.setSchedulers(new LinkedHashSet<>(Arrays.asList(dailyAtTwo(), second)));

        ResponseDto response = this.service.updateSourceJob(sourceJobDto);

        assertThat(response.getStatus()).isEqualTo("ERROR");
        assertThat(response.getMessage()).contains("A job has one schedule");
        verify(this.schedulerRepository, never()).save(any());
    }

    // ---- the list resolves its names in one query ---------------------------------------------

    /**
     * The assignee name was the one lookup on the list path that was not batched: one findById
     * per distinct assignee, alongside a single query for the jobs, the schedulers and the queue
     * counts. This asserts the batched call is what happens and the per-row one no longer does.
     */
    @Test
    void theListResolvesEveryAssigneeInOneQuery() throws Exception {
        List<SourceJob> jobs = Arrays.asList(
            jobAssignedTo(1L, 500L), jobAssignedTo(2L, 501L), jobAssignedTo(3L, 500L));
        when(this.sourceJobRepository.findAllActiveAndInactiveJobs(eq(Status.Active), eq(Status.Inactive), any()))
            .thenReturn(jobs);
        when(this.schedulerRepository.findByJobIdIn(anyList())).thenReturn(Collections.emptyList());
        when(this.jobQueueRepository.countGroupByJobIds(anyList())).thenReturn(Collections.emptyList());
        when(this.appUserRepository.findAllByIdAcrossTenants(anyCollection()))
            .thenReturn(Arrays.asList(userNamed(500L, "ana@tenant-a"), userNamed(501L, "bo@tenant-a")));

        ResponseDto response = this.service.listSourceJob();

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(this.appUserRepository, times(1)).findAllByIdAcrossTenants(anyCollection());
        verify(this.appUserRepository, never()).findById(any());
        List<?> data = (List<?>) response.getData();
        assertThat(data).hasSize(3);
    }

    private static SourceJob jobAssignedTo(long jobId, long assignedUserId) {
        SourceJob sourceJob = new SourceJob();
        sourceJob.setJobId(jobId);
        sourceJob.setTenantId(TENANT_A);
        sourceJob.setJobStatus(Status.Active);
        sourceJob.setAssignedUserId(assignedUserId);
        return sourceJob;
    }

    private static AppUser userNamed(long appUserId, String username) {
        AppUser appUser = new AppUser();
        appUser.setAppUserId(appUserId);
        appUser.setUsername(username);
        return appUser;
    }

}

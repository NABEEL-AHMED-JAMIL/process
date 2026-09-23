package process.model.service.impl;

import org.slf4j.Logger;
import process.util.UserNameResolver;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import process.engine.ProducerBulkEngine;
import process.model.dto.*;
import process.model.enums.Execution;
import process.model.enums.JobStatus;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;
import process.model.enums.Status;
import process.model.enums.UserRole;
import process.model.pojo.*;
import process.model.projection.JobAuditLogProjection;
import process.model.repository.*;
import process.model.service.SourceJobService;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.security.TenantOwnership;
import process.util.OpenSearchAuditLogClient;
import process.util.ProcessTimeUtil;
import process.util.ProcessUtil;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.*;
import java.sql.Timestamp;
import org.barco.notifications.contract.JobLifecycleChanged;
import process.notifications.Notices;
import process.notifications.NotificationPort;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class SourceJobServiceImpl implements SourceJobService {

    private Logger logger = LoggerFactory.getLogger(SourceJobServiceImpl.class);

    private final SourceJobRepository sourceJobRepository;
    private final SchedulerRepository schedulerRepository;
    private final SourceTaskRepository sourceTaskRepository;
    private final JobAuditLogRepository jobAuditLogRepository;
        private final JobQueueRepository jobQueueRepository;
    private final LookupDataRepository lookupDataRepository;
    private final AppUserRepository appUserRepository;
    private final ProducerBulkEngine producerBulkEngine;
    private final TenantFilterHelper tenantFilterHelper;
    private final OpenSearchAuditLogClient openSearchAuditLogClient;
    private final NotificationPort notifications;

    @PersistenceContext
    private EntityManager entityManager;

    private final UserNameResolver userNameResolver;


    public SourceJobServiceImpl(SourceJobRepository sourceJobRepository,
        SchedulerRepository schedulerRepository,
        SourceTaskRepository sourceTaskRepository,
        JobAuditLogRepository jobAuditLogRepository,
        JobQueueRepository jobQueueRepository,
        LookupDataRepository lookupDataRepository,
        AppUserRepository appUserRepository,
        ProducerBulkEngine producerBulkEngine,
        TenantFilterHelper tenantFilterHelper,
        OpenSearchAuditLogClient openSearchAuditLogClient,
        NotificationPort notifications,
        UserNameResolver userNameResolver) {
        this.userNameResolver = userNameResolver;
        this.sourceJobRepository = sourceJobRepository;
        this.schedulerRepository = schedulerRepository;
        this.sourceTaskRepository = sourceTaskRepository;
        this.jobAuditLogRepository = jobAuditLogRepository;
        this.jobQueueRepository = jobQueueRepository;
        this.lookupDataRepository = lookupDataRepository;
        this.appUserRepository = appUserRepository;
        this.producerBulkEngine = producerBulkEngine;
        this.tenantFilterHelper = tenantFilterHelper;
        this.openSearchAuditLogClient = openSearchAuditLogClient;
        this.notifications = notifications;
    }

    private void notifyTaskAssigned(SourceJob sourceJob, Long previousAssignedUserId) {
        Long newAssignedUserId = sourceJob.getAssignedUserId();
        if (newAssignedUserId == null || newAssignedUserId.equals(previousAssignedUserId)
            || newAssignedUserId.equals(TenantContext.getAppUserId())) {
            return;
        }
        this.notifications.notificationCreated(sourceJob.getTenantId(), Notices.notice(newAssignedUserId, NotificationType.TASK_ASSIGNED, NotificationSeverity.INFO, "Task assigned to you", sourceJob.getJobName() + " was assigned to you by " + TenantContext.getUsername() + ".", "/jobList"));
    }

    private boolean isOwnedByCaller(SourceJob sourceJob) {
        return sourceJob != null && TenantOwnership.isOwnedByCaller(sourceJob.getTenantId());
    }

    private boolean isOwnedByCaller(SourceTask sourceTask) {
        return sourceTask != null && TenantOwnership.isOwnedByCaller(sourceTask.getTenantId());
    }

    @Override
    @Transactional
    public ResponseDto addSourceJob(SourceJobDto sourceJobDto) throws Exception {
        if (ProcessUtil.isNull(sourceJobDto.getJobName())) {
            return new ResponseDto(ERROR, "SourceJob jobName missing.");
        } else if (ProcessUtil.isNull(sourceJobDto.getTaskDetail())) {
            return new ResponseDto(ERROR, "SourceJob taskDetail missing.");
        } else if (ProcessUtil.isNull(sourceJobDto.getTaskDetail().getTaskDetailId())) {
            return new ResponseDto(ERROR, "SourceJob taskDetailId missing.");
        } else if (ProcessUtil.isNull(sourceJobDto.getExecution())) {
            // Not-null in the database, so omitting it used to surface as a constraint violation
            // at commit and reach the caller as "Some internal error occurred contact with
            // support." Named here instead, like every other required field.
            return new ResponseDto(ERROR, "SourceJob execution missing -- Auto or Manual.");
        } else if (ProcessUtil.isNull(sourceJobDto.getPriority())) {
            // Exactly the same trap as execution above, and it was still open: priority is
            // not-null in the database and was written straight through from the DTO, so a
            // caller that omitted it got HTTP 500 "Some internal error occurred contact with
            // support." rather than being told which field was missing. The console always
            // sends it, which is why this only ever bit the API.
            return new ResponseDto(ERROR, "SourceJob priority missing -- 1 (highest) to 9.");
        } else if (sourceJobDto.getPriority() < 1 || sourceJobDto.getPriority() > 9) {
            // The console offers 1-9 and validates it; without the same check here the range is
            // decoration, and a job saved outside it sorts unpredictably against the rest.
            return new ResponseDto(ERROR, String.format(
                "SourceJob priority must be between 1 (highest) and 9; got %d.",
                sourceJobDto.getPriority()));
        } else if (retryPolicyError(sourceJobDto) != null) {
            return new ResponseDto(ERROR, retryPolicyError(sourceJobDto));
        } else if (Status.Delete.equals(sourceJobDto.getJobStatus())) {
            // Delete is the soft-delete tombstone, not something a job is born in. Honouring it
            // below would write a job that is invisible to every list and every count the moment
            // it exists, and the caller would be told it was saved.
            return new ResponseDto(ERROR, "SourceJob cannot be created as Delete -- create it Active or Inactive.");
        }
        String schedulerCardinalityError = this.refuseMoreThanOneScheduler(sourceJobDto);
        if (schedulerCardinalityError != null) {
            return new ResponseDto(ERROR, schedulerCardinalityError);
        }

        Optional<SourceTask> taskDetail = this.sourceTaskRepository.findById(
             sourceJobDto.getTaskDetail().getTaskDetailId());
        if (!taskDetail.isPresent() || !this.isOwnedByCaller(taskDetail.get())) {
            return new ResponseDto(ERROR, String.format("SourceTask not found with %d.",
                sourceJobDto.getTaskDetail().getTaskDetailId()));
        }
        Long tenantId = taskDetail.get().getTenantId();
        if (ProcessUtil.isNull(tenantId)) {
            return new ResponseDto(ERROR, "Selected sourceTask has no owning tenant -- fix its tenant before creating jobs against it.");
        }
        Long assignedUserId = !ProcessUtil.isNull(sourceJobDto.getAssignedUserId())
            ? sourceJobDto.getAssignedUserId() : TenantContext.getAppUserId();
        String assigneeError = this.validateAssignee(assignedUserId, tenantId);
        if (assigneeError != null) {
            return new ResponseDto(ERROR, assigneeError);
        }
        SourceJob sourceJob = new SourceJob();
        sourceJob.setJobName(sourceJobDto.getJobName());
        sourceJob.setTenantId(tenantId);
        sourceJob.setTaskDetail(taskDetail.get());
        /*
         * The caller's choice, not a constant. This was hard-coded to Active, so every path that
         * asks for an inactive job got a live one instead: the console's Clone button posts
         * Inactive and then says "it starts inactive", and the create form's Status field offered
         * Inactive and was ignored. Cloning an Auto job therefore doubled its runs from the next
         * slot onwards -- exactly what the clone was written to avoid -- and the toast told the
         * operator there was nothing to undo. Active stays the default for a caller that says
         * nothing, which is what the old behaviour was for.
         */
        sourceJob.setJobStatus(!ProcessUtil.isNull(sourceJobDto.getJobStatus())
            ? sourceJobDto.getJobStatus() : Status.Active);
        sourceJob.setExecution(sourceJobDto.getExecution());
        sourceJob.setPriority(sourceJobDto.getPriority());
        // Left at the entity's own default when the caller says nothing, rather than written as
        // null -- the columns are not-null, and null here would be the 500 that omitting priority
        // used to produce.
        if (!ProcessUtil.isNull(sourceJobDto.getMaxAttempts())) {
            sourceJob.setMaxAttempts(sourceJobDto.getMaxAttempts());
        }
        if (!ProcessUtil.isNull(sourceJobDto.getRetryBackoffSeconds())) {
            sourceJob.setRetryBackoffSeconds(sourceJobDto.getRetryBackoffSeconds());
        }
        sourceJob.setCompleteJob(sourceJobDto.isCompleteJob());
        sourceJob.setFailJob(sourceJobDto.isFailJob());
        sourceJob.setSkipJob(sourceJobDto.isSkipJob());

        sourceJob.setAssignedUserId(assignedUserId);
        this.sourceJobRepository.saveAndFlush(sourceJob);
        this.notifyTaskAssigned(sourceJob, null);
        if (!ProcessUtil.isNull(sourceJobDto.getSchedulers()) && !sourceJobDto.getSchedulers().isEmpty()) {
            sourceJobDto.getSchedulers()
                .forEach(schedulerDto -> {
                    Scheduler scheduler = new Scheduler();
                    this.applySchedulerFields(scheduler, schedulerDto, sourceJob.getJobId());
                    this.schedulerRepository.save(scheduler);
                });
        }
        this.notifications.jobLifecycleChanged(sourceJob.getTenantId(), new JobLifecycleChanged().setJobId(sourceJob.getJobId()).setChange(JobLifecycleChanged.Change.updated));
        return new ResponseDto(SUCCESS, String.format("Job save with jobId %d.", sourceJob.getJobId()));
    }

    /**
     * Copies one posted timetable onto a Scheduler row and seeds its next run.
     *
     * Create and update wrote the same nine assignments in two places, which is how the update
     * side came to be missing the branch that creates a row at all; sharing them means the two
     * cannot drift again.
     */
    private void applySchedulerFields(Scheduler scheduler, SchedulerDto schedulerDto, Long jobId) {
        scheduler.setStartDate(schedulerDto.getStartDate());
        /*
         * Written whatever it holds, including nothing.
         *
         * This was guarded, so an absent end date left the stored one alone -- and since the DTO
         * carries one nullable LocalDate, "the caller left it out" and "the caller cleared it" are
         * the same value. Clearing End date in the editor and saving therefore reported "Job save
         * with jobId N." while the old end date stayed in the row, and the job went on stopping on
         * a date the operator had just deleted and could see was gone. Nothing warned, and the
         * only way to notice was that the job stopped running.
         *
         * Every other field on this method's timetable -- start date, start time, frequency, the
         * days of the week and the day of the month -- is already copied unconditionally, because
         * a posted schedule replaces the stored one wholesale rather than being merged into it.
         * The end date was the single exception, and it is the only one of them that is optional,
         * which is exactly why the hole was there and nowhere else.
         */
        scheduler.setEndDate(schedulerDto.getEndDate());
        scheduler.setStartTime(schedulerDto.getStartTime());
        scheduler.setFrequency(schedulerDto.getFrequency());
        if (!StringUtils.isEmpty(schedulerDto.getIntervalValue())) {
            scheduler.setIntervalValue(schedulerDto.getIntervalValue());
        }
        scheduler.setDaysOfWeek(schedulerDto.getDaysOfWeek());
        scheduler.setDayOfMonth(schedulerDto.getDayOfMonth());
        ProcessTimeUtil.applyInitialSchedule(scheduler);
        scheduler.setJobId(jobId);
    }

    /**
     * Whether the payload asks for more than one timetable on one job.
     *
     * Nothing downstream can cope with a second row. Every reader -- this service in five
     * places, the dashboard, the bulk export and the job assistant -- goes through
     * SchedulerRepository.findSchedulerByJobId, a derived query returning Optional over a column
     * with no uniqueness, so a job with two rows makes Spring Data throw
     * IncorrectResultSizeDataAccessException. The job then cannot be opened, edited, re-activated
     * or skipped: every one of those is HTTP 500 "Some internal error occurred contact with
     * support." The field is a Set, but SchedulerDto declares no equals, so two identical JSON
     * entries stay two elements and really do produce two rows. Refusing the payload is the only
     * point at which this is still recoverable by the caller.
     */
    /** Widest retry policy a job may be given; the same ceiling the database enforces. */
    private static final int MAX_ATTEMPTS_CEILING = 10;

    /** Longest base backoff a job may be given, in seconds; the same ceiling the database enforces. */
    private static final int MAX_BACKOFF_SECONDS_CEILING = 60 * 60;

    /**
     * The retry policy's complaint as a sentence, or null when there is nothing wrong with it.
     *
     * Checked here as well as by the CHECK constraints so the caller is told which field is wrong
     * and what the range is. Reaching the constraint instead produces a DataIntegrityViolation at
     * commit, which arrives as "Some internal error occurred contact with support." -- the exact
     * trap priority and execution were each fixed for, and it would be a third instance of it.
     *
     * Both fields are optional: null means "leave whatever the job has", which for a new job is
     * the no-retry default. Only a value that is present and out of range is an error.
     */
    private static String retryPolicyError(SourceJobDto sourceJobDto) {
        Integer maxAttempts = sourceJobDto.getMaxAttempts();
        if (!ProcessUtil.isNull(maxAttempts) && (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS_CEILING)) {
            return String.format(
                "SourceJob maxAttempts must be between 1 (no retry) and %d; got %d.",
                MAX_ATTEMPTS_CEILING, maxAttempts);
        }
        Integer backoff = sourceJobDto.getRetryBackoffSeconds();
        if (!ProcessUtil.isNull(backoff) && (backoff < 1 || backoff > MAX_BACKOFF_SECONDS_CEILING)) {
            return String.format(
                "SourceJob retryBackoffSeconds must be between 1 and %d; got %d.",
                MAX_BACKOFF_SECONDS_CEILING, backoff);
        }
        return null;
    }

    private String refuseMoreThanOneScheduler(SourceJobDto sourceJobDto) {
        if (!ProcessUtil.isNull(sourceJobDto.getSchedulers()) && sourceJobDto.getSchedulers().size() > 1) {
            return String.format("A job has one schedule; %d were sent. Post a single schedulers entry.",
                sourceJobDto.getSchedulers().size());
        }
        return null;
    }

    @Override
    @Transactional
    public ResponseDto updateSourceJob(SourceJobDto sourceJobDto) throws Exception {
        if (ProcessUtil.isNull(sourceJobDto.getJobId())) {
            return new ResponseDto(ERROR, "SourceJob job-id missing.");
        } else if (ProcessUtil.isNull(sourceJobDto.getJobName())) {
            return new ResponseDto(ERROR, "SourceJob jobName missing.");
        } else if (ProcessUtil.isNull(sourceJobDto.getTaskDetail())) {
            return new ResponseDto(ERROR, "SourceJob taskDetail missing.");
        } else if (ProcessUtil.isNull(sourceJobDto.getTaskDetail().getTaskDetailId())) {
            return new ResponseDto(ERROR, "SourceJob taskDetailId missing.");
        }
        String schedulerCardinalityError = this.refuseMoreThanOneScheduler(sourceJobDto);
        if (schedulerCardinalityError != null) {
            return new ResponseDto(ERROR, schedulerCardinalityError);
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<SourceJob> sourceJob = this.sourceJobRepository.findById(sourceJobDto.getJobId());

        if (sourceJob.isPresent() && (!this.isOwnedByCaller(sourceJob.get())
            || Status.Delete.equals(sourceJob.get().getJobStatus()))) {
            return new ResponseDto(ERROR, String.format("SourceJob not found with %d.", sourceJobDto.getJobId()));
        }
        if (sourceJob.isPresent()) {
            sourceJob.get().setJobName(sourceJobDto.getJobName());

            Optional<SourceTask> sourceTask = this.sourceTaskRepository.findByTaskDetailIdAndTaskStatus(
                 sourceJobDto.getTaskDetail().getTaskDetailId(), Status.Active);
            if (sourceTask.isPresent() && !this.isOwnedByCaller(sourceTask.get())) {
                return new ResponseDto(ERROR, "Selected sourceTask not active.");
            } else if (sourceTask.isPresent()) {
                sourceJob.get().setTaskDetail(sourceTask.get());
            } else {
                return new ResponseDto(ERROR, "Selected sourceTask not active.");
            }
            if (!ProcessUtil.isNull(sourceJobDto.getJobStatus())) {
                sourceJob.get().setJobStatus(sourceJobDto.getJobStatus());
            }
            if (!ProcessUtil.isNull(sourceJobDto.getExecution())) {
                sourceJob.get().setExecution(sourceJobDto.getExecution());
            }
            if (!ProcessUtil.isNull(sourceJobDto.getPriority())) {
                // Same range the console offers and addSourceJob enforces. An update is the
                // other way a job's priority is set, so leaving it unchecked here would let
                // an out-of-range value in through the back door.
                if (sourceJobDto.getPriority() < 1 || sourceJobDto.getPriority() > 9) {
                    return new ResponseDto(ERROR, String.format(
                        "SourceJob priority must be between 1 (highest) and 9; got %d.",
                        sourceJobDto.getPriority()));
                }
                sourceJob.get().setPriority(sourceJobDto.getPriority());
            }
            String retryError = retryPolicyError(sourceJobDto);
            if (retryError != null) {
                return new ResponseDto(ERROR, retryError);
            }
            if (!ProcessUtil.isNull(sourceJobDto.getMaxAttempts())) {
                sourceJob.get().setMaxAttempts(sourceJobDto.getMaxAttempts());
            }
            if (!ProcessUtil.isNull(sourceJobDto.getRetryBackoffSeconds())) {
                sourceJob.get().setRetryBackoffSeconds(sourceJobDto.getRetryBackoffSeconds());
            }
            sourceJob.get().setCompleteJob(sourceJobDto.isCompleteJob());
            sourceJob.get().setFailJob(sourceJobDto.isFailJob());
            sourceJob.get().setSkipJob(sourceJobDto.isSkipJob());
            Long previousAssignedUserId = sourceJob.get().getAssignedUserId();
            if (!ProcessUtil.isNull(sourceJobDto.getAssignedUserId())) {
                String assigneeError = this.validateAssignee(sourceJobDto.getAssignedUserId(), sourceJob.get().getTenantId());
                if (assigneeError != null) {
                    return new ResponseDto(ERROR, assigneeError);
                }
                sourceJob.get().setAssignedUserId(sourceJobDto.getAssignedUserId());
            }
            this.sourceJobRepository.saveAndFlush(sourceJob.get());
            this.notifyTaskAssigned(sourceJob.get(), previousAssignedUserId);
            if (!ProcessUtil.isNull(sourceJobDto.getSchedulers()) && !sourceJobDto.getSchedulers().isEmpty()) {
                sourceJobDto.getSchedulers()
                    .forEach(schedulerDto -> {
                        Optional<Scheduler> scheduler = this.schedulerRepository.findSchedulerByJobId(sourceJobDto.getJobId());
                        /*
                         * The row is created when there isn't one. Only the present case was
                         * handled, with no else, so a posted timetable for a job that had never
                         * had one went nowhere and the caller was told "Job save with jobId N."
                         * A Manual job carries no scheduler row -- the console omits the whole
                         * schedulers block for one -- so switching it to Auto and filling in the
                         * timetable saved the execution change and silently discarded the
                         * schedule. The job then sat Active and Auto with nothing for
                         * findDueSchedulers to find: it never ran, the list showed no schedule
                         * against it, and Skip next answered that it had none.
                         */
                        Scheduler target = scheduler.isPresent() ? scheduler.get() : new Scheduler();
                        this.applySchedulerFields(target, schedulerDto, sourceJob.get().getJobId());
                        this.schedulerRepository.save(target);
                    });
            }
            this.notifications.jobLifecycleChanged(sourceJob.get().getTenantId(), new JobLifecycleChanged().setJobId(sourceJob.get().getJobId()).setChange(JobLifecycleChanged.Change.updated));
            return new ResponseDto(SUCCESS, String.format("Job save with jobId %d.", sourceJobDto.getJobId()));
        }
        return new ResponseDto(ERROR, String.format("SourceJob not found with %d.", sourceJobDto.getJobId()));
    }

    @Override
    @Transactional
    public ResponseDto deleteSourceJob(SourceJobDto sourceJobDto) throws Exception {
        if (ProcessUtil.isNull(sourceJobDto.getJobId())) {
            return new ResponseDto(ERROR, "SourceJob jobId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<SourceJob> sourceJob = this.sourceJobRepository.findById(sourceJobDto.getJobId());
        if (sourceJob.isPresent() && !this.isOwnedByCaller(sourceJob.get())) {
            return new ResponseDto(ERROR, String.format("SourceJob not found with %d.", sourceJobDto.getJobId()));
        }
        if (sourceJob.isPresent()) {

            sourceJob.get().setJobStatus(Status.Delete);
            this.sourceJobRepository.save(sourceJob.get());

            try {
                int updated = this.jobQueueRepository.updateStatusByJobId(sourceJobDto.getJobId(), Status.Delete.name());
                if (updated > 0) {

                    this.jobAuditLogRepository.updateStatusByJobId(sourceJobDto.getJobId(), Status.Delete.name());
                }
            } catch (Exception ex) {
                logger.error("An error occurred while updating related job queue/audit logs during deleteSourceJob :- {}.", ex);
            }

            this.notifications.jobLifecycleChanged(sourceJob.get().getTenantId(), new JobLifecycleChanged().setJobId(sourceJob.get().getJobId()).setChange(JobLifecycleChanged.Change.deleted));
            return new ResponseDto(SUCCESS, String.format("SourceJob successfully updated with ID %d.", sourceJobDto.getJobId()));
        }
        return new ResponseDto(ERROR, String.format("SourceJob not found with %d.", sourceJobDto.getJobId()));
    }

    @Override
    @Transactional
    public ResponseDto toggleSourceJobStatus(SourceJobDto sourceJobDto) throws Exception {
        if (ProcessUtil.isNull(sourceJobDto.getJobId())) {
            return new ResponseDto(ERROR, "SourceJob jobId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<SourceJob> sourceJob = this.sourceJobRepository.findById(sourceJobDto.getJobId());
        if (sourceJob.isPresent() && !this.isOwnedByCaller(sourceJob.get())) {
            return new ResponseDto(ERROR, String.format("SourceJob not found with %d.", sourceJobDto.getJobId()));
        }
        if (!sourceJob.isPresent()) {
            return new ResponseDto(ERROR, String.format("SourceJob not found with %d.", sourceJobDto.getJobId()));
        }
        if (sourceJob.get().getJobStatus() == Status.Delete) {
            return new ResponseDto(ERROR, "Can't change status of a deleted job.");
        }
        // Honour the state that was asked for when one is given, and only flip when it is not.
        // Ignoring it made the call non-idempotent: a retry after a timeout, or a second click,
        // put the job back exactly where it started with no way for the caller to tell.
        Status requested = sourceJobDto.getJobStatus();
        Status newStatus;
        if (Status.Active.equals(requested) || Status.Inactive.equals(requested)) {
            newStatus = requested;
        } else {
            newStatus = sourceJob.get().getJobStatus() == Status.Active ? Status.Inactive : Status.Active;
        }
        sourceJob.get().setJobStatus(newStatus);
        this.sourceJobRepository.save(sourceJob.get());

        if (Status.Active.equals(newStatus)) {
            // next_run_at only moves when a job is dispatched, and a paused job never is -- so it
            // sits at whatever slot was next when the job was paused. Resuming without this, the
            // job is overdue the instant it comes back: it fires immediately, and every slot that
            // went by while it was deliberately paused is written down as Missed. A pause is a
            // decision, not an outage, so the schedule is moved on to its next real slot instead.
            this.schedulerRepository.findSchedulerByJobId(sourceJob.get().getJobId())
                .ifPresent(scheduler -> {
                    ProcessTimeUtil.applyInitialSchedule(scheduler);
                    this.schedulerRepository.save(scheduler);
                });
        }
        this.notifications.jobLifecycleChanged(sourceJob.get().getTenantId(), new JobLifecycleChanged().setJobId(sourceJob.get().getJobId()).setChange(JobLifecycleChanged.Change.toggled));
        return new ResponseDto(SUCCESS, String.format("Job %s.", newStatus == Status.Active ? "activated" : "deactivated"), newStatus.name());
    }

    @Override
    @Transactional
    public ResponseDto runSourceJob(SourceJobDto sourceJobDto) throws Exception {
        if (ProcessUtil.isNull(sourceJobDto.getJobId())) {
            return new ResponseDto(ERROR, "SourceJob jobId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<SourceJob> sourceJob = this.sourceJobRepository.findByJobIdAndJobStatus(sourceJobDto.getJobId(), Status.Active);
        if (!sourceJob.isPresent() || !this.isOwnedByCaller(sourceJob.get())) {
            return new ResponseDto(ERROR, "SourceJob not found with jobId.");
        } else if (!ProcessUtil.isNull(sourceJob.get().getJobRunningStatus())
            && sourceJob.get().getJobRunningStatus().isInFlight()) {
            // Start included: a run just dispatched sits there until its worker reports, and a
            // second one would write into the same output folder.
            return new ResponseDto(ERROR,
                "A job can't be run while its last run is still in flight ('Queue', 'Start', 'Running').");
        }
        this.producerBulkEngine.addManualJobInQueue(sourceJob.get());
        sourceJob = this.sourceJobRepository.findByJobIdAndJobStatus(sourceJobDto.getJobId(), Status.Active);
        return new ResponseDto(SUCCESS, "SourceJob job successfully added into queue.", sourceJob);
    }

    @Override
    @Transactional
    public ResponseDto skipNextSourceJob(SourceJobDto sourceJobDto) throws Exception {
        if (ProcessUtil.isNull(sourceJobDto.getJobId())) {
            return new ResponseDto(ERROR, "SourceJob jobId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<SourceJob> sourceJob = this.sourceJobRepository.findByJobIdAndJobStatus(sourceJobDto.getJobId(), Status.Active);
        if (!sourceJob.isPresent() || !this.isOwnedByCaller(sourceJob.get())) {
            return new ResponseDto(ERROR, "SourceJob not found with jobId.");
        } else if (!ProcessUtil.isNull(sourceJob.get().getJobRunningStatus())
            && sourceJob.get().getJobRunningStatus().isInFlight()) {
            return new ResponseDto(ERROR,
                "A job's next run can't be skipped while its last run is still in flight ('Queue', 'Start', 'Running').");
        } else if (!sourceJob.get().getExecution().equals(Execution.Auto)) {
            return new ResponseDto(ERROR, "SourceJob skip only work with 'auto' source job.");
        }
        Optional<Scheduler> schedulerOpt = this.schedulerRepository.findSchedulerByJobId(sourceJob.get().getJobId());
        if (!schedulerOpt.isPresent()) {

            return new ResponseDto(ERROR, "SourceJob has no scheduler to skip.");
        }
        Scheduler scheduler = schedulerOpt.get();
        LocalDateTime nextJobRun = ProcessTimeUtil.computeNextRun(scheduler);
        /*
         * The end date bounds a day, not an instant, and this is the one place that read it
         * otherwise.
         *
         * The bound used to be endDate at the schedule's START TIME, so any slot later in the
         * final day counted as past the end. On a daily or slower schedule that is invisible --
         * there is only ever one slot a day and it falls at the start time -- but an hourly or
         * minute-level schedule has slots all day, and on its last day every one of them after
         * the first was judged to be beyond the window. Skip next run then answered "No more
         * flight skip." for a job with a dozen runs still to come that afternoon, and there was
         * no other way to skip one of them.
         *
         * applyNextRun and isLastFlight both compare the date alone -- a slot is in the window
         * while its DATE is not after the end date -- and they are what the engine actually
         * dispatches by, so a schedule refused here was one the engine would have gone on running.
         * Asking the same question the same way is what keeps the answer honest.
         */
        boolean stillHasMoreFlights = !ProcessUtil.isNull(nextJobRun) && (ProcessUtil.isNull(scheduler.getEndDate()) ||
            !nextJobRun.toLocalDate().isAfter(scheduler.getEndDate()));
        if (!stillHasMoreFlights) {
            return new ResponseDto(ERROR, "No more flight skip.");
        }
        this.producerBulkEngine.skipManualJobInQueue(scheduler);
        ProcessTimeUtil.applyNextRun(scheduler);
        this.schedulerRepository.save(scheduler);
        return new ResponseDto(SUCCESS, "SourceJob skip successfully.", scheduler);
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseDto findSourceJobAuditLog(Long jobQueueId, Long jobId) throws Exception {
        if (jobQueueId == null) {
            return new ResponseDto(ERROR, "JobQueueId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);

        Optional<SourceJob> sourceJobOpt = this.sourceJobRepository.findById(jobId);
        if (!sourceJobOpt.isPresent() || !this.isOwnedByCaller(sourceJobOpt.get())
            || Status.Delete.equals(sourceJobOpt.get().getJobStatus())) {
            return new ResponseDto(ERROR, String.format("SourceJob not found with %d.", jobId));
        }
        Optional<JobQueue> jobQueueOpt = this.jobQueueRepository.findById(jobQueueId)
            .filter(queue -> jobId.equals(queue.getJobId()));
        if (!jobQueueOpt.isPresent()) {
            return new ResponseDto(ERROR, String.format("JobQueue not found with %d for job %d.", jobQueueId, jobId));
        }
        Map<String, Object> payload = new HashMap<>();

        payload.put("auditLogs", mergeAuditLogs(
            this.openSearchAuditLogClient.searchByJobQueueId(jobQueueId),
            this.jobAuditLogRepository.findAllByJobQueueIdV1(jobQueueId)));
        SourceJobDto sourceJobDto = mapSourceJobToDto(sourceJobOpt.get());
        schedulerRepository.findSchedulerByJobId(jobId).ifPresent(s -> sourceJobDto.setScheduler(getSchedulerDto(s)));
        payload.put("sourceJob", sourceJobDto);
        payload.put("sourceJobQueue", getSourceJobQueueDto(jobQueueOpt.get()));
        return new ResponseDto(SUCCESS, String.format("SourceJob audit log found with %d.", jobQueueId), payload);
    }

    private List<JobAuditLogProjection> mergeAuditLogs(
        List<? extends JobAuditLogProjection> openSearchLogs,
        List<? extends JobAuditLogProjection> dbLogs) {
        LinkedHashMap<String, JobAuditLogProjection> byKey = new LinkedHashMap<>();
        for (JobAuditLogProjection log : dbLogs) {
            byKey.put(auditLogDedupeKey(log), log);
        }
        for (JobAuditLogProjection log : openSearchLogs) {
            byKey.put(auditLogDedupeKey(log), log);
        }
        List<JobAuditLogProjection> merged = new ArrayList<>(byKey.values());
        merged.sort(Comparator.comparing(JobAuditLogProjection::getDateCreated));
        return merged;
    }

    private String auditLogDedupeKey(JobAuditLogProjection log) {
        return !ProcessUtil.isNull(log.getExternalId())
            ? "ext:" + log.getExternalId()
            : "content:" + log.getJobQueueId() + "|" + log.getDateCreated() + "|" + log.getLogsDetail();
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchSourceJobDetailWithSourceJobId(Long jobId) {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        return sourceJobRepository.findById(jobId)
            .filter(this::isOwnedByCaller)
            .filter(sourceJob -> !Status.Delete.equals(sourceJob.getJobStatus()))
            .map(sourceJob -> {
                SourceJobDto dto = mapSourceJobToDto(sourceJob);
                schedulerRepository.findSchedulerByJobId(jobId).ifPresent(s -> dto.setScheduler(getSchedulerDto(s)));
                return new ResponseDto(SUCCESS, String.format("SourceJob found with %d.", jobId), dto);
            }).orElseGet(() -> new ResponseDto(ERROR, String.format("SourceJob not found with %d.", jobId)));
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchSourceJobQueueListWithJobId(Long jobId) throws Exception {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);

        Optional<SourceJob> sourceJobOpt = this.sourceJobRepository.findById(jobId);
        if (!sourceJobOpt.isPresent() || !this.isOwnedByCaller(sourceJobOpt.get())) {
            return new ResponseDto(ERROR, String.format("SourceJob not found with %d.", jobId));
        }
        // A deleted job has no history to show. It is gone from every list and every count, so
        // serving its runs here would be the one place it survived -- reachable by anyone who
        // still had the link.
        if (Status.Delete.equals(sourceJobOpt.get().getJobStatus())) {
            return new ResponseDto(ERROR, String.format("SourceJob not found with %d.", jobId));
        }
        List<SourceJobQueueDto> jobQueues = jobQueueRepository.findAllByJobId(jobId)
            .stream()
            .sorted(Comparator.comparing(JobQueue::getDateCreated, Comparator.nullsLast(Comparator.reverseOrder())))
            .map(this::getSourceJobQueueDto)
            .collect(Collectors.toList());
        Map<String, Object> payload = new HashMap<>();
        payload.put("jobQueues", jobQueues);
        return new ResponseDto(SUCCESS, String.format("SourceJobQueue found with %d.", jobId), payload);
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseDto listSourceJob() throws Exception {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        List<SourceJob> jobs = sourceJobRepository.findAllActiveAndInactiveJobs(
            Status.Active, Status.Inactive, Sort.by(Sort.Direction.ASC, "jobId"));
        List<Long> jobIds = jobs.stream().map(SourceJob::getJobId).collect(Collectors.toList());
        Map<Long, Scheduler> schedulerByJobId = jobIds.isEmpty() ? Collections.emptyMap()
            : schedulerRepository.findByJobIdIn(jobIds).stream()
                .collect(Collectors.toMap(Scheduler::getJobId, s -> s, (a, b) -> a));
        Map<Long, Long> queueCountByJobId = jobIds.isEmpty() ? Collections.emptyMap()
            : jobQueueRepository.countGroupByJobIds(jobIds).stream()
                .collect(Collectors.toMap(row -> ((Number) row[0]).longValue(), row -> ((Number) row[1]).longValue()));

        // The entities are already in hand here, so the names cost one lookup and no re-fetch.
        this.userNameResolver.attachNames(jobs);
        /*
         * The last three per-row lookups on this path, batched like the schedulers and the queue
         * counts above them. mapSourceJobToDto resolved the assignee's name with its own findById
         * per job, and mapSourceTaskToDto did the same for the task's home page and pipeline, so
         * the most-loaded screen in the console paid up to three extra round trips for every
         * distinct value in the list -- on top of the one query the list itself was carefully
         * built to be. The persistence context de-duplicated repeats inside the transaction, so
         * the cost tracked the number of distinct assignees and lookups rather than the number of
         * rows, which is why it grew with the team rather than with the job count.
         */
        Map<Long, String> usernameByUserId = this.resolveAssigneeNames(jobs);
        Map<Long, String> lookupTypeByLookupId = this.resolveTaskLookupTypes(jobs);
        List<SourceJobDto> sourceJobDtoList = jobs.stream()
            .map(job -> {
                SourceJobDto dto = mapSourceJobToDto(job, usernameByUserId, lookupTypeByLookupId);
                Optional.ofNullable(schedulerByJobId.get(job.getJobId())).ifPresent(s -> dto.setScheduler(getSchedulerDto(s)));
                dto.setTabActive(queueCountByJobId.getOrDefault(job.getJobId(), 0L) > 0);
                // The task's XML payload rides along at roughly 600 bytes a row and no list
                // view shows it -- only the single-job detail call needs it. Dropping it here
                // takes this response from 54KB to about 30KB for 41 jobs, and the saving
                // grows with the list.
                if (dto.getTaskDetail() != null) {
                    dto.getTaskDetail().setTaskPayload(null);
                }
                return dto;
            }).collect(Collectors.toList());
        return new ResponseDto(SUCCESS, "Fetch source jobs.", sourceJobDtoList);
    }

    /** Every distinct assignee in the list, resolved to a username in one query. */
    private Map<Long, String> resolveAssigneeNames(List<SourceJob> jobs) {
        List<Long> userIds = jobs.stream()
            .map(SourceJob::getAssignedUserId)
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());
        if (userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> usernameByUserId = new HashMap<>();
        this.appUserRepository.findAllById(userIds)
            .forEach(appUser -> usernameByUserId.put(appUser.getAppUserId(), appUser.getUsername()));
        return usernameByUserId;
    }

    /**
     * Every distinct home-page and pipeline lookup id behind the list's tasks, in one query.
     *
     * Both are stored as text and only some of them are lookup ids at all -- pipeline_id has held
     * the raw worker id since the PIPELINE_IDS family was dropped -- so the ones that do not parse
     * are simply not asked about, exactly as the per-row version skipped them.
     */
    private Map<Long, String> resolveTaskLookupTypes(List<SourceJob> jobs) {
        Set<Long> lookupIds = new LinkedHashSet<>();
        for (SourceJob job : jobs) {
            if (ProcessUtil.isNull(job.getTaskDetail())) {
                continue;
            }
            Long homePageLookupId = ProcessUtil.parseLongOrNull(job.getTaskDetail().getHomePageId());
            if (homePageLookupId != null) {
                lookupIds.add(homePageLookupId);
            }
            Long pipelineLookupId = ProcessUtil.parseLongOrNull(job.getTaskDetail().getPipelineId());
            if (pipelineLookupId != null) {
                lookupIds.add(pipelineLookupId);
            }
        }
        if (lookupIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, String> lookupTypeByLookupId = new HashMap<>();
        this.lookupDataRepository.findAllById(lookupIds)
            .forEach(lookupData -> lookupTypeByLookupId.put(lookupData.getLookupId(), lookupData.getLookupType()));
        return lookupTypeByLookupId;
    }

    private String validateAssignee(Long assignedUserId, Long tenantId) {
        Optional<AppUser> assignee = this.appUserRepository.findById(assignedUserId);
        if (!assignee.isPresent() || assignee.get().getStatus() == Status.Delete) {
            return String.format("Assigned user not found with %d.", assignedUserId);
        }

        if (assignee.get().getUserRole() == UserRole.PLATFORM_ADMIN) {
            return null;
        }
        if (!ProcessUtil.isNull(tenantId) && !tenantId.equals(assignee.get().getTenantId())) {
            return "Assigned user must belong to the same tenant as this job.";
        }
        return null;
    }

    /** One job on its own, where a single extra lookup each is cheaper than prefetching. */
    private SourceJobDto mapSourceJobToDto(SourceJob sourceJob) {
        return this.mapSourceJobToDto(sourceJob, null, null);
    }

    /**
     * A null map means "resolve it yourself", which is what the single-job callers want; the list
     * passes maps it has already filled so the same mapping costs no queries at all.
     */
    private SourceJobDto mapSourceJobToDto(SourceJob sourceJob,
        Map<Long, String> usernameByUserId, Map<Long, String> lookupTypeByLookupId) {
        SourceJobDto dto = new SourceJobDto();
        dto.setJobId(sourceJob.getJobId());
        dto.setJobStatus(sourceJob.getJobStatus());
        dto.setJobRunningStatus(sourceJob.getJobRunningStatus());
        dto.setLastJobRun(sourceJob.getLastJobRun());
        dto.setJobName(sourceJob.getJobName());
        dto.setCreatedByName(sourceJob.getCreatedByName());
        dto.setUpdatedByName(sourceJob.getUpdatedByName());
        dto.setCreatedBy(sourceJob.getCreatedBy());
        dto.setDateCreated(sourceJob.getDateCreated());
        dto.setPriority(sourceJob.getPriority());
        dto.setMaxAttempts(sourceJob.getMaxAttempts());
        dto.setRetryBackoffSeconds(sourceJob.getRetryBackoffSeconds());
        dto.setExecution(sourceJob.getExecution());
        dto.setCompleteJob(sourceJob.isCompleteJob());
        dto.setFailJob(sourceJob.isFailJob());
        dto.setSkipJob(sourceJob.isSkipJob());
        if (!ProcessUtil.isNull(sourceJob.getTaskDetail())) {
            dto.setTaskDetail(mapSourceTaskToDto(sourceJob.getTaskDetail(), lookupTypeByLookupId));
        }
        if (!ProcessUtil.isNull(sourceJob.getAssignedUserId())) {
            dto.setAssignedUserId(sourceJob.getAssignedUserId());
            if (usernameByUserId != null) {
                dto.setAssignedUsername(usernameByUserId.get(sourceJob.getAssignedUserId()));
            } else {
                this.appUserRepository.findById(sourceJob.getAssignedUserId())
                    .ifPresent(appUser -> dto.setAssignedUsername(appUser.getUsername()));
            }
        }
        return dto;
    }

    private SourceTaskDto mapSourceTaskToDto(SourceTask sourceTask) {
        return this.mapSourceTaskToDto(sourceTask, null);
    }

    private SourceTaskDto mapSourceTaskToDto(SourceTask sourceTask, Map<Long, String> lookupTypeByLookupId) {
        SourceTaskDto dto = new SourceTaskDto();
        dto.setTaskDetailId(sourceTask.getTaskDetailId());
        dto.setTaskName(sourceTask.getTaskName());
        dto.setTaskStatus(sourceTask.getTaskStatus());
        dto.setTaskPayload(sourceTask.getTaskPayload());
        dto.setBucket(sourceTask.getBucket());
        dto.setInputFolder(sourceTask.getInputFolder());
        dto.setOutputFolder(sourceTask.getOutputFolder());
        Long homePageLookupId = ProcessUtil.parseLongOrNull(sourceTask.getHomePageId());
        if (homePageLookupId != null) {
            dto.setHomePageId(lookupTypeByLookupId != null
                ? lookupTypeByLookupId.get(homePageLookupId)
                : lookupDataRepository.findById(homePageLookupId).map(ld -> ld.getLookupType()).orElse(null));
        }
        /*
         * pipeline_id is the raw id the worker routes on ("F768930") since the PIPELINE_IDS
         * lookup family was dropped (changeset V28) and Pipeline Forms became the catalogue.
         * parseLongOrNull returns null for it, so the guarded block below was skipped and the
         * field was left unset entirely -- which is why the console showed "Pipeline --" on
         * every task. A numeric value is still resolved, for rows written before that change,
         * and falls back to the raw value when it resolves to nothing.
         */
        String pipelineId = sourceTask.getPipelineId();
        Long pipelineLookupId = ProcessUtil.parseLongOrNull(pipelineId);
        if (pipelineLookupId != null) {
            dto.setPipelineId(lookupTypeByLookupId != null
                ? lookupTypeByLookupId.getOrDefault(pipelineLookupId, pipelineId)
                : lookupDataRepository.findById(pipelineLookupId).map(ld -> ld.getLookupType()).orElse(pipelineId));
        } else {
            dto.setPipelineId(pipelineId);
        }
        if (!ProcessUtil.isNull(sourceTask.getSourceTaskType())) {
            dto.setSourceTaskType(getSourceTaskTypeDto(sourceTask.getSourceTaskType()));
        }
        return dto;
    }

    private SourceTaskTypeDto getSourceTaskTypeDto(SourceTaskType sourceTaskType) {
        SourceTaskTypeDto sourceTaskTypeDto = new SourceTaskTypeDto();
        sourceTaskTypeDto.setSourceTaskTypeId(sourceTaskType.getSourceTaskTypeId());
        sourceTaskTypeDto.setServiceName(sourceTaskType.getServiceName());
        sourceTaskTypeDto.setQueueTopicPartition(sourceTaskType.getQueueTopicPartition());
        sourceTaskTypeDto.setDescription(sourceTaskType.getDescription());
        sourceTaskTypeDto.setKafkaConnectionProfileId(sourceTaskType.getKafkaConnectionProfileId());
        return sourceTaskTypeDto;
    }

    private SchedulerDto getSchedulerDto(Scheduler scheduler) {
        SchedulerDto schedulerDto = new SchedulerDto();
        schedulerDto.setSchedulerId(scheduler.getSchedulerId());
        schedulerDto.setStartDate(scheduler.getStartDate());
        schedulerDto.setEndDate(scheduler.getEndDate());
        schedulerDto.setStartTime(scheduler.getStartTime());
        schedulerDto.setFrequency(scheduler.getFrequency());
        schedulerDto.setIntervalValue(scheduler.getIntervalValue());
        schedulerDto.setDaysOfWeek(scheduler.getDaysOfWeek());
        schedulerDto.setDayOfMonth(scheduler.getDayOfMonth());
        schedulerDto.setNextRunAt(scheduler.getNextRunAt());
        schedulerDto.setExpired(scheduler.isExpired());
        schedulerDto.setLastFlight(!scheduler.isExpired() && ProcessTimeUtil.isLastFlight(scheduler));
        return schedulerDto;
    }

    private SourceJobQueueDto getSourceJobQueueDto(JobQueue jobQueue) {
        SourceJobQueueDto sourceJobQueueDto = new SourceJobQueueDto();
        sourceJobQueueDto.setJobQueueId(jobQueue.getJobQueueId());
        sourceJobQueueDto.setDateCreated(jobQueue.getDateCreated());
        sourceJobQueueDto.setEndTime(jobQueue.getEndTime());
        sourceJobQueueDto.setJobId(jobQueue.getJobId());
        sourceJobQueueDto.setJobStatus(jobQueue.getJobStatus());
        sourceJobQueueDto.setJobStatusMessage(jobQueue.getJobStatusMessage());
        sourceJobQueueDto.setSkipTime(jobQueue.getSkipTime());
        sourceJobQueueDto.setStartTime(jobQueue.getStartTime());
        // These three were never copied, and jobSend is a primitive boolean on the DTO, so
        // every row came back false regardless of what was stored -- 197 of job 1244's 210
        // runs have job_send true in the database and the API reported none of them.
        sourceJobQueueDto.setJobSend(jobQueue.isJobSend());
        sourceJobQueueDto.setRunManual(jobQueue.getRunManual());
        sourceJobQueueDto.setSkipManual(jobQueue.getSkipManual());
        return sourceJobQueueDto;
    }


    /**
     * The profile screen's activity card, in one query pair instead of the whole job list.
     *
     * Scoped to the caller's own appUserId rather than to their tenant, so it needs no tenant
     * filter to be safe: a job is either assigned to them or it is not, and the id comes from the
     * token rather than the request. Nothing here can be asked on somebody else's behalf, which is
     * why the endpoint takes no user parameter.
     *
     * Returns an empty shape rather than an error when nobody is signed in or nothing has run --
     * this card is supplementary, and a profile that fails to load because a person has no jobs
     * yet would be worse than one that says so.
     */
    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchMyActivity(int limit, int windowDays) throws Exception {
        UserActivityDto activity = new UserActivityDto();
        activity.setWindowDays(windowDays);
        Long callerId = TenantContext.getAppUserId();
        if (callerId == null) {
            return new ResponseDto(SUCCESS, "No activity.", activity);
        }

        List<Object[]> assigned = this.sourceJobRepository.countAssignedTo(callerId);
        if (!assigned.isEmpty() && assigned.get(0) != null) {
            activity.setJobsAssigned(this.asLong(assigned.get(0)[0]));
            activity.setActiveJobs(this.asLong(assigned.get(0)[1]));
        }

        List<Object[]> counts = this.jobQueueRepository.countRecentRunsForAssignee(
            callerId, LocalDateTime.now().minusDays(windowDays));
        if (!counts.isEmpty() && counts.get(0) != null) {
            activity.setRecentRuns(this.asLong(counts.get(0)[0]));
            activity.setRecentFailures(this.asLong(counts.get(0)[1]));
        }

        for (Object[] row : this.jobQueueRepository.findRecentRunsForAssignee(callerId, limit)) {
            UserActivityDto.Run run = new UserActivityDto.Run();
            run.setJobQueueId(this.asLongOrNull(row[0]));
            run.setJobId(this.asLongOrNull(row[1]));
            run.setJobName((String) row[2]);
            run.setJobStatus((String) row[3]);
            run.setStartTime(this.asDateTime(row[4]));
            run.setEndTime(this.asDateTime(row[5]));
            // Only on a run that went wrong. A completed run's message says nothing worth a line
            // on a profile page, and some of them are long.
            if (row[3] != null && "FAILED".equalsIgnoreCase(String.valueOf(row[3]))) {
                run.setJobStatusMessage((String) row[6]);
            }
            activity.getRuns().add(run);
        }
        for (Object[] row : this.sourceJobRepository.outcomesForAssignee(callerId)) {
            activity.getOutcomes().add(new UserActivityDto.Outcome(
                String.valueOf(row[0]), this.asLong(row[1])));
        }
        return new ResponseDto(SUCCESS, "Activity fetched successfully.", activity);
    }

    /** Native counts come back as whatever the driver picked -- Long, BigInteger, Integer. */
    private long asLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private Long asLongOrNull(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    private LocalDateTime asDateTime(Object value) {
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        return value instanceof Timestamp ? ((Timestamp) value).toLocalDateTime() : null;
    }

}

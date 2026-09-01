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
import process.model.service.NotificationCenterService;
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
import process.socket.JobEventPublisher;

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
    private final JobEventPublisher jobEventPublisher;
    private final JobQueueRepository jobQueueRepository;
    private final LookupDataRepository lookupDataRepository;
    private final AppUserRepository appUserRepository;
    private final ProducerBulkEngine producerBulkEngine;
    private final TenantFilterHelper tenantFilterHelper;
    private final OpenSearchAuditLogClient openSearchAuditLogClient;
    private final NotificationCenterService notificationCenterService;

    @PersistenceContext
    private EntityManager entityManager;

    private final UserNameResolver userNameResolver;


    public SourceJobServiceImpl(SourceJobRepository sourceJobRepository,
        SchedulerRepository schedulerRepository,
        SourceTaskRepository sourceTaskRepository,
        JobAuditLogRepository jobAuditLogRepository,
        JobEventPublisher jobEventPublisher,
        JobQueueRepository jobQueueRepository,
        LookupDataRepository lookupDataRepository,
        AppUserRepository appUserRepository,
        ProducerBulkEngine producerBulkEngine,
        TenantFilterHelper tenantFilterHelper,
        OpenSearchAuditLogClient openSearchAuditLogClient,
        NotificationCenterService notificationCenterService,
        UserNameResolver userNameResolver) {
        this.userNameResolver = userNameResolver;
        this.sourceJobRepository = sourceJobRepository;
        this.schedulerRepository = schedulerRepository;
        this.sourceTaskRepository = sourceTaskRepository;
        this.jobAuditLogRepository = jobAuditLogRepository;
        this.jobEventPublisher = jobEventPublisher;
        this.jobQueueRepository = jobQueueRepository;
        this.lookupDataRepository = lookupDataRepository;
        this.appUserRepository = appUserRepository;
        this.producerBulkEngine = producerBulkEngine;
        this.tenantFilterHelper = tenantFilterHelper;
        this.openSearchAuditLogClient = openSearchAuditLogClient;
        this.notificationCenterService = notificationCenterService;
    }

    private void notifyTaskAssigned(SourceJob sourceJob, Long previousAssignedUserId) {
        Long newAssignedUserId = sourceJob.getAssignedUserId();
        if (newAssignedUserId == null || newAssignedUserId.equals(previousAssignedUserId)
            || newAssignedUserId.equals(TenantContext.getAppUserId())) {
            return;
        }
        this.notificationCenterService.create(sourceJob.getTenantId(), newAssignedUserId,
            NotificationType.TASK_ASSIGNED, NotificationSeverity.INFO,
            "Task assigned to you", sourceJob.getJobName() + " was assigned to you by " + TenantContext.getUsername() + ".",
            "/jobList");
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
        sourceJob.setJobStatus(Status.Active);
        sourceJob.setExecution(sourceJobDto.getExecution());
        sourceJob.setPriority(sourceJobDto.getPriority());
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
                    scheduler.setStartDate(schedulerDto.getStartDate());
                    if (!StringUtils.isEmpty(schedulerDto.getEndDate())) {
                        scheduler.setEndDate(schedulerDto.getEndDate());
                    }
                    scheduler.setStartTime(schedulerDto.getStartTime());
                    scheduler.setFrequency(schedulerDto.getFrequency());
                    if (!StringUtils.isEmpty(schedulerDto.getIntervalValue())) {
                        scheduler.setIntervalValue(schedulerDto.getIntervalValue());
                    }
                    scheduler.setDaysOfWeek(schedulerDto.getDaysOfWeek());
                    scheduler.setDayOfMonth(schedulerDto.getDayOfMonth());
                    ProcessTimeUtil.applyInitialSchedule(scheduler);
                    scheduler.setJobId(sourceJob.getJobId());
                    this.schedulerRepository.save(scheduler);
                });
        }
        return new ResponseDto(SUCCESS, String.format("Job save with jobId %d.", sourceJob.getJobId()));
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
                sourceJob.get().setPriority(sourceJobDto.getPriority());
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
                        if (scheduler.isPresent()) {
                            scheduler.get().setStartDate(schedulerDto.getStartDate());
                            if (!StringUtils.isEmpty(schedulerDto.getEndDate())) {
                                scheduler.get().setEndDate(schedulerDto.getEndDate());
                            }
                            scheduler.get().setStartTime(schedulerDto.getStartTime());
                            scheduler.get().setFrequency(schedulerDto.getFrequency());
                            if (!StringUtils.isEmpty(schedulerDto.getIntervalValue())) {
                                scheduler.get().setIntervalValue(schedulerDto.getIntervalValue());
                            }
                            scheduler.get().setDaysOfWeek(schedulerDto.getDaysOfWeek());
                            scheduler.get().setDayOfMonth(schedulerDto.getDayOfMonth());
                            ProcessTimeUtil.applyInitialSchedule(scheduler.get());
                            scheduler.get().setJobId(sourceJob.get().getJobId());
                            this.schedulerRepository.save(scheduler.get());
                        }
                    });
            }
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
        } else if (!ProcessUtil.isNull(sourceJob.get().getJobRunningStatus()) && (sourceJob.get().getJobRunningStatus().equals(JobStatus.Queue) ||
            sourceJob.get().getJobRunningStatus().equals(JobStatus.Running))) {
            return new ResponseDto(ERROR, "SourceJob can't be run if its in ('Queue', 'Running') state.");
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
        } else if (!ProcessUtil.isNull(sourceJob.get().getJobRunningStatus()) && (sourceJob.get().getJobRunningStatus().equals(JobStatus.Queue) ||
            sourceJob.get().getJobRunningStatus().equals(JobStatus.Running))) {
            return new ResponseDto(ERROR, "SourceJob can't be run if its in ('Queue', 'Running') state.");
        } else if (!sourceJob.get().getExecution().equals(Execution.Auto)) {
            return new ResponseDto(ERROR, "SourceJob skip only work with 'auto' source job.");
        }
        Optional<Scheduler> schedulerOpt = this.schedulerRepository.findSchedulerByJobId(sourceJob.get().getJobId());
        if (!schedulerOpt.isPresent()) {

            return new ResponseDto(ERROR, "SourceJob has no scheduler to skip.");
        }
        Scheduler scheduler = schedulerOpt.get();
        LocalDateTime nextJobRun = ProcessTimeUtil.computeNextRun(scheduler);
        boolean stillHasMoreFlights = !ProcessUtil.isNull(nextJobRun) && (ProcessUtil.isNull(scheduler.getEndDate()) ||
            !scheduler.getEndDate().atTime(scheduler.getStartTime()).isBefore(nextJobRun));
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
        List<SourceJobDto> sourceJobDtoList = jobs.stream()
            .map(job -> {
                SourceJobDto dto = mapSourceJobToDto(job);
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

    private SourceJobDto mapSourceJobToDto(SourceJob sourceJob) {
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
        dto.setExecution(sourceJob.getExecution());
        dto.setCompleteJob(sourceJob.isCompleteJob());
        dto.setFailJob(sourceJob.isFailJob());
        dto.setSkipJob(sourceJob.isSkipJob());
        if (!ProcessUtil.isNull(sourceJob.getTaskDetail())) {
            dto.setTaskDetail(mapSourceTaskToDto(sourceJob.getTaskDetail()));
        }
        if (!ProcessUtil.isNull(sourceJob.getAssignedUserId())) {
            dto.setAssignedUserId(sourceJob.getAssignedUserId());
            this.appUserRepository.findById(sourceJob.getAssignedUserId())
                .ifPresent(appUser -> dto.setAssignedUsername(appUser.getUsername()));
        }
        return dto;
    }

    private SourceTaskDto mapSourceTaskToDto(SourceTask sourceTask) {
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
            dto.setHomePageId(lookupDataRepository.findById(homePageLookupId)
                .map(ld -> ld.getLookupType()).orElse(null));
        }
        Long pipelineLookupId = ProcessUtil.parseLongOrNull(sourceTask.getPipelineId());
        if (pipelineLookupId != null) {
            dto.setPipelineId(lookupDataRepository.findById(pipelineLookupId)
               .map(ld -> ld.getLookupType()).orElse(null));
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
        return value instanceof java.sql.Timestamp ? ((java.sql.Timestamp) value).toLocalDateTime() : null;
    }

}

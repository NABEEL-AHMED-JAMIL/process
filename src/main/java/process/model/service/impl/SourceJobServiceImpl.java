package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import process.engine.ProducerBulkEngine;
import process.model.dto.*;
import process.model.enums.Execution;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.enums.UserRole;
import process.model.pojo.*;
import process.model.repository.*;
import process.model.service.SourceJobService;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.ProcessTimeUtil;
import process.util.ProcessUtil;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import static process.util.ProcessUtil.*;

/**
 * @author Nabeel Ahmed
 */
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

    @PersistenceContext
    private EntityManager entityManager;

    public SourceJobServiceImpl(SourceJobRepository sourceJobRepository,
        SchedulerRepository schedulerRepository,
        SourceTaskRepository sourceTaskRepository,
        JobAuditLogRepository jobAuditLogRepository,
        JobQueueRepository jobQueueRepository,
        LookupDataRepository lookupDataRepository,
        AppUserRepository appUserRepository,
        ProducerBulkEngine producerBulkEngine,
        TenantFilterHelper tenantFilterHelper) {
        this.sourceJobRepository = sourceJobRepository;
        this.schedulerRepository = schedulerRepository;
        this.sourceTaskRepository = sourceTaskRepository;
        this.jobAuditLogRepository = jobAuditLogRepository;
        this.jobQueueRepository = jobQueueRepository;
        this.lookupDataRepository = lookupDataRepository;
        this.appUserRepository = appUserRepository;
        this.producerBulkEngine = producerBulkEngine;
        this.tenantFilterHelper = tenantFilterHelper;
    }

    /**
     * Method use to check whether the caller (PLATFORM_ADMIN, or the tenant that owns this
     * job) is allowed to see/act on it -- the Hibernate filter (TenantFilterHelper) only
     * protects list/query results; a by-id lookup like findById can still return a row outside
     * the filter's reach if called incorrectly, so every by-id read/write path checks this
     * explicitly too (belt and suspenders against IDOR: a tenant guessing/incrementing another
     * tenant's job id).
     * @param sourceJob
     * @return boolean
     * */
    private boolean isOwnedByCaller(SourceJob sourceJob) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        return sourceJob != null && Objects.equals(sourceJob.getTenantId(), TenantContext.getTenantId());
    }

    /**
     * Method use to check whether a SourceTask is linkable by the caller (PLATFORM_ADMIN, or
     * the tenant that owns it) -- without this, addSourceJob/updateSourceJob would let any
     * tenant user point a job at ANOTHER tenant's SourceTask just by guessing its
     * taskDetailId, which then leaks that task's name/payload/queue-topic right back to the
     * caller's own tenant through the job's mapped taskDetail (see mapSourceJobToDto).
     * @param sourceTask
     * @return boolean
     * */
    private boolean isOwnedByCaller(SourceTask sourceTask) {
        if (TenantContext.isPlatformAdmin()) {
            return true;
        }
        return sourceTask != null && Objects.equals(sourceTask.getTenantId(), TenantContext.getTenantId());
    }

    /**
     * Method use to add the source job. @Transactional so the SourceJob save and its Scheduler
     * rows commit/roll back together -- without it, a failure partway through the schedulers
     * forEach (e.g. a constraint violation on the second scheduler) left the already-flushed
     * SourceJob (and any earlier scheduler) permanently committed with no rollback: an orphaned,
     * half-scheduled job the UI shows as created but that never runs as configured.
     * @param sourceJobDto
     * @return ResponseDto
     * */
    @Override
    @Transactional
    public ResponseDto addSourceJob(SourceJobDto sourceJobDto) throws Exception {
        if (ProcessUtil.isNull(sourceJobDto.getJobName())) {
            return new ResponseDto(ERROR, "SourceJob jobName missing.");
        } else if (ProcessUtil.isNull(sourceJobDto.getTaskDetail())) {
            return new ResponseDto(ERROR, "SourceJob taskDetail missing.");
        } else if (ProcessUtil.isNull(sourceJobDto.getTaskDetail().getTaskDetailId())) {
            return new ResponseDto(ERROR, "SourceJob taskDetailId missing.");
        }
        // validation for scheduler list -> if any missing then
        Optional<SourceTask> taskDetail = this.sourceTaskRepository.findById(
             sourceJobDto.getTaskDetail().getTaskDetailId());
        if (!taskDetail.isPresent() || !this.isOwnedByCaller(taskDetail.get())) {
            return new ResponseDto(ERROR, String.format("SourceTask not found with %d.",
                sourceJobDto.getTaskDetail().getTaskDetailId()));
        }
        Long tenantId = TenantContext.getTenantId();
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
        // Defaults to whoever created the job -- explicitly reassignable via updateSourceJob
        // once there's a UI for it. This is who sendJobStatusNotification pushes run updates to.
        sourceJob.setAssignedUserId(assignedUserId);
        this.sourceJobRepository.saveAndFlush(sourceJob);
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
                    if (!StringUtils.isEmpty(schedulerDto.getRecurrence())) {
                        scheduler.setRecurrence(schedulerDto.getRecurrence());
                    }
                    scheduler.setRecurrenceTime(ProcessTimeUtil.getRecurrenceTime(
                        schedulerDto.getStartDate(), schedulerDto.getStartTime().toString()));
                    scheduler.setJobId(sourceJob.getJobId());
                    this.schedulerRepository.save(scheduler);
                });
        }
        return new ResponseDto(SUCCESS, String.format("Job save with jobId %d.", sourceJob.getJobId()));
    }

    /**
     * Method use to update the source job
     * @param sourceJobDto
     * @return ResponseDto
     * */
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
        // findById is a primary-key lookup -- Hibernate's @Filter is known to not reliably
        // apply to get()/load() by id (unlike list/criteria queries), so the explicit
        // isOwnedByCaller check below is the real guard here, not just defense-in-depth.
        if (sourceJob.isPresent() && !this.isOwnedByCaller(sourceJob.get())) {
            return new ResponseDto(ERROR, String.format("SourceJob not found with %d.", sourceJobDto.getJobId()));
        }
        if (sourceJob.isPresent()) {
            sourceJob.get().setJobName(sourceJobDto.getJobName());
            // check source active then allow to link
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
            if (!ProcessUtil.isNull(sourceJobDto.getAssignedUserId())) {
                String assigneeError = this.validateAssignee(sourceJobDto.getAssignedUserId(), sourceJob.get().getTenantId());
                if (assigneeError != null) {
                    return new ResponseDto(ERROR, assigneeError);
                }
                sourceJob.get().setAssignedUserId(sourceJobDto.getAssignedUserId());
            }
            this.sourceJobRepository.saveAndFlush(sourceJob.get());
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
                            if (!StringUtils.isEmpty(schedulerDto.getRecurrence())) {
                                scheduler.get().setRecurrence(schedulerDto.getRecurrence());
                            }
                            scheduler.get().setRecurrenceTime(ProcessTimeUtil.getRecurrenceTime(
                                schedulerDto.getStartDate(), schedulerDto.getStartTime().toString()));
                            scheduler.get().setJobId(sourceJob.get().getJobId());
                            this.schedulerRepository.save(scheduler.get());
                        }
                    });
            }
            return new ResponseDto(SUCCESS, String.format("Job save with jobId %d.", sourceJobDto.getJobId()));
        }
        return new ResponseDto(ERROR, String.format("SourceJob not found with %d.", sourceJobDto.getJobId()));
    }

    /**
     * Method use to delete teh source job
     * @param sourceJobDto
     * @return ResponseDto
     * */
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
            // mark the source job as deleted
            sourceJob.get().setJobStatus(Status.Delete);
            this.sourceJobRepository.save(sourceJob.get());

            // mark related job_queue rows as deleted (logical delete) using a bulk update query
            try {
                int updated = this.jobQueueRepository.updateStatusByJobId(sourceJobDto.getJobId(), Status.Delete.name());
                if (updated > 0) {
                    // update audit log statuses for those queues in a single query
                    this.jobAuditLogRepository.updateStatusByJobId(sourceJobDto.getJobId(), Status.Delete.name());
                }
            } catch (Exception ex) {
                logger.error("An error occurred while updating related job queue/audit logs during deleteSourceJob :- {}.", ex);
            }

            return new ResponseDto(SUCCESS, String.format("SourceJob successfully update with %d.", sourceJobDto.getJobId()));
        }
        return new ResponseDto(ERROR, String.format("SourceJob not found with %d.", sourceJobDto.getJobId()));
    }

    /**
     * Method use to run the source job
     * @param sourceJobDto
     * @return ResponseDto
     * */
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

    /**
     * Method use to run the source job
     * @param sourceJobDto
     * @return ResponseDto
     * */
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
            // an Auto-execution job can legally have no Scheduler row (addSourceJob treats it as optional)
            return new ResponseDto(ERROR, "SourceJob has no scheduler to skip.");
        }
        Scheduler scheduler = schedulerOpt.get();
        LocalDateTime nextJobRun = ProcessTimeUtil.computeNextRun(scheduler);
        if (!ProcessUtil.isNull(scheduler.getEndDate())) {
            LocalDateTime schedulerEndDateTime = scheduler.getEndDate().atTime(scheduler.getStartTime());
            if (!ProcessUtil.isNull(nextJobRun) && (schedulerEndDateTime.equals(nextJobRun) || schedulerEndDateTime.isAfter(nextJobRun))) {
                this.producerBulkEngine.skipManualJobInQueue(scheduler);
                scheduler.setRecurrenceTime(nextJobRun);
                this.schedulerRepository.save(scheduler);
                return new ResponseDto(SUCCESS, "SourceJob skip successfully.", scheduler);
            }
        } else if (!ProcessUtil.isNull(nextJobRun)) {
            this.producerBulkEngine.skipManualJobInQueue(scheduler);
            scheduler.setRecurrenceTime(nextJobRun);
            this.schedulerRepository.save(scheduler);
            return new ResponseDto(SUCCESS, "SourceJob skip successfully.", scheduler);
        }
        return new ResponseDto(ERROR, "No more flight skip.");
    }

    /**
     * Method use to find the source job audit log
     * @param jobQueueId
     * @param jobId
     * @return ResponseDto
     * */
    @Override
    @Transactional(readOnly = true)
    public ResponseDto findSourceJobAuditLog(Long jobQueueId, Long jobId) throws Exception {
        if (jobQueueId == null) {
            return new ResponseDto(ERROR, "JobQueueId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        // JobQueue/JobAuditLog have no tenantId column of their own -- they're only reachable
        // scoped through the SourceJob that owns them, so gate on that job's ownership first
        // rather than trusting jobQueueId/jobId to already belong to the same tenant.
        Optional<SourceJob> sourceJobOpt = this.sourceJobRepository.findById(jobId);
        if (!sourceJobOpt.isPresent() || !this.isOwnedByCaller(sourceJobOpt.get())) {
            return new ResponseDto(ERROR, String.format("SourceJob not found with %d.", jobId));
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("auditLogs", jobAuditLogRepository.findAllByJobQueueIdV1(jobQueueId));
        SourceJobDto sourceJobDto = mapSourceJobToDto(sourceJobOpt.get());
        schedulerRepository.findSchedulerByJobId(jobId).ifPresent(s -> sourceJobDto.setScheduler(getSchedulerDto(s)));
        payload.put("sourceJob", sourceJobDto);
        jobQueueRepository.findById(jobQueueId)
            .filter(queue -> jobId.equals(queue.getJobId()))
            .ifPresent(queue -> payload.put("sourceJobQueue", getSourceJobQueueDto(queue)));
        return new ResponseDto(SUCCESS, "SourceJob skip successfully.", payload);
    }

    /**
     * Method use to fetch source job detail with source job id
     * @param jobId
     * @return ResponseDto
     * */
    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchSourceJobDetailWithSourceJobId(Long jobId) {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        return sourceJobRepository.findById(jobId)
            .filter(this::isOwnedByCaller)
            .map(sourceJob -> {
                SourceJobDto dto = mapSourceJobToDto(sourceJob);
                schedulerRepository.findSchedulerByJobId(jobId).ifPresent(s -> dto.setScheduler(getSchedulerDto(s)));
                return new ResponseDto(SUCCESS, String.format("SourceJob found with %d.", jobId), dto);
            }).orElseGet(() -> new ResponseDto(ERROR, String.format("SourceJob not found with %d.", jobId)));
    }

    /**
     * Method use to fetch the job queue (run history) list for a given source job id,
     * most recent run first
     * @param jobId
     * @return ResponseDto
     * */
    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchSourceJobQueueListWithJobId(Long jobId) throws Exception {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        // JobQueue has no tenantId of its own -- gate on the owning SourceJob's tenant first.
        Optional<SourceJob> sourceJobOpt = this.sourceJobRepository.findById(jobId);
        if (!sourceJobOpt.isPresent() || !this.isOwnedByCaller(sourceJobOpt.get())) {
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

    /**
     * Method use the list source job
     * @return ResponseDto
     * */
    @Override
    @Transactional(readOnly = true)
    public ResponseDto listSourceJob() throws Exception {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        List<SourceJobDto> sourceJobDtoList = sourceJobRepository.findAllActiveAndInactiveJobs(
            Status.Active, Status.Inactive, Sort.by(Sort.Direction.ASC, "jobId"))
            .stream()
            .map(job -> {
                SourceJobDto dto = mapSourceJobToDto(job);
                schedulerRepository.findSchedulerByJobId(job.getJobId()).ifPresent(s -> dto.setScheduler(getSchedulerDto(s)));
                dto.setTabActive(jobQueueRepository.getCountForJobByJobId(job.getJobId()) > 0);
                return dto;
            }).collect(Collectors.toList());
        return new ResponseDto(SUCCESS, "Fetch source jobs.", sourceJobDtoList);
    }

    /**
     * Method use to map the source job to dto
     * @param sourceJob
     * @return SourceJobDto
     */
    /**
     * Method use to validate a job's assignedUserId before it's saved -- must be a real,
     * non-deleted user in the same tenant as the job. Without this, addSourceJob/updateSourceJob
     * (reachable by any TENANT_USER, not just admins) would accept an arbitrary id and
     * BulkAction.sendJobStatusNotification would push this job's status/name to whoever that id
     * happens to belong to, including a user in a different tenant entirely.
     * @param assignedUserId
     * @param tenantId
     * @return String error message, or null if valid
     * */
    private String validateAssignee(Long assignedUserId, Long tenantId) {
        Optional<AppUser> assignee = this.appUserRepository.findById(assignedUserId);
        if (!assignee.isPresent() || assignee.get().getStatus() == Status.Delete) {
            return String.format("Assigned user not found with %d.", assignedUserId);
        }
        // PLATFORM_ADMIN isn't bound to any tenant (tenantId==null) by design -- they operate
        // across every tenant, so they're always a valid assignee regardless of whose job it is.
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

    /**
     * Method use to map the source task to dto
     * @param sourceTask
     * @return SourceTaskDto
     */
    private SourceTaskDto mapSourceTaskToDto(SourceTask sourceTask) {
        SourceTaskDto dto = new SourceTaskDto();
        dto.setTaskDetailId(sourceTask.getTaskDetailId());
        dto.setTaskName(sourceTask.getTaskName());
        dto.setTaskStatus(sourceTask.getTaskStatus());
        dto.setTaskPayload(sourceTask.getTaskPayload());
        if (!ProcessUtil.isNull(sourceTask.getHomePageId())) {
            dto.setHomePageId(lookupDataRepository.findById(Long.valueOf(sourceTask.getHomePageId()))
                .map(ld -> ld.getLookupType()).orElse(null));
        }
        if (!ProcessUtil.isNull(sourceTask.getPipelineId())) {
            dto.setPipelineId(lookupDataRepository.findById(Long.valueOf(sourceTask.getPipelineId()))
               .map(ld -> ld.getLookupType()).orElse(null));
        }
        if (!ProcessUtil.isNull(sourceTask.getSourceTaskType())) {
            dto.setSourceTaskType(getSourceTaskTypeDto(sourceTask.getSourceTaskType()));
        }
        return dto;
    }

    /**
     * Method use to get the source task type dto
     * @param sourceTaskType
     * @return SourceTaskTypeDto
     * */
    private SourceTaskTypeDto getSourceTaskTypeDto(SourceTaskType sourceTaskType) {
        SourceTaskTypeDto sourceTaskTypeDto = new SourceTaskTypeDto();
        sourceTaskTypeDto.setSourceTaskTypeId(sourceTaskType.getSourceTaskTypeId());
        sourceTaskTypeDto.setServiceName(sourceTaskType.getServiceName());
        sourceTaskTypeDto.setQueueTopicPartition(sourceTaskType.getQueueTopicPartition());
        sourceTaskTypeDto.setDescription(sourceTaskType.getDescription());
        sourceTaskTypeDto.setKafkaConnectionProfileId(sourceTaskType.getKafkaConnectionProfileId());
        return sourceTaskTypeDto;
    }

    /***
     * Method use to get the scheduler dto
     * @param scheduler
     * @return SchedulerDto
     */
    private SchedulerDto getSchedulerDto(Scheduler scheduler) {
        SchedulerDto schedulerDto = new SchedulerDto();
        schedulerDto.setSchedulerId(scheduler.getSchedulerId());
        schedulerDto.setStartDate(scheduler.getStartDate());
        schedulerDto.setEndDate(scheduler.getEndDate());
        schedulerDto.setStartTime(scheduler.getStartTime());
        schedulerDto.setFrequency(scheduler.getFrequency());
        schedulerDto.setRecurrence(scheduler.getRecurrence());
        schedulerDto.setRecurrenceTime(scheduler.getRecurrenceTime());
        return schedulerDto;
    }

    /**
     * Method use to get the source job queue dto
     * @param jobQueue
     * @return SourceJobQueueDto
     * */
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
        return sourceJobQueueDto;
    }

    /**
     * Method use to get the local datetime for next flight
     * @param scheduler
     * @return LocalDateTime
     * */
}

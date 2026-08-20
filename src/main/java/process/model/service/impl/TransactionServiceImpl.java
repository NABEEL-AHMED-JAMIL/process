package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import process.model.enums.Status;
import process.model.pojo.*;
import process.model.projection.SourceJobProjection;
import process.model.repository.*;
import process.security.TenantContext;
import process.util.OpenSearchAuditLogClient;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class TransactionServiceImpl {

    private Logger logger = LoggerFactory.getLogger(TransactionServiceImpl.class);

    private final SourceJobRepository sourceJobRepository;
    private final SchedulerRepository schedulerRepository;
    private final JobQueueRepository jobQueueRepository;
    private final LookupDataRepository lookupDataRepository;
    private final JobAuditLogRepository jobAuditLogRepository;
    private final SourceTaskRepository sourceTaskRepository;
    private final OpenSearchAuditLogClient openSearchAuditLogClient;

    public TransactionServiceImpl(SourceJobRepository sourceJobRepository,
        SchedulerRepository schedulerRepository,
        JobQueueRepository jobQueueRepository,
        LookupDataRepository lookupDataRepository,
        JobAuditLogRepository jobAuditLogRepository,
        SourceTaskRepository sourceTaskRepository,
        OpenSearchAuditLogClient openSearchAuditLogClient) {
        this.sourceJobRepository = sourceJobRepository;
        this.schedulerRepository = schedulerRepository;
        this.jobQueueRepository = jobQueueRepository;
        this.lookupDataRepository = lookupDataRepository;
        this.jobAuditLogRepository = jobAuditLogRepository;
        this.sourceTaskRepository = sourceTaskRepository;
        this.openSearchAuditLogClient = openSearchAuditLogClient;
    }

    public void saveJobAuditLogs(Long jobQueueId, String logsDetail) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        String externalId = UUID.randomUUID().toString();
        if (this.openSearchAuditLogClient.index(externalId, jobQueueId, logsDetail, now)) {
            return;
        }
        JobAuditLogs jobAuditLogs = new JobAuditLogs();
        jobAuditLogs.setJobQueueId(jobQueueId);
        jobAuditLogs.setLogsDetail(logsDetail);
        this.jobAuditLogRepository.save(jobAuditLogs);
    }

    public void saveOrUpdateJob(SourceJob sourceJob) {
        this.sourceJobRepository.saveAndFlush(sourceJob);
    }

    public void saveOrUpdateScheduler(Scheduler scheduler) {
        this.schedulerRepository.save(scheduler);
    }

    public void saveOrUpdateJobQueue(JobQueue jobQueue) {
        this.jobQueueRepository.save(jobQueue);
    }

    public void updateLookupDate(LookupData lookupData) {
        this.lookupDataRepository.save(lookupData);
    }

    public Optional<SourceJob> findByJobIdAndJobStatus(Long jobId, Status status) {
        return this.sourceJobRepository.findByJobIdAndJobStatus(jobId, status);
    }

    public Optional<SourceJob> findByJobId(Long jobId) {
        return this.sourceJobRepository.findById(jobId);
    }

    public Optional<JobQueue> findJobQueueByJobQueueId(Long jobQueueId) {
        return this.jobQueueRepository.findById(jobQueueId);
    }

    public List<Scheduler> findDueSchedulers(LocalDateTime now) {
        return this.schedulerRepository.findDueSchedulers(now);
    }

    public List<JobQueue> findAllJobForTodayWithLimit(Long limit) {
        return this.jobQueueRepository.findAllJobForTodayWithLimit(limit);
    }

    public void updateJobQueue(JobQueue jobQueue) {
        this.jobQueueRepository.save(jobQueue);
    }

    public LookupData findByLookupType(String lookupType) {
        return this.lookupDataRepository.findByLookupType(lookupType);
    }

    public String findLookupValueByLookupId(Long lookupId) {
        return this.lookupDataRepository.findById(lookupId)
            .map(LookupData::getLookupValue)
            .orElse(null);
    }

    public Optional<SourceTask> findByTaskDetailIdAndTaskStatus(Long taskDetailId) {
        return this.sourceTaskRepository.findByTaskDetailIdAndTaskStatus(taskDetailId, Status.Active)
            .filter(task -> TenantContext.isPlatformAdmin() || Objects.equals(task.getTenantId(), TenantContext.getTenantId()));
    }

    public List<Long> findAllSourceTask() {
        return TenantContext.isPlatformAdmin()
            ? this.sourceTaskRepository.findAllSourceTask()
            : this.sourceTaskRepository.findAllSourceTaskForTenant(TenantContext.getTenantId());
    }

    public Integer getCountForInQueueJobByJobId(Long jobId) {
        return this.jobQueueRepository.getCountForInQueueJobByJobId(jobId);
    }

    public List<SourceJobProjection> fetchRunningJobEvent(List<Long> jobIds) {
        return this.sourceJobRepository.fetchRunningJobEvent(jobIds);
    }

}

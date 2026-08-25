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
import java.util.ArrayList;
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

    /**
     * Many audit lines at once, for a worker that batches instead of posting per line.
     *
     * Same contract as the single-line path: OpenSearch first, the database only if that is
     * unavailable. The fallback loops rather than bulk-inserting because it is the cold path
     * -- if OpenSearch is down, a slower write is the least of the problems.
     */
    public void saveJobAuditLogs(Long jobQueueId, List<String> logDetails) {
        if (logDetails == null || logDetails.isEmpty()) {
            return;
        }
        Timestamp now = new Timestamp(System.currentTimeMillis());
        List<Object[]> entries = new ArrayList<>();
        for (String detail : logDetails) {
            entries.add(new Object[]{ UUID.randomUUID().toString(), jobQueueId, detail, now });
        }
        if (this.openSearchAuditLogClient.indexAll(entries)) {
            return;
        }
        for (String detail : logDetails) {
            JobAuditLogs row = new JobAuditLogs();
            row.setJobQueueId(jobQueueId);
            row.setLogsDetail(detail);
            this.jobAuditLogRepository.save(row);
        }
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

    public List<JobQueue> findStalledRuns(LocalDateTime startedBefore) {
        return this.jobQueueRepository.findStalledRuns(startedBefore);
    }

    public void saveJobQueue(JobQueue jobQueue) {
        this.jobQueueRepository.save(jobQueue);
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

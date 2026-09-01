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

/**
 * @author Nabeel Ahmed
 * */
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

    /**
     * The caller already holds the queue row this line belongs to. A caller that was handed the
     * job and the run as two separate values has to use the overload that takes both, so the
     * pairing is checked rather than assumed.
     */
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
     * Same contract as the single-line path: OpenSearch first, the database only for what
     * OpenSearch would not take. Asking which lines were rejected rather than whether the batch
     * succeeded matters on a partial rejection -- writing the whole batch to the database then
     * stored the accepted lines in both places, and since the merged view keys OpenSearch rows by
     * external id and database rows by content, those lines rendered twice. The fallback loops
     * rather than bulk-inserting because it is the cold path: if OpenSearch is refusing writes, a
     * slower one is the least of the problems.
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
        for (Object[] rejected : this.openSearchAuditLogClient.indexAllReturningFailures(entries)) {
            JobAuditLogs row = new JobAuditLogs();
            row.setJobQueueId(jobQueueId);
            row.setLogsDetail((String) rejected[2]);
            this.jobAuditLogRepository.save(row);
        }
    }

    /**
     * The same write, for a caller handed the job and the run as two independent values.
     *
     * A worker callback names both in its request and nothing in the pair ties them together, so
     * quoting a job the caller is entitled to next to somebody else's queue id landed the line on
     * the latter. The endpoint that does this today checks first, but the check belongs on the
     * write as well: any other path reaching here with an unverified pair gets the same answer.
     * The queue row is the only thing that knows which job it belongs to, so it is what decides.
     */
    public void saveJobAuditLogs(Long jobId, Long jobQueueId, String logsDetail) {
        if (!this.queueBelongsToJob(jobId, jobQueueId)) {
            return;
        }
        this.saveJobAuditLogs(jobQueueId, logsDetail);
    }

    /** The batched form of the checked write, for a worker that buffers its lines. */
    public void saveJobAuditLogs(Long jobId, Long jobQueueId, List<String> logDetails) {
        if (!this.queueBelongsToJob(jobId, jobQueueId)) {
            return;
        }
        this.saveJobAuditLogs(jobQueueId, logDetails);
    }

    private boolean queueBelongsToJob(Long jobId, Long jobQueueId) {
        if (jobId == null || jobQueueId == null) {
            return false;
        }
        Optional<JobQueue> jobQueue = this.jobQueueRepository.findById(jobQueueId);
        if (jobQueue.isPresent() && jobId.equals(jobQueue.get().getJobId())) {
            return true;
        }
        this.logger.warn("Refusing an audit log write: jobQueueId {} does not belong to jobId {}.", jobQueueId, jobId);
        return false;
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

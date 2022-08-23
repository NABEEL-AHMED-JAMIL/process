package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.*;
import process.model.repository.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Service
@Transactional
public class TransactionServiceImpl {

    private Logger logger = LoggerFactory.getLogger(TransactionServiceImpl.class);

    // repo
    @Autowired
    private SourceJobRepository sourceJobRepository;
    @Autowired
    private SchedulerRepository schedulerRepository;
    @Autowired
    private JobQueueRepository jobQueueRepository;
    @Autowired
    private LookupDataRepository lookupDataRepository;
    @Autowired
    private JobAuditLogRepository jobAuditLogRepository;
    @Autowired
    private SourceTaskRepository sourceTaskRepository;

    /**
     * The method use to save the logs for job
     * @param jobQueueId
     * @param logsDetail
     */
    public void saveJobAuditLogs(Long jobQueueId, String logsDetail) {
        JobAuditLogs jobAuditLogs = new JobAuditLogs();
        jobAuditLogs.setJobQueueId(jobQueueId);
        jobAuditLogs.setLogsDetail(logsDetail);
        this.jobAuditLogRepository.save(jobAuditLogs);
    }

    /**
     * The method use to update the job
     * @param sourceJob
     * */
    public void saveOrUpdateJob(SourceJob sourceJob) {
        this.sourceJobRepository.saveAndFlush(sourceJob);
    }

    /**
     * The method use to update the scheduler
     * @param scheduler
     * */
    public void saveOrUpdateScheduler(Scheduler scheduler) {
        this.schedulerRepository.save(scheduler);
    }

    /**
     * The method use to update the job-queue
     * @param jobQueue
     * */
    public void saveOrUpdateJobQueue(JobQueue jobQueue) {
        this.jobQueueRepository.save(jobQueue);
    }

    /**
     * The method use to update the lookup-data
     * @param lookupData
     */
    public void updateLookupDate(LookupData lookupData) {
        this.lookupDataRepository.save(lookupData);
    }

    /**
     * The method use to get the job by name and job status
     * @param jobName
     * @param status
     * @return Job
     */
    public Optional<SourceJob> findByJobNameAndJobStatus(String jobName, Status status) {
        return this.sourceJobRepository.findByJobNameAndJobStatus(jobName, status);
    }

    /**
     * The method use to get the job by jobId and job status
     * @param jobId
     * @param status
     * @return Job
     */
    public Optional<SourceJob> findByJobIdAndJobStatus(Long jobId, Status status) {
        return this.sourceJobRepository.findByJobIdAndJobStatus(jobId, status);
    }

    /**
     * The method use to get the job by id
     * @param jobId
     * @return Job
     */
    public Optional<SourceJob> findByJobId(Long jobId) {
        return this.sourceJobRepository.findById(jobId);
    }

    /**
     * The method use to get the JobQueue by
     * @param jobQueueId
     * @return JobQueue
     */
    public Optional<JobQueue> findJobQueueByJobQueueId(Long jobQueueId) {
        return this.jobQueueRepository.findById(jobQueueId);
    }

    /**
     * The method use to get the scheduler for the current date
     * @param todayDate
     * @return Scheduler
     */
    public List<Scheduler> findAllSchedulerForToday(LocalDate todayDate) {
        return this.schedulerRepository.findAllSchedulerForToday(todayDate);
    }

    /**
     * The method use to get the all job which status in queue state
     * @param limit
     * @return JobQueue
     */
    public List<JobQueue> findAllJobForTodayWithLimit(Long limit) {
        return this.jobQueueRepository.findAllJobForTodayWithLimit(limit);
    }

    /**
     * The method use to fine the lookup-data
     * @param lookupType
     * @return LookupData
     */
    public LookupData findByLookupType(String lookupType) {
        return this.lookupDataRepository.findByLookupType(lookupType);
    }

    /**
     * This method use to fetch all jobId
     * @return List<Long>
     */
    public List<SourceJob> findJobByJobRunningStatus() {
        return this.sourceJobRepository.findJobByJobRunningStatus(JobStatus.PartialComplete);
    }

    public Optional<SourceJob> findJobById(Long sourceJobId) {
        return this.sourceJobRepository.findById(sourceJobId);
    }

    public Optional<SourceTask> findByTaskDetailIdAndTaskStatus(Long taskDetailId) {
        return this.sourceTaskRepository.findByTaskDetailIdAndTaskStatus(taskDetailId, Status.Active);
    }

    public List<Long> findAllSourceTask() {
        return this.sourceTaskRepository.findAllSourceTask();
    }

}

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
    private JobRepository jobRepository;
    @Autowired
    private SchedulerRepository schedulerRepository;
    @Autowired
    private JobHistoryRepository jobHistoryRepository;
    @Autowired
    private LookupDataRepository lookupDataRepository;
    @Autowired
    private JobAuditLogRepository jobAuditLogRepository;

    /**
     * The method use to save the logs for job
     * @param jobId
     * @param jobHistoryId
     * @param logsDetail
     * @return JobHistoryDto
     */
    public void saveJobAuditLogs(Long jobId, Long jobHistoryId, String logsDetail) {
        JobAuditLogs jobAuditLogs = new JobAuditLogs();
        jobAuditLogs.setJobId(jobId);
        jobAuditLogs.setJobHistoryId(jobHistoryId);
        jobAuditLogs.setLogsDetail(logsDetail);
        this.jobAuditLogRepository.save(jobAuditLogs);
    }

    /**
     * The method use to update the job
     * @param job
     * */
    public void saveOrUpdateJob(Job job) {
        this.jobRepository.saveAndFlush(job);
    }

    /**
     * The method use to update the scheduler
     * @param scheduler
     * */
    public void saveOrUpdateScheduler(Scheduler scheduler) {
        this.schedulerRepository.saveAndFlush(scheduler);
    }

    /**
     * The method use to update the job-history
     * @param jobHistory
     * */
    public void saveOrUpdateJobHistory(JobHistory jobHistory) {
        this.jobHistoryRepository.saveAndFlush(jobHistory);
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
    public Optional<Job> findByJobNameAndJobStatus(String jobName, Status status) {
        return this.jobRepository.findByJobNameAndJobStatus(jobName, status);
    }

    /**
     * The method use to get the job by name and job status
     * @param jobId
     * @param status
     * @return Job
     */
    public Optional<Job> findByJobIdAndJobStatus(Long jobId, Status status) {
        return this.jobRepository.findByJobIdAndJobStatus(jobId, status);
    }

    /**
     * The method use to get the JobHistory by
     * @param jobHistoryId
     * @param jobHistoryId
     * @return JobHistory
     */
    public Optional<JobHistory> findJobHistoryByJobHistoryId(Long jobHistoryId) {
        return this.jobHistoryRepository.findById(jobHistoryId);
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
     * @return JobHistory
     */
    public List<JobHistory> findAllJobForTodayWithLimit(Long limit) {
        return this.jobHistoryRepository.findAllJobForTodayWithLimit(limit);
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
    public List<Job> findJobByJobRunningStatus() {
        return this.jobRepository.findJobByJobRunningStatus(JobStatus.PartialComplete);
    }
}

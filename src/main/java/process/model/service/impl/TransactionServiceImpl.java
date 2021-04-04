package process.model.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.JobDto;
import process.model.dto.JobHistoryDto;
import process.model.dto.SchedulerDto;
import process.model.enums.Status;
import process.model.mapper.JobHistoryMapper;
import process.model.mapper.JobMapper;
import process.model.mapper.SchedulerMapper;
import process.model.pojo.*;
import process.model.repository.*;
import process.model.repository.projection.JobViewProjection;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;


/**
 * @author Nabeel Ahmed
 */
@Service
@Transactional
@Scope("singleton")
public class TransactionServiceImpl {

    private Logger logger = LoggerFactory.getLogger(TransactionServiceImpl.class);

    // mapper
    @Autowired
    private JobMapper jobMapper;
    @Autowired
    private SchedulerMapper schedulerMapper;
    @Autowired
    private JobHistoryMapper jobHistoryMapper;

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
     * The method use to save the job
     * @param jobDto
     * @return Job
     */
    public JobDto saveJob(JobDto jobDto) {
        Job job = this.jobMapper.mapToEntity(jobDto);
        this.jobRepository.saveAndFlush(job);
        jobDto.setJobId(job.getJobId());
        logger.info("Job Save " + job);
        return jobDto;
    }

    /**
     * The method use to save the scheduler
     * @param schedulerDto
     * @return SchedulerDto
     */
    public SchedulerDto saveScheduler(SchedulerDto schedulerDto) {
        Scheduler scheduler = this.schedulerMapper.mapToEntity(schedulerDto);
        this.schedulerRepository.saveAndFlush(scheduler);
        schedulerDto.setJobId(scheduler.getJobId());
        logger.info("Scheduler Save " + scheduler);
        return schedulerDto;
    }

    /**
     * The method use to save the jobHistory
     * @param jobHistoryDto
     * @return JobHistoryDto
     */
    public JobHistoryDto saveJobHistory(JobHistoryDto jobHistoryDto) {
        JobHistory jobHistory = this.jobHistoryMapper.mapToEntity(jobHistoryDto);
        this.jobHistoryRepository.saveAndFlush(jobHistory);
        jobHistoryDto.setJobHistoryId(jobHistory.getJobHistoryId());
        logger.info("JobHistory Save " + jobHistory);
        return jobHistoryDto;
    }

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

    public void updateJobStatus(Job job) {
        this.jobRepository.saveAndFlush(job);
    }

    public void updateScheduler(Scheduler scheduler) {
        this.schedulerRepository.saveAndFlush(scheduler);
    }

    public void updateJobHistory(JobHistory jobHistory) {
        this.jobHistoryRepository.saveAndFlush(jobHistory);
    }

    /**
     * The method use to get the job by name and job status
     * @param jobName
     * @param status
     * @return Job
     */
    public Optional<Job> findByJobNameAndJobStatus(String jobName, Status status) {
        logger.info("JobName " + jobName + " Status " + status);
        return this.jobRepository.findByJobNameAndJobStatus(jobName, status);
    }

    /**
     * The method use to get the job by name and job status
     * @param jobId
     * @param status
     * @return Job
     */
    public Optional<Job> findByJobIdAndJobStatus(Long jobId, Status status) {
        logger.info("JobId " + jobId + " Status " + status);
        return this.jobRepository.findByJobIdAndJobStatus(jobId, status);
    }

    /**
     * The method use to get the job by name and job status
     * @param page
     * @param size
     * @return Job
     */
    public Page<JobViewProjection> getJobs(int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        return this.jobRepository.findByJobStatus(pageRequest);
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
     * The method use to update the lookup-data
     * @param lookupData
     */
    public void updateLookupDate(LookupData lookupData) {
        this.lookupDataRepository.save(lookupData);
    }

    /**
     * The method use to fine the lookup-data
     * @param lookupType
     * @return LookupData
     */
    public LookupData findByLookupType(String lookupType) {
        return this.lookupDataRepository.findByLookupType(lookupType);
    }
}

package process.engine;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.model.enums.Frequency;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.SourceJob;
import process.model.pojo.JobQueue;
import process.model.pojo.Scheduler;
import process.model.service.impl.TransactionServiceImpl;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Component
public class BulkAction {

    public Logger logger = LogManager.getLogger(BulkAction.class);

    @Autowired
    private TransactionServiceImpl transactionService;

    /**
     * This method use the change the status of main job
     * @param jobId
     * @param jobStatus
     * */
    public void changeJobStatus(Long jobId, JobStatus jobStatus) {
        Optional<SourceJob> sourceJob = this.transactionService.findByJobIdAndJobStatus(jobId, Status.Active);
        sourceJob.get().setJobRunningStatus(jobStatus);
        this.transactionService.saveOrUpdateJob(sourceJob.get());
    }

    /**
     * This method use the change the status of sub job
     * @param jobQueueId
     * @param jobStatus
     * */
    public void changeJobQueueStatus(Long jobQueueId, JobStatus jobStatus) {
        Optional<JobQueue> jobQueue = this.transactionService.findJobQueueByJobQueueId(jobQueueId);
        jobQueue.get().setJobStatus(jobStatus);
        this.transactionService.saveOrUpdateJobQueue(jobQueue.get());
    }

    /**
     * This method use the add the end date of running job
     * @param jobQueueId
     * @param endTime
     * */
    public void changeJobQueueEndDate(Long jobQueueId, LocalDateTime endTime) {
        Optional<JobQueue> jobQueue = this.transactionService.findJobQueueByJobQueueId(jobQueueId);
        jobQueue.get().setEndTime(endTime);
        jobQueue.get().setJobStatusMessage(String.format("Job %s now complete.", jobQueue.get().getJobId()));
        this.transactionService.saveOrUpdateJobQueue(jobQueue.get());
    }

    /**
     * This method use to run the last job in the main job
     * @param jobId
     * @param lastJobRun
     * */
    public void changeJobLastJobRun(Long jobId, LocalDateTime lastJobRun) {
        Optional<SourceJob> sourceJob = this.transactionService.findByJobIdAndJobStatus(jobId, Status.Active);
        sourceJob.get().setLastJobRun(lastJobRun);
        this.transactionService.saveOrUpdateJob(sourceJob.get());
    }

    /**
     * This method use to add the job into the job-queue in the queue state
     * the schedule pick the job from the job-queue and push into the queue
     * @param jobId
     * @param scheduledTime
     * @return JobQueueDto
     * */
    public JobQueue createJobQueue(Long jobId, LocalDateTime scheduledTime) {
        JobQueue jobQueue = new JobQueue();
        jobQueue.setStartTime(scheduledTime);
        jobQueue.setJobStatus(JobStatus.Queue);
        jobQueue.setJobId(jobId);
        jobQueue.setJobStatusMessage(String.format("Job %s now in the queue.", jobId));
        this.transactionService.saveOrUpdateJobQueue(jobQueue);
        return jobQueue;
    }

    /**
     * this method use to add the current job logs into the audit logs table
     * @param jobQueueId
     * @param logsDetail
     * */
    public void saveJobAuditLogs(Long jobQueueId, String logsDetail) {
        this.transactionService.saveJobAuditLogs(jobQueueId, logsDetail);
    }

    /**
     * this method use to update the scheduler next running time
     * @param scheduler
     * */
    public void updateNextScheduler(Scheduler scheduler) {
        LocalDateTime nextJobRun = null;
        if (scheduler.getFrequency().equals(Frequency.Mint.name()) && !this.isNull(scheduler.getRecurrence())) {
            nextJobRun = scheduler.getRecurrenceTime().plusMinutes(Long.valueOf(scheduler.getRecurrence()));
        } else if (scheduler.getFrequency().equals(Frequency.Hr.name()) && !this.isNull(scheduler.getRecurrence())) {
            nextJobRun = scheduler.getRecurrenceTime().plusHours(Long.valueOf(scheduler.getRecurrence()));
        } else if (scheduler.getFrequency().equals(Frequency.Daily.name()) && !this.isNull(scheduler.getRecurrence())) {
            nextJobRun = scheduler.getRecurrenceTime().plusDays(Long.valueOf(scheduler.getRecurrence()));
        } else if (scheduler.getFrequency().equals(Frequency.Weekly.name()) && !this.isNull(scheduler.getRecurrence())) {
            nextJobRun = scheduler.getRecurrenceTime().plusWeeks(Long.valueOf(scheduler.getRecurrence()));
        } else if (scheduler.getFrequency().equals(Frequency.Monthly.name()) && !this.isNull(scheduler.getRecurrence())) {
            nextJobRun = scheduler.getRecurrenceTime().plusMonths(Long.valueOf(scheduler.getRecurrence()));
        }
        if (scheduler.getEndDate() != null) {
            LocalDateTime schedulerEndDateTime = scheduler.getEndDate().atTime(scheduler.getStartTime());
            if (nextJobRun != null && (schedulerEndDateTime.equals(nextJobRun) || schedulerEndDateTime.isAfter(nextJobRun))) {
                scheduler.setRecurrenceTime(nextJobRun);
                this.transactionService.saveOrUpdateScheduler(scheduler);
            } else {
                this.changeJobStatus(scheduler.getJobId(), JobStatus.PartialComplete);
            }
        } else if (nextJobRun != null) {
            scheduler.setRecurrenceTime(nextJobRun);
            this.transactionService.saveOrUpdateScheduler(scheduler);
        }
    }

    /**
     * This method use to change the partial-complete status into complete status
     * */
    public void checkJobStatus() {
        for (SourceJob sourceJob : this.transactionService.findJobByJobRunningStatus()) {
            this.changeJobStatus(sourceJob.getJobId(), JobStatus.Completed);
        }
    }

    private static boolean isNull(String filed) {
        return (filed == null || filed.length() == 0) ? true : false;
    }
}
package process.engine;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.SourceJob;
import process.model.pojo.JobQueue;
import process.model.pojo.Scheduler;
import process.model.projection.SourceJobProjection;
import process.model.service.impl.TransactionServiceImpl;
import process.socket.NotificationService;
import process.util.ProcessTimeUtil;
import process.util.ProcessUtil;
import java.time.LocalDateTime;
import java.util.*;

/**
 * @author Nabeel Ahmed
 */
@Component
@Transactional
public class BulkAction {

    public Logger logger = LogManager.getLogger(BulkAction.class);

    private final TransactionServiceImpl transactionService;
    private final NotificationService notificationService;

    public BulkAction(TransactionServiceImpl transactionService, NotificationService notificationService) {
        this.transactionService = transactionService;
        this.notificationService = notificationService;
    }

    /**
     * This method use the change the status of main job
     * @param jobId
     * @param jobStatus
     * */
    public void changeJobStatus(Long jobId, JobStatus jobStatus) {
        Optional<SourceJob> sourceJob = this.transactionService.findByJobId(jobId);
        if (!sourceJob.isPresent()) {
            // job was deleted/removed concurrently with this status update -- nothing left to update
            this.logger.warn("changeJobStatus: SourceJob not found with jobId {}, skipping.", jobId);
            return;
        }
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
        if (!jobQueue.isPresent()) {
            this.logger.warn("changeJobQueueStatus: JobQueue not found with jobQueueId {}, skipping.", jobQueueId);
            return;
        }
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
        if (!jobQueue.isPresent()) {
            this.logger.warn("changeJobQueueEndDate: JobQueue not found with jobQueueId {}, skipping.", jobQueueId);
            return;
        }
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
        if (!sourceJob.isPresent()) {
            this.logger.warn("changeJobLastJobRun: active SourceJob not found with jobId {}, skipping.", jobId);
            return;
        }
        sourceJob.get().setLastJobRun(lastJobRun);
        this.transactionService.saveOrUpdateJob(sourceJob.get());
    }

    /**
     * This method use to add the job into the job-queue in the queue state
     * the schedule pick the job from the job-queue and push into the queue
     * @param jobId
     * @param scheduledTime
     * @param jobStatus
     * @param message
     * @param isSkip
     * @return JobQueueDto
     * */
    public JobQueue createJobQueue(Long jobId, LocalDateTime scheduledTime,
        JobStatus jobStatus, String message, Boolean isSkip) {
        JobQueue jobQueue = new JobQueue();
        if (isSkip) {
            jobQueue.setSkipTime(scheduledTime);
        } else {
            jobQueue.setStartTime(scheduledTime);
        }
        jobQueue.setJobStatus(jobStatus);
        jobQueue.setJobId(jobId);
        jobQueue.setJobStatusMessage(String.format(message, jobId));
        this.transactionService.saveOrUpdateJobQueue(jobQueue);
        return jobQueue;
    }

    /**
     * This method use to add the job into the job-queue in the queue state
     * the schedule pick the job from the job-queue and push into the queue
     * @param jobId
     * @param scheduledTime
     * @param jobStatus
     * @param message
     * @param isSkip
     * @return JobQueueDto
     * */
    public JobQueue createJobQueueV1(Long jobId, LocalDateTime scheduledTime,
        JobStatus jobStatus, String message, Boolean isSkip) {
        JobQueue jobQueue = new JobQueue();
        if (isSkip) {
            jobQueue.setSkipManual(true);
            jobQueue.setSkipTime(scheduledTime);
        } else {
            jobQueue.setRunManual(true);
            jobQueue.setStartTime(scheduledTime);
        }
        jobQueue.setJobStatus(jobStatus);
        jobQueue.setJobId(jobId);
        jobQueue.setJobStatusMessage(String.format(message, jobId));
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
     * this method use to get the count of job which is inQueue
     * @param jobId
     * */
    public Integer getCountForInQueueJobByJobId(Long jobId) {
        return this.transactionService.getCountForInQueueJobByJobId(jobId);
    }

    /**
     * this method use to update the scheduler next running time
     * @param scheduler
     * */
    public void updateNextScheduler(Scheduler scheduler) {
        LocalDateTime nextJobRun = ProcessTimeUtil.computeNextRun(scheduler);
        if (scheduler.getEndDate() != null) {
            LocalDateTime schedulerEndDateTime = scheduler.getEndDate().atTime(scheduler.getStartTime());
            if (nextJobRun != null && (schedulerEndDateTime.equals(nextJobRun) || schedulerEndDateTime.isAfter(nextJobRun))) {
                scheduler.setRecurrenceTime(nextJobRun);
                this.transactionService.saveOrUpdateScheduler(scheduler);
                return;
            }
            logger.info("No More Nex Job for jobId :- {}.", scheduler.getJobId());
        } else if (nextJobRun != null) {
            scheduler.setRecurrenceTime(nextJobRun);
            this.transactionService.saveOrUpdateScheduler(scheduler);
        }
    }

    /**
     * This method use the change the status of main job
     * @param jobId
     * */
    public void sendJobStatusNotification(Long jobId) {
        List<SourceJobProjection> sourceJob = this.transactionService.fetchRunningJobEvent(Arrays.asList(jobId));
        if (!sourceJob.isEmpty()) {
            String assignedUsername = sourceJob.get(0).getAssignedUsername();
            // No assignee (job predates assignedUserId, or its assignee was deleted) -- nobody
            // to target, so there's nothing to push. Not an error: the run is still visible via
            // manual refresh/history either way.
            if (assignedUsername != null) {
                this.notificationService.sendNotificationToSpecificUser(assignedUsername, this.getSourceJobDetail(sourceJob.get(0)));
            }
        }
    }

    /**
     * Method use to get the source job detail
     * @param sourceJobProjection
     * @return String
     * */
    private String getSourceJobDetail(SourceJobProjection sourceJobProjection) {
        HashMap<String, Object> jsonObject = new HashMap<>();
        jsonObject.put("jobId", sourceJobProjection.getJobId());
        jsonObject.put("jobStatus", sourceJobProjection.getJobStatus());
        jsonObject.put("jobRunningStatus", sourceJobProjection.getJobRunningStatus());
        if (!ProcessUtil.isNull(sourceJobProjection.getLastJobRun())) {
            jsonObject.put("lastJobRun", sourceJobProjection.getLastJobRun().toString());
        }
        if (!ProcessUtil.isNull(sourceJobProjection.getRecurrenceTime())) {
            jsonObject.put("recurrenceTime", sourceJobProjection.getRecurrenceTime().toString());
        }
        jsonObject.put("execution", sourceJobProjection.getExecution());
        return new Gson().toJson(jsonObject);
    }
}
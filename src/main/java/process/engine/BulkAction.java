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

    public void changeJobStatus(Long jobId, JobStatus jobStatus) {
        Optional<SourceJob> sourceJob = this.transactionService.findByJobId(jobId);
        if (!sourceJob.isPresent()) {

            this.logger.warn("changeJobStatus: SourceJob not found with jobId {}, skipping.", jobId);
            return;
        }
        sourceJob.get().setJobRunningStatus(jobStatus);
        this.transactionService.saveOrUpdateJob(sourceJob.get());
    }

    public void changeJobQueueStatus(Long jobQueueId, JobStatus jobStatus) {
        Optional<JobQueue> jobQueue = this.transactionService.findJobQueueByJobQueueId(jobQueueId);
        if (!jobQueue.isPresent()) {
            this.logger.warn("changeJobQueueStatus: JobQueue not found with jobQueueId {}, skipping.", jobQueueId);
            return;
        }
        jobQueue.get().setJobStatus(jobStatus);
        this.transactionService.saveOrUpdateJobQueue(jobQueue.get());
    }

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

    public void changeJobLastJobRun(Long jobId, LocalDateTime lastJobRun) {
        Optional<SourceJob> sourceJob = this.transactionService.findByJobIdAndJobStatus(jobId, Status.Active);
        if (!sourceJob.isPresent()) {
            this.logger.warn("changeJobLastJobRun: active SourceJob not found with jobId {}, skipping.", jobId);
            return;
        }
        sourceJob.get().setLastJobRun(lastJobRun);
        this.transactionService.saveOrUpdateJob(sourceJob.get());
    }

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

    public void saveJobAuditLogs(Long jobQueueId, String logsDetail) {
        this.transactionService.saveJobAuditLogs(jobQueueId, logsDetail);
    }

    public Integer getCountForInQueueJobByJobId(Long jobId) {
        return this.transactionService.getCountForInQueueJobByJobId(jobId);
    }

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

    public void sendJobStatusNotification(Long jobId) {
        List<SourceJobProjection> sourceJob = this.transactionService.fetchRunningJobEvent(Arrays.asList(jobId));
        if (!sourceJob.isEmpty()) {
            String assignedUsername = sourceJob.get(0).getAssignedUsername();

            if (assignedUsername != null) {
                this.notificationService.sendNotificationToSpecificUser(assignedUsername, this.getSourceJobDetail(sourceJob.get(0)));
            }
        }
    }

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
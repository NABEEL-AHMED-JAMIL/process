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
import process.model.service.NotificationCenterService;
import process.model.service.impl.TransactionServiceImpl;
import process.model.enums.NotificationSeverity;
import process.model.enums.NotificationType;
import process.socket.NotificationService;
import process.util.ProcessTimeUtil;
import process.util.ProcessUtil;
import java.time.LocalDateTime;
import java.util.*;

/**
 * @author Nabeel Ahmed
 * */
@Component
@Transactional
public class BulkAction {

    public Logger logger = LogManager.getLogger(BulkAction.class);

    private final TransactionServiceImpl transactionService;
    private final NotificationService notificationService;
    private final NotificationCenterService notificationCenterService;

    public BulkAction(TransactionServiceImpl transactionService, NotificationService notificationService,
        NotificationCenterService notificationCenterService) {
        this.transactionService = transactionService;
        this.notificationService = notificationService;
        this.notificationCenterService = notificationCenterService;
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
        this.changeJobQueueStatus(jobQueueId, jobStatus, null);
    }

    public void changeJobQueueStatus(Long jobQueueId, JobStatus jobStatus, String message) {
        Optional<JobQueue> jobQueue = this.transactionService.findJobQueueByJobQueueId(jobQueueId);
        if (!jobQueue.isPresent()) {
            this.logger.warn("changeJobQueueStatus: JobQueue not found with jobQueueId {}, skipping.", jobQueueId);
            return;
        }
        jobQueue.get().setJobStatus(jobStatus);
        if (!ProcessUtil.isNull(message)) {
            jobQueue.get().setJobStatusMessage(message);
        }
        this.transactionService.saveOrUpdateJobQueue(jobQueue.get());
    }

    public void changeJobQueueEndDate(Long jobQueueId, LocalDateTime endTime) {
        Optional<JobQueue> jobQueue = this.transactionService.findJobQueueByJobQueueId(jobQueueId);
        if (!jobQueue.isPresent()) {
            this.logger.warn("changeJobQueueEndDate: JobQueue not found with jobQueueId {}, skipping.", jobQueueId);
            return;
        }
        jobQueue.get().setEndTime(endTime);
        if (ProcessUtil.isNull(jobQueue.get().getJobStatusMessage())) {
            // The fallback has to follow the status. Assuming completion produced runs reading
            // "Failed -- Job 1196 now complete.", which is not merely unhelpful but actively
            // contradicts itself: the reason the run failed was never recorded, and the
            // placeholder then claimed it had succeeded.
            jobQueue.get().setJobStatusMessage(fallbackMessage(jobQueue.get()));
        }
        this.transactionService.saveOrUpdateJobQueue(jobQueue.get());
    }

    /**
     * What to say about a run that ended without saying anything.
     *
     * A missing message is itself information -- the worker stopped without reporting -- so
     * the text says that rather than inventing an outcome.
     */
    private static String fallbackMessage(JobQueue jobQueue) {
        Long jobId = jobQueue.getJobId();
        JobStatus status = jobQueue.getJobStatus();
        if (status == null) {
            return String.format("Job %s ended without reporting a status.", jobId);
        }
        switch (status) {
            case Completed:
                return String.format("Job %s now complete.", jobId);
            case Failed:
                return String.format("Job %s failed without reporting a reason. Check its logs.", jobId);
            case Interrupt:
                return String.format("Job %s was interrupted before it finished.", jobId);
            case Skip:
                return String.format("Job %s was skipped.", jobId);
            default:
                return String.format("Job %s ended while marked %s.", jobId, status);
        }
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
        this.applyBucketSnapshot(jobQueue, jobId);
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
        this.applyBucketSnapshot(jobQueue, jobId);
        this.transactionService.saveOrUpdateJobQueue(jobQueue);
        return jobQueue;
    }

    private void applyBucketSnapshot(JobQueue jobQueue, Long jobId) {
        this.transactionService.findByJobId(jobId).ifPresent(sourceJob -> {
            if (sourceJob.getTaskDetail() != null) {
                jobQueue.setBucket(sourceJob.getTaskDetail().getBucket());
                jobQueue.setOutputFolder(sourceJob.getTaskDetail().getOutputFolder());
            }
        });
    }

    public void saveJobAuditLogs(Long jobQueueId, String logsDetail) {
        this.transactionService.saveJobAuditLogs(jobQueueId, logsDetail);
    }

    /** Many lines at once, for a worker that buffers rather than posting per line. */
    public void saveJobAuditLogs(Long jobQueueId, java.util.List<String> logDetails) {
        this.transactionService.saveJobAuditLogs(jobQueueId, logDetails);
    }

    public Integer getCountForInQueueJobByJobId(Long jobId) {
        return this.transactionService.getCountForInQueueJobByJobId(jobId);
    }

    public void updateNextScheduler(Scheduler scheduler) {
        List<LocalDateTime> missedRuns = ProcessTimeUtil.computeMissedRuns(scheduler);
        ProcessTimeUtil.applyNextRun(scheduler);
        this.transactionService.saveOrUpdateScheduler(scheduler);
        if (scheduler.isExpired()) {
            logger.info("No more next job for jobId: {} -- schedule has expired.", scheduler.getJobId());
        }
        for (LocalDateTime missedAt : missedRuns) {
            this.recordMissedRun(scheduler.getJobId(), missedAt);
        }
    }

    private void recordMissedRun(Long jobId, LocalDateTime missedAt) {
        String template = "Job %s missed its scheduled run at " + missedAt + " -- the system was catching up after downtime.";
        JobQueue jobQueue = this.createJobQueue(jobId, missedAt, JobStatus.Missed, template, true);
        this.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format(template, jobId));
        this.sendJobStatusNotification(jobId);
        logger.warn("Job {} missed its scheduled run at {}.", jobId, missedAt);
    }

    public void sendJobStatusNotification(Long jobId) {
        this.sendJobStatusNotification(jobId, true);
    }

    public void sendJobStatusNotification(Long jobId, boolean isNewTransition) {
        List<SourceJobProjection> sourceJob = this.transactionService.fetchRunningJobEvent(Arrays.asList(jobId));
        if (!sourceJob.isEmpty()) {
            SourceJobProjection jobEvent = sourceJob.get(0);
            String assignedUsername = jobEvent.getAssignedUsername();

            if (assignedUsername != null) {
                this.notificationService.sendNotificationToSpecificUser(assignedUsername, this.getSourceJobDetail(jobEvent));
            }
            if (isNewTransition) {
                this.notifyJobOutcome(jobEvent);
            }
        }
    }

    private void notifyJobOutcome(SourceJobProjection jobEvent) {
        JobStatus runningStatus = jobEvent.getJobRunningStatus();
        if (runningStatus != JobStatus.Completed && runningStatus != JobStatus.Failed) {
            return;
        }
        String jobName = jobEvent.getJobName() != null ? jobEvent.getJobName() : ("Job " + jobEvent.getJobId());
        if (runningStatus == JobStatus.Completed) {
            this.notificationCenterService.create(jobEvent.getTenantId(), jobEvent.getAssignedUserId(),
                NotificationType.JOB_COMPLETED, NotificationSeverity.SUCCESS,
                "Job completed", jobName + " finished successfully.", "/jobList");
        } else {
            this.notificationCenterService.create(jobEvent.getTenantId(), jobEvent.getAssignedUserId(),
                NotificationType.JOB_FAILED, NotificationSeverity.ERROR,
                "Job failed", jobName + " failed.", "/jobList");
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
        if (!ProcessUtil.isNull(sourceJobProjection.getNextRunAt())) {
            jsonObject.put("nextRunAt", sourceJobProjection.getNextRunAt().toString());
        }
        jsonObject.put("execution", sourceJobProjection.getExecution());
        return new Gson().toJson(jsonObject);
    }
}

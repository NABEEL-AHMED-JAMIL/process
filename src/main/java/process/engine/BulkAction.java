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
import process.socket.JobEventPublisher;
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
    private final JobEventPublisher jobEventPublisher;

    public BulkAction(TransactionServiceImpl transactionService, NotificationService notificationService,
        NotificationCenterService notificationCenterService, JobEventPublisher jobEventPublisher) {
        this.transactionService = transactionService;
        this.notificationService = notificationService;
        this.notificationCenterService = notificationCenterService;
        this.jobEventPublisher = jobEventPublisher;
    }

    public void changeJobStatus(Long jobId, JobStatus jobStatus) {
        Optional<SourceJob> sourceJob = this.transactionService.findByJobId(jobId);
        if (!sourceJob.isPresent()) {

            this.logger.warn("changeJobStatus: SourceJob not found with jobId {}, skipping.", jobId);
            return;
        }
        sourceJob.get().setJobRunningStatus(jobStatus);
        this.transactionService.saveOrUpdateJob(sourceJob.get());
        // Announced here rather than at each caller. Every transition the platform makes --
        // Queue when a job is enqueued, Start when the engine picks it up, Interrupt, and the
        // engine's own Failed -- passes through this one method, while only the external
        // worker callback announced itself, through NotifyService. Publishing at the callers
        // meant nine sites of which eight were silent, so a job sat at its old status until
        // someone pressed Refresh; publishing here means the next caller added cannot forget.
        //
        // Unconditional, including a repeat of the status already held. Start -> Start and
        // Running -> Running are legal transitions (see NotifyServiceImpl.isValidStatusTransition)
        // because that is how a worker says it is still alive, and the jobs table advances its
        // lastJobRun on each one. Suppressing repeats as "not a change" would therefore switch
        // off the heartbeat and let a healthy long run be reported as stalled.
        //
        // After commit, because this class is @Transactional and the engine calls it inside
        // longer units of work: announcing as the row is written announces it before it is
        // durable, and a rollback then leaves every open jobs table showing a transition the
        // database does not have.
        if (jobStatus != null) {
            this.jobEventPublisher.publishStatusAfterCommit(sourceJob.get().getTenantId(),
                jobId, null, jobStatus.name(), null);
        }
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

    /**
     * For a caller that already holds the queue row -- it loaded it, or it just created it, so
     * the run it is writing against is not in question. A caller that was given the job and the
     * run as two separate values has to use the overload that takes both.
     */
    /**
     * The one ceiling on a computed backoff, in seconds.
     *
     * The wait doubles per attempt, and the column allows ten attempts on an hour's base, so the
     * ninth doubling of 3600 is twenty-one days. Nobody configuring "retry up to ten times, an
     * hour apart" is asking for a run that sits in the queue until October, and because a queued
     * run occupies its job, such a row would take that job off its own schedule for the duration.
     * Capping the interval keeps the attempt count meaning what it says.
     */
    private static final long MAX_BACKOFF_SECONDS = 60 * 60;

    /**
     * Puts a failed run back in the queue to be attempted again, or reports that it is finished.
     *
     * <b>The return value decides whether the caller announces a failure.</b> True means this run
     * is going round again and nothing has failed yet as far as anyone outside is concerned -- no
     * Failed status, no failure email. False means the run is genuinely over and the caller should
     * close it exactly as it did before this method existed. Callers that ignore the result send a
     * failure mail per attempt, which is worse than the problem retry set out to solve.
     *
     * Only transient failures should reach here. A run whose job has been deleted, or which a
     * person deliberately failed from the console, will not succeed by being tried again, and
     * retrying it just delays the news by the length of the backoff.
     *
     * The row is re-used rather than replaced, so the retry continues to occupy the single
     * in-flight slot its job is allowed -- two attempts of one job running at once would have two
     * workers writing the same output folder. The consequence worth knowing is that a job whose
     * backoff outlasts its own interval will skip its next slot, and that is the intended
     * ordering: finish the slot you are on before starting the next.
     */
    public boolean scheduleRetry(JobQueue jobQueue, String reason) {
        if (ProcessUtil.isNull(jobQueue) || ProcessUtil.isNull(jobQueue.getJobId())) {
            return false;
        }
        boolean retried = this.scheduleRetry(jobQueue.getJobQueueId(), jobQueue.getJobId(), reason);
        if (retried) {
            // Keep the caller's copy in step with what was just written. It is what a failure
            // email would be built from if the caller went on to send one, and a stale copy there
            // reports the run as Failed moments after this method put it back in the queue.
            Optional<JobQueue> written = this.transactionService.findJobQueueByJobQueueId(jobQueue.getJobQueueId());
            if (written.isPresent()) {
                jobQueue.setAttempt(written.get().getAttempt());
                jobQueue.setNextAttemptAt(written.get().getNextAttemptAt());
                jobQueue.setJobStatus(written.get().getJobStatus());
                jobQueue.setJobSend(written.get().isJobSend());
                jobQueue.setEndTime(written.get().getEndTime());
                jobQueue.setJobStatusMessage(written.get().getJobStatusMessage());
            }
        }
        return retried;
    }

    /**
     * As above, for a caller holding only the run's identity rather than the entity.
     *
     * The live worker callback is one of these: it arrives as a DTO off the wire, and loading the
     * entity purely to pass it in would be work this method immediately repeats.
     */
    public boolean scheduleRetry(Long jobQueueId, Long jobId, String reason) {
        if (ProcessUtil.isNull(jobQueueId) || ProcessUtil.isNull(jobId)) {
            return false;
        }
        Optional<SourceJob> sourceJob = this.transactionService.findByJobId(jobId);
        if (!sourceJob.isPresent()) {
            // Nothing to read a retry policy from, and a run whose job is gone is not coming back.
            return false;
        }
        // Read by id rather than taking an entity from the caller, which is what makes the attempt
        // count trustworthy. The Kafka path reaches retry from a send callback fired long after
        // its entity was loaded, so the copy it holds is detached and may be several attempts
        // behind -- and a stale count read as the current one retries a run that has already
        // exhausted its attempts, for ever. Every other writer in this class re-reads for the
        // same reason.
        Optional<JobQueue> current = this.transactionService.findJobQueueByJobQueueId(jobQueueId);
        if (!current.isPresent()) {
            this.logger.warn("scheduleRetry: JobQueue not found with jobQueueId {}, not retrying.", jobQueueId);
            return false;
        }
        JobQueue row = current.get();
        int maxAttempts = ProcessUtil.isNull(sourceJob.get().getMaxAttempts())
            ? 1 : sourceJob.get().getMaxAttempts();
        // A row written before this column existed reads 0 through a projection or a hand-edited
        // database; treat anything below 1 as the first attempt rather than as "already past the
        // limit", which would disable retry on exactly the rows most likely to be odd.
        int attempt = Math.max(1, row.getAttempt());
        if (attempt >= maxAttempts) {
            return false;
        }
        int nextAttempt = attempt + 1;
        long base = ProcessUtil.isNull(sourceJob.get().getRetryBackoffSeconds())
            ? 60L : sourceJob.get().getRetryBackoffSeconds().longValue();
        // Shift rather than Math.pow, and bounded before it is applied: attempt is already capped
        // at ten by the column's constraint, but the arithmetic should not depend on a constraint
        // in another table to avoid overflowing.
        long multiplier = 1L << Math.min(attempt - 1, 20);
        long backoffSeconds = Math.min(base * multiplier, MAX_BACKOFF_SECONDS);
        LocalDateTime dueAt = LocalDateTime.now().plusSeconds(backoffSeconds);

        row.setAttempt(nextAttempt);
        row.setNextAttemptAt(dueAt);
        row.setJobStatus(JobStatus.Queue);
        // Both of these are what makes the row eligible again: the dispatcher's pick-up query
        // takes Queue rows with job_send false, and this row has had it set true if it ever
        // reached the broker. Leaving it set means the retry is written down and then never
        // dispatched -- a run that waits for ever, which reads as a hang rather than a failure.
        row.setJobSend(false);
        // The run has not ended. An end time left over from the failed attempt makes its duration
        // read as negative once the retry finally completes.
        row.setEndTime(null);
        row.setJobStatusMessage(String.format(
            "Attempt %s of %s failed: %s Retrying at %s.", attempt, maxAttempts, endWithStop(reason), dueAt));
        this.transactionService.saveOrUpdateJobQueue(row);
        this.changeJobStatus(jobId, JobStatus.Queue);
        this.saveJobAuditLogs(jobQueueId, String.format(
            "Attempt %s of %s failed: %s Queued for attempt %s at %s.",
            attempt, maxAttempts, endWithStop(reason), nextAttempt, dueAt));
        this.sendJobStatusNotification(jobId);
        this.logger.warn("scheduleRetry --> job {} run {} attempt {} of {} failed; retrying at {}.",
            jobId, jobQueueId, attempt, maxAttempts, dueAt);
        return true;
    }

    /**
     * The reason as a sentence, so the text built around it does not read "failed: timeout Retrying".
     */
    private static String endWithStop(String reason) {
        if (ProcessUtil.isNull(reason)) {
            return "no reason recorded.";
        }
        String trimmed = reason.trim();
        return trimmed.endsWith(".") || trimmed.endsWith("!") || trimmed.endsWith("?")
            ? trimmed : trimmed + ".";
    }

    public void saveJobAuditLogs(Long jobQueueId, String logsDetail) {
        this.transactionService.saveJobAuditLogs(jobQueueId, logsDetail);
    }

    /** Many lines at once, for a worker that buffers rather than posting per line. */
    public void saveJobAuditLogs(Long jobQueueId, List<String> logDetails) {
        this.transactionService.saveJobAuditLogs(jobQueueId, logDetails);
    }

    /**
     * For a caller holding a job id and a queue id that arrived independently -- a worker
     * callback quotes both, and neither one proves anything about the other. The write refuses
     * unless the queue row says it belongs to that job, so a caller naming a job it is entitled
     * to cannot append to another tenant's run by quoting that run's queue id.
     */
    public void saveJobAuditLogs(Long jobId, Long jobQueueId, String logsDetail) {
        this.transactionService.saveJobAuditLogs(jobId, jobQueueId, logsDetail);
    }

    /** The batched form of the checked write. */
    public void saveJobAuditLogs(Long jobId, Long jobQueueId, List<String> logDetails) {
        this.transactionService.saveJobAuditLogs(jobId, jobQueueId, logDetails);
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

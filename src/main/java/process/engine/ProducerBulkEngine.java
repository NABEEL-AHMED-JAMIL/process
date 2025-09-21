package process.engine;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;
import process.emailer.EmailMessagesFactory;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.*;
import process.model.service.impl.LookupDataCacheService;
import process.model.service.impl.TransactionServiceImpl;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static java.util.Objects.isNull;

/**
 * @author Nabeel Ahmed
 */
@Component
public class ProducerBulkEngine {

    public Logger logger = LogManager.getLogger(ProducerBulkEngine.class);

    private final Pattern pattern;
    @Autowired
    private BulkAction bulkAction;
    @Autowired
    private TransactionServiceImpl transactionService;
    @Autowired
    private EmailMessagesFactory emailMessagesFactory;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private LookupDataCacheService lookupDataCacheService;

    public ProducerBulkEngine() {
        this.pattern = Pattern.compile("^topic=([a-zA-Z-]*)&partitions=\\[([0-9*])\\]$");
    }

    /**
     * Method use to send the job int the queue by user action
     * @param sourceJob
     * */
    public void addManualJobInQueue(SourceJob sourceJob) {
        this.bulkAction.changeJobStatus(sourceJob.getJobId(), JobStatus.Queue);
        JobQueue jobQueue = this.bulkAction.createJobQueueV1(sourceJob.getJobId(), LocalDateTime.now(), JobStatus.Queue, "Job %s now in the queue.", false);
        this.bulkAction.changeJobLastJobRun(sourceJob.getJobId(), jobQueue.getStartTime());
        this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s now in the queue.", sourceJob.getJobId()));
        this.bulkAction.sendJobStatusNotification(sourceJob.getJobId().intValue());
    }

    /**
     * Method use to send the job int the queue as skip by user action
     * @param scheduler
     * */
    public void skipManualJobInQueue(Scheduler scheduler) {
        // if the job in the skip state no need update the last run queue
        JobQueue jobQueue = this.bulkAction.createJobQueueV1(scheduler.getJobId(), scheduler.getRecurrenceTime(), JobStatus.Skip, "Job %s skip, by user action.", true);
        this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s skip, by user action.", scheduler.getJobId()));
        // if the user fail the job manual need to send the mail
        this.bulkAction.sendJobStatusNotification(jobQueue.getJobId().intValue());
        if (this.transactionService.findByJobId(jobQueue.getJobId()).get().isSkipJob()) {
            this.emailMessagesFactory.sendSourceJobEmail(EmailMessagesFactory.getSourceJobQueueDto(jobQueue),JobStatus.Skip);
        }
    }

    /**
     * This method use to fetch the detail from the scheduler and match the current running slot
     * if the data match then push the source-job into the job-queue with the 'queue status' and put the logs too for the job-queue
     * this method also update the next running detail if the next running date exist this will update into the
     * scheduler table.
     * */
    public void addJobInQueue() {
        try {
            logger.info("addJobInQueue --> FETCH Scheduler of current day STARTED ");
            LocalDateTime currentSchedulerTime = LocalDateTime.now();
            LookupData lookupData = this.transactionService.findByLookupType(ProcessUtil.SCHEDULER_LAST_RUN_TIME);
            LocalDateTime lastSchedulerRun = LocalDateTime.parse(lookupData.getLookupValue());
            // only the active job will move to the scheduler
            List<Scheduler> schedulerForToday = this.transactionService.findAllSchedulerForTodayV2(lastSchedulerRun, currentSchedulerTime);
            logger.info("addJobInQueue --> FETCHED Scheduler of current day: size {} ", schedulerForToday.size());
            lookupData.setLookupValue(currentSchedulerTime.toString());
            this.transactionService.updateLookupDate(lookupData);
            if (!schedulerForToday.isEmpty()) {
                schedulerForToday.parallelStream().forEach(scheduler -> {
                    if (this.isScheduled(lastSchedulerRun, currentSchedulerTime, scheduler.getJobId(), scheduler.getRecurrenceTime())) {
                        // we have to check if job in the queue then send the detail of job as skip with message
                        JobQueue jobQueue;
                        if (this.bulkAction.getCountForInQueueJobByJobId(scheduler.getJobId()) > 0) {
                            // if the job in the skip state no need update the last run queue
                            jobQueue = this.bulkAction.createJobQueue(scheduler.getJobId(), LocalDateTime.now(), JobStatus.Skip, "Job %s skip, already in queue.", true);
                            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s skip, already in queue.", scheduler.getJobId()));
                            if (this.transactionService.findByJobId(scheduler.getJobId()).get().isSkipJob()) {
                                this.emailMessagesFactory.sendSourceJobEmail(EmailMessagesFactory.getSourceJobQueueDto(jobQueue),JobStatus.Skip);
                            }
                        } else {
                            this.bulkAction.changeJobStatus(scheduler.getJobId(), JobStatus.Queue);
                            jobQueue = this.bulkAction.createJobQueue(scheduler.getJobId(), LocalDateTime.now(), JobStatus.Queue, "Job %s now in the queue.", false);
                            this.bulkAction.changeJobLastJobRun(scheduler.getJobId(), jobQueue.getStartTime());
                            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s now in the queue.", scheduler.getJobId()));
                        }
                        // update the next run in scheduler
                        this.bulkAction.updateNextScheduler(scheduler);
                        this.bulkAction.sendJobStatusNotification(scheduler.getJobId().intValue());
                    }
                });
                return;
            }
            logger.info("addJobInQueue --> NO scheduler is set for this timestamp");
        } catch (Exception ex) {
            logger.error("Error In addJobInQueue :- {}.", ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    /**
     * This method fetch the job from job-queue and put into the thread-pool
     * the thread pool send the detail to worker thread
     * */
    public void runJobInCurrentTimeSlot() {
        try {
            logger.info("runJobInCurrentTimeSlot --> FETCH JobQueue of current day STARTED ");
            LookupData lookupData = this.transactionService.findByLookupType(ProcessUtil.QUEUE_FETCH_LIMIT);
            List<JobQueue> jobQueues = this.transactionService.findAllJobForTodayWithLimit(Long.valueOf(lookupData.getLookupValue()));
            logger.info("runJobInCurrentTimeSlot --> FETCHED JobQueue of current day: size {} ", jobQueues.size());
            if (!jobQueues.isEmpty()) {
                jobQueues.parallelStream().forEach(jobQueue -> {
                    Optional<SourceJob> sourceJob = this.transactionService.findByJobIdAndJobStatus(jobQueue.getJobId(), Status.Active);
                    try {
                        if (sourceJob.isPresent()) {
                            this.pushMessageToQueue(sourceJob.get(), jobQueue);
                        } else {
                            this.changeStatusForLastJob(jobQueue, "Job %s fail in the queue due to main job either (delete|inactive).");
                        }
                    } catch (Exception ex) {
                        logger.error("Error In runJobInCurrentTimeSlot :- {}.", ExceptionUtil.getRootCauseMessage(ex));
                    }
                });
                return;
            }
            logger.info("runJobInCurrentTimeSlot --> NO scheduler is set for this timestamp");
        } catch (Exception ex) {
            logger.error("Error In runJobInCurrentTimeSlot :- {}.", ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    /**
     * Method use to push the data into the kafka queue per topic configuration
     * @param sourceJob
     * @param jobQueue
     * @exception Exception
     * */
    private void pushMessageToQueue(SourceJob sourceJob, JobQueue jobQueue) throws Exception {
        SourceTask sourceTask = sourceJob.getTaskDetail();
        if (!isNull(sourceTask.getSourceTaskType())) {
            try {
                SourceTaskType sourceTaskType = sourceTask.getSourceTaskType();
                if (sourceTaskType.getStatus().equals(Status.Active)) {
                    String queueTopicPartition = sourceTaskType.getQueueTopicPartition();
                    Matcher matcher = this.pattern.matcher(queueTopicPartition);
                    boolean resultRegex = matcher.matches();
                    if (resultRegex) {
                        String topic = matcher.group(1);
                        String partition = matcher.group(2);
                        // random key for send the msg for partitions
                        String key = UUID.randomUUID().toString();
                        Map<String, String> payload = fillPayloadDetail(sourceJob, jobQueue);
                        ListenableFuture<SendResult<String, String>> future = null;
                        if (partition.contains(ProcessUtil.START)) {
                            future = this.kafkaTemplate.send(topic, key, payload.toString());
                        } else {
                            future = this.kafkaTemplate.send(topic, Integer.valueOf(partition), key, payload.toString());
                        }
                        future.addCallback(new ListenableFutureCallback<SendResult<String, String>>() {
                            @Override
                            public void onSuccess(SendResult<String, String> result) {
                                logger.info("Sent message=[{}] with offset=[{}]", payload, result.getRecordMetadata().offset());
                                jobQueue.setJobSend(true);
                                jobQueue.setJobStatusMessage("Sent message=[" + payload + "] with offset=[" + result.getRecordMetadata().offset() + "]");
                                jobQueue.setJobStatus(JobStatus.Start);
                                transactionService.updateJobQueue(jobQueue);
                                bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s send message=[%s] with offset=[%s]", sourceJob.getJobId(), payload, result.getRecordMetadata().offset()));
                            }
                            @Override
                            public void onFailure(Throwable ex) {
                                logger.info("Unable to send message=[{}] due to : {}", payload, ex.getMessage());
                                jobQueue.setJobSend(false);
                                jobQueue.setJobStatusMessage("Unable to send message=[" + payload + "] due to : " + ex.getMessage());
                                transactionService.updateJobQueue(jobQueue);
                                changeStatusForLastJob(jobQueue, String.format("Job %s unable to send message=[%s] due to : %s", sourceJob.getJobId(), payload, ex.getMessage()));
                            }
                        });
                        return;
                    }
                    logger.error("Regex Not match..");
                    this.changeStatusForLastJob(jobQueue, "Broker configuration wrong job %s fail " + queueTopicPartition);
                    return;
                }
                this.changeStatusForLastJob(jobQueue, "Broker not active job %s fail.");
            } catch (Exception ex) {
                this.changeStatusForLastJob(jobQueue, "Broker not active job %s fail.");
                logger.error("Error In pushMessageToQueue :- {}.", ExceptionUtil.getRootCauseMessage(ex));
            }
        }
    }

    /***
     * This method check either the job is eligible to put into the queue or not
     * @param lastSchedulerTime -> system scheduler run date
     * @param currentSchedulerTime -> existing time of the application
     * @param scheduledTime -> target time for run the scheduler
     * @return boolean true|false
     */
    private boolean isScheduled(LocalDateTime lastSchedulerTime, LocalDateTime currentSchedulerTime,
        Long jobId, LocalDateTime scheduledTime) {
        LocalDateTime target = LocalDateTime.of(currentSchedulerTime.toLocalDate(), scheduledTime.toLocalTime());
        boolean isExist=target.isBefore(currentSchedulerTime) && target.isAfter(lastSchedulerTime);
        if(isExist) {
            logger.info("Scheduler -- jobId: " + jobId + " currentTime: " + currentSchedulerTime +
            " lastSchedulerTime: " + lastSchedulerTime + " scheduledTime: " + scheduledTime);
        }
        return isExist;
    }

    /**
     * Method use to set the status of the job into db
     * @param jobQueue
     * @param message
     * @return void
     * */
    private void changeStatusForLastJob(JobQueue jobQueue, String message) {
        this.bulkAction.changeJobStatus(jobQueue.getJobId(), JobStatus.Failed);
        this.bulkAction.changeJobQueueStatus(jobQueue.getJobQueueId(), JobStatus.Failed);
        this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format(message, jobQueue.getJobId()));
        this.bulkAction.changeJobQueueEndDate(jobQueue.getJobQueueId(), LocalDateTime.now());
        this.bulkAction.sendJobStatusNotification(jobQueue.getJobId().intValue());
        if (this.transactionService.findByJobId(jobQueue.getJobId()).get().isFailJob()) {
            this.emailMessagesFactory.sendSourceJobEmail(EmailMessagesFactory.getSourceJobQueueDto(jobQueue),JobStatus.Failed);
        }
    }

    /**
     * Method use to fill the payload detail
     * */
    private Map<String, String> fillPayloadDetail(SourceJob sourceJob, JobQueue jobQueue) {
        Map<String, String> payload = new HashMap<>();
        payload.put(ProcessUtil.JOB_QUEUE, jobQueue.toString());
        payload.put(ProcessUtil.TASK_DETAIL, sourceJob.getTaskDetail().toString());
        payload.put(ProcessUtil.PRIORITY, sourceJob.getPriority().toString());
        return payload;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
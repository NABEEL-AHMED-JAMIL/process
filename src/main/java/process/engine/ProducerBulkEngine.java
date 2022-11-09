package process.engine;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import process.emailer.EmailMessagesFactory;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.*;
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
    private final String REGEX = "^topic=([a-zA-Z-]*)&partitions=\\[([0-9*])\\]$";
    @Autowired
    private BulkAction bulkAction;
    @Autowired
    private TransactionServiceImpl transactionService;
    @Autowired
    private EmailMessagesFactory emailMessagesFactory;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    public ProducerBulkEngine() {
        this.pattern = Pattern.compile(REGEX);
    }

    /**
     * Method use to send the job int the queue by user action
     * @param sourceJob
     * */
    public void addManualJobInQueue(SourceJob sourceJob) {
        this.bulkAction.changeJobStatus(sourceJob.getJobId(), JobStatus.Queue);
        JobQueue jobQueue = this.bulkAction.createJobQueue(sourceJob.getJobId(),
                LocalDateTime.now(), JobStatus.Queue, "Job %s now in the queue.", false);
        this.bulkAction.changeJobLastJobRun(jobQueue.getJobId(), jobQueue.getStartTime());
        this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(), String.format("Job %s now in the queue.", sourceJob.getJobId()));
    }

    /**
     * Method use to send the job int the queue as skip by user action
     * @param sourceJob
     * */
    public void skipManualJobInQueue(SourceJob sourceJob) {
        // if the job in the skip state no need update the last run queue
        JobQueue jobQueue = this.bulkAction.createJobQueue(sourceJob.getJobId(), LocalDateTime.now(),
            JobStatus.Skip, "Job %s skip, by user action.", true);
        this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(),
            String.format("Job %s skip, by user action.", sourceJob.getJobId()));
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
            LocalDateTime now = LocalDateTime.now();
            LookupData lookupData = this.transactionService.findByLookupType(ProcessUtil.SCHEDULER_LAST_RUN_TIME);
            LocalDateTime lastSchedulerRun = LocalDateTime.parse(lookupData.getLookupValue());
            // only the active job will move to the scheduler
            List<Scheduler> schedulerForToday = this.transactionService.findAllSchedulerForToday(now.toLocalDate());
            logger.info("addJobInQueue --> FETCHED Scheduler of current day: size {} ", schedulerForToday.size());
            lookupData.setLookupValue(now.toString());
            this.transactionService.updateLookupDate(lookupData);
            if (!schedulerForToday.isEmpty()) {
                schedulerForToday.parallelStream().forEach(scheduler -> {
                    if (this.isScheduled(lastSchedulerRun, now, scheduler.getJobId(), scheduler.getRecurrenceTime())) {
                        // we have to check if job in the queue then send the detail of job as skip with message
                        JobQueue jobQueue;
                        if (this.bulkAction.getCountForInQueueJobByJobId(scheduler.getJobId()) > 0) {
                            // note:- changeJobLastJobRun only for the job which first move from queue -> next process
                            // if the job in the skip state no need update the last run queue
                            jobQueue = this.bulkAction.createJobQueue(scheduler.getJobId(), LocalDateTime.now(),
                                JobStatus.Skip, "Job %s skip, already in queue.", true);
                            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(),
                                String.format("Job %s skip, already in queue.", scheduler.getJobId()));
                        } else {
                            this.bulkAction.changeJobStatus(scheduler.getJobId(), JobStatus.Queue);
                            jobQueue = this.bulkAction.createJobQueue(scheduler.getJobId(), LocalDateTime.now(),
                                JobStatus.Queue, "Job %s now in the queue.", false);
                            this.bulkAction.changeJobLastJobRun(jobQueue.getJobId(), jobQueue.getStartTime());
                            this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(),
                                String.format("Job %s now in the queue.", scheduler.getJobId()));
                        }
                        // update the next run in scheduler
                        this.bulkAction.updateNextScheduler(scheduler);
                    }
                });
                return;
            }
            logger.info("addJobInQueue --> NO scheduler is set for this timestamp");
        } catch (Exception ex) {
            logger.error("Error In addJobInQueue " + ExceptionUtil.getRootCauseMessage(ex));
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
                jobQueues.parallelStream()
                .forEach(jobQueue -> {
                    Optional<SourceJob> sourceJob = this.transactionService.findByJobIdAndJobStatus(jobQueue.getJobId(), Status.Active);
                    try {
                        if (sourceJob.isPresent()) {
                            this.pushMessageToQueue(sourceJob.get(), jobQueue);
                        } else {
                            this.changeStatusForLastJob(jobQueue, "Job %s stop in the queue due to main job either (delete|inactive).");
                        }
                    } catch (Exception ex) {
                        logger.error("Error In runJobInCurrentTimeSlot " + ExceptionUtil.getRootCauseMessage(ex));
                    }
                });
                return;
            }
            logger.info("addJobInQueue --> NO scheduler is set for this timestamp");
        } catch (Exception ex) {
            logger.error("Error In runJobInCurrentTimeSlot " + ExceptionUtil.getRootCauseMessage(ex));
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
                    Boolean resultRegex = matcher.matches();
                    if (resultRegex) {
                        String topic = matcher.group(1);
                        String partition = matcher.group(2);
                        // random key for send the msg for partitions
                        UUID uuid = UUID.randomUUID();
                        String key = uuid.toString();
                        Map<String, Object> payload = new HashMap<>();
                        payload.put(ProcessUtil.JOB_QUEUE, jobQueue);
                        payload.put(ProcessUtil.TASK_DETAIL, sourceTask);
                        payload.put(ProcessUtil.PRIORITY, sourceJob.getPriority());
                        if (partition.contains(ProcessUtil.START)) {
                            this.kafkaTemplate.send(topic, key, payload.toString());
                        } else {
                            this.kafkaTemplate.send(topic, Integer.valueOf(partition), key, payload.toString());
                        }
                        logger.info("Payload Send " + payload);
                        return;
                    }
                    logger.error("Regex Not match..");
                    this.changeStatusForLastJob(jobQueue, "Broker configuration wrong job %s stop " + queueTopicPartition);
                    return;
                }
                this.changeStatusForLastJob(jobQueue, "Broker not active job %s stop.");
            } catch (Exception ex) {
                logger.error("Error In pushMessageToQueue " + ExceptionUtil.getRootCauseMessage(ex));
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
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
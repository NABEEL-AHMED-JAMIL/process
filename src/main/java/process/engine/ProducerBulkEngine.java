package process.engine;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import process.model.enums.Frequency;
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
    private KafkaTemplate<String, String> kafkaTemplate;

    public ProducerBulkEngine() {
        this.pattern = Pattern.compile(REGEX);
    }

    /**
     * This method use to fetch the detail from the scheduler and match the current running slot if the data
     * match then push the source-job into the job-queue with the 'queue status' and put the logs too for the job-queue
     * this method also update the next running detail if the next running date exist this will update into the
     * scheduler table if not then update the job status into the partial-complete and the status partial-complete to complete
     * base on daily scheduler which is run 1 hr
     * */
    public void addJobInQueue() {
        try {
            logger.info("addJobInQueue --> FETCH Scheduler of current day STARTED ");
            LocalDateTime now = LocalDateTime.now();
            LookupData lookupData = this.transactionService.findByLookupType(ProcessUtil.SCHEDULER_LAST_RUN_TIME);
            LocalDateTime lastSchedulerRun = LocalDateTime.parse(lookupData.getLookupValue());
            List<Scheduler> schedulerForToday = this.transactionService.findAllSchedulerForToday(now.toLocalDate());
            logger.info("addJobInQueue --> FETCHED Scheduler of current day: size {} ", schedulerForToday.size());
            // update the lookup time
            lookupData.setLookupValue(now.toString());
            this.transactionService.updateLookupDate(lookupData);
            if (!schedulerForToday.isEmpty()) {
                schedulerForToday.parallelStream().forEach(scheduler -> {
                    if ((scheduler.getFrequency().equalsIgnoreCase(Frequency.Mint.name()) ||
                        scheduler.getFrequency().equalsIgnoreCase(Frequency.Hr.name()) ||
                        scheduler.getFrequency().equalsIgnoreCase(Frequency.Daily.name()) ||
                        scheduler.getFrequency().equalsIgnoreCase(Frequency.Weekly.name()) ||
                        scheduler.getFrequency().equalsIgnoreCase(Frequency.Monthly.name())) &&
                        this.isScheduled(lastSchedulerRun, now, scheduler.getJobId(), scheduler.getRecurrenceTime())) {
                        // change the job status in-flight
                        this.bulkAction.changeJobStatus(scheduler.getJobId(), JobStatus.InFlight);
                        // add the job into the job-queue
                        JobQueue jobQueue = this.bulkAction.createJobQueue(scheduler.getJobId(), scheduler.getRecurrenceTime());
                        // add the job audit logs for job
                        this.bulkAction.saveJobAuditLogs(jobQueue.getJobQueueId(),
                            String.format("Job %s now in the queue.", scheduler.getJobId()));
                        // update the next run in scheduler
                        this.bulkAction.updateNextScheduler(scheduler);
                    }
                });
            } else {
                logger.info("addJobInQueue --> NO scheduler is set for this timestamp");
            }
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
                jobQueues.parallelStream().forEach(jobQueue -> {
                    Optional<SourceJob> sourceJob = this.transactionService.findByJobIdAndJobStatus(jobQueue.getJobId(), Status.Active);
                    if (sourceJob.isPresent()) {
                        try {
                            Thread.sleep(100);
                            // push the job into the message-queue
                            this.pushMessageToQueue(sourceJob.get(), jobQueue);
                        } catch (InterruptedException ex) {
                            logger.error("Error In runJobInCurrentTimeSlot " + ExceptionUtil.getRootCauseMessage(ex));
                        }
                    }
                });
            } else {
                logger.info("addJobInQueue --> NO scheduler is set for this timestamp");
            }
        } catch (Exception ex) {
            logger.error("Error In runJobInCurrentTimeSlot " + ExceptionUtil.getRootCauseMessage(ex));
        }
    }

    /**
     * This method use to change the partial-complete status into complete status
     * */
    public void checkJobStatus() {
        logger.info("checkJobStatus --> Scheduler of current day STARTED ");
        this.bulkAction.checkJobStatus();
        logger.info("checkJobStatus --> Scheduler of current day END ");
    }

    /***
     * This method check either the job is eligible to put into the queue or not
     * @param lastSchedulerTime -> system scheduler run date
     * @param currentTime -> existing time of the application
     * @param scheduledTime -> target time for run the scheduler
     * @return boolean true|false
     */
    private boolean isScheduled(LocalDateTime lastSchedulerTime, LocalDateTime currentTime,
        Long jobId, LocalDateTime scheduledTime) {
        LocalDateTime target = LocalDateTime.of(currentTime.toLocalDate(), scheduledTime.toLocalTime());
        boolean isExist=target.isBefore(currentTime) && target.isAfter(lastSchedulerTime);
        if(isExist) {
            logger.info("Scheduler -- jobId: " + jobId + " currentTime: " + currentTime
                + " lastSchedulerTime: " + lastSchedulerTime + " scheduledTime: " + scheduledTime);
        }
        return isExist;
    }

    /**
     * Method use to push the data into the kafka queue per topic configuration
     * @param sourceJob
     * @param jobQueue
     * @exception Exception
     * */
    private void pushMessageToQueue(SourceJob sourceJob, JobQueue jobQueue) {
        SourceTask sourceTask = sourceJob.getTaskDetail();
        if (sourceTask.getSourceTaskType() != null) {
            try {
                SourceTaskType sourceTaskType = sourceTask.getSourceTaskType();
                String queueTopicPartition = sourceTaskType.getQueueTopicPartition();
                Matcher matcher = pattern.matcher(queueTopicPartition);
                Boolean resultRegex = matcher.matches();
                if (resultRegex) {
                    String topic = matcher.group(1);
                    String partition = matcher.group(2);
                    // random key for send the msg for partitions
                    UUID uuid = UUID.randomUUID();
                    String key = uuid.toString();
                    /**
                     * If the partitions is containing * then use the simple one
                     * */
                    Map<String, Object> payload = new HashMap<>();
                    payload.put(ProcessUtil.JOB_QUEUE, jobQueue);
                    payload.put(ProcessUtil.TASK_DETAIL, sourceTask);
                    if (partition.contains(ProcessUtil.START)) {
                        /**
                         * Kafka will manage self to send the data to partition's
                         * */
                        this.kafkaTemplate.send(topic, key, payload.toString());
                    } else {
                        /**
                         * Kafka send only the target topic -> partition
                         * */
                        this.kafkaTemplate.send(topic, Integer.valueOf(partition), key, payload.toString());
                    }
                } else {
                    logger.error("Regex Not match..");
                }
            } catch (Exception ex) {
                logger.error("Error In pushMessageToQueue " + ExceptionUtil.getRootCauseMessage(ex));
            }
        }
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

    /**
     * public static void main(String[] args) {
     *    BulkEngine bulkEngine = new BulkEngine();
     *    LocalDateTime lastSchedulerTime = LocalDateTime.of(2021, Month.NOVEMBER, 29, 23, 59, 00);
     *    LocalDateTime currentTime = LocalDateTime.of(2021, Month.NOVEMBER, 30, 00, 05, 00);
     *    LocalDateTime scheduledTime= LocalDateTime.of(2021, Month.NOVEMBER, 30, 00, 00, 00);
     *    System.out.println(bulkEngine.isScheduled(lastSchedulerTime, currentTime, 1002L, scheduledTime));
     *
     *    Pattern twoDigitPattern = Pattern.compile("^topic=([a-zA-Z-]*)&partitions=\\[([0-9*])\\]$");
     *    Matcher matcher = twoDigitPattern.matcher("topic=scrapping-topic&partitions=[*]");
     *    Boolean resultRegex = matcher.matches();
     *    if (resultRegex) {
     *       System.out.println(matcher.group(1));
     *       System.out.println(matcher.group(2));
     *   }
     * }
     * */
}
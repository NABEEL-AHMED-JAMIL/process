package process.engine;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.engine.task.HelloWorldTask;
import process.engine.async.executor.AsyncDALTaskExecutor;
import process.engine.task.StockPriceReportTask;
import process.model.enums.Frequency;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.pojo.*;
import process.model.service.impl.TransactionServiceImpl;
import process.util.ProcessUtil;
import process.util.exception.ExceptionUtil;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Map;

/**
 * @author Nabeel Ahmed
 */
@Component
public class BulkEngine {

    public Logger logger = LogManager.getLogger(BulkEngine.class);

    // task
    @Autowired
    private HelloWorldTask helloWorldTask;
    @Autowired
    private StockPriceReportTask stockPriceReportTask;
    // thread-pool
    @Autowired
    private AsyncDALTaskExecutor asyncDALTaskExecutor;
    @Autowired
    private TransactionServiceImpl transactionService;
    @Autowired
    private BulkAction bulkAction;

    public BulkEngine() { }

    /**
     * This method use to fetch the detail from the scheduler and match the current running slot if the data
     * match then push the job into the job-history in the queue status and put the the logs too for the job-history
     * this method also update the next running detail if the next running date exist this will update into the
     * scheduler table if not then update the job status into the partial-complete and the status partial-complete to complete
     * base on daily scheduler which is run 1 hr
     * */
    public void addJobInQueue() {
        try {
            logger.info("addJobInQueue --> FETCH Scheduler of current day STARTED ");
            LocalDateTime now = LocalDateTime.now();
            LookupData obj = this.transactionService.findByLookupType(ProcessUtil.SCHEDULER_LAST_RUN_TIME);
            LocalDateTime lastSchedulerRun = LocalDateTime.parse(obj.getLookupName());
            List<Scheduler> schedulerForToday = this.transactionService.findAllSchedulerForToday(now.toLocalDate());
            logger.info("addJobInQueue --> FETCHED Scheduler of current day: size {} ", schedulerForToday.size());
            // update the lookup time
            obj.setLookupName(now.toString());
            this.transactionService.updateLookupDate(obj);
            if (!schedulerForToday.isEmpty()) {
                schedulerForToday.parallelStream().forEach(scheduler -> {
                    if ((scheduler.getFrequency().equalsIgnoreCase(Frequency.Mint.name()) || scheduler.getFrequency().equalsIgnoreCase(Frequency.Hr.name())
                        || scheduler.getFrequency().equalsIgnoreCase(Frequency.Daily.name()) || scheduler.getFrequency().equalsIgnoreCase(Frequency.Weekly.name()) ||
                        scheduler.getFrequency().equalsIgnoreCase(Frequency.Monthly.name())) &&
                        this.isScheduled(lastSchedulerRun, now, scheduler.getJobId(), scheduler.getRecurrenceTime())) {
                        // change the job status in-flight
                        this.bulkAction.changeJobStatus(scheduler.getJobId(), JobStatus.InFlight);
                        // add the job into the job-history
                        JobHistory jobHistory = this.bulkAction.createJobHistory(scheduler.getJobId(), scheduler.getRecurrenceTime());
                        // add the job audit logs for job
                        this.bulkAction.saveJobAuditLogs(scheduler.getJobId(), jobHistory.getJobHistoryId(),
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
     * This method fetch the job from job-history and put into the thread-pool
     * the thread pool send the detail to worker thread
     * */
    public void runJobInCurrentTimeSlot() {
        try {
            logger.info("runJobInCurrentTimeSlot --> FETCH JobQueue of current day STARTED ");
            LookupData obj = this.transactionService.findByLookupType(ProcessUtil.QUEUE_FETCH_LIMIT);
            List<JobHistory> jobHistories = this.transactionService.findAllJobForTodayWithLimit(Long.valueOf(obj.getLookupName()));
            logger.info("runJobInCurrentTimeSlot --> FETCHED JobQueue of current day: size {} ", jobHistories.size());
            if (!jobHistories.isEmpty()) {
                jobHistories.parallelStream().forEach(jobHistory -> {
                    Optional<Job> job = this.transactionService.findByJobIdAndJobStatus(jobHistory.getJobId(), Status.Active);
                    Map<String, Object> objectTransfer = new HashMap<>();
                    if (job.isPresent()) {
                        try {
                            Thread.sleep(100);
                            switch (job.get().getTriggerDetail()) {
                                case "process.engine.task.HelloWorldTask":
                                    objectTransfer.put(ProcessUtil.JOB_HISTORY, jobHistory);
                                    this.helloWorldTask.setData(objectTransfer);
                                    this.asyncDALTaskExecutor.addTask(this.helloWorldTask);
                                    break;
                                case "process.engine.task.StockPriceReportTask":
                                    objectTransfer.put(ProcessUtil.JOB_HISTORY, jobHistory);
                                    objectTransfer.put(ProcessUtil.FILE_PATH, ProcessUtil.STOCK_PRICE_REPORT_DETAIL_JSON);
                                    this.stockPriceReportTask.setData(objectTransfer);
                                    this.asyncDALTaskExecutor.addTask(this.stockPriceReportTask);
                                    break;
                                default:
                                    logger.info("No Trigger Found");
                            }
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
    public boolean isScheduled(LocalDateTime lastSchedulerTime, LocalDateTime currentTime, Long jobId, LocalDateTime scheduledTime) {
        LocalDateTime target = LocalDateTime.of(currentTime.toLocalDate(), scheduledTime.toLocalTime());
        boolean isExist=target.isBefore(currentTime) && target.isAfter(lastSchedulerTime);
        if(isExist) {
            logger.info("Scheduler -- jobId: " + jobId + " currentTime: " + currentTime
                + " lastSchedulerTime: " + lastSchedulerTime + " scheduledTime: " + scheduledTime);
        }
        return isExist;
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
     * }
     * */

}
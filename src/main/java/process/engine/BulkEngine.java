package process.engine;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import process.engine.task.EWalletCardDetailTask;
import process.engine.task.HelloWorldTask;
import process.engine.async.executor.AsyncDALTaskExecutor;
import process.engine.task.StoreLocatorPinTask;
import process.model.dto.JobHistoryDto;
import process.model.enums.JobStatus;
import process.model.enums.Status;
import process.model.mapper.JobMapper;
import process.model.pojo.*;
import process.model.service.impl.TransactionServiceImpl;
import process.util.exception.ExceptionUtil;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author Nabeel Ahmed
 */
@Component
public class BulkEngine {

    public Logger logger = LogManager.getLogger(BulkEngine.class);

    // mapper
    @Autowired
    private JobMapper jobMapper;
    // task
    @Autowired
    private HelloWorldTask helloWorldTask;
    @Autowired
    private EWalletCardDetailTask eWalletCardDetailTask;
    @Autowired
    private StoreLocatorPinTask storeLocatorPinTask;
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
     * scheduler table if not then update the job status into the partial-complete and the status partial-complete change
     * base on daily scheduler which is run 2 time a day (12, 24)
     * */
    public void addJobInQueue() {
        try {
            logger.info("addJobInQueue --> FETCH Scheduler of current day STARTED ");
            LocalDateTime now = LocalDateTime.now();
            LookupData obj = this.transactionService.findByLookupType("SCHEDULER_LAST_RUN_TIME");
            LocalDateTime lastSchedulerRun = LocalDateTime.parse(obj.getLookupName());
            List<Scheduler> schedulerForToday = this.transactionService.findAllSchedulerForToday(now.toLocalDate());
            logger.info("addJobInQueue --> FETCHED Scheduler of current day: size {} ", schedulerForToday.size());
            if (!schedulerForToday.isEmpty()) {
                schedulerForToday.parallelStream().forEach(scheduler -> {
                    if (scheduler.getFrequency().equalsIgnoreCase("Mint") && this.isScheduled(lastSchedulerRun, now,
                        scheduler.getJobId(), scheduler.getRecurrenceTime())) {
                        // change the job status in-flight
                        this.bulkAction.changeJobStatus(scheduler.getJobId(), JobStatus.InFlight);
                        // add the job into the job-history
                        JobHistoryDto jobHistoryDto = this.bulkAction.createJobHistory(scheduler.getJobId(), scheduler.getRecurrenceTime());
                        // add the job audit logs for job
                        this.transactionService.saveJobAuditLogs(scheduler.getJobId(), jobHistoryDto.getJobHistoryId(),
                        String.format("Job %s now in the queue.", scheduler.getJobId()));
                        // update the next run in scheduler
                    } else if (scheduler.getFrequency().equalsIgnoreCase("Hr") && this.isScheduled(lastSchedulerRun, now,
                            scheduler.getJobId(), scheduler.getRecurrenceTime())) {
                        // change the job status in-flight
                        this.bulkAction.changeJobStatus(scheduler.getJobId(), JobStatus.InFlight);
                        // add the job into the job-history
                        JobHistoryDto jobHistoryDto = this.bulkAction.createJobHistory(scheduler.getJobId(), scheduler.getRecurrenceTime());
                        // add the job audit logs for job
                        this.transactionService.saveJobAuditLogs(scheduler.getJobId(), jobHistoryDto.getJobHistoryId(),
                        String.format("Job %s now in the queue.", scheduler.getJobId()));
                        // update the next run in scheduler
                    } else if (scheduler.getFrequency().equalsIgnoreCase("Daily") && this.isScheduled(lastSchedulerRun, now,
                            scheduler.getJobId(), scheduler.getRecurrenceTime())) {
                        // change the job status in-flight
                        this.bulkAction.changeJobStatus(scheduler.getJobId(), JobStatus.InFlight);
                        // add the job into the job-history
                        JobHistoryDto jobHistoryDto = this.bulkAction.createJobHistory(scheduler.getJobId(), scheduler.getRecurrenceTime());
                        // add the job audit logs for job
                        this.transactionService.saveJobAuditLogs(scheduler.getJobId(), jobHistoryDto.getJobHistoryId(),
                        String.format("Job %s now in the queue.", scheduler.getJobId()));
                        // update the next run in scheduler
                    } else if (scheduler.getFrequency().equalsIgnoreCase("Weekly") && this.isScheduled(lastSchedulerRun, now,
                            scheduler.getJobId(), scheduler.getRecurrenceTime())) {
                        // change the job status in-flight
                        this.bulkAction.changeJobStatus(scheduler.getJobId(), JobStatus.InFlight);
                        // add the job into the job-history
                        JobHistoryDto jobHistoryDto = this.bulkAction.createJobHistory(scheduler.getJobId(), scheduler.getRecurrenceTime());
                        // add the job audit logs for job
                        this.transactionService.saveJobAuditLogs(scheduler.getJobId(), jobHistoryDto.getJobHistoryId(),
                        String.format("Job %s now in the queue.", scheduler.getJobId()));
                        // update the next run in scheduler
                    } else if (scheduler.getFrequency().equalsIgnoreCase("Monthly") && this.isScheduled(lastSchedulerRun, now,
                            scheduler.getJobId(), scheduler.getRecurrenceTime())) {
                        // change the job status in-flight
                        this.bulkAction.changeJobStatus(scheduler.getJobId(), JobStatus.InFlight);
                        // add the job into the job-history
                        JobHistoryDto jobHistoryDto = this.bulkAction.createJobHistory(scheduler.getJobId(), scheduler.getRecurrenceTime());
                        // add the job audit logs for job
                        this.transactionService.saveJobAuditLogs(scheduler.getJobId(), jobHistoryDto.getJobHistoryId(),
                        String.format("Job %s now in the queue.", scheduler.getJobId()));
                        // update the next run in scheduler
                    }
                });
            } else {
                logger.info("addJobInQueue --> NO scheduler is set for this timestamp");
            }
            obj.setLookupName(now.toString());
            this.transactionService.updateLookupDate(obj);
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
            LookupData obj = this.transactionService.findByLookupType("QUEUE_FETCH_LIMIT");
            List<JobHistory> jobHistories = this.transactionService.findAllJobForTodayWithLimit(Long.valueOf(obj.getLookupName()));
            logger.info("runJobInCurrentTimeSlot --> FETCHED JobQueue of current day: size {} ", jobHistories.size());
            if (!jobHistories.isEmpty()) {
                jobHistories.parallelStream().forEach(jobHistory -> {
                    Optional<Job> job = this.transactionService.findByJobIdAndJobStatus(jobHistory.getJobId(), Status.Active);
                    if (job.isPresent()) {
                        Job job1 = job.get();
                        switch (job1.getTriggerDetail()) {
                            case "process.engine.task.HelloWorldTask":
                                break;
                            case "process.engine.task.EWalletCardDetailTask":
                                break;
                            case "process.engine.task.StoreLocatorPinTask":
                                break;
                            default:
                                logger.info("No Trigger Found");
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
        if(isExist){
            logger.info("Scheduler -- jobId: " + jobId +
            " currentTime: " + currentTime + " lastSchedulerTime: " + lastSchedulerTime + " scheduledTime: " + scheduledTime);
        }
        return isExist;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}

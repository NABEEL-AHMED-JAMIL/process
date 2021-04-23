package process.engine.cron;

import com.google.gson.Gson;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import process.engine.BulkEngine;
import java.util.Date;

/**
 * @author Nabeel Ahmed
 * Class use to handle the all crons
 */
@Component
public class ProcessCron {

    public Logger logger = LogManager.getLogger(ProcessCron.class);

    public static final int SCHEDULER_CRON_TIME_IN_ONE_MINUTES=1;
    public static final int SCHEDULER_CRON_TIME_IN_TWO_MINUTES=2;
    public static final int SCHEDULER_CRON_TIME_IN_ONE_HOUR = 60;

    @Autowired
    private BulkEngine bulkEngine;

    /**
     * This addJobInQueue method run every 2 minutes
     * */
    @Scheduled(fixedDelay = 60 * ProcessCron.SCHEDULER_CRON_TIME_IN_TWO_MINUTES * 1000)
    public void addJobInQueue() {
        logger.info("++++++++++++++++++++++++Start-AddJobInQueue++++++++++++++++++++++++++++++++");
        logger.info("CRON JOB QUEUE STARTED " + new Date(System.currentTimeMillis()));
        this.bulkEngine.addJobInQueue();
        logger.info("+++++++++++++++++++++++++++End-AddJobInQueue++++++++++++++++++++++++++++++++");
    }

    /**
     * This runJob method run every 1 minutes and put the job into the running state
     * */
    @Scheduled(fixedDelay = 60 * ProcessCron.SCHEDULER_CRON_TIME_IN_ONE_MINUTES * 1000)
    public void runJob() {
        logger.info("************************Start-RunJob********************************");
        logger.info("CRON JOB STARTED " + new Date(System.currentTimeMillis()));
        this.bulkEngine.runJobInCurrentTimeSlot();
        logger.info("*************************End-RunJob*********************************");
    }

    /**
     * This runJob method run every 1 minutes and put the job into the running state
     * */
    @Scheduled(fixedDelay = 60 * ProcessCron.SCHEDULER_CRON_TIME_IN_ONE_HOUR * 1000)
    public void checkJobStatus() {
        logger.info("-------------------------Start-CheckJobStatus-------------------------");
        logger.info("CRON JOB STARTED " + new Date(System.currentTimeMillis()));
        this.bulkEngine.checkJobStatus();
        logger.info("-------------------------End-CheckJobStatus-------------------------");
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}

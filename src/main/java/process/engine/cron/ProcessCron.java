package process.engine.cron;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import process.engine.ProducerBulkEngine;

/**
 * @author Nabeel Ahmed
 * Class use to handle the all crons
 */
@Component
public class ProcessCron {

    public Logger logger = LogManager.getLogger(ProcessCron.class);

    public static final int SCHEDULER_CRON_TIME_IN_ONE_MINUTES=1;
    public static final int SCHEDULER_CRON_TIME_IN_THIRTY_SECOND=30;
    public static final int SCHEDULER_CRON_TIME_IN_TWO_MINUTES=2;
    public static final int SCHEDULER_CRON_TIME_IN_ONE_HOUR=60;

    @Autowired
    private ProducerBulkEngine producerBulkEngine;

    /**
     * This addJobInQueue method run every 1 minutes
     * */
    @Scheduled(fixedDelay = 60 * ProcessCron.SCHEDULER_CRON_TIME_IN_ONE_MINUTES * 1000)
    public void addJobInQueue() {
        logger.info("++++++++++++++++++++++++Start-AddJobInQueue++++++++++++++++++++++++++++++++");
        this.producerBulkEngine.addJobInQueue();
        logger.info("+++++++++++++++++++++++++++End-AddJobInQueue++++++++++++++++++++++++++++++++");
    }

    /**
     * This runJob method run every 30 SECOND and put the job into the running state
     * */
    @Scheduled(fixedDelay = ProcessCron.SCHEDULER_CRON_TIME_IN_THIRTY_SECOND * 1000)
    public void runJob() {
        logger.info("************************Start-RunJob********************************");
        this.producerBulkEngine.runJobInCurrentTimeSlot();
        logger.info("*************************End-RunJob*********************************");
    }

}
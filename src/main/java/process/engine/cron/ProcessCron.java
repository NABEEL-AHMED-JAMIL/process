package process.engine.cron;

import com.google.gson.Gson;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

    private final ProducerBulkEngine producerBulkEngine;

    public ProcessCron(ProducerBulkEngine producerBulkEngine) {
        this.producerBulkEngine = producerBulkEngine;
        logger.info("++++++++++++++++++++++++ProcessCron Bean Created and Initialized++++++++++++++++++++++++");
    }

    /**
     * This addJobInQueue method run every 30 second and put the job into queue
     * */
    @Scheduled(initialDelay = 5000, fixedDelay = 60 * ProcessCron.SCHEDULER_CRON_TIME_IN_ONE_MINUTES * 1000)
    @SchedulerLock(name = "addJobInQueue", lockAtLeastFor = "5S", lockAtMostFor = "10M")
    public void addJobInQueue() {
        try {
            logger.info("++++++++++++++++++++++++Start-AddJobInQueue++++++++++++++++++++++++++++++++");
            this.producerBulkEngine.addJobInQueue();
            logger.info("+++++++++++++++++++++++++++End-AddJobInQueue++++++++++++++++++++++++++++++++");
        } catch (Exception e) {
            logger.error("Error in addJobInQueue scheduler: {}", e.getMessage(), e);
        }
    }

    /**
     * This runJob method run every 30 second and put the job into the running state
     * */
    @Scheduled(initialDelay = 5000, fixedDelay = 60 * ProcessCron.SCHEDULER_CRON_TIME_IN_ONE_MINUTES * 1000)
    @SchedulerLock(name = "runJob", lockAtLeastFor = "5S", lockAtMostFor = "10M")
    public void runJob() {
        try {
            logger.info("************************Start-RunJob********************************");
            this.producerBulkEngine.runJobInCurrentTimeSlot();
            logger.info("*************************End-RunJob*********************************");
        } catch (Exception e) {
            logger.error("Error in runJob scheduler: {}", e.getMessage(), e);
        }
    }

    /**
     * Simple test scheduled method without ShedLock to verify Spring Scheduling works
     * */
    @Scheduled(initialDelay = 3000, fixedDelay = 30000)
    public void testScheduler() {
        logger.info("========== TEST SCHEDULER METHOD EXECUTING ==========");
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}
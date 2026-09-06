package process.engine.cron;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import process.engine.ProducerBulkEngine;

/**
 * @author Nabeel Ahmed
 * */
@Component
public class ProcessCron {

    private static final Logger logger = LogManager.getLogger(ProcessCron.class);

    public static final int SCHEDULER_CRON_TIME_IN_ONE_MINUTES=1;

    private final ProducerBulkEngine producerBulkEngine;

    public ProcessCron(ProducerBulkEngine producerBulkEngine) {
        this.producerBulkEngine = producerBulkEngine;
    }

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

    @Scheduled(initialDelay = 5000, fixedDelay = 60 * ProcessCron.SCHEDULER_CRON_TIME_IN_ONE_MINUTES * 1000)
    @SchedulerLock(name = "startJobInCurrentTimeSlot", lockAtLeastFor = "5S", lockAtMostFor = "10M")
    public void startJobInCurrentTimeSlot() {
        try {
            logger.info("************************Start-RunJob********************************");
            this.producerBulkEngine.startJobInCurrentTimeSlot();
            logger.info("*************************End-RunJob*********************************");
        } catch (Exception e) {
            logger.error("Error in startJobInCurrentTimeSlot scheduler: {}", e.getMessage(), e);
        }
    }

    /**
     * Runs a quarter-hour apart rather than every minute: it exists to catch something that has
     * already been stuck for six hours, so noticing within fifteen minutes is ample and it keeps
     * a table scan off the minute cycle.
     */
    @Scheduled(initialDelay = 30000, fixedDelay = 15 * 60 * 1000)
    @SchedulerLock(name = "reconcileStalledRuns", lockAtLeastFor = "5S", lockAtMostFor = "5M")
    public void reconcileStalledRuns() {
        try {
            this.producerBulkEngine.reconcileStalledRuns();
        } catch (Exception e) {
            logger.error("Error in reconcileStalledRuns scheduler: {}", e.getMessage(), e);
        }
    }

}
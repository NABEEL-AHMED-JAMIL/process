package process.engine.cron;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import process.engine.ProducerBulkEngine;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * @author Nabeel Ahmed
 * */
/*
 * Switched off wherever process.scheduling.enabled is false.
 *
 * src/test/resources/application-e2e.properties has set that property since the E2E profile was
 * written and NOTHING READ IT, so every end-to-end run booted the whole application on a developer's
 * machine with @EnableScheduling live and, five seconds later, this cron enqueued and started REAL
 * DUE JOBS out of the shared database. Twelve of a tenant's jobs were dispatched and marked Failed
 * by one such run -- there is no worker listening to a test context, so the dispatch cannot succeed
 * -- and their last_job_run was written in the developer's own timezone while the container writes
 * UTC, leaving the same column five hours inconsistent with itself.
 *
 * matchIfMissing = true: absent means enabled, so production is unchanged by this.
 */
@ConditionalOnProperty(name = "process.scheduling.enabled", havingValue = "true", matchIfMissing = true)
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
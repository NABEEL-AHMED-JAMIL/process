package process.engine.cron;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import process.engine.ProducerBulkEngine;
import process.model.pojo.QuerySchedule;
import process.model.service.QueryExecutionService;
import process.model.service.QueryScheduleService;
import process.security.TenantContext;
import java.sql.Timestamp;
import java.util.List;

/**
 * @author Nabeel Ahmed
 * */
@Component
public class ProcessCron {

    private static final Logger logger = LogManager.getLogger(ProcessCron.class);

    public static final int SCHEDULER_CRON_TIME_IN_ONE_MINUTES=1;

    private final ProducerBulkEngine producerBulkEngine;
    private final QueryScheduleService queryScheduleService;
    private final QueryExecutionService queryExecutionService;

    public ProcessCron(ProducerBulkEngine producerBulkEngine, QueryScheduleService queryScheduleService,
        QueryExecutionService queryExecutionService) {
        this.producerBulkEngine = producerBulkEngine;
        this.queryScheduleService = queryScheduleService;
        this.queryExecutionService = queryExecutionService;
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

    @Scheduled(initialDelay = 10000, fixedDelay = 60 * ProcessCron.SCHEDULER_CRON_TIME_IN_ONE_MINUTES * 1000)
    @SchedulerLock(name = "pollDueQuerySchedules", lockAtLeastFor = "5S", lockAtMostFor = "10M")
    public void pollDueQuerySchedules() {
        List<QuerySchedule> dueSchedules;
        try {
            dueSchedules = this.queryScheduleService.findDueSchedules(new Timestamp(System.currentTimeMillis()));
        } catch (Exception e) {
            logger.error("Error polling due query schedules: {}", e.getMessage(), e);
            return;
        }
        // Silent when nothing is due, which is the usual case on a minute cycle. The other crons
        // can afford their banner every tick because they always have work to describe.
        if (dueSchedules.isEmpty()) {
            return;
        }
        logger.info("========================Start-PollDueQuerySchedules due={}========================",
            dueSchedules.size());
        int executed = 0;
        for (QuerySchedule schedule : dueSchedules) {
            try {
                TenantContext.set(schedule.getTenantId(), "TENANT_USER", schedule.getCreatedBy(), "scheduler");
                this.queryExecutionService.executeForSchedule(schedule);
                executed++;
            } catch (Exception e) {
                logger.error("Query schedule {} (tenant {}) failed to execute: {}",
                    schedule.getScheduleId(), schedule.getTenantId(), e.getMessage(), e);
            } finally {
                // Cleared before nextRunAt is advanced, deliberately: AuditListener stamps
                // updated_by from whoever is in context, and a run the scheduler started on its
                // own has no human author to name.
                TenantContext.clear();

                try {
                    this.queryScheduleService.advanceNextRun(schedule.getScheduleId());
                } catch (Exception e) {
                    // Deliberately left un-advanced. nextRunAt stays in the past, so the schedule
                    // is picked up again on the next tick instead of being silently skipped.
                    logger.error("Could not advance nextRunAt for query schedule {}: {}",
                        schedule.getScheduleId(), e.getMessage(), e);
                }
            }
        }
        logger.info("=========================End-PollDueQuerySchedules executed={}/{}=========================",
            executed, dueSchedules.size());
    }

}
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
 * Class use to handle the all crons
 */
@Component
public class ProcessCron {

    public Logger logger = LogManager.getLogger(ProcessCron.class);

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
     * This addJobInQueue method run every 30 second and put the job into the running state
     * */
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
     * Polls query_schedule for due rows and runs each through QueryExecutionService --
     * §8/§9 of the Query Engine design: the exact same execution path a manual "Run" click
     * uses, just triggered here instead of an HTTP request. Same @Scheduled+@SchedulerLock
     * pattern as addJobInQueue/startJobInCurrentTimeSlot above (only one node runs this per
     * tick in a multi-instance deployment), deliberately not routed through Kafka/
     * ProducerBulkEngine -- a query execution is a fast, self-contained, in-process JDBC-to-
     * CSV-to-storage operation with no external worker to hand off to, unlike a SourceJob's
     * pipeline dispatch.
     *
     * TenantContext is a per-HTTP-request ThreadLocal (populated by JwtAuthenticationFilter,
     * cleared in its finally block) that every tenant-scoped service call in this app depends
     * on -- this scheduler thread never goes through that filter, so each due schedule's own
     * tenantId/createdBy is seeded here instead, immediately before that one schedule's
     * execution, and cleared right after (mirrors the filter's own set/finally-clear shape) so
     * one tenant's context can never leak into the next schedule processed in the same loop.
     * */
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
        for (QuerySchedule schedule : dueSchedules) {
            try {
                TenantContext.set(schedule.getTenantId(), "TENANT_USER", schedule.getCreatedBy(), "scheduler");
                this.queryExecutionService.executeForSchedule(schedule);
            } catch (Exception e) {
                logger.error("Query schedule {} (tenant {}) failed to execute: {}",
                    schedule.getScheduleId(), schedule.getTenantId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
                // Advance nextRunAt regardless of success/failure -- see
                // QueryScheduleService.advanceNextRun's own javadoc for why a persistently
                // failing schedule must not stay "due" forever.
                try {
                    this.queryScheduleService.advanceNextRun(schedule.getScheduleId());
                } catch (Exception e) {
                    logger.error("Could not advance nextRunAt for query schedule {}: {}",
                        schedule.getScheduleId(), e.getMessage(), e);
                }
            }
        }
    }

}
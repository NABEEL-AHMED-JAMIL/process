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
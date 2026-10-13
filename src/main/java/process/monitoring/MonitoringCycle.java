package process.monitoring;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * MIG-355: the monitor's minute, under ShedLock so one replica does it -- sample every service and the probes
 * (HealthCollector), evaluate every enabled alert rule (AlertEngine), and keep the tables bounded: health samples older
 * than a day and alerts resolved more than 30 days ago are removed. Each step's failure is logged and the next one runs;
 * the next minute tries again.
 *
 * On the clock at second 20, away from the minute crons' :00. Off with process.monitoring.enabled=false (and with every
 * cron, process.scheduling.enabled=false).
 */
@ConditionalOnProperty(name = "process.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class MonitoringCycle {

    private static final Logger logger = LoggerFactory.getLogger(MonitoringCycle.class);

    private final HealthCollector health;
    private final AlertEngine engine;
    private final AlertIncidents incidents;
    private final boolean enabled;
    private final Clock clock = Clock.systemUTC();

    public MonitoringCycle(HealthCollector health, AlertEngine engine, AlertIncidents incidents,
        @Value("${process.monitoring.enabled:true}") boolean enabled) {
        this.health = health;
        this.engine = engine;
        this.incidents = incidents;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${process.monitoring.cron:20 * * * * *}")
    @SchedulerLock(name = "monitoringCycle", lockAtLeastFor = "20S", lockAtMostFor = "55S")
    public void minute() {
        if (!this.enabled) {
            return;
        }
        Instant now = this.clock.instant().truncatedTo(ChronoUnit.SECONDS);
        try {
            this.health.collect(now);
        } catch (RuntimeException failed) {
            logger.warn("The health sample of {} could not be taken: {}", now, failed.getMessage());
        }
        try {
            this.engine.evaluate(now);
        } catch (RuntimeException failed) {
            logger.warn("The alert rules could not be evaluated at {}: {}", now, failed.getMessage());
        }
        try {
            this.health.purge(now);
            RowSecurity.acrossTenants("the alert retention (MIG-355) removes every workspace's alerts resolved more than 30 days ago",
                () -> this.incidents.purge(now));
        } catch (RuntimeException failed) {
            logger.warn("The monitoring tables could not be trimmed: {}", failed.getMessage());
        }
    }
}

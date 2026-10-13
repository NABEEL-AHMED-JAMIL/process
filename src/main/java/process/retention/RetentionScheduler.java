package process.retention;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * MIG-354: the daily retention pass, hourly under ShedLock (retentionPass): one replica at a time, one workspace at a
 * time, each as itself (RowSecurity.forTenant). The only read across workspaces is which of them turned retention on.
 *
 * <p>Per workspace, each tick:
 * <ul>
 *   <li>a RUNNING pass whose heartbeat stopped {@link #STALE} ago (the service restarted) becomes INTERRUPTED;</li>
 *   <li>a running pass (an administrator's) is left alone;</li>
 *   <li>a PARTIAL or INTERRUPTED last pass is continued (RESUME) -- a pass over its time budget or its per-class batch
 *   cap, or one cut off, finishes over the next ticks;</li>
 *   <li>otherwise, once a day from {@link #START_HOUR}:00 Chicago time, a SCHEDULE pass when none ended today.</li>
 * </ul>
 * A failure of one workspace's pass never stops the others'; it is FAILED in its record, and the workspace's RETENTION
 * alert tells its administrators.
 */
@ConditionalOnProperty(name = "process.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class RetentionScheduler {

    static final ZoneId ZONE = ZoneId.of("America/Chicago");
    static final int START_HOUR = 2;
    static final Duration STALE = Duration.ofMinutes(30);

    private static final Logger logger = LoggerFactory.getLogger(RetentionScheduler.class);

    private final RetentionStore store;
    private final RetentionPass pass;
    private final Clock clock;
    private final boolean enabled;

    @Autowired
    public RetentionScheduler(RetentionStore store, RetentionPass pass, @Value("${process.retention.enabled:true}") boolean enabled) {
        this(store, pass, Clock.systemUTC(), enabled);
    }

    RetentionScheduler(RetentionStore store, RetentionPass pass, Clock clock, boolean enabled) {
        this.store = store;
        this.pass = pass;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${process.retention.cron:0 40 * * * *}")
    @SchedulerLock(name = "retentionPass", lockAtLeastFor = "30S", lockAtMostFor = "55M")
    public void tick() {
        if (!this.enabled) {
            return;
        }
        try {
            this.runDue();
        } catch (RuntimeException failed) {
            logger.warn("Retention tick failed; the next hour tries again: {}", failed.getMessage());
        }
    }

    /** Runs every workspace's due pass; how many ran. */
    public int runDue() {
        List<Long> workspaces = RowSecurity.acrossTenants("the retention scheduler (MIG-354) reads which workspaces turned retention on (ids"
            + " only); each workspace's pass then runs as that workspace", this.store::enabledWorkspaces);
        int ran = 0;
        for (Long tenantId : workspaces) {
            try {
                if (RowSecurity.forTenant(tenantId, () -> this.runIfDue(tenantId))) {
                    ran++;
                }
            } catch (RuntimeException failed) {
                logger.warn("Retention: workspace {}'s pass could not run this hour: {}", tenantId, failed.getMessage());
            }
        }
        return ran;
    }

    /** One workspace, as itself: true when a pass ran. */
    boolean runIfDue(long tenantId) {
        Instant now = this.clock.instant();
        this.store.interruptStale(tenantId, now.minus(STALE));
        Optional<RetentionStore.Pass> last = this.store.latestPass(tenantId);
        String how = due(last.orElse(null), now);
        if (how == null) {
            return false;
        }
        this.pass.run(tenantId, how, null);
        return true;
    }

    /** SCHEDULE, RESUME or null (nothing due) for a workspace whose last pass is {@code last}. */
    static String due(RetentionStore.Pass last, Instant now) {
        if (last != null && "RUNNING".equals(last.state)) {
            return null;
        }
        if (last != null && ("PARTIAL".equals(last.state) || "INTERRUPTED".equals(last.state))) {
            return "RESUME";
        }
        ZonedDateTime local = now.atZone(ZONE);
        if (local.getHour() < START_HOUR) {
            return null;
        }
        LocalDate today = local.toLocalDate();
        if (last != null && last.startedAt != null && !last.startedAt.atZone(ZONE).toLocalDate().isBefore(today)) {
            return null;
        }
        return "SCHEDULE";
    }
}

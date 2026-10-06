package process.outbox;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes dispatch_outbox rows published or abandoned more than {@value #RETENTION_DAYS} days ago, hourly (event audit
 * E8): nothing purged them, and the table grew by a row per run attempt for ever. A pending row is never touched.
 *
 * In batches of {@value #BATCH} rows by ctid, so no statement holds many row locks or runs long beside the relay, and at
 * most {@value #MAX_BATCHES} batches a run: a backlog is cleared over the next runs. ShedLock keeps it to one instance at a
 * time; switched off with the rest of the schedulers where process.scheduling.enabled is false.
 *
 * Row-level security (MIG-258): a scheduler thread has no caller; the delete runs across workspaces
 * (RowSecurity.acrossTenants), listed in RowSecurityContractTest.
 */
@ConditionalOnProperty(name = "process.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class DispatchOutboxPurge {

    static final int RETENTION_DAYS = 7;
    static final int BATCH = 5000;
    static final int MAX_BATCHES = 200;

    private static final Logger logger = LoggerFactory.getLogger(DispatchOutboxPurge.class);

    private final JdbcTemplate jdbc;

    public DispatchOutboxPurge(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(initialDelay = 120000, fixedDelay = 60 * 60 * 1000)
    @SchedulerLock(name = "purgeDispatchOutbox", lockAtLeastFor = "5S", lockAtMostFor = "15M")
    public void purgeQuietly() {
        try {
            int purged = this.purge();
            if (purged > 0) {
                logger.info("Purged {} dispatch outbox row(s) published or abandoned more than {} days ago.", purged, RETENTION_DAYS);
            }
        } catch (RuntimeException failed) {
            logger.warn("Dispatch outbox purge failed: {}", failed.getMessage());
        }
    }

    /** Answers how many rows went. */
    public int purge() {
        return RowSecurity.acrossTenants("the dispatch outbox purge deletes every workspace's hand-offs published or abandoned more than"
            + " seven days ago; it never touches a pending one, and nothing else", () -> {
            int purged = 0;
            for (int batch = 0; batch < MAX_BATCHES; batch++) {
                int deleted = this.jdbc.update("DELETE FROM dispatch_outbox WHERE ctid IN (SELECT ctid FROM dispatch_outbox "
                    + "WHERE published_at < now() - make_interval(days => ?) OR abandoned_at < now() - make_interval(days => ?) LIMIT ?)",
                    RETENTION_DAYS, RETENTION_DAYS, BATCH);
                purged += deleted;
                if (deleted < BATCH) {
                    break;
                }
            }
            return purged;
        });
    }
}

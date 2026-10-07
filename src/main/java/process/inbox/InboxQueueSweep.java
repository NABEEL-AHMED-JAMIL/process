package process.inbox;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MIG-360: the inbox's waiting files start their job's next run within seconds of the run in flight ending, whichever
 * way it ended -- its worker or the step engine, a person failing or interrupting it, the stall sweep. Every
 * {@code inbox.queue.poll-ms} (2 s) it reads the jobs that have waiting files and no run in flight, and starts each one's
 * next run ({@link InboxTriggerService#startNext}) as that job's workspace.
 *
 * <p>The scan is one query on two partial indexes -- inbox_arrival's waiting files (V204) and V83's one-in-flight runs --
 * so with nothing waiting, the usual case, it reads no rows. Row-level security: the scan is across workspaces (listed in
 * RowSecurityContractTest, ids only); each start runs as its own workspace (RowSecurity.forTenant). One instance at a time
 * (ShedLock); a start is serialised per job anyway, so a second replica's sweep or an arrival racing it takes nothing twice.
 * Off with the rest of the schedulers where process.scheduling.enabled is false.
 */
@ConditionalOnProperty(name = "process.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class InboxQueueSweep {

    /** How many jobs one pass starts at most; the next pass takes the rest. */
    static final int BATCH = 200;

    private static final Logger logger = LoggerFactory.getLogger(InboxQueueSweep.class);

    private final JdbcTemplate jdbc;
    private final InboxTriggerService triggers;

    public InboxQueueSweep(JdbcTemplate jdbc, InboxTriggerService triggers) {
        this.jdbc = jdbc;
        this.triggers = triggers;
    }

    @Scheduled(initialDelayString = "${inbox.queue.initial-delay-ms:20000}", fixedDelayString = "${inbox.queue.poll-ms:2000}")
    @SchedulerLock(name = "inboxQueueSweep", lockAtMostFor = "5M")
    public void sweepQuietly() {
        try {
            int started = this.sweep();
            if (started > 0) {
                logger.info("Inbox queue: started {} run(s) from waiting files.", started);
            }
        } catch (RuntimeException failed) {
            logger.warn("The inbox queue sweep failed; it tries again: {}", failed.getMessage());
        }
    }

    /** Starts the next run of every job whose files wait and which has nothing in flight; answers how many runs it made. */
    public int sweep() {
        List<long[]> ready = RowSecurity.acrossTenants("the inbox queue sweep (MIG-360) reads which jobs of every workspace have"
            + " inbox files waiting and no run in flight (ids only); each job's next run is started as its own workspace",
            () -> this.jdbc.query("SELECT DISTINCT a.tenant_id, a.job_id FROM inbox_arrival a WHERE a.outcome = 'Waiting' "
                + "AND NOT EXISTS (SELECT 1 FROM job_queue q WHERE q.job_id = a.job_id AND UPPER(q.job_status) IN ('QUEUE', 'START', 'RUNNING')) "
                + "ORDER BY a.job_id LIMIT " + BATCH, (rs, i) -> new long[] {rs.getLong("tenant_id"), rs.getLong("job_id")}));
        int started = 0;
        for (long[] job : ready) {
            try {
                InboxTriggerService.Started next = RowSecurity.forTenant(job[0], () -> this.triggers.startNext(job[1]));
                if (next.jobQueueId != null) {
                    started++;
                    logger.info("Inbox queue: job {} of workspace {}: {}.", job[1], job[0], next);
                }
            } catch (RuntimeException failed) {
                // One job that cannot start must not hold up the others: its files wait for the next pass.
                logger.warn("Inbox queue: job {} of workspace {} could not start its next run; its files wait: {}", job[1], job[0],
                    failed.getMessage());
            }
        }
        return started;
    }
}

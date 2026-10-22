package process.pipeline;

import org.barco.platform.tenancy.RowSecurity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import process.engine.ProducerBulkEngine;
import process.model.pojo.JobQueue;
import process.model.pojo.SourceJob;
import process.model.repository.SourceJobRepository;

import java.util.Optional;

/**
 * Owner 2026-10-21: a run whose step engine stopped (Core restarted while its steps ran) is resumed once, from the step it
 * was on, as the run's next attempt -- straight from in flight, so a workflow waiting on it never sees it end. The step
 * that was running is closed as interrupted first; the steps before it are reused. A run resumed this way before, or one
 * a retry cannot resume (its pipeline changed, its inputs changed, its outputs expired, the workspace paused), is closed
 * as interrupted by the sweep as before, for a person to run again.
 */
@Component
public class LostRunResume implements ProducerBulkEngine.LostRunResumer {

    private static final Logger logger = LoggerFactory.getLogger(LostRunResume.class);

    private final RunRetries retries;
    private final SourceJobRepository jobs;
    private final JdbcTemplate jdbc;

    public LostRunResume(RunRetries retries, SourceJobRepository jobs, JdbcTemplate jdbc) {
        this.retries = retries;
        this.jobs = jobs;
        this.jdbc = jdbc;
    }

    @Override
    public boolean resume(JobQueue run) {
        Long tenantId = run.getTenantId();
        if (tenantId == null) {
            return false;
        }
        try {
            return RowSecurity.forTenant(tenantId, () -> {
                Long before = this.jdbc.queryForObject("SELECT count(*) FROM run_attempt WHERE job_queue_id = ? AND requested_via = ?",
                    Long.class, run.getJobQueueId(), RunAttemptStore.VIA_PLATFORM);
                if (before != null && before > 0) {
                    logger.info("Run {}: its step engine stopped again after the platform resumed it once; it is closed as interrupted.",
                        run.getJobQueueId());
                    return false;
                }
                Optional<SourceJob> job = this.jobs.findById(run.getJobId());
                if (!job.isPresent()) {
                    return false;
                }
                this.jdbc.update("UPDATE step_execution SET status = 'Interrupt', status_message = ?, date_updated = now() WHERE job_queue_id = ? "
                    + "AND status IN ('Queue', 'Start', 'Running')", "Stopped: Core restarted, or lost its database, while this step ran; the run is resumed from it.",
                    run.getJobQueueId());
                RunRetries.Outcome outcome = this.retries.retry(run, job.get(), false, RunRetries.Actor.platform());
                if (outcome.refusalKind != null) {
                    logger.info("Run {}: not resumed after Core restarted ({}: {}); it is closed as interrupted.", run.getJobQueueId(),
                        outcome.refusalKind, outcome.refusal);
                    return false;
                }
                return true;
            });
        } catch (RuntimeException failed) {
            logger.warn("Run {}: could not be resumed after Core restarted: {}", run.getJobQueueId(), failed.getMessage());
            return false;
        }
    }
}

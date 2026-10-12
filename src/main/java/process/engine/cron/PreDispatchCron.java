package process.engine.cron;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import process.engine.PreDispatchPhase;

/**
 * Drives the pre-dispatch phase (MIG-134) every {@code process.predispatch.delay-ms} after the last pass ended. No ShedLock:
 * every replica runs it, and they share its work through SKIP LOCKED claims and leases. Switched off with the other
 * schedulers where process.scheduling.enabled is false.
 *
 * MIG-326: two seconds, not ten. A step pipeline's run waits for this pass before the engine takes it, so at ten seconds a
 * run started through the API took 7-10 s end to end for 50 ms of steps (measured: p50 9.8 s at 1 to 8 runs at once). The
 * claim reads in-flight runs only, through the partial index ux_job_queue_one_in_flight_per_job: 0.05 ms at a million
 * runs (EXPLAIN ANALYZE, MIG-326), so a pass with nothing to do costs next to nothing.
 *
 * @author Nabeel Ahmed
 */
@ConditionalOnProperty(name = "process.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class PreDispatchCron {

    private static final Logger logger = LoggerFactory.getLogger(PreDispatchCron.class);

    private final PreDispatchPhase preDispatchPhase;

    public PreDispatchCron(PreDispatchPhase preDispatchPhase) {
        this.preDispatchPhase = preDispatchPhase;
    }

    @Scheduled(initialDelay = 5000, fixedDelayString = "${process.predispatch.delay-ms:2000}")
    public void prepareQueuedRuns() {
        try {
            this.preDispatchPhase.runPass();
        } catch (Exception e) {
            logger.error("Error in the pre-dispatch pass: {}", e.getMessage(), e);
        }
    }
}

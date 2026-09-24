package process.engine.cron;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import process.engine.PreDispatchPhase;

/**
 * Drives the pre-dispatch phase every ten seconds (MIG-134). No ShedLock: every replica runs it, and
 * they share its work through SKIP LOCKED claims and leases. Switched off with the other schedulers
 * where process.scheduling.enabled is false.
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

    @Scheduled(initialDelay = 5000, fixedDelay = 10000)
    public void prepareQueuedRuns() {
        try {
            this.preDispatchPhase.runPass();
        } catch (Exception e) {
            logger.error("Error in the pre-dispatch pass: {}", e.getMessage(), e);
        }
    }
}

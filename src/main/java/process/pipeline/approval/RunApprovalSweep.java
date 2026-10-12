package process.pipeline.approval;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * MIG-346: every minute, one replica at a time (ShedLock), {@link RunApprovals#sweep}: approval requests past their time
 * expire (their runs fail), and those whose run was closed are cancelled (their inbox tasks closed). Off with the other
 * schedulers where process.scheduling.enabled is false.
 */
@ConditionalOnProperty(name = "process.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class RunApprovalSweep {

    private static final Logger logger = LoggerFactory.getLogger(RunApprovalSweep.class);

    private final RunApprovals approvals;

    public RunApprovalSweep(RunApprovals approvals) {
        this.approvals = approvals;
    }

    @Scheduled(initialDelayString = "${process.approvals.initial-delay-ms:30000}", fixedDelayString = "${process.approvals.sweep-ms:60000}")
    @SchedulerLock(name = "runApprovalSweep", lockAtMostFor = "5M")
    public void sweepQuietly() {
        try {
            int[] swept = this.approvals.sweep(Instant.now());
            if (swept[0] + swept[1] > 0) {
                logger.info("Approvals: {} request(s) expired, {} cancelled (their runs were closed).", swept[0], swept[1]);
            }
        } catch (RuntimeException failed) {
            logger.warn("The approval sweep failed; it tries again: {}", failed.getMessage());
        }
    }
}

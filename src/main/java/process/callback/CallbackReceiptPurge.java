package process.callback;

import process.util.BusinessTime;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Deletes callback receipts past their retention, hourly (MIG-18). Switched off with the rest of the
 * schedulers where process.scheduling.enabled is false, as ProcessCron is.
 *
 * @author Nabeel Ahmed
 */
@ConditionalOnProperty(name = "process.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class CallbackReceiptPurge {

    private static final Logger logger = LoggerFactory.getLogger(CallbackReceiptPurge.class);

    private final CallbackReceipts receipts;

    public CallbackReceiptPurge(CallbackReceipts receipts) {
        this.receipts = receipts;
    }

    @Scheduled(initialDelay = 90000, fixedDelay = 60 * 60 * 1000)
    @SchedulerLock(name = "purgeCallbackReceipts", lockAtLeastFor = "5S", lockAtMostFor = "5M")
    public void purge() {
        try {
            int purged = this.receipts.purgeReceivedBefore(BusinessTime.now().minusDays(CallbackReceipts.RETENTION_DAYS));
            if (purged > 0) {
                logger.info("Purged {} worker callback receipt(s) older than {} days.", purged, CallbackReceipts.RETENTION_DAYS);
            }
        } catch (RuntimeException failed) {
            logger.warn("Worker callback receipt purge failed: {}", failed.getMessage());
        }
    }
}

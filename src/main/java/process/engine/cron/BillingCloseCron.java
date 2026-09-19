package process.engine.cron;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import process.billing.BillingService;
import process.billing.MeterClient;

import java.time.YearMonth;
import java.util.Map;

/**
 * The 1st of the month at 08:00: a draft for every workspace for the month just ended, built
 * from the meter. Issuing stays a person's act. Daily at 06:00: issued invoices past due become
 * overdue. Both are safe to run twice.
 */
@Component
public class BillingCloseCron {

    private static final Logger logger = LoggerFactory.getLogger(BillingCloseCron.class);
    private final BillingService billing;
    private final MeterClient meter;

    public BillingCloseCron(BillingService billing, MeterClient meter) { this.billing = billing; this.meter = meter; }

    @Scheduled(cron = "${billing.close.cron:0 0 8 1 * *}")
    @SchedulerLock(name = "billingCloseMonth", lockAtLeastFor = "1M", lockAtMostFor = "1H")
    public void closeLastMonth() {
        if (!this.meter.isConfigured()) {
            return;
        }
        YearMonth last = YearMonth.now().minusMonths(1);
        int drafted = 0;
        for (Map.Entry<Long, String> tenant : this.billing.tenantNames().entrySet()) {
            try {
                this.billing.draft(tenant.getKey(), last);
                drafted++;
            } catch (RuntimeException ex) {
                logger.warn("billing: draft for {} ({}) failed: {}", tenant.getValue(), tenant.getKey(), ex.toString());
            }
        }
        logger.info("billing: {} draft(s) for {}", drafted, last);
    }

    @Scheduled(cron = "${billing.dunning.cron:0 0 6 * * *}")
    @SchedulerLock(name = "billingDunning", lockAtLeastFor = "1M", lockAtMostFor = "30M")
    public void markOverdue() {
        int marked = this.billing.markOverdue();
        if (marked > 0) {
            logger.info("billing: {} invoice(s) now overdue", marked);
        }
    }
}

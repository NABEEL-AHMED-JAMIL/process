package process.engine.cron;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import process.analytics.AnalyticsLimits;
import process.model.repository.AnalyticsQueryRunRepository;

import java.sql.Timestamp;
import java.util.concurrent.TimeUnit;

/**
 * Removes analytics run history older than the configured retention window.
 *
 * <b>Does nothing unless somebody has set a retention window.</b> That is the whole shape of this
 * class, and it is deliberate. V32__analytics_query.sql argued at length for leaving
 * analytics_query_run unpruned -- "who read that bucket, and when" is asked weeks later by
 * somebody who was not there, a table that silently discarded the answer would answer wrongly
 * while looking like it had answered, and losing rows is the one thing that cannot be undone
 * afterwards. None of that stopped being true, so this ships the MECHANISM document 15 asks for
 * and leaves the POLICY to whoever owns the audit question.
 *
 * What has changed since that changeset is the rate: history is now written for every dataset
 * read and not only for statements somebody typed, so a reader paging a 1,500-page dataset leaves
 * 1,500 rows. That is a large multiple of the old rate and still a person clicking something --
 * not the "machine issuing queries on a schedule" the changeset named as the condition for
 * changing its mind. Hence available, and off.
 *
 * <b>@SchedulerLock, because a delete is not idempotent in the way a read is.</b> Two instances
 * running this at once would both compute a cut-off and both issue a DELETE; the second finds
 * nothing and costs a table scan, which is harmless but pointless. The lock also keeps the log
 * honest -- "removed 12,000 rows" printed twice for one deletion is how a person concludes the
 * retention window is wrong.
 *
 * @author Nabeel Ahmed
 */
@Component
public class AnalyticsHistoryCleanupCron {

    private final Logger logger = LogManager.getLogger(AnalyticsHistoryCleanupCron.class);

    private final AnalyticsQueryRunRepository analyticsQueryRunRepository;
    private final AnalyticsLimits analyticsLimits;

    public AnalyticsHistoryCleanupCron(AnalyticsQueryRunRepository analyticsQueryRunRepository,
        AnalyticsLimits analyticsLimits) {
        this.analyticsQueryRunRepository = analyticsQueryRunRepository;
        this.analyticsLimits = analyticsLimits;
    }

    /**
     * The schedule is fixed at hourly and the WINDOW decides whether anything happens.
     *
     * @Scheduled needs a compile-time constant, so the interval cannot be read from
     * AnalyticsLimits here. Waking hourly and returning immediately costs nothing measurable, and
     * it means changing analytics.history.cleanup-interval-hours takes effect without the
     * property having to be resolvable before the bean exists.
     *
     * The initial delay keeps this off the startup path: an application coming up under load has
     * better things to do in its first minute than a retention delete.
     */
    @Scheduled(initialDelay = 60000, fixedDelay = 60 * 60 * 1000)
    @SchedulerLock(name = "analyticsHistoryCleanup", lockAtLeastFor = "30S", lockAtMostFor = "10M")
    @Transactional
    public void removeExpiredHistory() {
        int days = this.analyticsLimits.getHistoryRetentionDays();
        if (days <= 0) {
            // The default. Not logged: a line every hour saying nothing happened is how a log
            // stops being read.
            return;
        }
        try {
            long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
            int removed = this.analyticsQueryRunRepository
                .deleteByDateCreatedBefore(new Timestamp(cutoff));
            if (removed > 0) {
                // Logged at INFO and with the window named, because this is destructive and the
                // number is the only evidence afterwards that the policy is set where somebody
                // meant it to be.
                this.logger.info("Analytics history cleanup removed {} run rows older than {} days.",
                    removed, days);
            }
        } catch (Exception ex) {
            // Swallowed on purpose. A retention delete that fails must not take down the
            // scheduler that also runs the job engine, and the next hour tries again.
            this.logger.error("An error occurred while removing expired analytics run history.", ex);
        }
    }
}

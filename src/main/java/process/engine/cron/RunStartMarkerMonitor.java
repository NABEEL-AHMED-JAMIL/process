package process.engine.cron;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import process.model.enums.JobAuditMarker;
import process.settings.OrchestrationSettings;
import process.settings.Watermark;
import process.util.BusinessTime;
import process.util.OpenSearchAuditLogClient;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * How many finished runs the run report cannot time (MIG-77, criterion 4).
 *
 * The report's exec_seconds comes from the run's JobAuditMarker.JOB_STARTED audit line, matched exactly; a run
 * without one reports -1, and nothing anywhere says so -- in September 2026, 69% of finished runs had none,
 * because only the retired Python listener wrote it. This measures that share among the runs that finished in
 * the last window-hours and publishes it:
 * <ul>
 *   <li>{@value #MISSING_RATIO}: unmarked / finished, 0 to 1; NaN before the first measurement and when nothing
 *       finished.</li>
 *   <li>{@value #FINISHED}: the sample size, so a share of 1.0 over one run reads as what it is.</li>
 * </ul>
 * and WARNs when the share is over warn-ratio on at least min-runs runs. Some unmarked runs are legitimate (the
 * stall sweep fails a run no worker ever picked up), hence a threshold rather than zero.
 *
 * Cheap by construction: one query per tick, from existing indexes (job_queue's Chicago-day index narrows the
 * candidates, the audit log's run index answers "is the line there"), on the clock under ShedLock so one replica
 * measures per tick. A replica that did not hold the lock keeps its last value (NaN if it never measured), so
 * read the gauge with max() across replicas.
 *
 * The window ends where job_audit_logs is complete. With OpenSearch on, audit lines land in OpenSearch and reach
 * job_audit_logs (the table the report reads) only when AuditLogSyncCron copies them, every four hours, with their
 * original time -- so the window ends at that sync's watermark, not now; before the first sync there is nothing
 * to measure. The candidates are runs enqueued (Chicago day) at most a day before the window starts: a run that
 * waited longer than that before finishing is not counted.
 *
 * @author Nabeel Ahmed
 */
@ConditionalOnProperty(name = "process.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class RunStartMarkerMonitor {

    public static final String MISSING_RATIO = "process.runs.start_marker.missing.ratio";
    public static final String FINISHED = "process.runs.start_marker.finished";

    /**
     * Finished runs in [from, to) and those of them with no exact marker line. The day predicate is written as
     * idx_job_queue_date_created_day's expression, so the planner can use it. Arguments: queryArguments.
     */
    static final String QUERY = "select count(*) as finished, "
        + "count(*) filter (where not exists (select 1 from job_audit_logs a "
        + "where a.job_queue_id = q.job_queue_id and a.log_detail = ?)) as unmarked "
        + "from job_queue q "
        + "where cast(q.date_created at time zone 'America/Chicago' as date) >= ? "
        + "and q.end_time >= ? and q.end_time < ? "
        + "and q.job_status in ('Completed', 'Failed')";

    private static final Logger logger = LogManager.getLogger(RunStartMarkerMonitor.class);

    /** Two figures, one query. Package-private for the tests. */
    static final class Sample {

        final long finished;
        final long unmarked;

        Sample(long finished, long unmarked) {
            this.finished = finished;
            this.unmarked = unmarked;
        }

        double ratio() {
            return this.finished == 0 ? Double.NaN : (double) this.unmarked / this.finished;
        }
    }

    private final JdbcTemplate sql;
    private final OpenSearchAuditLogClient openSearch;
    private final OrchestrationSettings settings;
    private final Duration window;
    private final double warnRatio;
    private final long minRuns;
    private final Clock clock;
    private final AtomicReference<Double> ratio = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> finished = new AtomicReference<>(Double.NaN);

    @Autowired
    public RunStartMarkerMonitor(JdbcTemplate sql, OpenSearchAuditLogClient openSearch, OrchestrationSettings settings,
        MeterRegistry registry,
        @Value("${process.run-start-marker.window-hours:24}") long windowHours,
        @Value("${process.run-start-marker.warn-ratio:0.10}") double warnRatio,
        @Value("${process.run-start-marker.min-runs:10}") long minRuns) {
        this(sql, openSearch, settings, registry, windowHours, warnRatio, minRuns, Clock.systemUTC());
    }

    RunStartMarkerMonitor(JdbcTemplate sql, OpenSearchAuditLogClient openSearch, OrchestrationSettings settings,
        MeterRegistry registry, long windowHours, double warnRatio, long minRuns, Clock clock) {
        this.sql = sql;
        this.openSearch = openSearch;
        this.settings = settings;
        this.window = Duration.ofHours(windowHours);
        this.warnRatio = warnRatio;
        this.minRuns = minRuns;
        this.clock = clock;
        Gauge.builder(MISSING_RATIO, this.ratio, AtomicReference::get)
            .description("Share of runs finished in the window with no 'Job started' audit line (run report exec_seconds -1)")
            .register(registry);
        Gauge.builder(FINISHED, this.finished, AtomicReference::get)
            .description("Runs finished in the window that the start-marker share is measured over")
            .register(registry);
    }

    /** Every quarter-hour, off the other crons' minutes; a failure is logged and the gauges keep their last value. */
    @Scheduled(cron = "${process.run-start-marker.cron:0 5/15 * * * *}")
    @SchedulerLock(name = "measureRunStartMarkers", lockAtLeastFor = "30S", lockAtMostFor = "5M")
    public void measure() {
        try {
            this.measureOnce();
        } catch (RuntimeException failed) {
            logger.warn("Could not measure the runs missing the '{}' audit line: {}",
                JobAuditMarker.JOB_STARTED.logDetail(), failed.getMessage());
        }
    }

    /** @return whether it warned */
    boolean measureOnce() {
        Optional<Instant> end = this.windowEnd();
        if (!end.isPresent()) {
            logger.info("Not measuring the '{}' audit line yet: OpenSearch holds the audit log and it has not been "
                + "synced to job_audit_logs.", JobAuditMarker.JOB_STARTED.logDetail());
            return false;
        }
        Instant to = end.get();
        Instant from = to.minus(this.window);
        Sample sample = this.sample(from, to);
        boolean warned = this.record(sample);
        if (warned) {
            logger.warn("{} of {} runs finished between {} and {} have no '{}' audit line ({}%, over the {}% threshold): "
                    + "the run report shows exec_seconds -1 for them. Is a worker not sending it as its Running message "
                    + "(WORKER-CONTRACT.md section 4.1)?", sample.unmarked, sample.finished, from, to,
                JobAuditMarker.JOB_STARTED.logDetail(), Math.round(sample.ratio() * 100), Math.round(this.warnRatio * 100));
        }
        return warned;
    }

    /** Where job_audit_logs is complete: now, or with OpenSearch on, the audit sync's watermark (never past now). */
    Optional<Instant> windowEnd() {
        Instant now = this.clock.instant();
        if (!this.openSearch.isEnabled()) {
            return Optional.of(now);
        }
        Optional<String> watermark = this.settings.value(Watermark.AUDIT_LOG_SYNC_LAST_RUN_TIME.name());
        if (!watermark.isPresent()) {
            return Optional.empty();
        }
        try {
            Instant synced = Instant.parse(watermark.get());
            return Optional.of(synced.isAfter(now) ? now : synced);
        } catch (DateTimeParseException unreadable) {
            logger.warn("The audit sync's watermark '{}' is not an instant; not measuring.", watermark.get());
            return Optional.empty();
        }
    }

    Sample sample(Instant from, Instant to) {
        Map<String, Object> row = this.sql.queryForMap(QUERY, queryArguments(from, to));
        return new Sample(((Number) row.get("finished")).longValue(), ((Number) row.get("unmarked")).longValue());
    }

    static Object[] queryArguments(Instant from, Instant to) {
        Date enqueuedFrom = Date.valueOf(from.minus(Duration.ofDays(1)).atZone(BusinessTime.ZONE).toLocalDate());
        return new Object[] { JobAuditMarker.JOB_STARTED.logDetail(), enqueuedFrom, Timestamp.from(from), Timestamp.from(to) };
    }

    /** Publishes the sample; @return whether the share is over the threshold on enough runs to mean something. */
    boolean record(Sample sample) {
        this.finished.set((double) sample.finished);
        this.ratio.set(sample.ratio());
        return sample.finished >= this.minRuns && sample.ratio() > this.warnRatio;
    }
}

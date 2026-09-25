package process.slo;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The stored-data side of the pipeline execution SLI, as gauges (MIG-196): RunSloReport over the trailing
 * window-hours, every quarter-hour, published as {@value #GOOD}, {@value #BAD} and {@value #EXCLUDED}. The counter
 * (RunOutcomes) is what the burn-rate alerts read; these are the rows' own answer over the same kind of window, so a
 * path that closes runs without counting them shows up as the two disagreeing.
 *
 * RunStartMarkerMonitor's pattern (MIG-77): one indexed query per tick (idx_job_queue_ended_at), under ShedLock so
 * one replica measures; a replica that did not hold the lock keeps its last value (NaN until it first measures), so
 * read the gauges with max() across replicas.
 */
@ConditionalOnProperty(name = "process.scheduling.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class RunSloMonitor {

    public static final String GOOD = "process.slo.runs.good";
    public static final String BAD = "process.slo.runs.bad";
    public static final String EXCLUDED = "process.slo.runs.excluded";

    private static final Logger logger = LoggerFactory.getLogger(RunSloMonitor.class);

    private final RunSloReport report;
    private final Duration window;
    private final Clock clock;
    private final AtomicReference<Double> good = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> bad = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> excluded = new AtomicReference<>(Double.NaN);

    @Autowired
    public RunSloMonitor(RunSloReport report, MeterRegistry registry,
        @Value("${process.slo.monitor.window-hours:24}") long windowHours) {
        this(report, registry, windowHours, Clock.systemUTC());
    }

    RunSloMonitor(RunSloReport report, MeterRegistry registry, long windowHours, Clock clock) {
        this.report = report;
        this.window = Duration.ofHours(windowHours);
        this.clock = clock;
        Gauge.builder(GOOD, this.good, AtomicReference::get)
            .description("Runs that ended good in the trailing window, from job_queue (pipeline execution SLI)").register(registry);
        Gauge.builder(BAD, this.bad, AtomicReference::get)
            .description("Runs that ended bad in the trailing window, from job_queue (pipeline execution SLI)").register(registry);
        Gauge.builder(EXCLUDED, this.excluded, AtomicReference::get)
            .description("Runs that ended outside the SLI in the trailing window, from job_queue").register(registry);
    }

    /** Every quarter-hour, off the other monitors' minutes; a failure is logged and the gauges keep their last value. */
    @Scheduled(cron = "${process.slo.monitor.cron:0 10/15 * * * *}")
    @SchedulerLock(name = "measureRunSlo", lockAtLeastFor = "30S", lockAtMostFor = "5M")
    public void measure() {
        try {
            this.measureOnce();
        } catch (RuntimeException failed) {
            logger.warn("Could not measure the pipeline execution SLI from job_queue: {}", failed.getMessage());
        }
    }

    RunSloReport.Window measureOnce() {
        Instant to = this.clock.instant();
        RunSloReport.Window measured = this.report.measure(to.minus(this.window), to);
        this.good.set((double) measured.good);
        this.bad.set((double) measured.bad);
        this.excluded.set((double) measured.excluded);
        return measured;
    }
}

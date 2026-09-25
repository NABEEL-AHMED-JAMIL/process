package process.engine.cron;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.util.ReflectionTestUtils;
import process.settings.OrchestrationSettings;
import process.util.OpenSearchAuditLogClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MIG-77 criterion 4: the share of finished runs whose report row is the -1 sentinel, as a gauge, and a WARN
 * past a threshold. What is decided without a database: the window's end (the audit sync's reach), the gauges
 * and when to warn. RunStartMarkerMonitorPostgresTest runs the query itself.
 */
class RunStartMarkerMonitorTest {

    private static final Instant NOW = Instant.parse("2026-09-24T17:00:00Z");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OrchestrationSettings settings = mock(OrchestrationSettings.class);
    private final OpenSearchAuditLogClient openSearch = new OpenSearchAuditLogClient();

    private RunStartMarkerMonitor monitor() {
        return new RunStartMarkerMonitor(null, this.openSearch, this.settings, this.registry, 24, 0.10, 10,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private double gauge(String name) {
        return this.registry.get(name).gauge().value();
    }

    @Test
    void withoutOpenSearchEveryLineIsInTheDatabaseAtOnceSoTheWindowEndsNow() {
        assertThat(this.monitor().windowEnd()).contains(NOW);
    }

    /**
     * With OpenSearch on, a line reaches job_audit_logs only when AuditLogSyncCron copies it (every four hours),
     * so a run finished after the sync's watermark would read as unmarked when it is not: the window ends there.
     */
    @Test
    void withOpenSearchTheWindowEndsAtTheAuditSyncsWatermark() {
        ReflectionTestUtils.setField(this.openSearch, "baseUrl", "http://opensearch:9200");
        when(this.settings.value("AUDIT_LOG_SYNC_LAST_RUN_TIME")).thenReturn(Optional.of("2026-09-24T14:10:00Z"));

        assertThat(this.monitor().windowEnd()).contains(Instant.parse("2026-09-24T14:10:00Z"));
    }

    @Test
    void withOpenSearchAndNoSyncYetThereIsNoWindowToMeasure() {
        ReflectionTestUtils.setField(this.openSearch, "baseUrl", "http://opensearch:9200");
        when(this.settings.value("AUDIT_LOG_SYNC_LAST_RUN_TIME")).thenReturn(Optional.empty());

        assertThat(this.monitor().windowEnd()).isEmpty();
    }

    @Test
    void aWatermarkAheadOfTheClockIsCappedAtNow() {
        ReflectionTestUtils.setField(this.openSearch, "baseUrl", "http://opensearch:9200");
        when(this.settings.value("AUDIT_LOG_SYNC_LAST_RUN_TIME")).thenReturn(Optional.of("2026-09-25T00:00:00Z"));

        assertThat(this.monitor().windowEnd()).contains(NOW);
    }

    @Test
    void theGaugesSayNothingUntilTheFirstMeasurement() {
        this.monitor();

        assertThat(this.gauge(RunStartMarkerMonitor.MISSING_RATIO)).isNaN();
        assertThat(this.gauge(RunStartMarkerMonitor.FINISHED)).isNaN();
    }

    @Test
    void aMeasurementSetsTheShareAndTheSampleSize() {
        RunStartMarkerMonitor monitor = this.monitor();

        monitor.record(new RunStartMarkerMonitor.Sample(40, 10));

        assertThat(this.gauge(RunStartMarkerMonitor.MISSING_RATIO)).isEqualTo(0.25);
        assertThat(this.gauge(RunStartMarkerMonitor.FINISHED)).isEqualTo(40.0);
    }

    @Test
    void noFinishedRunsIsNoShareNotAZeroOne() {
        RunStartMarkerMonitor monitor = this.monitor();

        assertThat(monitor.record(new RunStartMarkerMonitor.Sample(0, 0))).isFalse();

        assertThat(this.gauge(RunStartMarkerMonitor.MISSING_RATIO)).isNaN();
        assertThat(this.gauge(RunStartMarkerMonitor.FINISHED)).isZero();
    }

    @Test
    void itWarnsWhenTheShareIsOverTheThresholdOnEnoughRuns() {
        assertThat(this.monitor().record(new RunStartMarkerMonitor.Sample(10, 2))).isTrue();
    }

    @Test
    void itDoesNotWarnAtTheThresholdItself() {
        assertThat(this.monitor().record(new RunStartMarkerMonitor.Sample(10, 1))).isFalse();
    }

    /** Two runs, one unmarked, is 50% of nothing much: the gauge shows it, the log does not cry wolf. */
    @Test
    void itDoesNotWarnOnTooFewRuns() {
        assertThat(this.monitor().record(new RunStartMarkerMonitor.Sample(9, 9))).isFalse();
    }

    /** One replica measures per tick, under the same ShedLock pattern as the other single-instance crons. */
    @Test
    void itRunsOnTheClockUnderShedLockAndOnlyWhereSchedulingIsOn() throws Exception {
        SchedulerLock lock = RunStartMarkerMonitor.class.getMethod("measure").getAnnotation(SchedulerLock.class);
        Scheduled scheduled = RunStartMarkerMonitor.class.getMethod("measure").getAnnotation(Scheduled.class);
        ConditionalOnProperty condition = RunStartMarkerMonitor.class.getAnnotation(ConditionalOnProperty.class);

        assertThat(lock.name()).isEqualTo("measureRunStartMarkers");
        assertThat(scheduled.cron()).isNotEmpty();
        assertThat(condition.name()).containsExactly("process.scheduling.enabled");
    }
}

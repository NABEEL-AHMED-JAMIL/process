package process.slo;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import process.model.enums.JobStatus;
import process.model.enums.RunEnd;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIG-196: POST /internal/slo/runs -- the internal token, the window's defaults and bounds -- and the figures the
 * monitor publishes as gauges.
 */
class InternalSloRestApiTest {

    private static final Instant NOW = Instant.parse("2026-09-24T17:00:00Z");

    private final RunSloReport report = mock(RunSloReport.class);
    private final InternalSloRestApi api = new InternalSloRestApi(this.report, "s3cret", Clock.fixed(NOW, ZoneOffset.UTC));

    private static RunSloReport.Window window(Instant from, Instant to) {
        List<RunSloReport.Group> groups = Arrays.asList(
            new RunSloReport.Group(JobStatus.Completed, RunEnd.WORKER, 9998),
            new RunSloReport.Group(JobStatus.Interrupt, RunEnd.STALLED, 2),
            new RunSloReport.Group(JobStatus.Skip, RunEnd.SKIPPED, 40),
            new RunSloReport.Group(JobStatus.Failed, null, 0));
        return new RunSloReport.Window(from, to, groups);
    }

    private static Map<String, Object> body(String from, String to) {
        Map<String, Object> body = new HashMap<>();
        body.put("from", from);
        body.put("to", to);
        return body;
    }

    @Test
    void itIsAPostUnderInternalWhichTheSecurityConfigLetsThroughToTheTokenCheck() throws Exception {
        assertThat(InternalSloRestApi.class.getMethod("runs", String.class, Map.class).getAnnotation(PostMapping.class).value())
            .containsExactly("/runs");
    }

    @Test
    void noTokenOrTheWrongOneIsRefusedBeforeAnythingIsRead() {
        assertThat(this.api.runs(null, null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(this.api.runs("guess", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(new InternalSloRestApi(this.report, "", Clock.systemUTC()).runs("", null).getStatusCode())
            .as("an unconfigured token refuses everyone").isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(this.report, never()).measure(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aWindowIsMeasuredAndAnsweredWithItsBudget() {
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = Instant.parse("2026-09-08T00:00:00Z");
        when(this.report.measure(from, to)).thenReturn(window(from, to));
        ResponseEntity<?> answer = this.api.runs("s3cret", body(from.toString(), to.toString()));
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> figures = (Map<String, Object>) answer.getBody();
        assertThat(figures).containsEntry("good", 9998L).containsEntry("bad", 2L).containsEntry("excluded", 40L)
            .containsEntry("target", 0.9999).containsEntry("successRate", 0.9998);
        assertThat((Double) figures.get("errorBudgetRuns")).isCloseTo(1.0, Offset.offset(1e-9));
        assertThat((Double) figures.get("budgetSpent")).isCloseTo(2.0, Offset.offset(1e-9));
        assertThat((List<Map<String, Object>>) figures.get("groups")).extracting(g -> g.get("reason"))
            .containsExactly("WORKER", "STALLED", "SKIPPED", "UNATTRIBUTED");
    }

    @Test
    void withNoBoundsItIsTheTwentyEightDaysToNow() {
        when(this.report.measure(NOW.minus(Duration.ofDays(28)), NOW)).thenReturn(window(NOW.minus(Duration.ofDays(28)), NOW));
        assertThat(this.api.runs("s3cret", Collections.emptyMap()).getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(this.report).measure(NOW.minus(Duration.ofDays(28)), NOW);
    }

    @Test
    void anUnreadableBackwardsOrOverlongWindowIsABadRequest() {
        assertThat(this.api.runs("s3cret", body("yesterday", null)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(this.api.runs("s3cret", body("2026-09-08T00:00:00Z", "2026-09-01T00:00:00Z")).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(this.api.runs("s3cret", body("2025-01-01T00:00:00Z", "2026-09-01T00:00:00Z")).getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(this.report, never()).measure(any(), any());
    }

    @Test
    void anEmptyWindowHasNoRateAndNoBudget() {
        RunSloReport.Window empty = new RunSloReport.Window(NOW.minusSeconds(60), NOW, Collections.emptyList());
        assertThat(empty.successRate()).isNull();
        assertThat(empty.budgetSpent()).isNull();
        assertThat(empty.errorBudget()).isZero();
    }

    @Test
    void theMonitorPublishesTheTrailingWindowsFigures() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RunSloMonitor monitor = new RunSloMonitor(this.report, registry, 24, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(registry.get(RunSloMonitor.BAD).gauge().value()).as("before it first measures").isNaN();
        when(this.report.measure(NOW.minus(Duration.ofHours(24)), NOW)).thenReturn(window(NOW.minus(Duration.ofHours(24)), NOW));
        monitor.measure();
        assertThat(registry.get(RunSloMonitor.GOOD).gauge().value()).isEqualTo(9998);
        assertThat(registry.get(RunSloMonitor.BAD).gauge().value()).isEqualTo(2);
        assertThat(registry.get(RunSloMonitor.EXCLUDED).gauge().value()).isEqualTo(40);
        when(this.report.measure(any(), any())).thenThrow(new IllegalStateException("database away"));
        monitor.measure();
        assertThat(registry.get(RunSloMonitor.BAD).gauge().value()).as("a failed measurement keeps the last").isEqualTo(2);
    }
}

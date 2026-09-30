package process.slo;

import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import process.model.dto.ResponseDto;
import process.model.enums.JobStatus;
import process.model.enums.RunEnd;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MIG-196: the console's Reliability endpoints -- platform administrators only, the window's defaults and bounds. */
class ReliabilityRestApiTest {

    private static final Instant NOW = Instant.parse("2026-09-29T17:00:00Z");

    private final RunSloReport report = mock(RunSloReport.class);
    private final SloBurnRates burnRates = mock(SloBurnRates.class);
    private final ReliabilityRestApi api = new ReliabilityRestApi(this.report, this.burnRates, Clock.fixed(NOW, ZoneOffset.UTC));

    private static RunSloReport.Window window(Instant from, Instant to) {
        return new RunSloReport.Window(from, to, Arrays.asList(
            new RunSloReport.Group(JobStatus.Completed, RunEnd.WORKER, 19_998),
            new RunSloReport.Group(JobStatus.Failed, RunEnd.DECLINED, 1),
            new RunSloReport.Group(JobStatus.Interrupt, RunEnd.STALLED, 1),
            new RunSloReport.Group(JobStatus.Failed, RunEnd.AI_STEP, 7),
            new RunSloReport.Group(JobStatus.Skip, RunEnd.SKIPPED, 40)));
    }

    @Test
    void onlyAPlatformAdministratorMayAsk() throws Exception {
        assertThat(ReliabilityRestApi.class.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('PLATFORM_ADMIN')");
        assertThat(ReliabilityRestApi.class.getAnnotation(RequestMapping.class).value()).containsExactly("/reliability.json");
        assertThat(ReliabilityRestApi.class.getMethod("pipelineRuns", String.class, String.class).getAnnotation(GetMapping.class).value())
            .containsExactly("/pipelineRuns");
        assertThat(ReliabilityRestApi.class.getMethod("burnRates").getAnnotation(GetMapping.class).value()).containsExactly("/burnRates");
        // No method loosens the class's rule.
        assertThat(Arrays.stream(ReliabilityRestApi.class.getDeclaredMethods()).filter(m -> m.getAnnotation(PreAuthorize.class) != null))
            .isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void theDefaultIsTheSlosRolling28DaysWithItsDailySeries() {
        Instant from = NOW.minus(Duration.ofDays(28));
        when(this.report.measure(from, NOW)).thenReturn(window(from, NOW));
        RunSloReport.Day day = new RunSloReport.Day(LocalDate.parse("2026-09-28"));
        day.good = 19_998;
        day.bad = 2;
        day.excluded = 47;
        when(this.report.measureDaily(from, NOW)).thenReturn(Collections.singletonList(day));

        ResponseEntity<ResponseDto> answer = this.api.pipelineRuns(null, " ");
        assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> figures = (Map<String, Object>) answer.getBody().getData();
        assertThat(figures.get("numerator")).isEqualTo(19_998L);
        assertThat(figures.get("denominator")).isEqualTo(20_000L);
        assertThat(figures.get("successRate")).isEqualTo(0.9999);
        assertThat(figures.get("target")).isEqualTo(0.9999);
        // Two bad against a budget of two: none left.
        assertThat((Double) figures.get("errorBudgetRemaining")).isCloseTo(0.0, Offset.offset(1e-9));
        assertThat(figures.get("excluded")).isEqualTo(47L);
        List<Map<String, Object>> daily = (List<Map<String, Object>>) figures.get("daily");
        assertThat(daily).hasSize(1);
        assertThat(daily.get(0)).containsEntry("day", "2026-09-28").containsEntry("good", 19_998L).containsEntry("successRate", 0.9999);
    }

    @Test
    void anyPastWindowMayBeAskedFor() {
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-08-08T00:00:00Z");
        when(this.report.measure(from, to)).thenReturn(window(from, to));
        when(this.report.measureDaily(from, to)).thenReturn(Collections.emptyList());
        assertThat(this.api.pipelineRuns("2026-08-01T00:00:00Z", "2026-08-08T00:00:00Z").getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(this.report).measure(from, to);
    }

    @Test
    void aWindowThatIsNotOneIsRefused() {
        assertThat(this.api.pipelineRuns("yesterday", null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(this.api.pipelineRuns("2026-09-02T00:00:00Z", "2026-09-01T00:00:00Z").getBody().getMessage())
            .isEqualTo("from must be before to.");
        assertThat(this.api.pipelineRuns("2025-01-01T00:00:00Z", "2026-09-01T00:00:00Z").getBody().getMessage())
            .isEqualTo("Ask for at most 400 days at a time.");
        verify(this.report, never()).measure(any(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void theBurnRatesAreEveryRuleOfBothSlos() {
        SloBurnAlertsTest.FakeSli runs = new SloBurnAlertsTest.FakeSli("pipeline_execution", Duration.ZERO);
        runs.counts(Duration.ofHours(1), 9_980, 20).counts(Duration.ofMinutes(5), 798, 2);
        SloBurnRates real = new SloBurnRates(Collections.singletonList(runs), Clock.fixed(NOW, ZoneOffset.UTC));
        when(this.burnRates.evaluate()).thenReturn(real.evaluate());
        List<Map<String, Object>> rows = (List<Map<String, Object>>) this.api.burnRates().getBody().getData();
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0)).containsEntry("sli", "pipeline_execution").containsEntry("rule", "PAGE_1H")
            .containsEntry("severity", "page").containsEntry("threshold", 14.4).containsEntry("firing", true);
        assertThat((Map<String, Object>) rows.get(0).get("long")).containsEntry("window", "1h").containsEntry("bad", 20L);
        assertThat(rows.get(3)).containsEntry("rule", "TICKET_3D").containsEntry("firing", false);
    }
}

package process.analytics;

import org.junit.jupiter.api.Test;
import process.model.pojo.BenchmarkResult;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of the benchmark harness that NOTICES.
 *
 * Six rows in analytics_benchmark_result proved a number was captured once. Nothing compared the
 * next run to them, so a deployment that made Parquet reads four times slower would have written
 * its numbers down and said nothing. Document 15's "benchmark regression suite" row.
 *
 * The comparison is tested without a database on purpose: what is worth pinning is which rows are
 * comparable and which baseline is chosen, and both are decisions rather than measurements.
 *
 * @author Nabeel Ahmed
 */
class BenchmarkRegressionTest {

    private static BenchmarkResult row(String batch, String label, String measure, String format,
        long medianMs) {
        BenchmarkResult result = new BenchmarkResult();
        result.setBatchId(batch);
        result.setBenchmarkLabel(label);
        result.setMeasureKind(measure);
        result.setDatasetFormat(format);
        result.setDatasetPath("analytics-benchmark/sales-10mb." + format.toLowerCase());
        result.setMedianMs(medianMs);
        return result;
    }

    @Test
    void aSlowerRunAgainstTheSameMeasurementIsAregression() {
        List<BenchmarkResult> history =
            Collections.singletonList(row("b1", "10mb", "QUERY", "PARQUET", 50L));
        List<BenchmarkResult> batch =
            Collections.singletonList(row("b2", "10mb", "QUERY", "PARQUET", 200L));

        BenchmarkRegression.Verdict verdict = BenchmarkRegression.compare(batch, history).get(0);

        assertTrue(verdict.isRegressed());
        assertEquals(50L, verdict.getBaselineMs());
        assertTrue(verdict.describe().contains("REGRESSION"), verdict.describe());
    }

    @Test
    void noiseInsideTheToleranceIsNotAregression() {
        // These are real timings from a real network on whatever machine ran them. A check that
        // called a 20% swing on a laptop a regression would be switched off within a week.
        List<BenchmarkResult> history =
            Collections.singletonList(row("b1", "10mb", "QUERY", "CSV", 100L));
        List<BenchmarkResult> batch =
            Collections.singletonList(row("b2", "10mb", "QUERY", "CSV", 120L));

        assertFalse(BenchmarkRegression.compare(batch, history).get(0).isRegressed());
    }

    @Test
    void theBaselineIsTheBESTonRecord_soDriftCannotAccumulate() {
        // The property that makes this worth having. Against "the previous run", two runs each 15%
        // slower are each inside a 25% tolerance and the pair is 32% slower than where it started.
        // Against the best on record, the second run is measured from 100 and caught.
        List<BenchmarkResult> history = Arrays.asList(
            row("b1", "10mb", "QUERY", "CSV", 100L),
            row("b2", "10mb", "QUERY", "CSV", 115L));
        List<BenchmarkResult> batch =
            Collections.singletonList(row("b3", "10mb", "QUERY", "CSV", 132L));

        BenchmarkRegression.Verdict verdict = BenchmarkRegression.compare(batch, history).get(0);

        assertEquals(100L, verdict.getBaselineMs(), "baseline must be the best, not the latest");
        assertTrue(verdict.isRegressed());
    }

    @Test
    void aFileOpenIsNeverComparedWithAquery() {
        // FILE_OPEN opens three sessions and QUERY opens one, so their milliseconds are not the
        // same quantity. Folded together, a batch that changed its mix reports a 3x regression
        // that is only a change of question.
        List<BenchmarkResult> history =
            Collections.singletonList(row("b1", "10mb", "QUERY", "CSV", 100L));
        List<BenchmarkResult> batch =
            Collections.singletonList(row("b2", "10mb", "FILE_OPEN", "CSV", 900L));

        BenchmarkRegression.Verdict verdict = BenchmarkRegression.compare(batch, history).get(0);

        assertFalse(verdict.isRegressed());
        assertEquals(0L, verdict.getBaselineMs(), "a FILE_OPEN has no QUERY baseline");
        assertTrue(verdict.describe().contains("no baseline"), verdict.describe());
    }

    @Test
    void csvIsNeverComparedWithParquet() {
        // The comparison this whole harness exists to make. Folding the formats together would
        // average away the finding it was built to produce.
        List<BenchmarkResult> history =
            Collections.singletonList(row("b1", "10mb", "QUERY", "PARQUET", 50L));
        List<BenchmarkResult> batch =
            Collections.singletonList(row("b2", "10mb", "QUERY", "CSV", 242L));

        assertFalse(BenchmarkRegression.compare(batch, history).get(0).isRegressed(),
            "242ms CSV is not a regression against 50ms Parquet -- it is a different question");
    }

    @Test
    void aRunIsNeverComparedWithItself() {
        // A caller that reads history back AFTER writing its own rows would otherwise find its own
        // measurement as the baseline and conclude, every single time, that nothing had changed.
        List<BenchmarkResult> batch =
            Collections.singletonList(row("b2", "10mb", "QUERY", "CSV", 500L));
        List<BenchmarkResult> historyIncludingItself = Arrays.asList(
            row("b1", "10mb", "QUERY", "CSV", 100L),
            row("b2", "10mb", "QUERY", "CSV", 500L));

        BenchmarkRegression.Verdict verdict =
            BenchmarkRegression.compare(batch, historyIncludingItself).get(0);

        assertEquals(100L, verdict.getBaselineMs());
        assertTrue(verdict.isRegressed());
    }

    @Test
    void aFirstMeasurementSaysSoRatherThanPassingQuietly() {
        // "There is no baseline" and "it did not regress" look identical in a summary that omits
        // one of them, and the first is the one somebody needs to know before trusting a green run.
        List<BenchmarkResult> batch =
            Collections.singletonList(row("b1", "10mb", "QUERY", "CSV", 242L));

        BenchmarkRegression.Verdict verdict =
            BenchmarkRegression.compare(batch, Collections.<BenchmarkResult>emptyList()).get(0);

        assertFalse(verdict.isRegressed());
        assertTrue(verdict.describe().contains("first measurement"), verdict.describe());
    }

    @Test
    void animprovementIsReportedAsOne() {
        List<BenchmarkResult> history =
            Collections.singletonList(row("b1", "10mb", "QUERY", "CSV", 200L));
        List<BenchmarkResult> batch =
            Collections.singletonList(row("b2", "10mb", "QUERY", "CSV", 100L));

        BenchmarkRegression.Verdict verdict = BenchmarkRegression.compare(batch, history).get(0);

        assertFalse(verdict.isRegressed());
        assertTrue(verdict.describe().contains("FASTER"), verdict.describe());
    }

    // ---- the endpoint says it, not only the log ---------------------------------------------

    @Test
    void theEndpointNamesTheRegressedMeasurementInTheMessage() throws Exception {
        // "1 regression" sends somebody to a log that is on a server. The sentence carries both
        // numbers so the verdict can be argued with from the response alone.
        AnalyticsBenchmarkService service =
            org.mockito.Mockito.mock(AnalyticsBenchmarkService.class);
        org.mockito.Mockito.when(service.runAndCompare(org.mockito.ArgumentMatchers.any()))
            .thenReturn(BenchmarkRegression.compare(
                Collections.singletonList(row("b2", "10mb", "QUERY", "PARQUET", 200L)),
                Collections.singletonList(row("b1", "10mb", "QUERY", "PARQUET", 50L))));

        process.api.AnalyticsBenchmarkRestApi api =
            new process.api.AnalyticsBenchmarkRestApi(service, new AnalyticsLimits());
        org.springframework.http.ResponseEntity<?> response = api.runBenchmark(null);

        process.model.dto.ResponseDto body = (process.model.dto.ResponseDto) response.getBody();
        assertTrue(body.getMessage().contains("REGRESSION"), body.getMessage());
        assertTrue(body.getMessage().contains("50 ms"), body.getMessage());
        assertTrue(body.getMessage().contains("200 ms"), body.getMessage());
    }

    @Test
    void theEndpointDistinguishesNoBaselineFromNoRegression() throws Exception {
        // Different facts. Only one of them means the number can be trusted.
        AnalyticsBenchmarkService service =
            org.mockito.Mockito.mock(AnalyticsBenchmarkService.class);
        org.mockito.Mockito.when(service.runAndCompare(org.mockito.ArgumentMatchers.any()))
            .thenReturn(BenchmarkRegression.compare(
                Collections.singletonList(row("b1", "10mb", "QUERY", "CSV", 242L)),
                Collections.<BenchmarkResult>emptyList()));

        process.api.AnalyticsBenchmarkRestApi api =
            new process.api.AnalyticsBenchmarkRestApi(service, new AnalyticsLimits());
        process.model.dto.ResponseDto body =
            (process.model.dto.ResponseDto) api.runBenchmark(null).getBody();

        assertTrue(body.getMessage().contains("nothing to compare against yet"), body.getMessage());
    }
}

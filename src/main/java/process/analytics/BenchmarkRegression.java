package process.analytics;

import process.model.pojo.BenchmarkResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares a benchmark batch against the ones before it, and says what got slower.
 *
 * <b>Document 15's "benchmark regression suite" row. The harness measures; this is the half that
 * NOTICES.</b> Six rows in analytics_benchmark_result prove a number was captured once; nothing
 * compared the next run to them, so a deployment that made Parquet reads four times slower would
 * have written its numbers down and said nothing.
 *
 * <h3>What is comparable, and what is not</h3>
 *
 * Only rows sharing a label, a measure kind and a dataset path. Two of those are obvious; the
 * third is the one that bites. A FILE_OPEN measurement opens three sessions and a QUERY
 * measurement opens one, so their milliseconds are not the same quantity -- comparing them
 * produces a 3x "regression" every time the mix changes.
 *
 * <h3>Why the BEST previous median and not the last one</h3>
 *
 * A baseline of "the previous run" ratchets: two runs each 15% slower than the one before it are
 * each within a 20% tolerance, and the pair is 32% slower than where it started. Taking the best
 * median on record means drift has to be argued with rather than accumulated quietly. It also
 * means a single anomalously fast run raises the bar permanently, which is the correct direction
 * for a floor -- if the system managed it once, it can manage it.
 *
 * <h3>Why nothing here fails a build by itself</h3>
 *
 * These numbers come from a real object store over a real network on whatever machine ran them.
 * A 20% swing between two runs on a laptop with a browser open is not a regression, and a check
 * that cried wolf on that would be switched off within a week. This class reports; deciding what
 * to do about a report is the caller's, and the tolerance is theirs to set.
 *
 * @author Nabeel Ahmed
 */
public final class BenchmarkRegression {

    /**
     * How much slower than the best on record is tolerated before it is called a regression.
     *
     * 25%: wide enough to absorb a noisy laptop and a cold page cache, narrow enough that the 4.8x
     * and 9.8x CSV-to-Parquet ratios this module advertises cannot quietly invert. A number chosen
     * to be survivable rather than sensitive, because a check nobody trusts is a check nobody runs.
     */
    public static final double DEFAULT_TOLERANCE = 0.25d;

    private BenchmarkRegression() {}

    /** One measurement's comparison against its own history. */
    public static final class Verdict {

        private final String key;
        private final long medianMs;
        private final long baselineMs;
        private final double change;
        private final boolean regressed;

        Verdict(String key, long medianMs, long baselineMs, double change, boolean regressed) {
            this.key = key;
            this.medianMs = medianMs;
            this.baselineMs = baselineMs;
            this.change = change;
            this.regressed = regressed;
        }

        public String getKey() { return this.key; }
        public long getMedianMs() { return this.medianMs; }
        public long getBaselineMs() { return this.baselineMs; }

        /** Fraction slower than the baseline. 0.32 is 32% slower; negative is faster. */
        public double getChange() { return this.change; }

        public boolean isRegressed() { return this.regressed; }

        /** A sentence for a person, with both numbers in it so the verdict can be argued with. */
        public String describe() {
            long percent = Math.round(Math.abs(this.change) * 100);
            if (this.baselineMs <= 0) {
                return this.key + ": " + this.medianMs + " ms, first measurement -- no baseline yet.";
            }
            if (this.change < 0) {
                return this.key + ": " + this.medianMs + " ms, " + percent + "% FASTER than the "
                    + "best on record (" + this.baselineMs + " ms).";
            }
            return this.key + ": " + this.medianMs + " ms, " + percent + "% slower than the best on "
                + "record (" + this.baselineMs + " ms)" + (this.regressed ? " -- REGRESSION." : ".");
        }
    }

    /**
     * Compares a batch against history.
     *
     * @param batch   the rows just measured.
     * @param history every row previously recorded for these labels, in any order. Rows belonging
     *                to the batch itself are ignored if they appear here, so a caller that reads
     *                history back AFTER writing does not compare a run with itself and conclude
     *                nothing ever changes.
     * @param tolerance fraction slower that is still acceptable; see DEFAULT_TOLERANCE.
     */
    public static List<Verdict> compare(List<BenchmarkResult> batch, List<BenchmarkResult> history,
        double tolerance) {

        if (batch == null || batch.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, Long> best = new LinkedHashMap<>();
        if (history != null) {
            for (BenchmarkResult row : history) {
                if (row == null || row.getMedianMs() == null || row.getMedianMs() <= 0) {
                    continue;
                }
                if (isSameBatch(row, batch)) {
                    continue;
                }
                String key = keyOf(row);
                Long current = best.get(key);
                if (current == null || row.getMedianMs() < current) {
                    best.put(key, row.getMedianMs());
                }
            }
        }
        List<Verdict> verdicts = new ArrayList<>();
        for (BenchmarkResult row : batch) {
            if (row == null || row.getMedianMs() == null) {
                continue;
            }
            String key = keyOf(row);
            Long baseline = best.get(key);
            long median = row.getMedianMs();
            if (baseline == null) {
                // No history. Reported rather than skipped: "there is no baseline" and "it did not
                // regress" look identical in a summary that omits one of them.
                verdicts.add(new Verdict(key, median, 0L, 0d, false));
                continue;
            }
            double change = (median - baseline) / (double) baseline;
            verdicts.add(new Verdict(key, median, baseline, change, change > tolerance));
        }
        return verdicts;
    }

    public static List<Verdict> compare(List<BenchmarkResult> batch, List<BenchmarkResult> history) {
        return compare(batch, history, DEFAULT_TOLERANCE);
    }

    /**
     * What makes two measurements the same measurement.
     *
     * The measure kind is in the key because a FILE_OPEN opens three sessions and a QUERY opens
     * one; without it, a batch that changed its mix would report a 3x regression that is only a
     * change of question. The format is in it because CSV against Parquet is the comparison this
     * whole harness exists to make -- folding them together would average away the finding.
     */
    private static String keyOf(BenchmarkResult row) {
        return String.valueOf(row.getBenchmarkLabel())
            + " / " + String.valueOf(row.getMeasureKind())
            + " / " + String.valueOf(row.getDatasetFormat())
            + " / " + String.valueOf(row.getDatasetPath());
    }

    private static boolean isSameBatch(BenchmarkResult row, List<BenchmarkResult> batch) {
        String batchId = batch.get(0) == null ? null : batch.get(0).getBatchId();
        return batchId != null && batchId.equals(row.getBatchId());
    }
}

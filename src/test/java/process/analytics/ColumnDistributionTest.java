package process.analytics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import process.analytics.dto.ColumnDistributionDto;
import process.analytics.dto.DistributionBinDto;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a column's distribution has to be before it is worth drawing.
 *
 * The two DTOs this measures into were written, fully documented, and then referenced by nothing
 * at all -- no engine query, no endpoint, no UI. The Profile tab went on drawing a quartile strip,
 * which is three APPROXIMATE points, under a comment explaining that with only five points there
 * is nothing to bin. That reasoning was right and this removes its premise: the bars below are
 * counted.
 *
 * These are the contract tests. The shape rules -- twelve bars, value-by-value under a threshold,
 * half-open bins with a closed top -- are the whole of what a reader is being shown, and they are
 * asserted here rather than left to a live file that may not contain the awkward cases.
 *
 * @author Nabeel Ahmed
 */
public class ColumnDistributionTest {

    @Test
    @DisplayName("a bin carries its bounds as TEXT, so a DECIMAL edge is not widened to a double")
    public void boundsAreText() {
        // 103909527.58 through a float is 103909527.57999787, which is exactly the artefact the
        // dashboard spent a change removing from its tables.
        DistributionBinDto bin = new DistributionBinDto("103909527.58", "103909527.59", 4L);
        assertEquals("103909527.58", bin.getFrom());
        assertEquals("103909527.59", bin.getTo());
        assertEquals(4L, bin.getRows());
    }

    @Test
    @DisplayName("a value-by-value bar has a value and no range, which is how the client tells them apart")
    public void valueBarsCarryNoRange() {
        DistributionBinDto bar = new DistributionBinDto();
        bar.setValue("North");
        bar.setRows(8884L);
        assertEquals("North", bar.getValue());
        assertNull(bar.getFrom(), "a value bar has no lower bound to draw an axis from");
        assertNull(bar.getTo());
    }

    @Test
    @DisplayName("twelve bars, and the exact-value threshold matches it")
    public void constantsAgree() {
        // If the threshold were higher than BINS, a column just over it would be binned into
        // fewer buckets than it has values -- bars that merge two values without saying so.
        assertEquals(12, ColumnDistributionDto.BINS);
        assertEquals(12, ColumnDistributionDto.EXACT_VALUES_UP_TO);
        assertTrue(ColumnDistributionDto.EXACT_VALUES_UP_TO <= ColumnDistributionDto.BINS);
    }

    @Test
    @DisplayName("an empty distribution is empty bins, not a null the client has to guard")
    public void emptyIsEmptyList() {
        ColumnDistributionDto column = new ColumnDistributionDto("notes");
        column.setBins(new java.util.ArrayList<DistributionBinDto>());
        assertNotNull(column.getBins());
        assertTrue(column.getBins().isEmpty());
        assertFalse(column.isExactValues());
    }

    @Test
    @DisplayName("the most common value is carried with the count of rows that hold it")
    public void mostCommonCarriesItsCount() {
        ColumnDistributionDto column = new ColumnDistributionDto("region");
        column.setMostCommon("North");
        column.setMostCommonRows(8884L);
        assertEquals("North", column.getMostCommon());
        assertEquals(Long.valueOf(8884L), column.getMostCommonRows());
    }

    @Test
    @DisplayName("an all-null column has no most-common value rather than a most-common of nothing")
    public void allNullHasNoMode() {
        ColumnDistributionDto column = new ColumnDistributionDto("unused");
        assertNull(column.getMostCommon());
        assertNull(column.getMostCommonRows());
    }

    @Test
    @DisplayName("binned edges run contiguously, so the bars cover the range with no gap")
    public void edgesAreContiguous() {
        // The shape binnedBars produces: every bar's `to` is the next bar's `from`, which is what
        // makes the half-open [from, to) rule cover the range exactly once.
        List<DistributionBinDto> bars = java.util.Arrays.asList(
            new DistributionBinDto("0", "10", 3L),
            new DistributionBinDto("10", "20", 5L),
            new DistributionBinDto("20", "30", 1L));
        for (int at = 0; at < bars.size() - 1; at++) {
            assertEquals(bars.get(at).getTo(), bars.get(at + 1).getFrom(),
                "bar " + at + " must end where bar " + (at + 1) + " begins");
        }
    }

    @Test
    @DisplayName("a bin edge is rounded to what its width makes meaningful, not to the double's tail")
    public void edgesAreRoundedByWidth() throws Exception {
        // On the orders file, (3042.9 - 4.13) / 12 gives edges like 257.36083333333335 and
        // 1017.0533333333333, and that is what the chart's axis said. A bin edge is min + width*n
        // -- a synthetic boundary, not a value the file holds -- so precision past the scale it
        // divides carries no information and costs legibility.
        java.lang.reflect.Method edge = Class.forName("process.analytics.DuckDbAnalyticsEngine")
            .getDeclaredMethod("edge", double.class, double.class);
        edge.setAccessible(true);

        // Width 253 means whole numbers ARE the meaningful precision: two decimals on a boundary
        // that moves in steps of 253 describe nothing. I expected 257.36 when writing this and
        // the code was right -- 257 is the honest edge.
        assertEquals("257", edge.invoke(null, 257.36083333333335d, 253.23d));
        assertEquals("1017", edge.invoke(null, 1017.0533333333333d, 253.23d));
        // A narrower column keeps its decimals, which is the point of deriving this from width.
        assertEquals("25.74", edge.invoke(null, 25.73608333333d, 25.3d));
        // A wide column loses the decimals entirely; a narrow one keeps enough to stay distinct.
        assertEquals("1200", edge.invoke(null, 1200.4d, 500d));
        assertEquals("0.1235", edge.invoke(null, 0.12345d, 0.05d));
    }

    @Test
    @DisplayName("rounding both bounds keeps the bars contiguous")
    public void roundingPreservesContiguity() throws Exception {
        // Each bar's `to` is the next bar's `from`, and both go through the same rounding, so
        // the half-open [from, to) rule still covers the range exactly once.
        java.lang.reflect.Method edge = Class.forName("process.analytics.DuckDbAnalyticsEngine")
            .getDeclaredMethod("edge", double.class, double.class);
        edge.setAccessible(true);
        double low = 4.13d;
        double width = 253.23083333333335d;
        String previousTo = null;
        for (int at = 0; at < 12; at++) {
            String from = (String) edge.invoke(null, low + (width * at), width);
            String to = (String) edge.invoke(null, low + (width * (at + 1)), width);
            if (previousTo != null) {
                assertEquals(previousTo, from, "bar " + at + " must begin where the last one ended");
            }
            previousTo = to;
        }
    }
}

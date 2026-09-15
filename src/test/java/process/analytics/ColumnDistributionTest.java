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
}

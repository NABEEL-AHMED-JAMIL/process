package process.analytics.dto;

import java.util.List;

/**
 * What a column's values actually look like, as opposed to what summarises them.
 *
 * The Profile tab has had mean, deviation and three approximate quartiles since it was written,
 * and it draws a quartile strip rather than a histogram for a reason recorded on the client: with
 * only five points there is nothing to bin, and "anything with twelve bars on it would be twelve
 * numbers this screen invented". That reasoning is right, and this exists to remove its premise
 * rather than to override it -- the bars below are counted, not inferred.
 *
 * SEPARATE FROM DatasetProfileDto ON PURPOSE. profileOf is one SUMMARIZE and the comment on it
 * argues, correctly, that a second query for the same twelve numbers would be a fourth permit
 * against a ceiling of four. This is not those twelve numbers: it is a different measurement with
 * a cost of its own, so it is a different method, fetched when a reader asks to see a
 * distribution, which is the same "cost follows the click" bargain the registry panel and the
 * query library already make on that screen.
 *
 * @author Nabeel Ahmed
 */
public class ColumnDistributionDto {

    /**
     * How many bars a numeric column is cut into.
     *
     * Twelve is a reading decision rather than a statistical one: the card this draws in is about
     * 240px wide, so beyond a dozen the bars stop being distinguishable and the chart says less
     * than the quartile strip it replaces. Small-cardinality columns never reach it -- a column of
     * three ages is drawn as three bars, one per value, because binning three values into twelve
     * buckets would put nine empty bars on screen and invent a spread the column does not have.
     */
    public static final int BINS = 12;

    /**
     * At or below this many distinct values, a column is drawn value-by-value instead of binned.
     *
     * The profile's approx_unique decides it, and it is a HyperLogLog estimate -- so this is a
     * threshold on an approximation, and a column sitting near it may be drawn either way between
     * one open and the next. That is tolerable because both renderings are truthful; what it must
     * never do is silently switch a column from counted bars to invented ones.
     */
    public static final int EXACT_VALUES_UP_TO = 12;

    private String name;

    /**
     * One entry per bar, in ascending order, or empty when this column has no distribution to
     * draw -- a text column, an all-null column, or a dataset with no rows.
     */
    private List<DistributionBinDto> bins;

    /**
     * True when each bin is one distinct value rather than a range, so the client labels it with
     * the value itself instead of with an interval.
     */
    private boolean exactValues;

    /**
     * The most frequent non-null value, and how many rows carry it.
     *
     * Measured for every column, not only text ones: "most common" is as meaningful for a status
     * code stored as an integer as for one stored as a word. Null where the column is entirely
     * null, which is the one case with no most-common value rather than a most-common value of
     * nothing.
     */
    private String mostCommon;

    private Long mostCommonRows;

    public ColumnDistributionDto() {}

    public ColumnDistributionDto(String name) {
        this.name = name;
    }

    public String getName() { return this.name; }
    public void setName(String name) { this.name = name; }

    public List<DistributionBinDto> getBins() { return this.bins; }
    public void setBins(List<DistributionBinDto> bins) { this.bins = bins; }

    public boolean isExactValues() { return this.exactValues; }
    public void setExactValues(boolean exactValues) { this.exactValues = exactValues; }

    public String getMostCommon() { return this.mostCommon; }
    public void setMostCommon(String mostCommon) { this.mostCommon = mostCommon; }

    public Long getMostCommonRows() { return this.mostCommonRows; }
    public void setMostCommonRows(Long mostCommonRows) { this.mostCommonRows = mostCommonRows; }
}

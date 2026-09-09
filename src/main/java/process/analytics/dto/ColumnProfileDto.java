package process.analytics.dto;

import java.math.BigDecimal;

/**
 * One column as DuckDB's SUMMARIZE described it, plus the quality flags derived from that
 * description and from nothing else.
 *
 * <b>Read this before showing any number on this object to a user.</b> Three of the figures here
 * are estimates and two of the flags rest on an estimate, and none of that is visible from the
 * field types. The Profile tab and the Quality tab are both built from ONE scan -- that is the
 * decision in synthesis 3.9, taken because a file open already costs three sessions against a
 * ceiling of four (gap 17) -- and the price of one scan is that some answers are approximate.
 * Presenting an approximate one as exact would be a worse outcome than a second query.
 *
 * <b>What is exact.</b> The name, the type, min and max, avg and std. Also the dataset's row count,
 * which SUMMARIZE returns per column and DatasetProfileDto carries once.
 *
 * <b>What is approximate, and how far off it was measured to be.</b>
 * <ul>
 *   <li>approxDistinct is a HyperLogLog estimate. Over a million genuinely distinct values it came
 *       back as 962,761 -- 3.7% low. It is close enough to answer "roughly how many?" and not close
 *       enough to answer "are these unique?", which is why keyLike below is a suggestion.</li>
 *   <li>approxQ25/approxQ50/approxQ75 are approx_quantile, not the exact quantile. Measured on the
 *       same column: the exact first quartile was 21.0 and SUMMARIZE reported 18.375. Do not label
 *       approxQ50 "the median" without saying it is approximate.</li>
 *   <li>approxNullRows is totalRows scaled by a percentage DuckDB rounded to two decimal places, so
 *       it is a reconstruction rather than a count. SUMMARIZE has no exact null count to give: its
 *       'count' is the TOTAL row count, not the non-null one.</li>
 * </ul>
 *
 * <b>The trap in nullPercentage, in both directions.</b> Two decimal places is plenty on a small
 * file and not enough on a large one. One null row in ten million rounds to 0.00, and one non-null
 * row in ten million rounds to 100.00. So "0.00" does not prove a column has no nulls and "100.00"
 * does not prove it is empty -- which is why allNull below wants approxDistinct to agree before it
 * says anything.
 *
 * <b>Why every statistic is a String.</b> Because min, max, avg, std and the quartiles have to
 * carry a date and a piece of text as well as a number, DuckDB returns all seven as VARCHAR. They
 * are kept as text here for the same reason DatasetPreviewDto keeps its cells as text: parsing them
 * to a double works until the column is a DATE, and then it throws in production on a file nobody
 * tested with. avg and std are null on any non-numeric column; the quartiles are null on VARCHAR and
 * BOOLEAN but PRESENT on DATE, which is exactly the case a naive parse falls into.
 *
 * <b>What is not here, and must not be faked.</b> Duplicate rows, blank-versus-null, and outliers.
 * SUMMARIZE cannot answer any of the three. An exact duplicate count needs its own
 * count(*) - count(DISTINCT (...)) scan, which is a second session per file open and therefore a
 * cost decision rather than an oversight.
 *
 * @author Nabeel Ahmed
 */
public class ColumnProfileDto {

    /** typeSurprise: the values look like numbers even though the column was read as text. */
    public static final String SURPRISE_NUMBER = "NUMBER";

    /** typeSurprise: the values look like dates even though the column was read as text. */
    public static final String SURPRISE_DATE = "DATE";

    private String name;
    private String type;

    private String min;
    private String max;
    private String avg;
    private String std;
    private String approxQ25;
    private String approxQ50;
    private String approxQ75;

    /** approx_unique. A HyperLogLog estimate of the distinct NON-NULL values -- never exact. */
    private long approxDistinct;

    /** Null only when the dataset has no rows at all, where DuckDB has no percentage to give. */
    private BigDecimal nullPercentage;

    // ---- derived below this line. Nothing here cost a second query. --------------------------

    /** 100 - nullPercentage, carrying the same two-decimal rounding and the same null case. */
    private BigDecimal completeness;

    /** APPROXIMATE. Reconstructed from a rounded percentage; see the class note above. */
    private Long approxNullRows;

    /**
     * Every value in the column is null.
     *
     * Wants both signals to agree -- a null percentage of 100.00 AND no distinct values found --
     * because on a large file either one alone can be produced by a column that is very nearly
     * empty rather than empty. Together they are as close to a fact as one scan gets.
     */
    private boolean allNull;

    /**
     * Every non-null value in the column is the same one.
     *
     * "Non-null" is the part to say on screen: a column of 'GB' with a third of its rows missing is
     * constant by this definition, and a reader who assumes otherwise will misread it.
     */
    private boolean constant;

    /**
     * The column MIGHT be a unique key. A suggestion, never a finding.
     *
     * It compares an estimate against a fact -- HyperLogLog's distinct count against the true row
     * count -- so the threshold has to be loose enough to survive the estimator's own error, which
     * was measured at 3.7% low on a million distinct values. Loose enough not to miss a real key is
     * also loose enough to flag a column with a handful of duplicates in it. Proving uniqueness
     * needs an exact count(DISTINCT ...), which is a second scan.
     */
    private boolean keyLike;

    /**
     * SURPRISE_NUMBER, SURPRISE_DATE, or null. A question to put to the user, not a verdict.
     *
     * It is decided from the two extreme values only, because min and max are the only values
     * SUMMARIZE returns. That makes it cheap and makes it fallible in both directions. A column of
     * '1', '1abc', '9' reports min '1' and max '9' -- both numbers, with the one value that is not
     * a number hidden between them. And a zip code column of '01234' and '00987' is flagged as
     * numeric when VARCHAR was the right call all along, because casting it to a number is what
     * eats the leading zero.
     */
    private String typeSurprise;

    /**
     * No all-args constructor on purpose: seventeen positional arguments, nine of them String,
     * is a call site where two swapped statistics look like working code. AnalyticsQueryService
     * builds these with setters, in two named passes -- what the engine said, then what was
     * derived from it.
     */
    public ColumnProfileDto() {}

    public String getName() { return this.name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return this.type; }
    public void setType(String type) { this.type = type; }

    public String getMin() { return this.min; }
    public void setMin(String min) { this.min = min; }

    public String getMax() { return this.max; }
    public void setMax(String max) { this.max = max; }

    public String getAvg() { return this.avg; }
    public void setAvg(String avg) { this.avg = avg; }

    public String getStd() { return this.std; }
    public void setStd(String std) { this.std = std; }

    public String getApproxQ25() { return this.approxQ25; }
    public void setApproxQ25(String approxQ25) { this.approxQ25 = approxQ25; }

    public String getApproxQ50() { return this.approxQ50; }
    public void setApproxQ50(String approxQ50) { this.approxQ50 = approxQ50; }

    public String getApproxQ75() { return this.approxQ75; }
    public void setApproxQ75(String approxQ75) { this.approxQ75 = approxQ75; }

    public long getApproxDistinct() { return this.approxDistinct; }
    public void setApproxDistinct(long approxDistinct) { this.approxDistinct = approxDistinct; }

    public BigDecimal getNullPercentage() { return this.nullPercentage; }
    public void setNullPercentage(BigDecimal nullPercentage) { this.nullPercentage = nullPercentage; }

    public BigDecimal getCompleteness() { return this.completeness; }
    public void setCompleteness(BigDecimal completeness) { this.completeness = completeness; }

    public Long getApproxNullRows() { return this.approxNullRows; }
    public void setApproxNullRows(Long approxNullRows) { this.approxNullRows = approxNullRows; }

    public boolean isAllNull() { return this.allNull; }
    public void setAllNull(boolean allNull) { this.allNull = allNull; }

    public boolean isConstant() { return this.constant; }
    public void setConstant(boolean constant) { this.constant = constant; }

    public boolean isKeyLike() { return this.keyLike; }
    public void setKeyLike(boolean keyLike) { this.keyLike = keyLike; }

    public String getTypeSurprise() { return this.typeSurprise; }
    public void setTypeSurprise(String typeSurprise) { this.typeSurprise = typeSurprise; }
}

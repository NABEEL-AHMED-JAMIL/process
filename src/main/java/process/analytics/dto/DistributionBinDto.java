package process.analytics.dto;

/**
 * One bar of a column's distribution, and the rows counted into it.
 *
 * The bounds are carried as text for the same reason every figure in ColumnProfileDto is: a bin
 * edge on a DECIMAL column is not a double, and rendering 103909527.58 through a float would put
 * 103909527.57999787 on a tooltip -- which is exactly the artefact the dashboard spent a change
 * removing from its tables. The client formats them; nothing here parses them.
 *
 * Half-open, [from, to), so a value on a boundary is counted once and in the lower bar. The final
 * bin is the exception and is closed at the top, because otherwise the maximum value of the column
 * -- which is the one value guaranteed to exist -- would fall outside every bar.
 *
 * @author Nabeel Ahmed
 */
public class DistributionBinDto {

    private String from;
    private String to;
    private long rows;

    /**
     * The value this bar stands for, when the column was drawn value-by-value rather than binned.
     * Null for a real range, which is how the client tells the two apart without a second flag.
     */
    private String value;

    public DistributionBinDto() {}

    public DistributionBinDto(String from, String to, long rows) {
        this.from = from;
        this.to = to;
        this.rows = rows;
    }

    public String getFrom() { return this.from; }
    public void setFrom(String from) { this.from = from; }

    public String getTo() { return this.to; }
    public void setTo(String to) { this.to = to; }

    public long getRows() { return this.rows; }
    public void setRows(long rows) { this.rows = rows; }

    public String getValue() { return this.value; }
    public void setValue(String value) { this.value = value; }
}

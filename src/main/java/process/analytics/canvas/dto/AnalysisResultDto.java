package process.analytics.canvas.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import process.analytics.canvas.AnalysisRequest;
import process.analytics.dto.ColumnDto;

import java.util.List;
import java.util.Map;

/**
 * The answer to an analysis, with everything a client needs to draw it and nothing it would have to
 * recompute.
 *
 * <b>The shape is deliberately close to {@link process.analytics.dto.QueryResultDto} and the
 * differences are where 07 lives.</b> Rows are still lists of strings for the reasons that DTO
 * gives -- a DECIMAL that arrives in a browser as a JavaScript double has already lost precision --
 * but every column now carries a type AND a role, so a client can right-align the numbers, format
 * the dates, and tell which cells of a row are the dimension values that reproduce it. That last
 * one is 07's "every result row should carry enough context to reproduce its filter state",
 * satisfied by the roles rather than by a per-row payload nobody would want to send.
 *
 * <b>Four fields exist so that the client never reconstructs analytical state it did not
 * compute.</b> {@link #crumbs} is the drill trail already labelled; {@link #drillPath} is the same
 * trail in the form the next request echoes back; {@link #other} says what the Top-N roll-up stands
 * for; {@link #pivot} is the two-dimensional result already shaped as a grid. Each of them is
 * derivable from the rows by a client willing to do the work, and every one of them is a place where
 * the client's answer and the server's could differ -- a chart that disagrees with the breadcrumb
 * above it is the failure this shape is meant to make impossible.
 *
 * @author Nabeel Ahmed
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnalysisResultDto {

    /** The columns, in result order, each with its engine type and its role in the analysis. */
    private List<ColumnDto> columns;

    /** The rows. Dimension cells first in the order the dimensions were given, then the measure. */
    private List<List<String>> rows;

    private int rowCount;

    /** Whether analytics.query.max-rows is what ended the result rather than the data. */
    private boolean truncated;

    /** The dimension names this analysis grouped by, after drilling. Empty for a single total. */
    private List<String> dimensions;

    /** The name of the measure column, so a chart does not have to work out which one it is. */
    private String measure;

    private String queryId;
    private String status;
    private Long durationMs;

    /** Present only when a Top-N rolled values up. Null means every row is a real value. */
    private OtherBucketDto other;

    /**
     * The drill trail as labels, oldest first, always starting at the root.
     *
     * Composed here rather than in the client because the server is what decided the trail: it
     * applied the filters, it replaced the dimensions, and it is the only side that knows the
     * analysis actually ran that way. A crumb rendered from a client's own copy of the state is a
     * claim about what the server did.
     */
    private List<CrumbDto> crumbs;

    /** The same trail as data, for the next request to send back unchanged. */
    private List<AnalysisRequest.Drill> drillPath;

    /** The grid, when there are exactly two dimensions. Null otherwise; see the type's javadoc. */
    private PivotDto pivot;

    /**
     * What each relative date window resolved to, as "2024-03-01 to 2024-03-07".
     *
     * Reported because a filter that says "last 7 days" is not reproducible from the request alone
     * -- it depends on when it ran -- and a user comparing two charts taken an hour either side of
     * midnight deserves to be able to see why they differ.
     */
    private Map<String, String> resolvedWindows;

    public List<ColumnDto> getColumns() { return this.columns; }
    public void setColumns(List<ColumnDto> columns) { this.columns = columns; }

    public List<List<String>> getRows() { return this.rows; }
    public void setRows(List<List<String>> rows) { this.rows = rows; }

    public int getRowCount() { return this.rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }

    public boolean isTruncated() { return this.truncated; }
    public void setTruncated(boolean truncated) { this.truncated = truncated; }

    public List<String> getDimensions() { return this.dimensions; }
    public void setDimensions(List<String> dimensions) { this.dimensions = dimensions; }

    public String getMeasure() { return this.measure; }
    public void setMeasure(String measure) { this.measure = measure; }

    public String getQueryId() { return this.queryId; }
    public void setQueryId(String queryId) { this.queryId = queryId; }

    public String getStatus() { return this.status; }
    public void setStatus(String status) { this.status = status; }

    public Long getDurationMs() { return this.durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public OtherBucketDto getOther() { return this.other; }
    public void setOther(OtherBucketDto other) { this.other = other; }

    public List<CrumbDto> getCrumbs() { return this.crumbs; }
    public void setCrumbs(List<CrumbDto> crumbs) { this.crumbs = crumbs; }

    public List<AnalysisRequest.Drill> getDrillPath() { return this.drillPath; }
    public void setDrillPath(List<AnalysisRequest.Drill> drillPath) { this.drillPath = drillPath; }

    public PivotDto getPivot() { return this.pivot; }
    public void setPivot(PivotDto pivot) { this.pivot = pivot; }

    public Map<String, String> getResolvedWindows() { return this.resolvedWindows; }
    public void setResolvedWindows(Map<String, String> windows) { this.resolvedWindows = windows; }

    /**
     * What the Top-N roll-up row stands for.
     *
     * <b>The values are why this type exists.</b> A row labelled "Other" with a number beside it is
     * not an answer -- it is a hole in a chart with a total in it. Naming what was rolled up turns
     * the roll-up into something a reader can act on: either they recognise the names and are
     * content, or they raise the Top-N.
     *
     * On a genuinely high-cardinality dimension the list is a sample, and it says so: valueCount is
     * the exact number of distinct values in the bucket and the list is capped. A reader is never
     * shown a partial list as though it were complete.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OtherBucketDto {

        private String label;
        private List<String> values;
        private long valueCount;
        private boolean valuesTruncated;

        public OtherBucketDto() {}

        public OtherBucketDto(String label, List<String> values, long valueCount,
            boolean valuesTruncated) {
            this.label = label;
            this.values = values;
            this.valueCount = valueCount;
            this.valuesTruncated = valuesTruncated;
        }

        /** What the row is called in the result. "Other" unless the caller chose another word. */
        public String getLabel() { return this.label; }
        public void setLabel(String label) { this.label = label; }

        /** The values rolled up, up to the reporting cap. */
        public List<String> getValues() { return this.values; }
        public void setValues(List<String> values) { this.values = values; }

        /** How many distinct values are in the bucket, whether or not they are all listed. */
        public long getValueCount() { return this.valueCount; }
        public void setValueCount(long valueCount) { this.valueCount = valueCount; }

        /** True when values is a sample of valueCount rather than the whole of it. */
        public boolean isValuesTruncated() { return this.valuesTruncated; }
        public void setValuesTruncated(boolean truncated) { this.valuesTruncated = truncated; }
    }

    /** One step of the drill trail, already labelled for a breadcrumb bar. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CrumbDto {

        private String label;
        private String field;
        private String value;

        public CrumbDto() {}

        public CrumbDto(String label, String field, String value) {
            this.label = label;
            this.field = field;
            this.value = value;
        }

        /** "All rows" at the root, then "region: north". */
        public String getLabel() { return this.label; }
        public void setLabel(String label) { this.label = label; }

        /** Null on the root crumb, which is the whole dataset and narrows nothing. */
        public String getField() { return this.field; }
        public void setField(String field) { this.field = field; }

        /** Null when the drilled group was the one with no value in it. */
        public String getValue() { return this.value; }
        public void setValue(String value) { this.value = value; }
    }

    /**
     * The same result as a row x column grid, for the two-dimension case 07 asks for.
     *
     * <b>Why this is a section of the ordinary response and not an endpoint or a flag.</b> A
     * separate /analyze/pivot would run the same aggregate a second time, on a second session and a
     * second governor permit, to produce numbers this response already holds -- which is exactly the
     * argument profileOf makes for Profile and Quality being one scan against a ceiling of four
     * concurrent queries. A request flag would be worse in a different way: it would let a caller
     * ask for a grid over one dimension or three, where a grid has no meaning, and it would give one
     * query two response shapes for the client to branch on. Shaping it here costs no engine work at
     * all -- it is a re-arrangement of rows already in hand, done after the session has closed, the
     * same place and the same way profileOf derives its quality flags.
     *
     * It is present when the analysis has exactly two dimensions and absent otherwise, so its
     * presence IS the answer to "can this be drawn as a grid".
     *
     * The row axis is the first dimension and the column axis is the second, in the order the caller
     * gave them, because that is the order they appear in every other part of this response and a
     * grid that silently swapped them would transpose a user's chart.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PivotDto {

        private String rowDimension;
        private String columnDimension;
        private List<String> columnValues;
        private List<PivotRowDto> rows;
        private boolean columnsTruncated;

        public PivotDto() {}

        public PivotDto(String rowDimension, String columnDimension, List<String> columnValues,
            List<PivotRowDto> rows, boolean columnsTruncated) {
            this.rowDimension = rowDimension;
            this.columnDimension = columnDimension;
            this.columnValues = columnValues;
            this.rows = rows;
            this.columnsTruncated = columnsTruncated;
        }

        public String getRowDimension() { return this.rowDimension; }
        public void setRowDimension(String rowDimension) { this.rowDimension = rowDimension; }

        public String getColumnDimension() { return this.columnDimension; }
        public void setColumnDimension(String dimension) { this.columnDimension = dimension; }

        /** The column headers, in first-seen order, so the grid matches the result's own sort. */
        public List<String> getColumnValues() { return this.columnValues; }
        public void setColumnValues(List<String> columnValues) { this.columnValues = columnValues; }

        public List<PivotRowDto> getRows() { return this.rows; }
        public void setRows(List<PivotRowDto> rows) { this.rows = rows; }

        /**
         * True when the column dimension had more distinct values than a grid can carry.
         *
         * The grid is then not drawn -- rows is null -- because a table five thousand columns wide
         * is not a narrower version of the answer, it is a different and unusable one. The flag says
         * why, so a screen can offer the Top-N control instead of showing nothing.
         */
        public boolean isColumnsTruncated() { return this.columnsTruncated; }
        public void setColumnsTruncated(boolean truncated) { this.columnsTruncated = truncated; }
    }

    /** One row of the grid: the row dimension's value, and one cell per column value. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PivotRowDto {

        private String key;
        private List<String> cells;

        public PivotRowDto() {}

        public PivotRowDto(String key, List<String> cells) {
            this.key = key;
            this.cells = cells;
        }

        /** The row dimension's value. Null when the group is the one with no value in it. */
        public String getKey() { return this.key; }
        public void setKey(String key) { this.key = key; }

        /**
         * The measure for each column value, aligned to columnValues by position.
         *
         * A null cell is a combination that has no rows, which is different from a zero and must
         * stay different: a month with no sales and a month with sales of nothing are not the same
         * fact, and filling the gap with 0 would put a point on a chart where there is no data.
         */
        public List<String> getCells() { return this.cells; }
        public void setCells(List<String> cells) { this.cells = cells; }
    }
}

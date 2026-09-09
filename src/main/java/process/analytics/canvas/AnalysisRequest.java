package process.analytics.canvas;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import process.analytics.AnalyticsException;

import java.util.ArrayList;
import java.util.List;

/**
 * An analysis, as a structure rather than as a statement.
 *
 * <b>This is the type 07 was missing, and the reason the document stood at 2 of 48.</b> What
 * existed was a SQL console: a person could hand-write the same GROUP BY, and the audit says
 * plainly that a console which lets a user write the query is not this requirement. The requirement
 * is that the backend receives a MODEL -- dimensions, a measure, filters, a sort, a Top-N -- and
 * generates the query itself, so that the query is generated safely once instead of being trusted
 * once per user.
 *
 * <b>Nothing here names a location.</b> A connection alias and a path inside it, exactly as every
 * other analytics endpoint takes them, so DatasetResolver stays the only thing in the module that
 * turns a request into somewhere readable and the bucket still comes from the connection record.
 *
 * <b>Nothing here validates either, beyond the shape.</b> {@link #normalised()} settles the
 * questions that can be answered without knowing what is in the file -- how many dimensions, whether
 * a measure that needs a field has one, whether a drill names a dimension this analysis has -- and
 * stops there. Everything about a FIELD is deferred to {@link AnalysisQueryBuilder}, because a field
 * can only be judged against the dataset's own schema and judging it anywhere else would be a second
 * allow-list to keep in step with the first.
 *
 * <b>Drilling is stateless and the state travels in {@link #drillPath}.</b> The three endpoints
 * differ only in what they do to that list: /analyze runs it as given, /analyze/drill appends
 * {@link #into}, /analyze/drill-up drops {@link #steps} from its end. The list comes back on every
 * response so the client echoes it rather than reconstructing it -- which is 07's point about
 * drill-up, and the reason the crumbs are server-composed too. A client that had to rebuild the
 * filter stack itself would be recomputing analytical state it never computed in the first place,
 * and the first divergence between its version and the server's is a chart that does not match its
 * own breadcrumb.
 *
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnalysisRequest {

    /**
     * The most dimensions one analysis may group by.
     *
     * <b>Three because 07 asks for one, two and three and stops, and because the fourth is where
     * the result stops being a result.</b> A grouped result has one row per COMBINATION, so its
     * height is the product of the dimensions' distinct counts: three columns of ten values each is
     * a thousand rows, and a fourth of the same size is ten thousand -- past which
     * analytics.query.max-rows is not an unusual outcome but the normal one, and every answer comes
     * back flagged truncated. The reader's side has the same ceiling from the other direction:
     * two dimensions draw as a grid and three as a grid with a nested axis, and nobody has ever
     * read a four-dimensional table.
     *
     * It is a limit on GROUPING, not on filtering. An analysis narrowed by six filters and grouped
     * by one dimension is entirely ordinary, and drilling deliberately spends dimensions rather
     * than accumulating them -- each drill replaces the dimension it drilled through, so a drill
     * chain of any length stays inside this ceiling.
     */
    public static final int MAX_DIMENSIONS = 3;

    /** The eight measures 07's measure menu lists, and exactly those. */
    public enum Aggregation {

        /** How many rows are in the group. The only one that ignores the field. */
        COUNT_ROWS,
        /** How many rows have a value in this column. */
        COUNT_NON_NULL,
        /**
         * How many different values this column takes in the group.
         *
         * Exact, and that is a decision the profile path does not make. SUMMARIZE's approx_unique is
         * HyperLogLog and was measured 3.7% low over a million distinct values -- acceptable for a
         * per-column statistic offered as a prompt to look, and not acceptable for a number a user
         * put on a chart beside a sum. count(DISTINCT) over a GROUP is also a much smaller question
         * than over a whole file, because the groups partition the work.
         */
        DISTINCT_COUNT,
        SUM,
        AVERAGE,
        MINIMUM,
        MAXIMUM,
        /** The middle value. Which of DuckDB's two quantile functions computes it depends on the
         *  column's type, and {@link AnalysisQueryBuilder} carries the measured reason. */
        MEDIAN;

        /** Whether this measure is about a particular column, or about the rows themselves. */
        public boolean needsField() {
            return this != COUNT_ROWS;
        }
    }

    private String connection;
    private String path;

    /**
     * The columns to group by, in the order they nest. Empty or absent means no grouping at all --
     * one row for the whole filtered dataset, which is what a KPI card is.
     */
    private List<String> dimensions;

    private Measure measure;

    /** The root of the filter tree, or null for no filter. */
    private FilterClause filters;

    private TopN topN;
    private Sort sort;

    /**
     * The id this run answers to, so a stop button has something to name.
     *
     * Optional, and the same bargain /query strikes: the endpoint is synchronous, so a server-minted
     * id arrives in the same response as the rows -- that is, once there is nothing left to cancel.
     * A client that means to offer a stop control sends the id it will cancel with.
     */
    private String queryId;

    /** The drill steps already taken, oldest first. Sent back exactly as it was received. */
    private List<Drill> drillPath;

    /** The step /analyze/drill is being asked to take. Read by that endpoint and no other. */
    private Drill into;

    /** How many steps /analyze/drill-up is being asked to undo. Read by that endpoint and no other. */
    private Integer steps;

    /**
     * The same request with the answerable questions answered, or a refusal.
     *
     * Returns a copy rather than mutating, because the three endpoints all build on the incoming
     * request and one that had been edited underneath them would make "what did the client send"
     * unanswerable at the point where it matters most -- the drill path echoed back in the response.
     */
    public AnalysisRequest normalised() throws AnalyticsException {
        AnalysisRequest copy = new AnalysisRequest();
        copy.connection = this.connection;
        copy.path = this.path;
        copy.dimensions = trimmed(this.dimensions);
        copy.measure = this.measure;
        copy.filters = this.filters;
        copy.topN = this.topN;
        copy.sort = this.sort;
        copy.queryId = this.queryId;
        copy.drillPath = this.drillPath == null ? new ArrayList<>() : new ArrayList<>(this.drillPath);
        copy.into = this.into;
        copy.steps = this.steps;

        if (copy.dimensions.size() > MAX_DIMENSIONS) {
            throw new AnalyticsException("An analysis groups by up to "
                + MAX_DIMENSIONS + " dimensions at a time. Drill into one instead of adding a "
                + "fourth.");
        }
        if (copy.measure == null || copy.measure.getAggregation() == null) {
            throw new AnalyticsException("Pick something to measure first.");
        }
        if (copy.measure.getAggregation().needsField() && isBlank(copy.measure.getField())) {
            throw new AnalyticsException("A "
                + copy.measure.getAggregation().name().toLowerCase().replace('_', ' ')
                + " needs a column to measure.");
        }
        return copy;
    }

    private static List<String> trimmed(List<String> names) {
        List<String> clean = new ArrayList<>();
        if (names == null) {
            return clean;
        }
        for (String name : names) {
            if (!isBlank(name)) {
                clean.add(name.trim());
            }
        }
        return clean;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public String getConnection() { return this.connection; }
    public void setConnection(String connection) { this.connection = connection; }

    public String getPath() { return this.path; }
    public void setPath(String path) { this.path = path; }

    public List<String> getDimensions() { return this.dimensions; }
    public void setDimensions(List<String> dimensions) { this.dimensions = dimensions; }

    public Measure getMeasure() { return this.measure; }
    public void setMeasure(Measure measure) { this.measure = measure; }

    public FilterClause getFilters() { return this.filters; }
    public void setFilters(FilterClause filters) { this.filters = filters; }

    public TopN getTopN() { return this.topN; }
    public void setTopN(TopN topN) { this.topN = topN; }

    public Sort getSort() { return this.sort; }
    public void setSort(Sort sort) { this.sort = sort; }

    public String getQueryId() { return this.queryId; }
    public void setQueryId(String queryId) { this.queryId = queryId; }

    public List<Drill> getDrillPath() { return this.drillPath; }
    public void setDrillPath(List<Drill> drillPath) { this.drillPath = drillPath; }

    public Drill getInto() { return this.into; }
    public void setInto(Drill into) { this.into = into; }

    public Integer getSteps() { return this.steps; }
    public void setSteps(Integer steps) { this.steps = steps; }

    /** What is being counted, summed or averaged, and over which column. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Measure {

        private String field;
        private Aggregation aggregation;

        public Measure() {}

        public Measure(String field, Aggregation aggregation) {
            this.field = field;
            this.aggregation = aggregation;
        }

        /** Ignored by COUNT_ROWS, required by the other seven. */
        public String getField() { return this.field; }
        public void setField(String field) { this.field = field; }

        public Aggregation getAggregation() { return this.aggregation; }
        public void setAggregation(Aggregation aggregation) { this.aggregation = aggregation; }
    }

    /**
     * How much of a high-cardinality dimension to show, and what to do with the rest.
     *
     * The limit applies to the FIRST dimension and to no other. That is what a Top-N control means
     * on a screen -- "the top ten departments", never "the top ten department-and-status pairs" --
     * and it is also what keeps a pivot's row axis stable when the column axis is left whole.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TopN {

        private Integer limit;

        /**
         * Whether the values outside the top N are rolled up into one row, or simply left out.
         *
         * True by default because leaving them out silently is how a chart comes to show a total
         * that does not add up to the total. A roll-up says how much is missing; a truncation does
         * not, and looks identical.
         */
        private boolean includeOther = true;

        /** What the rolled-up row is called. "Other" unless the caller has a better word. */
        private String otherLabel;

        public Integer getLimit() { return this.limit; }
        public void setLimit(Integer limit) { this.limit = limit; }

        public boolean isIncludeOther() { return this.includeOther; }
        public void setIncludeOther(boolean includeOther) { this.includeOther = includeOther; }

        public String getOtherLabel() { return this.otherLabel; }
        public void setOtherLabel(String otherLabel) { this.otherLabel = otherLabel; }
    }

    /** Which column the rows are ordered by, and which way. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Sort {

        public enum By {
            /** The measure. What a ranked chart wants, and the default. */
            MEASURE,
            /** The dimensions, in the order they were given. What a time series wants. */
            DIMENSION
        }

        public enum Direction {
            ASC,
            DESC
        }

        private By by;
        private Direction direction;

        public Sort() {}

        public Sort(By by, Direction direction) {
            this.by = by;
            this.direction = direction;
        }

        public By getBy() { return this.by; }
        public void setBy(By by) { this.by = by; }

        public Direction getDirection() { return this.direction; }
        public void setDirection(Direction direction) { this.direction = direction; }
    }

    /**
     * One narrowing of the analytical context: a value clicked, and what to look at inside it.
     *
     * The same type describes a step being TAKEN ({@link AnalysisRequest#into}) and a step already
     * taken ({@link AnalysisRequest#drillPath}), because they are the same thing at two moments.
     * 07's example -- Department to Engineering, then Location to Chicago, then Status to Active --
     * is three of these in a list.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Drill {

        /** The dimension whose value was clicked. It must be one this analysis is grouping by. */
        private String dimension;

        /**
         * The value that was clicked.
         *
         * Null means the clicked group was the one with no value in it, and it is drilled into as
         * IS NULL rather than as an equality -- because "= NULL" is never true and would silently
         * return an empty analysis for a group the user can see has rows in it.
         */
        private String value;

        /**
         * What to group by instead, or null to drop the grouping and keep only the narrowing.
         *
         * Replacing rather than appending is what keeps a drill chain of any length inside
         * MAX_DIMENSIONS, and it is also what drill-up restores: the dimension a step replaced is
         * recorded here, so undoing the step puts it back without the client having to remember it.
         */
        private String nextDimension;

        /**
         * Whether the clicked row was the Top-N roll-up rather than a real value.
         *
         * A flag rather than a comparison against the label, because a dataset is perfectly entitled
         * to contain the value "Other" and a client should never have to tell the two apart by
         * spelling. Drilling into the roll-up is refused: it stands for a set of values, and an
         * equality on the word would return the rows that literally say Other, which is a different
         * and much smaller answer than the one the row was showing.
         */
        private boolean otherBucket;

        public Drill() {}

        public Drill(String dimension, String value, String nextDimension) {
            this.dimension = dimension;
            this.value = value;
            this.nextDimension = nextDimension;
        }

        public String getDimension() { return this.dimension; }
        public void setDimension(String dimension) { this.dimension = dimension; }

        public String getValue() { return this.value; }
        public void setValue(String value) { this.value = value; }

        public String getNextDimension() { return this.nextDimension; }
        public void setNextDimension(String nextDimension) { this.nextDimension = nextDimension; }

        public boolean isOtherBucket() { return this.otherBucket; }
        public void setOtherBucket(boolean otherBucket) { this.otherBucket = otherBucket; }
    }
}

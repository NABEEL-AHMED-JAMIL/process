package process.analytics.canvas;

import process.analytics.AnalyticsEngine;
import process.analytics.AnalyticsException;
import process.analytics.dto.ColumnDto;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;

/**
 * Turns an analysis model into one statement, against the dataset's own schema.
 *
 * <b>This is 07's "generate safe backend analytical requests from a structured analysis model", and
 * the safety is structural rather than defensive.</b> Nothing a caller sends becomes SQL text.
 * Values become bound parameters in {@link FilterCompiler}; field names become lookups in
 * {@link FilterCompiler.Columns}, which answers with the SCHEMA's own string or refuses; aggregation
 * and sort are enums, so the only way to reach a keyword is to be one of the eight or one of the
 * two. What is left that a request contributes to the text at all is a Top-N limit and a nesting of
 * brackets, both of which are integers this class writes itself.
 *
 * <b>The statement is composed against a view named "dataset", never against a scan.</b> That is
 * what lets {@link process.analytics.StatementGate#confirmComposed} run over the finished text: the
 * gate refuses table functions as a class, so a builder that had emitted read_csv_auto would have
 * had to be exempted from the check that stands behind it. Naming the view instead means the second
 * layer applies to server-composed SQL exactly as it applies to a user's, and a field name that
 * somehow carried a location would land in table_name or schema_name and be refused there.
 *
 * <b>What this class deliberately is NOT.</b> It is not engine-neutral. quantile_cont,
 * quantile_disc, list_slice, IS NOT DISTINCT FROM and the CTE shape below are DuckDB, and
 * {@link AnalyticsEngine}'s javadoc is already honest that SQL crosses that seam -- bounded()
 * returns a String of it. A second engine needs a builder of its own, and that is recorded here
 * rather than implied away by putting the class in a package with a neutral-sounding name.
 *
 * @author Nabeel Ahmed
 */
public final class AnalysisQueryBuilder {

    /**
     * The largest Top-N a caller may ask for.
     *
     * 07's menu is 10, 25, 50 and a custom N, so the number is the user's -- but a "custom N" of a
     * million is not a Top-N, it is the whole dimension with a roll-up row bolted on, and it would
     * put a million-branch grouping key in front of the row ceiling that is supposed to bound the
     * answer. Ten thousand is far past any list a person reads and far short of the point where the
     * shape stops meaning anything.
     */
    public static final int MAX_TOP_N = 10000;

    /**
     * How many of the rolled-up values the Other bucket reports by name.
     *
     * The contract says the response must say which rows Other represents, and on a
     * high-cardinality dimension -- which is the only reason Top-N exists -- the honest answer is a
     * sample and a count rather than forty thousand strings in a JSON payload the browser then has
     * to hold. The exact count travels beside the sample, so a reader is never told a partial list
     * is the whole one.
     */
    public static final int OTHER_VALUES_REPORTED = 200;

    /** The name a user's dimension answers to inside the composed statement's own scope. */
    private static final String SOURCE = AnalyticsEngine.DATASET;

    private final LocalDate today;

    public AnalysisQueryBuilder() {
        this(null);
    }

    /** The same builder with the relative-date clock named, so a test does not wait for tomorrow. */
    public AnalysisQueryBuilder(LocalDate today) {
        this.today = today;
    }

    /**
     * The statement for this analysis, plus everything the caller needs to read its result.
     *
     * A Plan rather than a String because the result cannot be interpreted without it: which
     * columns are dimensions, which is the measure, and -- when a Top-N rolled up -- which of the
     * trailing columns are the marker and the roll-up's membership, none of which belong in the
     * response and all of which have to be found by position rather than by name.
     */
    public Plan plan(AnalysisRequest rawRequest, List<ColumnDto> schema) throws AnalyticsException {
        // Normalised here as well as at the endpoint, and not as belt-and-braces: this is the only
        // entry point the tests use, and a builder that assumed a caller had already checked the
        // dimension count would be a builder whose ceiling depends on who called it. normalised()
        // is idempotent, so the second call costs a copy.
        AnalysisRequest request = rawRequest.normalised();
        FilterCompiler.Columns columns = FilterCompiler.Columns.of(schema);

        // The drill steps first, because they decide what the dimensions ARE. Everything below
        // works on the effective list, so a drilled analysis and a hand-built one with the same
        // shape compile to the same statement -- which is what makes a drill reproducible.
        Drilled drilled = drill(request, columns);
        List<Grouping> dimensions = drilled.dimensions;

        FilterCompiler compiler = this.today == null
            ? new FilterCompiler(columns) : new FilterCompiler(columns, this.today);
        AnalyticsEngine.BoundStatement where = compiler.compile(drilled.filters);

        Measure measure = measure(request.getMeasure(), columns);
        // Against the dimension ALIASES, not their names: a dataset may genuinely hold a
        // column called booked_on_month, and the alias is what would collide.
        String measureAlias = uniqueAliasAmong(measure.alias, aliases(dimensions));

        List<Object> parameters = new ArrayList<>();
        String sql = request.getTopN() != null && request.getTopN().getLimit() != null
            ? this.topN(request, dimensions, measure, measureAlias, where, columns, parameters)
            : plain(request, dimensions, measure, measureAlias, where, parameters);

        boolean rolledUp = request.getTopN() != null && request.getTopN().getLimit() != null
            && request.getTopN().isIncludeOther();
        int visible = dimensions.size() + 1;
        return new Plan(new AnalyticsEngine.BoundStatement(sql, parameters), names(dimensions),
            aliases(dimensions), grainsOf(dimensions),
            measureAlias, visible, rolledUp ? visible : -1,
            rolledUp && !dimensions.isEmpty() ? visible + 1 : -1,
            rolledUp && !dimensions.isEmpty() ? visible + 2 : -1,
            compiler.getResolvedWindows(), drilled.crumbs, drilled.drillPath);
    }

    /**
     * A grouped aggregate, which is what an analysis is when nobody has asked for a Top-N.
     *
     * GROUP BY takes ordinals rather than repeating the expressions. That is not brevity: a
     * dimension is written into the select list exactly once, so there is one place a name reaches
     * the text and one place to look when asking whether it could have been anything else.
     */
    private static String plain(AnalysisRequest request, List<Grouping> dimensions,
        Measure measure, String measureAlias, AnalyticsEngine.BoundStatement where,
        List<Object> parameters) {

        StringBuilder sql = new StringBuilder("SELECT ");
        for (Grouping dimension : dimensions) {
            // The grouping EXPRESSION, which is the column itself unless a grain buckets it.
            // GROUP BY and ORDER BY stay ordinals below, so "a dimension is written into the
            // select list exactly once" survives a grain unchanged.
            sql.append(dimension.expression(null)).append(" AS ")
                .append(quoteAlias(dimension.alias)).append(", ");
        }
        sql.append(measure.expression).append(" AS ").append(quoteAlias(measureAlias));
        sql.append(" FROM ").append(SOURCE);
        if (where != null) {
            sql.append(" WHERE ").append(where.getSql());
            parameters.addAll(where.getParameters());
        }
        appendGroupBy(sql, dimensions.size(), -1);
        appendOrderBy(sql, request.getSort(), dimensions.size(), false);
        return sql.toString();
    }

    /**
     * A Top-N over the first dimension, with everything else rolled into one row.
     *
     * <b>A roll-up, not a truncation, and the difference is the whole point of the CTE.</b> Taking
     * the first N rows of a grouped result and calling the rest Other would be arithmetic on
     * aggregates: an average of averages is not an average, a distinct count of distinct counts is
     * not a distinct count, and even a sum would be right only by luck of the measure chosen. So the
     * membership is decided first -- which values of the first dimension are in the top N -- and the
     * measure is then computed ONCE, from the raw rows, with every non-member's rows grouped
     * together. The Other row is a real aggregate over real rows.
     *
     * <b>Ranked by the measure descending, always, whatever the display sort says.</b> "Top" means
     * largest; a user who sorts the result ascending for readability has not asked for the bottom
     * ten. A bottom-N is a control this model cannot express today, and leaving it unexpressible is
     * better than smuggling it into a field that means something else.
     *
     * <b>IS NOT DISTINCT FROM rather than IN.</b> A dimension is allowed to be null, a null group
     * is allowed to be one of the biggest, and "NULL IN (NULL)" is unknown rather than true -- so an
     * IN test would push a group that IS in the top N into the Other bucket and the numbers would
     * stop adding up. The one three-valued comparison in this file is the one that removes the
     * three-valued logic.
     *
     * <b>The cost that is worth knowing.</b> list(DISTINCT ...) on the Other group holds every
     * rolled-up value in the aggregate's state before list_slice takes the first
     * {@value #OTHER_VALUES_REPORTED} -- there is no bounded list aggregate in DuckDB 1.1.3 -- so a
     * dimension with a million distinct values builds a million-element list inside the session's
     * own memory ceiling. It is bounded by analytics.duckdb.memory-limit rather than by this class,
     * which means it fails as an engine error rather than as an OOM, and it is only paid when a
     * caller asked for the roll-up.
     */
    private String topN(AnalysisRequest request, List<Grouping> dimensions, Measure measure,
        String measureAlias, AnalyticsEngine.BoundStatement where, FilterCompiler.Columns columns,
        List<Object> parameters) throws AnalyticsException {

        if (dimensions.isEmpty()) {
            throw new AnalyticsException("Top-N ranks the values of a dimension, and this analysis "
                + "has none to rank.");
        }
        int limit = request.getTopN().getLimit();
        if (limit < 1 || limit > MAX_TOP_N) {
            throw new AnalyticsException("A Top-N is between 1 and " + MAX_TOP_N + ".");
        }

        Grouping ranked = dimensions.get(0);
        // The grouping EXPRESSION, so a grained Top-N ranks the buckets rather than the raw days.
        String rankedName = ranked.expression(null);
        // Internal names are checked against the dataset's own columns rather than assumed free:
        // "filtered.*" projects every column the file has, so a file with a column called in_top
        // would otherwise produce two columns of that name and a statement that means something
        // else. Rare, and the cost of being sure is one loop.
        String marker = free("analysis_in_top", columns);
        String filtered = free("analysis_filtered", columns);
        String top = free("analysis_top", columns);
        String tagged = free("analysis_tagged", columns);
        String key = free("analysis_key", columns);
        String hit = free("analysis_hit", columns);

        StringBuilder sql = new StringBuilder("WITH ").append(filtered)
            .append(" AS (SELECT * FROM ").append(SOURCE);
        if (where != null) {
            sql.append(" WHERE ").append(where.getSql());
            parameters.addAll(where.getParameters());
        }
        sql.append("), ").append(top).append(" AS (SELECT ").append(rankedName).append(" AS ")
            .append(quoteAlias(key)).append(", TRUE AS ").append(quoteAlias(hit))
            .append(" FROM ").append(filtered)
            .append(" GROUP BY 1 ORDER BY ").append(measure.expression)
            .append(" DESC NULLS LAST LIMIT ").append(limit).append(")");

        /*
         * A JOIN, not a correlated EXISTS, and the difference is measured rather than stylistic.
         *
         * DuckDB decorrelates the EXISTS into a plain HASH_JOIN only when there is no WHERE
         * filter. Add one -- which is to say, in almost every real analysis -- and the plan
         * becomes a DELIM_JOIN plus an extra HASH_JOIN. Measured on 4M rows with 50,000 distinct
         * values: a filtered Top-N of 25 went from 75ms to 30ms without the roll-up, and 109ms to
         * 83ms with it. Unfiltered, where the old form already decorrelated, it is unchanged.
         *
         * The PREDICATE is untouched. IS NOT DISTINCT FROM is still what decides membership, so
         * the three-valued-logic argument above survives word for word; only its position moves
         * from a WHERE inside a subquery to a JOIN ... ON.
         */
        /*
         * expression(filtered), NOT filtered + "." + rankedName.
         *
         * A qualifier belongs on the COLUMN, inside the grouping expression -- prefixing it to
         * the whole thing produced analysis_filtered.date_trunc('month', "booked_on"), which
         * reads as a function call on a table and is not valid SQL. Both sides have to be grained
         * the same way, or the ranking buckets months while the membership compares days and
         * nothing matches at all.
         */
        String membership = " ON " + top + "." + quoteAlias(key)
            + " IS NOT DISTINCT FROM " + ranked.expression(filtered);

        if (!request.getTopN().isIncludeOther()) {
            // No roll-up asked for: the same membership test, used to narrow rather than to bucket.
            // Still an aggregate over the raw rows of the top N values, so the numbers on the rows
            // that ARE shown are the same numbers they would have had with the roll-up present.
            sql.append(" SELECT ");
            for (Grouping dimension : dimensions) {
                sql.append(dimension.expression(null)).append(" AS ")
                    .append(quoteAlias(dimension.alias)).append(", ");
            }
            sql.append(measure.expression).append(" AS ").append(quoteAlias(measureAlias));
            /*
             * A plain INNER JOIN is safe here and does not duplicate rows: the ranking CTE is a
             * GROUP BY over the ranked dimension, so its key column is distinct by construction
             * and no left row can match twice. A duplicating join would show up first in count(*),
             * which the tests check.
             */
            sql.append(" FROM ").append(filtered).append(" JOIN ").append(top).append(membership);
            appendGroupBy(sql, dimensions.size(), -1);
            appendOrderBy(sql, request.getSort(), dimensions.size(), false);
            return sql.toString();
        }

        /*
         * coalesce is load-bearing: a LEFT JOIN gives an unmatched row NULL, and the marker has to
         * be FALSE. AnalysisService compares it against the string "false", and the outer GROUP BY
         * groups on it -- a NULL would make its own group and the roll-up would split in two.
         */
        sql.append(", ").append(tagged).append(" AS (SELECT ").append(filtered).append(".*, ")
            .append("coalesce(").append(top).append(".").append(quoteAlias(hit))
            .append(", FALSE) AS ").append(quoteAlias(marker))
            .append(" FROM ").append(filtered).append(" LEFT JOIN ").append(top)
            .append(membership).append(")");

        sql.append(" SELECT CASE WHEN ").append(quoteAlias(marker)).append(" THEN ")
            .append(rankedName).append(" END AS ").append(quoteAlias(ranked.alias));
        for (int i = 1; i < dimensions.size(); i++) {
            sql.append(", ").append(dimensions.get(i).expression(null)).append(" AS ")
                .append(quoteAlias(dimensions.get(i).alias));
        }
        sql.append(", ").append(measure.expression).append(" AS ").append(quoteAlias(measureAlias));
        // The three trailing columns exist for the caller and never for the reader: the marker says
        // which row is the roll-up without relying on a label a dataset could genuinely contain,
        // and the two after it say what the roll-up stands for. AnalysisService strips all three.
        sql.append(", ").append(quoteAlias(marker)).append(" AS ").append(quoteAlias(marker));
        // to_json around the list, not the list itself. DuckDB's own list rendering is
        // "[north, south]" -- unquoted, comma-and-space separated -- so a value containing ", "
        // cannot be told from two values, and the caller taking it apart would be guessing. JSON
        // has an escaping rule, and the caller has a parser for it.
        sql.append(", to_json(list_slice(list(DISTINCT ").append(rankedName).append("), 1, ")
            .append(OTHER_VALUES_REPORTED).append(")) AS ")
            .append(quoteAlias(marker + "_values"));
        /*
         * The LENGTH of the list above, not a second count(DISTINCT) over the same column.
         *
         * This used to be count(DISTINCT x) plus a "+ max(CASE WHEN x IS NULL THEN 1 ELSE 0 END)"
         * correction, because count(DISTINCT) ignores nulls while list(DISTINCT) does not -- the
         * two disagreed by exactly one whenever the group with no value was among those rolled up,
         * and the response shipped a four-element list beside a count of three.
         *
         * len(list(DISTINCT x)) counts the null group by construction, so the correction is not
         * needed and the bug it was written for cannot come back. It also collapses two distinct
         * hash tables into one: DuckDB common-subexpressions the two references to the same list.
         * Measured on 8M rows, 400,000 distinct values: 491ms to 392ms.
         */
        sql.append(", len(list(DISTINCT ").append(rankedName).append(")) AS ")
            .append(quoteAlias(marker + "_count"));
        sql.append(" FROM ").append(tagged);
        appendGroupBy(sql, dimensions.size(), dimensions.size() + 2);
        // The roll-up sorts last whatever the display order is, because it is not a peer of the
        // rows above it: it is what is left. A reader scanning a ranked list expects the remainder
        // at the bottom, and a chart that put it in the middle would imply it had a rank.
        sql.append(" ORDER BY ").append(dimensions.size() + 2).append(" DESC");
        appendOrderBy(sql, request.getSort(), dimensions.size(), true);
        return sql.toString();
    }

    /** GROUP BY the dimension ordinals, plus the roll-up marker when there is one. */
    private static void appendGroupBy(StringBuilder sql, int dimensionCount, int markerOrdinal) {
        if (dimensionCount == 0 && markerOrdinal < 0) {
            // No grouping at all is a legitimate analysis, not a missing one: it is the single
            // number a KPI card shows. An aggregate with no GROUP BY returns exactly one row.
            return;
        }
        sql.append(" GROUP BY ");
        for (int i = 1; i <= dimensionCount; i++) {
            sql.append(i == 1 ? "" : ", ").append(i);
        }
        if (markerOrdinal > 0) {
            sql.append(dimensionCount == 0 ? "" : ", ").append(markerOrdinal);
        }
    }

    /**
     * ORDER BY, written as ordinals so no name reaches the text a second time.
     *
     * NULLS LAST is explicit rather than left to the engine's default, because a missing value
     * sorting to the top of a ranked chart reads as the biggest one.
     *
     * <b>continuing is passed rather than worked out from the text.</b> The obvious implementation
     * -- look for " ORDER BY " in what has been built so far -- is wrong here and was: the Top-N
     * CTE contains an ORDER BY of its own, so a scan finds one even when the outer query has none,
     * and the clause lands on the end of a GROUP BY. The caller knows which of the two it is.
     */
    private static void appendOrderBy(StringBuilder sql, AnalysisRequest.Sort sort,
        int dimensionCount, boolean continuing) {

        if (dimensionCount == 0 && !continuing) {
            // Nothing to order. An aggregate with no grouping returns one row, and an ORDER BY over
            // one row is a line of SQL that has to be read and dismissed every time.
            return;
        }
        AnalysisRequest.Sort.By by = sort == null || sort.getBy() == null
            ? AnalysisRequest.Sort.By.MEASURE : sort.getBy();
        String direction = sort == null || sort.getDirection() == null
            ? "DESC" : sort.getDirection().name();
        sql.append(continuing ? ", " : " ORDER BY ");
        if (by == AnalysisRequest.Sort.By.DIMENSION && dimensionCount > 0) {
            // Ascending unless told otherwise would be the friendlier default for a dimension, but
            // a default that depends on which field it is applied to is a default nobody can
            // predict. The caller says, or gets the one direction this model has.
            for (int i = 1; i <= dimensionCount; i++) {
                sql.append(i == 1 ? "" : ", ").append(i).append(" ").append(direction)
                    .append(" NULLS LAST");
            }
            return;
        }
        sql.append(dimensionCount + 1).append(" ").append(direction).append(" NULLS LAST");
    }

    /**
     * The dimensions this analysis actually groups by, and the filters the drilling added.
     *
     * <b>Each step REPLACES the dimension it drilled through.</b> 07's example -- Department to
     * Engineering, then Location to Chicago, then Status to Active -- narrows three times and never
     * shows four dimensions at once, because each click answers "what is inside this" rather than
     * "what else is there". Replacing is also what makes drill-up cheap: the dimension a step
     * displaced is recorded on the step, so undoing it restores the dimension without the client
     * having had to keep it.
     *
     * <b>The clicked value becomes a filter, and a clicked null becomes IS NULL.</b> Composing it
     * as an equality would be "= NULL", which is never true, so drilling into the group a user can
     * see has rows in it would return nothing at all.
     */
    private static Drilled drill(AnalysisRequest request, FilterCompiler.Columns columns)
        throws AnalyticsException {

        /*
         * Groupings rather than bare columns, and the grain comes from the request's parallel
         * list -- which normalised() guarantees is exactly as long as the dimensions.
         *
         * The duplicate rule now compares column AND grain, so year-of and month-of the SAME date
         * column is expressible. That is a real nested time axis -- a year band above a month
         * series -- and DuckDB executes it correctly; refusing it would have been an accident of
         * how the check was written rather than a decision.
         */
        List<AnalysisRequest.Grain> requested = request.getGrains();
        List<Grouping> dimensions = new ArrayList<>();
        List<String> takenAliases = new ArrayList<>();
        for (int at = 0; at < request.getDimensions().size(); at++) {
            ColumnDto column = columns.require(request.getDimensions().get(at));
            AnalysisRequest.Grain grain = requested == null || at >= requested.size()
                ? null : requested.get(at);
            requireTemporal(column, grain);
            if (containsGrouping(dimensions, column, grain)) {
                throw new AnalyticsException("An analysis cannot group by "
                    + FilterCompiler.safeName(column.getName())
                    + (grain == null ? "" : " by " + grain.name().toLowerCase(Locale.ROOT))
                    + " twice.");
            }
            String alias = uniqueAliasAmong(aliasFor(column, grain), takenAliases);
            takenAliases.add(alias);
            dimensions.add(new Grouping(column, grain, alias));
        }

        List<FilterClause> narrowings = new ArrayList<>();
        List<AnalysisResultCrumb> crumbs = new ArrayList<>();
        List<AnalysisRequest.Drill> path = new ArrayList<>();

        for (AnalysisRequest.Drill step : request.getDrillPath()) {
            if (step == null || step.getDimension() == null) {
                throw new AnalyticsException("A drill step has to say which dimension it went "
                    + "through.");
            }
            if (step.isOtherBucket()) {
                throw new AnalyticsException("Other stands for several values at once, so it "
                    + "cannot be drilled into. Raise the Top-N, or drill into one of the values it "
                    + "lists.");
            }
            ColumnDto through = columns.require(step.getDimension());
            int at = indexOfGrouping(dimensions, through);
            // Read BEFORE the grouping is removed or replaced below: a drill through a bucketed
            // dimension narrows to the whole bucket, and after this loop mutates `dimensions` the
            // grain that produced the displayed value is gone.
            AnalysisRequest.Grain drilledGrain = at >= 0 ? dimensions.get(at).grain : null;
            if (at < 0) {
                // The client sent a path that does not fit the dimensions it also sent. Refused
                // rather than repaired: silently dropping the step would run a DIFFERENT analysis
                // from the one the breadcrumb above the chart claims to be showing.
                throw new AnalyticsException("This analysis is no longer grouped by "
                    + FilterCompiler.safeName(through.getName()) + ", so it cannot be drilled "
                    + "through it. Start again from the top.");
            }
            if (step.getNextDimension() == null || step.getNextDimension().trim().isEmpty()) {
                dimensions.remove(at);
            } else {
                ColumnDto next = columns.require(step.getNextDimension());
                if (indexOfGrouping(dimensions, next) >= 0
                    && indexOfGrouping(dimensions, next) != at) {
                    throw new AnalyticsException("This analysis already groups by "
                        + FilterCompiler.safeName(next.getName()) + ".");
                }
                // The replacing dimension takes its OWN grain from the step, not the displaced
                // one's -- a drill from month-of-booked-on into region must not bucket region.
                AnalysisRequest.Grain nextGrain = step.getNextGrain();
                requireTemporal(next, nextGrain);
                dimensions.set(at, new Grouping(next, nextGrain, aliasFor(next, nextGrain)));
            }
            if (step.getValue() == null) {
                narrowings.add(FilterClause.of(through.getName(), FilterClause.Operator.IS_NULL, null));
            } else if (drilledGrain != null) {
                narrowings.add(bucketWindow(through, drilledGrain, step.getValue()));
            } else {
                narrowings.add(FilterClause.of(through.getName(), FilterClause.Operator.EQ, step.getValue()));
            }
            crumbs.add(new AnalysisResultCrumb(through.getName(), step.getValue()));
            path.add(step);
        }

        if (dimensions.size() > AnalysisRequest.MAX_DIMENSIONS) {
            throw new AnalyticsException("An analysis groups by up to "
                + AnalysisRequest.MAX_DIMENSIONS + " dimensions at a time.");
        }

        FilterClause filters = request.getFilters();
        if (!narrowings.isEmpty()) {
            // The drill filters are ANDed with the user's own rather than merged into them, so a
            // user's OR group keeps its own brackets. Flattening the two would turn "region = north
            // OR region = south" plus a drill into "... OR region = south AND drill", which is a
            // different question with the same words in it.
            List<FilterClause> all = new ArrayList<>();
            if (filters != null) {
                all.add(filters);
            }
            all.addAll(narrowings);
            filters = FilterClause.group(FilterClause.LogicalOp.AND, all);
        }
        return new Drilled(dimensions, filters, crumbs, path);
    }

    /**
     * The narrowing for a drill through a dimension that was bucketed by a calendar grain.
     *
     * A bucketed row shows the bucket, not a value of the column: grouping booked_on by MONTH
     * displays 2024-03-01 for every row in March. Narrowing on that as an equality --
     * {@code booked_on = '2024-03-01'} -- matches midnight on the first of March and nothing else,
     * so drilling into a month with 40,000 orders in it returned the handful booked at exactly
     * that instant, or none at all, presented as the legitimate contents of March. The bucket is a
     * RANGE and has to be narrowed as one.
     *
     * Expressed as DATE_RANGE rather than as two bounds of our own so that the column's type is
     * still the compiler's business: dayWindow() already knows a TIMESTAMP needs a half-open
     * window ending at the start of the next day while a DATE takes an inclusive one, and
     * duplicating that here is how the two would drift apart.
     */
    private static FilterClause bucketWindow(ColumnDto column, AnalysisRequest.Grain grain,
        String bucketStart) throws AnalyticsException {

        LocalDate from;
        try {
            // Whatever the bucket was rendered as -- a date, or a timestamp at midnight -- the day
            // it starts on is its first ten characters.
            from = LocalDate.parse(bucketStart.trim().substring(0, 10));
        } catch (RuntimeException notADate) {
            throw new AnalyticsException("That row is bucketed by " + grain.name().toLowerCase()
                + ", so drilling into it narrows to the whole bucket -- but "
                + FilterCompiler.safeName(bucketStart) + " is not a date this can find the bucket "
                + "for.");
        }
        LocalDate toInclusive;
        switch (grain) {
            case DAY:
                toInclusive = from;
                break;
            case WEEK:
                toInclusive = from.plusDays(6);
                break;
            case MONTH:
                toInclusive = from.withDayOfMonth(from.lengthOfMonth());
                break;
            case QUARTER:
                // Two months on from the first month of the quarter, then that month's last day.
                LocalDate lastMonth = from.plusMonths(2);
                toInclusive = lastMonth.withDayOfMonth(lastMonth.lengthOfMonth());
                break;
            case YEAR:
                toInclusive = from.withDayOfYear(from.lengthOfYear());
                break;
            default:
                // A sixth grain added to the enum and forgotten here would otherwise narrow to a
                // single day and quietly answer a different question.
                throw new AnalyticsException("This analysis cannot drill through a "
                    + grain.name().toLowerCase() + " bucket.");
        }
        return FilterClause.ofValues(column.getName(), FilterClause.Operator.DATE_RANGE,
            Arrays.asList(from.toString(), toInclusive.toString()));
    }

    /**
     * The aggregate expression for a measure, and the name its column will answer to.
     *
     * Every branch checks the column can take the aggregate BEFORE the statement is built, because
     * the alternative is DuckDB's own answer: sum(DATE) is
     * "Binder Error: No function matches the given name and argument types 'sum(DATE)'" followed by
     * a list of thirty candidate signatures, which is a true statement and not an answer anyone can
     * act on from a chart menu.
     */
    private static Measure measure(AnalysisRequest.Measure requested, FilterCompiler.Columns columns)
        throws AnalyticsException {

        AnalysisRequest.Aggregation aggregation = requested.getAggregation();
        if (aggregation == AnalysisRequest.Aggregation.COUNT_ROWS) {
            return new Measure("count(*)", "row_count");
        }
        ColumnDto column = columns.require(requested.getField());
        String field = FilterCompiler.Columns.quote(column);
        String name = column.getName();
        switch (aggregation) {
            case COUNT_NON_NULL:
                return new Measure("count(" + field + ")", name + "_count");
            case DISTINCT_COUNT:
                return new Measure("count(DISTINCT " + field + ")", name + "_distinct_count");
            case SUM:
                requireNumeric(column, "summed");
                return new Measure("sum(" + exactly(column, field) + ")", name + "_sum");
            case AVERAGE:
                requireNumeric(column, "averaged");
                return new Measure("avg(" + field + ")", name + "_avg");
            case MINIMUM:
                requireOrderable(column, "a minimum");
                return new Measure("min(" + field + ")", name + "_min");
            case MAXIMUM:
                requireOrderable(column, "a maximum");
                return new Measure("max(" + field + ")", name + "_max");
            case MEDIAN:
                return new Measure(median(column, field), name + "_median");
            default:
                throw new AnalyticsException("That is not a measure this analysis can compute.");
        }
    }

    /**
     * The median, computed by whichever of DuckDB's two quantile functions returns a value the
     * column could actually hold.
     *
     * <b>quantile_cont on a DATE is wrong, and it is wrong in a way that looks right.</b> Measured
     * on 1.1.3 over four dates, quantile_cont returned <b>2024-02-15 12:00:00</b> -- a TIMESTAMP,
     * from a DATE column, at a time of day the column has no concept of. Interpolating between the
     * two middle values is the correct definition of a continuous median and it is the wrong answer
     * for a calendar: a reader shown "the median booking date" wants a date that is in the data, not
     * the midpoint between two of them rendered in a type the column does not have.
     *
     * quantile_disc returns the lower of the two middle values, keeps the column's own type
     * (measured: DATE in, DATE out), and works on VARCHAR -- where quantile_cont is a Binder Error
     * outright, because there is nothing between two strings to interpolate.
     *
     * So: continuous for numbers, where interpolation is what a median means and 2.5 is a legitimate
     * median of 2 and 3; discrete for everything orderable that is not a number. BOOLEAN is refused
     * rather than answered, because the median of true and false is a question with no useful reply.
     */
    private static String median(ColumnDto column, String field) throws AnalyticsException {
        if (FilterCompiler.Columns.isNumeric(column)) {
            return "quantile_cont(" + field + ", 0.5)";
        }
        if (FilterCompiler.Columns.isBoolean(column)) {
            throw new AnalyticsException("A median needs values that can be ordered, and "
                + FilterCompiler.safeName(column.getName()) + " is true or false.");
        }
        requireOrderable(column, "a median");
        return "quantile_disc(" + field + ", 0.5)";
    }

    /**
     * A column's values in a form that can be TOTALLED without accumulating float error.
     *
     * <b>The defect this exists for, measured.</b> DuckDB's CSV reader types a column of
     * "1999.20" as DOUBLE, because nothing in a text file says the writer meant two decimal
     * places; Parquet carries the DECIMAL(12,2) it was written with. Summing 250,000 of the
     * former gives 103909527.57999855 where the latter gives 103909527.58. Under two thousandths
     * of a penny at that size, and it grows with the row count -- which is exactly the wrong
     * direction for a figure somebody puts in front of a finance team.
     *
     * <b>Why the route is through VARCHAR.</b> DuckDB renders a DOUBLE to text as the shortest
     * decimal that round-trips -- 1999.20 as a double prints "1999.2" -- and VARCHAR to DECIMAL
     * never touches binary floating point. So this recovers the number the writer wrote. It is
     * the same trick plainNumber() already plays in Java with BigDecimal.valueOf(double), for
     * the same reason.
     *
     * <b>Why not a plain CAST to DECIMAL, which is shorter.</b> There is no correct scale.
     * Measured: at scale 2 it is exact for this file and destroys four-decimal data (1.2345
     * becomes 1.23, a 0.36% error introduced to fix a 1e-16 one). At scale 10 it is exact at
     * hundreds and wrong at thirteen significant digits. The text route at scale 15 was exact for
     * both, and exact for a genuine float column too.
     *
     * <b>Why not fix the READ instead, which would fix every path at once.</b> Because it was
     * tried and measured, and it is worse. Steering the sniffer with auto_type_candidates types a
     * column of 3.14159265358979 as DECIMAL(18,3) and reads it as 3.142 -- a 0.013% error
     * manufactured by the fix. It also turns a file that reads today into a hard failure when a
     * large value appears past the sniff sample. Forcing types= needs a schema and a scale that
     * nothing knows. This change is narrow on purpose: it touches SUM and nothing else.
     *
     * <b>What is deliberately NOT fixed here.</b> AVERAGE cannot be: avg() over a DECIMAL still
     * returns DOUBLE in 1.1.3, so routing its argument would look like a fix and not be one.
     * MIN and MAX never accumulate error and are already exact. The honest thing is to leave them
     * and say so rather than appear to have covered them.
     *
     * A column that is already DECIMAL, or an integer, is returned untouched -- there is nothing
     * to recover and the cast would only cost a scan.
     */
    private static String exactly(ColumnDto column, String field) {
        if (!FilterCompiler.Columns.isBinaryFloat(column)) {
            return field;
        }
        return "CAST(CAST(" + field + " AS VARCHAR) AS DECIMAL(38,15))";
    }

    private static void requireNumeric(ColumnDto column, String verb) throws AnalyticsException {
        if (!FilterCompiler.Columns.isNumeric(column)) {
            throw new AnalyticsException(FilterCompiler.safeName(column.getName()) + " holds "
                + column.getType() + ", which cannot be " + verb + ". Count it, or count how many "
                + "different values it has.");
        }
    }

    private static void requireOrderable(ColumnDto column, String what) throws AnalyticsException {
        if (!FilterCompiler.Columns.isOrderable(column)) {
            throw new AnalyticsException(FilterCompiler.safeName(column.getName()) + " holds "
                + column.getType() + ", which has no order, so " + what + " of it means nothing.");
        }
    }

    /**
     * The measure's column name, moved out of the way if a dimension already answers to it.
     *
     * A dataset is allowed to have a column called amount_sum, and grouping by it while summing
     * amount would put two columns of that name in one result -- which a client reading by name
     * resolves to whichever it meets first. Rare enough that nobody would think of it, cheap enough
     * that nobody has to.
     */
    private static boolean containsGrouping(List<Grouping> groupings, ColumnDto column,
        AnalysisRequest.Grain grain) {

        for (Grouping grouping : groupings) {
            if (grouping.column.getName().equalsIgnoreCase(column.getName())
                && grouping.grain == grain) {
                return true;
            }
        }
        return false;
    }

    /** Where a column is grouped, whatever grain it is at. A drill names a column, not a bucket. */
    private static int indexOfGrouping(List<Grouping> groupings, ColumnDto column) {
        for (int at = 0; at < groupings.size(); at++) {
            if (groupings.get(at).column.getName().equalsIgnoreCase(column.getName())) {
                return at;
            }
        }
        return -1;
    }

    private static String uniqueAlias(String proposed, List<ColumnDto> dimensions) {
        String alias = proposed;
        int suffix = 1;
        while (clashes(alias, dimensions)) {
            alias = proposed + "_" + suffix++;
        }
        return alias;
    }

    /** The same, against a list of aliases already taken rather than against column objects. */
    private static String uniqueAliasAmong(String proposed, List<String> taken) {
        String alias = proposed;
        int suffix = 1;
        while (containsIgnoringCase(taken, alias)) {
            alias = proposed + "_" + suffix++;
        }
        return alias;
    }

    private static boolean containsIgnoringCase(List<String> haystack, String needle) {
        for (String candidate : haystack) {
            if (needle.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean clashes(String alias, List<ColumnDto> dimensions) {
        for (ColumnDto dimension : dimensions) {
            if (alias.equalsIgnoreCase(dimension.getName())) {
                return true;
            }
        }
        return false;
    }

    /** An internal name the dataset does not already use, so "filtered.*" cannot collide with it. */
    private static String free(String proposed, FilterCompiler.Columns columns) {
        String name = proposed;
        int suffix = 1;
        while (used(name, columns)) {
            name = proposed + "_" + suffix++;
        }
        return name;
    }

    private static boolean used(String name, FilterCompiler.Columns columns) {
        for (ColumnDto column : columns.all()) {
            if (column.getName() != null && column.getName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(List<ColumnDto> columns, ColumnDto column) {
        return indexOf(columns, column) >= 0;
    }

    private static int indexOf(List<ColumnDto> columns, ColumnDto column) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getName().equals(column.getName())) {
                return i;
            }
        }
        return -1;
    }

    /** The schema's own field names, which stay the identity a drill step and a filter use. */
    private static List<String> names(List<Grouping> groupings) {
        List<String> found = new ArrayList<>();
        for (Grouping grouping : groupings) {
            found.add(grouping.column.getName());
        }
        return found;
    }

    /** What the RESULT columns are labelled, which is the name plus the grain where there is one. */
    private static List<String> aliases(List<Grouping> groupings) {
        List<String> found = new ArrayList<>();
        for (Grouping grouping : groupings) {
            found.add(grouping.alias);
        }
        return found;
    }

    /** The grain each grouping was bucketed at, index-aligned with the dimensions. */
    private static List<AnalysisRequest.Grain> grainsOf(List<Grouping> groupings) {
        List<AnalysisRequest.Grain> found = new ArrayList<>();
        for (Grouping grouping : groupings) {
            found.add(grouping.grain);
        }
        return found;
    }

    private static List<String> namesOfColumns(List<ColumnDto> columns) {
        List<String> names = new ArrayList<>(columns.size());
        for (ColumnDto column : columns) {
            names.add(column.getName());
        }
        return names;
    }

    /**
     * An output name, quoted the same way an identifier is.
     *
     * The alias is derived from a schema name, so it carries whatever the file's header carried --
     * a space, a bracket, a quote. Quoting it means the result's column labels are the names a user
     * recognises rather than a mangled version of them.
     */
    private static String quoteAlias(String alias) {
        return "\"" + alias.replace("\"", "\"\"") + "\"";
    }

    /** An aggregate and the name its column takes. */
    private static final class Measure {

        private final String expression;
        private final String alias;

        private Measure(String expression, String alias) {
            this.expression = expression;
            this.alias = alias;
        }
    }

    /** What the drill path did to the dimensions, and what it added to the filters. */
    /**
     * One grouping column, and how it is bucketed.
     *
     * <b>A carrier rather than two parallel lists inside the builder.</b> The request models the
     * grains as a list index-aligned with the dimensions, which is right there because a drill
     * replaces a dimension by index. Inside the builder the pair travels together through five
     * call sites in the Top-N path alone, and two lists that must stay the same length across
     * that many hops is an invitation for exactly one of them to be updated.
     */
    private static final class Grouping {

        private final ColumnDto column;
        /** Null means grouped by the column's own values, which is what everything did before. */
        private final AnalysisRequest.Grain grain;
        private final String alias;

        private Grouping(ColumnDto column, AnalysisRequest.Grain grain, String alias) {
            this.column = column;
            this.grain = grain;
            this.alias = alias;
        }

        /**
         * The expression to group by, optionally qualified by a CTE name.
         *
         * The qualifier exists for the Top-N path, where the same grouping has to be written both
         * bare (in the ranking CTE's own SELECT) and qualified (against the filtered CTE on the
         * other side of the membership test). Both sides must be grained or the ranking buckets
         * days while the membership compares months, and nothing matches.
         */
        private String expression(String qualifier) {
            String field = (qualifier == null ? "" : qualifier + ".")
                + FilterCompiler.Columns.quote(this.column);
            return this.grain == null
                ? field
                : "date_trunc('" + this.grain.getPart() + "', " + field + ")";
        }

        /** The raw column, unbucketed. The null test in the roll-up count needs this. */
        private String raw() {
            return FilterCompiler.Columns.quote(this.column);
        }
    }

    /**
     * The default alias for a grouping: the column's own name, or the name and the grain.
     *
     * booked_on grouped by month is booked_on_month, so a reader can see from the column heading
     * that they are looking at months and not days -- which a bare "booked_on" over the first of
     * every month would not tell them.
     */
    private static String aliasFor(ColumnDto column, AnalysisRequest.Grain grain) {
        return grain == null
            ? column.getName()
            : column.getName() + "_" + grain.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Refuses a grain on a column that has no calendar in it.
     *
     * Before the statement is built, for the reason measure() gives about its own type checks: the
     * alternative is a DuckDB Binder Error arriving at somebody who picked "by month" from a menu,
     * naming a function they did not call. TIME lands here too -- date_trunc over a TIME is a
     * binder error in 1.1.3, measured, not assumed.
     */
    private static void requireTemporal(ColumnDto column, AnalysisRequest.Grain grain)
        throws AnalyticsException {

        if (grain == null) {
            return;
        }
        if (!FilterCompiler.Columns.isDate(column) && !FilterCompiler.Columns.isTimestamp(column)) {
            throw new AnalyticsException(FilterCompiler.safeName(column.getName()) + " holds "
                + column.getType() + ", which has no calendar in it, so it cannot be grouped by "
                + grain.name().toLowerCase(Locale.ROOT) + ".");
        }
    }

    private static final class Drilled {

        private final List<Grouping> dimensions;
        private final FilterClause filters;
        private final List<AnalysisResultCrumb> crumbs;
        private final List<AnalysisRequest.Drill> drillPath;

        private Drilled(List<Grouping> dimensions, FilterClause filters,
            List<AnalysisResultCrumb> crumbs, List<AnalysisRequest.Drill> drillPath) {
            this.dimensions = dimensions;
            this.filters = filters;
            this.crumbs = crumbs;
            this.drillPath = drillPath;
        }
    }

    /**
     * One breadcrumb, as the builder knows it: a field and the value that was clicked.
     *
     * A plain carrier rather than the response DTO, so the builder does not have to know how a
     * crumb is labelled or serialised. AnalysisService turns these into the response's crumbs and
     * adds the root.
     */
    public static final class AnalysisResultCrumb {

        private final String field;
        private final String value;

        AnalysisResultCrumb(String field, String value) {
            this.field = field;
            this.value = value;
        }

        public String getField() {
            return this.field;
        }

        public String getValue() {
            return this.value;
        }
    }

    /**
     * The statement, and everything needed to read what comes back from it.
     *
     * The three indexes are the awkward part and they are here rather than guessed at the far end:
     * a Top-N roll-up adds a marker column and two more describing what was rolled up, and the only
     * honest way to find them in the result is to be told where they are. AnalysisService uses them
     * to build the Other bucket and then drops those columns, so nothing internal reaches a client.
     */
    public static final class Plan {

        private final AnalyticsEngine.BoundStatement statement;
        private final List<String> dimensions;
        /** What the result columns are LABELLED, which differs from the names once a grain is on. */
        private final List<String> dimensionAliases;
        /** The grain each dimension was bucketed at, index-aligned. Null entries are ungrained. */
        private final List<AnalysisRequest.Grain> grains;
        private final String measureAlias;
        private final int visibleColumns;
        private final int rollupMarkerIndex;
        private final int otherValuesIndex;
        private final int otherCountIndex;
        private final Map<String, String> resolvedWindows;
        private final List<AnalysisResultCrumb> crumbs;
        private final List<AnalysisRequest.Drill> drillPath;

        Plan(AnalyticsEngine.BoundStatement statement, List<String> dimensions,
            List<String> dimensionAliases, List<AnalysisRequest.Grain> grains, String measureAlias,
            int visibleColumns, int rollupMarkerIndex, int otherValuesIndex, int otherCountIndex,
            Map<String, String> resolvedWindows, List<AnalysisResultCrumb> crumbs,
            List<AnalysisRequest.Drill> drillPath) {

            this.statement = statement;
            this.dimensions = Collections.unmodifiableList(dimensions);
            this.dimensionAliases = Collections.unmodifiableList(dimensionAliases);
            this.grains = Collections.unmodifiableList(grains);
            this.measureAlias = measureAlias;
            this.visibleColumns = visibleColumns;
            this.rollupMarkerIndex = rollupMarkerIndex;
            this.otherValuesIndex = otherValuesIndex;
            this.otherCountIndex = otherCountIndex;
            this.resolvedWindows = Collections.unmodifiableMap(
                resolvedWindows == null ? new LinkedHashMap<>() : resolvedWindows);
            this.crumbs = Collections.unmodifiableList(crumbs);
            this.drillPath = Collections.unmodifiableList(drillPath);
        }

        public AnalyticsEngine.BoundStatement getStatement() { return this.statement; }

        /** The dimensions this analysis groups by after drilling, in the schema's own spelling. */
        public List<String> getDimensions() { return this.dimensions; }

        public List<String> getDimensionAliases() { return this.dimensionAliases; }

        /**
         * The grain each dimension was bucketed at, or null where it was not.
         *
         * Index-aligned with getDimensions(). Answers the one question a reader cannot get from
         * the rows: whether a column of first-of-the-months is months, or days that happen to
         * fall on the first.
         */
        public List<AnalysisRequest.Grain> getGrains() { return this.grains; }

        public String getMeasureAlias() { return this.measureAlias; }

        /** How many leading columns of the result belong to the reader. */
        public int getVisibleColumns() { return this.visibleColumns; }

        /** Where the roll-up marker sits in the result, or -1 when nothing was rolled up. */
        public int getRollupMarkerIndex() { return this.rollupMarkerIndex; }

        public int getOtherValuesIndex() { return this.otherValuesIndex; }

        public int getOtherCountIndex() { return this.otherCountIndex; }

        /** What each relative window resolved to, so the response can say which days it used. */
        public Map<String, String> getResolvedWindows() { return this.resolvedWindows; }

        public List<AnalysisResultCrumb> getCrumbs() { return this.crumbs; }

        public List<AnalysisRequest.Drill> getDrillPath() { return this.drillPath; }

        /** The composed SQL. On the plan for tests and for a log line, never for a caller to edit. */
        public String getSql() { return this.statement.getSql(); }
    }
}

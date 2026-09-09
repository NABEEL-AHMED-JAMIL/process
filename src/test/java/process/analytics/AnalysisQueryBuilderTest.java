package process.analytics;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import process.analytics.canvas.AnalysisQueryBuilder;
import process.analytics.canvas.AnalysisRequest;
import process.analytics.canvas.FilterClause;
import process.analytics.dto.ColumnDto;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * What the builder writes, and what it refuses to write.
 *
 * <b>Two claims are checked about every statement here and they are different claims.</b> The first
 * is the shape -- that a two-dimension SUM is one GROUP BY over two ordinals and not something else.
 * The second is that DuckDB's own parser accepts it as exactly one READ that names nothing but the
 * bound view, which is asserted by putting the finished text through
 * {@link StatementGate#confirmComposed} on a real session. That check is the layer standing behind
 * the field allow-list: a builder bug that let a name become an expression, a second statement or a
 * location would be caught there even if it got past FilterCompiler, and it is the same check that
 * did not exist for schema_name in phase three.
 *
 * The refusals are as much of the subject as the SQL. Left to the engine, summing a DATE column is
 * "Binder Error: No function matches the given name and argument types 'sum(DATE)'" plus thirty
 * candidate signatures -- true, and no use at all to somebody who picked Sum off a menu.
 *
 * @author Nabeel Ahmed
 */
class AnalysisQueryBuilderTest {

    private static final List<ColumnDto> SCHEMA = Arrays.asList(
        new ColumnDto("region", "VARCHAR"),
        new ColumnDto("city", "VARCHAR"),
        new ColumnDto("status", "VARCHAR"),
        new ColumnDto("amount", "DOUBLE"),
        new ColumnDto("qty", "INTEGER"),
        new ColumnDto("booked_on", "DATE"),
        new ColumnDto("active", "BOOLEAN"));

    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    /** A real session with the view the builder composes against, for the parser check. */
    private static Connection engine;

    @BeforeAll
    static void openEngine() throws Exception {
        engine = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement statement = engine.createStatement()) {
            statement.execute("CREATE OR REPLACE TEMP VIEW dataset AS SELECT * FROM (VALUES "
                + "('north','oslo','active',10.5,1,DATE '2024-01-01',true),"
                + "('north','bergen','closed',21.0,2,DATE '2024-02-01',false),"
                + "('south','rome','active',42.0,3,DATE '2024-03-01',true),"
                + "('south','rome','closed',7.0,4,DATE '2024-04-01',true),"
                + "('east','lima','active',1.0,5,DATE '2024-05-01',false)"
                + ") AS t(region, city, status, amount, qty, booked_on, active)");
        }
    }

    @AfterAll
    static void closeEngine() throws Exception {
        engine.close();
    }

    // ---- one, two and three dimensions ----------------------------------------------------------

    @Test
    void oneDimensionIsOneGroupByOverOneOrdinal() throws Exception {
        AnalysisQueryBuilder.Plan plan = plan(analysis(
            dimensions("region"), measure("amount", AnalysisRequest.Aggregation.SUM)));

        assertThat(plan.getSql()).isEqualTo(
            "SELECT \"region\" AS \"region\", sum(\"amount\") AS \"amount_sum\" "
            + "FROM dataset GROUP BY 1 ORDER BY 2 DESC NULLS LAST");
        assertThat(plan.getDimensions()).containsExactly("region");
        assertThat(plan.getMeasureAlias()).isEqualTo("amount_sum");
        assertThat(rows(plan)).hasSize(3);
    }

    @Test
    void twoAndThreeDimensionsNestInTheOrderTheyWereGiven() throws Exception {
        AnalysisQueryBuilder.Plan two = plan(analysis(
            dimensions("region", "status"), measure(null, AnalysisRequest.Aggregation.COUNT_ROWS)));

        assertThat(two.getSql()).isEqualTo(
            "SELECT \"region\" AS \"region\", \"status\" AS \"status\", count(*) AS \"row_count\" "
            + "FROM dataset GROUP BY 1, 2 ORDER BY 3 DESC NULLS LAST");

        AnalysisQueryBuilder.Plan three = plan(analysis(
            dimensions("region", "status", "city"),
            measure(null, AnalysisRequest.Aggregation.COUNT_ROWS)));

        assertThat(three.getSql()).contains("GROUP BY 1, 2, 3").contains("ORDER BY 4 DESC");
        assertThat(three.getDimensions()).containsExactly("region", "status", "city");
        assertThat(rows(three)).hasSize(5);
    }

    @Test
    void aFourthDimensionIsRefusedBecauseThatIsWhereAResultStopsBeingOne() throws Exception {
        assertThat(refusedBy(analysis(dimensions("region", "status", "city", "active"),
            measure(null, AnalysisRequest.Aggregation.COUNT_ROWS))))
            .contains("up to 3 dimensions");
    }

    @Test
    void noDimensionsAtAllIsOneRowAndNoGroupByAndNoOrderBy() throws Exception {
        AnalysisQueryBuilder.Plan plan = plan(analysis(
            dimensions(), measure("amount", AnalysisRequest.Aggregation.SUM)));

        // This is a KPI card, not a missing analysis. An aggregate with no GROUP BY returns exactly
        // one row, and ordering one row is a clause a reader has to dismiss every time.
        assertThat(plan.getSql())
            .isEqualTo("SELECT sum(\"amount\") AS \"amount_sum\" FROM dataset");
        assertThat(rows(plan)).hasSize(1);
    }

    @Test
    void theSameDimensionTwiceIsRefused() throws Exception {
        assertThat(refusedBy(analysis(dimensions("region", "region"),
            measure(null, AnalysisRequest.Aggregation.COUNT_ROWS)))).contains("twice");
    }

    // ---- the eight aggregations -----------------------------------------------------------------

    @Test
    void eachOfTheEightMeasuresCompilesToItsOwnAggregateAndItsOwnColumnName() throws Exception {
        assertThat(measureSql(AnalysisRequest.Aggregation.COUNT_ROWS, null))
            .isEqualTo("count(*) AS \"row_count\"");
        assertThat(measureSql(AnalysisRequest.Aggregation.COUNT_NON_NULL, "amount"))
            .isEqualTo("count(\"amount\") AS \"amount_count\"");
        assertThat(measureSql(AnalysisRequest.Aggregation.DISTINCT_COUNT, "city"))
            .isEqualTo("count(DISTINCT \"city\") AS \"city_distinct_count\"");
        assertThat(measureSql(AnalysisRequest.Aggregation.SUM, "amount"))
            .isEqualTo("sum(\"amount\") AS \"amount_sum\"");
        assertThat(measureSql(AnalysisRequest.Aggregation.AVERAGE, "amount"))
            .isEqualTo("avg(\"amount\") AS \"amount_avg\"");
        assertThat(measureSql(AnalysisRequest.Aggregation.MINIMUM, "booked_on"))
            .isEqualTo("min(\"booked_on\") AS \"booked_on_min\"");
        assertThat(measureSql(AnalysisRequest.Aggregation.MAXIMUM, "booked_on"))
            .isEqualTo("max(\"booked_on\") AS \"booked_on_max\"");
        assertThat(measureSql(AnalysisRequest.Aggregation.MEDIAN, "amount"))
            .isEqualTo("quantile_cont(\"amount\", 0.5) AS \"amount_median\"");
    }

    @Test
    void countRowsIgnoresTheFieldAndTheOtherSevenRequireOne() throws Exception {
        // COUNT_ROWS with a field set is not an error: the field is simply not part of the question.
        AnalysisRequest counting = analysis(dimensions("region"),
            measure("amount", AnalysisRequest.Aggregation.COUNT_ROWS));
        assertThat(plan(counting).getSql()).contains("count(*)").doesNotContain("\"amount\"");

        assertThat(refusedBy(analysis(dimensions("region"),
            measure(null, AnalysisRequest.Aggregation.SUM)))).contains("needs a column to measure");
    }

    @Test
    void aMedianOnADateUsesTheQuantileThatReturnsADateThatExists() throws Exception {
        // Measured on DuckDB 1.1.3: quantile_cont over four dates returns 2024-02-15 12:00:00 -- a
        // TIMESTAMP, from a DATE column, at a time of day the column has no concept of. The
        // continuous median is the correct definition and the wrong answer for a calendar.
        assertThat(measureSql(AnalysisRequest.Aggregation.MEDIAN, "booked_on"))
            .isEqualTo("quantile_disc(\"booked_on\", 0.5) AS \"booked_on_median\"");
        assertThat(measureSql(AnalysisRequest.Aggregation.MEDIAN, "region"))
            .isEqualTo("quantile_disc(\"region\", 0.5) AS \"region_median\"");

        AnalysisQueryBuilder.Plan plan = plan(analysis(dimensions(),
            measure("booked_on", AnalysisRequest.Aggregation.MEDIAN)));
        // The value comes back as a DATE and is one of the five in the fixture, which is the whole
        // point of choosing the discrete quantile.
        assertThat(rows(plan).get(0).get(0)).isEqualTo("2024-03-01");
    }

    @Test
    void aMedianOfTrueAndFalseIsRefusedRatherThanAnswered() throws Exception {
        assertThat(refusedBy(analysis(dimensions(),
            measure("active", AnalysisRequest.Aggregation.MEDIAN)))).contains("true or false");
    }

    @Test
    void summingAndAveragingAreRefusedOnColumnsThatHoldNoNumbers() throws Exception {
        assertThat(refusedBy(analysis(dimensions("region"),
            measure("booked_on", AnalysisRequest.Aggregation.SUM))))
            .contains("holds DATE").contains("cannot be summed");
        assertThat(refusedBy(analysis(dimensions("region"),
            measure("city", AnalysisRequest.Aggregation.AVERAGE))))
            .contains("cannot be averaged");
        // And the control: counting them is exactly what a text column is for.
        assertThat(plan(analysis(dimensions("region"),
            measure("city", AnalysisRequest.Aggregation.DISTINCT_COUNT))).getSql())
            .contains("count(DISTINCT \"city\")");
    }

    @Test
    void aMeasureWhoseColumnNameCollidesWithADimensionIsMovedOutOfTheWay() throws Exception {
        List<ColumnDto> awkward = Arrays.asList(
            new ColumnDto("amount", "DOUBLE"), new ColumnDto("amount_sum", "VARCHAR"));

        AnalysisQueryBuilder.Plan plan = new AnalysisQueryBuilder(TODAY).plan(analysis(
            dimensions("amount_sum"), measure("amount", AnalysisRequest.Aggregation.SUM)), awkward);

        // Two columns of one name in a result resolve, for a client reading by name, to whichever
        // it meets first. A dataset is allowed to have a column called amount_sum.
        assertThat(plan.getMeasureAlias()).isEqualTo("amount_sum_1");
        assertThat(plan.getSql()).contains("AS \"amount_sum_1\"");
    }

    // ---- filters, sort and the parser behind them -----------------------------------------------

    @Test
    void aFilterBecomesAWhereClauseWithItsValuesStillBound() throws Exception {
        AnalysisRequest request = analysis(dimensions("region"),
            measure("amount", AnalysisRequest.Aggregation.SUM));
        request.setFilters(FilterClause.group(FilterClause.LogicalOp.AND, Arrays.asList(
            FilterClause.of("status", FilterClause.Operator.EQ, "active"),
            FilterClause.of("amount", FilterClause.Operator.GT, "5"))));

        AnalysisQueryBuilder.Plan plan = plan(request);

        assertThat(plan.getSql()).contains(
            "WHERE (\"status\" = ? AND \"amount\" > ?) GROUP BY 1");
        assertThat(plan.getStatement().getParameters())
            .containsExactly("active", new BigDecimal("5"));
        assertThat(plan.getSql()).doesNotContain("active");
        assertThat(rows(plan)).hasSize(2);
    }

    @Test
    void theSortNamesAnOrdinalAndAlwaysSaysWhereTheNullsGo() throws Exception {
        AnalysisRequest byDimension = analysis(dimensions("region", "status"),
            measure(null, AnalysisRequest.Aggregation.COUNT_ROWS));
        byDimension.setSort(new AnalysisRequest.Sort(
            AnalysisRequest.Sort.By.DIMENSION, AnalysisRequest.Sort.Direction.ASC));

        assertThat(plan(byDimension).getSql())
            .endsWith("ORDER BY 1 ASC NULLS LAST, 2 ASC NULLS LAST");

        AnalysisRequest byMeasure = analysis(dimensions("region"),
            measure("amount", AnalysisRequest.Aggregation.SUM));
        byMeasure.setSort(new AnalysisRequest.Sort(
            AnalysisRequest.Sort.By.MEASURE, AnalysisRequest.Sort.Direction.ASC));

        assertThat(plan(byMeasure).getSql()).endsWith("ORDER BY 2 ASC NULLS LAST");
    }

    @Test
    void everyStatementThisBuilderWritesIsOneReadThatNamesNothingButTheDataset() throws Exception {
        // The layer behind the field allow-list, asserted on the real parser. Nothing here can be
        // two statements, and nothing can carry a location: both would be refused by the same gate
        // that judges a user's own SQL, which is the point of composing against a view rather than
        // against a scan expression.
        List<AnalysisRequest> everything = Arrays.asList(
            analysis(dimensions("region"), measure("amount", AnalysisRequest.Aggregation.SUM)),
            analysis(dimensions(), measure(null, AnalysisRequest.Aggregation.COUNT_ROWS)),
            analysis(dimensions("region", "status", "city"),
                measure("city", AnalysisRequest.Aggregation.DISTINCT_COUNT)),
            topNRequest(2, true),
            topNRequest(2, false),
            filtered(),
            drilled());

        for (AnalysisRequest request : everything) {
            String sql = plan(request).getSql();
            // Both forms the engine actually runs: the composed statement, and the same thing
            // wrapped in the row ceiling.
            StatementGate.confirmComposed(engine, sql, 5);
            StatementGate.confirmComposed(engine,
                "SELECT * FROM (" + sql + ") AS bounded_query LIMIT 100", 5);
        }
    }

    // ---- Top-N ----------------------------------------------------------------------------------

    @Test
    void topNRollsTheRemainderUpFromTheRawRowsRatherThanTruncatingTheResult() throws Exception {
        AnalysisQueryBuilder.Plan plan = plan(topNRequest(1, true));

        assertThat(plan.getSql())
            .contains("IS NOT DISTINCT FROM")
            .contains("ORDER BY sum(\"amount\") DESC NULLS LAST LIMIT 1")
            .contains("CASE WHEN \"analysis_in_top\" THEN \"region\" END AS \"region\"")
            .contains("to_json(list_slice(list(DISTINCT \"region\"), 1, 200))")
            .contains("count(DISTINCT \"region\")");

        List<List<String>> rows = rows(plan);
        // Two rows: the one value in the top, and one roll-up standing for the other two. The
        // roll-up's measure is a sum over ITS raw rows -- 10.5 + 21.0 + 1.0 -- and not the sum of
        // two already-aggregated numbers, which is what a truncation would have had to do.
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly("south", "49.0", "true", "[\"south\"]", "1");
        assertThat(rows.get(1).get(0)).isNull();
        assertThat(rows.get(1).get(1)).isEqualTo("32.5");
        assertThat(rows.get(1).get(2)).isEqualTo("false");
        assertThat(rows.get(1).get(3)).contains("\"north\"").contains("\"east\"");
        assertThat(rows.get(1).get(4)).isEqualTo("2");

        // And the caller is told where the three internal columns are, because they are found by
        // position and never by name.
        assertThat(plan.getVisibleColumns()).isEqualTo(2);
        assertThat(plan.getRollupMarkerIndex()).isEqualTo(2);
        assertThat(plan.getOtherValuesIndex()).isEqualTo(3);
        assertThat(plan.getOtherCountIndex()).isEqualTo(4);
    }

    @Test
    void aTopNWithoutTheOtherBucketNarrowsAndStillAggregatesFromTheRawRows() throws Exception {
        AnalysisQueryBuilder.Plan plan = plan(topNRequest(1, false));

        assertThat(plan.getSql()).contains("WHERE EXISTS").doesNotContain("CASE WHEN");
        assertThat(plan.getRollupMarkerIndex()).isEqualTo(-1);

        List<List<String>> rows = rows(plan);
        assertThat(rows).hasSize(1);
        // The same 49.0 as the roll-up test: the rows that ARE shown carry the number they would
        // have carried with the roll-up present.
        assertThat(rows.get(0)).containsExactly("south", "49.0");
    }

    @Test
    void aTopNIsRankedByTheMeasureDescendingWhateverTheDisplaySortSays() throws Exception {
        AnalysisRequest request = topNRequest(1, false);
        request.setSort(new AnalysisRequest.Sort(
            AnalysisRequest.Sort.By.MEASURE, AnalysisRequest.Sort.Direction.ASC));

        // "Top" means largest. A user who sorted ascending for readability has not asked for the
        // bottom one, and a bottom-N is deliberately left unexpressible rather than smuggled into
        // a field that means something else.
        String sql = plan(request).getSql();
        assertThat(sql).contains("ORDER BY sum(\"amount\") DESC NULLS LAST LIMIT 1");
        assertThat(sql).endsWith("ORDER BY 2 ASC NULLS LAST");
    }

    @Test
    void theRollUpRowSortsLastNoMatterHowTheRowsAboveItAreOrdered() throws Exception {
        AnalysisRequest request = topNRequest(2, true);
        request.setSort(new AnalysisRequest.Sort(
            AnalysisRequest.Sort.By.MEASURE, AnalysisRequest.Sort.Direction.ASC));

        assertThat(plan(request).getSql()).endsWith("ORDER BY 3 DESC, 2 ASC NULLS LAST");
        // The remainder is not a peer of the rows above it, so it goes at the bottom either way.
        assertThat(rows(plan(request)).get(2).get(0)).isNull();
    }

    @Test
    void aTopNNeedsADimensionAndAWorkableLimit() throws Exception {
        AnalysisRequest noDimension = analysis(dimensions(),
            measure("amount", AnalysisRequest.Aggregation.SUM));
        noDimension.setTopN(topN(5, true));
        assertThat(refusedBy(noDimension)).contains("has none to rank");

        assertThat(refusedBy(topNRequest(0, true))).contains("between 1 and 10000");
        assertThat(refusedBy(topNRequest(999999, true))).contains("between 1 and 10000");
    }

    @Test
    void aDatasetWithAColumnCalledAnalysisInTopDoesNotCollideWithTheMarker() throws Exception {
        List<ColumnDto> awkward = Arrays.asList(
            new ColumnDto("region", "VARCHAR"),
            new ColumnDto("amount", "DOUBLE"),
            new ColumnDto("analysis_in_top", "VARCHAR"));

        AnalysisQueryBuilder.Plan plan = new AnalysisQueryBuilder(TODAY)
            .plan(topNRequest(1, true), awkward);

        // "filtered.*" projects every column the file has, so an unlucky header would otherwise
        // put two columns of one name in the result and change what the statement means.
        assertThat(plan.getSql()).contains("\"analysis_in_top_1\"");
    }

    // ---- drilling ------------------------------------------------------------------------------

    @Test
    void aDrillReplacesTheDimensionItWentThroughAndFiltersOnTheClickedValue() throws Exception {
        AnalysisRequest request = analysis(dimensions("region"),
            measure("amount", AnalysisRequest.Aggregation.SUM));
        request.setDrillPath(new ArrayList<>(Collections.singletonList(
            new AnalysisRequest.Drill("region", "south", "city"))));

        AnalysisQueryBuilder.Plan plan = plan(request);

        assertThat(plan.getDimensions()).containsExactly("city");
        assertThat(plan.getSql()).contains("\"city\" AS \"city\"").contains("WHERE (\"region\" = ?)");
        assertThat(plan.getStatement().getParameters()).containsExactly("south");
        assertThat(plan.getCrumbs()).hasSize(1);
        assertThat(plan.getCrumbs().get(0).getField()).isEqualTo("region");
        assertThat(plan.getCrumbs().get(0).getValue()).isEqualTo("south");
        // One row: rome, the only city in the south.
        assertThat(rows(plan)).hasSize(1);
        assertThat(rows(plan).get(0)).containsExactly("rome", "49.0");
    }

    @Test
    void threeDrillsInARowStayInsideTheDimensionCeiling() throws Exception {
        // 07's own example: Department to Engineering, Location to Chicago, Status to Active. Each
        // step spends the dimension it drilled, so a chain of any length never accumulates.
        AnalysisRequest request = analysis(dimensions("region"),
            measure(null, AnalysisRequest.Aggregation.COUNT_ROWS));
        request.setDrillPath(new ArrayList<>(Arrays.asList(
            new AnalysisRequest.Drill("region", "south", "city"),
            new AnalysisRequest.Drill("city", "rome", "status"),
            new AnalysisRequest.Drill("status", "closed", null))));

        AnalysisQueryBuilder.Plan plan = plan(request);

        assertThat(plan.getDimensions()).isEmpty();
        assertThat(plan.getStatement().getParameters()).containsExactly("south", "rome", "closed");
        assertThat(plan.getCrumbs()).hasSize(3);
        assertThat(rows(plan)).hasSize(1);
        assertThat(rows(plan).get(0)).containsExactly("1");
    }

    @Test
    void aDrillIntoAGroupWithNoValueBecomesIsNullAndNotAnEqualityAgainstNothing() throws Exception {
        AnalysisRequest request = analysis(dimensions("region"),
            measure(null, AnalysisRequest.Aggregation.COUNT_ROWS));
        request.setDrillPath(new ArrayList<>(Collections.singletonList(
            new AnalysisRequest.Drill("region", null, "city"))));

        // "= NULL" is never true, so the user would have clicked a group they can see has rows in
        // it and been handed an empty analysis.
        assertThat(plan(request).getSql()).contains("WHERE (\"region\" IS NULL)");
        assertThat(plan(request).getStatement().getParameters()).isEmpty();
    }

    @Test
    void theUsersOwnFiltersKeepTheirBracketsWhenADrillIsAddedToThem() throws Exception {
        AnalysisRequest request = analysis(dimensions("region"),
            measure(null, AnalysisRequest.Aggregation.COUNT_ROWS));
        request.setFilters(FilterClause.group(FilterClause.LogicalOp.OR, Arrays.asList(
            FilterClause.of("status", FilterClause.Operator.EQ, "active"),
            FilterClause.of("status", FilterClause.Operator.EQ, "closed"))));
        request.setDrillPath(new ArrayList<>(Collections.singletonList(
            new AnalysisRequest.Drill("region", "south", null))));

        // Flattening the two would turn "A OR B" plus a drill into "A OR (B AND drill)", which is a
        // different question written with the same words.
        assertThat(plan(request).getSql()).contains(
            "WHERE ((\"status\" = ? OR \"status\" = ?) AND \"region\" = ?)");
    }

    @Test
    void aDrillThroughADimensionThisAnalysisNoLongerHasIsRefusedRatherThanDropped() throws Exception {
        AnalysisRequest request = analysis(dimensions("region"),
            measure(null, AnalysisRequest.Aggregation.COUNT_ROWS));
        request.setDrillPath(new ArrayList<>(Collections.singletonList(
            new AnalysisRequest.Drill("city", "rome", "status"))));

        // Silently dropping the step would run a different analysis from the one the breadcrumb
        // above the chart claims to be showing.
        assertThat(refusedBy(request)).contains("no longer grouped by");
    }

    @Test
    void theRollUpRowCannotBeDrilledIntoBecauseItIsNotAValue() throws Exception {
        AnalysisRequest request = analysis(dimensions("region"),
            measure(null, AnalysisRequest.Aggregation.COUNT_ROWS));
        AnalysisRequest.Drill step = new AnalysisRequest.Drill("region", "Other", "city");
        step.setOtherBucket(true);
        request.setDrillPath(new ArrayList<>(Collections.singletonList(step)));

        // An equality on the word would return the rows that literally say Other, which is a
        // different and much smaller answer than the row the user clicked was showing.
        assertThat(refusedBy(request)).contains("cannot be drilled into");
    }

    @Test
    void aDrillOntoADimensionTheAnalysisAlreadyHasIsRefused() throws Exception {
        AnalysisRequest request = analysis(dimensions("region", "city"),
            measure(null, AnalysisRequest.Aggregation.COUNT_ROWS));
        request.setDrillPath(new ArrayList<>(Collections.singletonList(
            new AnalysisRequest.Drill("region", "south", "city"))));

        assertThat(refusedBy(request)).contains("already groups by");
    }

    // ---- helpers --------------------------------------------------------------------------------

    private static AnalysisQueryBuilder.Plan plan(AnalysisRequest request) throws Exception {
        return new AnalysisQueryBuilder(TODAY).plan(request, SCHEMA);
    }

    private static String refusedBy(AnalysisRequest request) {
        AnalyticsException refused = catchThrowableOfType(
            () -> new AnalysisQueryBuilder(TODAY).plan(request, SCHEMA), AnalyticsException.class);
        assertThat(refused).as("the builder was expected to refuse this analysis").isNotNull();
        return refused.getMessage();
    }

    /** The measure fragment of a one-dimension analysis, which is what these assertions are about. */
    private static String measureSql(AnalysisRequest.Aggregation aggregation, String field)
        throws Exception {

        String sql = plan(analysis(dimensions("region"), measure(field, aggregation))).getSql();
        int start = sql.indexOf("\"region\", ") + "\"region\", ".length();
        return sql.substring(start, sql.indexOf(" FROM dataset"));
    }

    /** The rows the plan actually returns, run on the real view with the parameters bound. */
    private static List<List<String>> rows(AnalysisQueryBuilder.Plan plan) throws Exception {
        try (PreparedStatement prepared = engine.prepareStatement(plan.getSql())) {
            List<Object> parameters = plan.getStatement().getParameters();
            for (int i = 0; i < parameters.size(); i++) {
                prepared.setObject(i + 1, parameters.get(i));
            }
            try (ResultSet result = prepared.executeQuery()) {
                int width = result.getMetaData().getColumnCount();
                List<List<String>> rows = new ArrayList<>();
                while (result.next()) {
                    List<String> row = new ArrayList<>(width);
                    for (int i = 1; i <= width; i++) {
                        Object value = result.getObject(i);
                        row.add(value == null ? null : String.valueOf(value));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    private static AnalysisRequest analysis(List<String> dimensions,
        AnalysisRequest.Measure measure) {

        AnalysisRequest request = new AnalysisRequest();
        request.setConnection("store");
        request.setPath("sales.csv");
        request.setDimensions(dimensions);
        request.setMeasure(measure);
        return request;
    }

    private static AnalysisRequest topNRequest(int limit, boolean includeOther) {
        AnalysisRequest request = analysis(dimensions("region"),
            measure("amount", AnalysisRequest.Aggregation.SUM));
        request.setTopN(topN(limit, includeOther));
        return request;
    }

    private static AnalysisRequest filtered() {
        AnalysisRequest request = analysis(dimensions("region"),
            measure("amount", AnalysisRequest.Aggregation.SUM));
        request.setFilters(FilterClause.group(FilterClause.LogicalOp.OR, Arrays.asList(
            FilterClause.of("status", FilterClause.Operator.CONTAINS, "act"),
            FilterClause.of("booked_on", FilterClause.Operator.RELATIVE_DATE, "LAST_30_DAYS"))));
        return request;
    }

    private static AnalysisRequest drilled() {
        AnalysisRequest request = analysis(dimensions("region"),
            measure("amount", AnalysisRequest.Aggregation.SUM));
        request.setDrillPath(new ArrayList<>(Collections.singletonList(
            new AnalysisRequest.Drill("region", "south", "city"))));
        return request;
    }

    private static AnalysisRequest.TopN topN(int limit, boolean includeOther) {
        AnalysisRequest.TopN topN = new AnalysisRequest.TopN();
        topN.setLimit(limit);
        topN.setIncludeOther(includeOther);
        return topN;
    }

    private static AnalysisRequest.Measure measure(String field,
        AnalysisRequest.Aggregation aggregation) {
        return new AnalysisRequest.Measure(field, aggregation);
    }

    private static List<String> dimensions(String... names) {
        return new ArrayList<>(Arrays.asList(names));
    }
}

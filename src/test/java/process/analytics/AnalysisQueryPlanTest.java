package process.analytics;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import process.analytics.canvas.AnalysisQueryBuilder;
import process.analytics.canvas.AnalysisRequest;
import process.analytics.canvas.FilterClause;
import process.analytics.dto.ColumnDto;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What DuckDB actually DOES with the SQL this module composes.
 *
 * <b>Document 15's "inspect query plans", as a test rather than as production code.</b> EXPLAIN is
 * exposed to users through StatementGate, but nothing in the module has ever looked at a plan of
 * its own statements -- so a change that turned a hash join into a nested loop, or added a third
 * pass over the file, would show up as "it feels slower" and nothing else.
 *
 * <b>Why this is not EXPLAIN logging in the engine.</b> That was considered and declined.
 * inSession() is the one place every governed read passes and already logs elapsed milliseconds,
 * but it does not hold the SQL text; the only run whose plan is worth capturing is a slow or
 * failed one, and after a timeout or a cancel the connection has been interrupted, so a follow-up
 * EXPLAIN would fail anyway. It would also spend a second statement while holding one of the four
 * governor permits. The properties below are the whole of the useful part, and they are checked
 * every build instead of once in production.
 *
 * Each assertion is a property that is TRUE TODAY and would be expensive to lose quietly.
 *
 * @author Nabeel Ahmed
 */
public class AnalysisQueryPlanTest {

    private static final List<ColumnDto> SCHEMA = Arrays.asList(
        new ColumnDto("region", "VARCHAR"),
        new ColumnDto("city", "VARCHAR"),
        new ColumnDto("amount", "DOUBLE"),
        new ColumnDto("booked_on", "DATE"));

    /**
     * The operator that reads the relation, which is what a pass over the file looks like here.
     *
     * COLUMN_DATA_SCAN rather than SEQ_SCAN because the fixture's `dataset` is a view over a
     * VALUES list -- the plan names the operator, not the view. Against a real file the operator
     * differs; the COUNT is the property being pinned, and that is the same either way.
     */
    private static final String SCAN = "COLUMN_DATA_SCAN";

    private static Connection engine;

    @BeforeAll
    static void openEngine() throws Exception {
        engine = DriverManager.getConnection("jdbc:duckdb:");
        try (Statement statement = engine.createStatement()) {
            statement.execute("CREATE OR REPLACE TEMP VIEW dataset AS SELECT * FROM (VALUES "
                + "('north','oslo',10.5,DATE '2024-01-01'),"
                + "('north','bergen',21.0,DATE '2024-02-01'),"
                + "('south','rome',42.0,DATE '2024-03-01'),"
                + "('east','lima',1.0,DATE '2024-05-01')"
                + ") AS t(region, city, amount, booked_on)");
        }
    }

    @AfterAll
    static void closeEngine() throws Exception {
        if (engine != null) {
            engine.close();
        }
    }

    /** The plan DuckDB chose, as one string, with the plan's own parameters bound. */
    private static String planFor(AnalysisRequest request) throws Exception {
        AnalysisQueryBuilder.Plan plan = new AnalysisQueryBuilder().plan(request, SCHEMA);
        StringBuilder explained = new StringBuilder();
        try (PreparedStatement prepared =
                 engine.prepareStatement("EXPLAIN " + plan.getStatement().getSql())) {
            List<Object> parameters = plan.getStatement().getParameters();
            for (int at = 0; at < parameters.size(); at++) {
                prepared.setObject(at + 1, parameters.get(at));
            }
            try (ResultSet result = prepared.executeQuery()) {
                while (result.next()) {
                    // Column 2 is explain_value; column 1 names the kind of plan.
                    explained.append(result.getString(2)).append('\n');
                }
            }
        }
        return explained.toString();
    }

    private static AnalysisRequest analysis(boolean filtered, Integer limit, boolean other) {
        AnalysisRequest request = new AnalysisRequest();
        request.setConnection("c");
        request.setPath("p");
        request.setDimensions(new ArrayList<String>(Arrays.asList("region")));
        AnalysisRequest.Measure measure = new AnalysisRequest.Measure();
        measure.setAggregation(AnalysisRequest.Aggregation.SUM);
        measure.setField("amount");
        request.setMeasure(measure);
        if (filtered) {
            request.setFilters(FilterClause.of("city", FilterClause.Operator.EQ, "rome"));
        }
        if (limit != null) {
            AnalysisRequest.TopN topN = new AnalysisRequest.TopN();
            topN.setLimit(limit);
            topN.setIncludeOther(other);
            request.setTopN(topN);
        }
        return request;
    }

    /** The matrix that actually changes the plan. Anything outside it plans the same way. */
    private static List<AnalysisRequest> everyTopNShape() {
        List<AnalysisRequest> all = new ArrayList<AnalysisRequest>();
        for (boolean filtered : new boolean[] { true, false }) {
            for (boolean other : new boolean[] { true, false }) {
                for (int limit : new int[] { 1, 2 }) {
                    all.add(analysis(filtered, limit, other));
                }
            }
        }
        return all;
    }

    @Test
    void noTopNplanFallsBackToAnestedLoop() throws Exception {
        // A BLOCKWISE_NL_JOIN over a real dataset is quadratic. It is what DuckDB reaches for when
        // a join condition stops being equality-shaped, which is one careless edit to the
        // membership test away.
        for (AnalysisRequest request : everyTopNShape()) {
            assertThat(planFor(request))
                .as("a Top-N plan must not contain a nested-loop join")
                .doesNotContain("BLOCKWISE_NL_JOIN");
        }
    }

    @Test
    void noTopNplanUsesAdelimJoin() throws Exception {
        /*
         * This is the property the correlated-EXISTS rewrite bought, and the only thing that would
         * silently take it back.
         *
         * DuckDB decorrelated the old EXISTS into a plain hash join ONLY when there was no WHERE
         * filter. With one -- which is almost every real analysis -- it produced a DELIM_JOIN plus
         * an extra hash join, and the query took roughly twice as long. Reverting to an EXISTS
         * would still return the right rows, so nothing else here would notice.
         */
        for (AnalysisRequest request : everyTopNShape()) {
            assertThat(planFor(request))
                .as("a Top-N plan must not contain a delimited join")
                .doesNotContain("DELIM_JOIN");
        }
    }

    @Test
    void aplainGroupingReadsTheDatasetOnce() throws Exception {
        assertThat(occurrences(planFor(analysis(true, null, false)), SCAN))
            .as("a plain grouped analysis is one pass over the file")
            .isEqualTo(1);
    }

    @Test
    void atopNreadsTheDatasetExactlyTwiceAndNeverThreeTimes() throws Exception {
        /*
         * Two passes are inherent and correct: the ranking has to be known before the roll-up can
         * be aggregated from the raw rows, which is what makes the Other bucket a real total
         * rather than a subtraction. A THIRD pass would not be, and would be invisible except as
         * a slowdown on a big file -- which is exactly the kind of regression nobody attributes
         * to the right change.
         */
        for (AnalysisRequest request : everyTopNShape()) {
            assertThat(occurrences(planFor(request), SCAN))
                .as("a Top-N is two passes over the file, no more")
                .isEqualTo(2);
        }
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            count++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return count;
    }
}

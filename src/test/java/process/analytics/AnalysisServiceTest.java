package process.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import process.analytics.canvas.AnalysisRequest;
import process.analytics.canvas.AnalysisService;
import process.analytics.canvas.FilterClause;
import process.analytics.canvas.dto.AnalysisResultDto;
import process.analytics.dto.ColumnDto;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.security.TenantContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Analysis Canvas end to end, against a real DuckDB.
 *
 * <b>Real engine, redirected location, exactly as AnalyticsProfileTest does it and for the same
 * reason.</b> Nearly every claim under test is a claim about what an engine returns: that a Top-N
 * roll-up re-aggregates rather than adding up aggregates, that a DOUBLE sum used to come back in
 * scientific notation, that a DATE median comes back as a date. A mocked ResultSet would answer
 * whatever this file told it to and would prove that the service agrees with its own fixture.
 * DuckDbSessionFactory hands out sessions with the local filesystem removed, so the one substitution
 * here is the s3:// scan expression for the same reader pointed at a temp CSV; the statement around
 * it, the governor, the timeout, the statement gate, the binding and the rendering are all real.
 *
 * The rows are chosen so that each answer would be wrong in a different way if the code were wrong:
 * an amount that overflows into scientific notation under the old rendering, a region with enough
 * cities to drill into, a status column narrow enough to pivot on, and a group whose region is
 * missing entirely -- because null dimension values are where a grid, a Top-N membership test and a
 * drill each have a way of going quietly wrong.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
// Lenient because the governor test never reaches the engine at all: it is refused a permit before
// a session is opened, which is the property it exists to prove.
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalysisServiceTest {

    private static final long TENANT_ID = 1001L;
    private static final String BUCKET = "etl-bucket";
    private static final String PATH = "etl-demo/sales.csv";

    /**
     * Five rows, and every value is load-bearing.
     *
     * The two large amounts sum to 74,661,250 -- the exact figure the benchmark measured coming
     * back as "7.466125E7" -- so a regression in the rendering shows up as a number a person would
     * not recognise rather than as a rounding difference. The last row has no region, which is the
     * case a grid, a roll-up and a drill each get wrong differently.
     */
    private static final String FIXTURE_CSV =
          "region,city,status,amount,booked_on,depart\n"
        + "north,oslo,active,10.00,2024-01-01,14:30:00\n"
        + "north,bergen,closed,74661240.00,2024-02-01,09:05:07\n"
        + "south,rome,active,21.00,2024-03-01,00:00:00\n"
        + "south,milan,closed,42.00,2024-04-01,14:30:00\n"
        + ",lima,active,7.00,2024-05-01,23:59:59\n";

    @Mock private DuckDbSessionFactory sessions;
    @Mock private DatasetResolver datasetResolver;

    private Connection engine;
    private Path csv;
    private DatasetRef dataset;
    private DuckDbAnalyticsEngine analytics;
    private AnalysisService service;

    /** Every statement that reached the engine, so "how many queries did that cost" is answerable. */
    private final List<String> executed = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() throws Exception {
        this.csv = Files.createTempFile("analysis-service", ".csv");
        Files.write(this.csv, FIXTURE_CSV.getBytes("UTF-8"));
        this.engine = DriverManager.getConnection("jdbc:duckdb:");
        this.dataset = datasetRef();
        this.wire();

        AnalyticsLimits limits = limits(2, 1000);
        this.analytics = new DuckDbAnalyticsEngine(this.sessions, limits, new RunningQueries());
        this.service = new AnalysisService(this.datasetResolver, this.analytics);
        when(this.datasetResolver.resolve(anyString(), anyString())).thenReturn(this.dataset);

        TenantContext.set(TENANT_ID, "TENANT_USER", 7L, "analyst");
    }

    /**
     * The one substitution: a scan of a bucket nobody can reach, for a scan of a file this test can.
     *
     * Everything else on the session is delegated straight to a real DuckDB, including
     * prepareStatement -- which is what makes the parameter binding and the statement gate's parse
     * probe real rather than stubbed.
     */
    private void wire() throws Exception {
        String remoteScan = this.dataset.scanExpression();
        String localScan = "read_csv_auto('"
            + this.csv.toAbsolutePath().toString().replace("'", "''") + "')";

        Connection duck = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(this.sessions.open(any(StorageConnection.class))).thenReturn(duck);
        when(duck.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0, String.class);
            this.executed.add(sql);
            return this.engine.createStatement().execute(sql.replace(remoteScan, localScan));
        });
        when(statement.executeQuery(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0, String.class);
            this.executed.add(sql);
            return this.engine.createStatement().executeQuery(sql.replace(remoteScan, localScan));
        });
        when(duck.prepareStatement(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0, String.class);
            this.executed.add(sql);
            return this.engine.prepareStatement(sql.replace(remoteScan, localScan));
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        this.analytics.shutdown();
        this.engine.close();
        Files.deleteIfExists(this.csv);
        TenantContext.clear();
    }

    // ---- the shape of an answer -----------------------------------------------------------------

    @Test
    void anAnalysisComesBackWithEveryColumnTypedAndItsRoleNamed() throws Exception {
        AnalysisResultDto result = this.service.analyze(
            request(dimensions("region"), measure("amount", AnalysisRequest.Aggregation.SUM)));

        assertThat(result.getColumns()).hasSize(2);
        ColumnDto dimension = result.getColumns().get(0);
        assertThat(dimension.getName()).isEqualTo("region");
        assertThat(dimension.getType()).isEqualTo("VARCHAR");
        assertThat(dimension.getRole()).isEqualTo(ColumnDto.ROLE_DIMENSION);

        ColumnDto measure = result.getColumns().get(1);
        assertThat(measure.getName()).isEqualTo("amount_sum");
        assertThat(measure.getType()).isEqualTo("DOUBLE");
        assertThat(measure.getRole()).isEqualTo(ColumnDto.ROLE_MEASURE);

        // 09's typed column metadata, and 07's "every result row should carry enough context to
        // reproduce its filter state": the DIMENSION cells of a row are the equalities that
        // reproduce it, which a client can only know because the roles say so.
        assertThat(result.getMeasure()).isEqualTo("amount_sum");
        assertThat(result.getDimensions()).containsExactly("region");
        assertThat(result.getStatus()).isEqualTo(AnalyticsEngine.RunState.COMPLETED.name());
        assertThat(result.getDurationMs()).isNotNull();
        assertThat(result.getQueryId()).isNotBlank();
        assertThat(result.isTruncated()).isFalse();
    }

    @Test
    void aCurrencyTotalIsAPlainDecimalAndNotScientificNotation() throws Exception {
        AnalysisResultDto result = this.service.analyze(
            request(dimensions(), measure("amount", AnalysisRequest.Aggregation.SUM)));

        // The measured defect, inverted into a regression. Through the full request path on the
        // benchmark file this exact total came back as "7.466125E7", and a currency total is the
        // single most likely thing anybody aggregates.
        String total = result.getRows().get(0).get(0);
        assertThat(total).isEqualTo("74661320");
        assertThat(total).doesNotContain("E");
        // A DOUBLE renders as the shortest decimal that round-trips to the same double, so the
        // trailing ".0" that 10.0 keeps is absent here. That is the honest rendering of a DOUBLE
        // and it is also the argument for storing money as DECIMAL, where DuckDB carries the scale
        // and this rendering preserves it -- see the DECIMAL assertion in AnalyticsEngineTest.
        assertThat(this.service.analyze(request(dimensions(),
            measure("amount", AnalysisRequest.Aggregation.AVERAGE))).getRows().get(0).get(0))
            .isEqualTo("14932264");
    }

    @Test
    void aDateMeasureIsADateAndNotAMidnightThatIsNotInTheData() throws Exception {
        AnalysisResultDto earliest = this.service.analyze(
            request(dimensions(), measure("booked_on", AnalysisRequest.Aggregation.MINIMUM)));

        assertThat(earliest.getColumns().get(0).getType()).isEqualTo("DATE");
        assertThat(earliest.getRows().get(0).get(0)).isEqualTo("2024-01-01");

        AnalysisResultDto middle = this.service.analyze(
            request(dimensions(), measure("booked_on", AnalysisRequest.Aggregation.MEDIAN)));

        // quantile_cont would have answered 2024-03-01 12:00:00 as a TIMESTAMP here. The value
        // below is a date, is of the column's own type, and is one that exists in the file.
        assertThat(middle.getColumns().get(0).getType()).isEqualTo("DATE");
        assertThat(middle.getRows().get(0).get(0)).isEqualTo("2024-03-01");
    }

    @Test
    void aFilterValueThatLooksLikeSqlIsComparedAndNotExecuted() throws Exception {
        AnalysisRequest request = request(dimensions("region"),
            measure(null, AnalysisRequest.Aggregation.COUNT_ROWS));
        request.setFilters(FilterClause.of("region", FilterClause.Operator.EQ,
            "north'; DROP TABLE dataset; --"));

        AnalysisResultDto result = this.service.analyze(request);

        // No rows, no exception, and the dataset is still readable afterwards -- which is the claim
        // that matters, since a statement composed by concatenation would have failed differently
        // or not at all.
        assertThat(result.getRows()).isEmpty();
        assertThat(this.service.analyze(request(dimensions("region"),
            measure(null, AnalysisRequest.Aggregation.COUNT_ROWS))).getRows()).hasSize(3);
    }

    @Test
    void aFieldThatIsNotInTheDatasetIsRefusedWithoutRunningAnything() throws Exception {
        AnalysisRequest request = request(dimensions("region"),
            measure(null, AnalysisRequest.Aggregation.COUNT_ROWS));
        request.setFilters(FilterClause.of("salary", FilterClause.Operator.GT, "1"));

        AnalyticsException refused = catchThrowableOfType(
            () -> this.service.analyze(request), AnalyticsException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).contains("no column called \"salary\"");
        // The view and the DESCRIBE happened -- the schema is what the refusal is made of -- but
        // nothing was aggregated, and no prepared statement was ever built.
        assertThat(this.executed).noneMatch(sql -> sql.contains("GROUP BY"));
    }

    @Test
    void theContractOnTheWireDeserialisesIntoThisModelAndRuns() throws Exception {
        // The request body exactly as the API contract writes it, nested group and all. A model
        // that only ever gets built by this test's own helpers is a model nobody has checked
        // against the shape a browser actually sends -- and Jackson is where a missing no-arg
        // constructor or a renamed field shows up.
        String body = "{"
            + "\"connection\":\"etl-bucket\",\"path\":\"sales.csv\","
            + "\"dimensions\":[\"region\",\"status\"],"
            + "\"measure\":{\"field\":\"amount\",\"aggregation\":\"SUM\"},"
            + "\"filters\":{\"op\":\"AND\",\"clauses\":["
            + "  {\"field\":\"city\",\"operator\":\"NEQ\",\"value\":\"lima\"},"
            + "  {\"op\":\"OR\",\"clauses\":["
            + "    {\"field\":\"status\",\"operator\":\"EQ\",\"value\":\"active\"},"
            + "    {\"field\":\"amount\",\"operator\":\"GT\",\"value\":\"30\"}]}]},"
            + "\"topN\":{\"limit\":25,\"includeOther\":true},"
            + "\"sort\":{\"by\":\"MEASURE\",\"direction\":\"DESC\"},"
            + "\"queryId\":\"ui-7\"}";

        AnalysisRequest request = new ObjectMapper().readValue(body, AnalysisRequest.class);
        AnalysisResultDto result = this.service.analyze(request);

        assertThat(request.getDimensions()).containsExactly("region", "status");
        assertThat(request.getFilters().isGroup()).isTrue();
        assertThat(request.getFilters().getClauses().get(1).getOp())
            .isEqualTo(FilterClause.LogicalOp.OR);
        assertThat(result.getQueryId()).isEqualTo("ui-7");
        // Four of the five rows survive: lima is the only one the NEQ removes, and every other row
        // satisfies one branch of the OR or the other.
        assertThat(result.getRows()).hasSize(4);
        assertThat(result.getRows().get(0)).containsExactly("north", "closed", "74661240");
        assertThat(result.getPivot()).isNotNull();
    }

    // ---- one session, one permit ----------------------------------------------------------------

    @Test
    void theSchemaAndTheAnalysisShareOneSessionAndOnePermit() throws Exception {
        this.service.analyze(
            request(dimensions("region"), measure("amount", AnalysisRequest.Aggregation.SUM)));

        // The argument profileOf makes for Profile and Quality, applied here: a file open already
        // costs three permits against a ceiling of four, and an analysis that resolved its schema
        // through a separate schemaOf() call would take two more for every click of the canvas.
        verify(this.sessions, times(1)).open(any(StorageConnection.class));
        assertThat(this.executed).anyMatch(sql -> sql.startsWith("CREATE OR REPLACE TEMP VIEW"));
        assertThat(this.executed).anyMatch(sql -> sql.startsWith("DESCRIBE SELECT * FROM dataset"));
    }

    @Test
    void anAnalysisIsRefusedWhenTheGovernorHasNoPermitLeft() {
        // The "no second door" rule, asserted on the new path. A query that opened its own
        // connection would sail past a drained semaphore, and this is the only way to find out.
        Semaphore slots = (Semaphore) ReflectionTestUtils.getField(this.analytics, "slots");
        slots.drainPermits();

        AnalyticsException refused = catchThrowableOfType(() -> this.service.analyze(
            request(dimensions("region"), measure("amount", AnalysisRequest.Aggregation.SUM))),
            AnalyticsException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).contains("Too many analytics queries");
        assertThat(this.executed).isEmpty();
    }

    @Test
    void theRunAnswersToTheIdTheCallerChoseSoAStopButtonHasSomethingToName() throws Exception {
        AnalysisRequest request = request(dimensions("region"),
            measure("amount", AnalysisRequest.Aggregation.SUM));
        request.setQueryId("ui-canvas-1");

        assertThat(this.service.analyze(request).getQueryId()).isEqualTo("ui-canvas-1");
    }

    @Test
    void aResultThatFillsTheRowCeilingSaysSo() throws Exception {
        DuckDbAnalyticsEngine tiny = new DuckDbAnalyticsEngine(this.sessions, limits(2, 2),
            new RunningQueries());
        try {
            AnalysisResultDto result = new AnalysisService(this.datasetResolver, tiny).analyze(
                request(dimensions("city"), measure(null, AnalysisRequest.Aggregation.COUNT_ROWS)));

            // Five cities, a ceiling of two. A user handed two rows out of five and not told has
            // been given a wrong answer rather than a partial one.
            assertThat(result.getRows()).hasSize(2);
            assertThat(result.isTruncated()).isTrue();
        } finally {
            tiny.shutdown();
        }
    }

    // ---- Top-N and the Other bucket -------------------------------------------------------------

    @Test
    void theOtherRowIsLabelledHereAndCarriesTheValuesItStandsFor() throws Exception {
        AnalysisRequest request = request(dimensions("region"),
            measure("amount", AnalysisRequest.Aggregation.SUM));
        request.setTopN(topN(1, true, null));

        AnalysisResultDto result = this.service.analyze(request);

        assertThat(result.getRows()).hasSize(2);
        assertThat(result.getRows().get(0)).containsExactly("north", "74661250");
        // The label is applied on this side, from the marker column, and never in the SQL. A
        // dataset is entitled to contain the word "Other", and the marker is what tells the two
        // apart -- so it never leaves the server.
        assertThat(result.getRows().get(1)).containsExactly("Other", "70.0");
        assertThat(result.getColumns()).hasSize(2);

        AnalysisResultDto.OtherBucketDto other = result.getOther();
        assertThat(other).isNotNull();
        assertThat(other.getLabel()).isEqualTo("Other");
        // 70.0 is 21 + 42 + 7, summed from the raw rows of all three remaining groups -- including
        // the one whose region is missing, which is where a membership test written with IN rather
        // than IS NOT DISTINCT FROM loses a row.
        assertThat(other.getValues()).contains("south");
        // TWO, not one. The same three lines above say the roll-up includes the group whose region
        // is missing -- and it does, and it is a member like any other. This asserted 1 because
        // count(DISTINCT x) ignores nulls while the list beside it does not, so the response
        // shipped a two-element list next to a count of one and flagged nothing, since "truncated"
        // is count > list size. The count now includes the no-value group.
        assertThat(other.getValues()).hasSize(2).containsNull();
        assertThat(other.getValueCount()).isEqualTo(2L);
        assertThat(other.isValuesTruncated()).isFalse();
    }

    @Test
    void aCallerMayNameTheRollUpRowSomethingElse() throws Exception {
        AnalysisRequest request = request(dimensions("region"),
            measure("amount", AnalysisRequest.Aggregation.SUM));
        request.setTopN(topN(1, true, "Everything else"));

        AnalysisResultDto result = this.service.analyze(request);

        assertThat(result.getRows().get(1).get(0)).isEqualTo("Everything else");
        assertThat(result.getOther().getLabel()).isEqualTo("Everything else");
    }

    @Test
    void aRolledUpValueContainingACommaIsStillOneValue() throws Exception {
        // DuckDB's own list rendering is "[a, b]" with no quoting, so a region actually called
        // "Rome, Italy" would arrive indistinguishable from two regions. The builder asks for JSON
        // instead, which has an escaping rule, and this is what proves the difference matters.
        Path awkward = Files.createTempFile("analysis-comma", ".csv");
        try {
            Files.write(awkward, ("region,amount\n"
                + "\"Rome, Italy\",1.0\n"
                + "\"Oslo, Norway\",2.0\n"
                + "big,90.0\n").getBytes("UTF-8"));
            this.repoint(awkward);

            AnalysisRequest request = request(dimensions("region"),
                measure("amount", AnalysisRequest.Aggregation.SUM));
            request.setTopN(topN(1, true, null));

            AnalysisResultDto.OtherBucketDto other = this.service.analyze(request).getOther();

            assertThat(other.getValueCount()).isEqualTo(2L);
            assertThat(other.getValues()).containsExactlyInAnyOrder("Rome, Italy", "Oslo, Norway");
            assertThat(other.isValuesTruncated()).isFalse();
        } finally {
            Files.deleteIfExists(awkward);
        }
    }

    @Test
    void withoutTheOtherBucketThereIsNoRollUpRowAndNoBucketOnTheResponse() throws Exception {
        AnalysisRequest request = request(dimensions("region"),
            measure("amount", AnalysisRequest.Aggregation.SUM));
        request.setTopN(topN(1, false, null));

        AnalysisResultDto result = this.service.analyze(request);

        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getOther()).isNull();
    }

    // ---- the pivot ------------------------------------------------------------------------------

    @Test
    void twoDimensionsComeBackAsAGridTheClientDoesNotHaveToBuild() throws Exception {
        AnalysisRequest request = request(dimensions("region", "status"),
            measure("amount", AnalysisRequest.Aggregation.SUM));
        request.setSort(new AnalysisRequest.Sort(
            AnalysisRequest.Sort.By.DIMENSION, AnalysisRequest.Sort.Direction.ASC));

        AnalysisResultDto result = this.service.analyze(request);
        AnalysisResultDto.PivotDto pivot = result.getPivot();

        assertThat(pivot).isNotNull();
        assertThat(pivot.getRowDimension()).isEqualTo("region");
        assertThat(pivot.getColumnDimension()).isEqualTo("status");
        assertThat(pivot.getColumnValues()).containsExactly("active", "closed");
        assertThat(pivot.isColumnsTruncated()).isFalse();

        // Three rows, in the result's own order -- so the grid never disagrees with the table
        // beside it. The row whose region is missing sorts last under NULLS LAST and keeps a null
        // key rather than becoming the four letters "null", which a dataset is allowed to contain.
        assertThat(pivot.getRows()).hasSize(3);
        assertThat(pivot.getRows().get(0).getKey()).isEqualTo("north");
        assertThat(pivot.getRows().get(0).getCells()).containsExactly("10.0", "74661240");
        assertThat(pivot.getRows().get(1).getKey()).isEqualTo("south");
        assertThat(pivot.getRows().get(1).getCells()).containsExactly("21.0", "42.0");
        assertThat(pivot.getRows().get(2).getKey()).isNull();
        // A combination with no rows stays null. A month with no sales and a month with sales of
        // nothing are not the same fact, and a zero would put a point on a chart with no data
        // behind it.
        assertThat(pivot.getRows().get(2).getCells()).containsExactly("7.0", null);
    }

    @Test
    void oneDimensionAndThreeDimensionsHaveNoGridBecauseAGridHasTwoAxes() throws Exception {
        assertThat(this.service.analyze(request(dimensions("region"),
            measure(null, AnalysisRequest.Aggregation.COUNT_ROWS))).getPivot()).isNull();
        assertThat(this.service.analyze(request(dimensions("region", "status", "city"),
            measure(null, AnalysisRequest.Aggregation.COUNT_ROWS))).getPivot()).isNull();
        // Presence IS the answer to "can this be drawn as a grid", which is why it is a section of
        // the ordinary response rather than an endpoint or a request flag.
        assertThat(this.service.analyze(request(dimensions("region", "status"),
            measure(null, AnalysisRequest.Aggregation.COUNT_ROWS))).getPivot()).isNotNull();
    }

    @Test
    void aColumnDimensionTooWideToDrawIsWithheldWithTheReasonAttached() throws Exception {
        // A grid five thousand columns wide is not a narrower version of the answer, it is a
        // different and unusable one. The rows are still returned; only the grid is withheld.
        Path wide = Files.createTempFile("analysis-wide", ".csv");
        try {
            StringBuilder csv = new StringBuilder("region,status,amount\n");
            for (int i = 0; i < 250; i++) {
                csv.append("north,s").append(i).append(",1.0\n");
            }
            Files.write(wide, csv.toString().getBytes("UTF-8"));
            this.repoint(wide);

            AnalysisResultDto result = this.service.analyze(request(
                dimensions("region", "status"), measure("amount",
                    AnalysisRequest.Aggregation.SUM)));

            assertThat(result.getRows()).hasSize(250);
            assertThat(result.getPivot()).isNotNull();
            assertThat(result.getPivot().isColumnsTruncated()).isTrue();
            assertThat(result.getPivot().getRows()).isNull();
        } finally {
            Files.deleteIfExists(wide);
        }
    }

    // ---- drilling -------------------------------------------------------------------------------

    @Test
    void anUndrilledAnalysisStillCarriesTheRootCrumbAndAnEmptyTrail() throws Exception {
        AnalysisResultDto result = this.service.analyze(
            request(dimensions("region"), measure(null, AnalysisRequest.Aggregation.COUNT_ROWS)));

        // So a client renders the breadcrumb bar with the same code on the first request as on the
        // fifth, rather than special-casing "nothing has been drilled yet".
        assertThat(result.getCrumbs()).hasSize(1);
        assertThat(result.getCrumbs().get(0).getLabel()).isEqualTo("All rows");
        assertThat(result.getCrumbs().get(0).getField()).isNull();
        assertThat(result.getDrillPath()).isEmpty();
    }

    @Test
    void aDrillNarrowsTheAnalysisAndTheServerComposesEveryPartOfIt() throws Exception {
        AnalysisRequest request = request(dimensions("region"),
            measure("amount", AnalysisRequest.Aggregation.SUM));
        request.setInto(new AnalysisRequest.Drill("region", "south", "city"));

        AnalysisResultDto result = this.service.drill(request);

        assertThat(result.getDimensions()).containsExactly("city");
        assertThat(result.getRows()).hasSize(2);
        assertThat(result.getColumns().get(0).getName()).isEqualTo("city");
        // The crumbs come back labelled, so the client never composes them from state of its own --
        // the first divergence between its copy and the server's is a chart that disagrees with the
        // breadcrumb above it.
        assertThat(result.getCrumbs()).hasSize(2);
        assertThat(result.getCrumbs().get(1).getLabel()).isEqualTo("region: south");
        assertThat(result.getCrumbs().get(1).getField()).isEqualTo("region");
        assertThat(result.getCrumbs().get(1).getValue()).isEqualTo("south");
        // And the trail comes back as data, for the next request to echo unchanged.
        assertThat(result.getDrillPath()).hasSize(1);
        assertThat(result.getDrillPath().get(0).getNextDimension()).isEqualTo("city");
    }

    @Test
    void drillingUpRemovesTheLastStepsAndRestoresWhatTheyReplaced() throws Exception {
        AnalysisRequest request = request(dimensions("region"),
            measure("amount", AnalysisRequest.Aggregation.SUM));
        request.setDrillPath(new ArrayList<>(Arrays.asList(
            new AnalysisRequest.Drill("region", "south", "city"),
            new AnalysisRequest.Drill("city", "rome", "status"))));
        request.setSteps(1);

        AnalysisResultDto result = this.service.drillUp(request);

        // One step back: the city dimension is still in place because the step that replaced it is
        // the one that was removed, and the analysis is grouped by city again.
        assertThat(result.getDimensions()).containsExactly("city");
        assertThat(result.getCrumbs()).hasSize(2);
        assertThat(result.getDrillPath()).hasSize(1);
    }

    @Test
    void drillingUpFurtherThanTheTrailGoesIsTheFirstBreadcrumbAndNotAnError() throws Exception {
        AnalysisRequest request = request(dimensions("region"),
            measure("amount", AnalysisRequest.Aggregation.SUM));
        request.setDrillPath(new ArrayList<>(Collections.singletonList(
            new AnalysisRequest.Drill("region", "south", "city"))));
        request.setSteps(9);

        AnalysisResultDto result = this.service.drillUp(request);

        // Clicking "All rows" is a request to undo everything. Refusing it would be the application
        // correcting a user who was right.
        assertThat(result.getDimensions()).containsExactly("region");
        assertThat(result.getCrumbs()).hasSize(1);
        assertThat(result.getDrillPath()).isEmpty();
        assertThat(result.getRows()).hasSize(3);
    }

    @Test
    void aDrillWithNoDimensionOnItIsRefused() {
        AnalysisRequest request = request(dimensions("region"),
            measure("amount", AnalysisRequest.Aggregation.SUM));

        AnalyticsException refused = catchThrowableOfType(
            () -> this.service.drill(request), AnalyticsException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).contains("which dimension was clicked");
    }

    // ---- fixture --------------------------------------------------------------------------------

    /** Points the session at a different file, for the one test that needs a wider dataset. */
    private void repoint(Path replacement) throws Exception {
        String remoteScan = this.dataset.scanExpression();
        String localScan = "read_csv_auto('"
            + replacement.toAbsolutePath().toString().replace("'", "''") + "')";
        Connection duck = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(this.sessions.open(any(StorageConnection.class))).thenReturn(duck);
        when(duck.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenAnswer(call -> this.engine.createStatement()
            .execute(call.getArgument(0, String.class).replace(remoteScan, localScan)));
        when(statement.executeQuery(anyString())).thenAnswer(call -> this.engine.createStatement()
            .executeQuery(call.getArgument(0, String.class).replace(remoteScan, localScan)));
        when(duck.prepareStatement(anyString())).thenAnswer(call -> this.engine
            .prepareStatement(call.getArgument(0, String.class).replace(remoteScan, localScan)));
    }

    private static AnalysisRequest request(List<String> dimensions,
        AnalysisRequest.Measure measure) {

        AnalysisRequest request = new AnalysisRequest();
        request.setConnection("store");
        request.setPath(PATH);
        request.setDimensions(dimensions);
        request.setMeasure(measure);
        return request;
    }

    private static AnalysisRequest.TopN topN(int limit, boolean includeOther, String label) {
        AnalysisRequest.TopN topN = new AnalysisRequest.TopN();
        topN.setLimit(limit);
        topN.setIncludeOther(includeOther);
        topN.setOtherLabel(label);
        return topN;
    }

    private static AnalysisRequest.Measure measure(String field,
        AnalysisRequest.Aggregation aggregation) {
        return new AnalysisRequest.Measure(field, aggregation);
    }

    private static List<String> dimensions(String... names) {
        return new ArrayList<>(Arrays.asList(names));
    }

    /**
     * DatasetRef's constructor is package-private and DatasetResolver is its only production caller.
     * This test sits in that package, so it can hand the service the object the resolver would have.
     */
    private static DatasetRef datasetRef() {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(9L);
        connection.setTenantId(TENANT_ID);
        connection.setProvider(StorageProvider.S3);
        connection.setAlias("store");
        connection.setBucketName(BUCKET);
        connection.setStatus(Status.Active);
        return new DatasetRef(connection, BUCKET, PATH, DatasetRef.Format.CSV);
    }

    private static AnalyticsLimits limits(int maxConcurrent, int maxRows) {
        AnalyticsLimits limits = new AnalyticsLimits();
        ReflectionTestUtils.setField(limits, "timeoutSeconds", 30);
        ReflectionTestUtils.setField(limits, "maxRows", maxRows);
        ReflectionTestUtils.setField(limits, "previewPageSize", 100);
        ReflectionTestUtils.setField(limits, "maxConcurrentQueries", maxConcurrent);
        return limits;
    }
    // ---- TIME, which had no coverage anywhere in the analytics tree ----------------------------

    @Test
    void aFilterOnATimeColumnRunsInsteadOfBlamingTheFile() throws Exception {
        // Every value-bearing filter on a TIME column used to die in the engine. FilterCompiler
        // bound a java.time.LocalTime, which duckdb_jdbc 1.1.3 refuses outright -- "Unsupported
        // parameter type" -- and explain() mapped that to "This file could not be read as CSV. It
        // may be malformed", so the application blamed the customer's data for its own bug.
        AnalysisRequest request = request(dimensions("region"),
            measure("amount", AnalysisRequest.Aggregation.SUM));
        request.setFilters(FilterClause.of("depart", FilterClause.Operator.EQ, "14:30:00"));

        AnalysisResultDto result = this.service.analyze(request);

        // north 10.00 and south 42.00 both depart at 14:30:00; the other three rows do not.
        assertThat(result.getRows()).hasSize(2);
        assertThat(result.getRowCount()).isEqualTo(2);
    }

    @Test
    void aTimeIsBoundAsTextBecauseTheObviousRepairMatchesNothing() throws Exception {
        // Worth its own test because java.sql.Time is what anyone fixing the above would reach
        // for, and measured on 1.1.3 it binds WITHOUT COMPLAINT and matches zero rows -- a filter
        // that silently returns nothing, which is harder to notice than an exception. If a future
        // change swaps the bound String for a Time, this test goes red instead of the feature
        // going quiet.
        AnalysisRequest request = request(dimensions("region"),
            measure("amount", AnalysisRequest.Aggregation.SUM));
        request.setFilters(FilterClause.of("depart", FilterClause.Operator.GT, "10:00:00"));

        AnalysisResultDto result = this.service.analyze(request);

        assertThat(result.getRows()).isNotEmpty();
    }

    @Test
    void aTimeKeepsItsSecondsOnTheWayOut() throws Exception {
        // The driver renders a zero seconds field away -- 14:30:00 came back as "14:30" and
        // 00:00:00 as "00:00" -- so one column rendered at two precisions depending on the value.
        // Same defect class as the phantom midnight a DATE used to grow.
        AnalysisRequest request = request(dimensions("depart"),
            measure("amount", AnalysisRequest.Aggregation.SUM));

        AnalysisResultDto result = this.service.analyze(request);

        List<String> departures = new ArrayList<>();
        for (List<String> row : result.getRows()) {
            departures.add(row.get(0));
        }
        assertThat(departures).contains("14:30:00", "00:00:00", "09:05:07", "23:59:59");
        assertThat(departures).doesNotContain("14:30", "00:00");
    }

    @Test
    void anInListOverTimesWorksToo() throws Exception {
        // IN builds its own placeholders rather than going through the comparison branch, so the
        // cast that makes ">" work on a TIME column has to be applied there as well. Its own test
        // because that is exactly the kind of second emitter a fix forgets.
        AnalysisRequest request = request(dimensions("region"),
            measure("amount", AnalysisRequest.Aggregation.SUM));
        FilterClause in = new FilterClause();
        in.setField("depart");
        in.setOperator(FilterClause.Operator.IN);
        in.setValues(Arrays.asList("14:30:00", "23:59:59"));
        request.setFilters(in);

        AnalysisResultDto result = this.service.analyze(request);

        // north 10.00 + south 42.00 at 14:30, and the no-region row at 23:59:59.
        assertThat(result.getRows()).isNotEmpty();
    }

    @Test
    void aValueThatIsNotATimeIsStillRefusedByName() throws Exception {
        // The control. Binding as text rather than as a parsed type could have meant accepting
        // anything; the parse still runs, it just validates instead of producing the parameter.
        AnalysisRequest request = request(dimensions("region"),
            measure("amount", AnalysisRequest.Aggregation.SUM));
        request.setFilters(FilterClause.of("depart", FilterClause.Operator.EQ, "half past two"));

        AnalyticsException refused = catchThrowableOfType(
            () -> this.service.analyze(request), AnalyticsException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).contains("holds times");
    }

}

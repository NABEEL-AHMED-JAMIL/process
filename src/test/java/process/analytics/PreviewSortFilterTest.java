package process.analytics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import process.analytics.canvas.FilterClause;
import process.analytics.dto.DatasetPreviewDto;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The data grid's server half -- sort, search, filter and the count that has to agree with them --
 * against a real DuckDB.
 *
 * <b>Real engine, redirected location, exactly as AnalysisServiceTest does it and for the same
 * reason.</b> Every claim below is a claim about what an engine returns: that NULLS LAST holds in
 * both directions, that contains(lower(..)) folds the case the way DuckDB folds it, that count(*)
 * under a bound predicate counts the rows the page is a page of. A mocked ResultSet would answer
 * whatever this file told it to and would prove only that the code agrees with its own fixture.
 * DuckDbSessionFactory hands out sessions with the local filesystem removed, so the one
 * substitution here is the s3:// scan expression for the same reader pointed at a temp CSV; the
 * view, the DESCRIBE, the statement gate, the FilterCompiler, the binding, the governor and the
 * rendering are all real.
 *
 * <b>The interaction this file exists for is the last section.</b> A caller that carries the
 * unfiltered total forward across a filter change gets a pager built on a number that is no longer
 * true -- every page clickable, the ones past the real end empty, which reads as a broken filter
 * rather than as a wrong count. It is exactly the kind of thing nobody tries by hand, because it
 * needs two requests and a client that is doing the right thing with the first one.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PreviewSortFilterTest {

    private static final long TENANT_ID = 1001L;
    private static final String BUCKET = "etl-bucket";
    private static final String PATH = "etl-demo/sales.csv";

    /**
     * Five rows, and every cell is chosen to make one of the answers below wrong in a different way
     * if the code were wrong.
     *
     * "Oslo" is capitalised in city and lower case inside note, so a case-insensitive search has to
     * fold BOTH sides and has to look in more than the first text column. score is missing on two
     * rows, which is what a sort has to place somewhere and what NULLS LAST decides. region is
     * missing on one, so a filter and a sort each meet a null in a text column. amount is the only
     * place the characters "42" appear, and it is not a text column -- which is how "search matches
     * any TEXT column" is held to what it says rather than quietly widened.
     */
    private static final String FIXTURE_CSV =
          "region,city,note,amount,score\n"
        + "north,Oslo,shipped,10.00,3\n"
        + "north,bergen,oslo depot,74661240.00,\n"
        + "south,rome,shipped,21.00,1\n"
        + "south,milan,,42.00,2\n"
        + ",lima,late,7.00,\n";

    /** A file with no text column at all, for the one case the search has no column to look in. */
    private static final String NUMBERS_CSV =
          "id,amount\n"
        + "1,10.00\n"
        + "2,20.00\n";

    @Mock private DuckDbSessionFactory sessions;

    private Connection duckdb;
    private Path csv;
    private Path numbers;
    private DatasetRef dataset;
    private DatasetRef numeric;
    private DuckDbAnalyticsEngine engine;

    /** Every statement that reached the engine, so "how many queries did that cost" is answerable. */
    private final List<String> executed = Collections.synchronizedList(new ArrayList<String>());

    @BeforeEach
    void setUp() throws Exception {
        this.csv = Files.createTempFile("preview-grid", ".csv");
        Files.write(this.csv, FIXTURE_CSV.getBytes("UTF-8"));
        this.numbers = Files.createTempFile("preview-grid-numbers", ".csv");
        Files.write(this.numbers, NUMBERS_CSV.getBytes("UTF-8"));

        this.duckdb = DriverManager.getConnection("jdbc:duckdb:");
        this.dataset = datasetRef(PATH);
        this.numeric = datasetRef("etl-demo/numbers.csv");
        this.wire();

        this.engine = new DuckDbAnalyticsEngine(this.sessions, limits(2, 1000),
            new RunningQueries());
        TenantContext.set(TENANT_ID, "TENANT_USER", 7L, "analyst");
    }

    /**
     * The one substitution: a scan of a bucket nobody can reach, for a scan of a file this test can.
     *
     * Everything else on the session is delegated straight to a real DuckDB, including
     * prepareStatement -- which is what makes the parameter binding and the statement gate's parse
     * probe real rather than stubbed. Both datasets are redirected on the same connection, so a
     * TEMP VIEW created by one statement is visible to the next, exactly as it is in production.
     */
    private void wire() throws Exception {
        Connection session = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(this.sessions.open(any(StorageConnection.class))).thenReturn(session);
        when(session.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0, String.class);
            this.executed.add(sql);
            return this.duckdb.createStatement().execute(local(sql));
        });
        when(statement.executeQuery(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0, String.class);
            this.executed.add(sql);
            return this.duckdb.createStatement().executeQuery(local(sql));
        });
        when(session.prepareStatement(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0, String.class);
            this.executed.add(sql);
            return this.duckdb.prepareStatement(local(sql));
        });
    }

    private String local(String sql) {
        return sql
            .replace(this.dataset.scanExpression(), reader(this.csv))
            .replace(this.numeric.scanExpression(), reader(this.numbers));
    }

    private static String reader(Path file) {
        return "read_csv_auto('" + file.toAbsolutePath().toString().replace("'", "''") + "')";
    }

    @AfterEach
    void tearDown() throws Exception {
        this.engine.shutdown();
        this.duckdb.close();
        Files.deleteIfExists(this.csv);
        Files.deleteIfExists(this.numbers);
        TenantContext.clear();
    }

    // ---- sorting ---------------------------------------------------------------------------------

    @Test
    void sortingIsDoneByTheEngineAndNotByWhicheverRowsThePageHappenedToHold() throws Exception {
        // The page is one row wide on purpose. A grid that sorted its own page would return the
        // file's first row and present it as the smallest score, which is the single most
        // convincing wrong answer this screen can give: it looks exactly like the right one.
        DatasetPreviewDto page = this.engine.preview(this.dataset, 0, 1, null,
            shape("score", AnalyticsEngine.PreviewShape.Direction.ASC, null, null));

        assertThat(cell(page, 0, "city")).isEqualTo("rome");
        assertThat(cell(page, 0, "score")).isEqualTo("1");
        // One page of five rows, and the count is of the file rather than of the page.
        assertThat(page.getTotalRows()).isEqualTo(5L);
        assertThat(page.isFiltered()).isFalse();
    }

    @Test
    void aMissingValueSortsLastInBothDirectionsAndSoIsNeverTheLargestOrTheSmallest() throws Exception {
        // score is missing on bergen and on lima. If nulls led an ascending sort they would read as
        // the minimum; if they led a descending sort they would read as the maximum -- the same two
        // cells claiming to be both, depending on which arrow the user clicked.
        DatasetPreviewDto ascending = this.engine.preview(this.dataset, 0, 10, null,
            shape("score", AnalyticsEngine.PreviewShape.Direction.ASC, null, null));

        assertThat(column(ascending, "score")).containsExactly("1", "2", "3", null, null);

        DatasetPreviewDto descending = this.engine.preview(this.dataset, 0, 10, null,
            shape("score", AnalyticsEngine.PreviewShape.Direction.DESC, null, null));

        assertThat(column(descending, "score")).containsExactly("3", "2", "1", null, null);
    }

    @Test
    void aSortColumnIsAnIdentifierAndIsRefusedUnlessTheDatasetDeclaredIt() throws Exception {
        AnalyticsException refused = catchThrowableOfType(
            () -> this.engine.preview(this.dataset, 0, 10, null,
                shape("salary", AnalyticsEngine.PreviewShape.Direction.ASC, null, null)),
            AnalyticsException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).contains("no column called \"salary\"");
    }

    @Test
    void aSortColumnThatIsReallyAnExpressionIsRefusedByTheSameAllowList() throws Exception {
        // A sort cannot be bound -- SQL has no parameter for an identifier -- so the only defence is
        // that the string written into the ORDER BY came from the dataset's own DESCRIBE. This is
        // that defence being attacked rather than assumed.
        for (String attempt : Arrays.asList("score DESC, (SELECT 1)", "1", "score; DROP TABLE dataset",
            "score\" , (SELECT 1) \"")) {

            AnalyticsException refused = catchThrowableOfType(
                () -> this.engine.preview(this.dataset, 0, 10, null,
                    shape(attempt, AnalyticsEngine.PreviewShape.Direction.ASC, null, null)),
                AnalyticsException.class);

            assertThat(refused).as(attempt).isNotNull();
            assertThat(refused.getMessage()).as(attempt).contains("no column called");
        }
        // And the dataset is still readable afterwards, which is the claim that matters.
        assertThat(this.engine.preview(this.dataset, 0, 10, null, null).getRows()).hasSize(5);
    }

    @Test
    void aColumnNameIsResolvedToTheSpellingTheFileUsesRatherThanTheOneTheCallerTyped() throws Exception {
        // The same convenience FilterCompiler.Columns gives a filter field. What is written into the
        // SQL is the schema's own string either way, which is the property that makes it safe.
        DatasetPreviewDto page = this.engine.preview(this.dataset, 0, 10, null,
            shape("SCORE", AnalyticsEngine.PreviewShape.Direction.ASC, null, null));

        assertThat(column(page, "score")).containsExactly("1", "2", "3", null, null);
    }

    @Test
    void ascendingIsWhatAGridMeansWhenItNamesAColumnAndNoDirection() throws Exception {
        DatasetPreviewDto page = this.engine.preview(this.dataset, 0, 10, null,
            shape("score", null, null, null));

        assertThat(column(page, "score")).containsExactly("1", "2", "3", null, null);
    }

    // ---- searching -------------------------------------------------------------------------------

    @Test
    void aSearchLooksInEveryTextColumnAndNotOnlyInTheFirstOne() throws Exception {
        // "Oslo" is a city on one row and part of a note on another. A search that covered only the
        // first text column would answer with one row and would look entirely reasonable doing it.
        DatasetPreviewDto page = this.engine.preview(this.dataset, 0, 10, null,
            shape(null, null, "oslo", null));

        assertThat(column(page, "city")).containsExactlyInAnyOrder("Oslo", "bergen");
        assertThat(page.getTotalRows()).isEqualTo(2L);
        assertThat(page.isFiltered()).isTrue();
    }

    @Test
    void aTermThatIsInOneColumnAndNotAnotherFindsTheRowItIsIn() throws Exception {
        DatasetPreviewDto inCity = this.engine.preview(this.dataset, 0, 10, null,
            shape(null, null, "bergen", null));
        assertThat(column(inCity, "city")).containsExactly("bergen");
        assertThat(inCity.getTotalRows()).isEqualTo(1L);

        DatasetPreviewDto inNote = this.engine.preview(this.dataset, 0, 10, null,
            shape(null, null, "late", null));
        assertThat(column(inNote, "city")).containsExactly("lima");
        assertThat(inNote.getTotalRows()).isEqualTo(1L);

        // And a term that is in neither is not in the answer, rather than being in it because some
        // other column happened to contain the characters.
        assertThat(this.engine.preview(this.dataset, 0, 10, null,
            shape(null, null, "reykjavik", null)).getRows()).isEmpty();
    }

    @Test
    void theCaseTheUserTypedIsNotTheCaseTheFileHolds() throws Exception {
        // Both foldings are done by the engine, on the column and on the term, so one implementation
        // of case decides both. Folding the term in Java would disagree with DuckDB on the first
        // non-ASCII alphabet anybody searches in.
        for (String typed : Arrays.asList("OSLO", "oslo", "OsLo")) {
            assertThat(this.engine.preview(this.dataset, 0, 10, null,
                shape(null, null, typed, null)).getTotalRows()).as(typed).isEqualTo(2L);
        }
    }

    @Test
    void aSearchIsNotAWildcardAndIsNotSql() throws Exception {
        // contains(), not LIKE: a person searching for "50%" is searching for a percentage and not
        // for "50" followed by anything. And a term that is SQL is a term.
        assertThat(this.engine.preview(this.dataset, 0, 10, null,
            shape(null, null, "%", null)).getRows()).isEmpty();
        assertThat(this.engine.preview(this.dataset, 0, 10, null,
            shape(null, null, "_", null)).getRows()).isEmpty();
        assertThat(this.engine.preview(this.dataset, 0, 10, null,
            shape(null, null, "' OR 1=1 --", null)).getRows()).isEmpty();
        // Still readable, so the quote was compared rather than executed.
        assertThat(this.engine.preview(this.dataset, 0, 10, null, null).getRows()).hasSize(5);
    }

    @Test
    void aSearchLooksInTextColumnsAndSaysSoByNotFindingANumber() throws Exception {
        // "42" is the amount on one row and appears nowhere else. The contract says any TEXT column,
        // and this is that contract held to what it says: widening it to every column would make a
        // search over a wide numeric file scan and cast every value on every row.
        DatasetPreviewDto page = this.engine.preview(this.dataset, 0, 10, null,
            shape(null, null, "42", null));

        assertThat(page.getRows()).isEmpty();
        assertThat(page.getTotalRows()).isZero();
        assertThat(page.isFiltered()).isTrue();
    }

    @Test
    void aFileWithNoTextColumnMatchesNothingRatherThanFailing() throws Exception {
        // A search box that works on one file and throws on the next is worse to hand a person than
        // an empty result. The response says filtered, so the screen can say "0 of 2" rather than
        // implying the file is empty.
        DatasetPreviewDto page = this.engine.preview(this.numeric, 0, 10, null,
            shape(null, null, "anything", null));

        assertThat(page.getRows()).isEmpty();
        assertThat(page.getTotalRows()).isZero();
        assertThat(page.isFiltered()).isTrue();
    }

    // ---- filtering -------------------------------------------------------------------------------

    @Test
    void aFilterIsCompiledByTheSameCompilerTheCanvasUses() throws Exception {
        DatasetPreviewDto page = this.engine.preview(this.dataset, 0, 10, null,
            shape(null, null, null, filters(
                FilterClause.of("region", FilterClause.Operator.EQ, "north"))));

        assertThat(column(page, "city")).containsExactlyInAnyOrder("Oslo", "bergen");
        assertThat(page.getTotalRows()).isEqualTo(2L);
        assertThat(page.isFiltered()).isTrue();
    }

    @Test
    void theArrayOfConditionsIsJoinedByAndSoThatEachChipNarrows() throws Exception {
        DatasetPreviewDto page = this.engine.preview(this.dataset, 0, 10, null,
            shape(null, null, null, filters(
                FilterClause.of("region", FilterClause.Operator.EQ, "north"),
                FilterClause.of("city", FilterClause.Operator.EQ, "bergen"))));

        assertThat(column(page, "city")).containsExactly("bergen");
        assertThat(page.getTotalRows()).isEqualTo(1L);
    }

    @Test
    void aFilterThatRemovesEveryRowSaysSoInTheCountAsWellAsInTheRows() throws Exception {
        // The case where a wrong count is invisible in the rows: an empty page with a total of five
        // draws a pager offering four more pages of nothing, and reads as a broken filter.
        DatasetPreviewDto page = this.engine.preview(this.dataset, 0, 10, null,
            shape(null, null, null, filters(
                FilterClause.of("region", FilterClause.Operator.EQ, "atlantis"))));

        assertThat(page.getRows()).isEmpty();
        assertThat(page.getTotalRows()).isZero();
        assertThat(page.isFiltered()).isTrue();
    }

    @Test
    void aFilterValueThatLooksLikeSqlIsComparedAndNotExecuted() throws Exception {
        DatasetPreviewDto page = this.engine.preview(this.dataset, 0, 10, null,
            shape(null, null, null, filters(FilterClause.of("region", FilterClause.Operator.EQ,
                "north'; DROP TABLE dataset; --"))));

        assertThat(page.getRows()).isEmpty();
        assertThat(page.getTotalRows()).isZero();
        // The dataset is still readable, which is the claim that matters: a statement built by
        // concatenation would have failed differently, or would not have failed at all.
        assertThat(this.engine.preview(this.dataset, 0, 10, null, null).getRows()).hasSize(5);
    }

    @Test
    void aFilterFieldThatIsNotInTheDatasetIsRefusedBeforeAnythingIsAggregated() throws Exception {
        AnalyticsException refused = catchThrowableOfType(
            () -> this.engine.preview(this.dataset, 0, 10, null,
                shape(null, null, null,
                    filters(FilterClause.of("salary", FilterClause.Operator.GT, "1")))),
            AnalyticsException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).contains("no column called \"salary\"");
        assertThat(this.executed).noneMatch(sql -> sql.contains("count(*)"));
    }

    @Test
    void aFilterASearchAndASortAllApplyToTheSamePage() throws Exception {
        DatasetPreviewDto page = this.engine.preview(this.dataset, 0, 10, null,
            shape("city", AnalyticsEngine.PreviewShape.Direction.ASC, "shipped", filters(
                FilterClause.of("amount", FilterClause.Operator.GT, "15"))));

        // shipped is on Oslo (10.00) and rome (21.00); the amount filter leaves rome alone.
        assertThat(column(page, "city")).containsExactly("rome");
        assertThat(page.getTotalRows()).isEqualTo(1L);
        assertThat(page.isFiltered()).isTrue();
    }

    // ---- the count, and the total a caller carries forward ---------------------------------------

    @Test
    void anUnshapedPreviewIsTheOneThatWasHereBeforeTheGrid() throws Exception {
        DatasetPreviewDto page = this.engine.preview(this.dataset, 0, 10, null, null);

        assertThat(page.getRows()).hasSize(5);
        assertThat(page.getTotalRows()).isEqualTo(5L);
        assertThat(page.isFiltered()).isFalse();
        // No view, no DESCRIBE and no ORDER BY: the call every file open makes did not get more
        // expensive because a grid learned to sort.
        assertThat(this.executed).noneMatch(sql -> sql.contains("CREATE OR REPLACE TEMP VIEW"));
        assertThat(this.executed).noneMatch(sql -> sql.contains("ORDER BY"));
    }

    @Test
    void aFilteredPageIsCountedUnderItsOwnFilterAndNotUnderTheWholeFile() throws Exception {
        // One row per page, so the total is the only thing that can tell a caller there is a second
        // page. north has two rows out of five.
        DatasetPreviewDto first = this.engine.preview(this.dataset, 0, 1, null,
            shape("city", AnalyticsEngine.PreviewShape.Direction.ASC, null, filters(
                FilterClause.of("region", FilterClause.Operator.EQ, "north"))));

        assertThat(column(first, "city")).containsExactly("Oslo");
        assertThat(first.getTotalRows()).isEqualTo(2L);

        DatasetPreviewDto second = this.engine.preview(this.dataset, 1, 1, null,
            shape("city", AnalyticsEngine.PreviewShape.Direction.ASC, null, filters(
                FilterClause.of("region", FilterClause.Operator.EQ, "north"))));

        // "Oslo" sorts before "bergen" because DuckDB orders by code point and O is 0x4F.
        assertThat(column(second, "city")).containsExactly("bergen");
        assertThat(second.getTotalRows()).isEqualTo(2L);
    }

    @Test
    void aTotalCarriedAcrossAFilterChangeIsRefusedRatherThanPaginated() throws Exception {
        // THE interaction. First request: no filter, and the client learns the file holds five rows.
        DatasetPreviewDto whole = this.engine.preview(this.dataset, 0, 10, null, null);
        assertThat(whole.getTotalRows()).isEqualTo(5L);
        assertThat(whole.isFiltered()).isFalse();

        // Second request: the user types a filter, and the client -- doing exactly what the
        // knownTotal round trip taught it to do -- sends the total it is already displaying.
        DatasetPreviewDto filtered = this.engine.preview(this.dataset, 0, 10,
            (int) whole.getTotalRows(), shape(null, null, null, filters(
                FilterClause.of("region", FilterClause.Operator.EQ, "north"))));

        assertThat(filtered.getRows()).hasSize(2);
        // Five would have drawn a pager over rows that no longer exist, and every page past the
        // first would have come back empty -- which reads as a filter that does not work.
        assertThat(filtered.getTotalRows()).isEqualTo(2L);
        assertThat(filtered.isFiltered()).isTrue();
    }

    @Test
    void aTotalCarriedAcrossASearchChangeIsRefusedTheSameWay() throws Exception {
        DatasetPreviewDto searched = this.engine.preview(this.dataset, 0, 10, 5,
            shape(null, null, "oslo", null));

        assertThat(searched.getTotalRows()).isEqualTo(2L);
        assertThat(searched.isFiltered()).isTrue();

        // And the one that would be silent: a search that matches nothing, carrying a total that
        // says there are five rows to page through.
        DatasetPreviewDto nothing = this.engine.preview(this.dataset, 0, 10, 5,
            shape(null, null, "reykjavik", null));

        assertThat(nothing.getRows()).isEmpty();
        assertThat(nothing.getTotalRows()).isZero();
    }

    @Test
    void aTotalCarriedAcrossASortIsKeptBecauseASortRemovesNoRows() throws Exception {
        this.executed.clear();

        DatasetPreviewDto sorted = this.engine.preview(this.dataset, 0, 10, 4200,
            shape("score", AnalyticsEngine.PreviewShape.Direction.DESC, null, null));

        // Echoed back untouched, exactly as an unshaped page turn does. Ordering rows does not
        // change how many there are, and a grid whose every header click cost a full count would
        // make sorting the most expensive thing on the screen.
        assertThat(sorted.getTotalRows()).isEqualTo(4200L);
        assertThat(sorted.isFiltered()).isFalse();
        assertThat(this.executed).noneMatch(sql -> sql.contains("count(*)"));
    }

    @Test
    void aSortedPageWithNoTotalToCarryCountsOnTheSessionItAlreadyHolds() throws Exception {
        this.executed.clear();

        DatasetPreviewDto sorted = this.engine.preview(this.dataset, 0, 10, null,
            shape("score", AnalyticsEngine.PreviewShape.Direction.ASC, null, null));

        assertThat(sorted.getTotalRows()).isEqualTo(5L);
        // One count, and it happened inside the same session as the page rather than through
        // rowCount(), which would have taken a second of the module's four governor permits.
        assertThat(counted()).isEqualTo(1);
        assertThat(this.executed).anyMatch(sql -> sql.startsWith("CREATE OR REPLACE TEMP VIEW"));
    }

    @Test
    void aFilteredPageCountsOnceAndBindsTheSameValuesToBothStatements() throws Exception {
        this.executed.clear();

        DatasetPreviewDto page = this.engine.preview(this.dataset, 0, 10, null,
            shape(null, null, null, filters(
                FilterClause.of("region", FilterClause.Operator.EQ, "north"))));

        assertThat(page.getTotalRows()).isEqualTo(2L);
        assertThat(counted()).isEqualTo(1);
        // Both statements carry the predicate, which is what makes the count a count of the rows the
        // page is a page of rather than a number that merely arrived with them.
        assertThat(this.executed).filteredOn(sql -> sql.contains("\"region\""))
            .as("the count and the page both filter").hasSizeGreaterThanOrEqualTo(2);
        // And no value is in the text. The parameter is a "?" on both.
        assertThat(this.executed).noneMatch(sql -> sql.contains("north"));
    }

    @Test
    void aRunningPreviewIsRegisteredAndReleasedLikeEveryOtherRead() throws Exception {
        RunningQueries running = new RunningQueries();
        DuckDbAnalyticsEngine governed = new DuckDbAnalyticsEngine(this.sessions, limits(2, 1000),
            running);

        governed.preview(this.dataset, 0, 10, null,
            shape("score", AnalyticsEngine.PreviewShape.Direction.ASC, "oslo", filters(
                FilterClause.of("region", FilterClause.Operator.EQ, "north"))));
        governed.shutdown();

        // A shaped preview holds a permit and a session exactly as a user's SQL does, and gives them
        // back on the way out. A registry that kept an entry per query it had seen is a memory leak
        // with a tenant id in it.
        assertThat(running.size()).isZero();
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private int counted() {
        int seen = 0;
        for (String sql : this.executed) {
            if (sql.startsWith("SELECT count(*)")) {
                seen++;
            }
        }
        return seen;
    }

    private static AnalyticsEngine.PreviewShape shape(String sort,
        AnalyticsEngine.PreviewShape.Direction direction, String search, List<FilterClause> filters)
        throws AnalyticsException {

        return new AnalyticsEngine.PreviewShape(sort, direction, search, filters);
    }

    private static List<FilterClause> filters(FilterClause... clauses) {
        return Arrays.asList(clauses);
    }

    /** One cell, addressed by column name so a change in column order is not a silent pass. */
    private static String cell(DatasetPreviewDto page, int row, String column) {
        return page.getRows().get(row).get(page.getColumns().indexOf(column));
    }

    /** One column of the page, in the order the rows came back. */
    private static List<String> column(DatasetPreviewDto page, String column) {
        int at = page.getColumns().indexOf(column);
        List<String> values = new ArrayList<>();
        for (List<String> row : page.getRows()) {
            values.add(row.get(at));
        }
        return values;
    }

    private static DatasetRef datasetRef(String path) {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(9L);
        connection.setTenantId(TENANT_ID);
        connection.setProvider(StorageProvider.S3);
        connection.setAlias("store");
        connection.setBucketName(BUCKET);
        connection.setStatus(Status.Active);
        return new DatasetRef(connection, BUCKET, path, DatasetRef.Format.CSV);
    }

    private static AnalyticsLimits limits(int maxConcurrent, int maxRows) {
        AnalyticsLimits limits = new AnalyticsLimits();
        ReflectionTestUtils.setField(limits, "timeoutSeconds", 30);
        ReflectionTestUtils.setField(limits, "maxRows", maxRows);
        ReflectionTestUtils.setField(limits, "previewPageSize", 100);
        ReflectionTestUtils.setField(limits, "maxConcurrentQueries", maxConcurrent);
        return limits;
    }
    @Test
    void aTotalCountedUnderAFilterIsNotReusedOnceTheFilterComesOff() throws Exception {
        // The mirror image of the trap above, and the half that HIDES data. Refusing knownTotal
        // while narrowing stops a filtered page being paginated as the whole file. But CLEARING a
        // filter is not narrowing, so the request takes the trusting branch while the client is
        // still holding the filtered total from the response before it -- and the dataset appears
        // to have permanently shrunk the moment the filter came off. Pages that exist stop being
        // offered, which is worse than the original trap, where the extra pages were visibly empty.
        AnalyticsEngine.PreviewShape cleared = new AnalyticsEngine.PreviewShape(
            null, null, null, null, true);

        DatasetPreviewDto answer = this.engine.preview(this.dataset, 0, 2, 2, cleared);

        // Recounted, not echoed: the fixture holds five rows and the caller offered two.
        assertThat(answer.getTotalRows()).isEqualTo(5L);
        assertThat(answer.isFiltered()).isFalse();
    }

    @Test
    void anUnfilteredTotalIsStillReusedSoPagingDoesNotRecountEveryTurn() throws Exception {
        // The control, and the reason the short-circuit exists at all. A rule that recounted on
        // every page turn would satisfy the test above while making paging twice as expensive as
        // it was before any of this -- and would look identical in a green suite.
        AnalyticsEngine.PreviewShape turning = new AnalyticsEngine.PreviewShape(
            null, null, null, null, false);

        DatasetPreviewDto answer = this.engine.preview(this.dataset, 0, 2, 999, turning);

        // Taken at its word: 999 is not the fixture's six, and no count was issued to find out.
        assertThat(answer.getTotalRows()).isEqualTo(999L);
    }

}

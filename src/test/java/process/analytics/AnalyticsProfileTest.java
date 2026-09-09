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
import process.analytics.dto.ColumnProfileDto;
import process.analytics.dto.DatasetProfileDto;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.security.TenantContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What profileOf() actually gets back from DuckDB, and what it is entitled to say about it.
 *
 * This runs a REAL DuckDB, for the same reason DuckDbLockdownTest does: every claim under test is a
 * claim about what a specific engine returns. A mocked ResultSet would answer whatever this file
 * told it to, so a suite built on one would prove that the derivation agrees with the fixture and
 * nothing else -- and the four things most likely to be got wrong here are all facts about the
 * engine. That min, max, avg, std and the quartiles come back as VARCHAR even on a DATE column.
 * That SUMMARIZE's 'count' is the TOTAL row count and not the non-null one, so no exact null count
 * exists in the result. That approx_unique is HyperLogLog. That an all-null column reports zero
 * distinct values rather than one. Each of those was measured before it was asserted.
 *
 * The engine is real; the LOCATION is redirected. DuckDbSessionFactory hands out sessions with the
 * local filesystem removed and there is no object store in a unit test, so a session that could
 * read this fixture is not a session the feature ever opens. What is stubbed is therefore only the
 * seam that chooses where to read: the s3:// scan expression the service builds is swapped for the
 * same reader pointed at a temp CSV, and everything else -- the SQL, the governor, the timeout, the
 * ResultSet, the twelve columns, the derivation -- is the real thing. What a real SESSION refuses
 * is DuckDbLockdownTest's subject and is not re-asserted here.
 *
 * The fixture is one CSV holding every shape the Quality tab claims to recognise: a key-like
 * column, a constant column, a column that is entirely null, a numeric-looking VARCHAR, a
 * date-looking VARCHAR, a numeric column with a null in it, a real DATE, and -- as the control that
 * matters most -- an ordinary VARCHAR with a repeated value, which must be flagged as none of them.
 * A derivation that answered "yes" to everything would pass a suite made only of positives.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
// Lenient because the governor test never reaches the engine: it is refused a permit before a
// session is opened, which is the property it exists to prove. Strict stubs would report the
// unused half of a shared fixture as a defect in the test.
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalyticsProfileTest {

    private static final int TIMEOUT_SECONDS = 30;
    private static final long TENANT_ID = 1001L;
    private static final String BUCKET = "etl-bucket";
    private static final String PATH = "etl-demo/customers.csv";

    /**
     * Eight columns, six rows, and every value chosen for a flag it is meant to produce.
     *
     * notes is empty on every row; region never changes; id is one per row; name repeats 'ann' so
     * that it is NOT a key; amount is missing on row three; zip keeps its leading zeros, which is
     * what makes DuckDB read it as text; when_txt is dd/MM/yyyy, which the CSV sniffer does not
     * recognise as a date and therefore also reads as text.
     */
    private static final String FIXTURE_CSV =
          "id,region,notes,zip,amount,signup,when_txt,name\n"
        + "1,north,,01234,10.5,2024-01-05,05/01/2024,ann\n"
        + "2,north,,00987,21.0,2024-02-11,11/02/2024,bob\n"
        + "3,north,,01234,,2024-03-02,02/03/2024,cat\n"
        + "4,north,,07001,42.0,2024-04-19,19/04/2024,dan\n"
        + "5,north,,00987,52.5,2024-05-23,23/05/2024,eve\n"
        + "6,north,,01234,63.0,2024-06-30,30/06/2024,ann\n";

    @Mock private DuckDbSessionFactory sessions;

    /** A real, un-locked-down DuckDB. The lock-down is asserted elsewhere; this one has to read. */
    private Connection engine;
    private Path csv;
    private Statement statement;

    /** Every statement that reached the engine, in order. One is the number this feature promises. */
    private final List<String> executed = Collections.synchronizedList(new ArrayList<>());

    private DatasetRef dataset;
    private AnalyticsQueryService service;

    @BeforeEach
    void setUp() throws Exception {
        this.csv = Files.createTempFile("analytics-profile", ".csv");
        Files.write(this.csv, FIXTURE_CSV.getBytes("UTF-8"));

        this.engine = DriverManager.getConnection("jdbc:duckdb:");
        this.dataset = datasetRef();

        String remoteScan = this.dataset.scanExpression();
        String localScan = "read_csv_auto('" + this.csv.toAbsolutePath().toString().replace("'", "''") + "')";

        Connection duck = mock(Connection.class);
        this.statement = mock(Statement.class);
        when(this.sessions.open(any(StorageConnection.class))).thenReturn(duck);
        when(duck.createStatement()).thenReturn(this.statement);
        when(this.statement.executeQuery(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0, String.class);
            this.executed.add(sql);
            // The only substitution anywhere in this test, and it is one string for another of the
            // same shape: read_csv_auto over a URL the test cannot reach, for read_csv_auto over a
            // file it can. The statement around it is the one the service built.
            return this.engine.createStatement().executeQuery(sql.replace(remoteScan, localScan));
        });

        this.service = new AnalyticsQueryService(this.sessions, limits(2));
        // run() names the tenant on its read line, so the context has to exist for it to run at all.
        TenantContext.set(TENANT_ID, "TENANT_USER", 7L, "analyst");
    }

    @AfterEach
    void tearDown() throws Exception {
        this.engine.close();
        Files.deleteIfExists(this.csv);
        // A ThreadLocal on a surefire thread the next test will be handed.
        TenantContext.clear();
    }

    // ---- the constraint the whole design rests on -----------------------------------------------

    @Test
    void bothTabsCostOneScanAndOneSession() throws Exception {
        DatasetProfileDto profile = this.service.profileOf(this.dataset);

        // The central claim of synthesis 3.9. A file open already costs three sessions against a
        // ceiling of four, and a Quality endpoint beside a Profile endpoint would have made five.
        // Everything the Quality tab shows is on this object, and one statement produced it.
        assertThat(this.executed).hasSize(1);
        assertThat(this.executed.get(0)).startsWith("SUMMARIZE SELECT * FROM ");
        verify(this.sessions, times(1)).open(any(StorageConnection.class));
        assertThat(column(profile, "region").isConstant()).isTrue();
        assertThat(column(profile, "id").isKeyLike()).isTrue();
    }

    @Test
    void theProfileRunsUnderTheSameTimeoutAsEveryOtherQuery() throws Exception {
        this.service.profileOf(this.dataset);

        verify(this.statement).setQueryTimeout(TIMEOUT_SECONDS);
    }

    @Test
    void theProfileIsRefusedWhenTheGovernorHasNoPermitLeft() {
        // Taken directly rather than by parking a thread inside the engine: the question is only
        // whether profileOf goes through run(), and a query that opened its own session would sail
        // past a drained semaphore. This is the "no second door" rule, asserted on the new method.
        Semaphore slots = (Semaphore)
            ReflectionTestUtils.getField(this.service.getEngine(), "slots");
        slots.drainPermits();

        AnalyticsException refused = catchThrowableOfType(
            () -> this.service.profileOf(this.dataset), AnalyticsException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).contains("Too many analytics queries");
        assertThat(this.executed).isEmpty();
    }

    // ---- what the engine actually returns -------------------------------------------------------

    @Test
    void summarizeStillReturnsTheTwelveColumnsThisCodeReadsByName() throws Exception {
        // measured() reads all twelve by label. A DuckDB upgrade that renamed one would otherwise
        // surface as an unhelpful "column not found" from inside a lambda in the service.
        try (Statement raw = this.engine.createStatement();
             ResultSet rows = raw.executeQuery("SUMMARIZE SELECT * FROM read_csv_auto('"
                 + this.csv.toAbsolutePath() + "')")) {

            ResultSetMetaData meta = rows.getMetaData();
            List<String> labels = new ArrayList<>();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                labels.add(meta.getColumnLabel(i));
            }
            assertThat(labels).containsExactly("column_name", "column_type", "min", "max",
                "approx_unique", "avg", "std", "q25", "q50", "q75", "count", "null_percentage");
        }
    }

    @Test
    void everyStatisticIsCarriedAsTextBecauseSomeOfThemAreDates() throws Exception {
        DatasetProfileDto profile = this.service.profileOf(this.dataset);

        // The trap this DTO is shaped around. signup is a DATE, and DuckDB fills its quartiles in
        // -- q25/q50/q75 are null for VARCHAR and BOOLEAN but present for DATE -- so a profile that
        // parsed the quartile columns to double would compile, pass on every numeric file, and
        // throw the first time a user opened one with a date in it.
        ColumnProfileDto signup = column(profile, "signup");
        assertThat(signup.getType()).isEqualTo("DATE");
        assertThat(signup.getMin()).isEqualTo("2024-01-05");
        assertThat(signup.getApproxQ50()).isEqualTo("2024-03-26");
        assertThat(signup.getAvg()).isNull();
        assertThat(signup.getStd()).isNull();

        ColumnProfileDto amount = column(profile, "amount");
        assertThat(amount.getAvg()).isEqualTo("37.8");
        assertThat(amount.getApproxQ25()).isNotNull();
    }

    @Test
    void theCountFromSummarizeIsTheTotalRowCountNotTheNonNullOne() throws Exception {
        DatasetProfileDto profile = this.service.profileOf(this.dataset);

        // amount is missing on one of the six rows. SUMMARIZE still reports six, and reports the
        // gap only as a percentage -- so the dataset row count is exact and free, and the null
        // count on the column is a reconstruction. Nothing on this object may claim otherwise.
        assertThat(profile.getTotalRows()).isEqualTo(6L);
        ColumnProfileDto amount = column(profile, "amount");
        assertThat(amount.getNullPercentage()).isEqualByComparingTo("16.67");
        assertThat(amount.getCompleteness()).isEqualByComparingTo("83.33");
        assertThat(amount.getApproxNullRows()).isEqualTo(1L);
    }

    // ---- the quality flags, each with its negative control --------------------------------------

    @Test
    void aColumnThatIsEntirelyNullIsNamedAsSuch() throws Exception {
        DatasetProfileDto profile = this.service.profileOf(this.dataset);

        ColumnProfileDto notes = column(profile, "notes");
        assertThat(notes.isAllNull()).isTrue();
        assertThat(notes.getCompleteness()).isEqualByComparingTo("0.00");
        assertThat(notes.getApproxNullRows()).isEqualTo(6L);
        // The measured detail that keeps allNull and constant from colliding: an empty column
        // reports ZERO distinct values, not one.
        assertThat(notes.getApproxDistinct()).isZero();
        assertThat(notes.isConstant()).isFalse();
        assertThat(notes.isKeyLike()).isFalse();

        assertThat(column(profile, "region").isAllNull()).isFalse();
    }

    @Test
    void aColumnWithOneValueInItIsNamedAsConstant() throws Exception {
        DatasetProfileDto profile = this.service.profileOf(this.dataset);

        ColumnProfileDto region = column(profile, "region");
        assertThat(region.isConstant()).isTrue();
        assertThat(region.getApproxDistinct()).isEqualTo(1L);
        assertThat(region.getMin()).isEqualTo("north");
        assertThat(region.isKeyLike()).isFalse();

        assertThat(column(profile, "name").isConstant()).isFalse();
    }

    @Test
    void aColumnWithOneValuePerRowIsNamedAsKeyLikeAndOneWithARepeatIsNot() throws Exception {
        DatasetProfileDto profile = this.service.profileOf(this.dataset);

        assertThat(column(profile, "id").isKeyLike()).isTrue();
        assertThat(column(profile, "signup").isKeyLike()).isTrue();

        // The control that stops the flag meaning nothing. name repeats 'ann', so five distinct
        // values in six rows -- 0.83, under the threshold -- and amount has a null, which no key
        // has, whatever its distinct count says.
        ColumnProfileDto name = column(profile, "name");
        assertThat(name.getApproxDistinct()).isEqualTo(5L);
        assertThat(name.isKeyLike()).isFalse();
        assertThat(column(profile, "amount").isKeyLike()).isFalse();
        assertThat(column(profile, "notes").isKeyLike()).isFalse();
    }

    @Test
    void aTextColumnHoldingNumbersOrDatesIsFlaggedAsATypeSurprise() throws Exception {
        DatasetProfileDto profile = this.service.profileOf(this.dataset);

        // Both of these are genuinely VARCHAR as far as DuckDB is concerned, which is the point:
        // leading zeros stop zip being read as a number, and dd/MM/yyyy stops when_txt being read
        // as a date. Whether either SHOULD have been converted is the user's call -- casting a zip
        // code to a number is how the leading zero gets lost -- so this is a prompt, not a finding.
        ColumnProfileDto zip = column(profile, "zip");
        assertThat(zip.getType()).isEqualTo("VARCHAR");
        assertThat(zip.getTypeSurprise()).isEqualTo(ColumnProfileDto.SURPRISE_NUMBER);

        ColumnProfileDto whenText = column(profile, "when_txt");
        assertThat(whenText.getType()).isEqualTo("VARCHAR");
        assertThat(whenText.getTypeSurprise()).isEqualTo(ColumnProfileDto.SURPRISE_DATE);

        // Three controls: ordinary text is not a surprise, a column DuckDB already read as a
        // number is not one either, and neither is a column with no values to look at.
        assertThat(column(profile, "name").getTypeSurprise()).isNull();
        assertThat(column(profile, "amount").getTypeSurprise()).isNull();
        assertThat(column(profile, "notes").getTypeSurprise()).isNull();
    }

    @Test
    void aDatasetWithNoRowsReturnsNoStatisticsRatherThanZeroes() throws Exception {
        // The branch the whole empty-state UI rests on, and the one nothing here exercised: the
        // fixture has six rows, so every derived field was always present. A header-only file --
        // the normal shape of a botched export, and exactly the file a quality tab exists for --
        // takes the other path through derive().
        //
        // Measured on DuckDB 1.1.3 rather than assumed: SUMMARIZE over an empty relation returns
        // count=0 and null_percentage=NULL, NOT 0.00. So completeness, nullPercentage and
        // approxNullRows are all null here, and a screen that rendered them as numbers would draw
        // a "0% filled" bar from a percentage the engine declined to give. It is the difference
        // between "no rows are filled" and "there is nothing to measure".
        Path empty = Files.createTempFile("analytics-profile-empty", ".csv");
        try {
            Files.write(empty, "id,name\n".getBytes("UTF-8"));
            String localScan = "read_csv_auto('"
                + empty.toAbsolutePath().toString().replace("'", "''") + "')";
            String remoteScan = this.dataset.scanExpression();
            // doAnswer, not when(...). when(mock.executeQuery(anyString())) CALLS the method to
            // record the stub, and the call runs setUp's existing answer with "" -- which reaches
            // the engine as an empty statement and fails with "No statements to execute" before
            // this test has done anything. doAnswer stubs without invoking.
            doAnswer(call -> {
                String sql = call.getArgument(0, String.class);
                this.executed.add(sql);
                return this.engine.createStatement().executeQuery(sql.replace(remoteScan, localScan));
            }).when(this.statement).executeQuery(anyString());

            DatasetProfileDto profile = this.service.profileOf(this.dataset);

            assertThat(profile.getTotalRows()).isZero();
            // The columns still exist and are still named -- the file has a header. Only the
            // statistics are absent, which is why the schema is still worth showing.
            assertThat(profile.getColumns()).hasSize(2);
            ColumnProfileDto id = profile.getColumns().get(0);
            assertThat(id.getName()).isEqualTo("id");
            assertThat(id.getNullPercentage()).isNull();
            assertThat(id.getCompleteness()).isNull();
            assertThat(id.getApproxNullRows()).isNull();
            // And none of the flags fires on evidence that does not exist. allNull in particular
            // must NOT be true here: a column with no rows is not a column full of nulls.
            assertThat(id.isAllNull()).isFalse();
            assertThat(id.isConstant()).isFalse();
            assertThat(id.isKeyLike()).isFalse();
            assertThat(id.getTypeSurprise()).isNull();
        } finally {
            Files.deleteIfExists(empty);
        }
    }

    @Test
    void theProfileDescribesTheDatasetItWasAskedAbout() throws Exception {
        DatasetProfileDto profile = this.service.profileOf(this.dataset);

        assertThat(profile.getBucket()).isEqualTo(BUCKET);
        assertThat(profile.getPath()).isEqualTo(PATH);
        assertThat(profile.getFormat()).isEqualTo("CSV");
        assertThat(profile.isMultiFile()).isFalse();
        assertThat(profile.getColumns()).extracting(ColumnProfileDto::getName)
            .containsExactly("id", "region", "notes", "zip", "amount", "signup", "when_txt", "name");
    }

    // ---- fixture --------------------------------------------------------------------------------

    private static ColumnProfileDto column(DatasetProfileDto profile, String name) {
        return profile.getColumns().stream()
            .filter(candidate -> name.equals(candidate.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the profile has no column named " + name));
    }

    /**
     * DatasetRef's constructor is package-private and DatasetResolver is its only production
     * caller. This test sits in that package, so it can hand the service the object the resolver
     * would have handed it rather than mocking a final class.
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

    private static AnalyticsLimits limits(int maxConcurrent) {
        AnalyticsLimits limits = new AnalyticsLimits();
        ReflectionTestUtils.setField(limits, "timeoutSeconds", TIMEOUT_SECONDS);
        ReflectionTestUtils.setField(limits, "maxRows", 1000);
        ReflectionTestUtils.setField(limits, "previewPageSize", 100);
        ReflectionTestUtils.setField(limits, "maxConcurrentQueries", maxConcurrent);
        return limits;
    }
}

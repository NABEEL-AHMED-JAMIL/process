package process.analytics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import process.analytics.dto.DatasetPreviewDto;
import process.analytics.dto.DatasetSchemaDto;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.security.TenantContext;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The four promises this class makes that nothing else in the module can make for it.
 *
 * It is the only place an analytics query runs, and every bound the feature claims to have is
 * either applied here or is not applied at all: the governor that stops one careless request
 * taking the backend down, the row ceiling that analytics.query.max-rows names, the count a page
 * turn is allowed to skip, and the translation of an engine failure into something a person can
 * act on. None of them failed visibly when they were wrong. A missing semaphore release shows up
 * as an application that stops answering after a few hours; an unbounded query shows up as an OOM
 * in a container that also runs the ETL dispatcher; a leaked engine message shows up in a bug
 * report from a customer who can now see an internal host name.
 *
 * DuckDbSessionFactory is mocked rather than run. What a real session refuses is DuckDbLockdownTest's
 * subject and would be worthless as a mock; what THIS class does with a session -- how many it
 * opens, what SQL it puts through one, and what it does when one fails -- is the opposite, and is
 * unobservable against a real engine because a real engine will not fail on demand with the twelve
 * specific messages below. Those messages are real DuckDB output, copied rather than invented,
 * because explain() matches on their wording and a mapping tested against strings someone made up
 * proves only that the strings agree with each other.
 *
 * Stubbing is lenient by decision. The fixture scripts a whole engine -- DESCRIBE, count and page
 * results, plus a failure mode -- and which parts of it a given test drives depends on the query
 * under test, so strict stubs would report the unused half of a deliberately complete fake as a
 * defect in the test rather than in the code.
 *
 * Every refusal here is paired with a positive control. A governor that refused everything, a
 * clamp that returned zero rows and a mapper that answered "The dataset could not be read." to all
 * twelve messages would satisfy a suite made only of refusals.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalyticsQueryServiceTest {

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_ROWS = 1000;
    private static final int PAGE_SIZE = 100;

    private static final long TENANT_ID = 1001L;
    private static final String BUCKET = "etl-bucket";
    private static final String PATH = "etl-demo/sales.csv";
    private static final String URL = "s3://etl-bucket/etl-demo/sales.csv";

    /**
     * Sits on the connection so the log assertion has something real to fail on.
     *
     * The Q5 claim -- that the read line is safe at info because DatasetRef.toString() names the
     * location without the credentials that reach it -- is only worth asserting if the credential
     * is actually present on the object being logged.
     */
    private static final String STORED_SECRET = "AKIAI44QH8DHBEXAMPLE";

    /** How long the governor makes a caller wait before refusing. Mirrors SLOT_WAIT_SECONDS. */
    private static final long SLOT_WAIT_MILLIS = 2000L;

    @Mock private DuckDbSessionFactory sessions;

    private Connection duck;
    private Statement statement;

    /** Every statement the service put through a session, in order. The subject of most of this. */
    private final List<String> executed = Collections.synchronizedList(new ArrayList<>());

    private long countAnswer = 4200L;
    private int pageRows = 2;
    private Object amountValue = new BigDecimal("9.99");

    /** When set, every query fails with it. This is how explain() is reached. */
    private SQLException failure;

    /** When set, a query parks inside the engine so a second caller meets a held permit. */
    private CountDownLatch executing;
    private CountDownLatch release;

    private DatasetRef dataset;
    private AnalyticsQueryService service;

    private ch.qos.logback.classic.Logger serviceLogger;
    private ListAppender<ILoggingEvent> logged;
    private Level originalLevel;

    @BeforeEach
    void setUp() throws SQLException {
        this.dataset = datasetRef();
        this.duck = mock(Connection.class);
        this.statement = mock(Statement.class);
        when(this.sessions.open(any(StorageConnection.class))).thenReturn(this.duck);
        when(this.duck.createStatement()).thenReturn(this.statement);
        when(this.statement.executeQuery(anyString())).thenAnswer(this::engine);

        this.service = new AnalyticsQueryService(this.sessions, limits(2, MAX_ROWS, PAGE_SIZE));

        // The read line names the tenant, so the context has to exist for that assertion to mean
        // anything. Cleared afterwards because it is a ThreadLocal on a pooled surefire thread.
        TenantContext.set(TENANT_ID, "TENANT_USER", 7L, "analyst");

        // The engine's logger, not the service's: the read line, the statement-rejection
        // warning and the unmapped-failure error all moved to DuckDbAnalyticsEngine when the
        // engine seam was introduced, and an appender on the old name would record nothing while
        // every assertion below still read as though it were watching.
        this.serviceLogger = (ch.qos.logback.classic.Logger)
            LoggerFactory.getLogger(DuckDbAnalyticsEngine.class);
        this.originalLevel = this.serviceLogger.getLevel();
        // Pinned at INFO rather than left at logback.xml's debug: at debug a demoted read line
        // would still be recorded and the Q5 assertion below would pass while the property it
        // exists to protect had been reverted.
        this.serviceLogger.setLevel(Level.INFO);
        this.logged = new ListAppender<>();
        this.logged.start();
        this.serviceLogger.addAppender(this.logged);
    }

    @AfterEach
    void tearDown() {
        // Released unconditionally: a test that failed its assertion before releasing would
        // otherwise leave the holder thread parked for the rest of the run.
        if (this.release != null) {
            this.release.countDown();
        }
        this.serviceLogger.detachAppender(this.logged);
        this.serviceLogger.setLevel(this.originalLevel);
        TenantContext.clear();
    }

    // ---- the fixture ---------------------------------------------------------------------------

    /**
     * DatasetRef's constructor is package-private and the resolver is its only production caller.
     * This test sits in that package, so it can hand the service the object the resolver would
     * have handed it rather than mocking a final class.
     */
    private static DatasetRef datasetRef() {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(9L);
        connection.setTenantId(TENANT_ID);
        connection.setProvider(StorageProvider.S3);
        connection.setAlias("store");
        connection.setBucketName(BUCKET);
        connection.setSecretKeyEnc(STORED_SECRET);
        connection.setStatus(Status.Active);
        return new DatasetRef(connection, BUCKET, PATH, DatasetRef.Format.CSV);
    }

    private static AnalyticsLimits limits(int maxConcurrent, int maxRows, int previewPageSize) {
        AnalyticsLimits limits = new AnalyticsLimits();
        ReflectionTestUtils.setField(limits, "timeoutSeconds", TIMEOUT_SECONDS);
        ReflectionTestUtils.setField(limits, "maxRows", maxRows);
        ReflectionTestUtils.setField(limits, "previewPageSize", previewPageSize);
        ReflectionTestUtils.setField(limits, "maxConcurrentQueries", maxConcurrent);
        return limits;
    }

    /**
     * A DuckDB that answers the three statements this feature issues, and fails on demand.
     *
     * Dispatching on the SQL rather than on call order is deliberate: preview() issues its count
     * and its page through two separate sessions, and a fixture keyed on order would keep passing
     * if those two were ever swapped or one of them silently dropped -- which is precisely the
     * change gap 17 makes.
     */
    private ResultSet engine(InvocationOnMock invocation) throws Throwable {
        String sql = invocation.getArgument(0);
        this.executed.add(sql);
        if (this.executing != null) {
            this.executing.countDown();
            this.release.await(10, TimeUnit.SECONDS);
        }
        if (this.failure != null) {
            throw this.failure;
        }
        if (sql.startsWith("DESCRIBE")) {
            return describeResult();
        }
        if (sql.toLowerCase(Locale.ROOT).contains("count(*)")) {
            return countResult();
        }
        return pageResult();
    }

    private static ResultSet describeResult() throws SQLException {
        ResultSet rows = mock(ResultSet.class);
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getString("column_name")).thenReturn("id", "amount");
        when(rows.getString("column_type")).thenReturn("BIGINT", "DECIMAL(10,2)");
        return rows;
    }

    private ResultSet countResult() throws SQLException {
        ResultSet rows = mock(ResultSet.class);
        when(rows.next()).thenReturn(true, false);
        when(rows.getLong(1)).thenReturn(this.countAnswer);
        return rows;
    }

    private ResultSet pageResult() throws SQLException {
        ResultSet rows = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(rows.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(2);
        when(meta.getColumnLabel(1)).thenReturn("id");
        when(meta.getColumnLabel(2)).thenReturn("amount");
        AtomicInteger emitted = new AtomicInteger();
        when(rows.next()).thenAnswer(call -> emitted.getAndIncrement() < this.pageRows);
        when(rows.getObject(1)).thenAnswer(call -> (long) emitted.get());
        when(rows.getObject(2)).thenAnswer(call -> this.amountValue);
        return rows;
    }

    /** The single statement the service issued, when a test expects exactly one. */
    private String onlyStatement() {
        assertThat(this.executed).hasSize(1);
        return this.executed.get(0);
    }

    /**
     * Starts a caller that reaches the engine and parks there, holding its permit.
     *
     * Returns once the permit is definitely taken, so a governor assertion made afterwards is
     * about the ceiling rather than about a race the test happened to lose.
     */
    private Thread aCallerHoldingAPermit(AnalyticsQueryService governed) throws InterruptedException {
        this.executing = new CountDownLatch(1);
        this.release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                governed.rowCount(this.dataset);
            } catch (AnalyticsException ignored) {
                // The holder's own outcome is never what these tests are about.
            }
        }, "analytics-slot-holder");
        holder.start();
        assertThat(this.executing.await(10, TimeUnit.SECONDS))
            .as("the first caller should have reached the engine and be holding a permit")
            .isTrue();
        return holder;
    }

    private void letGo(Thread holder) throws InterruptedException {
        this.release.countDown();
        holder.join(TimeUnit.SECONDS.toMillis(10));
    }

    /** Drives explain() by failing the engine, and returns the sentence the user would see. */
    private String refusalFor(String engineMessage) {
        this.failure = new SQLException(engineMessage);
        AnalyticsException refused = catchThrowableOfType(
            () -> this.service.rowCount(this.dataset), AnalyticsException.class);
        assertThat(refused).as("the engine failed, so something should have been thrown").isNotNull();
        return refused.getMessage();
    }

    // ---- positive controls, so a refusal below is a refusal and not a broken fixture ------------

    @Test
    void schemaOfDescribesTheDatasetWithoutReadingItsRows() throws Exception {
        DatasetSchemaDto schema = this.service.schemaOf(this.dataset);

        // DESCRIBE rather than a SELECT with a zero LIMIT: the reader settles the schema from the
        // Parquet footer or a CSV sample, which is what makes this cheap enough to call on click.
        assertThat(onlyStatement()).startsWith("DESCRIBE SELECT * FROM ");
        assertThat(onlyStatement()).contains(URL);
        assertThat(schema.getBucket()).isEqualTo(BUCKET);
        assertThat(schema.getPath()).isEqualTo(PATH);
        assertThat(schema.getFormat()).isEqualTo("CSV");
        assertThat(schema.getColumns()).hasSize(2);
        assertThat(schema.getColumns().get(0).getName()).isEqualTo("id");
        assertThat(schema.getColumns().get(1).getType()).isEqualTo("DECIMAL(10,2)");
    }

    @Test
    void previewReturnsThePageTheColumnsAndTheTotal() throws Exception {
        DatasetPreviewDto page = this.service.preview(this.dataset, 0, 50);

        assertThat(page.getColumns()).containsExactly("id", "amount");
        assertThat(page.getRows()).hasSize(2);
        assertThat(page.getPage()).isZero();
        assertThat(page.getPageSize()).isEqualTo(50);
        assertThat(page.getTotalRows()).isEqualTo(4200L);
        assertThat(page.isMultiFile()).isFalse();
    }

    @Test
    void aValueIsRenderedAsTextAndANullStaysNull() throws Exception {
        this.amountValue = null;
        this.pageRows = 1;

        DatasetPreviewDto page = this.service.preview(this.dataset, 0, 10);

        // Rendered here rather than in the browser: a DECIMAL that arrives as a JavaScript double
        // has lost precision before anyone looks at it. A null must stay a null rather than
        // becoming the four characters "null", which would be indistinguishable from the value.
        assertThat(page.getRows().get(0)).containsExactly("1", null);
    }

    // ---- gap 17: the count a page turn already holds --------------------------------------------

    @Test
    void aKnownTotalIsEchoedBackAndTheCountIsNeverIssued() throws Exception {
        DatasetPreviewDto page = this.service.preview(this.dataset, 3, 50, 4200);

        // The whole of gap 17 is this assertion. A page turn that re-counts costs a second session
        // and a second governor permit for a number the browser is already displaying, which
        // against a ceiling of four is what makes four people paging feel like eight.
        assertThat(this.executed)
            .as("a known total must skip the count entirely, not merely ignore its answer")
            .noneMatch(sql -> sql.toLowerCase(Locale.ROOT).contains("count(*)"));
        assertThat(this.executed).hasSize(1);
        verify(this.sessions, times(1)).open(any(StorageConnection.class));
        assertThat(page.getTotalRows()).isEqualTo(4200L);
    }

    @Test
    void anAbsentKnownTotalStillCounts() throws Exception {
        // The positive control for the assertion above. Without it, a preview() that had stopped
        // counting altogether -- returning zero for every dataset -- would pass that test.
        DatasetPreviewDto page = this.service.preview(this.dataset, 0, 50, null);

        assertThat(this.executed).hasSize(2);
        assertThat(this.executed.get(0).toLowerCase(Locale.ROOT)).contains("count(*)");
        verify(this.sessions, times(2)).open(any(StorageConnection.class));
        assertThat(page.getTotalRows()).isEqualTo(4200L);
    }

    @Test
    void aNonPositiveKnownTotalCounts() throws Exception {
        // Zero is what an uninitialised client field looks like and -1 is what "unknown" looks
        // like in a browser. Neither is a total somebody counted, so both fall back to counting.
        for (Integer notATotal : new Integer[] { 0, -1 }) {
            this.executed.clear();
            this.service.preview(this.dataset, 0, 50, notATotal);
            assertThat(this.executed).as("knownTotal=%s", notATotal).hasSize(2);
            assertThat(this.executed.get(0).toLowerCase(Locale.ROOT)).contains("count(*)");
        }
    }

    @Test
    void theThreeArgumentPreviewCountsBecauseItHasNothingToCarryForward() throws Exception {
        // The overload the first request for a dataset takes, and the reason gap 17 is additive:
        // the old signature had to keep behaving exactly as it did.
        this.service.preview(this.dataset, 0, 50);

        assertThat(this.executed).hasSize(2);
        assertThat(this.executed.get(0).toLowerCase(Locale.ROOT)).contains("count(*)");
    }

    @Test
    void aKnownTotalIsEchoedRatherThanTrusted() throws Exception {
        // It is a display value. Checking it against the dataset would be the count this exists to
        // skip, so a wrong one is allowed through and the worst it does is misdraw a page control.
        this.countAnswer = 9L;

        DatasetPreviewDto page = this.service.preview(this.dataset, 0, 50, 123456);

        assertThat(page.getTotalRows()).isEqualTo(123456L);
    }

    // ---- gap 14: the page-size clamp and the row ceiling ----------------------------------------

    @Test
    void anAbsentPageSizeIsTheConfiguredOne() throws Exception {
        DatasetPreviewDto page = this.service.preview(this.dataset, 0, null, 10);

        assertThat(page.getPageSize()).isEqualTo(PAGE_SIZE);
        assertThat(onlyStatement()).contains("LIMIT " + PAGE_SIZE + " OFFSET 0");
    }

    @Test
    void aNonsensePageSizeIsTheConfiguredOne() throws Exception {
        for (Integer nonsense : new Integer[] { 0, -25 }) {
            this.executed.clear();
            DatasetPreviewDto page = this.service.preview(this.dataset, 0, nonsense, 10);
            assertThat(page.getPageSize()).as("pageSize=%s", nonsense).isEqualTo(PAGE_SIZE);
        }
    }

    @Test
    void aPageSizeAboveTheCeilingIsClampedToIt() throws Exception {
        DatasetPreviewDto page = this.service.preview(this.dataset, 0, 1_000_000, 10);

        // The page size is a request, not an instruction. A caller asking for a million rows gets
        // the maximum, because the rows would otherwise be serialised to a browser that then has
        // to hold them.
        assertThat(page.getPageSize()).isEqualTo(MAX_ROWS);
        assertThat(onlyStatement()).contains("LIMIT " + MAX_ROWS + " OFFSET 0");
    }

    @Test
    void theConfiguredPageSizeIsItselfClampedByTheCeiling() throws Exception {
        // An operator who sets preview.page-size above max-rows has written a contradiction. The
        // ceiling wins, because it is the one of the two that exists to protect the process.
        AnalyticsQueryService misconfigured =
            new AnalyticsQueryService(this.sessions, limits(2, 20, 500));

        DatasetPreviewDto page = misconfigured.preview(this.dataset, 0, null, 10);

        assertThat(page.getPageSize()).isEqualTo(20);
    }

    @Test
    void theOffsetIsThePageTimesTheClampedSize() throws Exception {
        this.service.preview(this.dataset, 3, 1_000_000, 10);

        // Against the clamped size, not the requested one. Multiplying the page by what the caller
        // asked for would page in steps of a million through a result of a thousand.
        assertThat(onlyStatement())
            .contains("LIMIT " + MAX_ROWS + " OFFSET " + (3 * MAX_ROWS));
    }

    @Test
    void aNegativePageIsTheFirstPage() throws Exception {
        this.service.preview(this.dataset, -4, 50, 10);

        // A negative OFFSET is a syntax error in DuckDB, so this would surface as an engine
        // failure on a request a user could reach by editing a URL.
        assertThat(onlyStatement()).contains("OFFSET 0");
    }

    @Test
    void boundedWrapsTheQueryRatherThanAppendingToIt() throws Exception {
        String bounded = this.service.bounded("SELECT region, sum(amount) FROM sales "
            + "GROUP BY region ORDER BY 2 DESC LIMIT 5000000");

        // Appending is what a first attempt does and it is wrong on every query a person writes:
        // "... LIMIT 5000000 LIMIT 1000" does not parse, and appending after an ORDER BY changes
        // which rows come back rather than how many. Wrapping bounds the inside without having to
        // understand it.
        assertThat(bounded).isEqualTo("SELECT * FROM (SELECT region, sum(amount) FROM sales "
            + "GROUP BY region ORDER BY 2 DESC LIMIT 5000000) AS bounded_query LIMIT " + MAX_ROWS);
    }

    @Test
    void boundedStripsTheSemicolonAPersonLeavesBehind() throws Exception {
        // The one thing that cannot survive being wrapped, and the thing a person typing SQL into
        // an editor leaves behind most often. Repeated because a paste can leave more than one.
        assertThat(this.service.bounded("SELECT 1;"))
            .isEqualTo("SELECT * FROM (SELECT 1) AS bounded_query LIMIT " + MAX_ROWS);
        assertThat(this.service.bounded("  SELECT 1 ; ; \n"))
            .isEqualTo("SELECT * FROM (SELECT 1) AS bounded_query LIMIT " + MAX_ROWS);
    }

    @Test
    void boundedTakesItsCeilingFromTheProperty() throws Exception {
        AnalyticsQueryService tight = new AnalyticsQueryService(this.sessions, limits(2, 7, 100));

        // The property is the point of the method. A hard-coded ceiling here would make
        // analytics.query.max-rows a lie in the other direction.
        assertThat(tight.bounded("SELECT * FROM sales")).endsWith("AS bounded_query LIMIT 7");
    }

    @Test
    void boundedRefusesAQueryThatIsNotThere() {
        // Null, empty and a lone semicolon all reach this from a phase-three editor whose text
        // area the user has not typed in yet. Wrapping any of them produces "SELECT * FROM ()",
        // whose parser error would be reported as a bug in the query the user did not write.
        for (String nothing : new String[] { null, "", "   ", " ; ; " }) {
            AnalyticsException refused = catchThrowableOfType(
                () -> this.service.bounded(nothing), AnalyticsException.class);
            assertThat(refused).as("bounded(%s)", nothing).isNotNull();
            assertThat(refused.getMessage()).isEqualTo("There is no query to run.");
        }
    }

    @Test
    void thePreviewPageIsItselfBounded() throws Exception {
        this.service.preview(this.dataset, 0, 50, 10);

        // The clamp and the bound are different policies that happen to agree today: the clamp
        // says how big a PAGE may be, the bound says how big a RESULT may be. This asserts the
        // preview goes through the bound rather than relying on the clamp, so the door phase three
        // walks through is already the guarded one.
        assertThat(onlyStatement())
            .startsWith("SELECT * FROM (SELECT * FROM ")
            .endsWith(") AS bounded_query LIMIT " + MAX_ROWS);
    }

    @Test
    void theCountAndTheDescribeAreDeliberatelyNotBounded() throws Exception {
        this.service.rowCount(this.dataset);
        String count = onlyStatement();
        this.executed.clear();
        this.service.schemaOf(this.dataset);

        // count(*) returns one row whatever the dataset is, and DESCRIBE returns a row per column
        // and is not a SELECT the wrapper could legally contain. Wrapping either adds a subquery
        // for nothing, and this pins that as a decision rather than an omission.
        assertThat(count).doesNotContain("bounded_query");
        assertThat(onlyStatement()).doesNotContain("bounded_query");
    }

    // ---- the governor --------------------------------------------------------------------------

    @Test
    void aCallerThatCannotGetASlotIsRefusedRatherThanQueued() throws Exception {
        AnalyticsQueryService single =
            new AnalyticsQueryService(this.sessions, limits(1, MAX_ROWS, PAGE_SIZE));
        Thread holder = aCallerHoldingAPermit(single);

        long startedAt = System.currentTimeMillis();
        AnalyticsException refused = catchThrowableOfType(
            () -> single.rowCount(this.dataset), AnalyticsException.class);
        long waited = System.currentTimeMillis() - startedAt;

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage())
            .contains("Too many analytics queries are running right now")
            // Advice, not a diagnosis. The caller can do something about this and the sentence has
            // to say what, because it is the message a user meets under load.
            .contains("Try again in a moment");
        // It waited, so the refusal is a ceiling being hit rather than the permit never being
        // taken -- and it stopped waiting, so a slow query cannot turn into a slow application.
        assertThat(waited).isGreaterThanOrEqualTo(SLOT_WAIT_MILLIS - 250L);
        assertThat(waited).isLessThan(SLOT_WAIT_MILLIS * 4);

        letGo(holder);
    }

    @Test
    void aRefusedCallerNeverPaysForASessionOrReachesTheEngine() throws Exception {
        AnalyticsQueryService single =
            new AnalyticsQueryService(this.sessions, limits(1, MAX_ROWS, PAGE_SIZE));
        Thread holder = aCallerHoldingAPermit(single);

        catchThrowableOfType(() -> single.rowCount(this.dataset), AnalyticsException.class);

        // The slot is taken before the session is opened, and this is the reason for that order:
        // the other way round, a rejected caller has already cost a DuckDB session, an INSTALL, a
        // LOAD and a decrypted secret. Only the holder's session and the holder's statement exist.
        verify(this.sessions, times(1)).open(any(StorageConnection.class));
        verify(this.duck, times(1)).createStatement();
        assertThat(this.executed).hasSize(1);

        letGo(holder);
    }

    @Test
    void aSlotIsGivenBackWhenTheQuerySucceeds() throws Exception {
        AnalyticsQueryService single =
            new AnalyticsQueryService(this.sessions, limits(1, MAX_ROWS, PAGE_SIZE));

        // The positive control for the refusal above, and the leak that would take hours to
        // notice: with a ceiling of one, a permit that is not released makes the SECOND query the
        // application ever runs the last one it ever runs.
        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(single.rowCount(this.dataset)).isEqualTo(4200L);
        }
    }

    @Test
    void aSlotIsGivenBackWhenTheQueryFails() throws Exception {
        AnalyticsQueryService single =
            new AnalyticsQueryService(this.sessions, limits(1, MAX_ROWS, PAGE_SIZE));
        this.failure = new SQLException("Out of Memory Error: failed to allocate data of size "
            + "256.0 MiB (512.0 MiB/512.0 MiB used)");

        AnalyticsException first = catchThrowableOfType(
            () -> single.rowCount(this.dataset), AnalyticsException.class);
        AnalyticsException second = catchThrowableOfType(
            () -> single.rowCount(this.dataset), AnalyticsException.class);

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        // The failing path is the one that leaks a permit, because it leaves through a catch
        // rather than a return. If the release were not in a finally, the second caller would be
        // refused by the governor instead of being told what actually went wrong.
        assertThat(first.getMessage()).contains("more memory than analytics is allowed to use");
        assertThat(second.getMessage())
            .as("the second caller should meet the same engine failure, not the governor")
            .isEqualTo(first.getMessage());

        this.failure = null;
        assertThat(single.rowCount(this.dataset)).isEqualTo(4200L);
    }

    @Test
    void callersUnderTheCeilingBothGetThrough() throws Exception {
        AnalyticsQueryService pair =
            new AnalyticsQueryService(this.sessions, limits(2, MAX_ROWS, PAGE_SIZE));
        Thread holder = aCallerHoldingAPermit(pair);

        // The other positive control: with two permits and one held, the second caller must not be
        // refused. A governor that refused everything would satisfy every assertion above this.
        this.release.countDown();
        assertThat(pair.rowCount(this.dataset)).isEqualTo(4200L);

        holder.join(TimeUnit.SECONDS.toMillis(10));
    }

    // ---- the timeout and the session ------------------------------------------------------------

    @Test
    void everyStatementCarriesTheConfiguredTimeout() throws Exception {
        this.service.preview(this.dataset, 0, 50, null);

        // Set per statement rather than once per session, so both halves of a preview are bounded.
        // The timeout is what stops a wedged scan holding a permit for ever and shrinking the
        // ceiling for everybody else.
        verify(this.statement, times(2)).setQueryTimeout(TIMEOUT_SECONDS);
    }

    @Test
    void everyQueryClosesTheSessionItOpened() throws Exception {
        this.service.preview(this.dataset, 0, 50, null);

        // A session is per query and dies with it. That is what stops one caller's attached
        // credentials and in-memory catalogue being visible to the next, so a session that
        // outlives its query is not a leak of memory but a leak of access.
        verify(this.duck, times(2)).close();
        verify(this.statement, times(2)).close();
    }

    @Test
    void aFailedQueryStillClosesItsSession() throws Exception {
        this.failure = new SQLException("Out of Memory Error: failed to allocate data");

        catchThrowableOfType(() -> this.service.rowCount(this.dataset), AnalyticsException.class);

        verify(this.duck, times(1)).close();
    }

    // ---- Q5: the read line ------------------------------------------------------------------------

    @Test
    void aDatasetReadIsRecordedAtInfoSoWhoReadWhatIsAnswerable() throws Exception {
        this.service.rowCount(this.dataset);

        assertThat(this.logged.list)
            .as("the read line is the only record anywhere that a dataset was read; at debug it "
                + "is absent from a normal deployment's logs")
            .hasSize(1);
        ILoggingEvent event = this.logged.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        // The two halves of "who read what". Neither is useful without the other.
        assertThat(event.getFormattedMessage()).contains(BUCKET + "/" + PATH);
        assertThat(event.getFormattedMessage()).contains(String.valueOf(TENANT_ID));
    }

    @Test
    void theReadLineNamesTheLocationWithoutTheCredentialsThatReachIt() throws Exception {
        this.service.rowCount(this.dataset);

        // The entire safety case for raising this line to info. The connection it logs carries an
        // encrypted secret and an access key; DatasetRef.toString() was written to name the
        // location and nothing else, and this is what notices if it is ever changed to name more.
        assertThat(this.logged.list.get(0).getFormattedMessage())
            .doesNotContain(STORED_SECRET)
            .doesNotContain("SecretKey")
            .doesNotContain("StorageConnection@");
    }

    // ---- gap 20: explain(), against real DuckDB output --------------------------------------------

    @Test
    void aSyntaxErrorComesBackInDuckDbsOwnWords() {
        String refusal = refusalFor("Parser Error: syntax error at or near \"SELCT\"\n"
            + "LINE 1: SELCT * FROM read_csv_auto('" + URL + "')\n        ^");

        // The half of gap 20 that phase three needs. Once the user holds the SQL, "syntax error at
        // or near SELCT" says more than any sentence written here, and the generic fallback --
        // right while the SQL is ours -- becomes the least useful answer possible.
        assertThat(refusal).contains("syntax error at or near");
        assertThat(refusal).contains("SELCT");
        assertThat(refusal).isNotEqualTo("The dataset could not be read.");
    }

    @Test
    void aBinderErrorComesBackInDuckDbsOwnWords() {
        String refusal = refusalFor("Binder Error: Referenced column \"amont\" not found in FROM "
            + "clause!\nCandidate bindings: \"amount\"");

        // A misspelled column is the same kind of answer to the same kind of reader as a misspelled
        // keyword, and DuckDB's suggestion is the most useful part of it.
        assertThat(refusal).contains("Referenced column \"amont\" not found");
        assertThat(refusal).contains("Candidate bindings");
    }

    @Test
    void aCatalogErrorIsAStatementFailureRatherThanAMissingFile() {
        String refusal = refusalFor("Catalog Error: Table with name orders does not exist!\n"
            + "Did you mean \"temp.information_schema.tables\"?");

        // The ordering inside explain() is load-bearing and this is the case that proves it. The
        // message contains "does not exist", so a mapping that checked the dataset branch first
        // would answer "Nothing to read at etl-bucket/etl-demo/sales.csv" -- true of a path the
        // user never wrote, and false of the query they did.
        assertThat(refusal).contains("Table with name orders does not exist");
        assertThat(refusal).doesNotContain("Nothing to read at");
        assertThat(refusal).doesNotContain(PATH);
    }

    @Test
    void aStatementFailureNeverCarriesTheDatasetLocation() {
        String refusal = refusalFor("Parser Error: syntax error at or near \"FORM\"\n"
            + "LINE 1: SELECT * FORM read_csv_auto('" + URL + "')");

        // DuckDB quotes the failing statement back, and this class interpolated a location into
        // that statement. A user who typed FORM does not need to be shown the bucket to learn they
        // meant FROM, and a message on its way to a screen is a message on its way to a screenshot.
        assertThat(refusal).doesNotContain("s3://");
        assertThat(refusal).doesNotContain(BUCKET);
        assertThat(refusal).contains("<dataset>");
        assertThat(refusal).contains("FORM");
    }

    @Test
    void aVeryLongStatementFailureIsCutShort() {
        StringBuilder wide = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            wide.append("column_").append(i).append(", ");
        }
        String refusal = refusalFor("Parser Error: syntax error at or near \"SELCT\"\n"
            + "LINE 1: SELCT " + wide + " FROM read_csv_auto('" + URL + "')");

        // A parser error on a long statement quotes the whole statement back. Past a couple of
        // lines the useful part -- what is wrong and roughly where -- has already been said, and
        // the rest is a wall of text in a toast.
        assertThat(refusal).hasSizeLessThanOrEqualTo(403);
        assertThat(refusal).endsWith("...");
        assertThat(refusal).startsWith("Parser Error: syntax error at or near");
    }

    @Test
    void aTimeoutSaysHowLongItWasAllowed() {
        String refusal = refusalFor("Query timeout after 30 seconds");

        // The number matters: "it took too long" invites the user to try the same thing again,
        // and naming the ceiling tells them roughly how much smaller the question has to be.
        assertThat(refusal).contains("longer than " + TIMEOUT_SECONDS + " seconds");
        assertThat(refusal).contains("Narrow the dataset");
    }

    @Test
    void anInterruptIsATimeout() {
        // What DuckDB itself throws when the driver cancels a statement, and it says nothing about
        // time. The user's experience is identical, so the answer has to be.
        String refusal = refusalFor("INTERRUPT Error: Interrupted!");

        assertThat(refusal).contains("longer than " + TIMEOUT_SECONDS + " seconds");
    }

    @Test
    void aMissingFileNamesThePathAndClearsTheConnection() {
        for (String message : new String[] {
            "IO Error: No files found that match the pattern \"" + URL + "\"",
            "HTTP Error: HTTP GET error on '" + URL + "' (HTTP 404)",
            "IO Error: Unable to connect to URL \"" + URL + "\": 404 (NoSuchKey)" }) {

            String refusal = refusalFor(message);

            // The user chose a file, not a query, so the actionable half is which path was wrong.
            // Saying the connection worked is the other half: it stops the next twenty minutes
            // being spent on the credentials.
            assertThat(refusal).as(message)
                .isEqualTo("Nothing to read at " + BUCKET + "/" + PATH
                    + ". The connection worked, so check the path.");
        }
    }

    @Test
    void aForbiddenReadSeparatesReachingFromBeingAllowed() {
        for (String message : new String[] {
            "HTTP Error: HTTP GET error on '" + URL + "' (HTTP 403)",
            "IO Error: Unable to connect to URL \"" + URL + "\": 403 (Access Denied)",
            "IO Error: Forbidden" }) {

            String refusal = refusalFor(message);

            // A different person fixes this one -- it is a bucket policy, not a typo -- and the
            // sentence exists to send it to them rather than back to the user.
            assertThat(refusal).as(message)
                .isEqualTo("The storage connection reached " + BUCKET
                    + " but was not allowed to read it.");
        }
    }

    @Test
    void runningOutOfMemorySuggestsTheTwoThingsThatHelp() {
        String refusal = refusalFor("Out of Memory Error: failed to allocate data of size "
            + "256.0 MiB (512.0 MiB/512.0 MiB used)");

        // DuckDB runs inside this JVM, so this is the failure that is one step away from taking
        // the ETL dispatcher with it. Parquet is named because it is the change that most often
        // works: a columnar read of two columns does not materialise the other forty.
        assertThat(refusal).contains("more memory than analytics is allowed to use");
        assertThat(refusal).contains("Parquet instead of CSV");
    }

    @Test
    void aFileThatIsNotWhatItsNameSaysNamesTheFormatItWasReadAs() {
        for (String message : new String[] {
            "Invalid Input Error: Error when sniffing file \"" + URL + "\". It was not possible to "
                + "automatically detect the CSV Parsing dialect/types",
            "Conversion Error: CSV Error on Line: 42\nCould not convert string \"n/a\" to 'BIGINT'" }) {

            String refusal = refusalFor(message);

            // Format is detected from the extension, so this is the failure a renamed file
            // produces, and naming what it was read AS is what makes that recognisable.
            assertThat(refusal).as(message)
                .isEqualTo("This file could not be read as CSV. "
                    + "It may be malformed, or a different format.");
        }
    }

    @Test
    void anUnmappedFailureStaysGenericAndCarriesNothingOutWithIt() {
        String refusal = refusalFor("IO Error: Connection error for HTTP HEAD to "
            + "'https://minio.internal:9000/etl-bucket/etl-demo/sales.csv' - curl error 7");

        // The other half of gap 20, and the half that must NOT change when phase three arrives. An
        // unmapped engine message is exactly the string that carries an internal host name or a
        // path, so it is logged in full and reported as a sentence that says nothing.
        assertThat(refusal).isEqualTo("The dataset could not be read.");
        assertThat(refusal).doesNotContain("minio.internal");
        assertThat(refusal).doesNotContain("curl");
    }

    @Test
    void aFailureWithNoMessageAtAllIsStillASentence() {
        // A driver is allowed to throw one, and the alternative is an error toast containing the
        // word "null", which reads to a user as the screen being broken rather than the file.
        String refusal = refusalFor(null);

        assertThat(refusal).isEqualTo("The dataset could not be read.");
    }

    @Test
    void theMappingIsReachedFromEveryQueryPathRatherThanFromPreviewAlone() throws Exception {
        this.failure = new SQLException("HTTP Error: HTTP GET error on '" + URL + "' (HTTP 403)");
        String expected = "The storage connection reached " + BUCKET
            + " but was not allowed to read it.";

        AnalyticsException fromSchema = catchThrowableOfType(
            () -> this.service.schemaOf(this.dataset), AnalyticsException.class);
        AnalyticsException fromPreview = catchThrowableOfType(
            () -> this.service.preview(this.dataset, 0, 50, 10), AnalyticsException.class);
        AnalyticsException fromCount = catchThrowableOfType(
            () -> this.service.rowCount(this.dataset), AnalyticsException.class);

        // All three go through run(), which is the whole argument for run() existing. A path that
        // grew its own try/catch would answer the same failure differently on one screen.
        assertThat(fromSchema.getMessage()).isEqualTo(expected);
        assertThat(fromPreview.getMessage()).isEqualTo(expected);
        assertThat(fromCount.getMessage()).isEqualTo(expected);
        // And a failed read is not a read, so it must not be recorded as one.
        verify(this.statement, atLeastOnce()).executeQuery(anyString());
        assertThat(this.logged.list).noneMatch(event -> event.getLevel() == Level.INFO);
    }
}

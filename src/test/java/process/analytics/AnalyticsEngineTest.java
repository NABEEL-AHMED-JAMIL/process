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
import process.analytics.dto.DatasetPreviewDto;
import process.analytics.dto.DatasetProfileDto;
import process.analytics.dto.DatasetSchemaDto;
import process.analytics.dto.QueryResultDto;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.AnalyticsQueryRun;
import process.model.pojo.StorageConnection;
import process.security.TenantContext;
import process.util.EncryptionUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The engine seam, asserted against the engine that is actually behind it.
 *
 * 05 asks for DuckDB to be hidden behind an AnalyticsEngine so another engine could be introduced.
 * Two different claims follow from that, and this file keeps them apart on purpose:
 *
 * <ul>
 *   <li><b>The seam is load-bearing</b> -- what the module calls is the interface, not a class that
 *       happens to implement it. That half is checked with a mock engine, because the point is
 *       which type the caller depends on.</li>
 *   <li><b>The implementation behind it is real</b> -- every operation on the interface answers
 *       from a genuine, locked-down DuckDB, with the governor, the timeout, the statement gate and
 *       the row ceiling all the production ones. That half would be worthless mocked, for the same
 *       reason DuckDbLockdownTest is not mocked: a stub answers whatever the stub says.</li>
 * </ul>
 *
 * The lifecycle assertions live here rather than beside the entity because a state is only worth
 * having if something produces it. The three that used to be indistinguishable -- a statement the
 * gate refused, a query that reached DuckDB and failed, and a query the watchdog stopped -- were
 * all written to history as REFUSED, and the tests below are the three that now come apart.
 *
 * One substitution, the same one AnalyticsQueryExecutionTest makes and for the same reason: there
 * is no object store in a unit test, so a dataset's scan expression is swapped for a literal
 * relation of the same shape. The statement around it is the statement the engine built.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
// Lenient because a query that is refused before a session is opened uses none of the scripted
// session, and that refusal is the property such a test exists to prove.
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalyticsEngineTest {

    private static final long TENANT_ID = 1001L;
    private static final long USER_ID = 7L;
    private static final String BUCKET = "etl-bucket";
    private static final String SALES_PATH = "etl-demo/sales.csv";

    private static final int TIMEOUT_SECONDS = 20;
    private static final int MAX_ROWS = 1000;

    /** Three rows, two regions -- enough for a group, a profile and a page. */
    private static final String SALES_STAND_IN =
        "(VALUES (1,'north',10.50),(2,'south',21.00),(3,'north',42.00)) AS sales(id,region,amount)";

    /** A hundred billion rows behind the dataset name, so a query can be made not to come back. */
    private static final String ENDLESS_STAND_IN = "range(100000000000) AS huge(id)";

    @Mock private DuckDbSessionFactory sessions;

    /** The real thing: locked down by the real factory, exactly as a request would get it. */
    private Connection engineConnection;

    private DatasetRef sales;
    private RunningQueries running;
    private DuckDbAnalyticsEngine engine;

    private final List<String> executed = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> standIns = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        this.sales = datasetRef();
        this.standIns.put(this.sales.scanExpression(), SALES_STAND_IN);

        this.engineConnection = new DuckDbSessionFactory(limits(2, MAX_ROWS, TIMEOUT_SECONDS),
            new EncryptionUtil()).open(storageConnection());
        this.running = new RunningQueries();
        this.engine = new DuckDbAnalyticsEngine(this.sessions,
            limits(2, MAX_ROWS, TIMEOUT_SECONDS), this.running);

        Connection duck = session();
        when(this.sessions.open(any(StorageConnection.class))).thenReturn(duck);

        // The engine names the tenant on its read line, so the context has to exist for it to run.
        TenantContext.set(TENANT_ID, "TENANT_USER", USER_ID, "analyst");
    }

    @AfterEach
    void tearDown() throws Exception {
        this.engine.shutdown();
        this.engineConnection.close();
        // A ThreadLocal on a surefire thread the next test will be handed.
        TenantContext.clear();
    }

    // ---- the fixture ------------------------------------------------------------------------------

    private Connection session() throws SQLException {
        Connection duck = mock(Connection.class);
        when(duck.createStatement()).thenAnswer(call -> statement());
        when(duck.prepareStatement(anyString())).thenAnswer(call ->
            this.engineConnection.prepareStatement(call.getArgument(0, String.class)));
        return duck;
    }

    private Statement statement() throws SQLException {
        Statement real = this.engineConnection.createStatement();
        Statement recorded = mock(Statement.class);
        when(recorded.executeQuery(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0, String.class);
            this.executed.add(sql);
            return real.executeQuery(rewrite(sql));
        });
        when(recorded.execute(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0, String.class);
            this.executed.add(sql);
            return real.execute(rewrite(sql));
        });
        // Passed straight through, which is what makes the timeout assertion below a fact about
        // the engine rather than about a mock.
        doAnswer(call -> {
            real.cancel();
            return null;
        }).when(recorded).cancel();
        doAnswer(call -> {
            real.close();
            return null;
        }).when(recorded).close();
        return recorded;
    }

    private String rewrite(String sql) {
        String rewritten = sql;
        for (Map.Entry<String, String> standIn : this.standIns.entrySet()) {
            rewritten = rewritten.replace(standIn.getKey(), standIn.getValue());
        }
        return rewritten;
    }

    /**
     * DatasetRef's constructor is package-private and DatasetResolver is its only production
     * caller. This test sits in that package, so it can hand the engine the object the resolver
     * would have handed it.
     */
    private static DatasetRef datasetRef() {
        return new DatasetRef(storageConnection(), BUCKET, SALES_PATH, DatasetRef.Format.CSV);
    }

    private static StorageConnection storageConnection() {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(9L);
        connection.setTenantId(TENANT_ID);
        // MINIO with no endpoint, so nothing here can make a network call even by accident.
        connection.setProvider(StorageProvider.MINIO);
        connection.setAlias("store");
        connection.setBucketName(BUCKET);
        connection.setStatus(Status.Active);
        return connection;
    }

    private static AnalyticsLimits limits(int maxConcurrent, int maxRows, int timeoutSeconds) {
        AnalyticsLimits limits = new AnalyticsLimits();
        ReflectionTestUtils.setField(limits, "timeoutSeconds", timeoutSeconds);
        ReflectionTestUtils.setField(limits, "maxRows", maxRows);
        ReflectionTestUtils.setField(limits, "previewPageSize", 100);
        ReflectionTestUtils.setField(limits, "maxConcurrentQueries", maxConcurrent);
        ReflectionTestUtils.setField(limits, "memoryLimit", "256MB");
        ReflectionTestUtils.setField(limits, "threads", 1);
        return limits;
    }

    // ---- the seam ---------------------------------------------------------------------------------

    @Test
    void whatTheModuleCallsIsTheInterfaceAndNotTheImplementation() throws Exception {
        // The half of "hide DuckDB behind AnalyticsEngine" that a class named DuckDbAnalyticsEngine
        // does not by itself deliver. If the front door held a DuckDbAnalyticsEngine field, the
        // interface would be decoration and a second engine would still be a change to every
        // caller. Verified with a mock precisely because the subject is which TYPE is depended on.
        AnalyticsEngine other = mock(AnalyticsEngine.class);
        AnalyticsQueryService service = new AnalyticsQueryService(other, new RunningQueries());

        service.query(this.sales, null, "SELECT 1");
        service.schemaOf(this.sales);
        service.profileOf(this.sales);
        service.rowCount(this.sales);
        service.preview(this.sales, 0, 10);
        service.bounded("SELECT 1");

        verify(other).query(eq(this.sales), isNull(), eq("SELECT 1"), isNull());
        verify(other).schemaOf(this.sales);
        verify(other).profileOf(this.sales);
        verify(other).rowCount(this.sales);
        verify(other).preview(this.sales, 0, 10, null);
        verify(other).bounded("SELECT 1");
    }

    @Test
    void theDefaultArrangementIsADuckDbEngineBehindThatInterface() {
        // The control for the test above: the seam is real, and what is behind it today is DuckDB.
        // Said out loud because AnalyticsEngine's javadoc claims no more portability than exists,
        // and a test that only proved indirection would read as though it claimed more.
        AnalyticsQueryService service = new AnalyticsQueryService(this.sessions,
            limits(2, MAX_ROWS, TIMEOUT_SECONDS));

        assertThat(service.getEngine()).isInstanceOf(DuckDbAnalyticsEngine.class);
    }

    @Test
    void everyOperationOnTheSeamAnswersFromARealEngine() throws Exception {
        // Typed as the interface on purpose: this is the list a second engine would have to
        // provide, exercised through the only names it would have to provide them under.
        AnalyticsEngine seam = this.engine;

        DatasetSchemaDto schema = seam.schemaOf(this.sales);
        assertThat(schema.getColumns()).extracting("name").containsExactly("id", "region", "amount");

        assertThat(seam.rowCount(this.sales)).isEqualTo(3L);

        DatasetPreviewDto page = seam.preview(this.sales, 0, 2, null);
        assertThat(page.getRows()).hasSize(2);
        assertThat(page.getTotalRows()).isEqualTo(3L);

        DatasetProfileDto profile = seam.profileOf(this.sales);
        assertThat(profile.getTotalRows()).isEqualTo(3L);
        assertThat(profile.getColumns()).hasSize(3);

        QueryResultDto result = seam.query(this.sales, null,
            "SELECT region, sum(amount) AS total FROM dataset GROUP BY region ORDER BY total DESC");
        assertThat(result.getRows().get(0)).containsExactly("north", "52.50");
    }

    // ---- the lifecycle ----------------------------------------------------------------------------

    @Test
    void theSixStatesTheSpecNamesExistAndSoDoesTheSeventh() {
        // A vocabulary assertion, which is worth having exactly once: these names are shared with
        // a database column, a JSON payload and a screen, so a rename is a wire change and should
        // fail here first. The seventh is REFUSED -- see AnalyticsQueryRun for why losing it to
        // match a list of six would delete a security signal.
        assertThat(Arrays.asList(AnalyticsEngine.RunState.values()))
            .containsExactlyInAnyOrder(AnalyticsEngine.RunState.QUEUED,
                AnalyticsEngine.RunState.RUNNING, AnalyticsEngine.RunState.COMPLETED,
                AnalyticsEngine.RunState.FAILED, AnalyticsEngine.RunState.CANCELLED,
                AnalyticsEngine.RunState.TIMED_OUT, AnalyticsEngine.RunState.REFUSED);

        // COMPLETED is stored as SUCCESS, which is what the column already holds and what the
        // history screen's pill reads. Pinned so that "rename it to match the spec" has to argue
        // with a test rather than with a comment.
        assertThat(AnalyticsQueryRun.STATUS_SUCCESS).isEqualTo("SUCCESS");
        assertThat(AnalyticsQueryRun.STATUS_CANCELLED).isEqualTo("CANCELLED");
        assertThat(AnalyticsQueryRun.STATUS_TIMED_OUT).isEqualTo("TIMED_OUT");
        assertThat(AnalyticsQueryRun.STATUS_QUEUED).isEqualTo("QUEUED");
        assertThat(AnalyticsQueryRun.STATUS_RUNNING).isEqualTo("RUNNING");
    }

    @Test
    void aCompletedQueryComesBackWithAnIdAStateAndATiming() throws Exception {
        QueryResultDto result = this.engine.query(this.sales, null, "SELECT * FROM dataset");

        // The id is the load-bearing one. Without it the caller cannot name the run it is looking
        // at, which is why nothing could be cancelled: 09 asks for stable ids and this response
        // carried none.
        assertThat(result.getQueryId()).isNotBlank();
        assertThat(result.getStatus()).isEqualTo(AnalyticsEngine.RunState.COMPLETED.name());
        assertThat(result.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0L);
    }

    @Test
    void theCallerMayNameTheRunSoItHasSomethingToCancelWith() throws Exception {
        QueryResultDto result = this.engine.query(this.sales, null, "SELECT * FROM dataset",
            "editor-tab-3");

        // /query is synchronous, so a server-minted id arrives with the rows -- after there is
        // anything left to stop. A client that means to offer a stop button names the run itself.
        assertThat(result.getQueryId()).isEqualTo("editor-tab-3");
    }

    @Test
    void aRunIdThatIsNotAnIdIsRefusedBeforeItReachesAMapOrALogLine() {
        AnalyticsException refused = catchThrowableOfType(
            () -> this.engine.query(this.sales, null, "SELECT * FROM dataset",
                "'; DROP TABLE analytics_query_run --"),
            AnalyticsException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).contains("letters, digits");
        assertThat(this.executed).as("nothing should have reached the engine").isEmpty();
    }

    @Test
    void twoRunsCannotAnswerToTheSameName() throws Exception {
        RunningQueries.Handle first = this.running.open("shared");
        try {
            AnalyticsException clash = catchThrowableOfType(() -> this.running.open("shared"),
                AnalyticsException.class);

            // Silently minting a different id would leave the caller holding a name that cancels
            // the wrong query, which is worse than being told the name is taken.
            assertThat(clash).isNotNull();
            assertThat(clash.getMessage()).contains("already running");
        } finally {
            first.close();
        }
        // And the name is free again the moment the run is over.
        this.running.open("shared").close();
    }

    @Test
    void aStatementTheGateWillNotAdmitIsStillRefusedAndIsNotAnEngineFailure() {
        AnalyticsException refused = catchThrowableOfType(
            () -> this.engine.query(this.sales, null, "DROP TABLE dataset"),
            AnalyticsException.class);

        assertThat(refused).isNotNull();
        // A plain AnalyticsException, NOT a RunFailure -- which is how the controller knows to
        // record REFUSED. The distinction is the whole reason REFUSED survives: this statement
        // never reached DuckDB, and a history that called it FAILED would say it did.
        assertThat(refused).isNotInstanceOf(AnalyticsEngine.RunFailure.class);
    }

    @Test
    void aQueryThatReachedTheEngineAndFailedThereIsFailedRatherThanRefused() {
        AnalyticsEngine.RunFailure failed = catchThrowableOfType(
            () -> this.engine.query(this.sales, null, "SELECT missing_column FROM dataset"),
            AnalyticsEngine.RunFailure.class);

        assertThat(failed).isNotNull();
        // The audit's finding, closed: AnalyticsQueryRun says FAILED means "reached the engine and
        // failed there", and until now every such query was written to history as REFUSED, so the
        // one status that means "we would not run this" also meant "we ran it and it broke".
        assertThat(failed.getState()).isEqualTo(AnalyticsEngine.RunState.FAILED);
        assertThat(failed.getMessage()).containsIgnoringCase("binder error");
    }

    @Test
    void aQueryTheWatchdogStopsIsTimedOutRatherThanRefused() {
        DuckDbAnalyticsEngine impatient = new DuckDbAnalyticsEngine(this.sessions,
            limits(2, MAX_ROWS, 1), this.running);
        this.standIns.put(this.sales.scanExpression(), ENDLESS_STAND_IN);

        long startedAt = System.currentTimeMillis();
        AnalyticsEngine.RunFailure stopped = catchThrowableOfType(
            () -> impatient.query(this.sales, null, "SELECT count(*) FROM dataset WHERE id % 7 = 3"),
            AnalyticsEngine.RunFailure.class);
        long waited = System.currentTimeMillis() - startedAt;
        impatient.shutdown();

        assertThat(stopped).isNotNull();
        // A timed-out query used to be stored as REFUSED, so a query that ran for thirty seconds
        // and one the governor never started were the same row afterwards. The user was told the
        // truth ("took longer than ... and was stopped") and the history was not.
        assertThat(stopped.getState()).isEqualTo(AnalyticsEngine.RunState.TIMED_OUT);
        assertThat(stopped.getMessage()).contains("longer than 1 seconds and was stopped");
        assertThat(waited).isLessThan(30_000L);
    }

    // ---- the registry -----------------------------------------------------------------------------

    @Test
    void nothingIsLeftInTheRegistryAfterASuccessARefusalAFailureOrATimeout() throws Exception {
        // A map that keeps an entry per query it has ever seen is a memory leak with a tenant id,
        // a user id and a live JDBC handle in every entry -- and it is invisible from every other
        // observation this suite makes, which is why it gets an assertion of its own.
        this.engine.query(this.sales, null, "SELECT * FROM dataset");
        assertThat(this.running.size()).as("after a completed query").isZero();

        catchThrowableOfType(() -> this.engine.query(this.sales, null, "DROP TABLE dataset"),
            AnalyticsException.class);
        assertThat(this.running.size()).as("after a refusal").isZero();

        catchThrowableOfType(() -> this.engine.query(this.sales, null, "SELCT 1"),
            AnalyticsException.class);
        assertThat(this.running.size()).as("after an engine failure").isZero();

        DuckDbAnalyticsEngine impatient = new DuckDbAnalyticsEngine(this.sessions,
            limits(2, MAX_ROWS, 1), this.running);
        this.standIns.put(this.sales.scanExpression(), ENDLESS_STAND_IN);
        catchThrowableOfType(
            () -> impatient.query(this.sales, null, "SELECT count(*) FROM dataset WHERE id % 7 = 3"),
            AnalyticsException.class);
        impatient.shutdown();
        assertThat(this.running.size()).as("after a timeout").isZero();
    }

    @Test
    void aBuiltInReadIsRegisteredAndReleasedLikeAnythingElse() throws Exception {
        // schema, preview, count and profile hold a permit and a session exactly as a user's SQL
        // does, so "what is this application running right now" has one answer rather than one
        // answer plus the reads nobody counted.
        this.engine.schemaOf(this.sales);
        this.engine.profileOf(this.sales);
        this.engine.preview(this.sales, 0, 2, null);

        assertThat(this.running.size()).isZero();
    }
}

package process.analytics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import process.api.AnalyticsRestApi;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.AnalyticsQueryRun;
import process.model.pojo.StorageConnection;
import process.model.repository.StorageConnectionRepository;
import process.model.service.AnalyticsQueryLibraryService;
import process.security.TenantContext;
import process.util.EncryptionUtil;
import process.util.ProcessUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A user stopping a query, against a real DuckDB that really has to stop.
 *
 * Every assertion here interrupts a query that would otherwise not come back: a hundred billion
 * rows behind the dataset name, filtered with a modulo so DuckDB cannot answer it from metadata.
 * <b>Nothing in this file asserts that a method was called.</b> A cancellation test that verified
 * {@code verify(statement).cancel()} would pass against a driver whose cancel() does nothing --
 * which is exactly the trap the timeout fell into before it, because duckdb_jdbc 1.1.3 implements
 * setQueryTimeout as a no-op that logs and returns. What is asserted is that the query STOPPED,
 * with a state saying who stopped it, in a fraction of the time it would have taken to finish.
 *
 * Each test carries a timeout that is far longer than the cancel it is measuring. That is the
 * safety net rather than the mechanism: if a cancel is ever silently lost, the watchdog ends the
 * query with TIMED_OUT and the assertion fails on the state, instead of the suite hanging.
 *
 * <b>The tenancy tests are the ones to read first.</b> Against a governor ceiling of a few
 * concurrent permits, being able to stop other people's queries is a denial of service that costs
 * an attacker one HTTP call. Both of them have their positive control in the same test -- the
 * owner cancels the same run immediately afterwards -- because a registry that refused everybody
 * would satisfy a refusal assertion while making the feature useless.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalyticsCancellationTest {

    private static final long TENANT_ID = 1001L;
    private static final long OTHER_TENANT_ID = 2002L;
    private static final long USER_ID = 7L;
    private static final long OTHER_USER_ID = 8L;

    private static final String BUCKET = "etl-bucket";
    private static final String SALES_PATH = "etl-demo/sales.csv";

    /**
     * Long enough that a cancel measured in milliseconds is unmistakable, short enough that a lost
     * cancel ends the test rather than the working day.
     */
    private static final int TIMEOUT_SECONDS = 20;

    private static final int MAX_ROWS = 1000;

    /** A hundred billion rows, filtered so it cannot be answered from metadata. */
    private static final String ENDLESS_STAND_IN = "range(100000000000) AS huge(id)";

    private static final String ENDLESS_QUERY = "SELECT count(*) FROM dataset WHERE id % 7 = 3";

    @Mock private DuckDbSessionFactory sessions;
    @Mock private AnalyticsQueryLibraryService library;
    @Mock private StorageConnectionRepository connections;

    private Connection engineConnection;
    private DatasetRef sales;
    private RunningQueries running;
    private DuckDbAnalyticsEngine engine;
    private AnalyticsQueryService service;

    private final Map<String, String> standIns = new LinkedHashMap<>();
    private final List<String> executed = Collections.synchronizedList(new ArrayList<>());

    /** Counted down by the recorded statement, so a test knows the engine has the query in hand. */
    private final CountDownLatch executing = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        this.sales = datasetRef();
        this.standIns.put(this.sales.scanExpression(), ENDLESS_STAND_IN);

        this.engineConnection = new DuckDbSessionFactory(limits(TIMEOUT_SECONDS),
            new EncryptionUtil()).open(storageConnection());
        this.running = new RunningQueries();
        this.engine = new DuckDbAnalyticsEngine(this.sessions, limits(TIMEOUT_SECONDS), this.running);
        this.service = new AnalyticsQueryService(this.engine, this.running);

        Connection duck = session();
        when(this.sessions.open(any(StorageConnection.class))).thenReturn(duck);
        when(this.connections.findByAlias(anyString()))
            .thenReturn(Optional.of(storageConnection()));

        TenantContext.set(TENANT_ID, "TENANT_USER", USER_ID, "analyst");
    }

    @AfterEach
    void tearDown() throws Exception {
        this.engine.shutdown();
        this.engineConnection.close();
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

    /**
     * The recorded statement, with cancel() passed straight through to the real one.
     *
     * That pass-through is what makes this file a test of DuckDB rather than of Mockito: the
     * interruption every assertion below waits for is the driver's own.
     */
    private Statement statement() throws SQLException {
        Statement real = this.engineConnection.createStatement();
        Statement recorded = mock(Statement.class);
        when(recorded.executeQuery(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0, String.class);
            this.executed.add(sql);
            // Before the call, because the call is the thing that does not return.
            this.executing.countDown();
            return real.executeQuery(rewrite(sql));
        });
        when(recorded.execute(anyString())).thenAnswer(call -> {
            this.executed.add(call.getArgument(0, String.class));
            return real.execute(rewrite(call.getArgument(0, String.class)));
        });
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

    private static DatasetRef datasetRef() {
        return new DatasetRef(storageConnection(), BUCKET, SALES_PATH, DatasetRef.Format.CSV);
    }

    private static StorageConnection storageConnection() {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(9L);
        connection.setTenantId(TENANT_ID);
        connection.setProvider(StorageProvider.MINIO);
        connection.setAlias("store");
        connection.setBucketName(BUCKET);
        connection.setStatus(Status.Active);
        return connection;
    }

    private static AnalyticsLimits limits(int timeoutSeconds) {
        AnalyticsLimits limits = new AnalyticsLimits();
        ReflectionTestUtils.setField(limits, "timeoutSeconds", timeoutSeconds);
        ReflectionTestUtils.setField(limits, "maxRows", MAX_ROWS);
        ReflectionTestUtils.setField(limits, "previewPageSize", 100);
        ReflectionTestUtils.setField(limits, "maxConcurrentQueries", 2);
        ReflectionTestUtils.setField(limits, "memoryLimit", "256MB");
        ReflectionTestUtils.setField(limits, "threads", 1);
        return limits;
    }

    /** One run in flight on its own thread, so the test thread is free to stop it. */
    private final class Run {

        private final String id;
        private final Thread thread;
        private final AtomicReference<Throwable> outcome = new AtomicReference<>();
        private final long startedAt = System.currentTimeMillis();
        private long finishedAt;

        private Run(String id, Runnable body) {
            this.id = id;
            this.thread = new Thread(() -> {
                // The handle records the tenant and user from THIS thread's context, which is what
                // the cancel is checked against. Set here for that reason, not for tidiness.
                TenantContext.set(TENANT_ID, "TENANT_USER", USER_ID, "analyst");
                try {
                    body.run();
                } catch (Throwable thrown) {
                    this.outcome.set(thrown);
                } finally {
                    this.finishedAt = System.currentTimeMillis();
                    TenantContext.clear();
                }
            }, "analytics-cancellation-" + id);
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private void awaitStart() throws InterruptedException {
            assertThat(AnalyticsCancellationTest.this.executing.await(15, TimeUnit.SECONDS))
                .as("the query should have reached the engine").isTrue();
            // The latch is counted down on the way INTO executeQuery, so give DuckDB a moment to
            // actually be running before interrupting it.
            Thread.sleep(300L);
        }

        private Throwable awaitEnd() throws InterruptedException {
            this.thread.join(TIMEOUT_SECONDS * 1000L + 10_000L);
            assertThat(this.thread.isAlive()).as("the query should have stopped").isFalse();
            return this.outcome.get();
        }

        private long elapsedMs() {
            return this.finishedAt - this.startedAt;
        }
    }

    private Run endlessQuery(String id) {
        return new Run(id, () -> {
            try {
                AnalyticsCancellationTest.this.engine.query(
                    AnalyticsCancellationTest.this.sales, null, ENDLESS_QUERY, id);
            } catch (AnalyticsException ex) {
                throw new IllegalStateException(ex);
            }
        });
    }

    /** Runs a cancel under somebody else's identity, on a thread of its own. */
    private RunningQueries.Outcome cancelAs(long tenantId, long appUserId, String runId)
        throws InterruptedException {

        AtomicReference<RunningQueries.Outcome> answer = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            TenantContext.set(tenantId, "TENANT_USER", appUserId, "someone-else");
            try {
                answer.set(AnalyticsCancellationTest.this.service.cancel(runId));
            } finally {
                TenantContext.clear();
            }
        }, "analytics-cancel-caller");
        caller.start();
        caller.join(10_000L);
        return answer.get();
    }

    // ---- stopping a query that is really running --------------------------------------------------

    @Test
    void aRunningQueryStopsWhenItsOwnerCancelsIt() throws Exception {
        Run run = endlessQuery("run-1");
        run.awaitStart();

        RunningQueries.Outcome outcome = this.service.cancel("run-1");
        Throwable thrown = run.awaitEnd();

        assertThat(outcome).isEqualTo(RunningQueries.Outcome.CANCELLED);
        assertThat(thrown).hasCauseInstanceOf(AnalyticsEngine.RunFailure.class);
        AnalyticsEngine.RunFailure failure = (AnalyticsEngine.RunFailure) thrown.getCause();
        // CANCELLED and not TIMED_OUT, even though DuckDB reports both as
        // "INTERRUPT Error: Interrupted!". The registry knows who called cancel(); the engine's
        // message never will, which is why this cannot be decided by matching the string.
        assertThat(failure.getState()).isEqualTo(AnalyticsEngine.RunState.CANCELLED);
        assertThat(failure.getMessage()).isEqualTo("That query was cancelled.");
        // The measurement that makes this a test of the engine: the watchdog would not have fired
        // for twenty seconds, and this query would not have finished at all.
        assertThat(run.elapsedMs())
            .as("the query should have stopped on the cancel, not on the timeout")
            .isLessThan(10_000L);
    }

    @Test
    void aQueryStoppedWhileItWaitedForAPermitIsCancelledAndNotRefused() throws Exception {
        // The QUEUED half of the lifecycle, which is all of two seconds long here because this
        // module refuses rather than queues. Short is not the same as absent: a run can be stopped
        // in it, and saying "too many queries are running" to somebody who pressed stop would
        // blame the governor for a decision the user made.
        Semaphore slots = (Semaphore) ReflectionTestUtils.getField(this.engine, "slots");
        slots.drainPermits();

        Run run = endlessQuery("queued-1");
        Thread.sleep(200L);
        RunningQueries.Outcome outcome = this.service.cancel("queued-1");
        Throwable thrown = run.awaitEnd();

        assertThat(outcome).isEqualTo(RunningQueries.Outcome.CANCELLED);
        AnalyticsEngine.RunFailure failure = (AnalyticsEngine.RunFailure) thrown.getCause();
        assertThat(failure.getState()).isEqualTo(AnalyticsEngine.RunState.CANCELLED);
        assertThat(this.executed).as("nothing should have reached the engine").isEmpty();
    }

    // ---- the tenant boundary ----------------------------------------------------------------------

    @Test
    void oneTenantCannotStopAnotherTenantsQuery() throws Exception {
        Run run = endlessQuery("run-2");
        run.awaitStart();

        RunningQueries.Outcome stranger = cancelAs(OTHER_TENANT_ID, USER_ID, "run-2");

        assertThat(stranger).isEqualTo(RunningQueries.Outcome.NOT_RUNNING);
        // Still going, which is the half that matters: an answer of "not running" that had
        // nevertheless stopped the query would be the denial of service with a politer message.
        assertThat(this.running.size()).isEqualTo(1);
        assertThat(run.thread.isAlive()).isTrue();

        // The positive control, on the same run and one line later. A registry that refused
        // everybody would have passed everything above while making the feature useless.
        assertThat(this.service.cancel("run-2")).isEqualTo(RunningQueries.Outcome.CANCELLED);
        assertThat(run.awaitEnd()).isNotNull();
    }

    @Test
    void anotherUserInTheSameTenantCannotStopItEither() throws Exception {
        Run run = endlessQuery("run-3");
        run.awaitStart();

        RunningQueries.Outcome colleague = cancelAs(TENANT_ID, OTHER_USER_ID, "run-3");

        // Scoped to the person, not only to the workspace. A tenant administrator stopping a
        // colleague's runaway query is a reasonable thing to want and is deliberately not this:
        // it should be a named endpoint with an audit line, not a quiet widening of this one.
        assertThat(colleague).isEqualTo(RunningQueries.Outcome.NOT_RUNNING);
        assertThat(run.thread.isAlive()).isTrue();

        assertThat(this.service.cancel("run-3")).isEqualTo(RunningQueries.Outcome.CANCELLED);
        run.awaitEnd();
    }

    // ---- the registry -----------------------------------------------------------------------------

    @Test
    void aCancelledRunLeavesNothingBehindInTheRegistry() throws Exception {
        Run run = endlessQuery("run-4");
        run.awaitStart();
        assertThat(this.running.size()).isEqualTo(1);

        this.service.cancel("run-4");
        run.awaitEnd();

        // Cancellation is the fifth way out of a run and the easiest one to forget: it leaves
        // through a throw, from a thread other than the one that asked for it.
        assertThat(this.running.size()).isZero();
    }

    @Test
    void stoppingARunThatHasNotReachedItsStatementStopsItFromStarting() throws Exception {
        // The window between taking a permit and having a statement -- the one moment a stop
        // request has nothing to act on. Asserted directly on the handle because it is a race
        // that cannot be scheduled from outside, and the alternative to a test is a comment.
        RunningQueries.Handle handle = this.running.open("run-5");
        try {
            assertThat(handle.stop(RunningQueries.Stopper.USER)).isTrue();
            assertThat(handle.running(mock(Statement.class)))
                .as("a run cancelled before it started must not then start")
                .isFalse();
        } finally {
            handle.close();
        }
    }

    // ---- cancelling nothing -----------------------------------------------------------------------

    @Test
    void cancellingAQueryThatHasAlreadyFinishedIsNotAnError() throws Exception {
        assertThat(this.service.cancel("never-existed"))
            .isEqualTo(RunningQueries.Outcome.NOT_RUNNING);

        // And through the endpoint, because this is the race a user hits by pressing stop as the
        // last row lands, and an error toast for it would be the application blaming them for its
        // own timing.
        AnalyticsRestApi api = new AnalyticsRestApi(new DatasetResolver(this.connections),
            this.service, this.library, null, new AnalyticsLimits());
        ResponseEntity<?> response = api.cancelQuery("never-existed");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseDto body = (ResponseDto) response.getBody();
        assertThat(body.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(body.getMessage()).isEqualTo("That query is no longer running.");
    }

    @Test
    void aMalformedQueryIdIsAnsweredRatherThanLookedUp() {
        assertThat(this.service.cancel("'; DROP TABLE analytics_query_run --"))
            .isEqualTo(RunningQueries.Outcome.NOT_RUNNING);
        assertThat(this.service.cancel(null)).isEqualTo(RunningQueries.Outcome.NOT_RUNNING);
    }

    // ---- what history is told ---------------------------------------------------------------------

    @Test
    void theHistoryRowSaysCancelledWithTheDurationUpToTheStop() throws Exception {
        AnalyticsRestApi api = new AnalyticsRestApi(new DatasetResolver(this.connections),
            this.service, this.library, null, new AnalyticsLimits());

        Run run = new Run("api-1", () -> api.query(request("api-1")));
        run.awaitStart();
        this.service.cancel("api-1");
        run.awaitEnd();

        ArgumentCaptor<AnalyticsQueryRun> recorded = ArgumentCaptor.forClass(AnalyticsQueryRun.class);
        verify(this.library).recordRun(recorded.capture());
        AnalyticsQueryRun row = recorded.getValue();

        // Before this existed the row said REFUSED -- the status that means "the gate or the
        // governor would not run this" -- for a query that ran, was watched, and was stopped by
        // the person who started it.
        assertThat(row.getRunStatus()).isEqualTo(AnalyticsQueryRun.STATUS_CANCELLED);
        assertThat(row.getQueryText()).isEqualTo(ENDLESS_QUERY);
        assertThat(row.getErrorMessage()).isEqualTo("That query was cancelled.");
        // The duration is the time up to the cancel, not the ceiling the query never reached.
        assertThat(row.getDurationMs()).isNotNull().isPositive()
            .isLessThan(TIMEOUT_SECONDS * 1000L);
    }

    private Map<String, String> request(String queryId) {
        Map<String, String> body = new HashMap<>();
        body.put("connection", "store");
        body.put("path", SALES_PATH);
        body.put("sql", ENDLESS_QUERY);
        body.put("queryId", queryId);
        return body;
    }
}

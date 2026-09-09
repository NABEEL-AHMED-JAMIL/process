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
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.analytics.dto.QueryResultDto;
import process.security.TenantContext;
import process.util.EncryptionUtil;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ADVERSARIAL PROBE -- written by a review pass, not by the implementing agent.
 * Delete this file once the findings it pins have been dealt with.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdversarialWave1ProbeTest {

    private static final long TENANT_ID = 1001L;
    private static final long OTHER_TENANT_ID = 2002L;
    private static final long USER_ID = 7L;
    private static final long OTHER_USER_ID = 8L;
    private static final String BUCKET = "etl-bucket";
    private static final String SALES_PATH = "etl-demo/sales.csv";
    private static final String ENDLESS_STAND_IN = "range(100000000000) AS huge(id)";
    private static final String ENDLESS_QUERY = "SELECT count(*) FROM dataset WHERE id % 7 = 3";

    @Mock private DuckDbSessionFactory sessions;

    private Connection engineConnection;
    private DatasetRef sales;
    private RunningQueries running;
    private DuckDbAnalyticsEngine engine;
    private AnalyticsQueryService service;

    private final Map<String, String> standIns = new LinkedHashMap<>();
    private final List<String> executed = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch executing = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        this.sales = datasetRef();
        this.standIns.put(this.sales.scanExpression(), ENDLESS_STAND_IN);
        this.engineConnection = new DuckDbSessionFactory(limits(20), new EncryptionUtil())
            .open(storageConnection());
        this.running = new RunningQueries();
        this.engine = new DuckDbAnalyticsEngine(this.sessions, limits(20), this.running);
        this.service = new AnalyticsQueryService(this.engine, this.running);
        when(this.sessions.open(any(StorageConnection.class))).thenAnswer(c -> session());
        TenantContext.set(TENANT_ID, "TENANT_USER", USER_ID, "analyst");
    }

    @AfterEach
    void tearDown() throws Exception {
        this.engine.shutdown();
        this.engineConnection.close();
        TenantContext.clear();
    }

    // ================================================================= FINDING 1: id-collision oracle

    /**
     * One tenant must not learn that another is running a query under a given id.
     *
     * This began as a probe asserting the DEFECT: the registry was one global map keyed on the
     * client-supplied id, so "A query with that id is already running." answered "is this id live
     * in somebody else's workspace?" to anyone who could call /query. cancel() closed that oracle
     * carefully and open() reopened it one method above. Inverted once the key became
     * (tenant, user, id); a stranger now gets the same answer for a busy id as for an idle one.
     */
    @Test
    void aCollisionInAnotherWorkspaceIsInvisibleRatherThanAnOracle() throws Exception {
        RunningQueries.Handle mine = this.running.open("shared-id");
        try {
            AtomicReference<String> strangerSaw = new AtomicReference<>();
            AtomicReference<String> strangerSawUnusedId = new AtomicReference<>();
            Thread stranger = new Thread(() -> {
                TenantContext.set(OTHER_TENANT_ID, "TENANT_USER", OTHER_USER_ID, "stranger");
                try {
                    try {
                        this.running.open("shared-id").close();
                        strangerSaw.set("<accepted>");
                    } catch (AnalyticsException ex) {
                        strangerSaw.set(ex.getMessage());
                    }
                    try {
                        this.running.open("id-nobody-is-using").close();
                        strangerSawUnusedId.set("<accepted>");
                    } catch (AnalyticsException ex) {
                        strangerSawUnusedId.set(ex.getMessage());
                    }
                } finally {
                    TenantContext.clear();
                }
            });
            stranger.start();
            stranger.join(5000L);

            System.out.println("PROBE1 other tenant asking for an id THIS tenant is running -> "
                + strangerSaw.get());
            System.out.println("PROBE1 other tenant asking for an unused id                 -> "
                + strangerSawUnusedId.get());

            // Identical answers, so nothing about this tenant's activity is observable. Both are
            // "<accepted>": the stranger's own namespace is empty either way, which is the point.
            assertThat(strangerSaw.get()).isEqualTo(strangerSawUnusedId.get());
            assertThat(strangerSaw.get()).isEqualTo("<accepted>");
        } finally {
            mine.close();
        }
    }

    /**
     * A stranger must not be able to take an id its rightful owner wants to use.
     *
     * The other half of the same defect, and the worse half: with one namespace a stranger holding
     * "victim-run-1" made the owner's own run fail, and the owner could not clear it, because
     * cancel() correctly refuses a handle that is not theirs. Denial of service on a namespace,
     * delivered by the one part of the class that was being scrupulous.
     */
    @Test
    void aStrangerHoldingTheSameIdDoesNotBlockTheOwner() throws Exception {
        AtomicReference<RunningQueries.Handle> squatted = new AtomicReference<>();
        Thread stranger = new Thread(() -> {
            TenantContext.set(OTHER_TENANT_ID, "TENANT_USER", OTHER_USER_ID, "stranger");
            try {
                squatted.set(this.running.open("victim-run-1"));
            } catch (AnalyticsException ex) {
                throw new IllegalStateException(ex);
            } finally {
                TenantContext.clear();
            }
        });
        stranger.start();
        stranger.join(5000L);

        // Victim, on the main thread, is tenant 1001 / user 7, and runs its own id unimpeded.
        QueryResultDto result = this.engine.query(this.sales, null, "SELECT 1", "victim-run-1");
        assertThat(result.getRowCount()).isEqualTo(1);

        // The stranger's run is still theirs and still untouched -- the two coexist under one
        // spelling because they are two different keys.
        assertThat(this.running.size()).isEqualTo(1);
        squatted.get().close();
        assertThat(this.running.size()).isZero();
    }

    // ============================================================== FINDING 2: does the registry drain

    @Test
    void probe_registryDrainsOnCompletionFailureRefusalAndTimeout() throws Exception {
        // 1. completion
        this.engine.query(this.sales, null, "SELECT 1 AS one", "ok-1");
        assertThat(this.running.size()).as("after a completed query").isZero();

        // 2. engine failure (a column that does not exist)
        try {
            this.engine.query(this.sales, null, "SELECT no_such_column FROM dataset", "bad-1");
        } catch (AnalyticsException expected) {
            System.out.println("PROBE3 engine failure -> " + expected.getClass().getSimpleName()
                + " state=" + (expected instanceof AnalyticsEngine.RunFailure
                    ? ((AnalyticsEngine.RunFailure) expected).getState() : "n/a"));
        }
        assertThat(this.running.size()).as("after an engine failure").isZero();

        // 3. refusal by the statement gate (a write)
        try {
            this.engine.query(this.sales, null, "DELETE FROM dataset", "refused-1");
        } catch (AnalyticsException expected) {
            System.out.println("PROBE3 gate refusal -> " + expected.getClass().getSimpleName()
                + " state=" + (expected instanceof AnalyticsEngine.RunFailure
                    ? ((AnalyticsEngine.RunFailure) expected).getState() : "n/a")
                + " msg=" + expected.getMessage());
        }
        assertThat(this.running.size()).as("after a gate refusal").isZero();

        // 4. built-in read (schemaOf) -- registered with a minted id
        this.engine.schemaOf(this.sales);
        assertThat(this.running.size()).as("after a built-in read").isZero();

        // 5. timeout: a one-second watchdog against an endless query
        DuckDbAnalyticsEngine quick = new DuckDbAnalyticsEngine(this.sessions, limits(1), this.running);
        try {
            quick.query(this.sales, null, ENDLESS_QUERY, "timeout-1");
        } catch (AnalyticsException expected) {
            System.out.println("PROBE3 timeout -> " + expected.getClass().getSimpleName()
                + " state=" + (expected instanceof AnalyticsEngine.RunFailure
                    ? ((AnalyticsEngine.RunFailure) expected).getState() : "n/a")
                + " msg=" + expected.getMessage());
        } finally {
            quick.shutdown();
        }
        assertThat(this.running.size()).as("after a timeout").isZero();
    }

    // ====================================================== FINDING 3: does the watchdog queue drain

    @Test
    void probe_cancelledWatchdogTasksAccumulateInTheScheduler() throws Exception {
        // 120 seconds is the shipped default. Every fast query schedules a task 120s out and
        // cancels it; a ScheduledThreadPoolExecutor does not remove cancelled tasks from its
        // queue unless removeOnCancelPolicy is set.
        DuckDbAnalyticsEngine slowTimeout =
            new DuckDbAnalyticsEngine(this.sessions, limits(120), this.running);
        try {
            for (int i = 0; i < 50; i++) {
                slowTimeout.query(this.sales, null, "SELECT 1 AS one", "drain-" + i);
            }
            Object wrapper = ReflectionTestUtils.getField(slowTimeout, "watchdogs");
            System.out.println("PROBE4 watchdogs is a " + wrapper.getClass().getName()
                + " (setRemoveOnCancelPolicy is not reachable on this type)");
            // Weak-reference proof: a Handle from a query that COMPLETED and closed should be
            // collectable. If the cancelled watchdog task is still sitting in the delay queue it
            // pins the Handle, and through it the closed java.sql.Statement.
            java.lang.ref.WeakReference<Object> pinned;
            RunningQueries.Handle handle = this.running.open("weak-1");
            pinned = new java.lang.ref.WeakReference<>(handle);
            java.lang.reflect.Method inSession = DuckDbAnalyticsEngine.class.getDeclaredMethod(
                "inSession", DatasetRef.class, RunningQueries.Handle.class,
                Class.forName("process.analytics.DuckDbAnalyticsEngine$SessionWork"));
            inSession.setAccessible(true);
            Object work = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] { Class.forName("process.analytics.DuckDbAnalyticsEngine$SessionWork") },
                (p, m, a) -> "done");
            inSession.invoke(slowTimeout, this.sales, handle, work);
            handle = null;
            for (int i = 0; i < 5; i++) {
                System.gc();
                Thread.sleep(50L);
            }
            System.out.println("PROBE4 handle of a COMPLETED run still reachable after gc = "
                + (pinned.get() != null));
            assertThat(this.running.size()).isZero();
        } finally {
            slowTimeout.shutdown();
        }
    }

    // ==================================================== FINDING 4: what does a 100k row result cost

    @Test
    void probe_theShippedMaxRowsCeilingAsAPayload() throws Exception {
        AnalyticsLimits shipped = new AnalyticsLimits();
        ReflectionTestUtils.setField(shipped, "timeoutSeconds", 120);
        ReflectionTestUtils.setField(shipped, "maxRows", 100000);
        ReflectionTestUtils.setField(shipped, "previewPageSize", 100);
        ReflectionTestUtils.setField(shipped, "maxConcurrentQueries", 4);
        ReflectionTestUtils.setField(shipped, "memoryLimit", "512MB");
        ReflectionTestUtils.setField(shipped, "threads", 1);

        this.standIns.put(this.sales.scanExpression(),
            "(SELECT i AS id, 'value-' || i AS a, 'value-' || i AS b, 'value-' || i AS c, "
            + "'value-' || i AS d, 'value-' || i AS e, 'value-' || i AS f, 'value-' || i AS g, "
            + "'value-' || i AS h, 'value-' || i AS j FROM range(200000) t(i))");
        DuckDbAnalyticsEngine big = new DuckDbAnalyticsEngine(this.sessions, shipped, this.running);
        try {
            long before = usedHeap();
            process.analytics.dto.QueryResultDto result =
                big.query(this.sales, null, "SELECT * FROM dataset", "big-1");
            long after = usedHeap();
            com.google.gson.Gson gson = new com.google.gson.Gson();
            String json = gson.toJson(result);
            System.out.println("PROBE5 rows=" + result.getRowCount()
                + " truncated=" + result.isTruncated()
                + " cols=" + result.getColumns().size()
                + " jsonBytes=" + json.getBytes("UTF-8").length
                + " heapDeltaMB=" + ((after - before) / (1024 * 1024)));
            assertThat(result.getRowCount()).isEqualTo(100000);
        } finally {
            big.shutdown();
        }
    }

    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        System.gc();
        return rt.totalMemory() - rt.freeMemory();
    }

    // ---- fixture ---------------------------------------------------------------------------------

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
        ReflectionTestUtils.setField(limits, "maxRows", 1000);
        ReflectionTestUtils.setField(limits, "previewPageSize", 100);
        ReflectionTestUtils.setField(limits, "maxConcurrentQueries", 2);
        ReflectionTestUtils.setField(limits, "memoryLimit", "256MB");
        ReflectionTestUtils.setField(limits, "threads", 1);
        return limits;
    }
}

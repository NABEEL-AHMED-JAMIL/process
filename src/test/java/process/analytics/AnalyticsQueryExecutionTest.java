package process.analytics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import process.analytics.dto.QueryResultDto;
import process.api.AnalyticsRestApi;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.model.repository.StorageConnectionRepository;
import process.security.TenantContext;
import process.util.EncryptionUtil;
import process.util.ProcessUtil;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A query somebody wrote, run end to end against a real, locked-down DuckDB.
 *
 * The engine here is the one the feature hands out: DuckDbSessionFactory's own session, with
 * httpfs loaded, the local filesystem removed and the configuration locked, in that order. The
 * governor, the timeout, the gate, bounded() and the error mapping are all the production ones.
 * One thing is substituted and only one -- the LOCATION. There is no object store in a unit test,
 * so each dataset's scan expression is swapped for a literal relation of the same shape; the
 * statement around it, including the view definition that binds it to the name a user types, is
 * the statement the service built.
 *
 * The test that matters most is the one that does NOT substitute anything:
 * aQueryCannotReadALocalFileEvenThoughTheUserWroteTheSql. Phase one's whole argument for shipping
 * no SQL was that the sandbox had to be proven first, and this is the assertion that says the
 * argument held once the SQL became somebody else's. Note which layer refuses it -- the gate
 * admits that statement, because reading a file IS a read; what stops it is disabled_filesystems.
 * Two layers, each doing the job the other cannot, is the whole design.
 *
 * The tenancy half of gap 28 is asserted through the controller, because the controller is what
 * resolves the second dataset, and "each one goes through DatasetResolver on its own" is a claim
 * about that code and not about the engine.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
// Lenient because the fixture scripts a whole session -- statements, prepared statements, close --
// and a test that is refused before the engine is reached uses none of it. That refusal is the
// property such a test exists to prove, so an unused stub there is not a defect.
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalyticsQueryExecutionTest {

    private static final long TENANT_ID = 1001L;
    private static final long OTHER_TENANT_ID = 2002L;
    private static final String BUCKET = "etl-bucket";
    private static final String SALES_PATH = "etl-demo/sales.csv";
    private static final String REGIONS_PATH = "etl-demo/regions.csv";

    private static final int TIMEOUT_SECONDS = 20;
    private static final int MAX_ROWS = 1000;

    /** Three rows, two regions, one repeated -- enough for a join, a group and an ordering. */
    private static final String SALES_STAND_IN =
        "(VALUES (1,'north',10.50),(2,'south',21.00),(3,'north',42.00)) AS sales(id,region,amount)";

    private static final String REGIONS_STAND_IN =
        "(VALUES ('north','Northern'),('south','Southern')) AS regions(region,label)";

    @Mock private DuckDbSessionFactory sessions;

    /** The real thing: locked down by the real factory, exactly as a request would get it. */
    private Connection engine;

    private DatasetRef sales;
    private DatasetRef regions;
    private AnalyticsQueryService service;

    /** Statements that reached the engine through the session's Statement, in order. */
    private final List<String> executed = Collections.synchronizedList(new ArrayList<>());

    /** Scan expression to the relation that stands in for it. The only substitution in this file. */
    private final Map<String, String> standIns = new LinkedHashMap<>();

    private ch.qos.logback.classic.Logger serviceLogger;
    private ListAppender<ILoggingEvent> logged;
    private Level originalLevel;

    @BeforeEach
    void setUp() throws Exception {
        this.sales = datasetRef(TENANT_ID, SALES_PATH);
        this.regions = datasetRef(TENANT_ID, REGIONS_PATH);
        this.standIns.put(this.sales.scanExpression(), SALES_STAND_IN);
        this.standIns.put(this.regions.scanExpression(), REGIONS_STAND_IN);

        this.engine = new DuckDbSessionFactory(limits(2, MAX_ROWS, TIMEOUT_SECONDS),
            new EncryptionUtil()).open(storageConnection(TENANT_ID));
        this.service = new AnalyticsQueryService(this.sessions,
            limits(2, MAX_ROWS, TIMEOUT_SECONDS));
        // Built before the stub rather than inside it: session() does its own stubbing, and
        // Mockito treats a mock built inside an unfinished when(...) as the unfinished one.
        Connection duck = session();
        when(this.sessions.open(any(StorageConnection.class))).thenReturn(duck);

        // run() names the tenant on its read line, so the context has to exist for it to run.
        TenantContext.set(TENANT_ID, "TENANT_USER", 7L, "analyst");

        // The engine's logger, not the service's: the read line, the statement-rejection
        // warning and the unmapped-failure error all moved to DuckDbAnalyticsEngine when the
        // engine seam was introduced, and an appender on the old name would record nothing while
        // every assertion below still read as though it were watching.
        this.serviceLogger = (ch.qos.logback.classic.Logger)
            LoggerFactory.getLogger(DuckDbAnalyticsEngine.class);
        this.originalLevel = this.serviceLogger.getLevel();
        this.serviceLogger.setLevel(Level.INFO);
        this.logged = new ListAppender<>();
        this.logged.start();
        this.serviceLogger.addAppender(this.logged);
    }

    @AfterEach
    void tearDown() throws Exception {
        this.serviceLogger.detachAppender(this.logged);
        this.serviceLogger.setLevel(this.originalLevel);
        this.engine.close();
        // A ThreadLocal on a surefire thread the next test will be handed.
        TenantContext.clear();
    }

    // ---- the fixture ------------------------------------------------------------------------------

    /**
     * A session that is the real locked-down connection with one string swapped on the way in.
     *
     * Mocked at the Connection rather than deeper because the swap has to happen between the
     * service writing a statement and the engine reading it, and there is nowhere else to stand.
     * Everything else is passed straight through, including cancel(), which is what makes the
     * timeout assertion below a fact about the engine rather than about a mock.
     */
    private Connection session() throws SQLException {
        Connection duck = mock(Connection.class);
        when(duck.createStatement()).thenAnswer(call -> statement());
        when(duck.prepareStatement(anyString())).thenAnswer(call ->
            this.engine.prepareStatement(call.getArgument(0, String.class)));
        return duck;
    }

    private Statement statement() throws SQLException {
        Statement real = this.engine.createStatement();
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
     * caller. This test sits in that package, so it can hand the service the object the resolver
     * would have handed it.
     */
    private static DatasetRef datasetRef(long tenantId, String path) {
        return new DatasetRef(storageConnection(tenantId), BUCKET, path, DatasetRef.Format.CSV);
    }

    private static StorageConnection storageConnection(long tenantId) {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(9L);
        connection.setTenantId(tenantId);
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

    private String theQueryThatRan() {
        assertThat(this.executed).as("something should have reached the engine").isNotEmpty();
        return this.executed.get(this.executed.size() - 1);
    }

    // ---- the query path ----------------------------------------------------------------------------

    @Test
    void aQueryAPersonWroteRunsAndComesBackAsColumnsAndRows() throws Exception {
        QueryResultDto result = this.service.query(this.sales, null,
            "SELECT region, sum(amount) AS total FROM dataset GROUP BY region ORDER BY total DESC");

        assertThat(result.getColumns()).containsExactly("region", "total");
        assertThat(result.getRows()).hasSize(2);
        assertThat(result.getRows().get(0)).containsExactly("north", "52.50");
        assertThat(result.getRows().get(1)).containsExactly("south", "21.00");
        assertThat(result.getRowCount()).isEqualTo(2);
        assertThat(result.isTruncated()).isFalse();
    }

    @Test
    void theUserNamesTheDatasetAndTheServerNamesTheLocation() throws Exception {
        this.service.query(this.sales, null, "SELECT * FROM dataset");

        // The property gap 26 turns on. The view definition is the only statement carrying an
        // s3:// URL, and the service wrote it from a DatasetRef the resolver produced; the
        // statement the user wrote names nothing but "dataset".
        assertThat(this.executed.get(0))
            .isEqualTo("CREATE OR REPLACE TEMP VIEW dataset AS SELECT * FROM "
                + this.sales.scanExpression());
        assertThat(theQueryThatRan()).contains("SELECT * FROM dataset");
        assertThat(theQueryThatRan()).doesNotContain("s3://");
        assertThat(theQueryThatRan()).doesNotContain(BUCKET);
    }

    @Test
    void aQueryWithNoLimitOfItsOwnIsBoundedAndTheTruncationIsVisible() throws Exception {
        // Five hundred rows against a ceiling of ten. Without the bound this is what a browser
        // would be asked to hold; without the flag, the user would be shown ten rows and told
        // nothing, which is a wrong answer rather than a short one.
        this.standIns.put(this.sales.scanExpression(), "range(1, 501) AS wide(id)");
        AnalyticsQueryService tight = new AnalyticsQueryService(this.sessions,
            limits(2, 10, TIMEOUT_SECONDS));

        QueryResultDto result = tight.query(this.sales, null, "SELECT id FROM dataset ORDER BY id");

        assertThat(result.getRowCount()).isEqualTo(10);
        assertThat(result.getRows()).hasSize(10);
        assertThat(result.isTruncated()).isTrue();
        // Through bounded(), not through a second row ceiling invented for this path.
        assertThat(theQueryThatRan())
            .isEqualTo("SELECT * FROM (SELECT id FROM dataset ORDER BY id) "
                + "AS bounded_query LIMIT 10");
    }

    @Test
    void aResultThatFitsUnderTheCeilingIsNotReportedAsTruncated() throws Exception {
        // The control for the flag above. A truncated flag that is always true tells a user
        // nothing, and would make every honest answer look like a partial one.
        this.standIns.put(this.sales.scanExpression(), "range(1, 6) AS narrow(id)");
        AnalyticsQueryService tight = new AnalyticsQueryService(this.sessions,
            limits(2, 10, TIMEOUT_SECONDS));

        QueryResultDto result = tight.query(this.sales, null, "SELECT id FROM dataset");

        assertThat(result.getRowCount()).isEqualTo(5);
        assertThat(result.isTruncated()).isFalse();
    }

    @Test
    void aQueryThatAlreadyHasALimitIsStillWrappedRatherThanAppendedTo() throws Exception {
        QueryResultDto result = this.service.query(this.sales, null,
            "SELECT id FROM dataset ORDER BY id LIMIT 2");

        // Appending is what a first attempt does, and "... LIMIT 2 LIMIT 1000" does not parse.
        assertThat(result.getRowCount()).isEqualTo(2);
        assertThat(theQueryThatRan()).startsWith("SELECT * FROM (SELECT id FROM dataset");
        assertThat(theQueryThatRan()).endsWith("AS bounded_query LIMIT " + MAX_ROWS);
    }

    @Test
    void explainRunsUnwrappedAndIsNeverTruncated() throws Exception {
        QueryResultDto result = this.service.query(this.sales, null, "EXPLAIN SELECT * FROM dataset");

        assertThat(theQueryThatRan()).isEqualTo("EXPLAIN SELECT * FROM dataset");
        assertThat(theQueryThatRan()).doesNotContain("bounded_query");
        assertThat(result.getRows()).isNotEmpty();
        assertThat(result.isTruncated()).isFalse();
    }

    // ---- what the gate stops, on the way to the engine ----------------------------------------------

    @Test
    void aStatementThatIsNotAReadNeverOpensTheDataset() {
        AnalyticsException refused = catchThrowableOfType(
            () -> this.service.query(this.sales, null,
                "COPY (SELECT * FROM dataset) TO '/tmp/analytics-should-not-exist.csv'"),
            AnalyticsException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).contains("only runs queries that read");
        // The gate runs before the views are created, so a refused statement costs a parse and
        // nothing else -- no scan, no bind, no header read, and no record of a dataset being read.
        assertThat(this.executed).isEmpty();
        assertThat(new File("/tmp/analytics-should-not-exist.csv")).doesNotExist();
        assertThat(this.logged.list).noneMatch(event -> event.getLevel() == Level.INFO);
    }

    @Test
    void aSecondStatementAfterASemicolonNeverReachesTheEngine() {
        AnalyticsException refused = catchThrowableOfType(
            () -> this.service.query(this.sales, null, "SELECT 1; SELECT 2"), AnalyticsException.class);

        assertThat(refused).isNotNull();
        assertThat(this.executed).isEmpty();
    }

    // ---- the point of the whole module ---------------------------------------------------------------

    @Test
    void aQueryCannotReadALocalFileEvenThoughTheUserWroteTheSql() throws Exception {
        // Stands in for anything on the deployment's disk worth stealing: the .env, the jar, a
        // mounted secret. In phase one no user could name it because no user wrote the SQL. They
        // do now, so this is the assertion that carries phase one's argument forward.
        Path secretOnDisk = Files.createTempFile("analytics-user-sql", ".csv");
        try {
            Files.write(secretOnDisk, "secret\nDB_PASSWORD=hunter2\n".getBytes("UTF-8"));
            String path = secretOnDisk.toAbsolutePath().toString().replace("'", "''");

            AnalyticsException refused = catchThrowableOfType(
                () -> this.service.query(this.sales, null,
                    "SELECT * FROM read_csv_auto('" + path + "')"), AnalyticsException.class);

            assertThat(refused).isNotNull();
            // Refused by the gate, before the engine is asked, because the statement names a
            // location nobody resolved -- and note that it is a perfectly good READ, so the "only
            // SELECT" half of the gate has nothing to say about it. The layer below is asserted
            // separately, immediately after this.
            assertThat(refused.getMessage()).contains("reads the datasets it was given, by name");
            assertThat(this.executed).isEmpty();
            // Nothing about the file comes back out: not its contents, not its path, and not a
            // message that would confirm to a caller that the file is there at all.
            assertThat(refused.getMessage()).doesNotContain("hunter2");
            assertThat(refused.getMessage()).doesNotContain(path);
            assertThat(secretOnDisk).exists();
        } finally {
            Files.deleteIfExists(secretOnDisk);
        }
    }

    @Test
    void andTheEngineWouldHaveRefusedItAnyway() throws Exception {
        // The layer under the gate, exercised through the production path rather than argued
        // about. The stand-in for the dataset's location is a real file on this machine, so the
        // view the service builds points at the local disk -- and the session it builds it on is
        // the one DuckDbSessionFactory hands out, with disabled_filesystems already set. If the
        // gate above were ever removed, deleted by a merge or walked past by a shape nobody
        // thought of, this is what the statement meets.
        Path secretOnDisk = Files.createTempFile("analytics-engine-layer", ".csv");
        try {
            Files.write(secretOnDisk, "secret\nDB_PASSWORD=hunter2\n".getBytes("UTF-8"));
            this.standIns.put(this.sales.scanExpression(), "read_csv_auto('"
                + secretOnDisk.toAbsolutePath().toString().replace("'", "''") + "')");

            AnalyticsException refused = catchThrowableOfType(
                () -> this.service.query(this.sales, null, "SELECT * FROM dataset"),
                AnalyticsException.class);

            assertThat(refused).isNotNull();
            ILoggingEvent failure = this.logged.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the engine failure should have been logged"));
            assertThat(failure.getThrowableProxy().getMessage())
                .contains("LocalFileSystem")
                .contains("disabled");
            // The user is told nothing about why, which is right: an unmapped engine message is
            // exactly the string that carries a path.
            assertThat(refused.getMessage()).isEqualTo("The dataset could not be read.");
            assertThat(refused.getMessage()).doesNotContain("hunter2");
        } finally {
            Files.deleteIfExists(secretOnDisk);
        }
    }

    @Test
    void aQueryCannotSpendTheConnectionsCredentialsOnADifferentBucket() {
        // The tenancy hole that is invisible to a rule about reads and writes, because this IS a
        // read. The session carries one connection's S3 secret; synthesis 3.3 says "use these
        // credentials against a different bucket" is not a request this API can express, and a SQL
        // editor is a field to express it in unless something takes the field away.
        for (String elsewhere : new String[] {
            "SELECT * FROM read_csv_auto('s3://etl-bucket/another-tenant/payroll.csv')",
            "SELECT * FROM 's3://etl-bucket/kafka/keystore.p12'",
            "SELECT * FROM dataset UNION ALL SELECT * FROM read_csv_auto('s3://other/x.csv')" }) {

            this.executed.clear();
            AnalyticsException refused = catchThrowableOfType(
                () -> this.service.query(this.sales, null, elsewhere), AnalyticsException.class);

            assertThat(refused).as("<%s>", elsewhere).isNotNull();
            assertThat(refused.getMessage()).contains("reads the datasets it was given, by name");
            // Refused before a session did anything, so the credentials were never pointed
            // anywhere at all.
            assertThat(this.executed).isEmpty();
        }
    }

    @Test
    void aQueryCannotWriteAFileToTheLocalDiskEither() throws Exception {
        File target = new File(System.getProperty("java.io.tmpdir"), "analytics-user-write.csv");
        target.delete();

        AnalyticsException refused = catchThrowableOfType(
            () -> this.service.query(this.sales, null,
                "COPY (SELECT 1 AS a) TO '" + target.getAbsolutePath().replace("'", "''")
                    + "' (FORMAT CSV)"), AnalyticsException.class);

        assertThat(refused).isNotNull();
        // Refused twice over, and the order is the design: the gate turns it away before the
        // engine is asked, and DuckDbLockdownTest already proves the engine would have refused it
        // anyway. Either alone would be enough; neither alone would be a reason to remove the
        // other.
        assertThat(refused.getMessage()).contains("only runs queries that read");
        assertThat(target).doesNotExist();
    }

    // ---- the governor and the timeout ------------------------------------------------------------------

    @Test
    void aQueryIsRefusedWhenTheGovernorHasNoPermitLeft() {
        // Taken directly rather than by parking a thread inside the engine: the question is only
        // whether query() goes through the same governed path, and a query that opened its own
        // session would sail past a drained semaphore. This is the "no second door" rule, asserted
        // on the one method in this class that runs SQL somebody else wrote.
        Semaphore slots = (Semaphore)
            ReflectionTestUtils.getField(this.service.getEngine(), "slots");
        slots.drainPermits();

        AnalyticsException refused = catchThrowableOfType(
            () -> this.service.query(this.sales, null, "SELECT * FROM dataset"),
            AnalyticsException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).contains("Too many analytics queries");
        // Not even parsed. A refused caller never pays for a session, which is why the slot is
        // taken first.
        assertThat(this.executed).isEmpty();
    }

    @Test
    void aQueryThatOutstaysItsWelcomeIsStopped() throws Exception {
        // The limit that was a number in a properties file until this release.
        // Statement.setQueryTimeout is a NO-OP in duckdb_jdbc 1.1.3 -- the driver logs "not
        // supported" and returns -- so nothing was enforcing analytics.query.timeout-seconds at
        // all. It did not show while every statement was built by this class and known to
        // terminate; a user who can write "SELECT count(*) FROM range(1e11)" holds a permit for
        // ever, and four of them close the feature until the process restarts.
        AnalyticsQueryService impatient = new AnalyticsQueryService(this.sessions, limits(2, MAX_ROWS, 1));
        // A hundred billion rows behind the dataset name, so the query is an ordinary one over
        // "dataset" -- a user cannot name a table function, and does not need to in order to ask
        // for something that never comes back.
        this.standIns.put(this.sales.scanExpression(), "range(100000000000) AS huge(id)");

        long startedAt = System.currentTimeMillis();
        AnalyticsException refused = catchThrowableOfType(
            () -> impatient.query(this.sales, null,
                "SELECT count(*) FROM dataset WHERE id % 7 = 3"),
            AnalyticsException.class);
        long waited = System.currentTimeMillis() - startedAt;

        assertThat(refused).isNotNull();
        // DuckDB throws "INTERRUPT Error: Interrupted!" when cancel() lands, and explain() has
        // always mapped an interrupt onto the timeout sentence. Nothing produced one before.
        assertThat(refused.getMessage()).contains("longer than 1 seconds and was stopped");
        assertThat(waited)
            .as("the query should have been cut off near the ceiling, not run to completion")
            .isLessThan(30_000L);
    }

    @Test
    void aSlotIsGivenBackAfterAQuerySucceedsAndAfterOneIsRefused() throws Exception {
        AnalyticsQueryService single = new AnalyticsQueryService(this.sessions,
            limits(1, MAX_ROWS, TIMEOUT_SECONDS));

        // With a ceiling of one, a permit that is not released makes the second query the
        // application ever runs the last one it ever runs -- and the refusal path is the one that
        // leaks it, because it leaves through a throw rather than a return.
        assertThat(single.query(this.sales, null, "SELECT * FROM dataset").getRowCount()).isEqualTo(3);
        catchThrowableOfType(() -> single.query(this.sales, null, "DROP TABLE dataset"),
            AnalyticsException.class);
        catchThrowableOfType(() -> single.query(this.sales, null, "SELCT 1"),
            AnalyticsException.class);
        assertThat(single.query(this.sales, null, "SELECT * FROM dataset").getRowCount()).isEqualTo(3);
    }

    @Test
    void aSyntaxErrorComesBackInDuckDbsOwnWordsNowThatTheUserHoldsTheSql() {
        AnalyticsException refused = catchThrowableOfType(
            () -> this.service.query(this.sales, null, "SELCT id FROM dataset"),
            AnalyticsException.class);

        assertThat(refused).isNotNull();
        // The half of gap 20 phase three needed. "syntax error at or near SELCT" says more than
        // any sentence that could be written here, and the generic fallback -- right while the SQL
        // was ours -- would now be the least useful answer possible.
        assertThat(refused.getMessage()).contains("syntax error at or near");
        assertThat(refused.getMessage()).contains("SELCT");
        assertThat(refused.getMessage()).isNotEqualTo("The dataset could not be read.");
    }

    // ---- gap 28: two datasets in one query ---------------------------------------------------------

    @Test
    void twoDatasetsComposeInOneFromClause() throws Exception {
        QueryResultDto result = this.service.query(this.sales, this.regions,
            "SELECT s.id, r.label FROM dataset s JOIN dataset2 r ON s.region = r.region "
                + "ORDER BY s.id");

        assertThat(result.getColumns()).containsExactly("id", "label");
        assertThat(result.getRows()).hasSize(3);
        assertThat(result.getRows().get(0)).containsExactly("1", "Northern");
        assertThat(result.getRows().get(1)).containsExactly("2", "Southern");
        // Two views, two scan expressions, and each one built from its own DatasetRef.
        assertThat(this.executed.get(0)).contains("VIEW dataset AS")
            .contains(this.sales.scanExpression());
        assertThat(this.executed.get(1)).contains("VIEW dataset2 AS")
            .contains(this.regions.scanExpression());
    }

    @Test
    void oneDatasetCostsOneView() throws Exception {
        this.service.query(this.sales, null, "SELECT * FROM dataset");

        // DuckDB binds a view when it is created, so a second one is a second header read. A
        // query over one dataset must not pay for it.
        assertThat(this.executed).hasSize(2);
        assertThat(this.executed).noneMatch(sql -> sql.contains("dataset2"));
    }

    @Test
    void theSecondDatasetIsResolvedOnItsOwnSoAJoinCannotReachAnotherTenant() {
        // The tenancy property of gap 28, asserted where it lives: the controller resolves each
        // dataset separately, through the real DatasetResolver, against the caller's own tenant.
        // A caller who can reach "store" and not "other-store" must not be able to reach
        // "other-store" by joining to it -- and the refusal has to be the same one they would have
        // met asking for it directly, or the join becomes an oracle for what other workspaces own.
        StorageConnectionRepository repository = mock(StorageConnectionRepository.class);
        when(repository.findByAlias("store"))
            .thenReturn(Optional.of(storageConnection(TENANT_ID)));
        StorageConnection theirs = storageConnection(OTHER_TENANT_ID);
        theirs.setAlias("other-store");
        when(repository.findByAlias("other-store")).thenReturn(Optional.of(theirs));

        AnalyticsQueryService queryService = mock(AnalyticsQueryService.class);
        AnalyticsRestApi api = new AnalyticsRestApi(new DatasetResolver(repository), queryService, null, null, new AnalyticsLimits());
        TenantContext.set(TENANT_ID, "TENANT_USER", 7L, "analyst");

        ResponseEntity<?> response = api.query(request("store", SALES_PATH,
            "other-store", REGIONS_PATH, "SELECT * FROM dataset JOIN dataset2 USING (region)"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseDto body = (ResponseDto) response.getBody();
        assertThat(body.getStatus()).isEqualTo(ProcessUtil.ERROR_MESSAGE);
        assertThat(body.getMessage()).isEqualTo("Storage connection not found.");
        // The query never ran. A tenancy check that refused the dataset but ran the SQL anyway
        // would have failed nothing above this line.
        verifyNoInteractions(queryService);
    }

    @Test
    void twoDatasetsTheCallerOwnsBothReachTheQueryService() throws Exception {
        // The control for the refusal above. A resolver that refused every second dataset would
        // satisfy that test while making a join impossible.
        StorageConnectionRepository repository = mock(StorageConnectionRepository.class);
        when(repository.findByAlias(anyString()))
            .thenReturn(Optional.of(storageConnection(TENANT_ID)));

        AnalyticsQueryService queryService = mock(AnalyticsQueryService.class);
        AnalyticsRestApi api = new AnalyticsRestApi(new DatasetResolver(repository), queryService, null, null, new AnalyticsLimits());
        TenantContext.set(TENANT_ID, "TENANT_USER", 7L, "analyst");

        ResponseEntity<?> response = api.query(request("store", SALES_PATH,
            "store", REGIONS_PATH, "SELECT * FROM dataset JOIN dataset2 USING (region)"));

        assertThat(((ResponseDto) response.getBody()).getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        ArgumentCaptor<DatasetRef> second = ArgumentCaptor.forClass(DatasetRef.class);
        // Four arguments now: the controller passes the caller-supplied run id straight through,
        // and this request carries none.
        verify(queryService).query(any(DatasetRef.class), second.capture(), anyString(), isNull());
        assertThat(second.getValue().getPath()).isEqualTo(REGIONS_PATH);
    }

    @Test
    void aQueryOverOneDatasetIsNotGivenASecondOne() throws Exception {
        StorageConnectionRepository repository = mock(StorageConnectionRepository.class);
        when(repository.findByAlias(anyString()))
            .thenReturn(Optional.of(storageConnection(TENANT_ID)));

        AnalyticsQueryService queryService = mock(AnalyticsQueryService.class);
        AnalyticsRestApi api = new AnalyticsRestApi(new DatasetResolver(repository), queryService, null, null, new AnalyticsLimits());
        TenantContext.set(TENANT_ID, "TENANT_USER", 7L, "analyst");

        api.query(request("store", SALES_PATH, null, null, "SELECT * FROM dataset"));

        verify(queryService).query(any(DatasetRef.class), isNull(), eq("SELECT * FROM dataset"),
            isNull());
    }

    @Test
    void halfASecondDatasetIsAnErrorRatherThanAOneDatasetAnswer() {
        StorageConnectionRepository repository = mock(StorageConnectionRepository.class);
        when(repository.findByAlias(anyString()))
            .thenReturn(Optional.of(storageConnection(TENANT_ID)));

        AnalyticsQueryService queryService = mock(AnalyticsQueryService.class);
        AnalyticsRestApi api = new AnalyticsRestApi(new DatasetResolver(repository), queryService, null, null, new AnalyticsLimits());
        TenantContext.set(TENANT_ID, "TENANT_USER", 7L, "analyst");

        // A caller who sent an alias and no path has made a mistake. Silently ignoring the half
        // they did send would run their join as a one-dataset query and answer the wrong question.
        ResponseEntity<?> response = api.query(
            request("store", SALES_PATH, "store", null, "SELECT * FROM dataset"));

        ResponseDto body = (ResponseDto) response.getBody();
        assertThat(body.getStatus()).isEqualTo(ProcessUtil.ERROR_MESSAGE);
        assertThat(body.getMessage()).isEqualTo("Pick a file or a folder pattern first.");
        verifyNoInteractions(queryService);
    }

    private static Map<String, String> request(String connection, String path,
        String connection2, String path2, String sql) {

        Map<String, String> body = new HashMap<>();
        body.put("connection", connection);
        body.put("path", path);
        body.put("connection2", connection2);
        body.put("path2", path2);
        body.put("sql", sql);
        return body;
    }
}

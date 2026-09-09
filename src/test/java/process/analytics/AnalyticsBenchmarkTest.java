package process.analytics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.hibernate.annotations.Filter;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import process.api.AnalyticsBenchmarkRestApi;
import process.model.dto.ObjectMetadataDto;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.AuditListener;
import process.model.pojo.BenchmarkResult;
import process.model.pojo.StorageConnection;
import process.model.repository.BenchmarkResultRepository;
import process.model.service.StorageBrowserService;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.EncryptionUtil;
import process.util.ProcessUtil;
import process.util.UserNameResolver;

import javax.persistence.Column;
import javax.persistence.EntityListeners;
import javax.persistence.EntityManager;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Whether a stored benchmark number can be trusted, and whether it can be misread.
 *
 * This is an unusual thing to test, so it is worth saying what the subject is. The arithmetic --
 * a min, a median, a mean -- is the least interesting part and would be fine if it were wrong,
 * because somebody would notice. What would NOT be noticed is a row that says 412 ms without
 * saying that 412 ms was a whole file open rather than one query, or a sample of five that was
 * really a sample of one because the warmups were counted, or a comparison whose Parquet half
 * failed and whose CSV half was written anyway. Every one of those produces a plausible table that
 * argues for the wrong conclusion, quietly, for as long as anybody trusts it.
 *
 * <b>The engine is real.</b> DuckDbSessionFactory's own locked-down session, the real
 * AnalyticsQueryService with its real semaphore, timeout, statement gate and row ceiling. One
 * thing is substituted and only one -- the LOCATION, because there is no object store in a unit
 * test -- exactly as AnalyticsQueryExecutionTest substitutes it. That matters here more than
 * usual: a benchmark that measured a mock would be measuring the mock, and the claim this class
 * makes about session cost is a claim about what AnalyticsQueryService actually does.
 *
 * <b>The session count is counted, not asserted from the constant.</b> FILE_OPEN_SESSIONS is 3
 * because a file open issues a schema read, a row count and a page -- a fact about another class,
 * written down in this one, which is the definition of a number that will rot. So the test counts
 * the sessions the service really opened and checks the recorded figure against them. If gap 17's
 * caching ever lands and a file open stops costing three, this test fails rather than the table
 * quietly acquiring rows whose most important column is a historical claim.
 *
 * <b>Every refusal has a positive control.</b> A harness that refused every request would satisfy
 * the whole "it declines to generate load" half of this file while being useless, which is exactly
 * how the module's earlier tenancy fix was pinned.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
// Lenient because the fixture scripts a whole session -- statements, prepared statements, close,
// a storage metadata lookup -- and a request refused before anything is opened uses none of it.
// That refusal is the property such a test exists to prove, so an unused stub is not a defect.
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalyticsBenchmarkTest {

    private static final long TENANT_ID = 1001L;
    private static final long OTHER_TENANT_ID = 2002L;
    private static final long PLATFORM_USER_ID = 7L;

    private static final String BUCKET = "etl-bucket";

    private static final String CSV_ALIAS = "store-csv";
    private static final String PARQUET_ALIAS = "store-parquet";
    private static final String CSV_PATH = "etl-demo/sales.csv";
    private static final String PARQUET_PATH = "etl-demo/sales.parquet";
    private static final String FOLDER_PATH = "etl-demo/2026/*.csv";

    private static final int TIMEOUT_SECONDS = 20;
    private static final int MAX_ROWS = 1000;

    /** Three rows, three columns. Small on purpose: this file times the plumbing, not the data. */
    private static final String SALES_STAND_IN =
        "(VALUES (1,'north',10.50),(2,'south',21.00),(3,'north',42.00)) AS sales(id,region,amount)";

    /** A real DuckDB message shape, so explain() maps it the way it would in production. */
    private static final String ENGINE_FAILURE =
        "IO Error: Connection error for HTTP HEAD to 's3://etl-bucket/etl-demo/sales.parquet'";

    @Mock private DuckDbSessionFactory sessions;
    @Mock private DatasetResolver datasetResolver;
    @Mock private BenchmarkResultRepository benchmarkResultRepository;
    @Mock private TenantFilterHelper tenantFilterHelper;
    @Mock private UserNameResolver userNameResolver;
    @Mock private StorageBrowserService storageBrowserService;
    @Mock private EntityManager entityManager;

    /** The real thing: locked down by the real factory, exactly as a request would get it. */
    private Connection engine;

    private AnalyticsQueryService analyticsQueryService;
    private AnalyticsBenchmarkService service;

    private DatasetRef csv;
    private DatasetRef parquet;

    /** Scan expression to the relation that stands in for it. The only substitution in this file. */
    private final Map<String, String> standIns = new LinkedHashMap<>();

    /** Statements that reached the engine, in order. */
    private final List<String> executed = Collections.synchronizedList(new ArrayList<>());

    /**
     * The connection alias handed to sessions.open, per session, in order.
     *
     * This is the instrument the whole file turns on. Each open is one governed session, and the
     * alias says which dataset paid for it -- so this list answers both "how many sessions did a
     * run cost" and "in what order were the datasets measured".
     */
    private final List<String> opened = Collections.synchronizedList(new ArrayList<>());

    /** When set, every statement from this index onward fails. How a mid-benchmark failure is made. */
    private int failFromStatement = -1;

    @BeforeEach
    void setUp() throws Exception {
        this.csv = datasetRef(CSV_ALIAS, CSV_PATH, DatasetRef.Format.CSV);
        this.parquet = datasetRef(PARQUET_ALIAS, PARQUET_PATH, DatasetRef.Format.PARQUET);
        this.standIns.put(this.csv.scanExpression(), SALES_STAND_IN);
        this.standIns.put(this.parquet.scanExpression(), SALES_STAND_IN);

        this.engine = new DuckDbSessionFactory(limits(), new EncryptionUtil())
            .open(storageConnection(CSV_ALIAS));
        this.analyticsQueryService = new AnalyticsQueryService(this.sessions, limits());

        when(this.sessions.open(any(StorageConnection.class))).thenAnswer(call -> {
            this.opened.add(call.getArgument(0, StorageConnection.class).getAlias());
            return session();
        });

        when(this.datasetResolver.resolve(CSV_ALIAS, CSV_PATH)).thenReturn(this.csv);
        when(this.datasetResolver.resolve(PARQUET_ALIAS, PARQUET_PATH)).thenReturn(this.parquet);
        when(this.storageBrowserService.getObjectMetadata(anyString(), anyString()))
            .thenAnswer(call -> new ObjectMetadataDto("sales", call.getArgument(1),
                CSV_PATH.equals(call.getArgument(1)) ? 4_000_000L : 900_000L,
                null, null, null, false));
        when(this.benchmarkResultRepository.saveAll(any())).thenAnswer(call ->
            new ArrayList<>(call.getArgument(0, Iterable.class) instanceof List
                ? (List<?>) call.getArgument(0)
                : new ArrayList<>()));

        this.service = new AnalyticsBenchmarkService(this.datasetResolver,
            this.analyticsQueryService, limits(), this.benchmarkResultRepository,
            this.tenantFilterHelper, this.userNameResolver, this.storageBrowserService);
        // @PersistenceContext is field injection, so there is no constructor to hand it to.
        Field entityManagerField = AnalyticsBenchmarkService.class.getDeclaredField("entityManager");
        entityManagerField.setAccessible(true);
        entityManagerField.set(this.service, this.entityManager);

        // A platform admin, because that is the only role the endpoint admits and the only one
        // that can therefore ever write a row.
        TenantContext.set(null, "PLATFORM_ADMIN", PLATFORM_USER_ID, "operator");
    }

    @AfterEach
    void tearDown() throws Exception {
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
     * close() is left as a mock no-op so the one real engine survives the many sessions the
     * benchmark opens; everything that decides an outcome is passed through.
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
            this.failIfDue();
            return real.executeQuery(rewrite(sql));
        });
        when(recorded.execute(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0, String.class);
            this.executed.add(sql);
            this.failIfDue();
            return real.execute(rewrite(sql));
        });
        doAnswer(call -> {
            real.close();
            return null;
        }).when(recorded).close();
        return recorded;
    }

    private void failIfDue() throws SQLException {
        if (this.failFromStatement >= 0 && this.executed.size() > this.failFromStatement) {
            throw new SQLException(ENGINE_FAILURE);
        }
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
     * would have handed it rather than mocking a final class.
     */
    private static DatasetRef datasetRef(String alias, String path, DatasetRef.Format format) {
        return new DatasetRef(storageConnection(alias), BUCKET, path, format);
    }

    private static StorageConnection storageConnection(String alias) {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(9L);
        connection.setTenantId(TENANT_ID);
        // MINIO with no endpoint, so nothing here can make a network call even by accident.
        connection.setProvider(StorageProvider.MINIO);
        connection.setAlias(alias);
        connection.setBucketName(BUCKET);
        connection.setStatus(Status.Active);
        return connection;
    }

    private static AnalyticsLimits limits() {
        AnalyticsLimits limits = new AnalyticsLimits();
        ReflectionTestUtils.setField(limits, "timeoutSeconds", TIMEOUT_SECONDS);
        ReflectionTestUtils.setField(limits, "maxRows", MAX_ROWS);
        ReflectionTestUtils.setField(limits, "previewPageSize", 100);
        ReflectionTestUtils.setField(limits, "maxConcurrentQueries", 2);
        ReflectionTestUtils.setField(limits, "memoryLimit", "256MB");
        ReflectionTestUtils.setField(limits, "threads", 1);
        return limits;
    }

    private static AnalyticsBenchmarkService.BenchmarkRequest request(String measure,
        Integer runs, Integer warmups, AnalyticsBenchmarkService.BenchmarkDataset... datasets) {
        AnalyticsBenchmarkService.BenchmarkRequest request =
            new AnalyticsBenchmarkService.BenchmarkRequest();
        request.setLabel("orders-1m");
        request.setMeasure(measure);
        request.setRuns(runs);
        request.setWarmups(warmups);
        request.setDatasets(new ArrayList<>(Arrays.asList(datasets)));
        return request;
    }

    private static AnalyticsBenchmarkService.BenchmarkDataset csvDataset() {
        return new AnalyticsBenchmarkService.BenchmarkDataset(CSV_ALIAS, CSV_PATH);
    }

    private static AnalyticsBenchmarkService.BenchmarkDataset parquetDataset() {
        return new AnalyticsBenchmarkService.BenchmarkDataset(PARQUET_ALIAS, PARQUET_PATH);
    }

    /** The two sessions every dataset pays before the timer starts: the schema, and the count. */
    private static final int SHAPE_SESSIONS = 2;

    // ---- what was measured, which is the whole point of the table --------------------------------

    /**
     * The recorded session cost is the session cost that was actually paid.
     *
     * The confound gap 17 named: a file open is three governed sessions, so a FILE_OPEN duration
     * carries the per-session cost three times and is not comparable with a single-query one. That
     * fact is stored on every row as sessions_per_run, and stored facts about other classes decay.
     * So it is checked against the opens the service really made, and it is checked with the
     * warmups included -- which is also what proves the warmups ran at all rather than being a
     * number written onto a row nobody honoured.
     */
    @Test
    void aFileOpenRowRecordsTheThreeSessionsItReallyCost() throws Exception {
        List<BenchmarkResult> rows = this.service.run(
            request(BenchmarkResult.MEASURE_FILE_OPEN, 3, 1, csvDataset()));

        assertThat(rows).hasSize(1);
        BenchmarkResult row = rows.get(0);
        assertThat(row.getSessionsPerRun()).isEqualTo(3);
        assertThat(row.getMeasuredRuns()).isEqualTo(3);
        assertThat(row.getWarmupRuns()).isEqualTo(1);
        assertThat(this.opened)
            .as("two sessions for the shape read, then one warmup and three measured runs at the "
                + "recorded cost each -- if a file open stops costing three, this is where it "
                + "shows up rather than in a column nobody re-derives")
            .hasSize(SHAPE_SESSIONS + (1 + 3) * row.getSessionsPerRun());
    }

    @Test
    void aQueryRowRecordsTheOneSessionItReallyCost() throws Exception {
        AnalyticsBenchmarkService.BenchmarkRequest request =
            request(BenchmarkResult.MEASURE_QUERY, 3, 1, csvDataset());
        request.setSql("SELECT region, sum(amount) FROM dataset GROUP BY region");

        List<BenchmarkResult> rows = this.service.run(request);

        BenchmarkResult row = rows.get(0);
        assertThat(row.getSessionsPerRun()).isEqualTo(1);
        assertThat(this.opened).hasSize(SHAPE_SESSIONS + (1 + 3) * row.getSessionsPerRun());
        // The statement is on the row because a duration without it is not a measurement: the
        // same dataset answers a count and a group-by in wildly different times.
        assertThat(row.getQueryText())
            .isEqualTo("SELECT region, sum(amount) FROM dataset GROUP BY region");
        assertThat(row.getMeasureKind()).isEqualTo(BenchmarkResult.MEASURE_QUERY);
    }

    /**
     * A row says what it measured in words, not only in a code.
     *
     * The enum-ish name can be renamed by a later phase; the sentence stored on the day cannot.
     * The reader of one row in three months has the row and not the source, and the single thing
     * they most need to be told is that this number contains three session opens.
     */
    @Test
    void aRowSaysInWordsWhichMeasurementItIs() throws Exception {
        BenchmarkResult fileOpen = this.service.run(
            request(BenchmarkResult.MEASURE_FILE_OPEN, 3, 0, csvDataset())).get(0);

        AnalyticsBenchmarkService.BenchmarkRequest queryRequest =
            request(BenchmarkResult.MEASURE_QUERY, 3, 0, csvDataset());
        queryRequest.setSql("SELECT * FROM dataset");
        BenchmarkResult query = this.service.run(queryRequest).get(0);

        assertThat(fileOpen.getMeasuredWhat())
            .contains("schema")
            .contains("row count")
            .contains("Three governed sessions")
            .contains("not comparable");
        assertThat(query.getMeasuredWhat()).contains("One statement").contains("One governed session");
        assertThat(fileOpen.getMeasuredWhat()).isNotEqualTo(query.getMeasuredWhat());
    }

    /**
     * The datasets are measured round-robin, not one to completion and then the other.
     *
     * Measuring CSV five times and then Parquet five times gives Parquet a warmer JVM and a warmer
     * client, which biases the result in the exact direction somebody will read it for. This is
     * the assertion that keeps the loops in that order: alias by alias, the measured region is
     * one pass per dataset per run.
     */
    @Test
    void theDatasetsAreInterleavedSoNeitherGetsTheWarmerHalfOfTheRun() throws Exception {
        this.service.run(request(BenchmarkResult.MEASURE_FILE_OPEN, 3, 0,
            csvDataset(), parquetDataset()));

        // Shape first: schema and count for each dataset in turn.
        List<String> shape = this.opened.subList(0, SHAPE_SESSIONS * 2);
        assertThat(shape).containsExactly(CSV_ALIAS, CSV_ALIAS, PARQUET_ALIAS, PARQUET_ALIAS);

        List<String> expected = new ArrayList<>();
        for (int run = 0; run < 3; run++) {
            expected.addAll(Arrays.asList(CSV_ALIAS, CSV_ALIAS, CSV_ALIAS));
            expected.addAll(Arrays.asList(PARQUET_ALIAS, PARQUET_ALIAS, PARQUET_ALIAS));
        }
        assertThat(this.opened.subList(SHAPE_SESSIONS * 2, this.opened.size()))
            .as("one pass per dataset per run, so drift over the request lands on both equally")
            .isEqualTo(expected);
    }

    // ---- the spread, and the honesty of the summary ------------------------------------------------

    /**
     * The summary is recomputable from the samples, and the samples are all there.
     *
     * A mean is where a measurement turns into a slogan. Keeping every kept run on the row, in the
     * order it ran, is what lets a reader who distrusts the summary check it -- and lets them see
     * a sequence that got steadily faster for what it is, which is a warming cache rather than a
     * fast format.
     */
    @Test
    void everyKeptRunIsOnTheRowAndTheSummaryAgreesWithIt() throws Exception {
        BenchmarkResult row = this.service.run(
            request(BenchmarkResult.MEASURE_FILE_OPEN, 5, 1, csvDataset())).get(0);

        List<Long> samples = new ArrayList<>();
        for (String sample : row.getRunDurationsMs().split(",")) {
            samples.add(Long.parseLong(sample));
        }
        assertThat(samples).hasSize(row.getMeasuredRuns()).hasSize(5);

        List<Long> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        assertThat(row.getMinMs()).isEqualTo(sorted.get(0));
        assertThat(row.getMaxMs()).isEqualTo(sorted.get(sorted.size() - 1));
        assertThat(row.getMedianMs()).isEqualTo(sorted.get(2));
        assertThat(row.getMeanMs()).isBetween(row.getMinMs(), row.getMaxMs());
    }

    /**
     * The dataset's shape is measured, not taken from the caller.
     *
     * Rows, columns and bytes are the columns that explain a duration -- Parquet's advantage is
     * largely compression, so a time with no size beside it cannot say whether the format won or
     * the file was simply smaller. A benchmark whose explanatory columns were hearsay would be
     * worth less than none.
     */
    @Test
    void theShapeOnTheRowWasReadRatherThanDeclared() throws Exception {
        BenchmarkResult row = this.service.run(
            request(BenchmarkResult.MEASURE_FILE_OPEN, 3, 0, csvDataset())).get(0);

        assertThat(row.getRowCount()).isEqualTo(3L);
        assertThat(row.getColumnCount()).isEqualTo(3);
        assertThat(row.getDatasetBytes()).isEqualTo(4_000_000L);
        assertThat(row.getDatasetFormat()).isEqualTo("CSV");
        // The alias, never the bucket the resolver found behind it -- the same rule every other
        // table in this module follows.
        assertThat(row.getConnectionAlias()).isEqualTo(CSV_ALIAS);
        assertThat(row.getDatasetPath()).isEqualTo(CSV_PATH);
        assertThat(row.toString()).doesNotContain(BUCKET);
    }

    /**
     * A size that cannot be established honestly is absent, not zero.
     *
     * A folder read as one dataset is a glob, and a glob names no single object to ask about. A
     * zero there would read as "this file is empty", which is a measurable claim and a false one.
     */
    @Test
    void aFolderDatasetRecordsNoSizeRatherThanAWrongOne() throws Exception {
        DatasetRef folder = datasetRef(CSV_ALIAS, FOLDER_PATH, DatasetRef.Format.CSV);
        this.standIns.put(folder.scanExpression(), SALES_STAND_IN);
        when(this.datasetResolver.resolve(CSV_ALIAS, FOLDER_PATH)).thenReturn(folder);

        BenchmarkResult row = this.service.run(request(BenchmarkResult.MEASURE_FILE_OPEN, 3, 0,
            new AnalyticsBenchmarkService.BenchmarkDataset(CSV_ALIAS, FOLDER_PATH))).get(0);

        assertThat(row.getDatasetBytes()).isNull();
        verifyNoInteractions(this.storageBrowserService);
    }

    /**
     * The limits in force are on the row, because they change what the number means.
     *
     * A QUERY result stops at the row ceiling. Two rows measured under different ceilings are not
     * comparable, and once somebody edits a properties file the ceiling that applied is not
     * recoverable from anything else.
     */
    @Test
    void theLimitsThatShapedTheNumberAreRecordedBesideIt() throws Exception {
        BenchmarkResult row = this.service.run(
            request(BenchmarkResult.MEASURE_FILE_OPEN, 3, 0, csvDataset())).get(0);

        assertThat(row.getLimitsAtRun())
            .contains("maxRows=" + MAX_ROWS)
            .contains("timeoutSeconds=" + TIMEOUT_SECONDS)
            .contains("maxConcurrent=2");
    }

    /** One batch is one comparison: the rows meant to be read together carry the same id. */
    @Test
    void theRowsOfOneComparisonShareABatch() throws Exception {
        List<BenchmarkResult> rows = this.service.run(request(BenchmarkResult.MEASURE_FILE_OPEN,
            3, 0, csvDataset(), parquetDataset()));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getBatchId()).isEqualTo(rows.get(1).getBatchId()).isNotBlank();
        assertThat(rows.get(0).getBenchmarkLabel()).isEqualTo("orders-1m");
    }

    // ---- a half-finished comparison is worse than none ---------------------------------------------

    /**
     * If any run fails, nothing is written.
     *
     * This is the failure mode that would do the most damage and would look the most like success:
     * the CSV half of a comparison stored, the Parquet half lost to a timeout, and a table
     * containing one number that reads as a result. A refused run is also not a slow run -- the
     * governor turning a caller away because somebody else is using the module measures the
     * governor -- so it must not be quietly dropped from a sample that then still claims five.
     */
    @Test
    void aComparisonThatLostHalfOfItselfWritesNothing() throws Exception {
        // Past the shape reads and the first measured pass, so samples had already been taken:
        // the point is that a partial sample is discarded, not merely that a bad request is.
        this.failFromStatement = 12;

        AnalyticsException refused = catchThrowableOfType(() -> this.service.run(
            request(BenchmarkResult.MEASURE_FILE_OPEN, 3, 0, csvDataset(), parquetDataset())),
            AnalyticsException.class);

        assertThat(refused).isNotNull();
        verify(this.benchmarkResultRepository, never()).saveAll(any());
        verify(this.benchmarkResultRepository, never()).save(any());
    }

    /** The positive control: with nothing failing, the same request does write its rows. */
    @Test
    void aComparisonThatCompletedIsWrittenOnce() throws Exception {
        this.service.run(request(BenchmarkResult.MEASURE_FILE_OPEN, 3, 0,
            csvDataset(), parquetDataset()));

        verify(this.benchmarkResultRepository).saveAll(any());
    }

    // ---- the refusals, and that each one costs no load ---------------------------------------------

    /**
     * There is no default measurement, and that is the confound handled at the door.
     *
     * FILE_OPEN and QUERY differ by more than any file format does. A harness that quietly picked
     * one would fill a table with durations nobody could account for afterwards, and the person
     * who would have to account for them is the person who ran it.
     */
    @Test
    void aBenchmarkThatDoesNotSayWhatToMeasureIsRefusedBeforeAnythingOpens() {
        AnalyticsException refused = catchThrowableOfType(
            () -> this.service.run(request(null, 3, 0, csvDataset())), AnalyticsException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage())
            .contains("FILE_OPEN").contains("QUERY").contains("no default");
        assertThat(this.opened).as("a refused benchmark generates no load at all").isEmpty();
    }

    /**
     * A query benchmark needs its statement, and count(*) is not offered as a default.
     *
     * The refusal says why, because the reason is the finding: count(*) is answered from Parquet's
     * file footer without reading a row and from every byte of a CSV, so a harness defaulting to
     * it would report an enormous Parquet win that is not about reading data -- in the one tool
     * built to check that claim.
     */
    @Test
    void aQueryBenchmarkWithNoStatementIsRefusedAndSaysWhyThereIsNoDefault() {
        AnalyticsException refused = catchThrowableOfType(
            () -> this.service.run(request(BenchmarkResult.MEASURE_QUERY, 3, 0, csvDataset())),
            AnalyticsException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).contains("count(*)").contains("footer");
        assertThat(this.opened).isEmpty();
    }

    /** A file open runs nobody's statement, so SQL alongside it is a misunderstanding worth naming. */
    @Test
    void aFileOpenBenchmarkCarryingSqlIsRefused() {
        AnalyticsBenchmarkService.BenchmarkRequest request =
            request(BenchmarkResult.MEASURE_FILE_OPEN, 3, 0, csvDataset());
        request.setSql("SELECT * FROM dataset");

        AnalyticsException refused = catchThrowableOfType(() -> this.service.run(request),
            AnalyticsException.class);

        assertThat(refused).isNotNull();
        assertThat(this.opened).isEmpty();
    }

    /**
     * Too few runs is refused rather than raised.
     *
     * Below three there is no spread to report, and a duration with no spread beside it is a
     * single sample of a JIT-compiled JVM against a network object store. Refused rather than
     * silently raised because raising it would generate load the caller did not ask for.
     */
    @Test
    void aBenchmarkTooSmallToShowASpreadIsRefused() {
        AnalyticsException refused = catchThrowableOfType(
            () -> this.service.run(request(BenchmarkResult.MEASURE_FILE_OPEN, 1, 0, csvDataset())),
            AnalyticsException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).contains("spread");
        assertThat(this.opened).isEmpty();
    }

    /**
     * Too many runs is clamped rather than refused, and the row records what really happened.
     *
     * The opposite direction from the test above, deliberately: asking for more load than the
     * ceiling allows is a request worth quietly declining, and measured_runs is the column that
     * stops the clamp being invisible.
     */
    @Test
    void tooManyRunsAreClampedAndTheRowSaysHowManyActuallyRan() throws Exception {
        BenchmarkResult row = this.service.run(
            request(BenchmarkResult.MEASURE_FILE_OPEN, 500, 0, csvDataset())).get(0);

        assertThat(row.getMeasuredRuns()).isEqualTo(10);
        assertThat(row.getRunDurationsMs().split(",")).hasSize(10);
    }

    /**
     * A benchmark whose three dials multiply past the ceiling is refused, with the arithmetic.
     *
     * Each dial is harmless on its own -- four datasets, ten runs, three warmups -- and their
     * product is 164 sessions holding a governor permit one after another. The refusal gives the
     * number so the caller can see which dial to turn down, and it happens before anything opens.
     */
    @Test
    void aBenchmarkThatWouldFloodTheGovernorIsRefusedWithTheNumbers() throws Exception {
        DatasetRef third = datasetRef(CSV_ALIAS, "etl-demo/a.csv", DatasetRef.Format.CSV);
        DatasetRef fourth = datasetRef(CSV_ALIAS, "etl-demo/b.csv", DatasetRef.Format.CSV);
        when(this.datasetResolver.resolve(CSV_ALIAS, "etl-demo/a.csv")).thenReturn(third);
        when(this.datasetResolver.resolve(CSV_ALIAS, "etl-demo/b.csv")).thenReturn(fourth);

        AnalyticsException refused = catchThrowableOfType(() -> this.service.run(
            request(BenchmarkResult.MEASURE_FILE_OPEN, 10, 3,
                csvDataset(), parquetDataset(),
                new AnalyticsBenchmarkService.BenchmarkDataset(CSV_ALIAS, "etl-demo/a.csv"),
                new AnalyticsBenchmarkService.BenchmarkDataset(CSV_ALIAS, "etl-demo/b.csv"))),
            AnalyticsException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).contains("sessions").contains("ceiling");
        assertThat(this.opened).as("checked before anything is opened").isEmpty();
    }

    /** More datasets than a batch may hold is refused, never trimmed to fit. */
    @Test
    void tooManyDatasetsAreRefusedRatherThanQuietlyDropped() {
        List<AnalyticsBenchmarkService.BenchmarkDataset> five = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            five.add(csvDataset());
        }
        AnalyticsBenchmarkService.BenchmarkRequest request =
            request(BenchmarkResult.MEASURE_FILE_OPEN, 3, 0);
        request.setDatasets(five);

        AnalyticsException refused = catchThrowableOfType(() -> this.service.run(request),
            AnalyticsException.class);

        assertThat(refused).isNotNull();
        assertThat(this.opened).isEmpty();
    }

    /** An unlabelled measurement is a number nobody can place, so it is not accepted. */
    @Test
    void aBenchmarkWithNoLabelIsRefused() {
        AnalyticsBenchmarkService.BenchmarkRequest request =
            request(BenchmarkResult.MEASURE_FILE_OPEN, 3, 0, csvDataset());
        request.setLabel("  ");

        AnalyticsException refused = catchThrowableOfType(() -> this.service.run(request),
            AnalyticsException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).contains("label");
    }

    /**
     * Every dataset goes through the resolver on its own, before any of them is opened.
     *
     * A caller cannot reach a connection through a benchmark that they could not have opened by
     * asking for it -- and a request naming one unreachable connection generates no load at all
     * rather than benchmarking the readable half first.
     */
    @Test
    void anUnreachableConnectionRefusesTheWholeBenchmarkBeforeAnyLoadIsGenerated() throws Exception {
        when(this.datasetResolver.resolve(PARQUET_ALIAS, PARQUET_PATH))
            .thenThrow(new AnalyticsException("Storage connection not found."));

        AnalyticsException refused = catchThrowableOfType(() -> this.service.run(
            request(BenchmarkResult.MEASURE_FILE_OPEN, 3, 0, csvDataset(), parquetDataset())),
            AnalyticsException.class);

        assertThat(refused).isNotNull();
        assertThat(refused.getMessage()).isEqualTo("Storage connection not found.");
        assertThat(this.opened).isEmpty();
    }

    // ---- reading the results back -------------------------------------------------------------------

    /**
     * A listing drops anything the caller does not own, after the database has already filtered.
     *
     * Unreachable today, because only a platform admin can reach this endpoint and a platform
     * admin owns everything. It is asserted anyway: a benchmark row names a connection alias and a
     * path -- where a workspace keeps its data -- and this is the check that has to already be
     * right on the day somebody lowers the floor, not the one somebody remembers to add.
     */
    @Test
    void aListingNeverHandsBackAnotherWorkspacesMeasurement() {
        TenantContext.set(TENANT_ID, "TENANT_ADMIN", 55L, "analyst");
        when(this.benchmarkResultRepository
            .findAllByOrderByDateCreatedDescAnalyticsBenchmarkResultIdDesc(any()))
            .thenReturn(Arrays.asList(resultOwnedBy(TENANT_ID), resultOwnedBy(OTHER_TENANT_ID)));

        List<BenchmarkResult> results = this.service.recentResults(null, null, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTenantId()).isEqualTo(TENANT_ID);
    }

    /** The window is clamped, because nothing prunes this table. */
    @Test
    void aListingWindowIsClamped() {
        when(this.benchmarkResultRepository
            .findAllByOrderByDateCreatedDescAnalyticsBenchmarkResultIdDesc(any()))
            .thenReturn(Collections.emptyList());

        this.service.recentResults(null, null, 100000);

        verify(this.benchmarkResultRepository)
            .findAllByOrderByDateCreatedDescAnalyticsBenchmarkResultIdDesc(
                PageRequest.of(0, 200));
    }

    private static BenchmarkResult resultOwnedBy(Long tenantId) {
        BenchmarkResult result = new BenchmarkResult();
        result.setTenantId(tenantId);
        result.setBatchId("batch");
        result.setBenchmarkLabel("orders-1m");
        return result;
    }

    // ---- the row, and the schema behind it ----------------------------------------------------------

    /**
     * A measurement belongs to exactly one workspace.
     *
     * Every row written today carries a null tenant, because only a platform admin can run a
     * benchmark -- which makes it tempting to read the filter as decoration. It is the opposite:
     * with plain equality a null-tenant row is visible to platform admins alone, and with
     * storage_connection's "or tenant_id is null" form it would be published to every tenant on
     * the box. A benchmark row names a connection alias and a path inside it.
     */
    @Test
    void aMeasurementBelongsToExactlyOneWorkspace() {
        Filter filter = BenchmarkResult.class.getAnnotation(Filter.class);

        assertThat(filter).isNotNull();
        assertThat(filter.name()).isEqualTo("tenantFilter");
        assertThat(filter.condition()).isEqualTo("tenant_id = :tenantId");
    }

    /**
     * The row says which connection reaches the data, never where the data is.
     *
     * The same rule analytics_dataset and analytics_query follow. A bucket, endpoint or region
     * copied here would be a second source of truth for a location the connection record owns, and
     * would be wrong on the day somebody repoints that connection -- silently, in a table whose
     * whole purpose is to be trusted months later.
     */
    @Test
    void theRowNamesAConnectionAndNeverALocation() {
        List<String> forbidden = Arrays.asList("bucket", "endpoint", "region", "url", "host",
            "secret", "accesskey", "password", "credential", "connectionstring");
        List<String> offenders = new ArrayList<>();
        for (Field field : BenchmarkResult.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase(Locale.ROOT);
            for (String word : forbidden) {
                if (name.contains(word)) {
                    offenders.add(field.getName());
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    /**
     * Every mapped column is created by the changeset, and the changeset is included.
     *
     * stage and prod run Hibernate with ddl-auto=validate, so a column the changeset does not
     * create is a startup failure there and nothing at all in dev, where ddl-auto=update quietly
     * adds it. The master include is the other half and the quieter one: a changeset that exists,
     * parses and is never run leaves a mapped entity with no table behind it.
     */
    @Test
    void everyMappedColumnIsCreatedByAnIncludedChangeset() throws Exception {
        String sql = changesetSql().toLowerCase(Locale.ROOT);

        assertThat(sql).contains(
            BenchmarkResult.class.getAnnotation(Table.class).name());
        SequenceGenerator generator = BenchmarkResult.class
            .getDeclaredField("analyticsBenchmarkResultId")
            .getAnnotation(SequenceGenerator.class);
        assertThat(generator).isNotNull();
        assertThat(sql).contains(generator.sequenceName());

        List<String> missing = new ArrayList<>();
        for (Field field : BenchmarkResult.class.getDeclaredFields()) {
            Column column = field.getAnnotation(Column.class);
            if (column == null || field.getAnnotation(Transient.class) != null) {
                continue;
            }
            if (!sql.contains(column.name().toLowerCase(Locale.ROOT))) {
                missing.add(column.name());
            }
        }
        assertThat(missing)
            .as("mapped but not in V33__analytics_benchmark.sql -- add a new changeset, never "
                + "edit the applied one")
            .isEmpty();

        assertThat(masterChangelog())
            .as("a changeset nothing includes is a table that never gets created")
            .contains("V33.0-analytics-benchmark.yaml");
    }

    /** The author is stamped by the listener, so no save path has to remember to do it. */
    @Test
    void aMeasurementIsStampedWithWhoRanIt() {
        EntityListeners listeners = BenchmarkResult.class.getAnnotation(EntityListeners.class);
        assertThat(listeners).isNotNull();
        assertThat(listeners.value()).contains(AuditListener.class);

        BenchmarkResult result = new BenchmarkResult();
        new AuditListener().onCreate(result);

        assertThat(result.getCreatedBy()).isEqualTo(PLATFORM_USER_ID);
        // Written once and never edited: that immutability is what makes it evidence.
        assertThat(result.getUpdatedBy()).isNull();
    }

    private static String changesetSql() throws Exception {
        return fileFrom("src/main/resources/db/changelog/changelog-sets/"
            + "V33.0-analytics-benchmark/V33__analytics_benchmark.sql");
    }

    private static String masterChangelog() throws Exception {
        return fileFrom("src/main/resources/db/changelog/db.changelog-master.yaml");
    }

    private static String fileFrom(String relativePath) throws Exception {
        // Surefire runs from the module root; the second path is for a run from the parent.
        for (String prefix : new String[] { "", "process/" }) {
            File file = new File(prefix + relativePath);
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Could not find " + relativePath + " from "
            + System.getProperty("user.dir"));
    }

    // ---- the controller -----------------------------------------------------------------------------

    /**
     * The role floor, which is the only guard in front of a deliberate load generator.
     *
     * PLATFORM_ADMIN rather than the TENANT_USER every other analytics endpoint uses, because this
     * one exists to occupy a governor that is shared by the whole JVM: while it runs, other
     * workspaces are refused. An annotation that is deleted, misspelled or shadowed by a
     * method-level one fails no compile and throws nothing at startup, so it is asserted by
     * reflection -- the only place the value exists.
     */
    @Test
    void theBenchmarkEndpointsSitAtThePlatformAdminFloor() {
        PreAuthorize floor = AnnotatedElementUtils.findMergedAnnotation(
            AnalyticsBenchmarkRestApi.class, PreAuthorize.class);

        assertThat(floor)
            .as("a load generator has no other guard in front of it")
            .isNotNull();
        assertThat(floor.value()).isEqualTo("hasRole('PLATFORM_ADMIN')");
    }

    @Test
    void noBenchmarkHandlerQuietlyEscapesTheClassFloor() {
        List<String> mapped = new ArrayList<>();
        for (Method handler : AnalyticsBenchmarkRestApi.class.getDeclaredMethods()) {
            if (!AnnotatedElementUtils.hasAnnotation(handler, RequestMapping.class)) {
                continue;
            }
            mapped.add(handler.getName());
            // A method-level @PreAuthorize REPLACES the class-level one rather than adding to it,
            // so a handler declaring its own is a handler this floor no longer covers.
            assertThat(AnnotatedElementUtils.hasAnnotation(handler, PreAuthorize.class))
                .as(handler.getName() + " must inherit the class floor rather than override it")
                .isFalse();
        }
        // Without this the loop above passes by iterating over nothing, which is how a renamed or
        // dropped mapping annotation would look from here.
        assertThat(mapped).contains("runBenchmark", "fetchRecentResults");
    }

    /**
     * Every response says what these numbers do not cover.
     *
     * The module makes two speed claims and this harness can test only one of them: there is no
     * load-into-Postgres path to time against. A result quoted for the untested claim would be an
     * argument about the architecture won with evidence that was never gathered, and the cheapest
     * defence is that the sentence travels with the numbers.
     */
    @Test
    void everyResponseSaysWhatWasNotMeasured() throws Exception {
        AnalyticsBenchmarkService benchmarkService = mock(AnalyticsBenchmarkService.class);
        when(benchmarkService.run(any())).thenReturn(Collections.emptyList());
        when(benchmarkService.recentResults(any(), any(), any())).thenReturn(Collections.emptyList());
        AnalyticsBenchmarkRestApi api = new AnalyticsBenchmarkRestApi(benchmarkService, new AnalyticsLimits());

        ResponseEntity<?> ran = api.runBenchmark(
            request(BenchmarkResult.MEASURE_FILE_OPEN, 3, 0, csvDataset()));
        ResponseEntity<?> fetched = api.fetchRecentResults(null, null, null);

        for (ResponseEntity<?> response : Arrays.asList(ran, fetched)) {
            ResponseDto body = (ResponseDto) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
            assertThat(body.getMessage())
                .contains("loading into Postgres")
                .contains("no load-into-Postgres path");
        }
    }

    /** A refused benchmark is a business failure: an OK carrying the sentence that was written. */
    @Test
    void aRefusedBenchmarkIsAnOkCarryingItsOwnWords() throws Exception {
        String message = "A benchmark needs at least 3 measured runs.";
        AnalyticsBenchmarkService benchmarkService = mock(AnalyticsBenchmarkService.class);
        when(benchmarkService.run(any())).thenThrow(new AnalyticsException(message));
        AnalyticsBenchmarkRestApi api = new AnalyticsBenchmarkRestApi(benchmarkService, new AnalyticsLimits());

        ResponseEntity<?> response = api.runBenchmark(
            request(BenchmarkResult.MEASURE_FILE_OPEN, 1, 0, csvDataset()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseDto body = (ResponseDto) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(ProcessUtil.ERROR_MESSAGE);
        // Not merely "contains": the sentence was written to be read as it stands.
        assertThat(body.getMessage()).isEqualTo(message);
    }

    /** And only an unexpected failure is a 500, saying nothing about itself. */
    @Test
    void anUnexpectedFailureIsA500ThatSaysNothingAboutItself() throws Exception {
        AnalyticsBenchmarkService benchmarkService = mock(AnalyticsBenchmarkService.class);
        when(benchmarkService.run(any()))
            .thenThrow(new IllegalStateException("minio.internal:9000 refused the connection"));
        AnalyticsBenchmarkRestApi api = new AnalyticsBenchmarkRestApi(benchmarkService, new AnalyticsLimits());

        ResponseEntity<?> response = api.runBenchmark(
            request(BenchmarkResult.MEASURE_FILE_OPEN, 3, 0, csvDataset()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ResponseDto body = (ResponseDto) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getMessage()).isEqualTo(ProcessUtil.INTERNAL_ERROR_500);
        assertThat(body.getMessage()).doesNotContain("minio.internal");
    }
}

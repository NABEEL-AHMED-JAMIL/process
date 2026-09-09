package process.analytics;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.concurrent.SettableListenableFuture;
import process.api.AnalyticsExportRestApi;
import process.config.KafkaConnectionResolver;
import process.config.KafkaTemplateProvider;
import process.config.MethodSecurityConfig;
import process.model.dto.ObjectMetadataDto;
import process.model.service.StorageBrowserService;
import process.model.dto.ResponseDto;
import process.model.enums.Status;
import process.model.enums.StorageProvider;
import process.model.pojo.StorageConnection;
import process.model.repository.StorageConnectionRepository;
import process.security.TenantContext;
import process.util.EncryptionUtil;
import process.util.ProcessUtil;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * What leaves the browser: a file, an object in a bucket, and an event on a topic.
 *
 * This runs a REAL DuckDB, for the reason DuckDbLockdownTest and AnalyticsProfileTest run one. The
 * claims under test are claims about what a specific engine does when handed a COPY, and a mocked
 * engine would do whatever this file told it to. Two of them were measured before they were
 * asserted: that COPY reports rows written as the JDBC update count -- 100 rows came back 100, an
 * empty result came back 0 -- and that json_serialize_sql refuses a COPY with "Only SELECT
 * statements can be serialized to json!", which is why a user cannot write one of their own.
 *
 * <b>The engine is real; the BUCKET is a directory.</b> There is no object store in a unit test, so
 * the same substitution the profile and query tests already make is made once more: the scan
 * expression becomes a literal relation, and the "s3://etl-bucket/" the service composed becomes a
 * temp directory, so a write actually lands and its bytes can be read back. Every assertion about
 * WHERE a write went is made on the statement as the service composed it, before that substitution
 * -- so "it went to the connection's own bucket" is a claim about production SQL and not about a
 * temp directory.
 *
 * <b>What the substitution cannot prove, a real locked-down session does.</b>
 * theEngineRefusesALocalWriteEvenWhenTheKeyCheckIsBypassed opens a session from the real
 * DuckDbSessionFactory, with disabled_filesystems set exactly as production sets it, and points the
 * composed COPY at a local file. That is the second of the two layers gap 31 rests on and it is
 * asserted here without touching DuckDbLockdownTest, whose aSessionCannotWriteAFileToTheLocalDisk
 * is the line that must not move.
 *
 * <b>Positive controls throughout.</b> A key check that refused everything would pass every refusal
 * below while making the feature useless, so every refusal has a sibling that succeeds: a complete
 * download says nothing about truncation, a query under the ceiling is written whole, an ordinary
 * folder is accepted, and a broker that is up publishes the event that a broker that is down does
 * not.
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
// Lenient because the fixture scripts a whole session and a request refused before the engine is
// reached uses none of it -- and being refused that early is the property those tests exist for.
@MockitoSettings(strictness = Strictness.LENIENT)
class AnalyticsExportTest {

    private static final long TENANT_ID = 1001L;
    private static final long OTHER_TENANT_ID = 2002L;
    private static final String ALIAS = "store";
    private static final String BUCKET = "etl-bucket";
    private static final String PATH = "etl-demo/sales.csv";
    private static final int TIMEOUT_SECONDS = 20;

    /** Five rows, so a ceiling of two truncates and a ceiling of ten does not. */
    private static final String SALES_STAND_IN =
        "(VALUES (1,'north',10.50),(2,'south',21.00),(3,'north',42.00),"
        + "(4,'east',5.00),(5,'west',7.25)) AS sales(id,region,amount)";

    /** Everything a COPY writes to, so a destination outside it is a test failure by itself. */
    private static final Pattern COPY_TARGET = Pattern.compile("TO '([^']*)'");

    /** Twelve rows where the view the probe counted had five. See the mid-write test. */
    private static final String GROWN_STAND_IN = "range(1, 13) AS grown(id)";

    @Mock private StorageConnectionRepository storageConnectionRepository;
    @Mock private DuckDbSessionFactory sessions;
    @Mock private KafkaTemplateProvider kafkaTemplateProvider;
    @Mock private KafkaConnectionResolver kafkaConnectionResolver;
    @Mock private StorageBrowserService storageBrowserService;
    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    /** A real, un-locked-down DuckDB. What a locked one refuses is asserted on its own below. */
    private Connection engine;

    /** Stands in for s3://etl-bucket/. A file appearing anywhere else is the failure being hunted. */
    private Path bucket;

    private final List<String> executed = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> standIns = new LinkedHashMap<>();
    private final List<AnalyticsQueryService> startedServices = new ArrayList<>();
    private final List<AnalyticsExportService> startedExports = new ArrayList<>();

    /** Makes the relation the COPY reads bigger than the one the probe counted. */
    private boolean theDatasetGrowsBeforeTheCopy;

    private DatasetResolver resolver;
    private AnalyticsExportService exports;
    /** The one inside this.exports, so a test can reach past the export service to it. */
    private AnalyticsQueryService queryService;
    private AnalyticsExportRestApi api;
    private DatasetRef sales;

    @BeforeEach
    void setUp() throws Exception {
        this.bucket = Files.createTempDirectory("analytics-bucket");
        this.engine = DriverManager.getConnection("jdbc:duckdb:");
        this.resolver = new DatasetResolver(this.storageConnectionRepository);
        when(this.storageConnectionRepository.findByAlias(anyString()))
            .thenReturn(Optional.of(storageConnection(TENANT_ID)));

        this.sales = new DatasetRef(storageConnection(TENANT_ID), BUCKET, PATH,
            DatasetRef.Format.CSV);
        this.standIns.put(this.sales.scanExpression(), SALES_STAND_IN);
        // Applied second, so the scan's own s3:// URL has already been replaced by the relation
        // above and only the COPY's destination is left to redirect.
        this.standIns.put("s3://" + BUCKET + "/", this.bucket.toAbsolutePath() + "/");

        Connection duck = session();
        when(this.sessions.open(any(StorageConnection.class))).thenReturn(duck);

        when(this.kafkaTemplateProvider.getTemplate(any())).thenReturn(this.kafkaTemplate);
        when(this.kafkaTemplate.send(anyString(), anyString(), anyString()))
            .thenReturn(new SettableListenableFuture<>());

        this.exports = exportService(10);
        this.queryService = this.startedServices.get(0);
        this.api = new AnalyticsExportRestApi(this.exports, new AnalyticsLimits());

        TenantContext.set(TENANT_ID, "TENANT_USER", 7L, "analyst");
    }

    @AfterEach
    void tearDown() throws Exception {
        for (AnalyticsQueryService service : this.startedServices) {
            service.shutdown();
        }
        for (AnalyticsExportService export : this.startedExports) {
            export.shutdown();
        }
        this.engine.close();
        TenantContext.clear();
    }

    // ---- the fixture ------------------------------------------------------------------------------

    private AnalyticsExportService exportService(int maxRows) {
        AnalyticsLimits limits = limits(maxRows);
        AnalyticsQueryService service = new AnalyticsQueryService(this.sessions, limits);
        this.startedServices.add(service);
        AnalyticsExportService export = new AnalyticsExportService(this.resolver, service, limits,
            this.kafkaTemplateProvider, this.kafkaConnectionResolver, this.storageBrowserService);
        this.startedExports.add(export);
        return export;
    }

    private static AnalyticsLimits limits(int maxRows) {
        AnalyticsLimits limits = new AnalyticsLimits();
        ReflectionTestUtils.setField(limits, "timeoutSeconds", TIMEOUT_SECONDS);
        ReflectionTestUtils.setField(limits, "maxRows", maxRows);
        ReflectionTestUtils.setField(limits, "previewPageSize", 100);
        ReflectionTestUtils.setField(limits, "maxConcurrentQueries", 2);
        ReflectionTestUtils.setField(limits, "memoryLimit", "256MB");
        ReflectionTestUtils.setField(limits, "threads", 1);
        return limits;
    }

    private static StorageConnection storageConnection(long tenantId) {
        StorageConnection connection = new StorageConnection();
        connection.setStorageConnectionId(9L);
        connection.setTenantId(tenantId);
        // MINIO with no endpoint, so nothing here can reach the network even by accident.
        connection.setProvider(StorageProvider.MINIO);
        connection.setAlias(ALIAS);
        connection.setBucketName(BUCKET);
        connection.setStatus(Status.Active);
        return connection;
    }

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
        when(recorded.getUpdateCount()).thenAnswer(call -> real.getUpdateCount());
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

    /**
     * The two substitutions, and the directory a redirected COPY needs to exist.
     *
     * An object store creates a key's prefixes for you and a filesystem does not, so a write that
     * would have succeeded against MinIO would fail here for a reason that has nothing to do with
     * what is being tested. The directory is created only for a destination already inside the
     * stand-in bucket, so a COPY that somehow escaped it still fails.
     */
    private String rewrite(String sql) throws Exception {
        String rewritten = sql;
        for (Map.Entry<String, String> standIn : this.standIns.entrySet()) {
            rewritten = rewritten.replace(standIn.getKey(), standIn.getValue());
        }
        if (this.theDatasetGrowsBeforeTheCopy && rewritten.startsWith("COPY")) {
            // Only the COPY, and deliberately not the view the probe read through: the view was
            // bound when it was created, which is what makes this a stand-in for the store moving
            // underneath a session rather than for a session changing its own mind.
            rewritten = rewritten.replace("FROM dataset)", "FROM " + GROWN_STAND_IN + ")");
        }
        Matcher target = COPY_TARGET.matcher(rewritten);
        if (rewritten.startsWith("COPY") && target.find()) {
            Path destination = Paths.get(target.group(1));
            if (destination.startsWith(this.bucket) && destination.getParent() != null) {
                Files.createDirectories(destination.getParent());
            }
        }
        return rewritten;
    }

    private Map<String, String> request(String... pairs) {
        Map<String, String> body = new HashMap<>();
        body.put("connection", ALIAS);
        body.put("path", PATH);
        for (int i = 0; i < pairs.length; i += 2) {
            body.put(pairs[i], pairs[i + 1]);
        }
        return body;
    }

    private String theCopyThatRan() {
        List<String> copies = new ArrayList<>();
        for (String sql : this.executed) {
            if (sql.startsWith("COPY")) {
                copies.add(sql);
            }
        }
        assertThat(copies).as("exactly one COPY should have been composed").hasSize(1);
        return copies.get(0);
    }

    private List<Path> objectsInTheBucket() throws Exception {
        List<Path> found = new ArrayList<>();
        Files.walk(this.bucket).filter(Files::isRegularFile).forEach(found::add);
        return found;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ResponseDto response) {
        assertThat(response.getStatus()).as("%s", response.getMessage())
            .isEqualTo(ProcessUtil.SUCCESS);
        return (Map<String, Object>) response.getData();
    }

    private static String fileIn(ResponseDto response) {
        return new String(Base64.getDecoder().decode(
            String.valueOf(dataOf(response).get("content"))), StandardCharsets.UTF_8);
    }

    // ---- download: the bytes ------------------------------------------------------------------------

    @Test
    void aDownloadIsTheResultAsCsvWithItsColumnNamesOnTheFirstLine() {
        ResponseDto response = this.exports.download(request(
            "sql", "SELECT region, sum(amount) AS total FROM dataset GROUP BY region ORDER BY region"));

        assertThat(fileIn(response)).isEqualTo(
            "region,total\n"
            + "east,5.00\n"
            + "north,52.50\n"
            + "south,21.00\n"
            + "west,7.25\n");
        Map<String, Object> data = dataOf(response);
        assertThat(data.get("filename").toString()).startsWith("sales-").endsWith(".csv");
        assertThat(data.get("contentType")).isEqualTo("text/csv");
        assertThat(data.get("truncated")).isEqualTo(false);
    }

    @Test
    void aDownloadWithNoStatementOfItsOwnIsTheWholeDataset() {
        // The "just give me this file as CSV" case, and it is a query like any other rather than a
        // second path that skips the gate.
        ResponseDto response = this.exports.download(request());

        assertThat(fileIn(response)).startsWith("id,region,amount\n1,north,10.50\n");
        assertThat(dataOf(response).get("rowCount")).isEqualTo(5);
    }

    @Test
    void aTsvDownloadIsTabsAndSaysSoInItsContentType() {
        ResponseDto response = this.exports.download(request("format", "tsv",
            "sql", "SELECT region FROM dataset WHERE id = 1"));

        assertThat(fileIn(response)).isEqualTo("region\nnorth\n");
        assertThat(dataOf(response).get("contentType")).isEqualTo("text/tab-separated-values");
    }

    @Test
    void aCellThatOpensLikeAFormulaIsDefusedAndANumberIsLeftAlone() {
        // A downloaded CSV exists to be opened in a spreadsheet, and the values in it came out of
        // somebody's file. The control matters as much as the defence: prefixing a number would
        // turn a figure into text the sheet cannot total.
        ResponseDto response = this.exports.download(request(
            "sql", "SELECT '=cmd|calc' AS formula, -12.5 AS negative, 'plain' AS ordinary "
                + "FROM dataset WHERE id = 1"));

        assertThat(fileIn(response)).isEqualTo(
            "formula,negative,ordinary\n'=cmd|calc,-12.5,plain\n");
    }

    // ---- download: whether the file says it is complete -----------------------------------------

    @Test
    void aTruncatedDownloadSaysSoInTheFileInItsNameAndInTheResponse() {
        AnalyticsExportService tight = exportService(2);

        ResponseDto response = tight.download(request("sql", "SELECT id FROM dataset ORDER BY id"));
        String file = fileIn(response);
        Map<String, Object> data = dataOf(response);

        // In the bytes. A file gets renamed, mailed and copied out of the browser that knew it was
        // partial, so the marker travels inside it.
        assertThat(file).isEqualTo(
            "id\n1\n2\n# INCOMPLETE EXPORT: this export stopped at the 2-row limit and there "
            + "may be more rows.\n");
        // In the name, for the reader who never opens it.
        assertThat(data.get("filename").toString()).contains("-partial.csv");
        // In the response, for the screen.
        assertThat(data.get("truncated")).isEqualTo(true);
        assertThat(response.getMessage()).contains("incomplete");
    }

    @Test
    void theTruncationMarkerIsAWholeRowSoTheFileStillParses() {
        AnalyticsExportService tight = exportService(2);

        String file = fileIn(tight.download(request(
            "sql", "SELECT id, region, amount FROM dataset ORDER BY id")));

        List<String> lines = Arrays.asList(file.trim().split("\n"));
        assertThat(lines).hasSize(4);
        for (String line : lines) {
            // Three cells on every line, the marker's included. A one-cell marker would be a
            // ragged record that a strict reader rejects, which turns "your export is short" into
            // "your export is broken".
            assertThat(line.split(",", -1)).as("line: %s", line).hasSize(3);
        }
        assertThat(lines.get(3)).startsWith("# INCOMPLETE EXPORT");
    }

    @Test
    void aCompleteDownloadSaysNothingAboutTruncationAnywhere() {
        // The control. A marker that is always there tells a reader nothing, and would make every
        // honest export look like a partial one.
        ResponseDto response = this.exports.download(request(
            "sql", "SELECT id FROM dataset ORDER BY id"));

        assertThat(fileIn(response)).doesNotContain("INCOMPLETE");
        assertThat(dataOf(response).get("filename").toString()).doesNotContain("partial");
        assertThat(dataOf(response).get("truncated")).isEqualTo(false);
        assertThat(dataOf(response).get("notice")).isNull();
    }

    @Test
    void aJsonDownloadCarriesItsCompletenessAsAFieldOfItself() {
        AnalyticsExportService tight = exportService(2);

        Map<String, Object> document = new Gson().fromJson(
            fileIn(tight.download(request("format", "json",
                "sql", "SELECT id FROM dataset ORDER BY id"))),
            new TypeToken<Map<String, Object>>() { }.getType());

        assertThat(document.get("truncated")).isEqualTo(Boolean.TRUE);
        assertThat(String.valueOf(document.get("notice"))).contains("there may be more rows");
        // Provenance by alias and path, the pair AnalyticsQueryRun keeps. Not a location.
        assertThat(document.get("connection")).isEqualTo(ALIAS);
        assertThat(document.get("path")).isEqualTo(PATH);
        assertThat(String.valueOf(document.get("columns"))).contains("id");
        assertThat(fileIn(tight.download(request("format", "json")))).doesNotContain("s3://");
    }

    @Test
    void aJsonDownloadKeepsBothColumnsWhenTwoWereAskedForByTheSameName() {
        // Rows are arrays rather than objects, and this is the case that would have decided it if
        // the engine had not already: "SELECT id AS a, region AS a" is legal, and an object with
        // two "a" keys keeps whichever was written last. DuckDB 1.1.3 disambiguates the second
        // label to a_1 by itself, which is worth pinning -- it is the reason the object shape is
        // merely unnecessary here rather than wrong, and the day it changes is a day somebody
        // should be told before choosing the friendlier shape.
        String file = fileIn(this.exports.download(request(
            "format", "json", "sql", "SELECT id AS a, region AS a FROM dataset WHERE id = 1")));

        assertThat(file).contains("\"columns\":[\"a\",\"a_1\"]");
        assertThat(file).contains("[\"1\",\"north\"]");
    }

    @Test
    void parquetIsNotOfferedForADownloadAndTheRefusalSaysWhereItIsOffered() {
        ResponseDto response = this.exports.download(request("format", "parquet"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR_MESSAGE);
        assertThat(response.getMessage()).contains("written to a bucket, not downloaded");
    }

    // ---- write-back: where it lands ---------------------------------------------------------------

    @Test
    void aWriteBackWillNotSilentlyReplaceSomethingAlreadyThere() throws Exception {
        // The reliability defect specification 15 names as "idempotent export/write operations",
        // and it was real: the file name carried a stamp only to the SECOND, so two write-backs of
        // the same dataset into the same folder inside one second built the same key -- and nothing
        // looked to see whether that key was taken. The second replaced the first and reported
        // success, in a class whose own message tells the user a storage connection has no undo.
        when(this.storageBrowserService.getObjectMetadata(anyString(), anyString()))
            .thenReturn(new ObjectMetadataDto());

        ResponseDto response = this.exports.writeBack(request());

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR_MESSAGE);
        assertThat(response.getMessage()).contains("already a file at").contains("no undo");
        // And nothing was written. A refusal that still wrote would be the worse of both.
        assertThat(this.executed).noneMatch(sql -> sql.startsWith("COPY"));
    }

    @Test
    void aWriteBackReplacesSomethingWhenItIsAskedTo() throws Exception {
        // The control. Refusing every write over an existing object would satisfy the test above
        // while making "replace yesterday's export" impossible, which is a real thing to want.
        when(this.storageBrowserService.getObjectMetadata(anyString(), anyString()))
            .thenReturn(new ObjectMetadataDto());

        ResponseDto response = this.exports.writeBack(request("overwrite", "true"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(theCopyThatRan()).startsWith("COPY (");
    }

    @Test
    void aStorageErrorRefusesTheWriteRatherThanAssumingTheKeyIsFree() throws Exception {
        // Fail closed. Treating an unreadable answer as "nothing is there" would restore exactly
        // the clobber the check exists to prevent, and would do it precisely when the store is
        // unhealthy -- the moment a silent overwrite is hardest to notice.
        when(this.storageBrowserService.getObjectMetadata(anyString(), anyString()))
            .thenThrow(new IllegalStateException("connection reset"));

        ResponseDto response = this.exports.writeBack(request());

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR_MESSAGE);
        assertThat(response.getMessage()).contains("Could not check");
        assertThat(this.executed).noneMatch(sql -> sql.startsWith("COPY"));
    }

    @Test
    void anAbsentObjectIsNotAnErrorAndTheWriteProceeds() throws Exception {
        // The other half of failing closed: every store spells "not found" differently, so an
        // absence is recognised by the shape of the message. If that recognition broke, every
        // write-back would refuse and the feature would be dead rather than unsafe.
        when(this.storageBrowserService.getObjectMetadata(anyString(), anyString()))
            .thenThrow(new IllegalArgumentException("NoSuchKey: the specified key does not exist"));

        ResponseDto response = this.exports.writeBack(request());

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(theCopyThatRan()).startsWith("COPY (");
    }

    @Test
    void aWriteBackLandsInTheConnectionsOwnBucket() throws Exception {
        ResponseDto response = this.exports.writeBack(request(
            "sql", "SELECT region, sum(amount) AS total FROM dataset GROUP BY region ORDER BY region"));

        Map<String, Object> data = dataOf(response);
        // The claim, made on the statement as the service composed it. The bucket in it came off
        // the storage_connection record; no field of the request could have put it there.
        assertThat(theCopyThatRan())
            .startsWith("COPY (SELECT * FROM (SELECT region, sum(amount) AS total FROM dataset "
                + "GROUP BY region ORDER BY region) AS bounded_query LIMIT 10) TO 's3://"
                + BUCKET + "/analytics-exports/sales-")
            .endsWith(".csv' (FORMAT CSV, HEADER)");
        assertThat(data.get("bucket")).isEqualTo(BUCKET);
        assertThat(data.get("key").toString()).startsWith("analytics-exports/sales-");
        assertThat(data.get("rowCount")).isEqualTo(4L);
        assertThat(data.get("truncated")).isEqualTo(false);

        // And the object is really there, with the rows in it.
        List<Path> written = objectsInTheBucket();
        assertThat(written).hasSize(1);
        assertThat(new String(Files.readAllBytes(written.get(0)), StandardCharsets.UTF_8))
            .isEqualTo("region,total\neast,5.00\nnorth,52.50\nsouth,21.00\nwest,7.25\n");
    }

    @Test
    void aWriteBackWithNoStatementConvertsTheDatasetInPlaceWithoutOverwritingIt() throws Exception {
        // The "give me this CSV as Parquet" case, and the reason the file name carries a stamp
        // nobody chooses: without it the most obvious key to name is the one the source occupies.
        ResponseDto response = this.exports.writeBack(request("format", "parquet"));

        String key = dataOf(response).get("key").toString();
        assertThat(key).isNotEqualTo(PATH).startsWith("analytics-exports/sales-").endsWith(".parquet");
        assertThat(theCopyThatRan()).endsWith("(FORMAT PARQUET)");

        List<Path> written = objectsInTheBucket();
        assertThat(written).hasSize(1);
        // A real Parquet file, not a CSV with the wrong extension.
        byte[] magic = Arrays.copyOf(Files.readAllBytes(written.get(0)), 4);
        assertThat(new String(magic, StandardCharsets.UTF_8)).isEqualTo("PAR1");
    }

    @Test
    void aJsonWriteBackIsAJsonArrayRatherThanTheEnginesDefaultOfOneObjectPerLine() throws Exception {
        this.exports.writeBack(request("format", "json",
            "sql", "SELECT id FROM dataset WHERE id < 3 ORDER BY id"));

        assertThat(theCopyThatRan()).endsWith("(FORMAT JSON, ARRAY true)");
        String object = new String(Files.readAllBytes(objectsInTheBucket().get(0)),
            StandardCharsets.UTF_8);
        assertThat(object.trim()).startsWith("[").endsWith("]").contains("{\"id\":1}");
    }

    @Test
    void aTsvWriteBackSendsARealTabToTheEngineAndNotTheTwoCharactersThatSpellOne() throws Exception {
        // The only writer option with an escape in it, and the one place a "\t" that stayed two
        // characters would produce a file delimited by a backslash and a t.
        this.exports.writeBack(request("format", "tsv",
            "sql", "SELECT id, region FROM dataset WHERE id = 1"));

        assertThat(theCopyThatRan()).endsWith("(FORMAT CSV, HEADER, DELIMITER '\t')");
        assertThat(new String(Files.readAllBytes(objectsInTheBucket().get(0)),
            StandardCharsets.UTF_8)).isEqualTo("id\tregion\n1\tnorth\n");
    }

    @Test
    void anOrdinaryFolderIsAcceptedAndTheWriteStaysInsideTheBucket() throws Exception {
        // The positive control for every refusal below. A key check that refused everything would
        // pass all of them and leave the feature unusable.
        ResponseDto response = this.exports.writeBack(request(
            "folder", "team/derived/2026-09", "fileName", "Regional Totals!!"));

        assertThat(dataOf(response).get("key").toString())
            .startsWith("team/derived/2026-09/regional-totals-").endsWith(".csv");
        assertThat(theCopyThatRan()).contains("TO 's3://" + BUCKET + "/team/derived/2026-09/");
        assertThat(objectsInTheBucket()).hasSize(1);
    }

    // ---- write-back: the destination cannot be moved ---------------------------------------------

    @Test
    void everyKeyThatCouldNameSomewhereElseIsRefusedAndNothingIsWritten() throws Exception {
        // Each one is a shape that has taken a location check apart somewhere before: the climb,
        // the scheme, the Windows separator, the glob, the empty step, the absolute path.
        Map<String, String> refused = new LinkedHashMap<>();
        refused.put("..", "a climb out of the prefix");
        refused.put("../../etc", "a climb to an absolute-looking path");
        refused.put("exports/../../etc", "a climb in the middle");
        refused.put("s3://other-bucket/loot", "a bucket of the caller's own");
        refused.put("https://attacker.test/collect", "an address to post to");
        refused.put("file:///etc", "a local scheme");
        refused.put("C:\\Windows\\Temp", "a Windows path");
        refused.put("exports/*", "a glob, which names a set and not a file");
        // The sibling the first version of this corpus missed. StorageBrowserServiceImpl
        // .isSafeKey (:405-418) refuses "." and ".." as segments; this refused only "..",
        // so an export could write an object the application's own Object Browser then
        // declines to preview, download or stat. "./x" and "x" are also one object to a
        // gateway that normalises and two to one that does not.
        refused.put(".", "a step that means \"here\" and addresses nothing");
        refused.put("a/./b", "the same step, buried where a prefix check would not look");
        refused.put("exports/.", "and trailing, where a split without -1 would drop it");
        refused.put("exports//deep", "an empty step");
        refused.put("exports/'; DROP TABLE x; --", "a quote that would end the SQL literal");
        refused.put("exports/%2e%2e/x", "a percent-encoded climb");

        for (Map.Entry<String, String> folder : refused.entrySet()) {
            this.executed.clear();
            ResponseDto response = this.exports.writeBack(
                request("folder", folder.getKey()));

            assertThat(response.getStatus())
                .as("%s (%s) should be refused", folder.getKey(), folder.getValue())
                .isEqualTo(ProcessUtil.ERROR_MESSAGE);
            assertThat(this.executed)
                .as("%s reached the engine", folder.getKey())
                .noneMatch(sql -> sql.startsWith("COPY"));
        }
        assertThat(objectsInTheBucket()).isEmpty();
    }

    @Test
    void aFolderThatLooksAbsoluteIsWrittenInsideTheBucketAndNotOnTheDisk() throws Exception {
        // "/etc" is not refused, and that is correct rather than lax: stripped of its leading
        // slash it is an ordinary prefix inside the bucket, which is where it goes. The property
        // that matters is not that the string was rejected, it is that the destination is still
        // the connection's own bucket.
        this.exports.writeBack(request("folder", "/etc"));

        assertThat(theCopyThatRan()).contains("TO 's3://" + BUCKET + "/etc/");
        assertThat(new File("/etc/sales.csv")).doesNotExist();
        assertThat(objectsInTheBucket()).hasSize(1);
        assertThat(objectsInTheBucket().get(0).startsWith(this.bucket)).isTrue();
    }

    @Test
    void aFileNameIsReducedToANameAndCannotCarryAPathIntoTheKey() throws Exception {
        // A name is reduced to letters, digits and hyphens rather than refused: the caller was
        // naming a FILE, so the slashes in it are a mistake and not an attack, and the useful
        // answer is the file they meant rather than a lecture.
        ResponseDto response = this.exports.writeBack(request("fileName", "../../../etc/passwd"));

        assertThat(dataOf(response).get("key").toString())
            .startsWith("analytics-exports/etc-passwd-").endsWith(".csv");
        assertThat(theCopyThatRan())
            .contains("TO 's3://" + BUCKET + "/analytics-exports/etc-passwd-");
        assertThat(objectsInTheBucket()).hasSize(1);
    }

    @Test
    void aBucketNamedInTheRequestIsNotAFieldAndChangesNothing() throws Exception {
        // There is no bucket parameter and no output-connection parameter, so the closest a caller
        // can come is sending the fields anyway. They are read by nothing.
        Map<String, String> body = request("bucket", "someone-elses-bucket",
            "outputConnection", "their-store", "submitUrl", "https://attacker.test/collect",
            "url", "s3://someone-elses-bucket/loot.csv");

        ResponseDto response = this.exports.writeBack(body);

        assertThat(dataOf(response).get("bucket")).isEqualTo(BUCKET);
        assertThat(theCopyThatRan()).contains("TO 's3://" + BUCKET + "/");
        assertThat(theCopyThatRan()).doesNotContain("someone-elses-bucket");
        assertThat(theCopyThatRan()).doesNotContain("attacker.test");
    }

    @Test
    void aTargetOnAnotherConnectionIsRefusedByTheClassThatBuildsTheSql() {
        // The export service is bypassed entirely here. A session carries exactly one connection's
        // credentials, so a target on another connection would be this connection's credential
        // spent on somebody else's bucket -- and the check that stops it belongs to the class
        // about to interpolate the URL into SQL, not only to the one that validated the key.
        StorageConnection other = storageConnection(OTHER_TENANT_ID);
        other.setStorageConnectionId(99L);
        other.setBucketName("someone-elses-bucket");
        DatasetRef elsewhere = new DatasetRef(other, "someone-elses-bucket",
            "analytics-exports/loot.csv", DatasetRef.Format.CSV);
        AnalyticsQueryService service = this.queryService;

        assertThatThrownBy(() -> service.copyTo(this.sales, null, "SELECT * FROM dataset", elsewhere))
            .isInstanceOf(AnalyticsException.class)
            .hasMessageContaining("names a different connection");
        assertThat(this.executed).noneMatch(sql -> sql.startsWith("COPY"));
    }

    @Test
    void aTargetPathThatWouldClimbOrGlobIsRefusedByTheClassThatBuildsTheSql() {
        // The same bypass, aimed at the path rather than the connection. Phase three's lesson was
        // that a location check with one uninspected half is a location check with a hole, so the
        // last layer re-examines what it is about to write even though the layer above it already
        // did.
        AnalyticsQueryService service = this.queryService;
        List<String> paths = Arrays.asList("analytics-exports/../../loot.csv",
            // The "." segment, at the interpolation point as well as in the folder check above --
            // the two layers deliberately overlap, so both are asserted.
            "analytics-exports/./loot.csv", "./loot.csv",
            "/analytics-exports/loot.csv", "analytics-exports//loot.csv",
            "analytics-exports/*.csv", "analytics-exports/");

        for (String path : paths) {
            DatasetRef target = new DatasetRef(storageConnection(TENANT_ID), BUCKET, path,
                DatasetRef.Format.CSV);
            assertThatThrownBy(() ->
                service.copyTo(this.sales, null, "SELECT * FROM dataset", target))
                .as("%s should not be writable", path)
                .isInstanceOf(AnalyticsException.class)
                .hasMessageContaining("not a location an export can be written to");
        }
        assertThat(this.executed).noneMatch(sql -> sql.startsWith("COPY"));
    }

    @Test
    void aConnectionTheCallerDoesNotOwnIsRefusedBeforeAnythingIsWritten() throws Exception {
        when(this.storageConnectionRepository.findByAlias(anyString()))
            .thenReturn(Optional.of(storageConnection(OTHER_TENANT_ID)));

        ResponseDto response = this.exports.writeBack(request());

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR_MESSAGE);
        assertThat(response.getMessage()).isEqualTo("Storage connection not found.");
        assertThat(objectsInTheBucket()).isEmpty();
    }

    // ---- write-back: the engine is the second layer ----------------------------------------------

    @Test
    void theEngineRefusesALocalWriteEvenWhenTheKeyCheckIsBypassed() throws Exception {
        // The layer underneath the key check, asserted on a session from the real factory with
        // disabled_filesystems set exactly as production sets it. This is a separate claim from
        // DuckDbLockdownTest's, which is about the session; this one is about the statement this
        // feature composes, pointed somewhere it must never reach.
        File target = new File(this.bucket.toFile(), "should-not-exist.csv");
        DuckDbSessionFactory factory = new DuckDbSessionFactory(limits(10), new EncryptionUtil());

        try (Connection locked = factory.open(storageConnection(TENANT_ID));
             Statement statement = locked.createStatement()) {

            String copy = "COPY (SELECT * FROM (SELECT 1 AS a) AS bounded_query LIMIT 10) TO '"
                + target.getAbsolutePath().replace("'", "''") + "' (FORMAT CSV, HEADER)";
            assertThatThrownBy(() -> statement.execute(copy))
                .isInstanceOf(SQLException.class)
                .satisfies(ex -> {
                    String message = ex.getMessage().toLowerCase();
                    assertThat(message.contains("localfilesystem") || message.contains("disabled")
                        || message.contains("permission"))
                        .as("the refusal should name the filesystem rule, got: %s", message)
                        .isTrue();
                });
        }
        assertThat(target).doesNotExist();
    }

    @Test
    void aRelativeOutputNameHasNoLocalFilesystemToLandOnEither() throws Exception {
        // The one shape a slash-based check cannot catch, and the reason the two layers overlap
        // here on purpose.
        DuckDbSessionFactory factory = new DuckDbSessionFactory(limits(10), new EncryptionUtil());

        try (Connection locked = factory.open(storageConnection(TENANT_ID));
             Statement statement = locked.createStatement()) {

            assertThatThrownBy(() ->
                statement.execute("COPY (SELECT 1 AS a) TO 'beside-the-jar.csv' (FORMAT CSV)"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("LocalFileSystem");
        }
        assertThat(new File("beside-the-jar.csv")).doesNotExist();
    }

    // ---- write-back: a partial object is never written -------------------------------------------

    @Test
    void aQueryOverTheExportCeilingWritesNothingAtAll() throws Exception {
        AnalyticsExportService tight = exportService(2);

        ResponseDto response = tight.writeBack(request("sql", "SELECT id FROM dataset"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR_MESSAGE);
        assertThat(response.getMessage())
            .contains("more than the 2 rows an export may write")
            .contains("nothing was written");
        // The point of the whole design. A download that stops at the ceiling can say so in its
        // last line; an object in a bucket has nowhere to put that sentence, so it is refused
        // instead of written short. A partial object is worse than none: nothing downstream can
        // tell it from a complete one.
        assertThat(this.executed).noneMatch(sql -> sql.startsWith("COPY"));
        assertThat(objectsInTheBucket()).isEmpty();
    }

    @Test
    void aQueryExactlyAtTheCeilingIsStillWrittenWhole() throws Exception {
        // The boundary, and the control for the refusal above. The probe counts to ceiling + 1 for
        // exactly this reason: a count that stopped at the ceiling reports "all of it" and "more
        // than we can write" as the same number.
        AnalyticsExportService exact = exportService(5);

        ResponseDto response = exact.writeBack(request("sql", "SELECT id FROM dataset"));

        assertThat(dataOf(response).get("rowCount")).isEqualTo(5L);
        assertThat(objectsInTheBucket()).hasSize(1);
        assertThat(Files.readAllLines(objectsInTheBucket().get(0))).hasSize(6);
    }

    @Test
    void anExportWhoseDatasetGrewBetweenTheCountAndTheWriteIsReportedAndNotCalledComplete()
        throws Exception {
        // The one remaining way a short object could reach a bucket, and the reason the row count
        // is compared afterwards as well as taken beforehand. The count and the COPY are two
        // statements on one session; the object store under them is not part of that session, so
        // a dataset that grew in between would be written bounded with every check above passed.
        this.theDatasetGrowsBeforeTheCopy = true;

        ResponseDto response = this.exports.writeBack(request("sql", "SELECT id FROM dataset"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR_MESSAGE);
        assertThat(response.getMessage())
            .contains("changed while it was being exported")
            .contains("may be incomplete")
            // Names the object, because it is already written and this is the only warning
            // anybody gets that it is the suspect one.
            .contains("analytics-exports/sales-");
    }

    @Test
    void anExportOfAnUnchangedDatasetIsNotAccusedOfChanging() throws Exception {
        // The control for the check above. A comparison that fired whenever the two numbers were
        // not identical for some other reason would refuse every honest export.
        this.theDatasetGrowsBeforeTheCopy = false;

        ResponseDto response = this.exports.writeBack(request("sql", "SELECT id FROM dataset"));

        assertThat(dataOf(response).get("rowCount")).isEqualTo(5L);
        assertThat(objectsInTheBucket()).hasSize(1);
    }

    @Test
    void anEmptyResultIsAHeaderOnlyObjectAndNotAFailure() throws Exception {
        ResponseDto response = this.exports.writeBack(
            request("sql", "SELECT id FROM dataset WHERE id > 900"));

        assertThat(dataOf(response).get("rowCount")).isEqualTo(0L);
        assertThat(Files.readAllLines(objectsInTheBucket().get(0))).containsExactly("id");
    }

    // ---- write-back: the statement is still the gate's to admit -----------------------------------

    @Test
    void aUsersOwnCopyStatementIsRefusedBeforeAnythingIsComposedAroundIt() throws Exception {
        // Measured on 1.1.3: json_serialize_sql answers "Only SELECT statements can be serialized
        // to json!" for a COPY, so the gate refuses it as unserialisable exactly as it refuses any
        // other write. The only COPY this application can run is one AnalyticsQueryService built.
        ResponseDto response = this.exports.writeBack(request(
            "sql", "COPY (SELECT 1 AS a) TO 's3://someone-elses-bucket/loot.csv' (FORMAT CSV)"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR_MESSAGE);
        assertThat(response.getMessage()).contains("only runs queries that read");
        assertThat(this.executed).noneMatch(sql -> sql.startsWith("COPY"));
        assertThat(objectsInTheBucket()).isEmpty();
    }

    @Test
    void aStatementNamingALocationOfItsOwnIsRefusedOnTheWayToAWrite() throws Exception {
        ResponseDto response = this.exports.writeBack(request(
            "sql", "SELECT * FROM \"s3://someone-elses-bucket/\".\"payroll.csv\""));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR_MESSAGE);
        assertThat(response.getMessage()).contains("It cannot name a file, a bucket or a URL");
        assertThat(objectsInTheBucket()).isEmpty();
    }

    @Test
    void twoStatementsAreRefusedOnTheExportPathToo() throws Exception {
        ResponseDto response = this.exports.writeBack(
            request("sql", "SELECT 1; SELECT 2"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR_MESSAGE);
        assertThat(response.getMessage()).contains("one statement at a time");
        assertThat(objectsInTheBucket()).isEmpty();
    }

    @Test
    void aPlanCannotBeWrittenToABucket() throws Exception {
        ResponseDto response = this.exports.writeBack(
            request("sql", "EXPLAIN SELECT * FROM dataset"));

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR_MESSAGE);
        assertThat(response.getMessage()).contains("query plan cannot be written");
        assertThat(objectsInTheBucket()).isEmpty();
    }

    // ---- the event ----------------------------------------------------------------------------------

    @Test
    void aCompletedQueryIsAnnouncedOnTheTopicWithTheWorkspaceAsItsKey() {
        this.exports.download(request("sql", "SELECT id FROM dataset ORDER BY id"));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(this.kafkaTemplate, timeout(5000)).send(
            eq(AnalyticsExportService.QUERY_COMPLETED_TOPIC), eq(String.valueOf(TENANT_ID)),
            payload.capture());

        Map<String, Object> event = new Gson().fromJson(payload.getValue(),
            new TypeToken<Map<String, Object>>() { }.getType());
        assertThat(event.get("connectionAlias")).isEqualTo(ALIAS);
        assertThat(event.get("datasetPath")).isEqualTo(PATH);
        assertThat(event.get("status")).isEqualTo("SUCCESS");
        assertThat(event.get("destination")).isEqualTo("DOWNLOAD");
        assertThat(event.get("truncated")).isEqualTo(Boolean.FALSE);
        // Read on the request thread, because TenantContext is a ThreadLocal and the publishing
        // thread carries no principal at all.
        assertThat(event.get("username")).isEqualTo("analyst");
        assertThat(((Number) event.get("tenantId")).longValue()).isEqualTo(TENANT_ID);
    }

    @Test
    void theEventCarriesNoCredentialNoBucketAndNoUrl() {
        this.exports.writeBack(request("folder", "team/derived"));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(this.kafkaTemplate, timeout(5000))
            .send(anyString(), anyString(), payload.capture());

        String event = payload.getValue();
        // The rule AnalyticsQueryRun states for its columns, applied harder here because a topic
        // travels further than a table behind a tenant filter does.
        assertThat(event).doesNotContain("s3://");
        assertThat(event).doesNotContain(BUCKET);
        assertThat(event.toLowerCase()).doesNotContain("secret");
        assertThat(event.toLowerCase()).doesNotContain("password");
        assertThat(event.toLowerCase()).doesNotContain("access_key");
        // The key without the bucket, which is the same shape as datasetPath.
        assertThat(event).contains("\"outputPath\":\"team/derived/sales-");
        assertThat(event).contains("\"destination\":\"BUCKET\"");
        // Not the SQL either: an event is read by machines deciding what to do next, and the
        // statement lives in the history table, inside the workspace that ran it.
        assertThat(event).doesNotContain("SELECT");
    }

    @Test
    void aBrokerThatCannotBeReachedDoesNotFailTheQuery() {
        when(this.kafkaTemplateProvider.getTemplate(any()))
            .thenThrow(new IllegalStateException("no brokers available"));

        ResponseDto response = this.exports.download(request("sql", "SELECT id FROM dataset"));

        // An audit side effect that can fail a read is worse than no audit side effect.
        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(fileIn(response)).contains("id");
    }

    @Test
    void aBrokerThatDoesNotAnswerCannotHoldTheRequestThread() throws Exception {
        // The reason the publisher has a thread of its own. KafkaTemplate.send() is asynchronous
        // in its RESULT and not in its start: a producer with no metadata for the topic blocks in
        // send() for max.block.ms, which defaults to a minute. On a request thread that turns a
        // broker nobody noticed was down into a minute-long analytics query -- a worse outcome
        // than losing the event.
        CountDownLatch reachedTheBroker = new CountDownLatch(1);
        CountDownLatch letItGo = new CountDownLatch(1);
        when(this.kafkaTemplate.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
            reachedTheBroker.countDown();
            letItGo.await(10, TimeUnit.SECONDS);
            return new SettableListenableFuture<>();
        });

        long startedAt = System.currentTimeMillis();
        ResponseDto response = this.exports.download(request("sql", "SELECT id FROM dataset"));
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(reachedTheBroker.await(5, TimeUnit.SECONDS))
            .as("the event should still have been attempted").isTrue();
        assertThat(elapsed).as("the query waited for the broker").isLessThan(2000L);
        letItGo.countDown();
    }

    @Test
    void aRefusedQueryIsNotAnnouncedAsACompletedOne() {
        // The control for the event. A publisher that fired on every request would make "a query
        // completed" mean nothing, and the refusal has its own record in the history table.
        this.exports.writeBack(request("folder", ".."));

        verifyNoInteractions(this.kafkaTemplate);
    }

    // ---- the controller ------------------------------------------------------------------------------

    @Test
    void downloadingIsATenantUserAndWritingIsATenantAdmin() throws Exception {
        PreAuthorize onClass = AnalyticsExportRestApi.class.getAnnotation(PreAuthorize.class);
        assertThat(onClass).isNotNull();
        assertThat(onClass.value()).isEqualTo("hasRole('TENANT_USER')");

        Method download = AnalyticsExportRestApi.class.getMethod("download", Map.class);
        Method writeBack = AnalyticsExportRestApi.class.getMethod("writeBack", Map.class);
        // @PreAuthorize is not repeatable, so a method-level one REPLACES the class annotation
        // rather than adding to it. Downloading must therefore have none of its own.
        assertThat(download.getAnnotation(PreAuthorize.class)).isNull();
        assertThat(writeBack.getAnnotation(PreAuthorize.class).value())
            .isEqualTo("hasRole('TENANT_ADMIN')");
    }

    @Test
    void theRoleHierarchyIsWhatMakesTheHigherFloorSafeToSetOnOneMethod() {
        // Raising writeBack to TENANT_ADMIN removes TENANT_USER from that method, so a
        // TENANT_ADMIN reaching it at all depends on this hierarchy existing. Deleting it would
        // silently widen nothing and silently NARROW this endpoint to nobody but the hierarchy's
        // absence is exactly the kind of change that fails no compile.
        RoleHierarchy hierarchy = new MethodSecurityConfig().roleHierarchy();
        List<GrantedAuthority> asAdmin = Collections.singletonList(
            new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"));

        assertThat(hierarchy.getReachableGrantedAuthorities(asAdmin))
            .extracting(GrantedAuthority::getAuthority)
            .contains("ROLE_TENANT_ADMIN", "ROLE_TENANT_USER");
    }

    @Test
    void aBusinessFailureIsATwoHundredCarryingStatusError() {
        ResponseEntity<?> response = this.api.writeBack(request("folder", ".."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((ResponseDto) response.getBody()).getStatus())
            .isEqualTo(ProcessUtil.ERROR_MESSAGE);
    }

    @Test
    void anUnexpectedFailureIsAFiveHundredCarryingNothingOfItsOwn() {
        AnalyticsExportService broken = mock(AnalyticsExportService.class);
        when(broken.download(any())).thenThrow(new IllegalStateException(
            "jdbc:duckdb: failed at minio.internal:9000 with secret AKIAEXAMPLE"));

        ResponseEntity<?> response = new AnalyticsExportRestApi(broken, new AnalyticsLimits()).download(request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ResponseDto body = (ResponseDto) response.getBody();
        assertThat(body.getMessage()).isEqualTo(ProcessUtil.INTERNAL_ERROR_500);
        assertThat(body.getMessage()).doesNotContain("minio.internal");
    }

    // ---- the destination that was deliberately not built ---------------------------------------------

    @Test
    void thereIsNoDestinationThatPostsAResultToAnAddressSomebodyTyped() {
        // The scope decision, asserted rather than remembered. ReportExportServiceImpl has a
        // submit destination and reports gaps 1-4 are open against it; this feature was scoped to
        // avoid inheriting them by copy, and the way that stops being true is somebody adding a
        // third case to a switch six months from now.
        for (Field field : AnalyticsExportService.class.getDeclaredFields()) {
            assertThat(field.getType().getName())
                .as("%s should not hold an HTTP client", field.getName())
                .doesNotContain("RestTemplate")
                .doesNotContain("HttpClient")
                .doesNotContain("WebClient");
        }
        List<String> surface = new ArrayList<>();
        for (Method method : AnalyticsExportService.class.getDeclaredMethods()) {
            surface.add(method.getName().toLowerCase());
        }
        for (Method method : AnalyticsExportRestApi.class.getDeclaredMethods()) {
            surface.add(method.getName().toLowerCase());
        }
        assertThat(surface).noneMatch(name -> name.contains("submit") || name.contains("post"));
    }
}

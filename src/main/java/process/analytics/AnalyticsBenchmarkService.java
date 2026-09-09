package process.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.analytics.dto.DatasetSchemaDto;
import process.model.dto.ObjectMetadataDto;
import process.model.pojo.BenchmarkResult;
import process.model.repository.BenchmarkResultRepository;
import process.model.service.StorageBrowserService;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.security.TenantOwnership;
import process.util.UserNameResolver;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Measures what a read of a dataset actually costs on this deployment, and writes it down.
 *
 * <b>Why this exists.</b> Since phase one the module has answered a failed scan with "Try a
 * narrower dataset, or Parquet instead of CSV" ({@link AnalyticsQueryService} explain), and the
 * whole feature rests on a premise about speed. Nobody had checked either. Advice that has never
 * been measured on the deployment giving it is a guess in the voice of an instrument, and this
 * class is what turns it into a number somebody can argue with.
 *
 * <b>The measurement goes through the front door, and that is not a detail.</b> Every run here
 * calls {@link AnalyticsQueryService} with a {@link DatasetRef} that
 * {@link DatasetResolver} produced -- so each one takes a governor permit, opens a session
 * {@code DuckDbSessionFactory} has locked down, runs under the same timeout and, for a query, goes
 * through {@code StatementGate} and {@code bounded()}. A harness that opened its own JDBC
 * connection would be quicker to write, would produce lower numbers, and would be measuring
 * something no user can ask for. It would also be the second door beside the locked one that this
 * module is shaped to prevent -- a rule that does not stop applying because the caller is a
 * benchmark.
 *
 * <b>The confound, which decides the whole design.</b> Opening a file in the Studio costs THREE
 * governed sessions: the schema read, the row count and the first page. So a benchmark that times
 * "opening a dataset" carries the per-session cost three times, and one that times a single query
 * carries it once and is not what a user experiences. Neither is wrong. Choosing without saying
 * which was chosen is what is wrong, so {@code measure} has no default -- a caller must say
 * FILE_OPEN or QUERY -- and the stored row records the choice three ways: as a name, as a
 * sentence, and as {@code sessionsPerRun}, which is the number that makes two rows comparable or
 * visibly not.
 *
 * <b>What it deliberately does not measure.</b> The module's other claim is that reading in place
 * beats loading into Postgres. There is no load-into-Postgres path in this application and adding
 * one is out of scope, so there is nothing to time it against. This class measures formats and
 * session cost, and every sentence it puts on a response says so, because a benchmark that let its
 * results be quoted for a claim it never tested would be worse than the silence it replaced.
 *
 * <b>Why it is not a scheduled job.</b> A benchmark is a deliberate load generator: it performs
 * the same read over and over, and because the runs are sequential it holds one of the governor's
 * four permits continuously for as long as that takes -- minutes, for a large dataset at ten runs.
 * A ceiling of four with one permit permanently spoken for is a ceiling of three, and gap 17 means
 * a single user opening a file already needs three. That is the reason the endpoint in front of
 * this sits at PLATFORM_ADMIN and the reason nothing here runs itself.
 *
 * @author Nabeel Ahmed
 */
@Service
public class AnalyticsBenchmarkService {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsBenchmarkService.class);

    /**
     * How many governed sessions one measured run costs, per kind.
     *
     * FILE_OPEN is three because {@code schemaOf} is one session and {@code preview} is two -- the
     * count and the page -- and the Studio issues both when a user clicks a file. These are
     * constants rather than a count taken at runtime because there is nowhere to observe a session
     * open from outside {@code DuckDbSessionFactory}; what keeps them true is
     * {@code AnalyticsBenchmarkTest}, which counts the opens against a fake factory and fails if
     * either number drifts.
     */
    private static final int FILE_OPEN_SESSIONS = 3;

    private static final int QUERY_SESSIONS = 1;

    /** The two shape reads every dataset pays before timing starts: the schema, and the count. */
    private static final int SHAPE_SESSIONS = 2;

    /**
     * Runs kept, and the floor under it.
     *
     * Three is a floor rather than a default because below it there is no spread to report, and a
     * duration with no spread beside it is the thing this phase exists to stop being written down.
     * A caller asking for fewer is refused; a caller asking for more than the ceiling gets the
     * ceiling, which is the same bargain the preview page size makes -- asking for less load than
     * allowed is a mistake worth naming, asking for more is a request worth quietly declining.
     */
    private static final int DEFAULT_RUNS = 5;
    private static final int MIN_RUNS = 3;
    private static final int MAX_RUNS = 10;

    /** Discarded runs. Zero is allowed, and is recorded, because it changes what the number means. */
    private static final int DEFAULT_WARMUPS = 1;
    private static final int MAX_WARMUPS = 3;

    /**
     * Datasets in one comparison.
     *
     * Two is the case this exists for -- the same data as CSV and as Parquet. Four leaves room for
     * a second pair at another size in the same batch, which is worth having because rows from one
     * batch are the only rows honestly comparable with each other. More than that is refused
     * rather than trimmed: silently dropping a dataset would leave a comparison missing its other
     * half and looking complete.
     */
    private static final int MAX_DATASETS = 4;

    /**
     * The most governed sessions one request may generate.
     *
     * A ceiling on the PRODUCT, because the three dials are individually harmless and multiply:
     * four datasets, three warmups and ten runs of a file open is 4 x (2 + 13 x 3) = 164 sessions,
     * each one taking a permit and each one a round trip to the object store. The runs are
     * sequential, so this occupies one of the governor's permits continuously rather than all of
     * them -- but it occupies it for as long as 164 reads take, and it holds a request thread for
     * the same stretch.
     *
     * 120 rather than a rounder number because it is the point at which the useful shapes still
     * fit: two datasets at ten runs and three warmups is 82, and four datasets still get eight
     * runs each. It is checked before anything is opened, so a request too big to run costs
     * nothing at all.
     */
    private static final int MAX_PROJECTED_SESSIONS = 120;

    private static final int MAX_LABEL_LENGTH = 255;

    private static final int DEFAULT_RESULT_LIMIT = 50;
    private static final int MAX_RESULT_LIMIT = 200;

    /**
     * What a FILE_OPEN row means, in the words the row itself will carry.
     *
     * Written as a sentence and stored per row rather than looked up from the kind, because the
     * person reading a row in three months has the row and not this file.
     */
    private static final String FILE_OPEN_MEANS =
        "One file open, as the Studio performs one when a user clicks a file: the schema read, "
        + "then the row count and the first page. Three governed sessions per run, so this "
        + "duration contains the per-session cost three times. It is what a user waits for; it is "
        + "not comparable with a single-query measurement.";

    private static final String QUERY_MEANS =
        "One statement, through the same governor, session lock-down, statement gate and row "
        + "ceiling a user's own query goes through. One governed session per run. The statement "
        + "measured is in query_text; a duration without it is not a measurement.";

    /**
     * The sentence that goes back with every result, and the one thing this API most needs to say.
     *
     * The module makes two speed claims and this harness can only test one of them. Saying so on
     * every response is cheap insurance against the results being quoted for the other, which is
     * the misreading that would do real damage -- it is an argument about the architecture, and it
     * would be won with evidence that was never gathered.
     */
    private static final String WHAT_THIS_DID_NOT_MEASURE =
        "These numbers compare formats and session cost on this deployment. Nothing here measures "
        + "reading in place against loading into Postgres: this application has no "
        + "load-into-Postgres path to time against.";

    @PersistenceContext
    private EntityManager entityManager;

    private final DatasetResolver datasetResolver;
    private final AnalyticsQueryService analyticsQueryService;
    private final AnalyticsLimits limits;
    private final BenchmarkResultRepository benchmarkResultRepository;
    private final TenantFilterHelper tenantFilterHelper;
    private final UserNameResolver userNameResolver;

    /**
     * The object browser's own service, for the one fact the engine cannot report: how many bytes
     * are on the store.
     *
     * It matters more than it looks. Parquet's advantage over CSV is largely compression, so a
     * duration with no size beside it cannot say whether the format won or the file was smaller.
     * Borrowed rather than reimplemented, per the module's standing decision that there is one
     * storage walk and Analytics uses it.
     */
    private final StorageBrowserService storageBrowserService;

    /**
     * One benchmark at a time, across the whole JVM.
     *
     * The class javadoc's whole argument for the role floor is that a benchmark holds ONE of the
     * governor's permits continuously rather than saturating it. Nothing enforced that: four
     * concurrent POSTs hold all four permits, and every other analytics call in the process --
     * every tenant's -- is then refused for as long as they run, which the projected-session
     * ceiling allows to be a very long time. A comment that asserts what the code does not
     * guarantee is worse than no comment, so this makes the sentence true instead of softening it.
     *
     * Not fair() and not queued: a second benchmark is turned away immediately rather than made to
     * wait, because waiting would hold a Tomcat thread for the length of the first one.
     */
    private final java.util.concurrent.Semaphore onlyOneAtATime = new java.util.concurrent.Semaphore(1);

    public AnalyticsBenchmarkService(DatasetResolver datasetResolver,
        AnalyticsQueryService analyticsQueryService, AnalyticsLimits limits,
        BenchmarkResultRepository benchmarkResultRepository, TenantFilterHelper tenantFilterHelper,
        UserNameResolver userNameResolver, StorageBrowserService storageBrowserService) {
        this.datasetResolver = datasetResolver;
        this.analyticsQueryService = analyticsQueryService;
        this.limits = limits;
        this.benchmarkResultRepository = benchmarkResultRepository;
        this.tenantFilterHelper = tenantFilterHelper;
        this.userNameResolver = userNameResolver;
        this.storageBrowserService = storageBrowserService;
    }

    /**
     * Runs one benchmark and returns the rows it wrote.
     *
     * <b>Deliberately not @Transactional.</b> The measuring takes as long as the reads take -- that
     * is the point of it -- and wrapping the whole thing would hold a pooled database connection
     * open for minutes while doing no database work at all. The single write at the end goes
     * through {@code saveAll}, which carries its own transaction.
     *
     * <b>The runs are interleaved, not batched per dataset.</b> Every warmup pass, and then every
     * measured pass, touches each dataset once in turn. Measuring CSV to completion and then
     * Parquet to completion would give the second one a warmer JVM and a warmer network, which is
     * a bias in exactly the direction the result is being read for. Round-robin spreads whatever
     * drift there is evenly across the datasets being compared, and it makes sample <i>i</i> of one
     * dataset adjacent in time to sample <i>i</i> of the other.
     *
     * <b>They are sequential, never concurrent.</b> Running the datasets in parallel would put
     * them in contention for the same permits, and the number that came back would be a
     * measurement of the governor rather than of the file.
     *
     * <b>All rows are written or none are.</b> If any run fails -- an engine error, a timeout, or
     * the governor refusing a permit because somebody else is using the module -- the whole request
     * fails and nothing is stored. A half-finished comparison is the worst thing this table could
     * hold: one format's number, sitting alone, looking like a result. A refused run is also not a
     * slow run, and quietly dropping one would shorten the sample while leaving the count looking
     * full.
     *
     * @throws AnalyticsException with a sentence written for the caller, as everywhere else here
     */
    public List<BenchmarkResult> run(BenchmarkRequest request) throws AnalyticsException {
        if (request == null) {
            throw new AnalyticsException("There is no benchmark to run.");
        }
        if (!this.onlyOneAtATime.tryAcquire()) {
            throw new AnalyticsException("A benchmark is already running. Only one runs at a time, "
                + "because two would take a second share of the query slots every other read on "
                + "this deployment is waiting for -- and because measurements taken while another "
                + "benchmark is competing for those slots are not measurements of the data.");
        }
        try {
            return this.runExclusively(request);
        } finally {
            this.onlyOneAtATime.release();
        }
    }

    /** The benchmark itself, with the one-at-a-time guarantee already held by the caller. */
    private List<BenchmarkResult> runExclusively(BenchmarkRequest request) throws AnalyticsException {
        // Unreachable while the endpoint sits at PLATFORM_ADMIN, and here anyway because the row's
        // tenant is taken from the context: a caller with neither a tenant nor the platform role
        // would write a null tenant_id, which this table reads as platform-owned. Refusing is the
        // same fail-closed reading TenantOwnership settled for the whole application.
        if (TenantContext.getTenantId() == null && !TenantContext.isPlatformAdmin()) {
            throw new AnalyticsException("A benchmark needs a signed-in caller to attribute it to.");
        }
        String label = trimmed(request.getLabel());
        if (label == null) {
            throw new AnalyticsException("Give the benchmark a label, so the numbers can be "
                + "placed later -- \"orders-1m\", say.");
        }
        if (label.length() > MAX_LABEL_LENGTH) {
            throw new AnalyticsException("A benchmark label is at most " + MAX_LABEL_LENGTH
                + " characters.");
        }

        String measure = this.measureKind(request.getMeasure());
        String sql = trimmed(request.getSql());
        if (BenchmarkResult.MEASURE_QUERY.equals(measure) && sql == null) {
            // No default, and the reason is the whole point of the harness. The obvious default,
            // "SELECT count(*) FROM dataset", is answered from Parquet's footer without reading a
            // row and from every byte of a CSV: it would manufacture an enormous Parquet win that
            // says nothing about reading data, in the one tool built to check that claim.
            throw new AnalyticsException("A query benchmark needs the statement to measure. There "
                + "is no default on purpose: count(*) is answered from Parquet's file footer "
                + "without reading a row, so it would report a difference that is not about "
                + "reading data.");
        }
        if (BenchmarkResult.MEASURE_FILE_OPEN.equals(measure) && sql != null) {
            throw new AnalyticsException("A file-open benchmark runs no statement you wrote. "
                + "Remove the SQL, or measure QUERY instead.");
        }

        List<BenchmarkTarget> targets = this.targetsOf(request.getDatasets());
        int runs = this.measuredRuns(request.getRuns());
        int warmups = warmupRuns(request.getWarmups());
        int sessionsPerRun = BenchmarkResult.MEASURE_FILE_OPEN.equals(measure)
            ? FILE_OPEN_SESSIONS : QUERY_SESSIONS;

        // Checked before a single dataset is opened, so a request too big to run costs nothing.
        int projected = targets.size() * (SHAPE_SESSIONS + (warmups + runs) * sessionsPerRun);
        if (projected > MAX_PROJECTED_SESSIONS) {
            throw new AnalyticsException("That benchmark would open " + projected
                + " analytics sessions against a ceiling of " + MAX_PROJECTED_SESSIONS
                + ", one after another, each holding a governor slot that other queries are "
                + "waiting for. Use fewer datasets, fewer runs, or fewer warmups.");
        }

        // Measured before the timer ever starts, and recorded on the row: a benchmark whose most
        // explanatory columns -- rows, columns, bytes -- came from the request would be a benchmark
        // reporting what somebody believed about the file.
        for (BenchmarkTarget target : targets) {
            this.readShape(target);
        }

        for (int warmup = 0; warmup < warmups; warmup++) {
            for (BenchmarkTarget target : targets) {
                this.measureOnce(target, measure, sql);
            }
        }
        for (int run = 0; run < runs; run++) {
            for (BenchmarkTarget target : targets) {
                target.samples.add(this.measureOnce(target, measure, sql));
            }
        }

        String batchId = UUID.randomUUID().toString();
        String limitsAtRun = this.limitsAtRun();
        List<BenchmarkResult> rows = new ArrayList<>(targets.size());
        for (BenchmarkTarget target : targets) {
            rows.add(row(target, batchId, label, measure, sessionsPerRun, sql, warmups,
                limitsAtRun));
        }
        // One write, after every measurement has succeeded. See the all-or-nothing paragraph above.
        List<BenchmarkResult> saved = this.benchmarkResultRepository.saveAll(rows);
        logger.info("Analytics benchmark {} ({}) measured {} dataset(s), {} run(s) after {} "
            + "discarded warmup(s), for tenant {}", label, measure, targets.size(), runs, warmups,
            TenantContext.getTenantId());
        return saved;
    }

    /** The sentence a caller should be shown beside any set of these numbers. */
    public static String whatThisDidNotMeasure() {
        return WHAT_THIS_DID_NOT_MEASURE;
    }

    /**
     * Measurements already taken: one batch, one label, or the most recent of everything.
     *
     * The window is clamped rather than taken as given, the same call the run history makes:
     * nothing prunes this table, so it is the reads that stay bounded.
     */
    @Transactional(readOnly = true)
    public List<BenchmarkResult> recentResults(String batchId, String label, Integer limit) {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        int window = limit == null || limit < 1 ? DEFAULT_RESULT_LIMIT
            : Math.min(limit, MAX_RESULT_LIMIT);
        PageRequest page = PageRequest.of(0, window);

        List<BenchmarkResult> found;
        if (trimmed(batchId) != null) {
            found = this.benchmarkResultRepository
                .findByBatchIdOrderByAnalyticsBenchmarkResultIdAsc(trimmed(batchId), page);
        } else if (trimmed(label) != null) {
            found = this.benchmarkResultRepository
                .findByBenchmarkLabelOrderByDateCreatedDescAnalyticsBenchmarkResultIdDesc(
                    trimmed(label), page);
        } else {
            found = this.benchmarkResultRepository
                .findAllByOrderByDateCreatedDescAnalyticsBenchmarkResultIdDesc(page);
        }

        // After the database has already filtered, and in a correct system this removes nothing.
        // It is here because the filter is enabled by a call the next person to write a listing
        // can forget, and because a benchmark row names a connection alias and a path -- which is
        // to say, where a workspace keeps its data. A short page is a better outcome than a
        // complete one belonging to somebody else.
        List<BenchmarkResult> owned = new ArrayList<>(found.size());
        for (BenchmarkResult result : found) {
            if (TenantOwnership.isOwnedByCaller(result.getTenantId())) {
                owned.add(result);
            }
        }
        this.userNameResolver.attachNames(owned);
        return owned;
    }

    // ---- what gets measured ---------------------------------------------------------------------

    /**
     * One timed run, through the same path a request takes.
     *
     * The clock starts before the governor permit is asked for and stops after the last row has
     * been marshalled, because that whole interval is what a user waits through. A harness that
     * timed only the engine would report a number nobody experiences and would hide the very cost
     * -- the session -- that this benchmark exists to make visible.
     *
     * Nanoseconds, rounded once to milliseconds at the end: a read that takes 600 microseconds
     * should record 1 rather than 0. A zero here would mean the read finished inside the clock's
     * resolution, which against an object store does not happen and against a local stand-in does.
     */
    private long measureOnce(BenchmarkTarget target, String measure, String sql)
        throws AnalyticsException {
        long startedAt = System.nanoTime();
        if (BenchmarkResult.MEASURE_FILE_OPEN.equals(measure)) {
            // Exactly what the Studio issues on a file click, in the same order, with no
            // knownTotal -- because a user opening a file has no total to carry forward. Three
            // sessions, and that is the honest cost of the thing being measured.
            this.analyticsQueryService.schemaOf(target.dataset);
            this.analyticsQueryService.preview(target.dataset, 0, null, null);
        } else {
            this.analyticsQueryService.query(target.dataset, null, sql);
        }
        return Math.round((System.nanoTime() - startedAt) / 1_000_000d);
    }

    /**
     * Rows, columns and bytes, read once and outside the sample.
     *
     * Outside because it is not what is being measured, and once because it does not change
     * between runs. It costs two governed sessions per dataset, which the projection above counts.
     */
    private void readShape(BenchmarkTarget target) throws AnalyticsException {
        DatasetSchemaDto schema = this.analyticsQueryService.schemaOf(target.dataset);
        target.columnCount = schema.getColumns() == null ? null : schema.getColumns().size();
        target.rowCount = this.analyticsQueryService.rowCount(target.dataset);
        target.datasetBytes = this.bytesOf(target);
    }

    /**
     * How big the object is, or null when that cannot be established honestly.
     *
     * Null for a multi-file dataset, because a glob names no single object to ask about and
     * summing a listing would be a second, differently-wrong storage walk. Null too when the store
     * declines to answer -- a benchmark must not fail because a size lookup did, and a wrong size
     * would be worse than an absent one, since the whole reason this column exists is to tell a
     * format win apart from a smaller file.
     */
    private Long bytesOf(BenchmarkTarget target) {
        DatasetRef dataset = target.dataset;
        if (dataset.isMultiFile()) {
            return null;
        }
        try {
            // The ALIAS, not dataset.getBucket(). StorageBrowserService's first parameter is named
            // "bucket" and is resolved with findByAliasAndStatus (StorageBrowserServiceImpl :530),
            // so it is a connection alias; DatasetRef.getBucket() is the bucket NAME off the
            // record. The two are equal in this environment and are not equal in general --
            // BucketRewritingStorageService exists precisely because they can differ. Passing the
            // bucket name found nothing by alias and quietly recorded dataset_bytes = null, whose
            // documented meaning is "this was a glob", so a correctly configured single-file
            // benchmark would have looked like a folder one. Worse, a bucket name that happens to
            // match some OTHER connection's alias resolves through that connection instead.
            ObjectMetadataDto metadata = this.storageBrowserService
                .getObjectMetadata(target.connectionAlias, dataset.getPath());
            return metadata == null ? null : metadata.getSize();
        } catch (Exception ex) {
            logger.warn("Benchmark could not read the size of {}: {}", dataset, ex.getMessage());
            return null;
        }
    }

    // ---- turning samples into a row --------------------------------------------------------------

    /**
     * The stored row, built from what was observed rather than from what was asked for.
     *
     * The tenant and the author come from the signed-in context, as everywhere else in this module:
     * "record this benchmark into another workspace" is not a request this method can be made to
     * honour, whatever arrives on the wire.
     */
    private static BenchmarkResult row(BenchmarkTarget target, String batchId, String label,
        String measure, int sessionsPerRun, String sql, int warmups, String limitsAtRun) {

        List<Long> sorted = new ArrayList<>(target.samples);
        Collections.sort(sorted);

        BenchmarkResult result = new BenchmarkResult();
        result.setTenantId(TenantContext.isPlatformAdmin() ? null : TenantContext.getTenantId());
        result.setBatchId(batchId);
        result.setBenchmarkLabel(label);
        result.setMeasureKind(measure);
        result.setMeasuredWhat(BenchmarkResult.MEASURE_FILE_OPEN.equals(measure)
            ? FILE_OPEN_MEANS : QUERY_MEANS);
        result.setSessionsPerRun(sessionsPerRun);
        result.setConnectionAlias(target.connectionAlias);
        result.setDatasetPath(target.dataset.getPath());
        result.setDatasetFormat(target.dataset.getFormat().name());
        result.setQueryText(sql);
        result.setRowCount(target.rowCount);
        result.setColumnCount(target.columnCount);
        result.setDatasetBytes(target.datasetBytes);
        result.setWarmupRuns(warmups);
        result.setMeasuredRuns(target.samples.size());
        result.setMinMs(sorted.get(0));
        result.setMedianMs(medianOf(sorted));
        result.setMaxMs(sorted.get(sorted.size() - 1));
        result.setMeanMs(meanOf(sorted));
        // In the order they ran, not sorted: a sequence that got steadily faster is a warming
        // cache, and that is only visible if the order survives.
        result.setRunDurationsMs(joined(target.samples));
        result.setLimitsAtRun(limitsAtRun);
        result.setDateCreated(new Timestamp(System.currentTimeMillis()));
        return result;
    }

    /** The middle of a sorted sample; the mean of the two middles when there is no single one. */
    private static long medianOf(List<Long> sorted) {
        int size = sorted.size();
        int middle = size / 2;
        if (size % 2 == 1) {
            return sorted.get(middle);
        }
        return Math.round((sorted.get(middle - 1) + sorted.get(middle)) / 2d);
    }

    private static long meanOf(List<Long> samples) {
        long total = 0;
        for (Long sample : samples) {
            total += sample;
        }
        return Math.round((double) total / samples.size());
    }

    private static String joined(List<Long> samples) {
        StringBuilder joined = new StringBuilder();
        for (Long sample : samples) {
            if (joined.length() > 0) {
                joined.append(',');
            }
            joined.append(sample);
        }
        return joined.toString();
    }

    /**
     * The limits this measurement ran under, as one line.
     *
     * Recorded because a query result stops at the row ceiling, so two rows measured under
     * different ceilings are not comparable -- and once somebody edits a properties file the
     * ceiling that applied is not recoverable from anything else.
     */
    private String limitsAtRun() {
        return "maxRows=" + this.limits.getMaxRows()
            + ", previewPageSize=" + this.limits.getPreviewPageSize()
            + ", timeoutSeconds=" + this.limits.getTimeoutSeconds()
            + ", maxConcurrent=" + this.limits.getMaxConcurrentQueries()
            + ", duckdbMemoryLimit=" + this.limits.getMemoryLimit()
            + ", duckdbThreads=" + this.limits.getThreads();
    }

    // ---- validation ------------------------------------------------------------------------------

    /**
     * Which measurement, and no default.
     *
     * The absence of a default is the confound handled at the door. A harness that quietly picked
     * one would produce rows whose numbers differ by a factor nobody could account for afterwards,
     * and the refusal below is where a caller learns that the two are different questions before
     * they have a table full of answers to the wrong one.
     */
    private String measureKind(String requested) throws AnalyticsException {
        String measure = trimmed(requested);
        if (measure != null) {
            measure = measure.toUpperCase(Locale.ROOT);
            if (BenchmarkResult.MEASURE_FILE_OPEN.equals(measure)
                || BenchmarkResult.MEASURE_QUERY.equals(measure)) {
                return measure;
            }
        }
        throw new AnalyticsException("Say what to measure. FILE_OPEN times a file open as the "
            + "Studio performs one -- the schema, the row count and the first page, three "
            + "sessions -- and QUERY times one statement you write, in one session. They differ "
            + "by more than the file format does, so there is no default.");
    }

    /**
     * The datasets, each resolved before anything is opened.
     *
     * Resolving all of them first means a request naming one connection the caller cannot reach
     * generates no load at all, rather than benchmarking the readable half and then refusing.
     * Every one goes through {@link DatasetResolver} on its own, so a caller cannot reach a
     * connection through a benchmark that they could not have opened by asking for it.
     */
    private List<BenchmarkTarget> targetsOf(List<BenchmarkDataset> requested)
        throws AnalyticsException {
        if (requested == null || requested.isEmpty()) {
            throw new AnalyticsException("Name at least one dataset to measure. Two -- the same "
                + "data as CSV and as Parquet -- is what makes it a comparison.");
        }
        if (requested.size() > MAX_DATASETS) {
            throw new AnalyticsException("A benchmark measures at most " + MAX_DATASETS
                + " datasets at once, so that the rows it writes were all taken minutes apart on "
                + "the same machine.");
        }
        List<BenchmarkTarget> targets = new ArrayList<>(requested.size());
        for (BenchmarkDataset dataset : requested) {
            if (dataset == null) {
                throw new AnalyticsException("One of the datasets says nothing about what to read.");
            }
            String alias = trimmed(dataset.getConnection());
            DatasetRef resolved = this.datasetResolver.resolve(alias, dataset.getPath());
            targets.add(new BenchmarkTarget(alias, resolved));
        }
        return targets;
    }

    private int measuredRuns(Integer requested) throws AnalyticsException {
        if (requested == null) {
            return DEFAULT_RUNS;
        }
        if (requested < MIN_RUNS) {
            // Refused rather than raised, because raising it would generate load the caller did
            // not ask for. Fewer than three runs cannot report a spread, and a duration with no
            // spread beside it is a single sample of a JIT-compiled JVM against a network store.
            throw new AnalyticsException("A benchmark needs at least " + MIN_RUNS
                + " measured runs. Fewer cannot show how far the runs sat apart, and a single "
                + "number with no spread beside it is not a measurement.");
        }
        // Clamped rather than refused in this direction: the caller asked for more load than the
        // ceiling allows, and measured_runs records what actually happened.
        return Math.min(requested, MAX_RUNS);
    }

    private static int warmupRuns(Integer requested) {
        if (requested == null) {
            return DEFAULT_WARMUPS;
        }
        return Math.min(Math.max(0, requested), MAX_WARMUPS);
    }

    private static String trimmed(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    // ---- the request, and one dataset's measurement in progress ----------------------------------

    /**
     * What a benchmark request says.
     *
     * A typed shape rather than a loose map, because a benchmark's parameters are exactly the
     * facts that end up on the row and a misspelled key silently becoming a default is how a
     * measurement acquires a caption that is not true of it. Nested here rather than in the DTO
     * package because nothing else in the application sends or receives one.
     */
    public static final class BenchmarkRequest {

        private String label;
        private String measure;
        private String sql;
        private Integer runs;
        private Integer warmups;
        private List<BenchmarkDataset> datasets;

        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }

        public String getMeasure() { return measure; }
        public void setMeasure(String measure) { this.measure = measure; }

        public String getSql() { return sql; }
        public void setSql(String sql) { this.sql = sql; }

        public Integer getRuns() { return runs; }
        public void setRuns(Integer runs) { this.runs = runs; }

        public Integer getWarmups() { return warmups; }
        public void setWarmups(Integer warmups) { this.warmups = warmups; }

        public List<BenchmarkDataset> getDatasets() { return datasets; }
        public void setDatasets(List<BenchmarkDataset> datasets) { this.datasets = datasets; }
    }

    /**
     * One dataset to measure: a connection alias and a path inside it.
     *
     * The same two fields every other endpoint in this module takes, and for the same reason --
     * there is no bucket and no URL here, so "measure a different bucket with these credentials"
     * is not a request a benchmark can express either.
     */
    public static final class BenchmarkDataset {

        private String connection;
        private String path;

        public BenchmarkDataset() {}

        public BenchmarkDataset(String connection, String path) {
            this.connection = connection;
            this.path = path;
        }

        public String getConnection() { return connection; }
        public void setConnection(String connection) { this.connection = connection; }

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }

    /** One dataset's measurement while it is being taken: the resolved location and its samples. */
    private static final class BenchmarkTarget {

        private final String connectionAlias;
        private final DatasetRef dataset;
        private final List<Long> samples = new ArrayList<>();

        private Long rowCount;
        private Integer columnCount;
        private Long datasetBytes;

        private BenchmarkTarget(String connectionAlias, DatasetRef dataset) {
            // The alias as the caller named it, kept beside the resolved reference: the row stores
            // the alias, never the bucket the resolver found behind it.
            this.connectionAlias = connectionAlias;
            this.dataset = dataset;
        }
    }
}

package process.analytics;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import process.analytics.dto.DatasetPreviewDto;
import process.analytics.dto.DatasetProfileDto;
import process.analytics.dto.DatasetSchemaDto;
import process.analytics.dto.QueryResultDto;

import javax.annotation.PreDestroy;

/**
 * The door the rest of the application knocks on to have analytics run something.
 *
 * <b>This class used to be the engine.</b> Everything it did is now in
 * {@link DuckDbAnalyticsEngine}, behind {@link AnalyticsEngine}, because 05 asks for an engine
 * abstraction and an abstraction is worth nothing if the DuckDB-shaped code sits on both sides of
 * it. What is left here is the name four services already inject -- AnalyticsRestApi,
 * AnalyticsExportService, AnalyticsBenchmarkService and AnalyticsLibraryRestApi's javadoc all say
 * "goes through AnalyticsQueryService" -- and the one operation that is not an engine operation at
 * all, which is stopping a run.
 *
 * <b>The rule those javadocs state is unchanged, and is worth restating in the words the code now
 * uses.</b> Every analytics query goes through the governed path: the locked-down
 * DuckDbSessionFactory session, the fair semaphore, the enforced timeout. That path is
 * DuckDbAnalyticsEngine's, there is exactly one bean holding it, and it is still the only thing in
 * this application that opens a DuckDB connection. A query path that opened its own connection
 * would be a second door beside the locked one, and moving the lock into a named class does not
 * add a door -- it names the one there is.
 *
 * <b>Why this stays rather than the callers moving to AnalyticsEngine.</b> Three of the four are
 * owned elsewhere and were being edited while this refactor ran. Pointing them at the interface is
 * a one-line change each and should happen; until it does, this class is where the seam is
 * absorbed, which is the whole reason the seam cost so little to introduce.
 *
 * @author Nabeel Ahmed
 */
@Service
public class AnalyticsQueryService {

    /**
     * The names a user's SQL calls the datasets by.
     *
     * Re-exported from {@link AnalyticsEngine} rather than redefined, because AnalyticsExportService
     * composes SQL against them and two copies of a contract with a person is one copy too many.
     */
    public static final String DATASET = AnalyticsEngine.DATASET;

    public static final String SECOND_DATASET = AnalyticsEngine.SECOND_DATASET;

    private final AnalyticsEngine engine;
    private final RunningQueries running;

    @Autowired
    public AnalyticsQueryService(AnalyticsEngine engine, RunningQueries running) {
        this.engine = engine;
        this.running = running;
    }

    /**
     * The same service wired to a DuckDB engine it builds itself.
     *
     * For callers that hold a session factory and a limits bean and want the module's default
     * arrangement -- which is every test in the analytics suite. It names DuckDB on purpose: a
     * convenience constructor that pretended to be engine-neutral while hard-wiring one engine
     * would be exactly the lie {@link AnalyticsEngine}'s javadoc warns about.
     */
    public AnalyticsQueryService(DuckDbSessionFactory sessions, AnalyticsLimits limits) {
        this(new RunningQueries(), sessions, limits);
    }

    private AnalyticsQueryService(RunningQueries running, DuckDbSessionFactory sessions,
        AnalyticsLimits limits) {
        this(new DuckDbAnalyticsEngine(sessions, limits, running), running);
    }

    /** The engine this service hands its work to. */
    public AnalyticsEngine getEngine() {
        return this.engine;
    }

    /** The dataset's columns and their inferred types, without reading its rows. */
    public DatasetSchemaDto schemaOf(DatasetRef dataset) throws AnalyticsException {
        return this.engine.schemaOf(dataset);
    }

    /** One page of rows, counted and bounded. */
    public DatasetPreviewDto preview(DatasetRef dataset, int page, Integer requestedSize)
        throws AnalyticsException {
        return this.preview(dataset, page, requestedSize, null);
    }

    /** The same page, with the caller allowed to say what the total already is. */
    public DatasetPreviewDto preview(DatasetRef dataset, int page, Integer requestedSize,
        Integer knownTotal) throws AnalyticsException {
        return this.engine.preview(dataset, page, requestedSize, knownTotal);
    }

    /** How many rows the dataset holds, across every file when the path is a pattern. */
    public long rowCount(DatasetRef dataset) throws AnalyticsException {
        return this.engine.rowCount(dataset);
    }

    /** Per-column statistics and the quality flags derived from them, from one scan. */
    public DatasetProfileDto profileOf(DatasetRef dataset) throws AnalyticsException {
        return this.engine.profileOf(dataset);
    }

    /** A query a person wrote, under a run id nobody outside can address. */
    public QueryResultDto query(DatasetRef primary, DatasetRef secondary, String sql)
        throws AnalyticsException {
        return this.engine.query(primary, secondary, sql, null);
    }

    /**
     * The same query, answering to an id the caller chose.
     *
     * @param requestedRunId the id a stop request will name, or null to mint one
     */
    public QueryResultDto query(DatasetRef primary, DatasetRef secondary, String sql,
        String requestedRunId) throws AnalyticsException {
        return this.engine.query(primary, secondary, sql, requestedRunId);
    }

    /** The same query, written into the caller's own connection's bucket instead of returned. */
    public long copyTo(DatasetRef primary, DatasetRef secondary, String sql, DatasetRef target)
        throws AnalyticsException {
        return this.engine.copyTo(primary, secondary, sql, target);
    }

    /** The bounded form of a read: the same statement, capped at analytics.query.max-rows. */
    public String bounded(String sql) throws AnalyticsException {
        return this.engine.bounded(sql);
    }

    /**
     * Stops the caller's own running query.
     *
     * Not an engine method, and {@link AnalyticsEngine}'s javadoc says why: a cancel acts on a
     * {@code java.sql.Statement}, so it is JDBC-shaped rather than engine-neutral. It is here
     * because this class is the module's front door and a stop request is a request about a query,
     * not about a dataset.
     *
     * A run that is not the caller's, and a run that finished a moment ago, both answer
     * NOT_RUNNING. {@link RunningQueries#cancel(String)} carries the reasoning for that sameness.
     */
    public RunningQueries.Outcome cancel(String runId) {
        return this.running.cancel(runId);
    }

    /** How many runs are in flight right now, across every tenant. */
    public int runningCount() {
        return this.running.size();
    }

    /**
     * Stops the engine's watchdog thread when the application does.
     *
     * Kept on this class as well as on the engine because the analytics tests build a service and
     * shut it down, and because a bean that owns another bean's lifecycle should say so.
     */
    @PreDestroy
    public void shutdown() {
        if (this.engine instanceof DuckDbAnalyticsEngine) {
            ((DuckDbAnalyticsEngine) this.engine).shutdown();
        }
    }
}

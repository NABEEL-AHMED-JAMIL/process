package process.analytics;

import process.analytics.dto.ColumnDto;
import process.analytics.dto.DatasetPreviewDto;
import process.analytics.dto.DatasetProfileDto;
import process.analytics.dto.DatasetSchemaDto;
import process.analytics.dto.QueryResultDto;

import java.util.Collections;
import java.util.List;

/**
 * The six things Analytics Studio asks an execution engine to do, and the only names the rest of
 * the module needs in order to ask.
 *
 * <b>Read the next three paragraphs before treating this as portability.</b> This interface is a
 * seam, not a promise that a second engine is a drop-in. It is here because 05 asks for one and
 * because the alternative -- DuckDB named directly in every caller -- makes the question "what
 * would a different engine have to provide" unanswerable. What it buys is that the callers
 * (AnalyticsRestApi, AnalyticsExportService, AnalyticsBenchmarkService, AnalyticsQueryService)
 * name an operation and a {@link DatasetRef} and nothing else: no session, no SQL dialect, no
 * driver. Swapping the implementation is a change to one bean, not to five files.
 *
 * <b>What is irreducibly DuckDB behind this interface</b>, listed so that nobody costs a second
 * engine by counting method signatures:
 *
 * <ul>
 *   <li><b>The scan.</b> {@link DatasetRef#scanExpression()} emits read_csv_auto, read_parquet and
 *       read_json_auto with union_by_name and filename -- DuckDB table functions, not SQL.</li>
 *   <li><b>The statement gate.</b> {@link StatementGate} decides "is this a read" by handing the
 *       text to json_serialize_sql, which is DuckDB's own parser answering about its own dialect.
 *       There is no portable form of that question; another engine would need its own parser and
 *       its own evidence, and the security argument would have to be made again from scratch.</li>
 *   <li><b>The lock-down.</b> {@link DuckDbSessionFactory} sets disabled_filesystems and
 *       lock_configuration and attaches credentials with CREATE SECRET. Those three pragmas are
 *       the whole reason a user may write SQL here at all. An engine without an equivalent is not
 *       a smaller change; it is a different security case.</li>
 *   <li><b>The profile's shape.</b> {@link DatasetProfileDto} is a row-for-row copy of what
 *       SUMMARIZE returns, including that approx_unique is HyperLogLog and q25/q50/q75 are
 *       approximate. The field names say "approx" because the ENGINE is approximate; another
 *       engine's profile would have different accuracy and the screen would have to say so.</li>
 *   <li><b>The bound and the copy.</b> {@link #bounded(String)} wraps a statement in a subquery
 *       with a LIMIT, and {@link #copyTo} composes COPY ... TO with DuckDB writer options.</li>
 *   <li><b>The failure vocabulary.</b> Engine messages are mapped to sentences by matching
 *       DuckDB's own error classes ("Parser Error:", "Binder Error:", "Catalog Error:").</li>
 *   <li><b>Cancellation.</b> Deliberately NOT on this interface -- see below.</li>
 * </ul>
 *
 * <b>What is genuinely engine-independent</b> and is what this seam actually protects: the DTOs,
 * {@link DatasetRef} and the rule that a caller names a storage connection rather than a URL; the
 * governor's shape (one permit, one timeout, one row ceiling); and the tenancy rule that a dataset
 * only exists once DatasetResolver has produced it.
 *
 * <b>Why cancellation is not a method here.</b> A cancel acts on a handle, and the handle this
 * module can actually hold is a {@code java.sql.Statement} -- {@link RunningQueries} says so in
 * its own type. That makes cancellation JDBC-shaped rather than engine-neutral: any JDBC engine
 * fits it unchanged, and an engine that is not JDBC would bring a handle of its own and
 * RunningQueries would have to be generalised with it. Putting {@code cancel(String)} here would
 * have implied the opposite, so it lives on {@link AnalyticsQueryService} and on RunningQueries,
 * where the JDBC assumption is visible.
 *
 * @author Nabeel Ahmed
 */
public interface AnalyticsEngine {

    /**
     * The name the dataset a user picked answers to inside their own SQL.
     *
     * On the interface rather than on an implementation because it is part of the contract with
     * the PERSON: "SELECT ... FROM dataset", with no bucket, no scan function and no URL anywhere
     * in it. A second engine that renamed this would break every saved query in the library.
     */
    String DATASET = "dataset";

    String SECOND_DATASET = "dataset2";

    /**
     * Where a run is in its life, in the vocabulary 05 specifies plus the one it did not.
     *
     * The seventh is REFUSED, and the reasoning for keeping it is written once, on
     * {@link process.model.pojo.AnalyticsQueryRun#STATUS_REFUSED}, which is where the same
     * vocabulary is persisted. The short version: a statement that never ran because the gate or
     * the governor turned it away is not a failure of the engine and is the single most
     * interesting row the history table holds. Folding it into FAILED to match a list of six
     * would delete a security signal to tidy up an enum.
     *
     * An enum here and Strings on the entity, deliberately. This is the in-memory state machine,
     * where the compiler should be checking the transitions; that is a column shared with a
     * screen and a JSON payload, where adding a name should not be a schema change.
     */
    enum RunState {
        /** Waiting for a governor permit. Short by design -- see AnalyticsQueryService's ceiling. */
        QUEUED,
        /** Holding a permit with a statement open on the engine. */
        RUNNING,
        /** Ran and returned. Persisted as SUCCESS, which is the name the table already holds. */
        COMPLETED,
        /** Reached the engine and failed there. */
        FAILED,
        /** Stopped because the person who started it asked for it to stop. */
        CANCELLED,
        /** Stopped by the watchdog at analytics.query.timeout-seconds. */
        TIMED_OUT,
        /** Never reached the engine: the gate or the governor turned it away. */
        REFUSED
    }

    /**
     * A failure that already knows which state the run ended in.
     *
     * It exists because the caller writing the history row cannot otherwise tell a timeout from a
     * refusal from an engine error -- all three arrive as an {@link AnalyticsException} with a
     * sentence in it, and before this the controller guessed, recording a timed-out query as
     * REFUSED. Guessing from the message text would be the same bug with more string matching.
     *
     * Still an AnalyticsException, so every existing catch keeps working and the message is still
     * the sentence written for the reader. A plain AnalyticsException from anywhere else --
     * StatementGate, DatasetResolver, the governor -- means REFUSED, which is what those throws
     * have always meant.
     */
    class RunFailure extends AnalyticsException {

        private final RunState state;

        public RunFailure(RunState state, String message) {
            super(message);
            this.state = state;
        }

        public RunState getState() {
            return this.state;
        }
    }

    /**
     * A statement with every VALUE in it held outside the text.
     *
     * <b>This type is the mechanical half of 07's "do not construct raw SQL by string concatenation
     * with untrusted values", and it exists so that the rule is enforced by the shape of the call
     * rather than remembered at each site.</b> A composer that wanted to interpolate a value would
     * have to build a string and leave this list empty, which is visible in a diff; a composer that
     * binds cannot forget to, because the parameter has nowhere else to go.
     *
     * The other half of that sentence -- field NAMES, which SQL has no parameter for -- cannot be
     * solved here and is solved in {@code process.analytics.canvas.FilterCompiler.Columns}: every
     * identifier written into the text is a String taken from the dataset's OWN schema, never from
     * the request. That is why {@link Composer} is handed the columns rather than being trusted to
     * have looked them up.
     *
     * The SQL is a String, which is the same honest limit {@link #bounded(String)} carries: this
     * interface is a seam, not a dialect-neutral query language, and a second engine would need a
     * composer that knew its own dialect. Recorded rather than hidden.
     */
    final class BoundStatement {

        private final String sql;
        private final List<Object> parameters;

        public BoundStatement(String sql, List<Object> parameters) {
            this.sql = sql;
            this.parameters = parameters == null
                ? Collections.emptyList() : Collections.unmodifiableList(parameters);
        }

        /** The statement text. Contains a "?" for every value and no value of its own. */
        public String getSql() {
            return this.sql;
        }

        /** The values, in the order their placeholders appear. Already typed for their columns. */
        public List<Object> getParameters() {
            return this.parameters;
        }
    }

    /**
     * Turns a dataset's own columns into the statement to run against it.
     *
     * A callback rather than a prepared string, because the composer needs the schema and reading
     * the schema costs a session and a governor permit. Handing the ENGINE the composer lets both
     * happen inside one session on one permit -- the same argument profileOf makes for Profile and
     * Quality being one scan, applied to an analysis and the DESCRIBE that validates its fields.
     * An analysis that resolved its schema through a separate {@link #schemaOf} call would cost two
     * of four permits per click.
     */
    @FunctionalInterface
    interface Composer {
        /**
         * @param columns the dataset's own columns, read inside the session the statement will run
         *                in, and the only allow-list an identifier may come from
         */
        BoundStatement composeFor(List<ColumnDto> columns) throws AnalyticsException;
    }

    /** The dataset's columns and their types, without reading its rows. */
    DatasetSchemaDto schemaOf(DatasetRef dataset) throws AnalyticsException;

    /**
     * One page of rows, counted and bounded.
     *
     * @param knownTotal a total the caller already holds, so a page turn need not count again;
     *                   null or non-positive counts
     */
    DatasetPreviewDto preview(DatasetRef dataset, int page, Integer requestedSize, Integer knownTotal)
        throws AnalyticsException;

    /** How many rows the dataset holds, across every file when the path is a pattern. */
    long rowCount(DatasetRef dataset) throws AnalyticsException;

    /** Per-column statistics and the quality flags derived from them, from one scan. */
    DatasetProfileDto profileOf(DatasetRef dataset) throws AnalyticsException;

    /** A validated read a person wrote, run under every limit the built-in reads run under. */
    default QueryResultDto query(DatasetRef primary, DatasetRef secondary, String sql)
        throws AnalyticsException {
        return this.query(primary, secondary, sql, null);
    }

    /**
     * The same read, with the caller naming the id the run will answer to.
     *
     * @param requestedRunId an id the caller chose so it can cancel this run before the response
     *                       carrying a server-minted one gets back to it; null to have one minted
     */
    QueryResultDto query(DatasetRef primary, DatasetRef secondary, String sql, String requestedRunId)
        throws AnalyticsException;

    /**
     * A structured analysis, composed against the dataset's own schema and run with its values bound.
     *
     * The third way into the governed path and the only one whose SQL nobody typed. /preview and
     * /profile build their own statement from a scan expression; /query takes a person's text
     * through {@link StatementGate}; this takes a MODEL -- dimensions, a measure, filters -- and
     * turns it into a statement here, inside the session, once the columns are known.
     *
     * <b>Everything the other two get, this gets.</b> One permit, one locked-down session, one
     * timeout, one registry entry that a stop button can reach, and the row ceiling from
     * {@link #bounded(String)}. It also goes through the statement gate, which is not redundant on
     * SQL the server composed: the gate is what proves the composed text is exactly ONE read that
     * names nothing but the bound dataset view, so a field name that had smuggled a location past
     * the schema allow-list would still be refused at the parser.
     *
     * @param composer given the dataset's columns, returns the statement and its bound values
     * @param requestedRunId the id a stop request will name, or null to mint one
     */
    QueryResultDto analyze(DatasetRef dataset, Composer composer, String requestedRunId)
        throws AnalyticsException;

    /**
     * The same read, written into the caller's own connection's bucket instead of returned.
     *
     * @param target where to write, which must be the primary dataset's own connection
     * @return the number of rows the engine reported writing
     */
    long copyTo(DatasetRef primary, DatasetRef secondary, String sql, DatasetRef target)
        throws AnalyticsException;

    /**
     * The bounded form of a read: the same statement, unable to return more than
     * analytics.query.max-rows rows.
     *
     * On the interface because callers assert against it and because the row ceiling is policy
     * rather than dialect -- but the STRING it returns is SQL, which is the honest limit of how
     * far this particular method travels.
     */
    String bounded(String sql) throws AnalyticsException;
}

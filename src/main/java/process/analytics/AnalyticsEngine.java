package process.analytics;

import process.analytics.canvas.FilterClause;
import process.analytics.dto.ColumnDto;
import process.analytics.dto.DatasetPreviewDto;
import process.analytics.dto.DatasetProfileDto;
import process.analytics.dto.DatasetSchemaDto;
import process.analytics.dto.QueryResultDto;

import java.util.ArrayList;
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
    /**
     * Releases whatever the engine holds between requests. Nothing, by default.
     *
     * On the interface because AnalyticsQueryService owns the engine's lifecycle and used to reach
     * for it with an `instanceof DuckDbAnalyticsEngine` -- which meant the seam this interface
     * exists to provide covered every method EXCEPT the one that stops the background thread. A
     * second engine would have been constructed, used, and then quietly never shut down.
     *
     * A default of "nothing" rather than an abstract method: an engine with nothing to release is
     * a reasonable engine, and making every implementation write an empty body to say so is how a
     * seam acquires ceremony.
     */
    default void shutdown() {
        // Nothing held.
    }

    class RunFailure extends AnalyticsException {

        private final RunState state;
        private final boolean retryable;

        public RunFailure(RunState state, String message) {
            this(state, message, false);
        }

        /**
         * @param retryable whether running exactly this again could plausibly succeed.
         *
         * <b>True only for a failure of the TRANSPORT, never of the request.</b> A refused
         * credential, a missing key, a malformed CSV and a statement the gate would not admit all
         * fail identically the second time, and retrying them turns one clear error into two
         * charged to the same governor. A connection reset or a 503 from the object store is a
         * different thing: nothing about the request was wrong.
         *
         * Defaults to false at every existing call site, which is the safe direction -- a failure
         * nobody has classified is one nobody has shown to be safe to repeat.
         */
        public RunFailure(RunState state, String message, boolean retryable) {
            super(message);
            this.state = state;
            this.retryable = retryable;
        }

        public RunState getState() {
            return this.state;
        }

        public boolean isRetryable() {
            return this.retryable;
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

    /**
     * What a grid asks of a page beyond which page it is: an order, a narrowing, or both.
     *
     * <b>It carries {@link FilterClause}, which lives in the canvas package, and that direction of
     * dependency is deliberate.</b> A grid filter and a canvas filter are the same problem -- a
     * predicate over the dataset's own columns with the user's values in it -- and the module can
     * afford exactly one answer to it. A second filter model here would be a second place to get
     * binding and the field allow-list wrong, and the two would drift the first time an operator
     * was added to one of them. So the grid speaks the canvas's filter language rather than a
     * dialect of it, and {@code FilterCompiler} is the only thing that turns either into SQL.
     *
     * <b>Sorting and searching are the engine's work, not the browser's.</b> A page is one window
     * onto a dataset that may hold millions of rows; sorting that window in the client sorts a
     * hundred rows and presents the answer with the same confidence as the right one, which is the
     * most convincing wrong answer this screen could give.
     *
     * Everything here is optional and an absent field means "do not". A shape with nothing set is
     * the preview that existed before this type did, and the engine takes the same path for it.
     */
    final class PreviewShape {

        /** Which way the sorted column runs. Two values, so an ordering keyword cannot be typed. */
        public enum Direction {
            ASC,
            DESC
        }

        /**
         * How long a search term may be.
         *
         * The term is compared against every text cell of every row, so its length is multiplied by
         * the size of the file. Past a couple of hundred characters it also cannot match anything a
         * person would recognise as a cell, and it is arriving on a GET, which means it is being
         * written into the access log of everything between the browser and here.
         */
        private static final int MAX_SEARCH_LENGTH = 256;

        private final String sort;
        private final Direction direction;
        private final String search;
        private final List<FilterClause> filters;

        /**
         * Whether the total the caller is carrying was counted under a filter.
         *
         * This closes the OTHER half of the knownTotal trap, and it is the half that hides data
         * rather than inventing it. Refusing knownTotal while narrowing stops a filtered page being
         * paginated as the whole file. But CLEARING a filter is not narrowing -- so the request
         * takes the trusting branch while the client is still holding the FILTERED total from the
         * response before it. The pager then offers thirteen pages of a dataset with two and a half
         * thousand, and the file appears to have permanently shrunk the moment the filter came off.
         *
         * A server cannot tell one number from another by looking at it, so the caller says where
         * it came from. Only a total counted with nothing narrowing may be reused, and in the
         * non-narrowing case there is exactly one correct answer, so that single bit is complete
         * rather than merely helpful.
         */
        private final boolean knownTotalFiltered;

        public PreviewShape(String sort, Direction direction, String search,
            List<FilterClause> filters) throws AnalyticsException {

            this(sort, direction, search, filters, false);
        }

        public PreviewShape(String sort, Direction direction, String search,
            List<FilterClause> filters, boolean knownTotalFiltered) throws AnalyticsException {

            this.knownTotalFiltered = knownTotalFiltered;
            this.sort = trimmedOrNull(sort);
            this.direction = direction;
            this.search = trimmedOrNull(search);
            if (this.search != null && this.search.length() > MAX_SEARCH_LENGTH) {
                throw new AnalyticsException("A search term may be up to " + MAX_SEARCH_LENGTH
                    + " characters.");
            }
            this.filters = filters == null || filters.isEmpty()
                ? Collections.<FilterClause>emptyList()
                : Collections.unmodifiableList(new ArrayList<FilterClause>(filters));
        }

        /** The column to order by, as the caller spelled it, or null. Resolved against the schema. */
        public String getSort() {
            return this.sort;
        }

        /**
         * Which way to order, ascending unless the caller said otherwise.
         *
         * Ascending is the default because the first click on a grid header is: A before Z, oldest
         * first, smallest first. It is only consulted when a sort column was named.
         */
        public Direction getDirection() {
            return this.direction == null ? Direction.ASC : this.direction;
        }

        /** The free text to look for in every text column, or null. */
        public String getSearch() {
            return this.search;
        }

        /** The conditions the caller sent, never null and possibly empty. */
        public List<FilterClause> getFilters() {
            return this.filters;
        }

        /**
         * The conditions as one tree, or null when there are none.
         *
         * ANDed, because a grid's filter chips read as "and": three chips narrow three times. The
         * wire shape is a flat array and this is the only place that decides what joining them
         * means -- and a caller that wants an OR still has one, because an element of the array may
         * itself be a group.
         */
        public FilterClause filterTree() {
            if (this.filters.isEmpty()) {
                return null;
            }
            return FilterClause.group(FilterClause.LogicalOp.AND, this.filters);
        }

        /**
         * Whether this shape removes rows, as opposed to only reordering them.
         *
         * <b>The single most consequential question this type answers</b>, because it is what
         * decides whether a total the caller carried forward is still a total of anything. A sort
         * moves rows; a filter or a search changes how many there are.
         */
        /** Whether a carried total was counted under a filter, and so cannot be reused. */
        public boolean isKnownTotalFiltered() {
            return this.knownTotalFiltered;
        }

        public boolean isNarrowing() {
            return this.search != null || !this.filters.isEmpty();
        }

        /** Whether an order was asked for. */
        public boolean isOrdered() {
            return this.sort != null;
        }

        /** Whether this asks for nothing at all, which is the preview that existed before. */
        public boolean isEmpty() {
            return !this.isOrdered() && !this.isNarrowing();
        }

        private static String trimmedOrNull(String raw) {
            if (raw == null) {
                return null;
            }
            String trimmed = raw.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }

    /** The dataset's columns and their types, without reading its rows. */
    DatasetSchemaDto schemaOf(DatasetRef dataset) throws AnalyticsException;

    /**
     * One page of rows, counted and bounded, in the order the file has.
     *
     * @param knownTotal a total the caller already holds, so a page turn need not count again;
     *                   null or non-positive counts
     */
    default DatasetPreviewDto preview(DatasetRef dataset, int page, Integer requestedSize,
        Integer knownTotal) throws AnalyticsException {
        return this.preview(dataset, page, requestedSize, knownTotal, null);
    }

    /**
     * The same page, ordered and narrowed by what a grid asked for.
     *
     * <b>totalRows counts what the shape left</b>, not what the file holds, and that is not a
     * detail: totalRows is what draws the pager, so a filtered page beside an unfiltered count
     * offers pages that do not exist. The response says which of the two it is -- see
     * {@link DatasetPreviewDto#isFiltered()} -- so a screen can report "1,204 of 250,000" rather
     * than implying the dataset is small.
     *
     * @param knownTotal a total the caller already holds; IGNORED whenever the shape narrows, for
     *                   the reason above -- a total carried across a filter change is a count of
     *                   something else
     * @param shape the order and the narrowing, or null for neither
     */
    DatasetPreviewDto preview(DatasetRef dataset, int page, Integer requestedSize,
        Integer knownTotal, PreviewShape shape) throws AnalyticsException;

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

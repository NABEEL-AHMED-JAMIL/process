package process.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import process.analytics.canvas.FilterCompiler;
import process.analytics.dto.ColumnDto;
import process.analytics.dto.ColumnProfileDto;
import process.analytics.dto.DatasetPreviewDto;
import process.analytics.dto.DatasetProfileDto;
import process.analytics.dto.DatasetSchemaDto;
import process.analytics.dto.QueryResultDto;
import process.security.TenantContext;

import javax.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The only place in this application where an analytics query runs, and the only class that knows
 * it is DuckDB that runs it.
 *
 * Everything here was AnalyticsQueryService until the engine seam was introduced, and moving it
 * rather than wrapping it is the point: {@link AnalyticsEngine} is worth having only if the
 * DuckDB-shaped code is on ONE side of it. AnalyticsQueryService is now the name the rest of the
 * module injects and it hands every call straight here; the governed path -- the fair semaphore,
 * the locked-down session, the timeout -- did not change, it only stopped being anonymous.
 *
 * Every path in and out is narrow on purpose. Callers hand in a DatasetRef, which can only have
 * come from DatasetResolver and therefore has already passed the tenant and path checks. The
 * built-in reads -- schema, preview, count, profile -- build their own SQL here from a scan
 * expression the DatasetRef produced, so there is exactly one place to read when the question is
 * "what can this feature execute".
 *
 * That narrowness is what made SQL Studio possible, and query() is where it landed. User-written
 * SQL arrives at THIS class, against a session already locked down by DuckDbSessionFactory, under
 * the same semaphore and the same timeout, rather than opening a second door beside this one --
 * through StatementGate, which decides whether the statement is a read at all, and through
 * bounded(), which is where analytics.query.max-rows is enforced and the only place it is. A
 * query path that skips bounded() is a query with no row ceiling, whatever the property says it
 * is set to, and one that skips the gate is a session whose only remaining defence is that the
 * filesystem was taken away from it.
 *
 * The user never names a location. query() binds each dataset to a view -- "dataset", and
 * "dataset2" for a join -- so the SQL a person writes contains a NAME where the URL would have
 * been, and DatasetResolver stays the only thing in the module that turns a request into somewhere
 * readable.
 *
 * <b>Every run is registered before it starts.</b> {@link RunningQueries} holds the in-flight set,
 * and a run is opened before the permit is asked for -- so a caller waiting on the governor is
 * QUEUED, a caller with a statement open is RUNNING, and both can be stopped. The registration is
 * closed in a finally, on all five ways out, because a registry that keeps an entry per query it
 * has ever seen is a memory leak with a tenant id in it.
 *
 * <b>copyTo() is the one method here that writes, and the module's design assumed for three phases
 * that no such method would exist.</b> It is here rather than in the export service because the
 * rule it has to keep is this class's rule: a statement that writes is composed HERE, around a read
 * StatementGate has already admitted, and never anywhere a caller can reach. The gate cannot admit
 * a COPY -- json_serialize_sql answers "Only SELECT statements can be serialized to json!", so a
 * user's own COPY is refused as unserialisable like any other write -- which means the only COPY
 * this application can run is one this file built. The destination is a DatasetRef, so it came from
 * DatasetResolver and is the caller's own connection's bucket, and lockDown() is untouched: the
 * write leaves through the S3 secret, and a local path is still refused by the engine itself.
 *
 * On why this is not a connection pool: a DuckDB session is per query and closed with it. The
 * in-memory catalogue and the attached credentials die with it, so one caller's dataset cannot
 * be visible to another's, and a query that wedges cannot poison a pooled connection for the
 * next caller. The cost is a fresh session per request, which is milliseconds against a scan
 * measured in hundreds.
 *
 * @author Nabeel Ahmed
 */
@Service
public class DuckDbAnalyticsEngine implements AnalyticsEngine {


    /**
     * The furthest a page may start.
     *
     * A page beyond the end of a dataset is empty rather than an error, so this does not need to
     * know how long the file is -- it only needs to stop the arithmetic wrapping. Ten billion rows
     * is past anything this engine will page through and far short of where a long overflows.
     */
    private static final long MAX_OFFSET = 10_000_000_000L;

    private static final Logger logger = LoggerFactory.getLogger(DuckDbAnalyticsEngine.class);

    /**
     * How long a caller waits for a slot before being turned away.
     *
     * Short by design. The point of the ceiling is to fail fast while the user is still looking
     * at the screen, not to build a queue that turns one slow query into a slow application.
     *
     * It is also the whole of QUEUED. 05 names a queued state and this module refuses rather than
     * queues, so the state exists for these two seconds and no longer -- long enough to be a real
     * thing a run can be stopped in, far too short to be the asynchronous submission the spec's
     * lifecycle assumes. That gap is recorded rather than papered over; see the result report.
     */
    private static final long SLOT_WAIT_SECONDS = 2;

    /**
     * Anything shaped like a URL, so an engine message can be returned without carrying one.
     *
     * The scan expression this class builds interpolates the dataset's s3:// or azure:// URL into
     * the SQL, and DuckDB's parser errors quote the statement back. Credentials never appear --
     * DuckDbSessionFactory attaches them with CREATE SECRET precisely so they cannot -- but the
     * location does, and the only engine messages that leave this class are the ones that would
     * have carried it.
     */
    private static final Pattern LOCATION = Pattern.compile("[a-zA-Z][a-zA-Z0-9+.\\-]*://[^\\s'\"()]*");

    /**
     * How much of an engine message is worth returning.
     *
     * A parser error on a long statement quotes the whole statement back. Past a couple of lines
     * the useful part -- what is wrong and roughly where -- has already been said.
     */
    private static final int ENGINE_MESSAGE_LIMIT = 400;

    /** Percentages arrive from SUMMARIZE as DECIMAL(9,2) and are turned back into counts here. */
    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    /**
     * How close an ESTIMATED distinct count has to sit to the row count before a column is called
     * key-like.
     *
     * Loose because the estimator is. approx_unique is HyperLogLog, and over a million genuinely
     * distinct values it was measured returning 962,761 -- 3.7% low. A 1% threshold would therefore
     * have refused to flag a real primary key on any file large enough for the question to matter,
     * which is the failure that costs a user something; the failure this threshold accepts instead
     * is flagging a column that has a few duplicates in it, which the word "like" already admits.
     */
    private static final BigDecimal KEY_LIKE_RATIO = new BigDecimal("0.95");

    /**
     * The date shapes a VARCHAR column is tested against before it is called a date surprise.
     *
     * Strict, so that 13/13/2024 is not a date. Both slash orderings are here because min and max
     * cannot tell us which convention wrote the file, and both are required to parse under the SAME
     * formatter, so a pair like 02/03/2024 and 30/06/2024 is only accepted by the reading that
     * works for both of them.
     */
    private static final List<DateTimeFormatter> DATE_SHAPES = Arrays.asList(
        DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("uuuu/MM/dd").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("M/d/uuuu").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT));

    /** How a TIMESTAMP is written down. See asTimestamp for why neither default would do. */
    private static final DateTimeFormatter TIMESTAMP_SHAPE =
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    /** The fraction, printed only when the value has one, at DuckDB's own microsecond precision. */
    private static final DateTimeFormatter FRACTION_SHAPE = DateTimeFormatter.ofPattern("SSSSSS");

    private final DuckDbSessionFactory sessions;
    private final AnalyticsLimits limits;
    private final RunningQueries running;
    private final Semaphore slots;

    /**
     * The thread that stops a query the driver will not stop for us.
     *
     * Statement.setQueryTimeout is a NO-OP in duckdb_jdbc 1.1.3 -- the driver logs "not supported"
     * at FINE and returns -- so until this existed, analytics.query.timeout-seconds was a number
     * in a properties file and nothing else. That was survivable while every statement was built
     * here and known to terminate; it stopped being survivable the moment a user could write the
     * statement, because four queries that never finish take every permit the governor has and
     * the feature is then down until the process restarts.
     *
     * What the driver does implement is cancel(), which interrupts the running query on that
     * connection -- measured, it lands in about the millisecond after it is called, and the
     * statement throws "INTERRUPT Error: Interrupted!". explain() has always mapped an interrupt
     * to the timeout sentence.
     *
     * It now cancels through the run's {@link RunningQueries.Handle} rather than through a
     * Statement it holds itself, because a user pressing stop needs the same interruption under
     * the same lock. Two mechanisms for one cancel is how the two disagree about whether the
     * connection is still open.
     *
     * One thread for the whole application: these tasks call cancel() and nothing else.
     */
    private final ScheduledExecutorService watchdogs;

    public DuckDbAnalyticsEngine(DuckDbSessionFactory sessions, AnalyticsLimits limits,
        RunningQueries running) {
        this.sessions = sessions;
        this.limits = limits;
        this.running = running;
        // Fair, so a steady trickle of small queries cannot starve one that has been waiting.
        this.slots = new Semaphore(Math.max(1, limits.getMaxConcurrentQueries()), true);
        this.watchdogs = Executors.newSingleThreadScheduledExecutor(work -> {
            // Daemon: this thread must never be the reason the application will not shut down.
            Thread thread = new Thread(work, "analytics-query-timeout");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * The dataset's columns and their types, without reading its rows.
     *
     * DESCRIBE makes the reader infer the schema from as little of the file as it needs, so this
     * stays cheap on a large Parquet file and on a folder of a thousand CSVs alike.
     */
    @Override
    public DatasetSchemaDto schemaOf(DatasetRef dataset) throws AnalyticsException {
        List<ColumnDto> columns = this.run(dataset,
            "DESCRIBE SELECT * FROM " + dataset.scanExpression(),
            resultSet -> {
                List<ColumnDto> found = new ArrayList<>();
                while (resultSet.next()) {
                    found.add(new ColumnDto(resultSet.getString("column_name"),
                        resultSet.getString("column_type")));
                }
                return found;
            });
        return new DatasetSchemaDto(dataset.getBucket(), dataset.getPath(),
            dataset.getFormat().name(), dataset.isMultiFile(), columns);
    }

    /**
     * One page of rows, counted and bounded, in whatever order the reader hands them over or in
     * the one a grid asked for.
     *
     * <b>Two paths, and the split is not laziness.</b> A preview with no shape is the same two
     * statements it has always been -- a count and a page over the scan expression -- and it stays
     * that way because it is the call every file open makes and it needs no schema. A shaped
     * preview cannot be composed without the dataset's own columns, because the columns ARE the
     * allow-list a sort column and a filter field are validated against, so it takes analyze()'s
     * route instead: one session, the dataset bound to a view, a DESCRIBE, then the statements
     * composed from what the DESCRIBE returned.
     *
     * That route costs LESS concurrency rather than more. The unshaped path opens two sessions and
     * takes two of the four governor permits when it has no total to carry forward, because
     * rowCount() is its own call; everything below happens on one.
     *
     * The rest of the reasoning is on the pieces: {@link #whereOf} for the filter and the search,
     * {@link #orderOf} for the sort, and {@link #totalFor} for why a total the caller sent is
     * refused as soon as anything narrows.
     */
    @Override
    public DatasetPreviewDto preview(DatasetRef dataset, int page, Integer requestedSize,
        Integer knownTotal, PreviewShape shape) throws AnalyticsException {

        int size = this.pageSize(requestedSize);
        // long, and clamped. page arrives unvalidated and size is clamped only to max-rows, so
        // page=21475 with a page size of 100,000 overflowed int and produced a NEGATIVE offset --
        // measured, "OFFSET -2147467296", which DuckDB refuses with "LIMIT/OFFSET cannot be
        // negative" and explain() turns into a sentence about the file rather than about the
        // request. It failed closed, so nothing was ever read wrongly; but the reader was told
        // something untrue about their data to describe a page number that cannot exist.
        long offset = Math.min((long) Math.max(0, page) * size, MAX_OFFSET);
        if (shape == null || shape.isEmpty()) {
            // A total counted under a filter is dropped even here, where the request itself carries
            // no filter. This branch is exactly what a grid hits when the reader CLEARS one: the
            // shape is empty again, but the number the client is echoing was counted while the
            // filter was on. Passing it through makes the file appear to have shrunk -- pages that
            // exist stop being offered, which is worse than the trap the shaped path closes, where
            // the surplus pages were at least visibly empty.
            Integer carried = shape != null && shape.isKnownTotalFiltered() ? null : knownTotal;
            return this.unshapedPreview(dataset, page, size, offset, carried);
        }
        return this.shapedPreview(dataset, page, size, offset, knownTotal, shape);
    }

    /**
     * The preview as it was before a grid asked for anything: a count and a page.
     *
     * The count is a separate query rather than the size of the page, because a reader wants to
     * know how much there is before deciding whether to page through it, and because count(*)
     * over Parquet is answered from metadata rather than by reading the file.
     *
     * A non-positive or absent knownTotal counts, so the first request for a dataset -- the one
     * that has nothing to carry forward -- behaves exactly as it always did. The value is not
     * validated against the dataset because it cannot be: checking it would be the count. It is
     * a number the client already displayed, echoed back for the client to display again, and
     * the worst a wrong one does is misdraw a page control. That last sentence stops being true
     * the moment a filter is involved, which is why the shaped path does not repeat it -- and why
     * the caller above drops a total whose own shape says it was counted under one, even when the
     * request that arrives here carries no filter at all.
     */
    private DatasetPreviewDto unshapedPreview(DatasetRef dataset, int page, int size, long offset,
        Integer knownTotal) throws AnalyticsException {

        long total = knownTotal != null && knownTotal > 0 ? knownTotal : this.rowCount(dataset);

        // Ordering is deliberately absent HERE. Object storage has no natural row order to promise,
        // and an ORDER BY on a page nobody asked to sort would sort the whole dataset to return a
        // hundred rows. A caller that does ask pays for it knowingly, in shapedPreview.
        //
        // Bounded even though the page size is already clamped, because the two are different
        // policies that happen to agree today: the clamp says how big a PAGE may be, and the
        // bound says how big a RESULT may be. Phase three's result is not a page.
        String sql = this.bounded("SELECT * FROM " + dataset.scanExpression()
            + " LIMIT " + size + " OFFSET " + offset);

        List<ColumnDto> columns = new ArrayList<>();
        List<List<String>> rows = this.run(dataset, sql, resultSet -> rowsOf(resultSet, columns));

        return new DatasetPreviewDto(namesOf(columns), rows, page, size, total,
            dataset.isMultiFile(), false);
    }

    /**
     * A page a grid ordered, narrowed, or both, composed against the dataset's own schema.
     *
     * <b>The order inside this session is the security argument and it is analyze()'s order, for
     * analyze()'s reason.</b> The view is created first, then DESCRIBEd, and only then is anything
     * composed -- so the sort column and every filter field are checked against a list of names the
     * dataset has just produced, rather than against a list somebody remembered to look up. A name
     * that is not in that list never reaches the text.
     *
     * <b>Everything the other governed reads get, this gets.</b> One permit, one locked-down
     * session, one timeout, one registry entry a stop button can reach, the row ceiling from
     * bounded(), and {@link StatementGate#confirmComposed} on both composed statements -- which is
     * not theatre on SQL this class wrote. Its job here is to make DuckDB's own parser confirm that
     * each is exactly ONE read naming nothing but the bound view, so a field name that had somehow
     * carried a location past the schema allow-list would land in table_name, schema_name or
     * catalog_name and be refused there as well.
     *
     * <b>Values are bound and never interpolated</b>, by {@code FilterCompiler} for the filters and
     * by {@link #whereOf} for the search term. The count and the page carry the SAME parameter list
     * in the same order, because they carry the same predicate -- which is also what makes the
     * count a count of the rows the page is a page of, rather than a number that merely arrived
     * with them.
     */
    private DatasetPreviewDto shapedPreview(DatasetRef dataset, int page, int size, long offset,
        Integer knownTotal, PreviewShape shape) throws AnalyticsException {

        int timeout = this.limits.getTimeoutSeconds();
        return this.inSession(dataset, (session, statement) -> {
            statement.execute(viewOf(DATASET, dataset));

            List<ColumnDto> schema = new ArrayList<>();
            try (ResultSet described = statement.executeQuery("DESCRIBE SELECT * FROM " + DATASET)) {
                while (described.next()) {
                    schema.add(new ColumnDto(described.getString("column_name"),
                        described.getString("column_type")));
                }
            }
            FilterCompiler.Columns columns = FilterCompiler.Columns.of(schema);

            List<Object> parameters = new ArrayList<>();
            String where = whereOf(shape, columns, parameters);
            String order = orderOf(shape, columns);

            long total = this.totalFor(session, statement, shape, where, parameters, knownTotal,
                timeout);

            String sql = this.bounded("SELECT * FROM " + DATASET + where + order
                + " LIMIT " + size + " OFFSET " + offset);
            StatementGate.confirmComposed(session, sql, timeout);

            List<ColumnDto> columnsRead = new ArrayList<>();
            List<List<String>> rows;
            try (PreparedStatement prepared = session.prepareStatement(sql)) {
                prepared.setQueryTimeout(timeout);
                bind(prepared, parameters);
                try (ResultSet resultSet = prepared.executeQuery()) {
                    rows = rowsOf(resultSet, columnsRead);
                }
            }
            return new DatasetPreviewDto(namesOf(columnsRead), rows, page, size, total,
                dataset.isMultiFile(), shape.isNarrowing());
        });
    }

    /**
     * How many rows the pager has to page through, which is not always a number worth trusting a
     * caller for.
     *
     * <b>A knownTotal is refused outright as soon as the shape narrows, and this is the whole of a
     * defect that would otherwise be almost impossible to catch.</b> The short-circuit was added so
     * a page turn need not re-count, and a page turn is exactly the request a grid sends after the
     * user has changed a filter: the client is holding a total from the response BEFORE the filter,
     * echoes it back out of habit, and gets 1,204 rows paginated as 250,000. Every page renders,
     * every page number is clickable, and the pages past the real end are empty -- which reads as a
     * broken filter rather than as a wrong count. There is no way to validate the number that is
     * cheaper than the count it exists to skip, so the only safe reading of it is that it describes
     * whatever the caller last saw, and once a filter has changed, what the caller last saw is a
     * different dataset.
     *
     * A sort keeps the short-circuit. Ordering rows does not change how many there are, so a total
     * carried across a header click is still the same total -- and a grid whose every sort cost a
     * full count would make sorting the most expensive thing on the screen.
     *
     * The count runs on the session already open, so an unshaped preview's second session and
     * second permit are saved even in the case that counts.
     */
    private long totalFor(Connection session, Statement statement, PreviewShape shape, String where,
        List<Object> parameters, Integer knownTotal, int timeout)
        throws AnalyticsException, SQLException {

        if (!shape.isNarrowing()) {
            // Not narrowing NOW is not the same as the carried total having been counted without a
            // filter. Clearing a filter lands here while the client still holds the filtered total,
            // and honouring it makes the dataset appear to shrink -- pages that exist stop being
            // offered, which is worse than the trap above, where the extra pages were at least
            // visibly empty. The caller says where its number came from; only an unfiltered one is
            // reusable, and in this branch there is exactly one correct total, so that is enough.
            if (knownTotal != null && knownTotal > 0 && !shape.isKnownTotalFiltered()) {
                return knownTotal;
            }
            return this.countMatching(session, statement, "", Collections.emptyList(), timeout);
        }
        return this.countMatching(session, statement, where, parameters, timeout);
    }

    /**
     * count(*) over the view, under the same predicate and the same bound values as the page.
     *
     * Not bounded(), for rowCount()'s reason: a count returns one row whatever the dataset is, and
     * wrapping it would add a subquery for nothing. Gated, for confirmComposed()'s reason: it is a
     * composed statement and the parser is the only thing entitled to say it is one read.
     *
     * The plain Statement is used when there is nothing to bind, so an unnarrowed count costs no
     * prepare -- and the prepared path exists only when a value has to stay outside the text.
     */
    private long countMatching(Connection session, Statement statement, String where,
        List<Object> parameters, int timeout) throws AnalyticsException, SQLException {

        String sql = "SELECT count(*) FROM " + DATASET + where;
        StatementGate.confirmComposed(session, sql, timeout);
        if (parameters.isEmpty()) {
            try (ResultSet counted = statement.executeQuery(sql)) {
                return counted.next() ? counted.getLong(1) : 0L;
            }
        }
        try (PreparedStatement prepared = session.prepareStatement(sql)) {
            prepared.setQueryTimeout(timeout);
            bind(prepared, parameters);
            try (ResultSet counted = prepared.executeQuery()) {
                return counted.next() ? counted.getLong(1) : 0L;
            }
        }
    }

    /**
     * The WHERE clause a grid asked for: the caller's own conditions, and the search box.
     *
     * <b>The conditions go through {@code FilterCompiler} unchanged and untouched.</b> That class
     * is the module's answer to "a predicate over a dataset with a user's values in it": values
     * become placeholders, field names come from the dataset's own DESCRIBE and are emitted in the
     * SCHEMA's spelling, and the operator vocabulary is the fourteen the Canvas already uses. A
     * grid filter is the same problem, so it gets the same compiler rather than a second one --
     * a second implementation is a second place to get injection wrong, and the two would drift
     * the first time an operator was added to one of them. Its ceilings come along too: 8 levels of
     * nesting, 200 conditions, 500 operands in an IN.
     *
     * <b>The search is NOT one of those fourteen and is composed here, which is a smaller
     * exception than it looks.</b> The nearest operator is CONTAINS and it is case-SENSITIVE by
     * design, while a search box that misses "Oslo" because the user typed "oslo" is a search box
     * that does not work. The alternative considered was a fifteenth, case-insensitive operator on
     * FilterClause -- rejected because that enum is the Canvas's public wire vocabulary, and
     * widening a contract with every existing client to serve a predicate no Canvas user can build
     * is a larger change than this one. What is reused is the part that matters: the identifiers
     * are {@code FilterCompiler.Columns}, so they are the same allow-list quoted the same way, and
     * the term is a bound parameter, so there is no escaping here either.
     *
     * <b>Every text column is searched, and the cost of that is measured rather than avoided.</b>
     * "Matches any text column" is an OR of contains over every VARCHAR in the file, evaluated per
     * row, and on a term that is nowhere in the file there is no early exit from either the page or
     * the count. Measured against the 1,500,000-row benchmark CSV in MinIO
     * (analytics-benchmark/sales-100mb.csv, three VARCHAR columns of six), 512MB session, warm:
     *
     * <ul>
     *   <li>unshaped page of 100, no count: <b>~140 ms</b></li>
     *   <li>count(*) with no predicate: <b>~360 ms</b></li>
     *   <li>count(*) under the search, term absent: <b>~400 ms</b> -- the predicate costs about
     *       40 ms over 1.5M rows, because parsing the CSV dominates it</li>
     *   <li>the page under the search, term absent: <b>~385 ms</b>, a full scan, since LIMIT 100
     *       cannot stop early when nothing matches</li>
     * </ul>
     *
     * So a searched page is roughly 790 ms against a cold unshaped page's 500 ms -- and it is 790
     * ms on ONE governor permit where the unshaped pair takes two. The cost grows with the number
     * of text columns, not with their width.
     *
     * Searching fewer columns would make it fast by making it a different feature, and a grid that
     * silently skipped a column would be a search box that answers "not found" about data that is
     * there. What bounds it instead is what bounds every other read here: one permit, the memory
     * ceiling on the session, and the query timeout. The term's own length is capped by
     * {@link PreviewShape}. What is deliberately NOT bounded here is a minimum term length: a
     * one-character search is a legitimate question, and the request that should not be sent on
     * every keystroke is a decision for the client's debounce rather than a refusal from here.
     *
     * contains() rather than LIKE, for the reason FilterCompiler gives at its own CONTAINS: LIKE
     * would make the user's own % and _ into wildcards, so a search for "50%" would match "50"
     * followed by anything, and the usual fix is the escaping neither class does.
     */
    private static String whereOf(PreviewShape shape, FilterCompiler.Columns columns,
        List<Object> parameters) throws AnalyticsException {

        StringBuilder where = new StringBuilder();
        String joiner = " WHERE ";
        BoundStatement filters = new FilterCompiler(columns).compile(shape.filterTree());
        if (filters != null) {
            where.append(joiner).append(filters.getSql());
            parameters.addAll(filters.getParameters());
            joiner = " AND ";
        }
        if (shape.getSearch() != null) {
            where.append(joiner).append(searchOf(shape.getSearch(), columns, parameters));
        }
        return where.toString();
    }

    /**
     * The search box, as an OR of case-insensitive containment over every text column.
     *
     * lower() is applied by the ENGINE to both sides rather than by Java to the term, so one
     * implementation of case folding decides both. Folding the term here and the column there would
     * disagree on the first non-ASCII alphabet somebody searches in. It is also free: measured on
     * the 1.5M-row benchmark, lower(?) over a bound parameter and a term folded in Java before
     * binding came back at 523 ms and 526 ms, which is DuckDB folding the constant once rather
     * than per row.
     *
     * <b>A dataset with no text columns matches nothing, and says so with a count of zero rather
     * than with a refusal.</b> A search is a filter, and a filter that matches nothing returns
     * nothing; the response carries filtered=true and totalRows=0, so the screen reads "0 of
     * 250,000" -- which is true. Refusing instead would mean a search box that works on one file
     * and throws on the next, which is a worse thing to hand a person than an empty result.
     */
    private static String searchOf(String term, FilterCompiler.Columns columns,
        List<Object> parameters) {

        StringBuilder sql = new StringBuilder();
        String joiner = "";
        for (ColumnDto column : columns.all()) {
            if (column == null || !FilterCompiler.Columns.isText(column)) {
                continue;
            }
            sql.append(joiner).append("contains(lower(")
                .append(FilterCompiler.Columns.quote(column)).append("), lower(?))");
            parameters.add(term);
            joiner = " OR ";
        }
        if (sql.length() == 0) {
            // Not a tautology in disguise: it is the honest predicate for "look in the text columns"
            // asked of a file that has none, and it is written out so the SQL in a log says so.
            return "false";
        }
        return "(" + sql + ")";
    }

    /**
     * ORDER BY, over one column the dataset itself named.
     *
     * <b>A sort column is an identifier, so it cannot be bound and has to be written into the
     * text.</b> It is validated exactly as the Canvas validates a dimension --
     * {@code Columns.require} against the schema the DESCRIBE just returned, which resolves the
     * dataset's OWN spelling of the name and refuses anything the file did not declare -- and then
     * quoted, because a CSV header is allowed to say "order date" or a"b. The quoting makes such a
     * name usable; the allow-list is what makes it safe, and neither is asked to do the other's job.
     *
     * <b>NULLS LAST, in both directions, and the direction-independence is the point.</b> A missing
     * value is not the largest value and it is not the smallest one, so a null that led an
     * ascending sort would read as the minimum and one that led a descending sort would read as the
     * maximum -- the same cell claiming to be both, depending on which arrow the user clicked. Last
     * in both directions makes nulls a place rather than a claim, and it keeps the first page of a
     * sort about the data: on a column that is a third empty, nulls first is a screen of blank
     * cells that says nothing about the file. It also matches what
     * {@code AnalysisQueryBuilder.appendOrderBy} already decided for the Canvas, so the two screens
     * do not disagree about where a null belongs.
     *
     * <b>What this deliberately does not promise is a stable page boundary.</b> ORDER BY over a
     * column with ties leaves the tied rows in an order DuckDB is free to choose, and it chooses per
     * execution -- so on a file with no unique column, paging through a sort by a low-cardinality
     * column can show a row twice or not at all. The fixes are a unique tiebreaker the file does not
     * have, or a full-width sort key that would multiply the cost of every sorted page; neither is
     * worth it for a preview, and the honest thing is to record it here rather than to have it
     * discovered on a duplicate row.
     */
    private static String orderOf(PreviewShape shape, FilterCompiler.Columns columns)
        throws AnalyticsException {

        if (!shape.isOrdered()) {
            return "";
        }
        ColumnDto column = columns.require(shape.getSort());
        if (!FilterCompiler.Columns.isOrderable(column)) {
            // The name echoed is the SCHEMA's, not the request's: require() has already resolved it
            // to a column the DESCRIBE returned, so this cannot hand a caller's own string back.
            throw new AnalyticsException("\"" + column.getName() + "\" holds " + column.getType()
                + ", which has no order, so the rows cannot be sorted by it.");
        }
        return " ORDER BY " + FilterCompiler.Columns.quote(column) + " "
            + shape.getDirection().name() + " NULLS LAST";
    }

    /**
     * How many rows the dataset holds, across every file when the path is a pattern.
     *
     * Not bounded: count(*) returns one row whatever the dataset is, and wrapping it would add a
     * subquery for nothing. Same for DESCRIBE in schemaOf, which returns a row per column and is
     * not a SELECT the wrapper below could legally contain anyway.
     */
    @Override
    public long rowCount(DatasetRef dataset) throws AnalyticsException {
        return this.run(dataset, "SELECT count(*) FROM " + dataset.scanExpression(),
            resultSet -> resultSet.next() ? resultSet.getLong(1) : 0L);
    }

    /**
     * A query a person wrote, run under every limit the rest of this class runs under.
     *
     * This is the door phase one spent its whole release building the lock for, and it is a door
     * in the same wall rather than a second one: the same DuckDbSessionFactory session, the same
     * fair semaphore, the same timeout, the same bounded(), the same explain(). What is added is
     * StatementGate, because none of those can tell a read from a write.
     *
     * The order inside the session is the argument. The statement is judged FIRST, so a COPY or an
     * ATTACH is refused before a dataset has been opened, let alone read -- a refusal costs a
     * parse and nothing else. The views come next, which is where the location the user never sees
     * gets bound to the name they type. Only then is anything executed.
     *
     * The second dataset is optional and, when present, has been through DatasetResolver on its
     * own. Two resolves rather than one is the whole tenancy property of a join: a caller who can
     * reach connection A and not connection B cannot reach B by joining to it, because the second
     * dataset passes or fails the same check the first one did, with the same answer it would have
     * given if B had been asked for by itself.
     *
     * <b>This is the only method that lets its caller name the run.</b> /query is synchronous, so
     * a server-minted id reaches the browser in the same response as the rows -- which is to say,
     * after there is anything left to cancel. A caller that intends to offer a stop button sends
     * the id it will cancel with; one that does not gets a minted id and a run nobody outside can
     * address. That asymmetry is a consequence of the request model, not a preference, and it is
     * the honest limit of cancellation until a query is a job with a handle of its own.
     *
     * @param secondary the dataset exposed as "dataset2", or null when the query reads only one
     * @param requestedRunId the id this run should answer to, or null to mint one
     */
    @Override
    public QueryResultDto query(DatasetRef primary, DatasetRef secondary, String sql,
        String requestedRunId) throws AnalyticsException {

        int timeout = this.limits.getTimeoutSeconds();
        RunningQueries.Handle handle = this.running.open(requestedRunId);
        QueryResultDto result = this.inSession(primary, handle, (session, statement) -> {
            StatementGate.Admitted admitted = StatementGate.admit(session, sql, timeout);

            statement.execute(viewOf(DATASET, primary));
            if (secondary != null) {
                statement.execute(viewOf(SECOND_DATASET, secondary));
            }

            String executable = admitted.getSql();
            if (admitted.isBoundable()) {
                executable = this.bounded(executable);
                // The gate again, on the string that is actually about to run rather than on the
                // one that was submitted. Wrapping cannot turn one statement into two today; this
                // is what keeps that true after somebody edits the wrapper.
                StatementGate.confirmComposed(session, executable, timeout);
            }

            List<ColumnDto> columns = new ArrayList<>();
            List<List<String>> rows;
            try (ResultSet resultSet = statement.executeQuery(executable)) {
                rows = rowsOf(resultSet, columns);
            }
            // A result that came back full to the ceiling is reported as truncated, including the
            // dataset that happens to hold exactly that many rows. The alternative is running the
            // query a second time without the bound to find out, which is the thing the bound is
            // for. An EXPLAIN is never truncated because it was never wrapped.
            boolean truncated = admitted.isBoundable() && rows.size() >= this.limits.getMaxRows();
            QueryResultDto answer = new QueryResultDto(namesOf(columns), rows, rows.size(), truncated);
            answer.setColumnMeta(columns);
            return answer;
        });
        // Set out here rather than in the lambda because the id and the elapsed time describe the
        // RUN, and the run is not over until the session that carried it has closed.
        result.setQueryId(handle.getId());
        result.setStatus(RunState.COMPLETED.name());
        result.setDurationMs(handle.elapsedMs());
        return result;
    }

    /**
     * A structured analysis: the schema read, the statement composed from it, and the values bound.
     *
     * <b>The order inside this session is the whole argument, and it is a different order from
     * query()'s for a reason that matters.</b> There the statement arrives first and is judged
     * before a dataset is opened, because the statement is the untrusted thing. Here the statement
     * does not exist yet: it is composed FROM the dataset, so the view comes first, then a DESCRIBE
     * of it, and only then does the composer get to write anything. That ordering is what makes the
     * field allow-list real -- the composer cannot name a column the dataset has not just told us
     * about, because it is handed the list rather than asked to trust one.
     *
     * <b>One session and one permit for both reads.</b> The DESCRIBE and the analysis run on the
     * same connection under the same slot, which is the argument profileOf already makes for
     * Profile and Quality. Resolving the schema through schemaOf() instead would take two of the
     * four permits this application has for every click of a canvas.
     *
     * <b>The gate runs on SQL this class composed, and that is not theatre.</b> Its job here is not
     * to ask whether a write got in -- nothing here can write -- but to make the parser confirm
     * that the composed text is exactly ONE statement and that every relation in it is a NAME. If a
     * field name ever carried a location past the schema allow-list, the identifier would land in
     * table_name, schema_name or catalog_name and readsOnlyWhatItWasGiven would refuse it. Phase
     * three's cross-bucket exploit went through the half of that check that did not exist; this is
     * the same check standing in front of a second composer.
     *
     * <b>Values are bound, never interpolated.</b> The statement is prepared and the composer's
     * parameters are set positionally, so a value cannot become syntax however it is spelled. The
     * count is checked against the driver's own view of the text first: a composer that emitted a
     * placeholder without a value, or the other way round, would otherwise run with a parameter
     * silently left unset -- measured on 1.1.3, that does not throw, it just answers wrongly.
     *
     * <b>Cancellation is left on the plain Statement deliberately.</b> The run is registered against
     * the Statement inSession() opened, not against the PreparedStatement executing below, because
     * duckdb_jdbc's cancel() interrupts the CONNECTION rather than one statement -- measured on
     * 1.1.3: cancelling the plain statement stopped a prepared statement running on the same
     * connection after 705 ms with "INTERRUPT Error: Interrupted!". Re-registering the prepared
     * statement would buy nothing and would leave a closed handle in the registry for the window
     * between its try-with-resources and inSession's finish().
     */
    @Override
    public QueryResultDto analyze(DatasetRef dataset, Composer composer, String requestedRunId)
        throws AnalyticsException {

        if (composer == null) {
            throw new AnalyticsException("There is no analysis to run.");
        }
        int timeout = this.limits.getTimeoutSeconds();
        RunningQueries.Handle handle = this.running.open(requestedRunId);
        QueryResultDto result = this.inSession(dataset, handle, (session, statement) -> {
            statement.execute(viewOf(DATASET, dataset));

            List<ColumnDto> schema = new ArrayList<>();
            try (ResultSet described = statement.executeQuery("DESCRIBE SELECT * FROM " + DATASET)) {
                while (described.next()) {
                    schema.add(new ColumnDto(described.getString("column_name"),
                        described.getString("column_type")));
                }
            }

            BoundStatement composed = composer.composeFor(schema);
            String executable = this.bounded(composed.getSql());
            StatementGate.confirmComposed(session, executable, timeout);

            try (PreparedStatement prepared = session.prepareStatement(executable)) {
                prepared.setQueryTimeout(timeout);
                bind(prepared, composed.getParameters());

                List<ColumnDto> columns = new ArrayList<>();
                List<List<String>> rows;
                try (ResultSet resultSet = prepared.executeQuery()) {
                    rows = rowsOf(resultSet, columns);
                }
                boolean truncated = rows.size() >= this.limits.getMaxRows();
                QueryResultDto answer = new QueryResultDto(namesOf(columns), rows, rows.size(),
                    truncated);
                answer.setColumnMeta(columns);
                return answer;
            }
        });
        result.setQueryId(handle.getId());
        result.setStatus(RunState.COMPLETED.name());
        result.setDurationMs(handle.elapsedMs());
        return result;
    }

    /**
     * Puts the composer's values into the prepared statement, having first agreed how many there are.
     *
     * The count check is the belt on a braces. The composer appends a placeholder and its value
     * together, so they cannot drift -- but "cannot" is a property of code somebody will edit, and
     * the failure it prevents is silent: DuckDB 1.1.3 executes a statement with an unset parameter
     * rather than refusing it, so the query returns an answer that is simply not the one that was
     * asked for. Asking the driver how many placeholders it found compares the composer's own
     * count against the parser's, which is the only second opinion available.
     */
    private static void bind(PreparedStatement prepared, List<Object> parameters)
        throws AnalyticsException, SQLException {

        int expected = prepared.getParameterMetaData().getParameterCount();
        if (expected != parameters.size()) {
            throw new AnalyticsException("That analysis could not be prepared safely, so it was "
                + "not run.");
        }
        for (int i = 0; i < parameters.size(); i++) {
            prepared.setObject(i + 1, parameters.get(i));
        }
    }

    /**
     * The same query, written into the caller's own connection's bucket instead of returned.
     *
     * Everything before the COPY is the read path unchanged: one session, one permit, one timeout,
     * StatementGate first so a write or a second statement is refused before a dataset is opened,
     * then the views, then bounded(). The only thing added is the last statement, and it is
     * composed here from three pieces a caller cannot supply -- the fixed word COPY, a read the
     * gate admitted, and a URL that came out of DatasetResolver.
     *
     * <b>The result is written whole or not at all, and that is why the row count is taken before
     * the write rather than after it.</b> Everywhere else in this module a result that hits the
     * ceiling comes back flagged truncated and the reader is told; an object in a bucket has
     * nowhere to carry that flag. Parquet has no comment, a CSV's trailing marker is a row somebody
     * will sum, and a file that is renamed or copied loses whatever its name was saying. So the
     * ceiling is tested first with a probe that stops at max-rows + 1, and a query that would
     * overflow is refused with nothing written. A partial object in a bucket is worse than no
     * object: nothing downstream can tell it from a complete one.
     *
     * The probe is not free and is not a full second scan either. It returns at most one more row
     * than the ceiling, so for a streaming read DuckDB stops early; only a query with a sort or an
     * aggregate in it pays close to the write's own cost, and that query was going to pay it twice
     * over anyway if it had been run and then exported.
     *
     * @param target where to write, which must be the primary dataset's own connection
     * @return the number of rows the engine reported writing
     */
    @Override
    public long copyTo(DatasetRef primary, DatasetRef secondary, String sql, DatasetRef target)
        throws AnalyticsException {

        String destination = this.writableUrl(primary, target);
        int timeout = this.limits.getTimeoutSeconds();
        int ceiling = this.limits.getMaxRows();

        return this.inSession(primary, (session, statement) -> {
            StatementGate.Admitted admitted = StatementGate.admit(session, sql, timeout);
            if (!admitted.isBoundable()) {
                // EXPLAIN is the only admission that is not boundable, and a plan is a description
                // of a query rather than an answer to one. Writing it would put a file in a bucket
                // that nothing downstream can read as data.
                throw new AnalyticsException("A query plan cannot be written to storage. "
                    + "Write the query's result instead of its EXPLAIN.");
            }

            statement.execute(viewOf(DATASET, primary));
            if (secondary != null) {
                statement.execute(viewOf(SECOND_DATASET, secondary));
            }

            long rows = this.countUpTo(session, statement, admitted.getSql(), ceiling, timeout);
            if (rows > ceiling) {
                throw new AnalyticsException("That query returns more than the " + ceiling
                    + " rows an export may write, so nothing was written. Narrow it with a WHERE "
                    + "or a LIMIT, or write it out in parts.");
            }

            String bounded = this.bounded(admitted.getSql());
            // The gate on the composed read, exactly as query() does it, and for the same reason:
            // what is about to be wrapped in a COPY has to be one read statement, and the parser
            // is the only thing entitled to say so.
            StatementGate.confirmComposed(session, bounded, timeout);

            String copy = "COPY (" + bounded + ") TO '" + destination.replace("'", "''") + "' "
                + copyOptions(target.getFormat());
            try {
                statement.execute(copy);
            } catch (SQLException ex) {
                // Mapped here rather than by explain(), which is written for a READ: it would tell
                // someone whose write was refused that the connection "was not allowed to read"
                // the bucket, and would name the source dataset for a failure at the destination.
                throw this.explainWrite(target, ex);
            }
            // DuckDB returns rows written as the update count -- measured on 1.1.3, 100 rows
            // reported 100 and an empty result reported 0. The probe's count is the fallback for a
            // driver that stops doing that, and the two agree by construction.
            long written = statement.getUpdateCount();
            if (written > rows) {
                // The one way this design can still put a short object in a bucket, closed here
                // rather than left as a caveat. The count and the write are two statements, and
                // the object store underneath them is not part of the session: a dataset that
                // grew past the ceiling in between would be written bounded, and every check
                // above would have passed. More rows written than were counted moments earlier is
                // proof that happened.
                //
                // A dataset that SHRANK in between goes uncaught, and that is deliberate rather
                // than a gap in the same check: the object then holds every row that was there
                // when it was written, which is all an export has ever been able to claim.
                //
                // The object is already there and cannot be unwritten from here, so the honest
                // answer is to say which one is suspect rather than to report a success.
                logger.error("An analytics export to {} wrote {} rows where {} were counted "
                    + "moments before; the dataset changed underneath it.", target, written, rows);
                throw new AnalyticsException("The dataset changed while it was being exported, so "
                    + "the file written to " + target.getPath() + " may be incomplete. Delete it "
                    + "and run the export again.");
            }
            if (written < 0) {
                // Fail closed, not open. This used to substitute the PROBE's count -- a number
                // from a different statement -- which also silently disabled the "grew underneath
                // us" check above it, since -1 > rows is false. Measured on 1.1.3 the driver does
                // report the count, so this is latent; but the honest answer when a write cannot
                // be confirmed is to say it cannot be confirmed. The object is already in the
                // bucket and cannot be unwritten from here, so name it rather than claim it.
                logger.error("An analytics export to {} completed without reporting how many rows "
                    + "it wrote; the engine's update count was {}.", target, written);
                throw new AnalyticsException("The export to " + target.getPath() + " finished, but "
                    + "the engine did not confirm how much it wrote, so it cannot be reported as "
                    + "complete. Check the file before relying on it.");
            }
            return written;
        });
    }

    /**
     * The destination URL, or a refusal, checked against the one rule that cannot be delegated.
     *
     * AnalyticsExportService allow-lists the key a user names, and that check is where a bad key is
     * caught with a sentence a person can act on. This one is different in kind: it is the check
     * made by the class that is about to interpolate the string into SQL, and it assumes its caller
     * is wrong. Phase three's lesson is the reason it exists -- the statement gate checked
     * table_name and not schema_name, and a quoted schema carried an s3:// URL past it -- so a
     * location is re-examined at the point of use and not only at the point of entry.
     *
     * The bucket is not compared against a name; the CONNECTION is compared against the primary
     * dataset's. A session carries exactly one storage connection's credentials, so a write to any
     * other connection's bucket would be this connection's credential spent on somebody else's
     * location, which is the request DatasetResolver exists to make unaskable.
     */
    private String writableUrl(DatasetRef primary, DatasetRef target) throws AnalyticsException {
        if (primary == null || target == null) {
            throw new AnalyticsException("There is nowhere to write this result.");
        }
        if (target.getConnection() == null || primary.getConnection() == null
            || target.getConnection().getStorageConnectionId() == null
            || !target.getConnection().getStorageConnectionId()
                .equals(primary.getConnection().getStorageConnectionId())
            || !target.getBucket().equals(primary.getBucket())) {
            throw new AnalyticsException("An export is written into the connection it was read "
                + "from, and this one names a different connection.");
        }
        String path = target.getPath();
        String url = target.url();
        boolean writable = url.equals("s3://" + target.getBucket() + "/" + path)
            // A second scheme anywhere in the string is the shape a smuggled location takes.
            && url.indexOf("://", "s3://".length()) < 0
            // A glob names a set of files. COPY given one writes a file with a star in its name,
            // which is a location nobody chose and nothing can find again.
            && !target.isMultiFile()
            && !path.contains("..")
            // And a "." segment, which StorageBrowserServiceImpl.isSafeKey (:405-418) refuses and
            // this did not -- so an object could be written that the app's own browser will not
            // read back. Checked here as well as in AnalyticsExportService because this is the
            // point of interpolation, and the two layers deliberately overlap.
            && !hasDotSegment(path)
            && !path.contains("//")
            && !path.startsWith("/")
            // A key ending in a slash makes DuckDB write a directory-shaped export beside the
            // object the caller was told about.
            && !path.endsWith("/");
        if (!writable) {
            throw new AnalyticsException("That is not a location an export can be written to.");
        }
        return url;
    }

    /**
     * How many rows the query would return, counting no further than it has to.
     *
     * LIMIT ceiling + 1 rather than ceiling, because "exactly the ceiling" and "more than the
     * ceiling" are the two answers this has to tell apart, and a count that stops at the ceiling
     * reports both as the same number.
     */
    private long countUpTo(Connection session, Statement statement, String sql, int ceiling,
        int timeout) throws AnalyticsException, SQLException {

        String probe = "SELECT count(*) FROM (SELECT 1 FROM (" + sql
            + ") AS export_probe LIMIT " + (ceiling + 1) + ") AS export_probe_count";
        StatementGate.confirmComposed(session, probe, timeout);
        try (ResultSet counted = statement.executeQuery(probe)) {
            return counted.next() ? counted.getLong(1) : 0L;
        }
    }

    /**
     * The writer options for a format, from a switch and never from a caller's string.
     *
     * ARRAY true for JSON so that a file called .json holds a JSON array rather than the
     * newline-delimited objects COPY writes by default. Both are readable by read_json_auto; only
     * one of them is what a person opening the file expects to find.
     */
    private static String copyOptions(DatasetRef.Format format) throws AnalyticsException {
        switch (format) {
            case CSV:     return "(FORMAT CSV, HEADER)";
            // A real tab in the SQL, not the two characters backslash-t: measured on 1.1.3, COPY's
            // DELIMITER takes the byte it is given and does not unescape it.
            case TSV:     return "(FORMAT CSV, HEADER, DELIMITER '\t')";
            case JSON:    return "(FORMAT JSON, ARRAY true)";
            case PARQUET: return "(FORMAT PARQUET)";
            default:
                throw new AnalyticsException("An export can be written as CSV, TSV, JSON or Parquet.");
        }
    }

    /**
     * A failed write, explained to whoever asked for it.
     *
     * The local-filesystem branch should be unreachable: the key is allow-listed on the way in and
     * re-checked in writableUrl, so no path that resolves locally survives to the COPY. It is here
     * because the day it IS reached is the day something above it stopped working, and the honest
     * message then is the rule, not "the dataset could not be read".
     */
    private RunFailure explainWrite(DatasetRef target, SQLException ex) {
        String raw = ex.getMessage() == null ? "" : ex.getMessage();
        String lower = raw.toLowerCase();
        if (lower.contains("localfilesystem") || lower.contains("disabled by configuration")
            || lower.contains("permission error")) {
            logger.error("An analytics write to {} was stopped by the filesystem rule: {}",
                target, raw);
            return new RunFailure(RunState.FAILED, "An export is written into a storage "
                + "connection's own bucket, and nowhere else.");
        }
        if (lower.contains("timeout") || lower.contains("interrupt")) {
            return new RunFailure(RunState.TIMED_OUT, "Writing that result took longer than "
                + this.limits.getTimeoutSeconds() + " seconds and was stopped. Narrow it, or "
                + "write it out in parts.");
        }
        if (lower.contains("403") || lower.contains("access denied") || lower.contains("forbidden")
            || lower.contains("401")) {
            return new RunFailure(RunState.FAILED, "The storage connection reached "
                + target.getBucket() + " but was not allowed to write to it.");
        }
        logger.error("An analytics write to {} failed", target, ex);
        // Deliberately says nothing else. An engine message from a write quotes the COPY back, and
        // the COPY is the one statement in this class that carries the destination URL.
        return new RunFailure(RunState.FAILED, "The result could not be written to storage.");
    }

    /**
     * The dataset, bound to a name a person can type.
     *
     * A view rather than a CTE prepended to the user's SQL, and the CTE is the obvious answer, so
     * why it is wrong is worth writing down. It cannot coexist with the user's own WITH clause --
     * "WITH dataset AS (...)" followed by "WITH sales AS (...)" is a syntax error, and a CTE is
     * the first thing anybody writes in a query editor. It also has to be glued to the front of
     * their text, which moves every position in a parser error onto a statement they did not
     * write, and DuckDB's positions are the half of a syntax message worth having.
     *
     * A view touches nothing the user typed. It costs one thing, which is worth knowing: DuckDB
     * binds a view when it is CREATED, so naming a second dataset reads its header even if the
     * query never mentions it. That is the price of the second dataset being resolved, checked
     * and readable whether or not the SQL turns out to use it.
     *
     * TEMP because the catalogue dies with the session anyway. Saying so in the SQL keeps it true
     * if a session ever stops being per-query.
     */
    private static String viewOf(String name, DatasetRef dataset) {
        return "CREATE OR REPLACE TEMP VIEW " + name + " AS SELECT * FROM "
            + dataset.scanExpression();
    }

    /**
     * A whole result read into strings, with each column's label AND type collected on the way past.
     *
     * Values are rendered as text here rather than in the browser: a DuckDB DECIMAL or TIMESTAMP
     * has no JSON equivalent that survives the trip unchanged, and a number that arrives as a
     * JavaScript double has already lost precision. A null stays a null rather than becoming the
     * four characters "null", which no reader could tell from the value.
     *
     * <b>"As text" is not the same as String.valueOf(), and treating them as the same was a real
     * defect.</b> Measured through the full request path on the 10 MB benchmark file:
     * {@code sum(amount)} over a DOUBLE column came back as <b>7.46613235E7</b>, and a currency
     * total in scientific notation is the single most likely thing a person aggregates; the median
     * of a DATE column came back as <b>2024-02-15 12:00:00.0</b>, a midnight-and-noon that is not
     * in the data and is not even the column's type. Both are Java's default toString for the
     * object the driver happened to hand over, which is a rendering nobody chose.
     *
     * So the type decides, and the type comes from the RESULT rather than from a guess about the
     * characters. That is also why the labels are collected as {@link ColumnDto} now: the same
     * metadata that fixes the rendering here is what 09 asks to be returned, and computing it twice
     * -- once to render and once to report -- is how the two would disagree.
     */
    private static List<List<String>> rowsOf(ResultSet resultSet, List<ColumnDto> columns)
        throws SQLException {

        ResultSetMetaData meta = resultSet.getMetaData();
        int width = meta.getColumnCount();
        int[] types = new int[width + 1];
        for (int i = 1; i <= width; i++) {
            types[i] = meta.getColumnType(i);
            columns.add(new ColumnDto(meta.getColumnLabel(i), meta.getColumnTypeName(i)));
        }
        List<List<String>> rows = new ArrayList<>();
        while (resultSet.next()) {
            List<String> row = new ArrayList<>(width);
            for (int i = 1; i <= width; i++) {
                row.add(rendered(resultSet, i, types[i]));
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * One value, as the string a reader of THAT column would recognise.
     *
     * Driven by java.sql.Types rather than by the class the driver returned, because the class is
     * the driver's business and changes with it -- duckdb_jdbc 1.1.3 hands back a LocalDate for one
     * DATE and a Timestamp for another depending on how the value was produced -- while the column
     * type is what the engine committed to. Every branch below is a shape the default rendering got
     * wrong, and everything else falls through to the rendering that was already correct.
     *
     * A failure to render is never a failure of the query: a type this method has not met comes out
     * as whatever the driver's own toString says, which is exactly what every value used to do.
     */
    /**
     * A time as the column holds it, seconds included.
     *
     * LocalTime.toString() is the driver's route and it omits a zero seconds field, which is legal
     * ISO-8601 and wrong here: the values in one column would render at different precisions
     * depending on their value. Anything that is not a LocalTime falls through to its own text,
     * because inventing a format for a type this method has not seen would be the same mistake.
     */
    private static String timeOf(Object value) {
        if (value instanceof java.time.LocalTime) {
            java.time.LocalTime time = (java.time.LocalTime) value;
            return time.getNano() == 0
                ? time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
                : time.toString();
        }
        return String.valueOf(value);
    }

    private static String rendered(ResultSet resultSet, int index, int sqlType) throws SQLException {
        Object value = resultSet.getObject(index);
        if (value == null) {
            return null;
        }
        switch (sqlType) {
            case Types.DECIMAL:
            case Types.NUMERIC:
            case Types.DOUBLE:
            case Types.FLOAT:
            case Types.REAL:
                return plainNumber(value);
            case Types.DATE:
                return asDate(value);
            case Types.TIME:
            case Types.TIME_WITH_TIMEZONE:
                // Explicitly, because the driver's own rendering drops zero seconds: a column
                // holding 14:30:00 and 00:00:00 came back as "14:30" and "00:00", ragged down the
                // column and shorter than the data. Same defect class as the phantom midnight a
                // DATE used to grow -- a rendering nobody chose, produced by the very method that
                // was rewritten to stop doing this.
                return timeOf(value);
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return asTimestamp(value);
            default:
                // Integers, booleans, text, blobs and DuckDB's own nested types. An integer's
                // toString is already its decimal form and a VARCHAR is already itself; rewriting
                // either would be a change with no defect behind it.
                return String.valueOf(value);
        }
    }

    /**
     * A number in the notation a person writes cheques in, not the one Java prints doubles in.
     *
     * BigDecimal.valueOf(double) goes through Double.toString, so it takes the shortest decimal
     * that round-trips to the same double -- 7.46613235E7 becomes exactly 74661323.5 and not the
     * binary expansion new BigDecimal(double) would produce. A DECIMAL arrives as a BigDecimal
     * already and keeps its scale, so a currency total that DuckDB computed as 52.50 stays "52.50"
     * rather than becoming "52.5".
     *
     * NaN and the infinities have no plain decimal form and BigDecimal refuses them outright, so
     * they keep the only names they have.
     *
     * One consequence worth knowing before somebody reports it as a bug: a DOUBLE renders as the
     * SHORTEST decimal that round-trips, so 10.0 keeps its trailing zero and 74661320.0 does not.
     * That is faithful to what a double is, and it is also the argument for storing money as
     * DECIMAL -- DuckDB carries the scale on that type, and the first branch above preserves it, so
     * a column declared DECIMAL(18,2) renders as 52.50 down the whole page.
     */
    private static String plainNumber(Object value) {
        if (value instanceof BigDecimal) {
            return ((BigDecimal) value).toPlainString();
        }
        if (value instanceof Double || value instanceof Float) {
            double number = ((Number) value).doubleValue();
            if (Double.isNaN(number) || Double.isInfinite(number)) {
                return String.valueOf(value);
            }
            return BigDecimal.valueOf(number).toPlainString();
        }
        return String.valueOf(value);
    }

    /** A DATE as a date. The time it is rendered with is a time the column does not have. */
    private static String asDate(Object value) {
        if (value instanceof java.sql.Date) {
            // java.sql.Date.toString is already yyyy-MM-dd, and going via toLocalDate() would
            // reinterpret the value in the JVM's zone on the way past.
            return value.toString();
        }
        if (value instanceof LocalDate) {
            return value.toString();
        }
        if (value instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) value).toLocalDateTime().toLocalDate().toString();
        }
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).toLocalDate().toString();
        }
        return String.valueOf(value);
    }

    /**
     * A TIMESTAMP with its seconds and without JDBC's trailing tenth.
     *
     * java.sql.Timestamp.toString always prints at least one fractional digit, so a whole second
     * reads as "10:00:00.0" -- a precision the value does not claim. LocalDateTime.toString has the
     * opposite habit and drops the seconds entirely when they are zero, which turns a timestamp
     * into something that looks like a different kind of value. Neither is what a column of
     * timestamps should look like down a page, so the format is fixed here and the fraction is
     * printed only when there is one.
     */
    private static String asTimestamp(Object value) {
        LocalDateTime moment;
        if (value instanceof java.sql.Timestamp) {
            moment = ((java.sql.Timestamp) value).toLocalDateTime();
        } else if (value instanceof LocalDateTime) {
            moment = (LocalDateTime) value;
        } else if (value instanceof OffsetDateTime) {
            return value.toString();
        } else {
            return String.valueOf(value);
        }
        String rendered = moment.format(TIMESTAMP_SHAPE);
        if (moment.getNano() != 0) {
            rendered = rendered + "." + FRACTION_SHAPE.format(moment);
        }
        return rendered;
    }

    /** The names of a set of columns, for the callers that carry names and not types. */
    private static List<String> namesOf(List<ColumnDto> columns) {
        List<String> names = new ArrayList<>(columns.size());
        for (ColumnDto column : columns) {
            names.add(column.getName());
        }
        return names;
    }

    /**
     * Per-column statistics for the Profile tab and the quality flags for the Quality tab, from one
     * scan.
     *
     * ONE scan is the whole design, not an optimisation. A file open already costs three sessions
     * and three governor permits against a ceiling of four (gap 17), so a Quality endpoint beside a
     * Profile endpoint would have made it five for the same twelve numbers read twice. Everything
     * the Quality tab shows is derived below from what SUMMARIZE returned, after the session has
     * closed -- if a flag ever needs a query of its own, it is a different method, and it is a cost
     * that has to be argued for rather than added.
     *
     * Deliberately not bounded(), and here that is a decision rather than a limitation. SUMMARIZE
     * does survive being wrapped in a subquery -- measured on 1.1.3, not assumed -- so the ceiling
     * could have been applied. It is not, because this result is one row per COLUMN: its size is
     * the file's width, not its length, and truncating it would silently drop columns out of a
     * profile that claims to describe the dataset. A page missing rows says so with a page number;
     * a profile missing columns says nothing at all.
     *
     * The row count comes back free. SUMMARIZE repeats the relation's row count on every row, so
     * this answers the count question without the separate count(*) that preview() pays for.
     */
    @Override
    public DatasetProfileDto profileOf(DatasetRef dataset) throws AnalyticsException {
        DatasetProfileDto profile = this.run(dataset,
            "SUMMARIZE SELECT * FROM " + dataset.scanExpression(),
            resultSet -> {
                List<ColumnProfileDto> found = new ArrayList<>();
                long rowsInDataset = 0L;
                while (resultSet.next()) {
                    // Read from every row and overwritten each time rather than read once: it is
                    // the same number on all of them because it counts the relation, not the
                    // column, and a dataset with no columns has no row to read it from at all.
                    rowsInDataset = resultSet.getLong("count");
                    found.add(measured(resultSet));
                }
                return new DatasetProfileDto(dataset.getBucket(), dataset.getPath(),
                    dataset.getFormat().name(), dataset.isMultiFile(), rowsInDataset, found);
            });

        // Outside run(), and visibly so. Every flag the Quality tab shows is decided here, from
        // numbers already in hand, with no session open and no permit held.
        for (ColumnProfileDto column : profile.getColumns()) {
            derive(column, profile.getTotalRows());
        }
        return profile;
    }

    /**
     * One SUMMARIZE row, copied across without interpretation.
     *
     * Seven of the twelve columns come back as VARCHAR because they have to carry dates and text as
     * well as numbers, and they are read with getString for that reason. Parsing them to double
     * here would work on every numeric column and throw on the first DATE, which is a failure that
     * only ever happens on a customer's file.
     */
    private static ColumnProfileDto measured(ResultSet resultSet) throws SQLException {
        ColumnProfileDto column = new ColumnProfileDto();
        column.setName(resultSet.getString("column_name"));
        column.setType(resultSet.getString("column_type"));
        column.setMin(resultSet.getString("min"));
        column.setMax(resultSet.getString("max"));
        column.setAvg(resultSet.getString("avg"));
        column.setStd(resultSet.getString("std"));
        // Named approx on the way in, because SUMMARIZE uses approx_quantile rather than the exact
        // one: on a column whose true first quartile is 21.0 it reported 18.375. A field called q50
        // becomes "Median" on a screen, and that would be an overclaim by the time anyone noticed.
        column.setApproxQ25(resultSet.getString("q25"));
        column.setApproxQ50(resultSet.getString("q50"));
        column.setApproxQ75(resultSet.getString("q75"));
        column.setApproxDistinct(resultSet.getLong("approx_unique"));
        // Null when the dataset has no rows, which is the one case with no percentage to report.
        column.setNullPercentage(resultSet.getBigDecimal("null_percentage"));
        return column;
    }

    /**
     * The Quality tab, decided from the Profile tab and from nothing else.
     *
     * What each flag can honestly claim is written on the field it sets, in ColumnProfileDto. The
     * short version is that only completeness rests on a figure DuckDB measured exactly, and even
     * that arrives rounded to two decimal places -- enough on a thousand rows, not enough on ten
     * million, where a single null and a single value both round away.
     */
    private static void derive(ColumnProfileDto column, long totalRows) {
        BigDecimal nullPercentage = column.getNullPercentage();
        if (nullPercentage != null) {
            column.setCompleteness(ONE_HUNDRED.subtract(nullPercentage));
            // A reconstruction, not a count. SUMMARIZE's own 'count' is the TOTAL row count -- a
            // column with one null in three still reports 3 -- so there is no exact null count in
            // the result to prefer over this one.
            column.setApproxNullRows(BigDecimal.valueOf(totalRows).multiply(nullPercentage)
                .divide(ONE_HUNDRED, 0, RoundingMode.HALF_UP).longValue());
        }

        // Both signals, because on a large file either alone is satisfied by a column that is
        // nearly empty rather than empty: 99.9999% nulls rounds to 100.00.
        column.setAllNull(column.getApproxDistinct() == 0
            && nullPercentage != null && nullPercentage.compareTo(ONE_HUNDRED) == 0);
        column.setConstant(column.getApproxDistinct() == 1);
        column.setKeyLike(isKeyLike(column, totalRows));
        column.setTypeSurprise(typeSurprise(column));
    }

    /**
     * Whether the column might be a unique key.
     *
     * A key has no nulls and one value per row, so both are asked. The row-count floor is there
     * because on a single-row file every column is trivially unique and saying so about all of them
     * is noise rather than an answer.
     */
    private static boolean isKeyLike(ColumnProfileDto column, long totalRows) {
        if (totalRows < 2 || column.getNullPercentage() == null
            || column.getNullPercentage().signum() != 0) {
            return false;
        }
        BigDecimal ratio = BigDecimal.valueOf(column.getApproxDistinct())
            .divide(BigDecimal.valueOf(totalRows), 4, RoundingMode.HALF_UP);
        return ratio.compareTo(KEY_LIKE_RATIO) >= 0;
    }

    /**
     * Whether a column DuckDB read as text is holding something that is not text.
     *
     * This is the cheapest question in the method and the least certain answer, because min and max
     * are the only two values SUMMARIZE hands back. Everything between them is unseen: '1', '1abc'
     * and '9' report a min of '1' and a max of '9', and this returns NUMBER for a column that
     * contains a word. It is offered as a prompt to look, which is what gap 25 asked for, and the
     * screen has to phrase it as one.
     *
     * Number is tested before date so that an all-digit value like 20240105 is called a number
     * rather than guessed at as a compact date.
     */
    private static String typeSurprise(ColumnProfileDto column) {
        if (!"VARCHAR".equalsIgnoreCase(column.getType())) {
            return null;
        }
        String low = column.getMin();
        String high = column.getMax();
        // Absent on an all-null column, and an empty string is a blank rather than a shape.
        if (low == null || high == null || low.isEmpty() || high.isEmpty()) {
            return null;
        }
        if (isNumber(low) && isNumber(high)) {
            return ColumnProfileDto.SURPRISE_NUMBER;
        }
        for (DateTimeFormatter shape : DATE_SHAPES) {
            if (parsesAsDate(low, shape) && parsesAsDate(high, shape)) {
                return ColumnProfileDto.SURPRISE_DATE;
            }
        }
        return null;
    }

    /** BigDecimal rather than Double, so a value too wide for a double is still a number. */
    private static boolean isNumber(String value) {
        try {
            new BigDecimal(value);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static boolean parsesAsDate(String value, DateTimeFormatter shape) {
        try {
            LocalDate.parse(value, shape);
            return true;
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    /**
     * The bounded form of a read query: the same query, unable to return more than
     * analytics.query.max-rows rows.
     *
     * This is what analytics.query.max-rows means, and until now nothing meant it -- the property
     * clamped the preview page size and was described in AnalyticsLimits as a LIMIT rewrite that
     * did not exist. It exists here, and it wraps rather than appends because appending is wrong
     * on any query that already ends in a LIMIT, an ORDER BY or a semicolon, which is every query
     * a person writes. Wrapping bounds whatever is inside without having to understand it.
     *
     * The bound is a ceiling on rows RETURNED, not on rows read: DuckDB pushes the outer limit
     * down where it can, but a query that aggregates a billion rows into one still reads a
     * billion. The timeout and the session's memory ceiling are what bound that, and this is the
     * third of the three rather than a replacement for either.
     *
     * What this deliberately does NOT do is decide whether the statement is a read at all. A
     * COPY ... TO or an ATTACH wrapped in a subquery is a syntax error rather than a write, and
     * disabled_filesystems has already removed the local target, but "the filesystem is gone" is
     * a weaker claim than "we only run SELECT". StatementGate makes the stronger one, in front of
     * this method rather than inside it -- refusing a statement and bounding a result are
     * different decisions and only one of them has a row count in it.
     */
    @Override
    public String bounded(String sql) throws AnalyticsException {
        String statement = sql == null ? "" : sql.trim();
        // A trailing semicolon is the one thing that cannot survive being wrapped, and it is what
        // a person typing SQL leaves behind most often.
        while (statement.endsWith(";")) {
            statement = statement.substring(0, statement.length() - 1).trim();
        }
        if (statement.isEmpty()) {
            throw new AnalyticsException("There is no query to run.");
        }
        return "SELECT * FROM (" + statement + ") AS bounded_query LIMIT " + this.limits.getMaxRows();
    }

    /**
     * Stops the watchdog thread when the application does.
     *
     * It is a daemon, so nothing hangs without this. It is here because a thread that outlives the
     * bean that owns it is the kind of thing that is fine until something else in the process
     * starts counting threads.
     */
    @PreDestroy
    public void shutdown() {
        this.watchdogs.shutdownNow();
    }

    /** What the caller asked for, clamped to what the policy allows. */
    private int pageSize(Integer requested) {
        int size = requested == null || requested < 1 ? this.limits.getPreviewPageSize() : requested;
        return Math.min(size, this.limits.getMaxRows());
    }

    /**
     * Runs one statement under every limit at once: a slot, a timeout, and a locked-down session.
     *
     * The three are applied together and in this order for a reason. Taking the slot first means
     * a rejected caller never pays for a session; the session's own memory ceiling bounds what
     * the query can consume once it starts; and the timeout bounds how long it may hold the slot,
     * so one wedged scan cannot occupy a permit for ever and shrink the ceiling for everybody.
     */
    private <T> T run(DatasetRef dataset, String sql, ResultReader<T> reader) throws AnalyticsException {
        return this.inSession(dataset, (session, statement) -> {
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                return reader.read(resultSet);
            }
        });
    }

    /**
     * A governed session for a run nobody outside can address.
     *
     * The built-in reads get a minted id and are registered like everything else, so "what is this
     * application running right now" has one answer rather than one answer plus the reads that
     * were not worth counting. No endpoint hands their ids out, so in practice only the timeout
     * stops them.
     */
    private <T> T inSession(DatasetRef dataset, SessionWork<T> work) throws AnalyticsException {
        return this.withStorageRetry(dataset, work);
    }

    /**
     * Retries a BUILT-IN read once when the object store failed to answer.
     *
     * <b>Only the built-in reads.</b> Schema, preview and profile are this class's own statements
     * over one object: repeating one produces the same answer and costs the same scan, so a retry
     * is invisible except that it worked. User SQL and composed analyses go through the two-argument
     * inSession and are deliberately NOT retried -- the caller holds a run id, may be watching a
     * stop button, and a silent second execution of a query somebody is trying to cancel is the
     * opposite of what they asked for.
     *
     * <b>A fresh session per attempt, not a re-execute on the old one.</b> The failures classified
     * as transient are failures of the connection to the object store; the DuckDB session holding
     * that connection is exactly the thing that is broken, and reusing it would retry through it.
     * That means each attempt re-acquires a governor permit and takes a new registry handle, which
     * is also correct: an attempt that queues behind other work is an attempt, not a free one.
     *
     * The retry is not silent to operators. Every one is a WARN naming the dataset, because a
     * storage service that needs a second ask on a noticeable fraction of reads is a fact about
     * the deployment that would otherwise be invisible -- reads would simply look slow.
     */
    private <T> T withStorageRetry(DatasetRef dataset, SessionWork<T> work)
        throws AnalyticsException {

        int attempts = Math.max(1, this.limits.getStorageRetryAttempts());
        for (int attempt = 1; ; attempt++) {
            try {
                return this.inSession(dataset, this.running.open(null), work);
            } catch (AnalyticsEngine.RunFailure failure) {
                if (!failure.isRetryable() || attempt >= attempts) {
                    throw failure;
                }
                logger.warn("Retrying analytics read of {} after a transient storage failure "
                    + "(attempt {} of {}): {}", dataset, attempt, attempts, failure.getMessage());
                this.pauseBeforeRetry();
            }
        }
    }

    /**
     * Waits between attempts, and treats an interrupt as a reason to stop rather than to hurry.
     *
     * Restoring the flag and giving up is what a caller shutting this thread down is asking for.
     * Swallowing it and retrying immediately would turn a shutdown into one more scan.
     */
    private void pauseBeforeRetry() throws AnalyticsException {
        long backoff = this.limits.getStorageRetryBackoffMs();
        if (backoff <= 0) {
            return;
        }
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AnalyticsException("The query was interrupted.");
        }
    }

    /**
     * The governed session itself: a slot, a locked-down connection, a timeout that is enforced,
     * a registry entry that can be cancelled, and one line in the log saying a dataset was read.
     *
     * Split out from run() so that user-written SQL, which needs the session for more than one
     * statement -- a parse, two view definitions and the query -- cannot get one any other way.
     * A path that opened its own connection would sit outside every limit applied here, and that
     * is the failure this whole module was shaped to prevent.
     *
     * The handle is closed in the outermost finally, which is what makes the registry bounded:
     * a completed run, a failed one, a timed-out one, a cancelled one and a caller who never got
     * a permit all leave through that one line.
     */
    private <T> T inSession(DatasetRef dataset, RunningQueries.Handle handle, SessionWork<T> work)
        throws AnalyticsException {

        try {
            boolean acquired;
            try {
                acquired = this.slots.tryAcquire(SLOT_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AnalyticsException("The query was interrupted.");
            }
            if (!acquired) {
                // A run its owner stopped while it queued is cancelled, not refused. Both end the
                // same two seconds later, but they are not the same event: one is the governor
                // saying no and the other is the user saying no, and a history that recorded a
                // deliberate stop as a capacity refusal would misreport how busy this module is.
                if (handle.stoppedBy() == RunningQueries.Stopper.USER) {
                    throw cancelled();
                }
                throw new AnalyticsException("Too many analytics queries are running right now. "
                    + "Try again in a moment.");
            }

            long startedAt = System.currentTimeMillis();
            try (Connection duck = this.sessions.open(dataset.getConnection());
                 Statement statement = duck.createStatement()) {

                // Kept even though duckdb_jdbc 1.1.3 ignores it. It is the standard way to say
                // this, it costs nothing, and the day the driver implements it the watchdog below
                // becomes the backstop rather than the mechanism.
                statement.setQueryTimeout(this.limits.getTimeoutSeconds());

                // QUEUED to RUNNING, and the one place a cancel that arrived while the caller was
                // waiting for a permit is honoured -- the statement it would have interrupted did
                // not exist yet, so the flag has to be read here instead.
                if (!handle.running(statement)) {
                    throw cancelled();
                }
                ScheduledFuture<?> watchdog = this.stopAfterTimeout(handle);
                try {
                    T value = work.on(duck, statement);
                    // info rather than debug: this line is the only record anywhere that a dataset
                    // was read, and "who read what, and when" is a question about customer data
                    // reached through a stored credential. A real audit trail is a platform item
                    // with four consumers waiting on it; a log line an operator can grep is what
                    // exists in the meantime. Safe to raise because DatasetRef.toString() was
                    // written to name the location without the credentials that reach it.
                    logger.info("Analytics query on {} for tenant {} took {} ms",
                        dataset, TenantContext.getTenantId(), System.currentTimeMillis() - startedAt);
                    return value;
                } finally {
                    // Before the statement closes: a cancel() already running on another thread
                    // finishes here, and one that has not started never will. The driver's own
                    // comment says cancel() on a closed connection is not safe.
                    handle.finish();
                    if (watchdog != null) {
                        watchdog.cancel(false);
                    }
                }
            } catch (SQLException ex) {
                throw this.explain(dataset, handle, ex, System.currentTimeMillis() - startedAt);
            } finally {
                this.slots.release();
            }
        } finally {
            handle.close();
        }
    }

    /**
     * Arranges for a query that outstays the ceiling to be interrupted.
     *
     * A non-positive timeout schedules nothing, which is JDBC's own reading of zero -- no limit --
     * rather than a ceiling of zero seconds that would cancel every query the instant it started.
     *
     * The interruption itself belongs to the handle, so the watchdog and a user pressing stop take
     * the same lock and reach the same statement. What differs is only who is recorded as having
     * stopped it, which is the difference between TIMED_OUT and CANCELLED in the history.
     */
    private ScheduledFuture<?> stopAfterTimeout(RunningQueries.Handle handle) {
        int seconds = this.limits.getTimeoutSeconds();
        if (seconds <= 0) {
            return null;
        }
        return this.watchdogs.schedule(() -> handle.stop(RunningQueries.Stopper.TIMEOUT),
            seconds, TimeUnit.SECONDS);
    }

    /**
     * Turns a DuckDB failure into something a person can act on, in the state the run ended in.
     *
     * DuckDB's messages are good but they are written for someone holding the SQL. That splits
     * every failure in two, and the split is what this method is organised around.
     *
     * A failure in the STATEMENT is one only the person holding the SQL can fix, and for those
     * DuckDB's own message is the better answer -- "syntax error at or near" says more than any
     * sentence written here, and once phase three lets a user write the query they are the person
     * holding it. A failure in the DATASET is the opposite: the user chose a file, not a query,
     * and an engine message about an HTTP status or a sniffing failure tells them nothing they
     * can act on. Those are mapped to sentences below.
     *
     * Anything else is logged in full and reported generically, because an unmapped engine
     * message is exactly the kind of string that carries a path or a host name.
     *
     * <b>The handle is asked first, and that ordering is the fix for a real defect.</b> A
     * cancelled query and a timed-out query both surface as "INTERRUPT Error: Interrupted!", so
     * the message cannot tell them apart and everything downstream used to record whichever one
     * the string matching happened to name. The registry knows who called cancel(); the engine
     * never will.
     */
    private RunFailure explain(DatasetRef dataset, RunningQueries.Handle handle, SQLException ex,
        long elapsedMs) {

        RunningQueries.Stopper stopper = handle.stoppedBy();
        if (stopper == RunningQueries.Stopper.USER) {
            return cancelled();
        }
        if (stopper == RunningQueries.Stopper.TIMEOUT) {
            return this.timedOut();
        }

        String raw = ex.getMessage() == null ? "" : ex.getMessage();
        String lower = raw.toLowerCase();

        // Ahead of the dataset mapping, not after it, and the ordering is load-bearing. DuckDB
        // names the error class in front of every message, so "Catalog Error: Table with name
        // orders does not exist!" is unambiguously about the statement -- but it also contains
        // "does not exist", and the branch below would report it as a missing file: true of a
        // path the user never wrote and false of the query they did. None of the dataset
        // failures this feature has actually seen carry a statement class, so nothing that was
        // mapped before is caught here now.
        if (isStatementFailure(lower)) {
            // Logged as well as returned, and it now means one of two different things. On a
            // built-in read the SQL is ours, so a statement failure is a bug in this class; on a
            // query() the SQL is the user's, so it is usually a typo. Both are worth a line: the
            // first is the only warning anybody gets, and the second is how an operator finds out
            // that the gate is refusing something it should be admitting.
            logger.warn("Analytics rejected a statement on {} after {} ms: {}", dataset, elapsedMs, raw);
            return new RunFailure(RunState.FAILED, safeEngineMessage(raw));
        }
        if (lower.contains("timeout") || lower.contains("interrupt")) {
            // Still here even though the handle is asked first: an interrupt this application did
            // not ask for -- a driver-level abort, a closed socket reported as one -- is closer to
            // a timeout than to anything else in this method, and calling it that is the reading
            // the user was given before there was a registry to consult.
            return this.timedOut();
        }
        if (lower.contains("no files found") || lower.contains("404")
            || lower.contains("nosuchkey") || lower.contains("does not exist")) {
            return new RunFailure(RunState.FAILED, "Nothing to read at " + dataset.getBucket() + "/"
                + dataset.getPath() + ". The connection worked, so check the path.");
        }
        if (lower.contains("403") || lower.contains("access denied") || lower.contains("forbidden")) {
            return new RunFailure(RunState.FAILED, "The storage connection reached "
                + dataset.getBucket() + " but was not allowed to read it.");
        }
        if (lower.contains("out of memory") || lower.contains("memory limit")) {
            return new RunFailure(RunState.FAILED, "That query needed more memory than analytics is "
                + "allowed to use. Try a narrower dataset, or Parquet instead of CSV.");
        }
        if (lower.contains("invalid input error") || lower.contains("could not convert")
            || lower.contains("sniffing") || lower.contains("csv error")) {
            return new RunFailure(RunState.FAILED, "This file could not be read as "
                + dataset.getFormat() + ". It may be malformed, or a different format.");
        }
        // LAST, deliberately. Every check above names something about the REQUEST -- a credential,
        // a path, the statement, the file's contents -- and each of those fails identically on a
        // second attempt. Only what is left over can be about the transport, so classifying
        // transience here rather than earlier means a 403 that happens to mention a socket is
        // still a 403.
        if (isTransientStorageFailure(lower)) {
            logger.warn("Analytics read of {} hit a transient storage failure after {} ms: {}",
                dataset, elapsedMs, raw);
            return new RunFailure(RunState.FAILED, "The storage service did not answer. "
                + "This is usually brief -- try again in a moment.", true);
        }
        logger.error("Analytics query failed on {} after {} ms", dataset, elapsedMs, ex);
        return new RunFailure(RunState.FAILED, "The dataset could not be read.");
    }

    /**
     * Whether the object store failed to ANSWER, as opposed to answering no.
     *
     * <b>Matched narrowly, and the narrowness is the point.</b> The cost of calling something
     * transient that is not is a second full scan of the same file, charged to a governor that
     * admits four queries at a time across the JVM -- so a wrong yes here is paid for by every
     * other reader. The cost of a wrong no is the error the user would have got anyway.
     *
     * 5xx and not 4xx: a 4xx is the store telling us the request was wrong, which repeating will
     * not fix. 503 and 429 in particular are the store asking to be asked again later, which is
     * the clearest possible case for a retry.
     *
     * "timeout" is NOT here, even though it looks transient. A query timeout is handled well
     * before this point and means the work did not fit in the ceiling; retrying it spends another
     * full ceiling to fail the same way.
     */
    private static boolean isTransientStorageFailure(String lower) {
        return lower.contains("connection reset")
            || lower.contains("connection refused")
            || lower.contains("could not establish connection")
            || lower.contains("unable to connect")
            || lower.contains("failed to connect")
            || lower.contains("connection closed")
            || lower.contains("broken pipe")
            || lower.contains("temporarily unavailable")
            || lower.contains("service unavailable")
            // "slowdown", not "slow down": S3's throttling error CODE is the single word
            // SlowDown, and the spaced version matched nothing. Found by the test, not by
            // reading -- which is the argument for testing a substring table at all.
            || lower.contains("slowdown")
            || lower.contains("http 429")
            || lower.contains("http 500")
            || lower.contains("http 502")
            || lower.contains("http 503")
            || lower.contains("http 504");
    }

    /**
     * What a user is told when their own stop request landed.
     *
     * Deliberately plain. A cancellation is the one failure in this class that the person reading
     * it already knows about, because they asked for it, and an explanation would read as though
     * something had gone wrong.
     */
    private static RunFailure cancelled() {
        return new RunFailure(RunState.CANCELLED, "That query was cancelled.");
    }

    private RunFailure timedOut() {
        return new RunFailure(RunState.TIMED_OUT, "That query took longer than "
            + this.limits.getTimeoutSeconds() + " seconds and was stopped. "
            + "Narrow the dataset, or filter it down.");
    }

    /**
     * Whether DuckDB is complaining about the statement rather than about the data.
     *
     * Matched on the error CLASS DuckDB prefixes its messages with, not on the wording of any one
     * of them. That is the durable half: the sentence after "Parser Error:" is rewritten between
     * releases, the class in front of it is part of how DuckDB reports errors. Binder and Catalog
     * are here with Parser because "no such column" and "no such table" are the same kind of
     * answer to the same kind of reader -- someone looking at a query they wrote.
     */
    private static boolean isStatementFailure(String lowerMessage) {
        return lowerMessage.contains("parser error")
            || lowerMessage.contains("syntax error")
            || lowerMessage.contains("binder error")
            || lowerMessage.contains("catalog error");
    }

    /**
     * An engine message with the things it must not carry taken out of it.
     *
     * The message is returned rather than summarised, because summarising it is what made it
     * useless. What is removed is the dataset URL: DuckDB quotes the failing statement back, this
     * class interpolated a location into that statement, and a user who wrote "SELCT" does not
     * need to be shown the bucket to learn they meant SELECT.
     */
    private static String safeEngineMessage(String raw) {
        String redacted = LOCATION.matcher(raw).replaceAll("<dataset>").trim();
        if (redacted.length() > ENGINE_MESSAGE_LIMIT) {
            redacted = redacted.substring(0, ENGINE_MESSAGE_LIMIT).trim() + "...";
        }
        // Only reachable if a driver ever throws a message that is nothing but a URL, but the
        // alternative is an empty error toast, which reads as a bug in the screen.
        return redacted.isEmpty() ? "The dataset could not be read." : redacted;
    }

    /** Reads a result set into a value. Kept local so callers never hold an open ResultSet. */
    @FunctionalInterface
    private interface ResultReader<T> {
        T read(ResultSet resultSet) throws SQLException;
    }

    /**
     * Everything one caller does with one session, so that the limits around it are applied once.
     *
     * Declares AnalyticsException as well as SQLException because the work may refuse the caller
     * on its own account -- StatementGate does -- and such a refusal is already written for a
     * person and must not be run through explain() as though the engine had said it.
     */
    @FunctionalInterface
    private interface SessionWork<T> {
        T on(Connection session, Statement statement) throws SQLException, AnalyticsException;
    }

    /**
     * Whether any path segment is "." — a step that means "here" and addresses nothing.
     *
     * Its own method because two layers check it and a copy that drifted would leave one of them
     * admitting what the other refuses. Split with a -1 limit so a trailing segment is not dropped.
     */
    private static boolean hasDotSegment(String key) {
        for (String segment : key.split("/", -1)) {
            if (".".equals(segment)) {
                return true;
            }
        }
        return false;
    }

}

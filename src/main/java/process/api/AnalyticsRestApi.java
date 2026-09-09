package process.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import process.analytics.AnalyticsEngine;
import process.analytics.AnalyticsException;
import process.analytics.AnalyticsLimits;
import process.analytics.AnalyticsQueryService;
import process.analytics.DatasetRef;
import process.analytics.AnalyticsExportService;
import process.analytics.DatasetResolver;
import process.analytics.RunningQueries;
import process.analytics.canvas.FilterClause;
import process.analytics.dto.DatasetPreviewDto;
import process.analytics.dto.DatasetProfileDto;
import process.analytics.dto.DatasetSchemaDto;
import process.analytics.dto.QueryResultDto;
import process.model.dto.ResponseDto;
import process.model.pojo.AnalyticsQueryRun;
import process.model.service.AnalyticsQueryLibraryService;
import process.util.ProcessUtil;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
/**
 * Analytics Studio: read a dataset, and query one.
 *
 * Every endpoint takes a storage CONNECTION and a path inside it, and never a bucket or a URL.
 * That is the feature's central security property rather than a convention: the bucket comes
 * from the connection record, so "read a different bucket with these credentials" is not a
 * request this API can express, and DatasetResolver decides whether the caller may read the
 * connection at all before anything is opened. The alias is the same one the object browser
 * uses, so the two screens address storage identically.
 *
 * The role is only the floor. TENANT_USER matches the storage browser this reads from, and for
 * the same reason: which connections a caller may reach is not a question a role can answer, and
 * is settled per request against the connection's own tenant.
 *
 * One endpoint here accepts SQL, and it is the one that has to be read with the rest of this
 * paragraph. It goes through AnalyticsQueryService like every other endpoint, under the same
 * session lock-down and the same governor, rather than opening a second door beside them: the
 * controller resolves datasets and hands over text, and nothing here opens a connection, builds a
 * scan or decides what a statement is allowed to be. That last decision is StatementGate's, made
 * against DuckDB's own parser on the session the query will run on.
 *
 * It is also what keeps the paragraph above true now that a caller can write SQL. "Read a
 * different bucket with these credentials" has no field on the other endpoints, and a statement is
 * a field it could have been written in -- so the gate refuses a query that names a location of
 * its own, and the datasets stay the two the resolver produced.
 *
 * @author Nabeel Ahmed
 * */
@RequestMapping(value = "/analytics.json")
@PreAuthorize("hasRole('TENANT_USER')")
public class AnalyticsRestApi {

    private final Logger logger = LoggerFactory.getLogger(AnalyticsRestApi.class);

    /**
     * Reads the one parameter on this API that carries structure rather than a scalar.
     *
     * Its own instance rather than the application's injected mapper, and shared rather than made
     * per request: ObjectMapper is thread-safe once configured and expensive to build, and this one
     * is deliberately at its defaults so a module-wide serialisation setting cannot change what a
     * filter means. AnalysisService keeps one for the same reason and on the same model.
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DatasetResolver datasetResolver;
    private final AnalyticsQueryService analyticsQueryService;
    private final AnalyticsQueryLibraryService analyticsQueryLibraryService;
    private final AnalyticsExportService analyticsExportService;

    /**
     * The feature switch, checked at the boundary rather than declared and forgotten.
     *
     * analytics.enabled existed as a property and three guard methods with ZERO callers, so
     * setting it to false left every endpoint serving while the health check reported the feature
     * off -- an operator killing analytics mid-incident got a green confirmation of a state that
     * was not true. Not @ConditionalOnProperty on the class: that answers 404, and 14's rollout
     * asks for a designed refusal a caller can read.
     */
    private final AnalyticsLimits analyticsLimits;

    public AnalyticsRestApi(DatasetResolver datasetResolver,
        AnalyticsQueryService analyticsQueryService,
        AnalyticsQueryLibraryService analyticsQueryLibraryService,
        AnalyticsExportService analyticsExportService,
        AnalyticsLimits analyticsLimits) {
        this.datasetResolver = datasetResolver;
        this.analyticsQueryService = analyticsQueryService;
        this.analyticsQueryLibraryService = analyticsQueryLibraryService;
        this.analyticsExportService = analyticsExportService;
        this.analyticsLimits = analyticsLimits;
    }

    /**
     * The dataset's columns and their inferred types.
     *
     * Cheap enough to call on selection: DESCRIBE reads only as much of the file as the reader
     * needs to settle the schema, which on Parquet is the footer and on CSV is a sample.
     */
    @RequestMapping(value = "/schema", method = RequestMethod.GET)
    public ResponseEntity<?> schema(
        @RequestParam(value = "connection") String connection,
        @RequestParam(value = "path") String path) {
        long startedAt = System.currentTimeMillis();
        try {
            this.analyticsLimits.requireEnabled();
            DatasetRef dataset = this.datasetResolver.resolve(connection, path);
            DatasetSchemaDto schema = this.analyticsQueryService.schemaOf(dataset);
            recordRead(connection, path, "schema", AnalyticsQueryRun.STATUS_SUCCESS,
                schema == null || schema.getColumns() == null ? null
                    : (long) schema.getColumns().size(), startedAt, null);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "Schema read.",
                schema), HttpStatus.OK);
        } catch (AnalyticsException ex) {
            // Written for a reader by whoever threw it, so it is returned as-is. A business
            // failure is an OK with status ERROR, matching every other endpoint here.
            recordRead(connection, path, "schema", AnalyticsQueryRun.STATUS_REFUSED, null,
                startedAt, ex.getMessage());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()),
                HttpStatus.OK);
        } catch (Exception ex) {
            recordRead(connection, path, "schema", AnalyticsQueryRun.STATUS_FAILED, null,
                startedAt, ex.getMessage());
            this.logger.error("An error occurred while reading a dataset schema.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * One page of rows, plus the total so the caller can page without guessing, in the order and
     * the narrowing a grid asked for.
     *
     * The page size is a request, not an instruction: AnalyticsQueryService clamps it, so a
     * caller asking for a million rows receives the configured maximum instead.
     *
     * knownTotal is the same bargain in the other direction. A client turning a page already has
     * the total from the response it is turning away from, and counting it again costs a second
     * session and a second governor permit for a number the browser is displaying. Passing it
     * back halves the cost of every page turn. It is optional and unvalidated on purpose -- the
     * only way to check it is to run the count this parameter exists to skip -- so it is treated
     * as a display value, never as anything a decision rests on. <b>The engine stops honouring it
     * the moment a filter or a search is present</b>, because at that point it is a count of a
     * different set of rows and the pager it draws offers pages that do not exist.
     *
     * <b>The four grid parameters.</b> sort names a column and direction says ASC or DESC; both are
     * resolved against the dataset's own schema inside the engine's session, so a name this API
     * cannot check here is not a name this API guesses about. search is free text matched
     * case-insensitively against every text column. filters is a JSON array of
     * {field, operator, value} in the SAME vocabulary the Canvas uses -- FilterClause, compiled by
     * the same FilterCompiler -- so there is one filter language in this module and not two.
     *
     * <b>They are parsed before the dataset is resolved</b>, so a malformed filter costs a JSON
     * parse and nothing else: no connection lookup, no session, no permit. Sorting and searching
     * are server-side because a page is a window onto a file that may hold millions of rows, and
     * sorting the window in the browser sorts the wrong rows and looks right doing it.
     */
    @RequestMapping(value = "/preview", method = RequestMethod.GET)
    public ResponseEntity<?> preview(
        @RequestParam(value = "connection") String connection,
        @RequestParam(value = "path") String path,
        @RequestParam(value = "page", required = false, defaultValue = "0") Integer page,
        @RequestParam(value = "pageSize", required = false) Integer pageSize,
        @RequestParam(value = "knownTotal", required = false) Integer knownTotal,
        @RequestParam(value = "sort", required = false) String sort,
        @RequestParam(value = "direction", required = false) String direction,
        @RequestParam(value = "search", required = false) String search,
        @RequestParam(value = "filters", required = false) String filters,
        // Where the carried total came from. Absent means "counted with nothing narrowing", which
        // is the only provenance that makes knownTotal reusable -- see PreviewShape.
        @RequestParam(value = "knownTotalFiltered", required = false,
            defaultValue = "false") Boolean knownTotalFiltered) {
        long startedAt = System.currentTimeMillis();
        // What was ASKED for, recorded whether or not the read succeeds -- a refused attempt has
        // no shape to describe afterwards. Narrowing is named but not quoted: the filter values
        // are the reader's data, and an audit table is not where a search term for somebody's
        // surname should end up.
        String descriptor = "preview page=" + (page == null ? 0 : page)
            + (sort == null || sort.trim().isEmpty() ? "" : " sorted")
            + (search == null || search.trim().isEmpty() ? "" : " searched")
            + (filters == null || filters.trim().isEmpty() ? "" : " filtered");
        try {
            this.analyticsLimits.requireEnabled();
            AnalyticsEngine.PreviewShape shape = new AnalyticsEngine.PreviewShape(sort,
                directionOf(direction), search, filtersOf(filters),
                Boolean.TRUE.equals(knownTotalFiltered));
            DatasetRef dataset = this.datasetResolver.resolve(connection, path);
            DatasetPreviewDto preview = this.analyticsQueryService.preview(dataset,
                page == null ? 0 : page, pageSize, knownTotal, shape);
            recordRead(connection, path, descriptor, AnalyticsQueryRun.STATUS_SUCCESS,
                preview == null || preview.getRows() == null ? null
                    : (long) preview.getRows().size(), startedAt, null);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "Dataset read.",
                preview), HttpStatus.OK);
        } catch (AnalyticsException ex) {
            recordRead(connection, path, descriptor, AnalyticsQueryRun.STATUS_REFUSED, null,
                startedAt, ex.getMessage());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()),
                HttpStatus.OK);
        } catch (Exception ex) {
            recordRead(connection, path, descriptor, AnalyticsQueryRun.STATUS_FAILED, null,
                startedAt, ex.getMessage());
            this.logger.error("An error occurred while previewing a dataset.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Per-column statistics, and the data quality flags derived from them.
     *
     * One endpoint for two tabs, and that is the point of it. Profile and Quality are the same
     * SUMMARIZE read for two different questions, so a second endpoint would have cost a second
     * session and a second governor permit to compute numbers this one already has -- on a file
     * open that costs three of each before either tab is opened.
     *
     * Several of the figures in the response are estimates rather than measurements, and the field
     * names and ColumnProfileDto's javadoc are where that is recorded. A caller rendering this
     * owes the user the same distinction.
     */
    @RequestMapping(value = "/profile", method = RequestMethod.GET)
    public ResponseEntity<?> profile(
        @RequestParam(value = "connection") String connection,
        @RequestParam(value = "path") String path) {
        long startedAt = System.currentTimeMillis();
        try {
            this.analyticsLimits.requireEnabled();
            DatasetRef dataset = this.datasetResolver.resolve(connection, path);
            DatasetProfileDto profile = this.analyticsQueryService.profileOf(dataset);
            // A profile is a FULL SCAN of the file, which is the most expensive read this module
            // offers and the one most worth being able to attribute afterwards.
            recordRead(connection, path, "profile", AnalyticsQueryRun.STATUS_SUCCESS,
                profile == null || profile.getColumns() == null ? null
                    : (long) profile.getColumns().size(), startedAt, null);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "Dataset profiled.",
                profile), HttpStatus.OK);
        } catch (AnalyticsException ex) {
            recordRead(connection, path, "profile", AnalyticsQueryRun.STATUS_REFUSED, null,
                startedAt, ex.getMessage());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()),
                HttpStatus.OK);
        } catch (Exception ex) {
            recordRead(connection, path, "profile", AnalyticsQueryRun.STATUS_FAILED, null,
                startedAt, ex.getMessage());
            this.logger.error("An error occurred while profiling a dataset.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * A query the user wrote, over one dataset or two.
     *
     * POST with a body rather than GET with parameters, and not for the usual REST reasons: SQL in
     * a query string is SQL in the access log, in the browser history and in every proxy between
     * here and there, and it is the one field on this API a person composes themselves.
     *
     * The body is connection, path and sql, plus connection2 and path2 when the query joins. The
     * two datasets are resolved SEPARATELY, each through DatasetResolver, so the second one passes
     * or fails the tenant check on its own -- a caller cannot reach a connection through a join
     * that they could not have opened by asking for it directly. There is no third field and no
     * list: two is what a join needs and every one of them costs a resolve, a view and a bind.
     *
     * The user's SQL names "dataset" and "dataset2". It never names a bucket, a URL or a scan
     * function, because the alias is still the only thing this API accepts and the resolver is
     * still the only thing that turns one into somewhere readable.
     *
     * <b>queryId is optional and is how a caller keeps the ability to stop this.</b> The endpoint
     * is synchronous, so a server-minted id reaches the browser in the same response as the rows --
     * that is, once there is nothing left to cancel. A client that means to show a stop button
     * therefore sends the id it will cancel with, and the response echoes whichever id was used.
     * The id is not a secret: it is only ever accepted from, and only ever acts on, the tenant and
     * user who started that run, which is the check RunningQueries makes.
     */
    @RequestMapping(value = "/query", method = RequestMethod.POST)
    public ResponseEntity<?> query(@RequestBody(required = false) Map<String, String> body) {
        Map<String, String> request = body == null ? Collections.emptyMap() : body;
        long startedAt = System.currentTimeMillis();
        try {
            this.analyticsLimits.requireEnabled();
            DatasetRef dataset = this.datasetResolver.resolve(request.get("connection"),
                request.get("path"));
            // Asked for only when named, so a query over one dataset pays for one. Either half
            // being present is enough to ask: a caller who sent half a second dataset has made a
            // mistake and is better told which half than silently given a one-dataset answer.
            DatasetRef second = null;
            if (hasText(request.get("connection2")) || hasText(request.get("path2"))) {
                second = this.datasetResolver.resolve(request.get("connection2"),
                    request.get("path2"));
            }
            QueryResultDto result = this.analyticsQueryService.query(dataset, second,
                request.get("sql"), request.get("queryId"));
            // Null-guarded rather than assumed. The service does not return null today, and a
            // history write is not a reason for a query that SUCCEEDED to come back as a 500.
            record(request, AnalyticsQueryRun.STATUS_SUCCESS,
                result == null ? null : (long) result.getRowCount(), startedAt, null);
            // The topic is called analytics.query.completed and, until this line, carried only
            // exports -- so a subscriber saw downloads and bucket writes and never a query, which
            // is not what the name promises. Destination.NONE exists for exactly this case and
            // was documented before it was reachable. Publishing cannot fail the read: the method
            // catches its own errors and hands off to a bounded queue.
            if (this.analyticsExportService != null && result != null) {
                this.analyticsExportService.publishQueryCompleted(dataset, second, "SUCCESS",
                    (long) result.getRowCount(), result.isTruncated(),
                    System.currentTimeMillis() - startedAt,
                    AnalyticsExportService.Destination.NONE, null);
            }
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "Query complete.",
                result), HttpStatus.OK);
        } catch (AnalyticsEngine.RunFailure ex) {
            // A failure that already knows which state it ended in, so this line no longer has to
            // guess. It used to: a query the watchdog stopped after thirty seconds was written to
            // history as REFUSED, which made it indistinguishable from one the governor never
            // started -- the user was told the truth and the row was not. A cancelled query would
            // have had the same problem, because DuckDB reports a timeout and a cancel with the
            // same "INTERRUPT Error: Interrupted!".
            //
            // The duration is measured to HERE, which for a cancelled run is the time up to the
            // stop. That is the honest number: it is how long the query actually ran.
            record(request, statusOf(ex), null, startedAt, ex.getMessage());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()),
                HttpStatus.OK);
        } catch (AnalyticsException ex) {
            // Includes every refusal from StatementGate and from DatasetResolver. A statement that
            // was not run is a business failure with a sentence attached, not a 500 and not a
            // stack trace.
            //
            // Recorded, not just returned. A refusal is the single most interesting row this
            // table will ever hold -- somebody tried to read a location they were not given -- and
            // from the logs alone a governor ceiling set too low for the people using the module
            // looks exactly like nobody using the module.
            record(request, AnalyticsQueryRun.STATUS_REFUSED, null, startedAt, ex.getMessage());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()),
                HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while running an analytics query.", ex);
            // The engine's own words never reach the column: the library service drops a message
            // naming a scheme or a credential, so passing the raw text would lose the row's
            // detail into a log line. The sentence the user was given is the one worth keeping.
            record(request, AnalyticsQueryRun.STATUS_FAILED, null, startedAt,
                ProcessUtil.INTERNAL_ERROR_500);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Stops a query the caller started and is still waiting on.
     *
     * <b>This is the first thing in the module a user can do to a query in flight</b>, and the
     * whole of it is that RunningQueries holds the run's Statement and DuckDB's cancel() genuinely
     * interrupts one -- measured, a CPU-bound query stopped at about 1006ms. setQueryTimeout does
     * nothing on this driver, so cancel() was already the only mechanism the timeout had; this
     * endpoint reaches the same mechanism from the user's side rather than building a second one.
     *
     * <b>Nothing here is scoped by role.</b> A run belongs to the tenant AND the user that started
     * it, and RunningQueries compares both against the caller's context before it touches a
     * statement -- so one workspace cannot stop another's work, which against a ceiling of four
     * concurrent permits would be a denial of service worth having. A run belonging to somebody
     * else is answered exactly as a run that has already finished, because saying "that is not
     * yours" would confirm the id exists.
     *
     * <b>Cancelling a query that has already finished is a success, not an error.</b> It is the
     * normal race -- a person presses stop as the last row lands -- and an error toast for it
     * would be the application blaming the user for its own timing. The response says which of
     * the two happened, for a client that wants to phrase it differently.
     *
     * No history row is written here. The request that is running the query writes it, from its
     * own catch, with the duration up to the stop; a second row from this side would double-count
     * one run.
     */
    @RequestMapping(value = "/query/{queryId}/cancel", method = RequestMethod.POST)
    public ResponseEntity<?> cancelQuery(@PathVariable(value = "queryId") String queryId) {
        try {
            this.analyticsLimits.requireEnabled();
            RunningQueries.Outcome outcome = this.analyticsQueryService.cancel(queryId);
            boolean stopped = outcome == RunningQueries.Outcome.CANCELLED;
            Map<String, String> state = new LinkedHashMap<>();
            state.put("queryId", queryId);
            state.put("status", stopped
                ? AnalyticsEngine.RunState.CANCELLED.name() : outcome.name());
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS,
                stopped ? "Query stopped." : "That query is no longer running.", state),
                HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while cancelling an analytics query.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * The history vocabulary for a state the engine reported.
     *
     * A switch rather than {@code state.name()} because the two vocabularies are deliberately not
     * the same word for the same thing: COMPLETED is stored as SUCCESS, which is what the column
     * already holds and what the screen's pill reads. Renaming stored evidence to match a spec's
     * spelling is the one edit an audit table should not take.
     */
    private static String statusOf(AnalyticsEngine.RunFailure failure) {
        switch (failure.getState()) {
            case CANCELLED: return AnalyticsQueryRun.STATUS_CANCELLED;
            case TIMED_OUT: return AnalyticsQueryRun.STATUS_TIMED_OUT;
            case REFUSED:   return AnalyticsQueryRun.STATUS_REFUSED;
            case COMPLETED: return AnalyticsQueryRun.STATUS_SUCCESS;
            default:        return AnalyticsQueryRun.STATUS_FAILED;
        }
    }

    /**
     * Writes the history row for a query attempt, whatever became of it.
     *
     * Here rather than in AnalyticsQueryService because a refusal from DatasetResolver never
     * reaches that service, and a refusal is exactly the attempt worth keeping. Failing to write
     * history must never fail the request that succeeded -- recordRun is REQUIRES_NEW and swallows
     * its own errors for that reason -- so this adds no catch of its own beyond the guard that a
     * missing library service (as in a unit test that does not care) is not an error.
     *
     * The SQL stored is the text as SUBMITTED, before the max-rows wrapper: a row that recorded
     * the rewritten statement would answer "what did they run" with something nobody wrote.
     */
    /**
     * Writes the history row for a dataset READ that is not a statement somebody wrote.
     *
     * Schema, preview and profile are reads of exactly the data a query reads, and until now they
     * left nothing behind but a log line -- so "who has read this file" could be answered for the
     * SQL console and not for the nine other tabs, which is most of how the file is actually
     * read. Document 15's audit-completeness row is that gap.
     *
     * <b>The descriptor is prefixed "-- " so it can never be mistaken for a statement.</b> It
     * lands in query_text, which everywhere else holds SQL as the caller submitted it; a bare
     * word there would eventually be read back as something somebody ran. As a comment it is
     * inert, it is obvious to a person reading the table, and `query_text LIKE '-- %'` separates
     * reads from statements without a schema change.
     *
     * Refusals are recorded the same way and for the same reason as query refusals: an attempt on
     * a dataset the caller could not reach is the attempt most worth keeping.
     *
     * <b>On volume.</b> This is one row per read, so opening a file writes two (schema, preview)
     * and three once a tab that profiles is opened, and every page turn writes another. A reader
     * paging through a 1,500-page dataset leaves 1,500 rows behind.
     *
     * That raises the RATE at which analytics_query_run grows by a large multiple; it does not
     * change the ARGUMENT V32__analytics_query.sql makes for leaving it unpruned. That changeset
     * named the condition that would change its mind -- "anything that lets a machine issue
     * queries on a schedule" -- and this is not that: every row here is still a person clicking
     * something, and the governor still caps even that at four concurrent queries. Retention is
     * available (analytics.history.retention-days) and OFF by default, because losing audit rows
     * is the one thing that cannot be undone later.
     */
    private void recordRead(String connection, String path, String descriptor, String status,
        Long rowCount, long startedAt, String message) {
        if (this.analyticsQueryLibraryService == null) {
            return;
        }
        AnalyticsQueryRun run = new AnalyticsQueryRun();
        run.setConnectionAlias(connection);
        run.setDatasetPath(path);
        run.setQueryText("-- " + descriptor);
        run.setRunStatus(status);
        run.setRowCount(rowCount);
        run.setDurationMs(System.currentTimeMillis() - startedAt);
        run.setErrorMessage(message);
        this.analyticsQueryLibraryService.recordRun(run);
    }

    private void record(Map<String, String> request, String status, Long rowCount,
        long startedAt, String message) {
        if (this.analyticsQueryLibraryService == null) {
            return;
        }
        AnalyticsQueryRun run = new AnalyticsQueryRun();
        run.setConnectionAlias(request.get("connection"));
        run.setDatasetPath(request.get("path"));
        run.setQueryText(request.get("sql"));
        run.setRunStatus(status);
        run.setRowCount(rowCount);
        run.setDurationMs(System.currentTimeMillis() - startedAt);
        run.setErrorMessage(message);
        this.analyticsQueryLibraryService.recordRun(run);
    }

    /**
     * ASC or DESC, and never anything else that could be written into an ORDER BY.
     *
     * A String parameter converted here rather than an enum parameter Spring converts, because
     * Spring answers a bad enum value with a 400 and a stack trace in the log. A direction of
     * "sideways" is a caller mistake, and a caller mistake in this module is an OK carrying
     * ERROR and a sentence -- the same shape every other refusal on this controller has.
     *
     * The offending value is not echoed. It is the caller's own string, it would be rendered into a
     * page by somebody else's code, and there are exactly two right answers to name instead.
     */
    private static AnalyticsEngine.PreviewShape.Direction directionOf(String requested)
        throws AnalyticsException {

        if (!hasText(requested)) {
            return null;
        }
        String named = requested.trim().toUpperCase(Locale.ROOT);
        if (AnalyticsEngine.PreviewShape.Direction.ASC.name().equals(named)) {
            return AnalyticsEngine.PreviewShape.Direction.ASC;
        }
        if (AnalyticsEngine.PreviewShape.Direction.DESC.name().equals(named)) {
            return AnalyticsEngine.PreviewShape.Direction.DESC;
        }
        throw new AnalyticsException("A column is sorted ASC or DESC.");
    }

    /**
     * The filters parameter, read as the Canvas's own filter model.
     *
     * <b>Deserialised into {@link FilterClause} rather than into a map, and that is the whole
     * reuse.</b> The same class the Canvas accepts means the same fourteen operators, the same
     * String-typed values -- which is what keeps an eighteen-digit id from becoming a double before
     * anything has looked at it -- and the same compiler downstream. An array element may itself be
     * a group, so a caller that wants an OR has one without this endpoint inventing a syntax for it.
     *
     * Nothing is validated here. A field name means nothing without the dataset's schema, and the
     * schema is read inside the engine's session; a check made here would be a second allow-list
     * built from a second query, which is both an extra permit and a second thing to keep in step.
     */
    private static List<FilterClause> filtersOf(String json) throws AnalyticsException {
        if (!hasText(json)) {
            return null;
        }
        try {
            return JSON.readValue(json, new TypeReference<List<FilterClause>>() { });
        } catch (IOException ex) {
            // The parser's own message names offsets into a string the caller sent and is written
            // for somebody holding it. What a person can act on is the shape that was expected.
            throw new AnalyticsException("Those filters could not be read. Filters are a JSON array "
                + "of {field, operator, value}, and the operator has to be one this module knows.");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

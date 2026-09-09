package process.api;

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
import process.analytics.dto.QueryResultDto;
import process.model.dto.ResponseDto;
import process.model.pojo.AnalyticsQueryRun;
import process.model.service.AnalyticsQueryLibraryService;
import process.util.ProcessUtil;

import java.util.Collections;
import java.util.LinkedHashMap;
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
        try {
            this.analyticsLimits.requireEnabled();
            DatasetRef dataset = this.datasetResolver.resolve(connection, path);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "Schema read.",
                this.analyticsQueryService.schemaOf(dataset)), HttpStatus.OK);
        } catch (AnalyticsException ex) {
            // Written for a reader by whoever threw it, so it is returned as-is. A business
            // failure is an OK with status ERROR, matching every other endpoint here.
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()),
                HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while reading a dataset schema.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * One page of rows, plus the total so the caller can page without guessing.
     *
     * The page size is a request, not an instruction: AnalyticsQueryService clamps it, so a
     * caller asking for a million rows receives the configured maximum instead.
     *
     * knownTotal is the same bargain in the other direction. A client turning a page already has
     * the total from the response it is turning away from, and counting it again costs a second
     * session and a second governor permit for a number the browser is displaying. Passing it
     * back halves the cost of every page turn. It is optional and unvalidated on purpose -- the
     * only way to check it is to run the count this parameter exists to skip -- so it is treated
     * as a display value, never as anything a decision rests on.
     */
    @RequestMapping(value = "/preview", method = RequestMethod.GET)
    public ResponseEntity<?> preview(
        @RequestParam(value = "connection") String connection,
        @RequestParam(value = "path") String path,
        @RequestParam(value = "page", required = false, defaultValue = "0") Integer page,
        @RequestParam(value = "pageSize", required = false) Integer pageSize,
        @RequestParam(value = "knownTotal", required = false) Integer knownTotal) {
        try {
            this.analyticsLimits.requireEnabled();
            DatasetRef dataset = this.datasetResolver.resolve(connection, path);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "Dataset read.",
                this.analyticsQueryService.preview(dataset, page == null ? 0 : page, pageSize,
                    knownTotal)), HttpStatus.OK);
        } catch (AnalyticsException ex) {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()),
                HttpStatus.OK);
        } catch (Exception ex) {
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
        try {
            this.analyticsLimits.requireEnabled();
            DatasetRef dataset = this.datasetResolver.resolve(connection, path);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "Dataset profiled.",
                this.analyticsQueryService.profileOf(dataset)), HttpStatus.OK);
        } catch (AnalyticsException ex) {
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()),
                HttpStatus.OK);
        } catch (Exception ex) {
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

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

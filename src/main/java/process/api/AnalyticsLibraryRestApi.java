package process.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import process.analytics.AnalyticsLimits;
import process.model.dto.ResponseDto;
import process.model.pojo.AnalyticsQuery;
import process.model.service.AnalyticsQueryLibraryService;
import process.util.ProcessUtil;

/**
 * Analytics Studio's library: naming a query, finding it again, and seeing what has been run.
 *
 * A separate controller from AnalyticsRestApi and not an addition to it, because the two answer
 * different questions and only one of them touches the engine. Nothing here opens a DuckDB
 * session, builds a scan or takes a governor slot -- and, as over there, <b>there is deliberately
 * no endpoint here that accepts SQL for execution</b>. A saved query's text is stored and handed
 * back as text; running it is AnalyticsQueryService's job, under the session lock-down and the
 * governor, which is the one door this feature has.
 *
 * There is also no endpoint that writes history. A run is recorded by the code that ran the
 * query, from what it observed; if a client could post one, the record of who read what would be
 * forgeable, and an audit trail people rely on and can forge is worse than none.
 *
 * The role is only the floor, and it is the same floor as AnalyticsRestApi's for the same reason:
 * TENANT_USER matches the storage browser this reads through, and which rows a caller may reach
 * is not a question a role can answer. That is settled per request, against the row's own tenant,
 * by AnalyticsQueryLibraryService.
 *
 * @author Nabeel Ahmed
 * */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/analyticsLibrary.json")
@PreAuthorize("hasRole('TENANT_USER')")
public class AnalyticsLibraryRestApi {

    private final Logger logger = LoggerFactory.getLogger(AnalyticsLibraryRestApi.class);

    private final AnalyticsQueryLibraryService analyticsQueryLibraryService;

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

    public AnalyticsLibraryRestApi(AnalyticsQueryLibraryService analyticsQueryLibraryService,
        AnalyticsLimits analyticsLimits) {
        this.analyticsQueryLibraryService = analyticsQueryLibraryService;
        this.analyticsLimits = analyticsLimits;
    }

    @RequestMapping(value = "/fetchAllQueries", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAllQueries() {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(this.analyticsQueryLibraryService.fetchAllQueries(), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while fetchAllQueries ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/fetchQueryById", method = RequestMethod.GET)
    public ResponseEntity<?> fetchQueryById(@RequestParam Long analyticsQueryId) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(this.analyticsQueryLibraryService.fetchQueryById(analyticsQueryId), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while fetchQueryById ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * The entity is bound straight from the body, which is safe only because the service does not
     * save what it is handed: it copies the four fields a person can decide -- name, connection
     * alias, path and SQL -- onto a row whose tenant and audit columns come from the signed-in
     * context. A tenantId or a createdBy on the wire is read by nothing.
     */
    @RequestMapping(value = "/saveQuery", method = RequestMethod.POST)
    public ResponseEntity<?> saveQuery(@RequestBody AnalyticsQuery analyticsQuery) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(this.analyticsQueryLibraryService.saveQuery(analyticsQuery), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while saveQuery ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/renameQuery", method = RequestMethod.PUT)
    public ResponseEntity<?> renameQuery(@RequestParam Long analyticsQueryId,
        @RequestParam String queryName) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(this.analyticsQueryLibraryService.renameQuery(analyticsQueryId, queryName), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while renameQuery ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/deleteQuery", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteQuery(@RequestParam Long analyticsQueryId) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(this.analyticsQueryLibraryService.deleteQuery(analyticsQueryId), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while deleteQuery ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Recent runs, newest first. Both parameters are optional: no analyticsQueryId means the
     * whole workspace's history, and the limit is clamped by the service rather than trusted.
     */
    @RequestMapping(value = "/fetchRecentRuns", method = RequestMethod.GET)
    public ResponseEntity<?> fetchRecentRuns(
        @RequestParam(value = "analyticsQueryId", required = false) Long analyticsQueryId,
        @RequestParam(value = "limit", required = false) Integer limit) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(this.analyticsQueryLibraryService.fetchRecentRuns(analyticsQueryId, limit), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while fetchRecentRuns ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}

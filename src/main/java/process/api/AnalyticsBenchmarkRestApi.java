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
import process.analytics.AnalyticsBenchmarkService;
import process.analytics.AnalyticsException;
import process.analytics.AnalyticsLimits;
import process.model.dto.ResponseDto;
import process.util.ProcessUtil;

/**
 * Analytics Studio's benchmark: measuring what a read actually costs here, and reading the results.
 *
 * <b>The role floor is PLATFORM_ADMIN, and it is the one decision on this class worth arguing
 * about.</b> Every other analytics endpoint sits at TENANT_USER, matching the storage browser they
 * borrow their connections from. This one sits three steps higher, for three reasons that compound.
 *
 * First, it is a load generator by construction rather than by accident. A benchmark's whole method
 * is to perform the same read over and over -- a file open is three governed sessions, and a
 * request may ask for ten runs across four datasets. The runs are sequential, so it does not
 * saturate the governor; it does something quieter and longer, which is to hold one of four permits
 * continuously for the whole request. A ceiling of four with one permit permanently spoken for is a
 * ceiling of three, and a single user opening a file already costs three.
 *
 * Second, that ceiling is per JVM and not per workspace -- a known, recorded shape of the module,
 * and tolerable while every caller is doing ordinary work. Handing any tenant a supported way to
 * hold a permit for minutes at a time turns a shared-ceiling annoyance into a documented method for
 * one workspace to degrade every other workspace on the box, at a moment of its choosing.
 *
 * Third, the question is not a tenant's question. "Is Parquet faster than CSV on this deployment,
 * and what does a session cost here" is asked by whoever operates the deployment, about the
 * deployment. A tenant benchmarking their own file learns something real, but not something they
 * can act on -- and the results table is keyed to the machine, not to their data.
 *
 * TENANT_ADMIN was the obvious middle answer and was rejected: a tenant administrator is an
 * administrator of a workspace, and nothing about that role says anything about the shared JVM this
 * endpoint spends. The read sits at the same floor because every row this table holds is written by
 * that same platform-only path, so a lower floor on the read would be a door onto an empty room --
 * and one that quietly became a door onto somebody else's rows if the write floor ever moved.
 *
 * Nothing here opens a session, builds a scan or decides what a statement may be. The benchmark
 * runs through AnalyticsQueryService like every other read in the module, under the same lock-down,
 * the same governor and the same statement gate -- a harness with its own JDBC connection would be
 * measuring something no user can ask for, and would be the second door beside the locked one that
 * this feature is shaped to prevent.
 *
 * @author Nabeel Ahmed
 * */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/analyticsBenchmark.json")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class AnalyticsBenchmarkRestApi {

    private final Logger logger = LoggerFactory.getLogger(AnalyticsBenchmarkRestApi.class);

    private final AnalyticsBenchmarkService analyticsBenchmarkService;

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

    public AnalyticsBenchmarkRestApi(AnalyticsBenchmarkService analyticsBenchmarkService,
        AnalyticsLimits analyticsLimits) {
        this.analyticsBenchmarkService = analyticsBenchmarkService;
        this.analyticsLimits = analyticsLimits;
    }

    /**
     * Runs a benchmark and returns the rows it wrote.
     *
     * POST with a body rather than GET with parameters, for the same reason the query endpoint is:
     * a QUERY benchmark carries SQL somebody composed, and SQL in a query string is SQL in the
     * access log, the browser history and every proxy in between. It is also not a read -- it
     * writes rows and it generates load -- so a GET would be the wrong verb twice over.
     *
     * The body is a label, a measure, the datasets, and optionally the SQL, the run count and the
     * warmup count. There is deliberately no default measure: FILE_OPEN and QUERY differ by more
     * than any file format does, and a harness that picked one silently would fill a table with
     * durations nobody could later account for.
     *
     * The success message names what these numbers do not cover. That sentence is not decoration:
     * the module makes two speed claims and this harness can only test one of them, and a result
     * quoted for the untested claim would be an argument about the architecture won with evidence
     * that was never gathered.
     */
    @RequestMapping(value = "/runBenchmark", method = RequestMethod.POST)
    public ResponseEntity<?> runBenchmark(
        @RequestBody(required = false) AnalyticsBenchmarkService.BenchmarkRequest request) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS,
                "Benchmark complete. " + AnalyticsBenchmarkService.whatThisDidNotMeasure(),
                this.analyticsBenchmarkService.run(request)), HttpStatus.OK);
        } catch (AnalyticsException ex) {
            // Written for a reader by whoever threw it, so it is returned as-is. A refused
            // benchmark -- too many sessions projected, too few runs to show a spread, a
            // connection the caller cannot reach -- is a business failure and an OK with status
            // ERROR, matching every other endpoint in the module.
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()),
                HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while running an analytics benchmark.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Measurements already taken: one batch, one label, or the most recent of everything.
     *
     * batchId is the one to reach for first. The rows written by a single invocation are the only
     * rows that may honestly be compared with each other -- same JVM, same object store, minutes
     * apart -- and a listing by label spans batches, which is exactly when a reader has to check
     * that they are not comparing September against November.
     *
     * The window is clamped by the service rather than taken as given, because nothing prunes this
     * table.
     */
    @RequestMapping(value = "/fetchRecentResults", method = RequestMethod.GET)
    public ResponseEntity<?> fetchRecentResults(
        @RequestParam(value = "batchId", required = false) String batchId,
        @RequestParam(value = "label", required = false) String label,
        @RequestParam(value = "limit", required = false) Integer limit) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS,
                "Data fetched successfully. " + AnalyticsBenchmarkService.whatThisDidNotMeasure(),
                this.analyticsBenchmarkService.recentResults(batchId, label, limit)),
                HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while fetching analytics benchmark results.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}

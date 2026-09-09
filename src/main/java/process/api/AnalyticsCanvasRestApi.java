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
import org.springframework.web.bind.annotation.RestController;
import process.analytics.AnalyticsEngine;
import process.analytics.AnalyticsException;
import process.analytics.AnalyticsLimits;
import process.analytics.canvas.AnalysisRequest;
import process.analytics.canvas.AnalysisService;
import process.model.dto.ResponseDto;
import process.util.ProcessUtil;

@RestController
@CrossOrigin(origins = "*")
/**
 * The Analytics Canvas: dimensions, a measure, filters, and the two ways to move through them.
 *
 * <b>Three endpoints and one operation.</b> /analyze runs an analysis; /analyze/drill runs the same
 * analysis one step narrower; /analyze/drill-up runs it some steps wider. They differ only in what
 * they do to the drill trail, which is why they are three routes over one service method rather
 * than three code paths -- a drilled analysis that could fail differently from an undrilled one
 * would be a second implementation of the same feature.
 *
 * <b>Everything the SQL endpoint's paragraph claims is claimed here too, and more cheaply.</b> The
 * body names a storage CONNECTION and a path inside it, never a bucket or a URL, so DatasetResolver
 * decides whether the caller may read it at all before anything is opened. The query goes through
 * AnalyticsEngine's governed path -- one permit, the locked-down session, the enforced timeout, a
 * registry entry a stop button can reach -- rather than opening a door beside it. What is different
 * is that nobody writes the statement: the request is a model, and the SQL is generated from it
 * against the dataset's own schema, which is what 07 asks for and is a stronger position than
 * checking a statement a user wrote.
 *
 * <b>Why POST for all three, including the ones that only read.</b> The same reason /query is a
 * POST: a filter's operands are a customer's data, and a GET would put them in the access log, the
 * browser history and every proxy in between. A body also happens to be the only shape a nested
 * AND/OR tree fits into.
 *
 * The role is the floor and not the answer. TENANT_USER matches the storage browser these datasets
 * come from; which connections a caller may reach is settled per request against the connection's
 * own tenant, by the resolver.
 *
 * @author Nabeel Ahmed
 * */
@RequestMapping(value = "/analytics.json")
@PreAuthorize("hasRole('TENANT_USER')")
public class AnalyticsCanvasRestApi {

    private final Logger logger = LoggerFactory.getLogger(AnalyticsCanvasRestApi.class);

    private final AnalysisService analysisService;

    /**
     * The feature switch, checked at the boundary like every other analytics endpoint.
     *
     * Wave one found analytics.enabled wired at six controllers and this is the seventh. A route
     * that served while the health check reported the feature off is the defect that fix exists to
     * prevent, and a new controller is exactly where it comes back.
     */
    private final AnalyticsLimits analyticsLimits;

    public AnalyticsCanvasRestApi(AnalysisService analysisService, AnalyticsLimits analyticsLimits) {
        this.analysisService = analysisService;
        this.analyticsLimits = analyticsLimits;
    }

    /**
     * An analysis: up to three dimensions, one measure, a filter tree, a sort and a Top-N.
     *
     * The response carries the crumbs and the drill trail even when nothing has been drilled, so a
     * client's rendering of the breadcrumb bar is the same code on the first request as on the
     * fifth. It carries the pivot grid when there are exactly two dimensions, so a grid never costs
     * a second query.
     */
    @RequestMapping(value = "/analyze", method = RequestMethod.POST)
    public ResponseEntity<?> analyze(@RequestBody(required = false) AnalysisRequest request) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "Analysis complete.",
                this.analysisService.analyze(request)), HttpStatus.OK);
        } catch (AnalyticsException ex) {
            return this.refused(ex);
        } catch (Exception ex) {
            return this.failed(ex);
        }
    }

    /**
     * One step in: the clicked value narrows the analysis and the dimension it sat on is replaced.
     *
     * <b>The server composes it, which is the requirement rather than a convenience.</b> A client
     * that turned a click into a filter itself would be computing analytical state it did not
     * compute -- and the first time its idea of the accumulated filters differed from the server's,
     * the chart and the breadcrumb above it would be describing different questions. Here the click
     * is a dimension, a value and an optional next dimension; everything it implies is derived from
     * the trail on this side.
     */
    @RequestMapping(value = "/analyze/drill", method = RequestMethod.POST)
    public ResponseEntity<?> drill(@RequestBody(required = false) AnalysisRequest request) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "Analysis narrowed.",
                this.analysisService.drill(request)), HttpStatus.OK);
        } catch (AnalyticsException ex) {
            return this.refused(ex);
        } catch (Exception ex) {
            return this.failed(ex);
        }
    }

    /**
     * Back out: the last steps are removed and the dimensions they replaced come back.
     *
     * Clicking the first breadcrumb sends more steps than there are, and that is answered as the
     * whole trail rather than as a mistake -- see AnalysisService.drillUp.
     */
    @RequestMapping(value = "/analyze/drill-up", method = RequestMethod.POST)
    public ResponseEntity<?> drillUp(@RequestBody(required = false) AnalysisRequest request) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.SUCCESS, "Analysis widened.",
                this.analysisService.drillUp(request)), HttpStatus.OK);
        } catch (AnalyticsException ex) {
            return this.refused(ex);
        } catch (Exception ex) {
            return this.failed(ex);
        }
    }

    /**
     * A refusal, which is an HTTP 200 with status ERROR and the sentence whoever threw it wrote.
     *
     * Every AnalyticsException on this path was written for a person to read -- "amount holds
     * VARCHAR, which cannot be summed", "this dataset has no column called ..." -- and rewording
     * them here would replace a specific answer with a generic one. That includes
     * {@link AnalyticsEngine.RunFailure}, whose state matters to the SQL endpoint because it writes
     * a history row; there is no analysis history table, so the state is already in the sentence.
     */
    private ResponseEntity<?> refused(AnalyticsException ex) {
        return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE, ex.getMessage()),
            HttpStatus.OK);
    }

    private ResponseEntity<?> failed(Exception ex) {
        this.logger.error("An error occurred while running an analysis.", ex);
        return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
            ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
    }
}

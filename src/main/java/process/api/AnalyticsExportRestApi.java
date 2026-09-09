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
import process.analytics.AnalyticsLimits;
import process.analytics.AnalyticsExportService;
import process.model.dto.ResponseDto;
import process.util.ProcessUtil;

import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
/**
 * Analytics Studio: getting a result out.
 *
 * Two destinations and deliberately not three. A result can be downloaded, or written back into the
 * storage connection it came from. There is no endpoint here that posts a result to an address a
 * caller supplies, and that absence is a decision rather than an omission: ReportExportServiceImpl
 * has such a destination and reports gaps 1-4 are open against it -- a TENANT_USER can make this
 * server POST to any URL they type and read the reply back, past an address guard weaker than the
 * one its sibling uses, on an HTTP client with no timeouts. Building the same destination here
 * would have inherited all four by copy. If it is ever wanted, it is a new design and not a third
 * case in a switch.
 *
 * The body carries the same fields /analytics.json/query takes -- connection, path, sql, and
 * connection2 and path2 for a join -- so a person exports exactly the query they just ran. It never
 * carries a bucket and never carries a URL, for the reason AnalyticsRestApi states about every
 * other endpoint in the module: the bucket comes from the connection record, so "use these
 * credentials somewhere else" is not a request this API can express. Write-back adds a folder and a
 * file name, and both are names inside that same bucket.
 *
 * POST for both, and not because one of them writes. SQL in a query string is SQL in the access
 * log, in the browser history and in every proxy in between, and it is the one field on this API a
 * person composes themselves.
 *
 * <b>The roles differ, and that is the only interesting thing about this class.</b> Downloading is
 * TENANT_USER, matching every read in the module: a caller who can already see the rows on screen
 * has not gained anything by receiving them as a file. Writing is TENANT_ADMIN, because it is not a
 * read -- it puts an object into a bucket that other people's pipelines read, next to data somebody
 * depends on, and storage connections are administered at TENANT_ADMIN already
 * (StorageConnectionRestApi:27). The class-level annotation is the floor for both; the method-level
 * one on writeBack raises it, and because @PreAuthorize is not repeatable it REPLACES the class
 * annotation on that method rather than adding to it -- which is safe here only because
 * TENANT_ADMIN sits above TENANT_USER in MethodSecurityConfig's hierarchy. Deleting that hierarchy
 * would silently widen this endpoint, so the test asserts the annotation and the hierarchy both.
 *
 * @author Nabeel Ahmed
 * */
@RequestMapping(value = "/analyticsExport.json")
@PreAuthorize("hasRole('TENANT_USER')")
public class AnalyticsExportRestApi {

    private final Logger logger = LoggerFactory.getLogger(AnalyticsExportRestApi.class);

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

    public AnalyticsExportRestApi(AnalyticsExportService analyticsExportService,
        AnalyticsLimits analyticsLimits) {
        this.analyticsExportService = analyticsExportService;
        this.analyticsLimits = analyticsLimits;
    }

    /**
     * The result as a file, handed back for the browser to save.
     *
     * The response carries the bytes base64-encoded inside the usual ResponseDto rather than as a
     * streamed attachment, which is what /report.json/export already does and what the shared
     * error handling on this API expects: a business failure has to be able to arrive as an HTTP
     * 200 with status ERROR, and a response whose body is a file has nowhere to put one.
     *
     * A download that stopped at the row ceiling still succeeds, and says so three times over --
     * in the message, in a truncated flag, and inside the file itself. It is not an error: the
     * rows are real and the user asked for them. It is an incomplete answer, and the difference
     * between those two is the whole reason this endpoint says anything at all.
     */
    @RequestMapping(value = "/download", method = RequestMethod.POST)
    public ResponseEntity<?> download(@RequestBody(required = false) Map<String, String> body) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(this.analyticsExportService.download(body), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while exporting an analytics result.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * The result written into the connection's own bucket.
     *
     * TENANT_ADMIN, for the reason in the class comment. The service returns a business failure as
     * a ResponseDto with status ERROR -- a refused key, a query over the export ceiling, a bucket
     * the credentials cannot write to -- so the only thing this catch is for is the unexpected.
     */
    @RequestMapping(value = "/writeBack", method = RequestMethod.POST)
    @PreAuthorize("hasRole('TENANT_ADMIN')")
    public ResponseEntity<?> writeBack(@RequestBody(required = false) Map<String, String> body) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(this.analyticsExportService.writeBack(body), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while writing an analytics result back to storage.", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}

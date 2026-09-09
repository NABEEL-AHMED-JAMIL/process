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
import process.model.pojo.AnalyticsDataset;
import process.model.service.AnalyticsDatasetService;
import process.util.ProcessUtil;

/**
 * Analytics Studio's dataset registry: naming a location once, and finding it again.
 *
 * Spec 09 names these endpoints {@code GET /datasets} and {@code GET /datasets/{id}}; they are
 * spelled here the way every other controller in this application spells its endpoints, which is
 * what that document's own first line asks for.
 *
 * <b>The id these endpoints introduce is new to this module and needs saying out loud.</b> Every
 * other analytics endpoint addresses data by connection alias and path, deliberately, so that
 * "read a different bucket with these credentials" has no field to be written in. A registry has
 * ids by necessity -- that is what a registry is -- and the moment ids appear on the wire, spec
 * 11's clause about never letting a caller reach another tenant's dataset by guessing an
 * identifier stops being hypothetical. It is answered in the service: a row the caller does not
 * own is refused with the same sentence as a row that does not exist, so walking the id space
 * tells a caller nothing about which datasets other workspaces hold.
 *
 * Nothing here opens a DuckDB session, builds a scan or takes a governor slot, and <b>there is
 * deliberately no endpoint here that reads a dataset's contents</b>. Registering names a
 * location; reading one is AnalyticsRestApi's, under the session lock-down and the governor,
 * which is the one door this feature has.
 *
 * The role is only the floor, and it is the same floor as AnalyticsRestApi's for the same reason:
 * TENANT_USER matches the storage browser these datasets are read through, and which rows a
 * caller may reach is not a question a role can answer. That is settled per request, against the
 * row's own tenant, by AnalyticsDatasetService.
 *
 * @author Nabeel Ahmed
 * */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/analyticsDataset.json")
@PreAuthorize("hasRole('TENANT_USER')")
public class AnalyticsDatasetRestApi {

    private final Logger logger = LoggerFactory.getLogger(AnalyticsDatasetRestApi.class);

    private final AnalyticsDatasetService analyticsDatasetService;

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

    public AnalyticsDatasetRestApi(AnalyticsDatasetService analyticsDatasetService,
        AnalyticsLimits analyticsLimits) {
        this.analyticsDatasetService = analyticsDatasetService;
        this.analyticsLimits = analyticsLimits;
    }

    @RequestMapping(value = "/fetchAllDatasets", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAllDatasets() {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(this.analyticsDatasetService.fetchAllDatasets(), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while fetchAllDatasets ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/fetchDatasetById", method = RequestMethod.GET)
    public ResponseEntity<?> fetchDatasetById(@RequestParam Long analyticsDatasetId) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(this.analyticsDatasetService.fetchDatasetById(analyticsDatasetId), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while fetchDatasetById ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * The entity is bound straight from the body, which is safe only because the service does not
     * save what it is handed: it copies the three fields a person can decide -- name, connection
     * alias and path -- onto a row whose tenant and audit columns come from the signed-in context
     * and whose format label comes from the resolver. A tenantId, a createdBy or a datasetFormat
     * on the wire is read by nothing.
     */
    @RequestMapping(value = "/registerDataset", method = RequestMethod.POST)
    public ResponseEntity<?> registerDataset(@RequestBody AnalyticsDataset analyticsDataset) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(this.analyticsDatasetService.registerDataset(analyticsDataset), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while registerDataset ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/deleteDataset", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteDataset(@RequestParam Long analyticsDatasetId) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(this.analyticsDatasetService.deleteDataset(analyticsDatasetId), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while deleteDataset ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}

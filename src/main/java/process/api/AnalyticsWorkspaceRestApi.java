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
import process.model.pojo.AnalyticsAnalysis;
import process.model.pojo.AnalyticsDashboard;
import process.model.pojo.AnalyticsDashboardWidget;
import process.model.service.AnalyticsWorkspaceService;
import process.util.ProcessUtil;

/**
 * Analytics Studio's workspace: saved analyses, dashboards and the widgets on them.
 *
 * A separate controller from AnalyticsRestApi and from AnalyticsLibraryRestApi, and not an
 * addition to either, because the three answer different questions and only one of them touches
 * the engine. Nothing here opens a DuckDB session, builds a scan or takes a governor slot, and
 * <b>there is deliberately no endpoint here that runs an analysis</b>. A saved analysis's
 * configuration is stored and handed back as text; executing one belongs behind the session
 * lock-down and the governor, which is the one door this feature has.
 *
 * <b>What these endpoints do not settle.</b> .ai/synthesis/analytics-studio.md section 6, Q2 asks
 * whether Analytics Studio should own a charting stack at all or whether its results should feed
 * the existing /reports pivot, and records that the question is open and the user has not decided.
 * Nothing here answers it: a widget carries a title, a reference and an opaque rendering
 * configuration, and no chart kind or drawing rule appears in this module's schema. If Q2 lands
 * on the pivot, these endpoints store a saved arrangement of results and the drawing happens
 * elsewhere.
 *
 * The role is only the floor, and it is the same floor as the other two analytics controllers
 * for the same reason: TENANT_USER matches the storage browser this work is read through, and
 * which rows a caller may reach is not a question a role can answer. That is settled per request,
 * against the row's own tenant, by AnalyticsWorkspaceService -- which for a widget also settles
 * the question a per-row check cannot answer on its own, whether the thing it points at belongs
 * to the same workspace as the dashboard it sits on.
 *
 * @author Nabeel Ahmed
 * */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping(value = "/analyticsWorkspace.json")
@PreAuthorize("hasRole('TENANT_USER')")
public class AnalyticsWorkspaceRestApi {

    private final Logger logger = LoggerFactory.getLogger(AnalyticsWorkspaceRestApi.class);

    private final AnalyticsWorkspaceService analyticsWorkspaceService;

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

    public AnalyticsWorkspaceRestApi(AnalyticsWorkspaceService analyticsWorkspaceService,
        AnalyticsLimits analyticsLimits) {
        this.analyticsWorkspaceService = analyticsWorkspaceService;
        this.analyticsLimits = analyticsLimits;
    }

    @RequestMapping(value = "/fetchAllAnalyses", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAllAnalyses() {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(this.analyticsWorkspaceService.fetchAllAnalyses(), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while fetchAllAnalyses ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/fetchAnalysisById", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAnalysisById(@RequestParam Long analyticsAnalysisId) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(this.analyticsWorkspaceService.fetchAnalysisById(analyticsAnalysisId), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while fetchAnalysisById ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * The entity is bound straight from the body, which is safe only because the service does not
     * save what it is handed: it copies the fields a person can decide onto a row whose tenant and
     * audit columns come from the signed-in context. A tenantId or a createdBy on the wire is read
     * by nothing.
     */
    @RequestMapping(value = "/saveAnalysis", method = RequestMethod.POST)
    public ResponseEntity<?> saveAnalysis(@RequestBody AnalyticsAnalysis analyticsAnalysis) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(this.analyticsWorkspaceService.saveAnalysis(analyticsAnalysis), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while saveAnalysis ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/deleteAnalysis", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteAnalysis(@RequestParam Long analyticsAnalysisId) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(this.analyticsWorkspaceService.deleteAnalysis(analyticsAnalysisId), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while deleteAnalysis ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/fetchAllDashboards", method = RequestMethod.GET)
    public ResponseEntity<?> fetchAllDashboards() {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(this.analyticsWorkspaceService.fetchAllDashboards(), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while fetchAllDashboards ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /** One dashboard with its widgets in display order. */
    @RequestMapping(value = "/fetchDashboardById", method = RequestMethod.GET)
    public ResponseEntity<?> fetchDashboardById(@RequestParam Long analyticsDashboardId) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(this.analyticsWorkspaceService.fetchDashboardById(analyticsDashboardId), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while fetchDashboardById ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/saveDashboard", method = RequestMethod.POST)
    public ResponseEntity<?> saveDashboard(@RequestBody AnalyticsDashboard analyticsDashboard) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(this.analyticsWorkspaceService.saveDashboard(analyticsDashboard), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while saveDashboard ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/deleteDashboard", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteDashboard(@RequestParam Long analyticsDashboardId) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(this.analyticsWorkspaceService.deleteDashboard(analyticsDashboardId), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while deleteDashboard ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * Puts a saved analysis or a saved query on a dashboard, or edits a widget already there.
     *
     * The three ids in the body are all checked by the service before anything is written -- the
     * dashboard, the source, and that the two are in the same workspace -- so a widget cannot be
     * used to render one workspace's saved work inside another's page.
     */
    @RequestMapping(value = "/saveWidget", method = RequestMethod.POST)
    public ResponseEntity<?> saveWidget(@RequestBody AnalyticsDashboardWidget analyticsDashboardWidget) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(this.analyticsWorkspaceService.saveWidget(analyticsDashboardWidget), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while saveWidget ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @RequestMapping(value = "/deleteWidget", method = RequestMethod.DELETE)
    public ResponseEntity<?> deleteWidget(@RequestParam Long analyticsDashboardWidgetId) {
        try {
            this.analyticsLimits.requireEnabled();
            return new ResponseEntity<>(this.analyticsWorkspaceService.deleteWidget(analyticsDashboardWidgetId), HttpStatus.OK);
        } catch (Exception ex) {
            this.logger.error("An error occurred while deleteWidget ", ex);
            return new ResponseEntity<>(new ResponseDto(ProcessUtil.ERROR_MESSAGE,
                ProcessUtil.INTERNAL_ERROR_500), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}

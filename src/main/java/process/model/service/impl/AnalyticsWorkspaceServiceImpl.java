package process.model.service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import process.model.dto.ResponseDto;
import process.model.pojo.AnalyticsAnalysis;
import process.model.pojo.AnalyticsDashboard;
import process.model.pojo.AnalyticsDashboardWidget;
import process.model.pojo.AnalyticsQuery;
import process.model.repository.AnalyticsAnalysisRepository;
import process.model.repository.AnalyticsDashboardRepository;
import process.model.repository.AnalyticsDashboardWidgetRepository;
import process.model.repository.AnalyticsQueryRepository;
import process.model.service.AnalyticsWorkspaceService;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.security.TenantOwnership;
import process.util.UserNameResolver;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static process.util.ProcessUtil.ERROR;
import static process.util.ProcessUtil.SUCCESS;
import static process.util.ProcessUtil.isNull;

/**
 * @author Nabeel Ahmed
 * */
@Service
public class AnalyticsWorkspaceServiceImpl implements AnalyticsWorkspaceService {

    private final Logger logger = LoggerFactory.getLogger(AnalyticsWorkspaceServiceImpl.class);

    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_ALIAS_LENGTH = 255;
    private static final int MAX_VISUALIZATION_LENGTH = 32;

    /**
     * How large a stored configuration may be.
     *
     * The column is TEXT, so the database will take a megabyte without complaining, and an
     * analysis configuration that needs one is not a configuration -- it is data that has found
     * its way into a metadata table. Spec 07's whole vocabulary is three dimensions, a handful of
     * measures and a filter tree; the ceiling is generous by two orders of magnitude and exists
     * only to stop this becoming somewhere to park a result set.
     */
    private static final int MAX_CONFIG_LENGTH = 64000;

    @PersistenceContext
    private EntityManager entityManager;

    private final AnalyticsAnalysisRepository analyticsAnalysisRepository;
    private final AnalyticsDashboardRepository analyticsDashboardRepository;
    private final AnalyticsDashboardWidgetRepository analyticsDashboardWidgetRepository;

    /**
     * Read-only, and only to answer one question: does this saved query belong to the workspace
     * whose dashboard is about to show it? Nothing here saves, renames or deletes a saved query
     * -- that is AnalyticsQueryLibraryService's, and a second writer for one table is how two
     * services come to disagree about what saving means.
     */
    private final AnalyticsQueryRepository analyticsQueryRepository;

    private final TenantFilterHelper tenantFilterHelper;
    private final UserNameResolver userNameResolver;

    public AnalyticsWorkspaceServiceImpl(AnalyticsAnalysisRepository analyticsAnalysisRepository,
        AnalyticsDashboardRepository analyticsDashboardRepository,
        AnalyticsDashboardWidgetRepository analyticsDashboardWidgetRepository,
        AnalyticsQueryRepository analyticsQueryRepository,
        TenantFilterHelper tenantFilterHelper, UserNameResolver userNameResolver) {
        this.analyticsAnalysisRepository = analyticsAnalysisRepository;
        this.analyticsDashboardRepository = analyticsDashboardRepository;
        this.analyticsDashboardWidgetRepository = analyticsDashboardWidgetRepository;
        this.analyticsQueryRepository = analyticsQueryRepository;
        this.tenantFilterHelper = tenantFilterHelper;
        this.userNameResolver = userNameResolver;
    }

    // ------------------------------------------------------------------------------- analyses

    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchAllAnalyses() throws Exception {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        List<AnalyticsAnalysis> analyses = ownedByCaller(
            this.analyticsAnalysisRepository.findAllByOrderByAnalyticsAnalysisIdDesc(),
            AnalyticsAnalysis::getTenantId);
        this.userNameResolver.attachNames(analyses);
        return new ResponseDto(SUCCESS, "Data fetched successfully.", analyses);
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchAnalysisById(Long analyticsAnalysisId) throws Exception {
        if (isNull(analyticsAnalysisId)) {
            return new ResponseDto(ERROR, "AnalyticsAnalysis analyticsAnalysisId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<AnalyticsAnalysis> analysis = this.scopedFindAnalysis(analyticsAnalysisId);
        if (!analysis.isPresent()) {
            return this.analysisNotFound(analyticsAnalysisId);
        }
        this.userNameResolver.attachNames(Arrays.asList(analysis.get()));
        return new ResponseDto(SUCCESS, "Data fetched successfully.", analysis.get());
    }

    @Override
    @Transactional
    public ResponseDto saveAnalysis(AnalyticsAnalysis payload) throws Exception {
        if (payload == null) {
            return new ResponseDto(ERROR, "AnalyticsAnalysis payload missing.");
        }
        String problem = this.validateAnalysis(payload);
        if (problem != null) {
            return new ResponseDto(ERROR, problem);
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);

        AnalyticsAnalysis target;
        if (payload.getAnalyticsAnalysisId() != null) {
            Optional<AnalyticsAnalysis> existing = this.scopedFindAnalysis(payload.getAnalyticsAnalysisId());
            if (!existing.isPresent()) {
                return this.analysisNotFound(payload.getAnalyticsAnalysisId());
            }
            target = existing.get();
            target.setDateUpdated(new Timestamp(System.currentTimeMillis()));
        } else {
            /*
             * The owning tenant comes from the signed-in context and never from the payload,
             * which is why this method builds a row instead of saving the one it was handed. The
             * endpoint binds the request straight onto the entity, so a caller can put any
             * tenantId, createdBy or id they like on the wire; none of them are read.
             */
            if (TenantContext.getTenantId() == null && !TenantContext.isPlatformAdmin()) {
                return new ResponseDto(ERROR, "A saved analysis needs a workspace to belong to.");
            }
            target = new AnalyticsAnalysis();
            target.setTenantId(TenantContext.isPlatformAdmin() ? null : TenantContext.getTenantId());
            target.setDateCreated(new Timestamp(System.currentTimeMillis()));
        }
        target.setAnalysisName(payload.getAnalysisName().trim());
        target.setConnectionAlias(payload.getConnectionAlias().trim());
        target.setDatasetPath(payload.getDatasetPath().trim());
        target.setVisualizationType(trimToNull(payload.getVisualizationType()));
        target.setAnalysisConfig(payload.getAnalysisConfig());
        target = this.analyticsAnalysisRepository.save(target);
        return new ResponseDto(SUCCESS, String.format("Saved analysis stored with %d.",
            target.getAnalyticsAnalysisId()), target);
    }

    @Override
    @Transactional
    public ResponseDto deleteAnalysis(Long analyticsAnalysisId) throws Exception {
        if (isNull(analyticsAnalysisId)) {
            return new ResponseDto(ERROR, "AnalyticsAnalysis analyticsAnalysisId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<AnalyticsAnalysis> existing = this.scopedFindAnalysis(analyticsAnalysisId);
        if (!existing.isPresent()) {
            return this.analysisNotFound(analyticsAnalysisId);
        }
        // Explicitly, not by waiting for the changeset's cascade. That cascade is keyed on
        // (id, tenant_id) and Postgres does not enforce a foreign key with a null column, so it
        // never fires for a platform admin's rows -- which would leave the one caller who can see
        // every dashboard looking at widgets pointing at nothing.
        List<AnalyticsDashboardWidget> showing =
            this.analyticsDashboardWidgetRepository.findByAnalyticsAnalysisId(analyticsAnalysisId);
        if (!showing.isEmpty()) {
            this.analyticsDashboardWidgetRepository.deleteAll(showing);
        }
        this.analyticsAnalysisRepository.delete(existing.get());
        return new ResponseDto(SUCCESS, showing.isEmpty()
            ? String.format("Saved analysis deleted with %d.", analyticsAnalysisId)
            : String.format("Saved analysis deleted with %d, and %d dashboard widget(s) that showed it.",
                analyticsAnalysisId, showing.size()));
    }

    // ----------------------------------------------------------------------------- dashboards

    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchAllDashboards() throws Exception {
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        List<AnalyticsDashboard> dashboards = ownedByCaller(
            this.analyticsDashboardRepository.findAllByOrderByAnalyticsDashboardIdDesc(),
            AnalyticsDashboard::getTenantId);
        this.userNameResolver.attachNames(dashboards);
        return new ResponseDto(SUCCESS, "Data fetched successfully.", dashboards);
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseDto fetchDashboardById(Long analyticsDashboardId) throws Exception {
        if (isNull(analyticsDashboardId)) {
            return new ResponseDto(ERROR, "AnalyticsDashboard analyticsDashboardId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<AnalyticsDashboard> found = this.scopedFindDashboard(analyticsDashboardId);
        if (!found.isPresent()) {
            return this.dashboardNotFound(analyticsDashboardId);
        }
        AnalyticsDashboard dashboard = found.get();
        List<AnalyticsDashboardWidget> widgets = ownedByCaller(
            this.analyticsDashboardWidgetRepository
                .findByAnalyticsDashboardIdOrderByDisplayOrderAscAnalyticsDashboardWidgetIdAsc(analyticsDashboardId),
            AnalyticsDashboardWidget::getTenantId);
        this.userNameResolver.attachNames(widgets);
        dashboard.setWidgets(widgets);
        this.userNameResolver.attachNames(Arrays.asList(dashboard));
        return new ResponseDto(SUCCESS, "Data fetched successfully.", dashboard);
    }

    @Override
    @Transactional
    public ResponseDto saveDashboard(AnalyticsDashboard payload) throws Exception {
        if (payload == null) {
            return new ResponseDto(ERROR, "AnalyticsDashboard payload missing.");
        }
        if (isBlank(payload.getDashboardName())) {
            return new ResponseDto(ERROR, "AnalyticsDashboard dashboardName missing.");
        }
        if (payload.getDashboardName().trim().length() > MAX_NAME_LENGTH) {
            return new ResponseDto(ERROR, String.format("A dashboard name is at most %d characters.", MAX_NAME_LENGTH));
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);

        AnalyticsDashboard target;
        if (payload.getAnalyticsDashboardId() != null) {
            Optional<AnalyticsDashboard> existing = this.scopedFindDashboard(payload.getAnalyticsDashboardId());
            if (!existing.isPresent()) {
                return this.dashboardNotFound(payload.getAnalyticsDashboardId());
            }
            target = existing.get();
            target.setDateUpdated(new Timestamp(System.currentTimeMillis()));
        } else {
            if (TenantContext.getTenantId() == null && !TenantContext.isPlatformAdmin()) {
                return new ResponseDto(ERROR, "A dashboard needs a workspace to belong to.");
            }
            target = new AnalyticsDashboard();
            target.setTenantId(TenantContext.isPlatformAdmin() ? null : TenantContext.getTenantId());
            target.setDateCreated(new Timestamp(System.currentTimeMillis()));
        }
        target.setDashboardName(payload.getDashboardName().trim());
        target.setDashboardDescription(trimToNull(payload.getDashboardDescription()));
        target = this.analyticsDashboardRepository.save(target);
        return new ResponseDto(SUCCESS, String.format("Dashboard stored with %d.",
            target.getAnalyticsDashboardId()), target);
    }

    @Override
    @Transactional
    public ResponseDto deleteDashboard(Long analyticsDashboardId) throws Exception {
        if (isNull(analyticsDashboardId)) {
            return new ResponseDto(ERROR, "AnalyticsDashboard analyticsDashboardId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<AnalyticsDashboard> existing = this.scopedFindDashboard(analyticsDashboardId);
        if (!existing.isPresent()) {
            return this.dashboardNotFound(analyticsDashboardId);
        }
        // Explicitly, for the same reason deleteAnalysis does it: the database's cascade does not
        // fire for a row whose tenant_id is null.
        List<AnalyticsDashboardWidget> widgets = this.analyticsDashboardWidgetRepository
            .findByAnalyticsDashboardIdOrderByDisplayOrderAscAnalyticsDashboardWidgetIdAsc(analyticsDashboardId);
        if (!widgets.isEmpty()) {
            this.analyticsDashboardWidgetRepository.deleteAll(widgets);
        }
        // What the widgets pointed at is untouched. A dashboard is an arrangement of saved work,
        // not the owner of it.
        this.analyticsDashboardRepository.delete(existing.get());
        return new ResponseDto(SUCCESS, String.format("Dashboard deleted with %d.", analyticsDashboardId));
    }

    // -------------------------------------------------------------------------------- widgets

    @Override
    @Transactional
    public ResponseDto saveWidget(AnalyticsDashboardWidget payload) throws Exception {
        if (payload == null) {
            return new ResponseDto(ERROR, "AnalyticsDashboardWidget payload missing.");
        }
        String problem = this.validateWidget(payload);
        if (problem != null) {
            return new ResponseDto(ERROR, problem);
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);

        // The dashboard first, because everything else is judged against its workspace.
        Optional<AnalyticsDashboard> dashboard = this.scopedFindDashboard(payload.getAnalyticsDashboardId());
        if (!dashboard.isPresent()) {
            return this.dashboardNotFound(payload.getAnalyticsDashboardId());
        }
        Long owningTenantId = dashboard.get().getTenantId();

        /*
         * The cross-tenant rule, and the reason it is three checks rather than one.
         *
         * scopedFind answers "may this caller reach the source", which a tenant user can only
         * satisfy for their own rows -- so for them the second check below is already the whole
         * story. It is not the whole story for a platform admin, who reaches every row: without
         * the tenant comparison they could hang one workspace's analysis on another workspace's
         * dashboard, and every ownership check made afterwards would pass, because the widget and
         * the dashboard really do belong to that workspace. What would not belong is the data on
         * the screen.
         */
        if (payload.getAnalyticsAnalysisId() != null) {
            Optional<AnalyticsAnalysis> source = this.scopedFindAnalysis(payload.getAnalyticsAnalysisId());
            if (!source.isPresent()) {
                return this.analysisNotFound(payload.getAnalyticsAnalysisId());
            }
            if (!Objects.equals(source.get().getTenantId(), owningTenantId)) {
                return new ResponseDto(ERROR,
                    "A widget can only show an analysis from the same workspace as its dashboard.");
            }
        } else {
            Optional<AnalyticsQuery> source =
                this.analyticsQueryRepository.findByAnalyticsQueryId(payload.getAnalyticsQueryId());
            if (!source.isPresent() || !TenantOwnership.isOwnedByCaller(source.get().getTenantId())) {
                // The saved-query library's own wording, so a widget cannot be used to find out
                // which query ids exist in another workspace when its own endpoints refuse to.
                return new ResponseDto(ERROR,
                    String.format("Saved query not found with %s.", payload.getAnalyticsQueryId()));
            }
            if (!Objects.equals(source.get().getTenantId(), owningTenantId)) {
                return new ResponseDto(ERROR,
                    "A widget can only show a saved query from the same workspace as its dashboard.");
            }
        }

        AnalyticsDashboardWidget target;
        if (payload.getAnalyticsDashboardWidgetId() != null) {
            Optional<AnalyticsDashboardWidget> existing =
                this.scopedFindWidget(payload.getAnalyticsDashboardWidgetId());
            if (!existing.isPresent()) {
                return this.widgetNotFound(payload.getAnalyticsDashboardWidgetId());
            }
            target = existing.get();
            target.setDateUpdated(new Timestamp(System.currentTimeMillis()));
        } else {
            target = new AnalyticsDashboardWidget();
            target.setDateCreated(new Timestamp(System.currentTimeMillis()));
        }
        // From the dashboard, not from the caller. A widget belongs to the page it sits on, and a
        // platform admin's null tenant copied onto it would leave a widget on a workspace's
        // dashboard that the workspace's own plain-equality filter hides from them.
        target.setTenantId(owningTenantId);
        target.setAnalyticsDashboardId(payload.getAnalyticsDashboardId());
        target.setWidgetTitle(payload.getWidgetTitle().trim());
        target.setAnalyticsAnalysisId(payload.getAnalyticsAnalysisId());
        target.setAnalyticsQueryId(payload.getAnalyticsQueryId());
        target.setVisualizationType(trimToNull(payload.getVisualizationType()));
        target.setWidgetConfig(payload.getWidgetConfig());
        target.setDisplayOrder(payload.getDisplayOrder() == null ? 0 : payload.getDisplayOrder());
        target = this.analyticsDashboardWidgetRepository.save(target);
        return new ResponseDto(SUCCESS, String.format("Dashboard widget stored with %d.",
            target.getAnalyticsDashboardWidgetId()), target);
    }

    @Override
    @Transactional
    public ResponseDto deleteWidget(Long analyticsDashboardWidgetId) throws Exception {
        if (isNull(analyticsDashboardWidgetId)) {
            return new ResponseDto(ERROR, "AnalyticsDashboardWidget analyticsDashboardWidgetId missing.");
        }
        this.tenantFilterHelper.enableIfNeeded(this.entityManager);
        Optional<AnalyticsDashboardWidget> existing = this.scopedFindWidget(analyticsDashboardWidgetId);
        if (!existing.isPresent()) {
            return this.widgetNotFound(analyticsDashboardWidgetId);
        }
        this.analyticsDashboardWidgetRepository.delete(existing.get());
        return new ResponseDto(SUCCESS, String.format("Dashboard widget deleted with %d.",
            analyticsDashboardWidgetId));
    }

    // --------------------------------------------------------------------------------- shared

    /**
     * Drops anything the caller does not own, after the database has already filtered it out.
     *
     * A listing is a query, so the Hibernate tenant filter genuinely does apply to it -- unlike a
     * load by id -- and in a correct system this loop removes nothing. It is here because the
     * filter is enabled by a call the next person to write a listing can forget, and because
     * these rows answer questions -- which connection somebody reads, which fields they group by
     * -- that are not recoverable once shown.
     */
    private static <T> List<T> ownedByCaller(List<T> rows, Function<T, Long> tenantIdOf) {
        List<T> owned = new ArrayList<>(rows.size());
        for (T row : rows) {
            if (TenantOwnership.isOwnedByCaller(tenantIdOf.apply(row))) {
                owned.add(row);
            }
        }
        return owned;
    }

    /**
     * One analysis, by id, for this caller only.
     *
     * The tenant check is here and not left to the Hibernate filter because a @Filter applies to
     * queries and not to a load by primary key: findById would hand back another workspace's row
     * without the filter ever being consulted. The repository's finder is a query, so the filter
     * does apply to it -- and this check runs on top of it anyway, because a tenancy rule with
     * exactly one enforcement point is a tenancy rule one refactor away from being none.
     */
    private Optional<AnalyticsAnalysis> scopedFindAnalysis(Long analyticsAnalysisId) {
        Optional<AnalyticsAnalysis> found =
            this.analyticsAnalysisRepository.findByAnalyticsAnalysisId(analyticsAnalysisId);
        if (!found.isPresent() || !TenantOwnership.isOwnedByCaller(found.get().getTenantId())) {
            return Optional.empty();
        }
        return found;
    }

    /** One dashboard, by id, for this caller only. Same reasoning as scopedFindAnalysis. */
    private Optional<AnalyticsDashboard> scopedFindDashboard(Long analyticsDashboardId) {
        Optional<AnalyticsDashboard> found =
            this.analyticsDashboardRepository.findByAnalyticsDashboardId(analyticsDashboardId);
        if (!found.isPresent() || !TenantOwnership.isOwnedByCaller(found.get().getTenantId())) {
            return Optional.empty();
        }
        return found;
    }

    /** One widget, by id, for this caller only. Same reasoning as scopedFindAnalysis. */
    private Optional<AnalyticsDashboardWidget> scopedFindWidget(Long analyticsDashboardWidgetId) {
        Optional<AnalyticsDashboardWidget> found =
            this.analyticsDashboardWidgetRepository.findByAnalyticsDashboardWidgetId(analyticsDashboardWidgetId);
        if (!found.isPresent() || !TenantOwnership.isOwnedByCaller(found.get().getTenantId())) {
            return Optional.empty();
        }
        return found;
    }

    /*
     * The three refusals below deliberately say the same thing for "no such row" and "somebody
     * else's row". A distinct "that belongs to another tenant" is itself a cross-tenant
     * disclosure: it confirms the id exists, which is enough to count another workspace's saved
     * work by walking the ids.
     */

    private ResponseDto analysisNotFound(Long analyticsAnalysisId) {
        return new ResponseDto(ERROR, String.format("Saved analysis not found with %s.", analyticsAnalysisId));
    }

    private ResponseDto dashboardNotFound(Long analyticsDashboardId) {
        return new ResponseDto(ERROR, String.format("Dashboard not found with %s.", analyticsDashboardId));
    }

    private ResponseDto widgetNotFound(Long analyticsDashboardWidgetId) {
        return new ResponseDto(ERROR, String.format("Dashboard widget not found with %s.",
            analyticsDashboardWidgetId));
    }

    private String validateAnalysis(AnalyticsAnalysis payload) {
        if (isBlank(payload.getAnalysisName())) {
            return "AnalyticsAnalysis analysisName missing.";
        }
        if (payload.getAnalysisName().trim().length() > MAX_NAME_LENGTH) {
            return String.format("A saved analysis name is at most %d characters.", MAX_NAME_LENGTH);
        }
        if (isBlank(payload.getConnectionAlias())) {
            return "AnalyticsAnalysis connectionAlias missing.";
        }
        if (payload.getConnectionAlias().trim().length() > MAX_ALIAS_LENGTH) {
            return String.format("A connection alias is at most %d characters.", MAX_ALIAS_LENGTH);
        }
        if (isBlank(payload.getDatasetPath())) {
            return "AnalyticsAnalysis datasetPath missing.";
        }
        if (isBlank(payload.getAnalysisConfig())) {
            return "AnalyticsAnalysis analysisConfig missing.";
        }
        return this.validateConfig(payload.getAnalysisConfig(), "A saved analysis");
    }

    private String validateWidget(AnalyticsDashboardWidget payload) {
        if (isNull(payload.getAnalyticsDashboardId())) {
            return "AnalyticsDashboardWidget analyticsDashboardId missing.";
        }
        if (isBlank(payload.getWidgetTitle())) {
            return "AnalyticsDashboardWidget widgetTitle missing.";
        }
        if (payload.getWidgetTitle().trim().length() > MAX_NAME_LENGTH) {
            return String.format("A widget title is at most %d characters.", MAX_NAME_LENGTH);
        }
        // The same rule the check constraint states, refused here with a sentence instead of a
        // constraint violation. Neither set is a widget with nothing to draw; both set is a row
        // that can disagree with itself about what it shows.
        boolean hasAnalysis = payload.getAnalyticsAnalysisId() != null;
        boolean hasQuery = payload.getAnalyticsQueryId() != null;
        if (hasAnalysis == hasQuery) {
            return "A widget shows exactly one thing: a saved analysis or a saved query, not both and not neither.";
        }
        if (payload.getVisualizationType() != null
            && payload.getVisualizationType().trim().length() > MAX_VISUALIZATION_LENGTH) {
            return String.format("A visualization type is at most %d characters.", MAX_VISUALIZATION_LENGTH);
        }
        if (payload.getWidgetConfig() != null && !payload.getWidgetConfig().trim().isEmpty()) {
            return this.validateConfig(payload.getWidgetConfig(), "A widget");
        }
        return null;
    }

    /**
     * That a configuration is a JSON object, and is a configuration rather than a payload.
     *
     * Checked here because the column is TEXT and the database will accept anything: a row
     * holding a string that does not parse breaks every reader of it, and the failure surfaces
     * far from the request that stored it.
     *
     * <b>Deliberately a well-formedness check and not a schema check.</b> The Canvas's shape is
     * still being designed, and a service that knew which dimensions and operators were legal
     * would be a second place the analysis model is defined -- one that has to be edited in step
     * with the first, and that silently refuses new work when somebody forgets. Requiring a JSON
     * OBJECT rather than merely valid JSON is the one bit of shape worth pinning: Gson's reader
     * is lenient, so a bare word parses happily as a string, and "department" stored as an entire
     * analysis configuration is not a thing any reader could do anything with.
     */
    private String validateConfig(String config, String subject) {
        if (config.length() > MAX_CONFIG_LENGTH) {
            return String.format("%s configuration is at most %d characters.", subject, MAX_CONFIG_LENGTH);
        }
        Object parsed;
        try {
            parsed = new Gson().fromJson(config, Object.class);
        } catch (JsonParseException ex) {
            this.logger.warn("A workspace configuration was refused: it did not parse as JSON.");
            return String.format("%s configuration must be a JSON object.", subject);
        }
        if (!(parsed instanceof Map)) {
            return String.format("%s configuration must be a JSON object.", subject);
        }
        return null;
    }

    private static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}

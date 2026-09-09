package process.model.service;

import process.model.dto.ResponseDto;
import process.model.pojo.AnalyticsAnalysis;
import process.model.pojo.AnalyticsDashboard;
import process.model.pojo.AnalyticsDashboardWidget;

/**
 * The workspace: saved analyses, dashboards, and the widgets on them.
 *
 * One service over three tables because they are one lifecycle -- an analysis is saved, a
 * dashboard is made, a widget puts the first on the second -- and because the rule that binds
 * them is a single rule that has to be applied in one place: a widget may only point at a saved
 * thing belonging to the same workspace as the dashboard it sits on.
 *
 * Kept apart from AnalyticsQueryLibraryService for the same reason that one is kept apart from
 * AnalyticsQueryService: nothing here opens a session, builds SQL or touches the engine. These
 * are tables of names, references and configuration. <b>There is deliberately no endpoint here
 * that runs an analysis</b> -- saving a configuration and executing one are different questions,
 * and execution belongs behind the session lock-down and the governor, which is the one door this
 * feature has.
 *
 * <b>What this service does not decide.</b> .ai/synthesis/analytics-studio.md section 6, Q2 asks
 * whether Analytics Studio should own charts at all or whether its results should feed the
 * existing /reports pivot, and records that the question is open. Nothing here answers it: a
 * widget stores a title, a reference and an opaque rendering configuration, and no chart kind,
 * chart library or drawing rule appears anywhere in these three tables.
 *
 * @author Nabeel Ahmed
 */
public interface AnalyticsWorkspaceService {

    /** Every saved analysis the caller's workspace can see, newest first. */
    ResponseDto fetchAllAnalyses() throws Exception;

    /** One saved analysis, or a not-found for anything the caller does not own. */
    ResponseDto fetchAnalysisById(Long analyticsAnalysisId) throws Exception;

    /**
     * Creates a saved analysis, or updates one the caller owns when the payload carries an id.
     *
     * The stored row is built here rather than bound from the request: tenantId and the audit
     * columns come from the signed-in context, so "save this into another workspace" is not a
     * request this method can be made to honour. The configuration is stored as submitted --
     * checked only for being well-formed JSON and for a sane size -- because this row holds what
     * somebody asked for, not what it compiles to.
     */
    ResponseDto saveAnalysis(AnalyticsAnalysis payload) throws Exception;

    /**
     * Deletes a saved analysis, and with it every widget that showed it.
     *
     * A widget stores a title and a rendering configuration and no copy of the analysis, so a
     * widget whose analysis is gone has nothing left to draw. The changeset's ON DELETE CASCADE
     * says the same thing, but the widgets are removed here explicitly as well: the cascade is
     * keyed on (id, tenant_id) and Postgres does not enforce -- so does not cascade -- a foreign
     * key with a null column, which is every row a platform admin owns. The response says how
     * many went, because a delete that quietly empties somebody's dashboard should say so.
     */
    ResponseDto deleteAnalysis(Long analyticsAnalysisId) throws Exception;

    /** Every dashboard the caller's workspace can see, newest first. Widgets are not included. */
    ResponseDto fetchAllDashboards() throws Exception;

    /**
     * One dashboard with its widgets in display order, or a not-found for one the caller does not
     * own.
     *
     * The widgets are fetched as their own tenant-filtered query rather than mapped as a
     * collection on the entity. A mapped collection would be loaded by primary key, and a
     * Hibernate @Filter does not apply to a load by primary key -- so the convenient mapping is
     * the one that walks past the boundary this module has already been bitten by once.
     */
    ResponseDto fetchDashboardById(Long analyticsDashboardId) throws Exception;

    /** Creates a dashboard, or updates one the caller owns when the payload carries an id. */
    ResponseDto saveDashboard(AnalyticsDashboard payload) throws Exception;

    /** Deletes a dashboard and its widgets. The analyses and saved queries they showed stay. */
    ResponseDto deleteDashboard(Long analyticsDashboardId) throws Exception;

    /**
     * Puts one saved analysis or one saved query on a dashboard, or edits a widget already there.
     *
     * <b>This is the method the cross-tenant rule lives in.</b> Three things are checked and all
     * three are necessary: the dashboard must be one the caller may reach, the referenced
     * analysis or saved query must be one the caller may reach, and the two must belong to the
     * same workspace. The third is not implied by the first two -- a platform admin may reach
     * every row, so without it they could hang one tenant's analysis on another tenant's
     * dashboard and leave a cross-tenant read behind that every later ownership check passes.
     *
     * The widget's own tenant is taken from the DASHBOARD rather than from the caller, for the
     * same case: a widget belongs to the page it sits on, and a platform admin's null tenant
     * copied onto it would produce a widget invisible to the workspace whose dashboard it is on.
     */
    ResponseDto saveWidget(AnalyticsDashboardWidget payload) throws Exception;

    /** Removes one widget. What it pointed at is untouched. */
    ResponseDto deleteWidget(Long analyticsDashboardWidgetId) throws Exception;

}

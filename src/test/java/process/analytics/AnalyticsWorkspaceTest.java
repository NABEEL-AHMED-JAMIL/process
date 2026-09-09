package process.analytics;

import org.hibernate.annotations.Filter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import process.api.AnalyticsWorkspaceRestApi;
import process.model.dto.ResponseDto;
import process.model.pojo.AnalyticsAnalysis;
import process.model.pojo.AnalyticsDashboard;
import process.model.pojo.AnalyticsDashboardWidget;
import process.model.pojo.AnalyticsQuery;
import process.model.pojo.AuditListener;
import process.model.repository.AnalyticsAnalysisRepository;
import process.model.repository.AnalyticsDashboardRepository;
import process.model.repository.AnalyticsDashboardWidgetRepository;
import process.model.repository.AnalyticsQueryRepository;
import process.model.service.impl.AnalyticsWorkspaceServiceImpl;
import process.security.TenantContext;
import process.security.TenantFilterHelper;
import process.util.ProcessUtil;
import process.util.UserNameResolver;

import javax.persistence.Column;
import javax.persistence.EntityListeners;
import javax.persistence.EntityManager;
import javax.persistence.SequenceGenerator;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Whether a saved analysis, a dashboard and the widgets on it stay inside one workspace.
 *
 * <b>The widget is the reason this suite is longer than its siblings.</b> Every row the module
 * persisted before it was a leaf -- a dataset, a saved query, an analysis and a run each carry
 * their own tenant and answer for nobody else, so one ownership check per row is the whole story.
 * A widget is the first row that references another saved row, and that breaks the pattern: a
 * widget can be entirely legitimate -- the caller's own tenant, on the caller's own dashboard --
 * and still put another workspace's data on the screen, because what it points at was never
 * checked. Nothing in a per-row check catches that.
 *
 * <b>Why the repositories are mocked to hand back the wrong tenant's rows.</b> Because that is
 * what the real ones do on a load by id. A Hibernate @Filter applies to queries and NOT to a load
 * by primary key, so every refusal below is the service's own check being asserted with the
 * database's contribution deliberately removed.
 *
 * <b>Every refusal has a positive control.</b> A rule that refused everybody would satisfy the
 * negative half of each pair while making dashboards useless, and that is exactly how the
 * resolver fix of 2026-09-08 was pinned. The platform-admin pair at the end is the sharpest of
 * them: that caller is refused a cross-workspace widget and allowed a same-workspace one, so the
 * rule being tested is "the two must match" and not "platform admins may not build dashboards".
 *
 * @author Nabeel Ahmed
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsWorkspaceTest {

    /** Two real workspaces. Neither is the platform, which is a third case and tested apart. */
    private static final Long ACME = 1001L;
    private static final Long GLOBEX = 2002L;

    private static final Long ACME_USER = 55L;
    private static final Long GLOBEX_USER = 66L;

    private static final Long OWN_ANALYSIS_ID = 500L;
    private static final Long THEIR_ANALYSIS_ID = 900L;
    private static final Long MISSING_ANALYSIS_ID = 4242L;

    private static final Long OWN_DASHBOARD_ID = 600L;
    private static final Long THEIR_DASHBOARD_ID = 960L;

    private static final Long OWN_QUERY_ID = 700L;
    private static final Long THEIR_QUERY_ID = 970L;

    private static final Long OWN_WIDGET_ID = 800L;
    private static final Long THEIR_WIDGET_ID = 980L;

    private static final String CONFIG =
        "{\"dimensions\":[\"department\"],\"measure\":\"user_id\",\"aggregation\":\"DISTINCT_COUNT\"}";

    /**
     * Anything that would say WHERE the data is rather than WHICH connection reaches it, plus the
     * words a credential arrives under. None of the three new tables may name one.
     */
    private static final List<String> LOCATION_AND_CREDENTIAL_WORDS = Arrays.asList(
        "bucket", "endpoint", "region", "url", "host", "port", "secret", "accesskey", "password",
        "credential", "connectionstring");

    @Mock private AnalyticsAnalysisRepository analyticsAnalysisRepository;
    @Mock private AnalyticsDashboardRepository analyticsDashboardRepository;
    @Mock private AnalyticsDashboardWidgetRepository analyticsDashboardWidgetRepository;
    @Mock private AnalyticsQueryRepository analyticsQueryRepository;
    @Mock private TenantFilterHelper tenantFilterHelper;
    @Mock private UserNameResolver userNameResolver;
    @Mock private EntityManager entityManager;

    private AnalyticsWorkspaceServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        this.service = new AnalyticsWorkspaceServiceImpl(this.analyticsAnalysisRepository,
            this.analyticsDashboardRepository, this.analyticsDashboardWidgetRepository,
            this.analyticsQueryRepository, this.tenantFilterHelper, this.userNameResolver);
        // @PersistenceContext is field injection, so there is no constructor to hand it to.
        Field entityManagerField = AnalyticsWorkspaceServiceImpl.class.getDeclaredField("entityManager");
        entityManagerField.setAccessible(true);
        entityManagerField.set(this.service, this.entityManager);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    // ------------------------------------------------------------------- somebody else's analysis

    @Test
    void aTenantCannotLoadAnotherTenantsAnalysis() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenAnalysisLoaded(THEIR_ANALYSIS_ID, analysisOwnedBy(GLOBEX, THEIR_ANALYSIS_ID));

        ResponseDto response = this.service.fetchAnalysisById(THEIR_ANALYSIS_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getData())
            .as("the row reached the service, so nothing but the service's own check kept it back")
            .isNull();
    }

    @Test
    void aTenantCanLoadItsOwnAnalysis() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        AnalyticsAnalysis mine = analysisOwnedBy(ACME, OWN_ANALYSIS_ID);
        whenAnalysisLoaded(OWN_ANALYSIS_ID, mine);

        ResponseDto response = this.service.fetchAnalysisById(OWN_ANALYSIS_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(response.getData()).isSameAs(mine);
    }

    /**
     * The refusal must not be a lookup service for other people's ids.
     *
     * "That belongs to another tenant" confirms the id exists, which is enough to count another
     * workspace's saved work by walking the id space.
     */
    @Test
    void aRefusedAnalysisAndAMissingAnalysisAreIndistinguishable() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenAnalysisLoaded(THEIR_ANALYSIS_ID, analysisOwnedBy(GLOBEX, THEIR_ANALYSIS_ID));
        when(this.analyticsAnalysisRepository.findByAnalyticsAnalysisId(MISSING_ANALYSIS_ID))
            .thenReturn(Optional.empty());

        String refused = this.service.fetchAnalysisById(THEIR_ANALYSIS_ID).getMessage();
        String missing = this.service.fetchAnalysisById(MISSING_ANALYSIS_ID).getMessage();

        assertThat(refused.replace(String.valueOf(THEIR_ANALYSIS_ID), "#"))
            .isEqualTo(missing.replace(String.valueOf(MISSING_ANALYSIS_ID), "#"));
    }

    @Test
    void aTenantCannotOverwriteAnotherTenantsAnalysisByPostingItsId() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        AnalyticsAnalysis theirs = analysisOwnedBy(GLOBEX, THEIR_ANALYSIS_ID);
        whenAnalysisLoaded(THEIR_ANALYSIS_ID, theirs);

        AnalyticsAnalysis payload = analysisPayload();
        payload.setAnalyticsAnalysisId(THEIR_ANALYSIS_ID);
        payload.setAnalysisConfig("{\"dimensions\":[\"salary\"]}");

        ResponseDto response = this.service.saveAnalysis(payload);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(theirs.getAnalysisConfig()).isEqualTo(CONFIG);
        verify(this.analyticsAnalysisRepository, never()).save(any(AnalyticsAnalysis.class));
    }

    @Test
    void aTenantCannotDeleteAnotherTenantsAnalysis() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenAnalysisLoaded(THEIR_ANALYSIS_ID, analysisOwnedBy(GLOBEX, THEIR_ANALYSIS_ID));

        ResponseDto response = this.service.deleteAnalysis(THEIR_ANALYSIS_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.analyticsAnalysisRepository, never()).delete(any(AnalyticsAnalysis.class));
    }

    /**
     * Deleting an analysis takes the widgets that showed it, and says so.
     *
     * Done here rather than left to the changeset's cascade because that cascade is keyed on
     * (id, tenant_id) and Postgres does not enforce -- so does not cascade -- a foreign key with a
     * null column. Every row a platform admin owns has one.
     */
    @Test
    void deletingAnAnalysisRemovesTheWidgetsThatShowedItAndSaysHowMany() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        AnalyticsAnalysis mine = analysisOwnedBy(ACME, OWN_ANALYSIS_ID);
        whenAnalysisLoaded(OWN_ANALYSIS_ID, mine);
        List<AnalyticsDashboardWidget> showing = Arrays.asList(
            widgetOwnedBy(ACME, OWN_WIDGET_ID), widgetOwnedBy(ACME, OWN_WIDGET_ID + 1));
        when(this.analyticsDashboardWidgetRepository.findByAnalyticsAnalysisId(OWN_ANALYSIS_ID))
            .thenReturn(showing);

        ResponseDto response = this.service.deleteAnalysis(OWN_ANALYSIS_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(response.getMessage()).contains("2 dashboard widget(s)");
        verify(this.analyticsDashboardWidgetRepository).deleteAll(showing);
        verify(this.analyticsAnalysisRepository).delete(mine);
    }

    /**
     * The endpoint binds the request body onto the entity, so a caller can put any tenant on the
     * wire. None of it is read: the row is built from the signed-in context.
     */
    @Test
    void savingAnAnalysisIgnoresTheTenantAndTheAuthorOnTheWire() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenAnalysisSavedReturnTheRow();

        AnalyticsAnalysis payload = analysisPayload();
        payload.setTenantId(GLOBEX);
        payload.setCreatedBy(GLOBEX_USER);

        this.service.saveAnalysis(payload);

        AnalyticsAnalysis stored = capturedAnalysisSave();
        assertThat(stored.getTenantId()).isEqualTo(ACME);
        assertThat(stored.getCreatedBy())
            .as("AuditListener stamps the author at persist time from the same context, so a "
                + "createdBy on the wire must not survive to be stamped over")
            .isNull();
        assertThat(stored.getAnalysisConfig())
            .as("stored as submitted -- this row holds what somebody asked for, not what it "
                + "compiles to")
            .isEqualTo(CONFIG);
    }

    @Test
    void aCallerWithNoWorkspaceCannotSaveAnAnalysisAtAll() throws Exception {
        signedInAs(null, "TENANT_USER", 99L);

        ResponseDto response = this.service.saveAnalysis(analysisPayload());

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.analyticsAnalysisRepository, never()).save(any(AnalyticsAnalysis.class));
    }

    /**
     * The column is TEXT, so the database takes anything. A row holding a string that does not
     * parse breaks every reader of it, far from the request that stored it.
     */
    @Test
    void anAnalysisConfigurationThatIsNotJsonIsRefused() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);

        AnalyticsAnalysis payload = analysisPayload();
        payload.setAnalysisConfig("dimensions: department");

        ResponseDto response = this.service.saveAnalysis(payload);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.analyticsAnalysisRepository, never()).save(any(AnalyticsAnalysis.class));
    }

    // ------------------------------------------------------------------ somebody else's dashboard

    @Test
    void aTenantCannotLoadAnotherTenantsDashboard() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenDashboardLoaded(THEIR_DASHBOARD_ID, dashboardOwnedBy(GLOBEX, THEIR_DASHBOARD_ID));

        ResponseDto response = this.service.fetchDashboardById(THEIR_DASHBOARD_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getData()).isNull();
        verify(this.analyticsDashboardWidgetRepository, never())
            .findByAnalyticsDashboardIdOrderByDisplayOrderAscAnalyticsDashboardWidgetIdAsc(any());
    }

    @Test
    void aTenantCanLoadItsOwnDashboardWithItsWidgets() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        AnalyticsDashboard mine = dashboardOwnedBy(ACME, OWN_DASHBOARD_ID);
        whenDashboardLoaded(OWN_DASHBOARD_ID, mine);
        AnalyticsDashboardWidget widget = widgetOwnedBy(ACME, OWN_WIDGET_ID);
        when(this.analyticsDashboardWidgetRepository
            .findByAnalyticsDashboardIdOrderByDisplayOrderAscAnalyticsDashboardWidgetIdAsc(OWN_DASHBOARD_ID))
            .thenReturn(Collections.singletonList(widget));

        ResponseDto response = this.service.fetchDashboardById(OWN_DASHBOARD_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(((AnalyticsDashboard) response.getData()).getWidgets()).containsExactly(widget);
    }

    /**
     * A widget belonging to somebody else does not reach a dashboard listing even if the query
     * that fetched it did not filter. The filter is enabled by a call that can be forgotten.
     */
    @Test
    void aDashboardShowsOnlyTheWidgetsItsOwnWorkspaceOwns() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenDashboardLoaded(OWN_DASHBOARD_ID, dashboardOwnedBy(ACME, OWN_DASHBOARD_ID));
        AnalyticsDashboardWidget mine = widgetOwnedBy(ACME, OWN_WIDGET_ID);
        when(this.analyticsDashboardWidgetRepository
            .findByAnalyticsDashboardIdOrderByDisplayOrderAscAnalyticsDashboardWidgetIdAsc(OWN_DASHBOARD_ID))
            .thenReturn(Arrays.asList(widgetOwnedBy(GLOBEX, THEIR_WIDGET_ID), mine));

        ResponseDto response = this.service.fetchDashboardById(OWN_DASHBOARD_ID);

        assertThat(((AnalyticsDashboard) response.getData()).getWidgets()).containsExactly(mine);
    }

    @Test
    void aTenantCannotDeleteAnotherTenantsDashboard() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenDashboardLoaded(THEIR_DASHBOARD_ID, dashboardOwnedBy(GLOBEX, THEIR_DASHBOARD_ID));

        ResponseDto response = this.service.deleteDashboard(THEIR_DASHBOARD_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.analyticsDashboardRepository, never()).delete(any(AnalyticsDashboard.class));
        verify(this.analyticsDashboardWidgetRepository, never()).deleteAll(anyList());
    }

    @Test
    void aTenantCanDeleteItsOwnDashboardAndItsWidgetsGoWithIt() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        AnalyticsDashboard mine = dashboardOwnedBy(ACME, OWN_DASHBOARD_ID);
        whenDashboardLoaded(OWN_DASHBOARD_ID, mine);
        List<AnalyticsDashboardWidget> widgets =
            Collections.singletonList(widgetOwnedBy(ACME, OWN_WIDGET_ID));
        when(this.analyticsDashboardWidgetRepository
            .findByAnalyticsDashboardIdOrderByDisplayOrderAscAnalyticsDashboardWidgetIdAsc(OWN_DASHBOARD_ID))
            .thenReturn(widgets);

        ResponseDto response = this.service.deleteDashboard(OWN_DASHBOARD_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        verify(this.analyticsDashboardWidgetRepository).deleteAll(widgets);
        verify(this.analyticsDashboardRepository).delete(mine);
    }

    // ------------------------------------------------------------------------------- the widget

    @Test
    void aWidgetCannotShowAnotherTenantsAnalysis() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenDashboardLoaded(OWN_DASHBOARD_ID, dashboardOwnedBy(ACME, OWN_DASHBOARD_ID));
        whenAnalysisLoaded(THEIR_ANALYSIS_ID, analysisOwnedBy(GLOBEX, THEIR_ANALYSIS_ID));

        AnalyticsDashboardWidget payload = widgetPayload();
        payload.setAnalyticsAnalysisId(THEIR_ANALYSIS_ID);

        ResponseDto response = this.service.saveWidget(payload);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.analyticsDashboardWidgetRepository, never()).save(any(AnalyticsDashboardWidget.class));
    }

    /** The control: the caller's own analysis on the caller's own dashboard is fine. */
    @Test
    void aWidgetCanShowItsOwnWorkspacesAnalysis() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenDashboardLoaded(OWN_DASHBOARD_ID, dashboardOwnedBy(ACME, OWN_DASHBOARD_ID));
        whenAnalysisLoaded(OWN_ANALYSIS_ID, analysisOwnedBy(ACME, OWN_ANALYSIS_ID));
        whenWidgetSavedReturnTheRow();

        AnalyticsDashboardWidget payload = widgetPayload();
        payload.setAnalyticsAnalysisId(OWN_ANALYSIS_ID);

        ResponseDto response = this.service.saveWidget(payload);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(capturedWidgetSave().getAnalyticsAnalysisId()).isEqualTo(OWN_ANALYSIS_ID);
    }

    @Test
    void aWidgetCannotShowAnotherTenantsSavedQuery() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenDashboardLoaded(OWN_DASHBOARD_ID, dashboardOwnedBy(ACME, OWN_DASHBOARD_ID));
        whenQueryLoaded(THEIR_QUERY_ID, savedQueryOwnedBy(GLOBEX, THEIR_QUERY_ID));

        AnalyticsDashboardWidget payload = widgetPayload();
        payload.setAnalyticsQueryId(THEIR_QUERY_ID);

        ResponseDto response = this.service.saveWidget(payload);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.analyticsDashboardWidgetRepository, never()).save(any(AnalyticsDashboardWidget.class));
    }

    /** The control on the saved-query side. */
    @Test
    void aWidgetCanShowItsOwnWorkspacesSavedQuery() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenDashboardLoaded(OWN_DASHBOARD_ID, dashboardOwnedBy(ACME, OWN_DASHBOARD_ID));
        whenQueryLoaded(OWN_QUERY_ID, savedQueryOwnedBy(ACME, OWN_QUERY_ID));
        whenWidgetSavedReturnTheRow();

        AnalyticsDashboardWidget payload = widgetPayload();
        payload.setAnalyticsQueryId(OWN_QUERY_ID);

        ResponseDto response = this.service.saveWidget(payload);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(capturedWidgetSave().getAnalyticsQueryId()).isEqualTo(OWN_QUERY_ID);
    }

    @Test
    void aWidgetCannotBeAddedToAnotherTenantsDashboard() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        whenDashboardLoaded(THEIR_DASHBOARD_ID, dashboardOwnedBy(GLOBEX, THEIR_DASHBOARD_ID));

        AnalyticsDashboardWidget payload = widgetPayload();
        payload.setAnalyticsDashboardId(THEIR_DASHBOARD_ID);
        payload.setAnalyticsAnalysisId(OWN_ANALYSIS_ID);

        ResponseDto response = this.service.saveWidget(payload);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.analyticsDashboardWidgetRepository, never()).save(any(AnalyticsDashboardWidget.class));
    }

    /**
     * <b>The case a per-row ownership check cannot catch, and the reason the rule is a comparison
     * rather than two lookups.</b>
     *
     * A platform admin reaches every row, so both halves of this widget pass their own ownership
     * check. What must not pass is the pair: one workspace's analysis rendered inside another
     * workspace's dashboard leaves a cross-tenant read behind that every later check approves,
     * because the widget and the dashboard really do belong to the workspace reading them.
     */
    @Test
    void aPlatformAdminCannotHangOneWorkspacesAnalysisOnAnothersDashboard() throws Exception {
        signedInAs(null, "PLATFORM_ADMIN", 7L);
        whenDashboardLoaded(OWN_DASHBOARD_ID, dashboardOwnedBy(ACME, OWN_DASHBOARD_ID));
        whenAnalysisLoaded(THEIR_ANALYSIS_ID, analysisOwnedBy(GLOBEX, THEIR_ANALYSIS_ID));

        AnalyticsDashboardWidget payload = widgetPayload();
        payload.setAnalyticsAnalysisId(THEIR_ANALYSIS_ID);

        ResponseDto response = this.service.saveWidget(payload);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        assertThat(response.getMessage()).contains("same workspace");
        verify(this.analyticsDashboardWidgetRepository, never()).save(any(AnalyticsDashboardWidget.class));
    }

    /**
     * The control for the pair above, and the test that makes the rule "the two must match"
     * rather than "a platform admin may not build a dashboard".
     *
     * It also pins where the widget's own tenant comes from: the DASHBOARD, not the caller. A
     * platform admin's null tenant copied onto the widget would leave a row on a workspace's
     * dashboard that the workspace's own plain-equality filter hides from them.
     */
    @Test
    void aPlatformAdminBuildingWithinOneWorkspaceSucceedsAndTheWidgetJoinsThatWorkspace() throws Exception {
        signedInAs(null, "PLATFORM_ADMIN", 7L);
        whenDashboardLoaded(OWN_DASHBOARD_ID, dashboardOwnedBy(ACME, OWN_DASHBOARD_ID));
        whenAnalysisLoaded(OWN_ANALYSIS_ID, analysisOwnedBy(ACME, OWN_ANALYSIS_ID));
        whenWidgetSavedReturnTheRow();

        AnalyticsDashboardWidget payload = widgetPayload();
        payload.setAnalyticsAnalysisId(OWN_ANALYSIS_ID);
        payload.setTenantId(GLOBEX);

        ResponseDto response = this.service.saveWidget(payload);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        assertThat(capturedWidgetSave().getTenantId())
            .as("from the dashboard, and never from the payload or the caller")
            .isEqualTo(ACME);
    }

    @Test
    void aWidgetShowingBothAnAnalysisAndAQueryIsRefused() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);

        AnalyticsDashboardWidget payload = widgetPayload();
        payload.setAnalyticsAnalysisId(OWN_ANALYSIS_ID);
        payload.setAnalyticsQueryId(OWN_QUERY_ID);

        ResponseDto response = this.service.saveWidget(payload);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.analyticsDashboardWidgetRepository, never()).save(any(AnalyticsDashboardWidget.class));
    }

    @Test
    void aWidgetShowingNothingAtAllIsRefused() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);

        ResponseDto response = this.service.saveWidget(widgetPayload());

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.analyticsDashboardWidgetRepository, never()).save(any(AnalyticsDashboardWidget.class));
    }

    @Test
    void aTenantCannotDeleteAnotherTenantsWidget() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        when(this.analyticsDashboardWidgetRepository.findByAnalyticsDashboardWidgetId(THEIR_WIDGET_ID))
            .thenReturn(Optional.of(widgetOwnedBy(GLOBEX, THEIR_WIDGET_ID)));

        ResponseDto response = this.service.deleteWidget(THEIR_WIDGET_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.ERROR);
        verify(this.analyticsDashboardWidgetRepository, never()).delete(any(AnalyticsDashboardWidget.class));
    }

    @Test
    void aTenantCanDeleteItsOwnWidget() throws Exception {
        signedInAs(ACME, "TENANT_USER", ACME_USER);
        AnalyticsDashboardWidget mine = widgetOwnedBy(ACME, OWN_WIDGET_ID);
        when(this.analyticsDashboardWidgetRepository.findByAnalyticsDashboardWidgetId(OWN_WIDGET_ID))
            .thenReturn(Optional.of(mine));

        ResponseDto response = this.service.deleteWidget(OWN_WIDGET_ID);

        assertThat(response.getStatus()).isEqualTo(ProcessUtil.SUCCESS);
        verify(this.analyticsDashboardWidgetRepository).delete(mine);
    }

    // ------------------------------------------------------------------------ the rows themselves

    @Test
    void allThreeTablesBelongToExactlyOneWorkspace() {
        for (Class<?> entity : Arrays.asList(
            AnalyticsAnalysis.class, AnalyticsDashboard.class, AnalyticsDashboardWidget.class)) {
            Filter filter = entity.getAnnotation(Filter.class);
            assertThat(filter).as(entity.getSimpleName() + " has no tenant filter").isNotNull();
            assertThat(filter.name()).isEqualTo("tenantFilter");
            // Deliberately not StorageConnection's "(tenant_id = :tenantId or tenant_id is null)".
            // That form publishes the platform's rows to every tenant, which is right for a
            // catalogue the whole application resolves through and wrong for one person's work.
            assertThat(filter.condition())
                .as(entity.getSimpleName() + " must filter on plain equality")
                .isEqualTo("tenant_id = :tenantId");
        }
    }

    @Test
    void allThreeTablesAreStampedWithTheirAuthorWithoutTheSavingCodeRememberingTo() {
        for (Class<?> entity : Arrays.asList(
            AnalyticsAnalysis.class, AnalyticsDashboard.class, AnalyticsDashboardWidget.class)) {
            EntityListeners listeners = entity.getAnnotation(EntityListeners.class);
            assertThat(listeners).as(entity.getSimpleName()).isNotNull();
            assertThat(listeners.value()).contains(AuditListener.class);
        }

        AnalyticsAnalysis analysis = new AnalyticsAnalysis();
        TenantContext.set(ACME, "TENANT_USER", ACME_USER, "user@acme.test");
        new AuditListener().onCreate(analysis);
        assertThat(analysis.getCreatedBy()).isEqualTo(ACME_USER);
        assertThat(analysis.getUpdatedBy()).isNull();

        TenantContext.set(ACME, "TENANT_ADMIN", 77L, "admin@acme.test");
        new AuditListener().onUpdate(analysis);
        assertThat(analysis.getCreatedBy()).isEqualTo(ACME_USER);
        assertThat(analysis.getUpdatedBy()).isEqualTo(77L);
    }

    /**
     * None of the three may say WHERE the data is, only WHICH connection reaches it.
     *
     * The resolver's whole safety case rests on the bucket coming from the connection record at
     * read time, and a saved analysis is the third table in this module that could quietly break
     * it by caching one.
     */
    @Test
    void nothingInTheWorkspaceSaysWhereTheConnectionPoints() {
        List<String> offenders = new ArrayList<>();
        for (Class<?> entity : Arrays.asList(
            AnalyticsAnalysis.class, AnalyticsDashboard.class, AnalyticsDashboardWidget.class)) {
            for (Field field : entity.getDeclaredFields()) {
                String name = field.getName().toLowerCase(Locale.ROOT);
                for (String word : LOCATION_AND_CREDENTIAL_WORDS) {
                    if (name.contains(word)) {
                        offenders.add(entity.getSimpleName() + "." + field.getName());
                    }
                }
            }
        }
        assertThat(offenders)
            .as("an analysis names the connection alias and the path; the bucket is read from the "
                + "connection at resolve time, and a copy here would be a second source of truth "
                + "for it that a repointed connection would silently leave stale")
            .isEmpty();
    }

    /**
     * stage and prod run Hibernate with ddl-auto=validate, so a column the changeset does not
     * create is a startup failure there and nothing at all in dev, where ddl-auto=update quietly
     * adds it. This is the cheap version of that check.
     */
    @Test
    void everyMappedColumnIsCreatedByTheChangeset() throws Exception {
        String sql = changesetSql().toLowerCase(Locale.ROOT);
        List<String> missing = new ArrayList<>();
        for (Class<?> entity : Arrays.asList(
            AnalyticsAnalysis.class, AnalyticsDashboard.class, AnalyticsDashboardWidget.class)) {
            assertThat(sql).contains(entity.getAnnotation(Table.class).name());
            for (Field field : entity.getDeclaredFields()) {
                Column column = field.getAnnotation(Column.class);
                if (column == null || field.getAnnotation(Transient.class) != null) {
                    continue;
                }
                if (!sql.contains(column.name().toLowerCase(Locale.ROOT))) {
                    missing.add(entity.getSimpleName() + "." + column.name());
                }
                SequenceGenerator generator = field.getAnnotation(SequenceGenerator.class);
                if (generator != null) {
                    // dev's ddl-auto=update would create the sequence at 1; prod's validate would
                    // not create it at all.
                    assertThat(sql).contains(generator.sequenceName());
                }
            }
        }
        assertThat(missing)
            .as("mapped but not in V34__analytics_workspace.sql -- add a new changeset, never edit "
                + "an applied one")
            .isEmpty();
    }

    /**
     * The half of the cross-tenant rule that lives in the database.
     *
     * A widget's references are keyed on (id, tenant_id) against a unique key on the same pair, so
     * a workspace has no cross-tenant pair to reference. Asserted as text because there is no
     * database in a unit test and because the difference between this and a single-column foreign
     * key is two words that a later edit could drop without anything failing.
     */
    @Test
    void theWidgetsReferencesAreKeyedOnTheTenantAsWellAsTheId() throws Exception {
        String sql = changesetSql().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");

        assertThat(sql).contains(
            "foreign key (analytics_analysis_id, tenant_id) references analytics_analysis "
                + "(analytics_analysis_id, tenant_id)");
        assertThat(sql).contains(
            "foreign key (analytics_query_id, tenant_id) references analytics_query "
                + "(analytics_query_id, tenant_id)");
        assertThat(sql).contains(
            "foreign key (analytics_dashboard_id, tenant_id) references analytics_dashboard "
                + "(analytics_dashboard_id, tenant_id)");
        // And the unique keys those three depend on, without which the foreign keys cannot exist.
        assertThat(sql).contains("unique (analytics_analysis_id, tenant_id)");
        assertThat(sql).contains("unique (analytics_dashboard_id, tenant_id)");
        assertThat(sql).contains("unique (analytics_query_id, tenant_id)");
    }

    /** A widget shows one thing. The service says so, and so does the table. */
    @Test
    void theChangesetRefusesAWidgetWithTwoSourcesOrNone() throws Exception {
        String sql = changesetSql().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        assertThat(sql).contains("ck_analytics_dashboard_widget_one_source check (");
        assertThat(sql).contains(
            "(analytics_analysis_id is not null and analytics_query_id is null) "
                + "or (analytics_analysis_id is null and analytics_query_id is not null)");
    }

    /**
     * A changeset nobody includes is a table nobody has.
     *
     * V31 shipped a table that no code read for a whole phase; this is the cheaper failure one
     * step earlier -- a table no database even gets.
     */
    @Test
    void theChangesetIsIncludedInTheMasterChangelog() throws Exception {
        String master = fileFromModuleRoot(
            "src/main/resources/db/changelog/db.changelog-master.yaml");
        assertThat(master).contains("db/changelog/yaml/V34.0-analytics-workspace.yaml");
    }

    // ---------------------------------------------------------------------------- the endpoint

    @Test
    void theEndpointsAreBehindTheSameRoleAsTheRestOfAnalyticsStudio() {
        PreAuthorize preAuthorize = AnnotatedElementUtils
            .findMergedAnnotation(AnalyticsWorkspaceRestApi.class, PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasRole('TENANT_USER')");
    }

    // ------------------------------------------------------------------------------- helpers

    private static void signedInAs(Long tenantId, String role, Long appUserId) {
        TenantContext.set(tenantId, role, appUserId, "user@test");
    }

    private void whenAnalysisLoaded(Long id, AnalyticsAnalysis analysis) {
        when(this.analyticsAnalysisRepository.findByAnalyticsAnalysisId(id))
            .thenReturn(Optional.of(analysis));
    }

    private void whenDashboardLoaded(Long id, AnalyticsDashboard dashboard) {
        when(this.analyticsDashboardRepository.findByAnalyticsDashboardId(id))
            .thenReturn(Optional.of(dashboard));
    }

    private void whenQueryLoaded(Long id, AnalyticsQuery query) {
        when(this.analyticsQueryRepository.findByAnalyticsQueryId(id)).thenReturn(Optional.of(query));
    }

    private void whenAnalysisSavedReturnTheRow() {
        when(this.analyticsAnalysisRepository.save(any(AnalyticsAnalysis.class)))
            .thenAnswer(invocation -> {
                AnalyticsAnalysis saved = invocation.getArgument(0);
                if (saved.getAnalyticsAnalysisId() == null) {
                    saved.setAnalyticsAnalysisId(OWN_ANALYSIS_ID);
                }
                return saved;
            });
    }

    private void whenWidgetSavedReturnTheRow() {
        when(this.analyticsDashboardWidgetRepository.save(any(AnalyticsDashboardWidget.class)))
            .thenAnswer(invocation -> {
                AnalyticsDashboardWidget saved = invocation.getArgument(0);
                if (saved.getAnalyticsDashboardWidgetId() == null) {
                    saved.setAnalyticsDashboardWidgetId(OWN_WIDGET_ID);
                }
                return saved;
            });
    }

    private AnalyticsAnalysis capturedAnalysisSave() {
        ArgumentCaptor<AnalyticsAnalysis> captor = ArgumentCaptor.forClass(AnalyticsAnalysis.class);
        verify(this.analyticsAnalysisRepository).save(captor.capture());
        return captor.getValue();
    }

    private AnalyticsDashboardWidget capturedWidgetSave() {
        ArgumentCaptor<AnalyticsDashboardWidget> captor =
            ArgumentCaptor.forClass(AnalyticsDashboardWidget.class);
        verify(this.analyticsDashboardWidgetRepository).save(captor.capture());
        return captor.getValue();
    }

    private static AnalyticsAnalysis analysisOwnedBy(Long tenantId, Long id) {
        AnalyticsAnalysis analysis = new AnalyticsAnalysis();
        analysis.setAnalyticsAnalysisId(id);
        analysis.setTenantId(tenantId);
        analysis.setAnalysisName("headcount by department");
        analysis.setConnectionAlias("etl-bucket");
        analysis.setDatasetPath("people/2026/*.csv");
        analysis.setVisualizationType("BAR");
        analysis.setAnalysisConfig(CONFIG);
        return analysis;
    }

    private static AnalyticsAnalysis analysisPayload() {
        AnalyticsAnalysis payload = new AnalyticsAnalysis();
        payload.setAnalysisName("headcount by department");
        payload.setConnectionAlias("etl-bucket");
        payload.setDatasetPath("people/2026/*.csv");
        payload.setAnalysisConfig(CONFIG);
        return payload;
    }

    private static AnalyticsDashboard dashboardOwnedBy(Long tenantId, Long id) {
        AnalyticsDashboard dashboard = new AnalyticsDashboard();
        dashboard.setAnalyticsDashboardId(id);
        dashboard.setTenantId(tenantId);
        dashboard.setDashboardName("monday review");
        return dashboard;
    }

    private static AnalyticsDashboardWidget widgetOwnedBy(Long tenantId, Long id) {
        AnalyticsDashboardWidget widget = new AnalyticsDashboardWidget();
        widget.setAnalyticsDashboardWidgetId(id);
        widget.setTenantId(tenantId);
        widget.setAnalyticsDashboardId(OWN_DASHBOARD_ID);
        widget.setWidgetTitle("headcount");
        widget.setAnalyticsAnalysisId(OWN_ANALYSIS_ID);
        return widget;
    }

    /** Deliberately points at nothing: each test sets the one source it is about. */
    private static AnalyticsDashboardWidget widgetPayload() {
        AnalyticsDashboardWidget payload = new AnalyticsDashboardWidget();
        payload.setAnalyticsDashboardId(OWN_DASHBOARD_ID);
        payload.setWidgetTitle("headcount");
        return payload;
    }

    private static AnalyticsQuery savedQueryOwnedBy(Long tenantId, Long id) {
        AnalyticsQuery query = new AnalyticsQuery();
        query.setAnalyticsQueryId(id);
        query.setTenantId(tenantId);
        query.setQueryName("last night's export");
        query.setConnectionAlias("etl-bucket");
        query.setDatasetPath("orders/2026/*.csv");
        query.setQueryText("select 1");
        return query;
    }

    private static String changesetSql() throws Exception {
        return fileFromModuleRoot("src/main/resources/db/changelog/changelog-sets/"
            + "V34.0-analytics-workspace/V34__analytics_workspace.sql");
    }

    private static String fileFromModuleRoot(String relativePath) throws Exception {
        // Surefire runs from the module root; the second path is for a run from the parent.
        for (String prefix : new String[] { "", "process/" }) {
            File file = new File(prefix + relativePath);
            if (file.isFile()) {
                return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("Could not find " + relativePath + " from "
            + System.getProperty("user.dir"));
    }

}

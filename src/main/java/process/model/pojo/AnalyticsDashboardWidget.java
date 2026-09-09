package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import javax.persistence.*;
import java.sql.Timestamp;

/**
 * One tile on a dashboard, pointing at exactly one saved analysis or one saved query.
 *
 * <b>This is the first row in the module that references another saved row, and that is what
 * makes it the dangerous one.</b> Everything before it was a leaf: a dataset, a saved query, an
 * analysis and a run each carry their own tenant and answer for nobody else. A widget that names
 * another workspace's analysis would render that workspace's data inside a dashboard whose own
 * ownership check passed -- a cross-tenant read that no single-row check on the widget would ever
 * catch, because the widget really does belong to the caller.
 *
 * So the rule is enforced twice. The service re-checks the referenced row's owner before saving
 * (a tenancy rule with exactly one enforcement point is one refactor away from having none), and
 * the changeset keys all three foreign keys on (id, tenant_id) against a unique key on the same
 * pair, so a widget owned by one workspace has no cross-tenant pair to reference at all. The
 * database half does not cover a platform admin's rows -- Postgres treats a foreign key with any
 * null column as satisfied -- which is why the service half is the one that always runs.
 *
 * Exactly one of {@link #analyticsAnalysisId} and {@link #analyticsQueryId} is set, and a check
 * constraint says so. There is deliberately no third column naming which kind it is: a label
 * beside two columns is a label that can contradict them.
 *
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "analytics_dashboard_widget", indexes = {
    @Index(name = "idx_analytics_dashboard_widget_dashboard",
        columnList = "analytics_dashboard_id, tenant_id, display_order"),
    @Index(name = "idx_analytics_dashboard_widget_analysis",
        columnList = "analytics_analysis_id, tenant_id"),
    @Index(name = "idx_analytics_dashboard_widget_query",
        columnList = "analytics_query_id, tenant_id")
})
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
// Plain equality, the same reading as every other table in this module. A widget is read only as
// part of a dashboard, but it carries its own tenant column and its own filter rather than
// inheriting the dashboard's: the tenant on this row is half of every foreign key it has, and a
// row whose tenancy is only implied by its parent cannot be the target of a composite key.
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@EntityListeners(AuditListener.class)
public class AnalyticsDashboardWidget implements Audited {

    @Transient
    private String createdByName;

    @Transient
    private String updatedByName;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "analytics_dashboard_widget_seq")
    @SequenceGenerator(name = "analytics_dashboard_widget_seq",
        sequenceName = "analytics_dashboard_widget_seq", allocationSize = 1)
    @Column(name = "analytics_dashboard_widget_id")
    private Long analyticsDashboardWidgetId;

    @Column(name = "tenant_id")
    private Long tenantId;

    // A plain column rather than a @ManyToOne, and the same for the two source ids below. An
    // association would be loaded by primary key, and a Hibernate @Filter does not apply to a
    // load by primary key -- so the convenient mapping is exactly the one that would walk past
    // the tenant boundary this row exists to hold.
    @Column(name = "analytics_dashboard_id", nullable = false)
    private Long analyticsDashboardId;

    @Column(name = "widget_title", nullable = false)
    private String widgetTitle;

    // Exactly one of these two is set. Which one is set is what says whether this widget shows an
    // analysis or a saved query; there is no separate kind column to disagree with them.
    @Column(name = "analytics_analysis_id")
    private Long analyticsAnalysisId;

    @Column(name = "analytics_query_id")
    private Long analyticsQueryId;

    /**
     * What to draw, or null.
     *
     * Null is meaningful twice over: a widget over a saved query may be a table, which is the
     * absence of a chart rather than a kind of one, and an analysis-backed widget that leaves it
     * null inherits the analysis's own visualization type instead of pinning a second copy of it
     * here.
     */
    @Column(name = "visualization_type", length = 32)
    private String visualizationType;

    /**
     * The rendering half -- axis choices, colours, size -- as JSON, for the same reason an
     * analysis keeps its configuration that way: the shape is still moving and a column per part
     * would be a migration per idea. Null when the widget takes every default; an empty object
     * stored to avoid a null is a null with extra steps.
     */
    @Column(name = "widget_config", columnDefinition = "TEXT")
    private String widgetConfig;

    // Where it sits, coarsely. A row/column/width/height quartet would freeze one layout engine's
    // model into the schema before a layout engine has been chosen; a finer position belongs in
    // widgetConfig until something server-side needs to read it.
    @Column(name = "display_order", nullable = false)
    private Integer displayOrder = 0;

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated = new Timestamp(System.currentTimeMillis());

    @Column(name = "date_updated")
    private Timestamp dateUpdated;

    public AnalyticsDashboardWidget() {}

    /** True when this widget shows a saved analysis rather than a saved query. */
    public boolean isAnalysisBacked() {
        return this.analyticsAnalysisId != null;
    }

    public Long getAnalyticsDashboardWidgetId() { return analyticsDashboardWidgetId; }
    public void setAnalyticsDashboardWidgetId(Long analyticsDashboardWidgetId) { this.analyticsDashboardWidgetId = analyticsDashboardWidgetId; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getAnalyticsDashboardId() { return analyticsDashboardId; }
    public void setAnalyticsDashboardId(Long analyticsDashboardId) { this.analyticsDashboardId = analyticsDashboardId; }

    public String getWidgetTitle() { return widgetTitle; }
    public void setWidgetTitle(String widgetTitle) { this.widgetTitle = widgetTitle; }

    public Long getAnalyticsAnalysisId() { return analyticsAnalysisId; }
    public void setAnalyticsAnalysisId(Long analyticsAnalysisId) { this.analyticsAnalysisId = analyticsAnalysisId; }

    public Long getAnalyticsQueryId() { return analyticsQueryId; }
    public void setAnalyticsQueryId(Long analyticsQueryId) { this.analyticsQueryId = analyticsQueryId; }

    public String getVisualizationType() { return visualizationType; }
    public void setVisualizationType(String visualizationType) { this.visualizationType = visualizationType; }

    public String getWidgetConfig() { return widgetConfig; }
    public void setWidgetConfig(String widgetConfig) { this.widgetConfig = widgetConfig; }

    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }

    public Timestamp getDateCreated() { return dateCreated; }
    public void setDateCreated(Timestamp dateCreated) { this.dateCreated = dateCreated; }

    public Timestamp getDateUpdated() { return dateUpdated; }
    public void setDateUpdated(Timestamp dateUpdated) { this.dateUpdated = dateUpdated; }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

    @Override
    public Long getCreatedBy() {
        return createdBy;
    }

    @Override
    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    @Override
    public Long getUpdatedBy() {
        return updatedBy;
    }

    @Override
    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    @Override
    public String getCreatedByName() {
        return createdByName;
    }

    @Override
    public void setCreatedByName(String createdByName) {
        this.createdByName = createdByName;
    }

    @Override
    public String getUpdatedByName() {
        return updatedByName;
    }

    @Override
    public void setUpdatedByName(String updatedByName) {
        this.updatedByName = updatedByName;
    }
}

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
 * A page of saved results: a name, a sentence saying what it is for, and the widgets on it.
 *
 * <b>This entity does not settle the question it looks like it settles.</b>
 * .ai/synthesis/analytics-studio.md section 6, Q2 asks whether Analytics Studio should have a
 * charting stack of its own or whether analytics results should feed the existing /reports pivot,
 * calls it "the most consequential open question in this document", and records that the user has
 * been warned and has not decided. Nothing here answers it: this row holds a title and an owner,
 * a widget holds a reference and an opaque rendering configuration, and no chart kind, chart
 * library or drawing rule appears in either. If Q2 lands on the pivot, what these two tables hold
 * is a saved arrangement of results and the drawing still happens over there.
 *
 * The widgets are deliberately not a mapped collection. They are fetched by the service as their
 * own tenant-filtered query, because a cascade from here would load them by primary key and a
 * Hibernate @Filter does not apply to a load by primary key -- which is the trap the whole module
 * has already been bitten by once.
 *
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "analytics_dashboard", indexes = {
    @Index(name = "idx_analytics_dashboard_tenant_id", columnList = "tenant_id")
})
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
// Plain equality, the same reading as every other table in this module and deliberately NOT
// StorageConnection's "or tenant_id is null". A dashboard is one workspace's page of its own
// results; nothing resolves through it and there is no platform-owned default to fall back on,
// so admitting a null tenant here would publish a platform admin's dashboards to every tenant.
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@EntityListeners(AuditListener.class)
public class AnalyticsDashboard implements Audited {

    @Transient
    private String createdByName;

    @Transient
    private String updatedByName;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "analytics_dashboard_seq")
    @SequenceGenerator(name = "analytics_dashboard_seq", sequenceName = "analytics_dashboard_seq", allocationSize = 1)
    @Column(name = "analytics_dashboard_id")
    private Long analyticsDashboardId;

    // Null for a platform admin, who has no tenant of their own. The filter above means that is a
    // dashboard only platform admins can see, not one shared with everybody.
    @Column(name = "tenant_id")
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", insertable = false, updatable = false)
    private Tenant tenant;

    @Column(name = "dashboard_name", nullable = false)
    private String dashboardName;

    // A dashboard is the one thing in this module made to be shown to somebody who did not build
    // it, and a name alone does not survive that.
    @Column(name = "dashboard_description", columnDefinition = "TEXT")
    private String dashboardDescription;

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated = new Timestamp(System.currentTimeMillis());

    @Column(name = "date_updated")
    private Timestamp dateUpdated;

    /**
     * The widgets, when a caller asked for one dashboard rather than the list.
     *
     * Not persisted and not a mapped association: the service fills it in from a tenant-filtered
     * query, so the widgets on a dashboard are scoped the same way everything else in the module
     * is rather than arriving through a cascade that the tenant filter never sees.
     */
    @Transient
    private java.util.List<AnalyticsDashboardWidget> widgets;

    public AnalyticsDashboard() {}

    public Long getAnalyticsDashboardId() { return analyticsDashboardId; }
    public void setAnalyticsDashboardId(Long analyticsDashboardId) { this.analyticsDashboardId = analyticsDashboardId; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }

    public String getDashboardName() { return dashboardName; }
    public void setDashboardName(String dashboardName) { this.dashboardName = dashboardName; }

    public String getDashboardDescription() { return dashboardDescription; }
    public void setDashboardDescription(String dashboardDescription) { this.dashboardDescription = dashboardDescription; }

    public Timestamp getDateCreated() { return dateCreated; }
    public void setDateCreated(Timestamp dateCreated) { this.dateCreated = dateCreated; }

    public Timestamp getDateUpdated() { return dateUpdated; }
    public void setDateUpdated(Timestamp dateUpdated) { this.dateUpdated = dateUpdated; }

    public java.util.List<AnalyticsDashboardWidget> getWidgets() { return widgets; }
    public void setWidgets(java.util.List<AnalyticsDashboardWidget> widgets) { this.widgets = widgets; }

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

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
 * An analysis somebody built and kept: where to read, and how to cut it.
 *
 * It names its location the way {@link AnalyticsDataset} and {@link AnalyticsQuery} do -- a
 * storage connection ALIAS and a path inside it -- rather than pointing at an analytics_dataset
 * row. Most analyses are built on the dataset the user is already looking at and never got round
 * to registering, and making a dataset row a precondition for pressing Save would put an
 * unrelated step in front of the one thing the Canvas is for. Carrying both an id and a path
 * would be worse than either: a row with two ways to say where it reads from is a row that can
 * disagree with itself.
 *
 * What is NOT here is the same list as on the other two, for the same reason. No bucket, no
 * endpoint, no region, no credential: DatasetResolver looks the alias up when the analysis is
 * opened, so a connection later repointed at a different bucket moves its saved analyses with it
 * instead of leaving them quietly reading the old one.
 *
 * <b>The configuration is one JSON column and not twenty.</b> Dimensions, measures, aggregation,
 * filters, sort and top-N are all still being designed -- spec 07 alone names eleven filter
 * operators and nested AND/OR groups -- and a column per part would mean a migration per new
 * operator and a shape that has to be agreed between the changeset and the Canvas before either
 * can move. The cost is real and is stated in the changeset: Postgres cannot answer "which
 * analyses group by department". The counterpart rule is that anything the SERVER acts on gets a
 * column, because a tenancy check or a foreign key cannot be written against a substring.
 *
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "analytics_analysis", indexes = {
    @Index(name = "idx_analytics_analysis_tenant_id", columnList = "tenant_id")
})
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
// Plain equality, the same call AnalyticsDataset and AnalyticsQuery made and deliberately NOT
// StorageConnection's "(tenant_id = :tenantId or tenant_id is null)". That form admits the
// platform's own rows to every tenant, which is right for a catalogue the whole application
// resolves buckets through and wrong for one person's saved work: nothing else resolves through a
// saved analysis, and there is no platform-owned default for a tenant to fall back on. Admitting
// a null tenant here would publish a platform admin's analyses -- name, connection alias, path,
// and the fields they group and filter by -- to every tenant on the box. That is the same shape
// as the cross-tenant read this module was fixed for on 2026-09-08, when DatasetResolver was
// moved off TenantOwnership.isVisibleToCaller.
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@EntityListeners(AuditListener.class)
public class AnalyticsAnalysis implements Audited {

    @Transient
    private String createdByName;

    @Transient
    private String updatedByName;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "analytics_analysis_seq")
    @SequenceGenerator(name = "analytics_analysis_seq", sequenceName = "analytics_analysis_seq", allocationSize = 1)
    @Column(name = "analytics_analysis_id")
    private Long analyticsAnalysisId;

    // Null for a platform admin, who has no tenant of their own. The filter above means that is
    // an analysis only platform admins can see, not one shared with everybody.
    @Column(name = "tenant_id")
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", insertable = false, updatable = false)
    private Tenant tenant;

    @Column(name = "analysis_name", nullable = false)
    private String analysisName;

    // The alias, not the connection id: it is what the caller already sends, what the object
    // browser shows, and what DatasetResolver looks a connection up by.
    @Column(name = "connection_alias", nullable = false)
    private String connectionAlias;

    // Keeps its glob when the analysis reads a folder as one table -- for those, the pattern IS
    // the location, and expanding it at save time would freeze a folder still being written to.
    @Column(name = "dataset_path", columnDefinition = "TEXT", nullable = false)
    private String datasetPath;

    /**
     * The chart kind this analysis is drawn as -- the one field lifted out of the JSON.
     *
     * A listing shows it as an icon and a dashboard widget dispatches on it, so the server reads
     * it where parsing the whole configuration would be absurd. It must not also appear inside
     * {@link #analysisConfig}: two places to say the same thing is one row that can disagree with
     * itself. A String rather than an enum for the same reason AnalyticsQueryRun's status is one
     * -- the names are shared with a screen and a JSON payload, and adding a chart kind should
     * not be a schema change.
     */
    @Column(name = "visualization_type", length = 32)
    private String visualizationType;

    /**
     * Dimensions, measures, aggregation, filters, sort and top-N, as the Canvas sent them.
     *
     * Stored as submitted and never rewritten here. This row holds what somebody asked for; the
     * SQL it compiles to belongs to the analysis query builder and to the engine's configuration
     * on the day it runs, and baking either into a saved analysis would freeze something the
     * operator can still change.
     */
    @Column(name = "analysis_config", columnDefinition = "TEXT", nullable = false)
    private String analysisConfig;

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated = new Timestamp(System.currentTimeMillis());

    // A dataset row is never updated and so carries no such column. This one is renamed and its
    // configuration is edited -- reopening an analysis is what it is for -- and updated_by on its
    // own says who without ever saying when.
    @Column(name = "date_updated")
    private Timestamp dateUpdated;

    public AnalyticsAnalysis() {}

    public Long getAnalyticsAnalysisId() { return analyticsAnalysisId; }
    public void setAnalyticsAnalysisId(Long analyticsAnalysisId) { this.analyticsAnalysisId = analyticsAnalysisId; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }

    public String getAnalysisName() { return analysisName; }
    public void setAnalysisName(String analysisName) { this.analysisName = analysisName; }

    public String getConnectionAlias() { return connectionAlias; }
    public void setConnectionAlias(String connectionAlias) { this.connectionAlias = connectionAlias; }

    public String getDatasetPath() { return datasetPath; }
    public void setDatasetPath(String datasetPath) { this.datasetPath = datasetPath; }

    public String getVisualizationType() { return visualizationType; }
    public void setVisualizationType(String visualizationType) { this.visualizationType = visualizationType; }

    public String getAnalysisConfig() { return analysisConfig; }
    public void setAnalysisConfig(String analysisConfig) { this.analysisConfig = analysisConfig; }

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

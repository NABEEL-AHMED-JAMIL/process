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
 * A query somebody named and kept: where to read, and what to ask.
 *
 * It names its location the way {@link AnalyticsDataset} does -- a storage connection ALIAS and a
 * path inside it -- rather than pointing at an analytics_dataset row. Most queries are written
 * against a path the user never got round to saving as a dataset, and making a dataset row a
 * precondition for pressing Save would put an unrelated step in front of the one thing this
 * feature is for. Carrying both a dataset id and a path would be worse than either: a row with
 * two ways to say where it reads from is a row that can disagree with itself.
 *
 * What is NOT here is the same list as on a dataset, for the same reason. No bucket, no endpoint,
 * no region, no credential: DatasetResolver looks the alias up at the moment the query is run to
 * find out where it currently points, so a connection later repointed at a different bucket moves
 * its saved queries with it instead of leaving them quietly reading the old one.
 *
 * The SQL is stored exactly as it was typed. The row ceiling the engine applies at execution
 * belongs to the engine's configuration on the day it runs, so a saved query must not carry a
 * rewritten copy of itself with yesterday's LIMIT baked in.
 *
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "analytics_query", indexes = {
    @Index(name = "idx_analytics_query_tenant_id", columnList = "tenant_id")
})
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
// Plain equality, the same call AnalyticsDataset made and deliberately NOT StorageConnection's
// "(tenant_id = :tenantId or tenant_id is null)". That form admits the platform's own rows to
// every tenant, which is right for a catalogue the whole application resolves buckets through and
// wrong for one person's saved work: nothing else resolves through a saved query, and there is no
// platform-owned default for a tenant to fall back on. Admitting a null tenant here would publish
// a platform admin's saved queries -- name, connection alias, path and their SQL -- to every
// tenant on the box. That is the same shape as the cross-tenant read this module was fixed for on
// 2026-09-08, when DatasetResolver was moved off TenantOwnership.isVisibleToCaller.
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@EntityListeners(AuditListener.class)
public class AnalyticsQuery implements Audited {

    @Transient
    private String createdByName;

    @Transient
    private String updatedByName;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "analytics_query_seq")
    @SequenceGenerator(name = "analytics_query_seq", sequenceName = "analytics_query_seq", allocationSize = 1)
    @Column(name = "analytics_query_id")
    private Long analyticsQueryId;

    // Null for a platform admin, who has no tenant of their own. The filter above means that is a
    // query only platform admins can see, not one shared with everybody.
    @Column(name = "tenant_id")
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", insertable = false, updatable = false)
    private Tenant tenant;

    @Column(name = "query_name", nullable = false)
    private String queryName;

    // The alias, not the connection id: it is what the caller already sends, what the object
    // browser shows, and what DatasetResolver looks a connection up by.
    @Column(name = "connection_alias", nullable = false)
    private String connectionAlias;

    // Keeps its glob when the query reads a folder as one table -- for those, the pattern IS the
    // location, and expanding it at save time would freeze a folder still being written to.
    @Column(name = "dataset_path", columnDefinition = "TEXT", nullable = false)
    private String datasetPath;

    @Column(name = "query_text", columnDefinition = "TEXT", nullable = false)
    private String queryText;

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated = new Timestamp(System.currentTimeMillis());

    // A dataset row is never updated and so carries no such column. This one is renamed and its
    // SQL is edited, and updated_by on its own says who without ever saying when.
    @Column(name = "date_updated")
    private Timestamp dateUpdated;

    public AnalyticsQuery() {}

    public Long getAnalyticsQueryId() { return analyticsQueryId; }
    public void setAnalyticsQueryId(Long analyticsQueryId) { this.analyticsQueryId = analyticsQueryId; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }

    public String getQueryName() { return queryName; }
    public void setQueryName(String queryName) { this.queryName = queryName; }

    public String getConnectionAlias() { return connectionAlias; }
    public void setConnectionAlias(String connectionAlias) { this.connectionAlias = connectionAlias; }

    public String getDatasetPath() { return datasetPath; }
    public void setDatasetPath(String datasetPath) { this.datasetPath = datasetPath; }

    public String getQueryText() { return queryText; }
    public void setQueryText(String queryText) { this.queryText = queryText; }

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

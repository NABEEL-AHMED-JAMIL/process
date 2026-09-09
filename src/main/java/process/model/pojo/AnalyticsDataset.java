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
 * A dataset somebody named and kept: one storage connection alias, and one path inside it.
 *
 * The first thing Analytics Studio persists, and the dataset rather than the query on purpose. A
 * saved query, a chart and a benchmark result all have to say which data they are about, and if
 * each of those phases invents its own way of naming a location there will be three spellings of
 * "the CSVs in that folder" and no way to answer "what else points at this?".
 *
 * What is NOT on this row is the design. There is no bucket, no endpoint, no region and no
 * credential: a dataset carries the connection ALIAS, and DatasetResolver looks the connection up
 * at the moment the dataset is opened to find out where that alias currently points. Storing the
 * bucket here would make this a second source of truth for it, so a connection later repointed at
 * a different bucket would leave every saved dataset quietly reading the old one -- and the
 * module's central property, that a caller names a connection and never a bucket, would stop being
 * true of the saved ones.
 *
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "analytics_dataset", indexes = {
    @Index(name = "idx_analytics_dataset_tenant_id", columnList = "tenant_id")
})
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
// Plain equality, NOT StorageConnection's "(tenant_id = :tenantId or tenant_id is null)". That
// one deliberately admits the platform's own rows because a storage connection is a catalogue the
// whole application resolves buckets through, and hiding etl-bucket from the alias lookup broke
// every workflow writing there. A saved dataset is the opposite kind of row: it is one person's
// work in one workspace, nothing else resolves through it, and there is no platform-owned default
// for a tenant to fall back on. Admitting a null tenant here would publish a platform admin's
// saved datasets -- name, connection alias and path -- to every tenant on the box, which is the
// same shape as the cross-tenant read this module was fixed for on 2026-09-08 when the resolver
// was moved off TenantOwnership.isVisibleToCaller.
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@EntityListeners(AuditListener.class)
public class AnalyticsDataset implements Audited {

    @Transient
    private String createdByName;

    @Transient
    private String updatedByName;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "analytics_dataset_seq")
    @SequenceGenerator(name = "analytics_dataset_seq", sequenceName = "analytics_dataset_seq", allocationSize = 1)
    @Column(name = "analytics_dataset_id")
    private Long analyticsDatasetId;

    // Null for a platform admin, who has no tenant of their own. The filter above means that is a
    // dataset only platform admins can see, not one shared with everybody.
    @Column(name = "tenant_id")
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", insertable = false, updatable = false)
    private Tenant tenant;

    @Column(name = "dataset_name", nullable = false)
    private String datasetName;

    // The alias, not the connection id: it is what the caller already sends, what the object
    // browser shows, and what DatasetResolver looks a connection up by.
    @Column(name = "connection_alias", nullable = false)
    private String connectionAlias;

    // Keeps its glob when the dataset is a folder read as one table -- for those, the pattern IS
    // the dataset, and expanding it at save time would freeze a folder that is still being
    // written to.
    @Column(name = "dataset_path", columnDefinition = "TEXT", nullable = false)
    private String datasetPath;

    /**
     * The name of a DatasetRef.Format -- CSV, TSV, JSON or PARQUET.
     *
     * A String rather than that enum because process.analytics already depends on this package and
     * mapping it here would point the dependency both ways. It costs nothing: the format is
     * derived from the path by the resolver on every read, so this column is a label for a listing
     * screen and never the thing the engine picks a reader from.
     */
    @Column(name = "dataset_format", length = 24, nullable = false)
    private String datasetFormat;

    @Column(name = "date_created", nullable = false)
    private Timestamp dateCreated = new Timestamp(System.currentTimeMillis());

    public AnalyticsDataset() {}

    public Long getAnalyticsDatasetId() { return analyticsDatasetId; }
    public void setAnalyticsDatasetId(Long analyticsDatasetId) { this.analyticsDatasetId = analyticsDatasetId; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }

    public String getDatasetName() { return datasetName; }
    public void setDatasetName(String datasetName) { this.datasetName = datasetName; }

    public String getConnectionAlias() { return connectionAlias; }
    public void setConnectionAlias(String connectionAlias) { this.connectionAlias = connectionAlias; }

    public String getDatasetPath() { return datasetPath; }
    public void setDatasetPath(String datasetPath) { this.datasetPath = datasetPath; }

    public String getDatasetFormat() { return datasetFormat; }
    public void setDatasetFormat(String datasetFormat) { this.datasetFormat = datasetFormat; }

    public Timestamp getDateCreated() { return dateCreated; }
    public void setDateCreated(Timestamp dateCreated) { this.dateCreated = dateCreated; }

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

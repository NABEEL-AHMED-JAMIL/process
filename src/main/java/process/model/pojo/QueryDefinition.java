package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.ParamDef;
import process.model.enums.Status;
import javax.persistence.*;
import java.sql.Timestamp;

/**
 * A tenant-owned, saved SQL query. queryText is stored encrypted (see EncryptionUtil) in this
 * table's own row -- NOT as a .txt file in object storage. Reviewed both designs against the
 * project's own tooling: query text here is a few KB at most, so there's no size/performance
 * reason to keep it out of Postgres, and every property object storage would have to hand-roll
 * (concurrent-update safety, versioning, tenant isolation, transactional consistency with the
 * rest of this row, backup/recovery) Postgres already gives for free via @Version (optimistic
 * locking below) and the same tested tenantFilter this whole app relies on -- object storage
 * would only add a second system to keep in sync for no corresponding benefit at this size.
 * databaseConnectionProfileId is a plain FK column, not a JPA @ManyToOne -- the owning
 * connection profile is re-validated (same tenant, still exists) in the service layer on every
 * read/write anyway, so a lazy association would just be an extra round trip most callers don't
 * need.
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "query_definition", indexes = {
    @Index(name = "idx_query_definition_tenant_id", columnList = "tenant_id")
})
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class QueryDefinition {

    @GenericGenerator(
        name = "queryDefinitionSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "query_definition_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "query_id")
    @GeneratedValue(generator = "queryDefinitionSequenceGenerator")
    private Long queryId;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "query_name", nullable = false)
    private String queryName;

    /** AES-256-GCM ciphertext (see EncryptionUtil) -- query text can embed schema/business
     * logic, so it's encrypted at rest the same way credentials are, even though it isn't a
     * secret in the same sense. Decrypted only in-process, immediately before execution/preview
     * -- never returned to the frontend in list responses (see QueryDefinitionServiceImpl). */
    @Column(name = "query_text", columnDefinition = "TEXT", nullable = false)
    private String queryText;

    @Column(name = "database_connection_profile_id", nullable = false)
    private Long databaseConnectionProfileId;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    /** Hibernate-managed optimistic-lock counter -- auto-incremented on every UPDATE, and a
     * concurrent second update against the same original version throws
     * OptimisticLockException instead of silently overwriting the first writer's change. */
    @Version
    @Column(name = "version")
    private Integer version;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at")
    private Timestamp createdAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at")
    private Timestamp updatedAt;

    public QueryDefinition() {}

    public Long getQueryId() {
        return queryId;
    }

    public void setQueryId(Long queryId) {
        this.queryId = queryId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getQueryName() {
        return queryName;
    }

    public void setQueryName(String queryName) {
        this.queryName = queryName;
    }

    public String getQueryText() {
        return queryText;
    }

    public void setQueryText(String queryText) {
        this.queryText = queryText;
    }

    public Long getDatabaseConnectionProfileId() {
        return databaseConnectionProfileId;
    }

    public void setDatabaseConnectionProfileId(Long databaseConnectionProfileId) {
        this.databaseConnectionProfileId = databaseConnectionProfileId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(Long createdBy) {
        this.createdBy = createdBy;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}

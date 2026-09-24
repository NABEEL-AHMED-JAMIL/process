package process.model.pojo;

import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.ParamDef;
import org.hibernate.annotations.Parameter;

import javax.persistence.*;
import java.sql.Timestamp;

/**
 * A workspace's home page or task group (MIG-167): what source_task.home_page_id and group_id point at. Moved out of
 * lookup_data by V141 with the ids it had there. A home page's value is the http(s) URL handed to the worker at
 * dispatch; a group's is an optional label. Names are unique per workspace and kind (owner's D3).
 */
@Entity
@Table(name = "task_reference")
@EntityListeners(AuditListener.class)
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class TaskReference implements Audited {

    public static final String HOME_PAGE = "HOME_PAGE";
    public static final String TASK_GROUP = "TASK_GROUP";

    @GenericGenerator(
        name = "taskReferenceSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "task_reference_id_seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "id")
    @GeneratedValue(generator = "taskReferenceSequenceGenerator")
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "value", columnDefinition = "text")
    private String value;

    @Column(name = "description")
    private String description;

    @Column(name = "created_at", nullable = false)
    private Timestamp createdAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private Timestamp updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Transient
    private String createdByName;

    @Transient
    private String updatedByName;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = new Timestamp(System.currentTimeMillis());
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = new Timestamp(System.currentTimeMillis());
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
    public Timestamp getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Timestamp updatedAt) { this.updatedAt = updatedAt; }

    @Override public Long getCreatedBy() { return createdBy; }
    @Override public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    @Override public Long getUpdatedBy() { return updatedBy; }
    @Override public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    @Override public String getCreatedByName() { return createdByName; }
    @Override public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }
    @Override public String getUpdatedByName() { return updatedByName; }
    @Override public void setUpdatedByName(String updatedByName) { this.updatedByName = updatedByName; }
}

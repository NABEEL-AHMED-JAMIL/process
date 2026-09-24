package process.model.pojo;

import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.ParamDef;
import org.hibernate.annotations.Parameter;

import javax.persistence.*;
import java.sql.Timestamp;

/**
 * One configuration entry of a workspace (MIG-167), referenced from task payloads as ${config:KEY} or ${secret:KEY}.
 *
 * A VALUE keeps its value in {@code value}. A SECRET keeps it only in {@code valueSealed}, sealed by EncryptionUtil
 * under the current key; there is no field that holds a secret in the clear, and this class has no toString, so
 * nothing that logs an entity can log one. The database refuses a secret in {@code value} and anything but the
 * tagged sealed format in {@code value_sealed} (V143's check).
 */
@Entity
@Table(name = "pipeline_config")
@EntityListeners(AuditListener.class)
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class PipelineConfig implements Audited {

    public static final String VALUE = "VALUE";
    public static final String SECRET = "SECRET";

    @GenericGenerator(
        name = "pipelineConfigSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "pipeline_config_id_seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "id")
    @GeneratedValue(generator = "pipelineConfigSequenceGenerator")
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "config_key", nullable = false, length = 64)
    private String configKey;

    @Column(name = "kind", nullable = false, length = 8)
    private String kind;

    @Column(name = "value", columnDefinition = "text")
    private String value;

    @Column(name = "value_sealed", columnDefinition = "text")
    private String valueSealed;

    @Column(name = "description")
    private String description;

    /** When the value or secret itself was last written; a description edit does not move it. */
    @Column(name = "value_set_at", nullable = false)
    private Timestamp valueSetAt;

    @Column(name = "value_set_by")
    private Long valueSetBy;

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
        if (this.valueSetAt == null) {
            this.valueSetAt = this.createdAt;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = new Timestamp(System.currentTimeMillis());
    }

    public boolean isSecret() {
        return SECRET.equals(this.kind);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getValueSealed() { return valueSealed; }
    public void setValueSealed(String valueSealed) { this.valueSealed = valueSealed; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Timestamp getValueSetAt() { return valueSetAt; }
    public void setValueSetAt(Timestamp valueSetAt) { this.valueSetAt = valueSetAt; }
    public Long getValueSetBy() { return valueSetBy; }
    public void setValueSetBy(Long valueSetBy) { this.valueSetBy = valueSetBy; }
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

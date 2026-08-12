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
import java.util.ArrayList;
import java.util.List;

/**
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "dynamic_form", indexes = {
    @Index(name = "idx_dynamic_form_tenant_id", columnList = "tenant_id")
})
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DynamicForm {

    @GenericGenerator(
        name = "dynamicFormSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "dynamic_form_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "dynamic_form_id")
    @GeneratedValue(generator = "dynamicFormSequenceGenerator")
    private Long dynamicFormId;

    /** Owning tenant -- see Tenant/TenantContext. Nullable during the Phase 0 migration window
     * (backfilled to the Default tenant by TenantSeedService on startup). */
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "form_name", nullable = false)
    private String formName;

    @Column(name = "description")
    private String description;

    @Column(name = "form_status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "date_created")
    private Timestamp dateCreated;

    /**
     * Opaque id for the shareable fetch-by-uuid API -- deliberately separate from the
     * sequential dynamicFormId so a shared/public link can't be used to enumerate other forms
     * by incrementing the number. Nullable at the column level only so existing rows (created
     * before this field existed) don't break the schema upgrade -- backfilled lazily the first
     * time such a row is read (see DynamicFormServiceImpl#ensureUuid), same as every new form
     * gets one on creation (see DynamicFormServiceImpl#addForm).
     * */
    @Column(name = "uuid", unique = true)
    private String uuid;

    @JoinColumn(name = "dynamic_form_id")
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("fieldOrder asc")
    private List<DynamicFormField> fields = new ArrayList<>();

    public DynamicForm() {}

    public Long getDynamicFormId() {
        return dynamicFormId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public void setDynamicFormId(Long dynamicFormId) {
        this.dynamicFormId = dynamicFormId;
    }

    public String getFormName() {
        return formName;
    }

    public void setFormName(String formName) {
        this.formName = formName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public List<DynamicFormField> getFields() {
        return fields;
    }

    public void setFields(List<DynamicFormField> fields) {
        this.fields = fields;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}

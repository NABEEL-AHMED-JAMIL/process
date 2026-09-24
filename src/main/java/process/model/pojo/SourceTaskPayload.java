package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.hibernate.annotations.ParamDef;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.Filter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import javax.persistence.*;

/**
 * @author Nabeel Ahmed
 * */
@Entity
@Table(name = "source_task_payload")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
public class SourceTaskPayload {

    @GenericGenerator(
        name = "sourceTaskPayloadSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "source_task_payload_seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "task_payload_id")
    @GeneratedValue(generator = "sourceTaskPayloadSequenceGenerator")
    private Long taskPayloadId;

    @Column(name = "tag_key", nullable = true)
    private String tagKey;

    @Column(name = "tag_parent", nullable = true)
    private String tagParent;

    @Column(name = "tag_value", nullable = true, columnDefinition = "TEXT")
    private String tagValue;

    /**
     * The task's tenant (V102, MIG-29/164): set when the row is written, and kept equal to the source_task row's by the database
     * (fk_source_task_payload_task_tenant, ON UPDATE CASCADE) -- so never written again from here. What the tenant filter scopes on.
     */
    // Not on the wire: nothing a console sends or reads names it (the wire format is unchanged).
    @JsonIgnore
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private Long tenantId;

    public SourceTaskPayload() {
    }

    public Long getTaskPayloadId() {
        return taskPayloadId;
    }

    public void setTaskPayloadId(Long taskPayloadId) {
        this.taskPayloadId = taskPayloadId;
    }

    public String getTagKey() {
        return tagKey;
    }

    public void setTagKey(String tagKey) {
        this.tagKey = tagKey;
    }

    public String getTagParent() {
        return tagParent;
    }

    public void setTagParent(String tagParent) {
        this.tagParent = tagParent;
    }

    public String getTagValue() {
        return tagValue;
    }

    public void setTagValue(String tagValue) {
        this.tagValue = tagValue;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }


    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }
}

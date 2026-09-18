package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import org.hibernate.annotations.ParamDef;
import process.model.enums.Status;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A pipeline a task can run: its public id (what the worker routes on), the topic it publishes
 * on, and the fields a task on it fills in -- so a task can be configured by form rather than
 * by hand-written XML.
 *
 * Holds no payload of its own. A task's tags remain the only record of what it sends; this
 * describes what those tags mean. Delete a pipeline and every task built with it keeps working.
 * Was task_form until V43; pipelineKey is the surrogate key, pipelineId the public id.
 *
 * @author Nabeel Ahmed
 */
@Entity
@Table(name = "pipeline")
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
// Declared for consistency with every other tenant-scoped entity, but currently inert:
// PipelineServiceImpl has no EntityManager/TenantFilterHelper and never enables this filter --
// the actual rule lives in PipelineRepository's own @Query methods (findVisibleToTenant,
// findForPipeline), both strict "tenant_id = :tenantId" (2026-09-05; a form used to also match
// a null-tenant "shared with every tenant" row, the same pattern Kafka Connections was already
// walked back from -- see TenantOwnership's javadoc).
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@EntityListeners(AuditListener.class)
public class Pipeline implements Audited {
    @Transient
    private String updatedByName;

    @Column(name = "updated_by")
    private Long updatedBy;


    @GenericGenerator(
        name = "pipelineSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "pipeline_source_seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        })
    @Id
    @GeneratedValue(generator = "pipelineSequenceGenerator")
    @Column(name = "pipeline_key")
    private Long pipelineKey;

    @Column(name = "pipeline_id", nullable = false)
    private String pipelineId;

    @Column(name = "pipeline_name", nullable = false)
    private String pipelineName;

    @Column(name = "description")
    private String description;

    @Column(name = "tenant_id")
    private Long tenantId;

    /** The topic (source task type) this pipeline publishes on; null only for a pre-V43 row. */
    @Column(name = "source_task_type_id")
    private Long sourceTaskTypeId;

    /** Filled in on the way out: the topic's name and Kafka topic, for the console's lists. */
    @Transient
    private String topicName;
    @Transient
    private String kafkaTopic;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.Active;

    @Column(name = "date_created", nullable = false)
    private LocalDateTime dateCreated = LocalDateTime.now();

    @Column(name = "created_by")
    private Long createdBy;

    /** Resolved for display only -- created_by holds the id, and an id tells a reader nothing. */
    @Transient
    private String createdByName;

    /**
     * Loaded with the form and replaced wholesale on save.
     *
     * orphanRemoval because a field taken off a form has no meaning without it -- leaving the
     * row behind would resurrect the field the next time the form was read.
     */
    @OneToMany(mappedBy = "pipeline", cascade = CascadeType.ALL, orphanRemoval = true,
               fetch = FetchType.EAGER)
    @OrderBy("position ASC")
    private List<PipelineField> fields = new ArrayList<>();

    public Long getPipelineKey() { return pipelineKey; }
    public void setPipelineKey(Long pipelineKey) { this.pipelineKey = pipelineKey; }

    public String getPipelineId() { return pipelineId; }
    public void setPipelineId(String pipelineId) { this.pipelineId = pipelineId; }

    public String getPipelineName() { return pipelineName; }
    public void setPipelineName(String pipelineName) { this.pipelineName = pipelineName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getSourceTaskTypeId() { return sourceTaskTypeId; }
    public void setSourceTaskTypeId(Long sourceTaskTypeId) { this.sourceTaskTypeId = sourceTaskTypeId; }

    public String getTopicName() { return topicName; }
    public void setTopicName(String topicName) { this.topicName = topicName; }

    public String getKafkaTopic() { return kafkaTopic; }
    public void setKafkaTopic(String kafkaTopic) { this.kafkaTopic = kafkaTopic; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public LocalDateTime getDateCreated() { return dateCreated; }
    public void setDateCreated(LocalDateTime dateCreated) { this.dateCreated = dateCreated; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public List<PipelineField> getFields() { return fields; }
    public void setFields(List<PipelineField> fields) { this.fields = fields; }

    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }

    @Override
    public void setUpdatedBy(Long updatedBy) {
        this.updatedBy = updatedBy;
    }

    @Override
    public Long getUpdatedBy() {
        return updatedBy;
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

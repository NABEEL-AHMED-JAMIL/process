package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import process.model.enums.Status;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import javax.persistence.*;
import java.sql.Timestamp;

/**
 * What a step says to a model: system instructions, a message template with {{variables}},
 * the variables it declares (JSON: name, type, required, sample), and what it expects back
 * (text, or JSON against a schema). Versioned -- every save bumps `version` and writes an
 * {@link AiPromptVersion} snapshot, so a pipeline can pin the version it was saved with and a
 * run can say exactly what it ran. `promptUuid` is what a worker names.
 */
@Entity
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@Table(name = "ai_prompt")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@EntityListeners(AuditListener.class)
public class AiPrompt implements Audited {

    @GenericGenerator(name = "aiPromptSeq", strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = { @Parameter(name = "sequence_name", value = "ai_prompt_seq"),
                       @Parameter(name = "initial_value", value = "1000"), @Parameter(name = "increment_size", value = "1") })
    @Id @GeneratedValue(generator = "aiPromptSeq")
    @Column(name = "prompt_id") private Long promptId;
    public Long getPromptId() { return promptId; }
    public void setPromptId(Long promptId) { this.promptId = promptId; }

    @Column(name = "prompt_uuid", nullable = false, unique = true, length = 36) private String promptUuid;
    public String getPromptUuid() { return promptUuid; }
    public void setPromptUuid(String promptUuid) { this.promptUuid = promptUuid; }
    @Column(name = "tenant_id") private Long tenantId;
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    @Column(name = "name", nullable = false) private String name;
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    @Column(name = "description", columnDefinition = "TEXT") private String description;
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    @Column(name = "connection_id") private Long connectionId;
    public Long getConnectionId() { return connectionId; }
    public void setConnectionId(Long connectionId) { this.connectionId = connectionId; }
    @Column(name = "model") private String model;
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    @Column(name = "system_instructions", columnDefinition = "TEXT") private String systemInstructions;
    public String getSystemInstructions() { return systemInstructions; }
    public void setSystemInstructions(String systemInstructions) { this.systemInstructions = systemInstructions; }
    @Column(name = "user_template", nullable = false, columnDefinition = "TEXT") private String userTemplate;
    public String getUserTemplate() { return userTemplate; }
    public void setUserTemplate(String userTemplate) { this.userTemplate = userTemplate; }
    @Column(name = "variables", nullable = false, columnDefinition = "TEXT") private String variables;
    public String getVariables() { return variables; }
    public void setVariables(String variables) { this.variables = variables; }
    @Column(name = "output_mode", nullable = false) private String outputMode;
    public String getOutputMode() { return outputMode; }
    public void setOutputMode(String outputMode) { this.outputMode = outputMode; }
    @Column(name = "output_schema", columnDefinition = "TEXT") private String outputSchema;
    public String getOutputSchema() { return outputSchema; }
    public void setOutputSchema(String outputSchema) { this.outputSchema = outputSchema; }
    @Column(name = "temperature") private Double temperature;
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    @Column(name = "max_tokens") private Integer maxTokens;
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    @Column(name = "tags") private String tags;
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    @Column(name = "version", nullable = false) private Integer version;
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    @Column(name = "status", nullable = false) @Enumerated(EnumType.STRING) private Status status = Status.Inactive;
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    @Column(name = "date_created") private Timestamp dateCreated;
    public Timestamp getDateCreated() { return dateCreated; }
    public void setDateCreated(Timestamp dateCreated) { this.dateCreated = dateCreated; }

    @Transient private String createdByName;
    @Transient private String updatedByName;
    @Column(name = "created_by") private Long createdBy;
    @Column(name = "updated_by") private Long updatedBy;
    @Override public Long getCreatedBy() { return createdBy; }
    @Override public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    @Override public Long getUpdatedBy() { return updatedBy; }
    @Override public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    @Override public String getCreatedByName() { return createdByName; }
    @Override public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }
    @Override public String getUpdatedByName() { return updatedByName; }
    @Override public void setUpdatedByName(String updatedByName) { this.updatedByName = updatedByName; }
}

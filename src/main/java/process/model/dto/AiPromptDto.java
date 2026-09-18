package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/** A prompt as the console reads and writes it; variables as objects, not the stored JSON string. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiPromptDto {

    /** One declared placeholder. `sample` is what Try it runs with. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Variable {
        public String name;
        public String type;
        public Boolean required;
        public String sample;
        public String description;
    }

    private Long promptId;
    private String promptUuid;
    private Long tenantId;
    private String tenantName;
    private String name;
    private String description;
    private Long connectionId;
    private String connectionName;
    private String provider;
    private String model;
    /** The model a run would use: the prompt's own, else the connection's default. */
    private String effectiveModel;
    private String systemInstructions;
    private String userTemplate;
    private List<Variable> variables;
    private String outputMode;
    private String outputSchema;
    private Double temperature;
    private Integer maxTokens;
    private String tags;
    private Integer version;
    private String status;
    /** On save: true activates the prompt with this version. */
    private Boolean activate;
    private Long runCount;
    /** How many live pipelines run it as a step -- what keeps it from being deactivated or deleted. */
    private Long pipelineCount;
    private Timestamp lastRunAt;
    private String lastRunStatus;
    private Timestamp dateCreated;
    private Long createdBy;
    private String createdByName;
    private String updatedByName;
    /** For Try it: the values to render with (overrides the samples). */
    private Map<String, String> values;

    public Long getPromptId() { return promptId; } public void setPromptId(Long v) { promptId = v; }
    public String getPromptUuid() { return promptUuid; } public void setPromptUuid(String v) { promptUuid = v; }
    public Long getTenantId() { return tenantId; } public void setTenantId(Long v) { tenantId = v; }
    public String getTenantName() { return tenantName; } public void setTenantName(String v) { tenantName = v; }
    public String getName() { return name; } public void setName(String v) { name = v; }
    public String getDescription() { return description; } public void setDescription(String v) { description = v; }
    public Long getConnectionId() { return connectionId; } public void setConnectionId(Long v) { connectionId = v; }
    public String getConnectionName() { return connectionName; } public void setConnectionName(String v) { connectionName = v; }
    public String getProvider() { return provider; } public void setProvider(String v) { provider = v; }
    public String getModel() { return model; } public void setModel(String v) { model = v; }
    public String getEffectiveModel() { return effectiveModel; } public void setEffectiveModel(String v) { effectiveModel = v; }
    public String getSystemInstructions() { return systemInstructions; } public void setSystemInstructions(String v) { systemInstructions = v; }
    public String getUserTemplate() { return userTemplate; } public void setUserTemplate(String v) { userTemplate = v; }
    public List<Variable> getVariables() { return variables; } public void setVariables(List<Variable> v) { variables = v; }
    public String getOutputMode() { return outputMode; } public void setOutputMode(String v) { outputMode = v; }
    public String getOutputSchema() { return outputSchema; } public void setOutputSchema(String v) { outputSchema = v; }
    public Double getTemperature() { return temperature; } public void setTemperature(Double v) { temperature = v; }
    public Integer getMaxTokens() { return maxTokens; } public void setMaxTokens(Integer v) { maxTokens = v; }
    public String getTags() { return tags; } public void setTags(String v) { tags = v; }
    public Integer getVersion() { return version; } public void setVersion(Integer v) { version = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public Boolean getActivate() { return activate; } public void setActivate(Boolean v) { activate = v; }
    public Long getRunCount() { return runCount; } public void setRunCount(Long v) { runCount = v; }
    public Long getPipelineCount() { return pipelineCount; } public void setPipelineCount(Long v) { pipelineCount = v; }
    public Timestamp getLastRunAt() { return lastRunAt; } public void setLastRunAt(Timestamp v) { lastRunAt = v; }
    public String getLastRunStatus() { return lastRunStatus; } public void setLastRunStatus(String v) { lastRunStatus = v; }
    public Timestamp getDateCreated() { return dateCreated; } public void setDateCreated(Timestamp v) { dateCreated = v; }
    public Long getCreatedBy() { return createdBy; } public void setCreatedBy(Long v) { createdBy = v; }
    public String getCreatedByName() { return createdByName; } public void setCreatedByName(String v) { createdByName = v; }
    public String getUpdatedByName() { return updatedByName; } public void setUpdatedByName(String v) { updatedByName = v; }
    public Map<String, String> getValues() { return values; } public void setValues(Map<String, String> v) { values = v; }
}

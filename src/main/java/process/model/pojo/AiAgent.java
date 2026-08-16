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

@Entity
@Table(name = "ai_agent", indexes = {
    @Index(name = "idx_ai_agent_tenant_id", columnList = "tenant_id")
})
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = "long"))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiAgent {

    @GenericGenerator(
        name = "aiAgentSequenceGenerator",
        strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = {
            @Parameter(name = "sequence_name", value = "ai_agent_Seq"),
            @Parameter(name = "initial_value", value = "1000"),
            @Parameter(name = "increment_size", value = "1")
        }
    )
    @Id
    @Column(name = "ai_agent_id")
    @GeneratedValue(generator = "aiAgentSequenceGenerator")
    private Long aiAgentId;

    @Column(name = "tenant_id")
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id", insertable = false, updatable = false)
    private Tenant tenant;

    @Column(name = "agent_name", nullable = false)
    private String agentName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "provider", nullable = false)
    private String provider;

    @Column(name = "api_endpoint")
    private String apiEndpoint;

    @Column(name = "api_key", length = 1000)
    private String apiKey;

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "target_file_types", nullable = false)
    private String targetFileTypes;

    @Column(name = "instructions", columnDefinition = "TEXT", nullable = false)
    private String instructions;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "json_mode")
    private Boolean jsonMode = false;

    @Column(name = "date_created")
    private Timestamp dateCreated;

    @Column(name = "tool_uuid", unique = true, length = 36)
    private String toolUuid;

    public AiAgent() {}

    public Long getAiAgentId() {
        return aiAgentId;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public Tenant getTenant() {
        return tenant;
    }

    public void setAiAgentId(Long aiAgentId) {
        this.aiAgentId = aiAgentId;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getApiEndpoint() {
        return apiEndpoint;
    }

    public void setApiEndpoint(String apiEndpoint) {
        this.apiEndpoint = apiEndpoint;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getTargetFileTypes() {
        return targetFileTypes;
    }

    public void setTargetFileTypes(String targetFileTypes) {
        this.targetFileTypes = targetFileTypes;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Boolean getJsonMode() {
        return jsonMode;
    }

    public void setJsonMode(Boolean jsonMode) {
        this.jsonMode = jsonMode;
    }

    public Timestamp getDateCreated() {
        return dateCreated;
    }

    public void setDateCreated(Timestamp dateCreated) {
        this.dateCreated = dateCreated;
    }

    public String getToolUuid() {
        return toolUuid;
    }

    public void setToolUuid(String toolUuid) {
        this.toolUuid = toolUuid;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}

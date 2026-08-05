package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;
import process.model.enums.Status;
import java.sql.Timestamp;

/**
 * apiKey is write-only -- set it to save/rotate the key, but it is never populated when this
 * Dto is built from a saved AiAgent (see apiKeyConfigured instead, a plain boolean the UI can
 * use to show "configured" vs "not set" without ever exposing the secret itself, even encrypted).
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiAgentDto {

    private Long aiAgentId;
    private String agentName;
    private String description;
    private String provider;
    private String apiEndpoint;
    private String apiKey;
    private Boolean apiKeyConfigured;
    private String model;
    private String targetFileTypes;
    private String instructions;
    private Status status;
    private Boolean jsonMode;
    private Timestamp dateCreated;
    /** Read-only -- server-generated, never accepted from the client (applyAgentDto never
     * reads it). Used to build the public "Copy Tool URL" link on the frontend. */
    private String toolUuid;

    public AiAgentDto() {}

    public Long getAiAgentId() {
        return aiAgentId;
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

    public Boolean getApiKeyConfigured() {
        return apiKeyConfigured;
    }

    public void setApiKeyConfigured(Boolean apiKeyConfigured) {
        this.apiKeyConfigured = apiKeyConfigured;
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

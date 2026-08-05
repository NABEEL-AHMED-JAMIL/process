package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * Public-facing view of an AiAgent, returned to any external consumer (e.g. a Source Task's
 * XML "tool url") that fetches an agent's configuration by its toolUuid -- deliberately a
 * narrower field set than AiAgentDto: no apiKey and no apiEndpoint, since a consumer never
 * talks to the underlying provider directly. To actually run the agent, the consumer calls
 * AiAgentRestApi#processText with this toolUuid instead of an aiAgentId -- the apiKey stays
 * decrypted only in-memory on this server, exactly like the internal call path.
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiAgentToolDto {

    private String toolUuid;
    private String agentName;
    private String description;
    private String provider;
    private String model;
    /** The agent's saved system prompt / task description. */
    private String instructions;
    private Boolean jsonMode;
    private String targetFileTypes;

    public AiAgentToolDto() {}

    public String getToolUuid() {
        return toolUuid;
    }

    public void setToolUuid(String toolUuid) {
        this.toolUuid = toolUuid;
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

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public Boolean getJsonMode() {
        return jsonMode;
    }

    public void setJsonMode(Boolean jsonMode) {
        this.jsonMode = jsonMode;
    }

    public String getTargetFileTypes() {
        return targetFileTypes;
    }

    public void setTargetFileTypes(String targetFileTypes) {
        this.targetFileTypes = targetFileTypes;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }

}

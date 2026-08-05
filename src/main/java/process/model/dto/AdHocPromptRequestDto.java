package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.google.gson.Gson;

/**
 * Request body for AiAgentRestApi/processAdHoc -- a fully ad-hoc "provider+model+prompt+text"
 * call, not tied to any saved AiAgent. Nothing here is persisted; apiKey is used in-memory
 * for this single call only, same write-only treatment a saved agent's key already gets.
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown=true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdHocPromptRequestDto {

    private String provider;
    private String apiEndpoint;
    private String apiKey;
    private String model;
    private String instructions;
    private String text;
    private Boolean jsonMode;

    public AdHocPromptRequestDto() {}

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

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public Boolean getJsonMode() {
        return jsonMode;
    }

    public void setJsonMode(Boolean jsonMode) {
        this.jsonMode = jsonMode;
    }

    @Override
    public String toString() {
        return new Gson().toJson(this);
    }
}

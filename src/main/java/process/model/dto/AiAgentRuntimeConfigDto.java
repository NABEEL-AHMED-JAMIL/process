package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Internal-only carrier for {@link process.model.service.AiAgentService#resolveRuntimeConfig}
 * -- unlike {@link AiAgentDto} (which the frontend receives and which deliberately only ever
 * reports whether a key is configured, never the key itself), this one carries the real,
 * decrypted API key so FileChatServiceImpl can call the provider on the caller's behalf. It is
 * built fresh per request and never sent back to a client, so no toString() override is
 * provided here -- deliberately, to keep an accidental log statement from ever serializing the
 * key the way a Gson-based toString() would.
 *
 * @author Nabeel Ahmed
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiAgentRuntimeConfigDto {

    private String provider;
    private String apiEndpoint;
    private String apiKey;
    private String model;
    private Boolean jsonMode;

    public AiAgentRuntimeConfigDto() {
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

    public Boolean getJsonMode() {
        return jsonMode;
    }

    public void setJsonMode(Boolean jsonMode) {
        this.jsonMode = jsonMode;
    }
}

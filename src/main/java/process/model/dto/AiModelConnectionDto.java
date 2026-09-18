package process.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.sql.Timestamp;
import java.util.List;

/** A model connection as the console reads and writes it. The key only ever travels in. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiModelConnectionDto {
    private Long connectionId;
    private Long tenantId;
    private String tenantName;
    private String name;
    private String provider;
    private String apiEndpoint;
    /** In: a new key, blank to keep the stored one. Out: never. */
    private String apiKey;
    private Boolean apiKeyConfigured;
    private String defaultModel;
    private Boolean isDefault;
    private Integer maxConcurrency;
    private Long dailyTokenBudget;
    private String status;
    private Timestamp lastTestedAt;
    private Boolean lastTestOk;
    private String lastTestMessage;
    private List<String> models;
    private Long promptCount;
    private Long runs30d;
    private Long tokensIn30d;
    private Long tokensOut30d;
    private Long tokensToday;
    private Timestamp dateCreated;
    private Long createdBy;
    private String createdByName;
    private String updatedByName;

    public Long getConnectionId() { return connectionId; } public void setConnectionId(Long v) { connectionId = v; }
    public Long getTenantId() { return tenantId; } public void setTenantId(Long v) { tenantId = v; }
    public String getTenantName() { return tenantName; } public void setTenantName(String v) { tenantName = v; }
    public String getName() { return name; } public void setName(String v) { name = v; }
    public String getProvider() { return provider; } public void setProvider(String v) { provider = v; }
    public String getApiEndpoint() { return apiEndpoint; } public void setApiEndpoint(String v) { apiEndpoint = v; }
    public String getApiKey() { return apiKey; } public void setApiKey(String v) { apiKey = v; }
    public Boolean getApiKeyConfigured() { return apiKeyConfigured; } public void setApiKeyConfigured(Boolean v) { apiKeyConfigured = v; }
    public String getDefaultModel() { return defaultModel; } public void setDefaultModel(String v) { defaultModel = v; }
    public Boolean getIsDefault() { return isDefault; } public void setIsDefault(Boolean v) { isDefault = v; }
    public Integer getMaxConcurrency() { return maxConcurrency; } public void setMaxConcurrency(Integer v) { maxConcurrency = v; }
    public Long getDailyTokenBudget() { return dailyTokenBudget; } public void setDailyTokenBudget(Long v) { dailyTokenBudget = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public Timestamp getLastTestedAt() { return lastTestedAt; } public void setLastTestedAt(Timestamp v) { lastTestedAt = v; }
    public Boolean getLastTestOk() { return lastTestOk; } public void setLastTestOk(Boolean v) { lastTestOk = v; }
    public String getLastTestMessage() { return lastTestMessage; } public void setLastTestMessage(String v) { lastTestMessage = v; }
    public List<String> getModels() { return models; } public void setModels(List<String> v) { models = v; }
    public Long getPromptCount() { return promptCount; } public void setPromptCount(Long v) { promptCount = v; }
    public Long getRuns30d() { return runs30d; } public void setRuns30d(Long v) { runs30d = v; }
    public Long getTokensIn30d() { return tokensIn30d; } public void setTokensIn30d(Long v) { tokensIn30d = v; }
    public Long getTokensOut30d() { return tokensOut30d; } public void setTokensOut30d(Long v) { tokensOut30d = v; }
    public Long getTokensToday() { return tokensToday; } public void setTokensToday(Long v) { tokensToday = v; }
    public Timestamp getDateCreated() { return dateCreated; } public void setDateCreated(Timestamp v) { dateCreated = v; }
    public Long getCreatedBy() { return createdBy; } public void setCreatedBy(Long v) { createdBy = v; }
    public String getCreatedByName() { return createdByName; } public void setCreatedByName(String v) { createdByName = v; }
    public String getUpdatedByName() { return updatedByName; } public void setUpdatedByName(String v) { updatedByName = v; }
}

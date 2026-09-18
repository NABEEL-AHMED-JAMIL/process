package process.model.pojo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.Parameter;
import process.model.enums.Status;
import javax.persistence.*;
import java.sql.Timestamp;

/**
 * Where a prompt runs: provider, endpoint, encrypted key, default model, and the two caps
 * that keep a scheduled job from running up a bill -- how many calls may be in flight and
 * how many tokens a day the workspace may spend through it. One is the workspace default;
 * a prompt that names no connection runs on it.
 */
@Entity
@Table(name = "ai_model_connection")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@EntityListeners(AuditListener.class)
public class AiModelConnection implements Audited {

    @GenericGenerator(name = "aiModelConnectionSeq", strategy = "org.hibernate.id.enhanced.SequenceStyleGenerator",
        parameters = { @Parameter(name = "sequence_name", value = "ai_model_connection_seq"),
                       @Parameter(name = "initial_value", value = "1000"), @Parameter(name = "increment_size", value = "1") })
    @Id @GeneratedValue(generator = "aiModelConnectionSeq")
    @Column(name = "connection_id") private Long connectionId;
    public Long getConnectionId() { return connectionId; }
    public void setConnectionId(Long connectionId) { this.connectionId = connectionId; }

    @Column(name = "tenant_id") private Long tenantId;
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    @Column(name = "name", nullable = false) private String name;
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    @Column(name = "provider", nullable = false) private String provider;
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    @Column(name = "api_endpoint") private String apiEndpoint;
    public String getApiEndpoint() { return apiEndpoint; }
    public void setApiEndpoint(String apiEndpoint) { this.apiEndpoint = apiEndpoint; }
    @Column(name = "api_key", length = 1000) private String apiKey;
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    @Column(name = "default_model", nullable = false) private String defaultModel;
    public String getDefaultModel() { return defaultModel; }
    public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
    @Column(name = "is_default", nullable = false) private Boolean isDefault;
    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }
    @Column(name = "max_concurrency", nullable = false) private Integer maxConcurrency;
    public Integer getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(Integer maxConcurrency) { this.maxConcurrency = maxConcurrency; }
    @Column(name = "daily_token_budget") private Long dailyTokenBudget;
    public Long getDailyTokenBudget() { return dailyTokenBudget; }
    public void setDailyTokenBudget(Long dailyTokenBudget) { this.dailyTokenBudget = dailyTokenBudget; }
    @Column(name = "status", nullable = false) @Enumerated(EnumType.STRING) private Status status = Status.Active;
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    @Column(name = "last_tested_at") private Timestamp lastTestedAt;
    public Timestamp getLastTestedAt() { return lastTestedAt; }
    public void setLastTestedAt(Timestamp lastTestedAt) { this.lastTestedAt = lastTestedAt; }
    @Column(name = "last_test_ok") private Boolean lastTestOk;
    public Boolean getLastTestOk() { return lastTestOk; }
    public void setLastTestOk(Boolean lastTestOk) { this.lastTestOk = lastTestOk; }
    @Column(name = "last_test_message", columnDefinition = "TEXT") private String lastTestMessage;
    public String getLastTestMessage() { return lastTestMessage; }
    public void setLastTestMessage(String lastTestMessage) { this.lastTestMessage = lastTestMessage; }
    @Column(name = "models_listed", columnDefinition = "TEXT") private String modelsListed;
    public String getModelsListed() { return modelsListed; }
    public void setModelsListed(String modelsListed) { this.modelsListed = modelsListed; }
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

package process.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One configuration entry as the console sees it, and what it sends (MIG-167).
 *
 * {@code value} carries a VALUE both ways. For a SECRET it only ever travels IN -- a new secret, or a replacement --
 * and is never set on an answer: the answer says {@code secretSet} and when and by whom. There is no field for the
 * sealed form. toString names the key only.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PipelineConfigDto {

    private Long id;
    private Long tenantId;
    private String key;
    private String kind;
    private String value;
    private Boolean secretSet;
    private String description;
    private String setAt;
    private String setByName;
    private String createdAt;
    private String createdByName;
    private Long usedByTasks;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public Boolean getSecretSet() { return secretSet; }
    public void setSecretSet(Boolean secretSet) { this.secretSet = secretSet; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getSetAt() { return setAt; }
    public void setSetAt(String setAt) { this.setAt = setAt; }
    public String getSetByName() { return setByName; }
    public void setSetByName(String setByName) { this.setByName = setByName; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }
    public Long getUsedByTasks() { return usedByTasks; }
    public void setUsedByTasks(Long usedByTasks) { this.usedByTasks = usedByTasks; }

    @Override
    public String toString() {
        return "PipelineConfigDto{id=" + this.id + ", key=" + this.key + ", kind=" + this.kind + "}";
    }
}

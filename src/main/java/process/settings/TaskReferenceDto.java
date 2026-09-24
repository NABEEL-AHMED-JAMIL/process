package process.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** A home page or task group as the console sees it, and what it sends (MIG-167). */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskReferenceDto {

    private Long id;
    private Long tenantId;
    private String kind;
    private String name;
    private String value;
    private String description;
    private String createdAt;
    private String createdByName;
    private String updatedAt;
    private String updatedByName;
    private Long usedByTasks;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getCreatedByName() { return createdByName; }
    public void setCreatedByName(String createdByName) { this.createdByName = createdByName; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    public String getUpdatedByName() { return updatedByName; }
    public void setUpdatedByName(String updatedByName) { this.updatedByName = updatedByName; }
    public Long getUsedByTasks() { return usedByTasks; }
    public void setUsedByTasks(Long usedByTasks) { this.usedByTasks = usedByTasks; }
}
